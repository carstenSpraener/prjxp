package de.spraener.prjxp.lucene;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsNotEqualTo;
import dev.langchain4j.store.embedding.filter.logical.And;
import dev.langchain4j.store.embedding.filter.logical.Not;
import dev.langchain4j.store.embedding.filter.logical.Or;
import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;
import de.spraener.prjxp.common.model.FileListResult;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.store.PxChunkDao;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.index.PostingsEnum;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.Terms;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.WildcardQuery;
import org.apache.lucene.util.automaton.CompiledAutomaton;
import org.apache.lucene.util.automaton.Operations;
import org.apache.lucene.util.BytesRef;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class LucenePxChunkDao implements PxChunkDao {
    /** mxbai-embed-large-v1 retrieval prefix (see the model card). */
    public static final String DEFAULT_QUERY_PROMPT =
            "Represent this sentence for searching relevant passages: ";

    private final EmbeddingStore<TextSegment> store;
    private final EmbeddingModel embeddingModel;
    private final PrjXPEmbeddingStoreReference storeReference;
    private final LuceneEmbeddingStore luceneStore;
    private final String queryPrompt;

    public LucenePxChunkDao(EmbeddingStore<TextSegment> store, EmbeddingModel embeddingModel,
                             PrjXPEmbeddingStoreReference storeReference) {
        this(store, embeddingModel, storeReference, DEFAULT_QUERY_PROMPT);
    }

    public LucenePxChunkDao(EmbeddingStore<TextSegment> store, EmbeddingModel embeddingModel,
                             PrjXPEmbeddingStoreReference storeReference, String queryPrompt) {
        this.store = store;
        this.embeddingModel = embeddingModel;
        this.storeReference = storeReference;
        this.luceneStore = store instanceof LuceneEmbeddingStore ? (LuceneEmbeddingStore) store : null;
        this.queryPrompt = queryPrompt == null ? "" : queryPrompt;
    }

    private Map<String, String> scopedFilters(Map<String, String> filters) {
        Map<String, String> scoped = new HashMap<>(filters == null ? Map.of() : filters);
        scoped.put(PxChunk.PXCHUNK_PROJECT, storeReference.getProjectName());
        return scoped;
    }

    private Filter projectFilter() {
        return new IsEqualTo(PxChunk.PXCHUNK_PROJECT, storeReference.getProjectName());
    }

    @Override
    public PrjXPEmbeddingStoreReference getStoreReference() {
        return storeReference;
    }

    @Override
    public List<PxChunk> findById(String id) {
        Filter filter = new And(new IsEqualTo(PxChunk.PXCHUNK_ID, id), projectFilter());
        return searchWithFilter(filter);
    }

    @Override
    public List<PxChunk> findByMetaData(Map<String, String> metaData) {
        if (metaData == null || metaData.isEmpty()) {
            return new ArrayList<>();
        }

        Filter combinedFilter = projectFilter();
        for (Map.Entry<String, String> entry : metaData.entrySet()) {
            Filter currentFilter = new IsEqualTo(entry.getKey(), entry.getValue());
            combinedFilter = (combinedFilter == null) ? currentFilter : new And(combinedFilter, currentFilter);
        }
        return searchWithFilter(combinedFilter);
    }

    @Override
    public List<PxChunk> findRelevant(String question, int maxResults, double minScore) {
        Embedding questionEmbedding = embeddingModel.embed(question).content();
        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(questionEmbedding)
                .filter(projectFilter())
                .maxResults(maxResults)
                .minScore(minScore)
                .build();

        EmbeddingSearchResult<TextSegment> searchResult = store.search(searchRequest);
        return searchResult.matches().stream()
                .map(this::toPxChunk)
                .collect(Collectors.toList());
    }

    @Override
    public Stream<PxChunk> findAll() {
        if (luceneStore != null) {
            return searchAllLucene().stream();
        }
        return searchWithFilter(projectFilter()).stream();
    }

    @Override
    public List<ScoredChunk> searchFullText(String query, Map<String, String> filters, int limit) {
        if (luceneStore == null) {
            throw new UnsupportedOperationException("Full-text search requires a Lucene store");
        }

        List<String> tokens = tokenize(query);
        if (tokens.isEmpty()) {
            return new ArrayList<>();
        }

        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (String token : tokens) {
            builder.add(new TermQuery(new Term("content", token)), BooleanClause.Occur.MUST);
        }
        for (Map.Entry<String, String> entry : scopedFilters(filters).entrySet()) {
            addFilterTerm(builder, entry.getKey(), entry.getValue());
        }

        try {
            IndexSearcher searcher = luceneStore.getSearcher();
            try {
                TopDocs topDocs = searcher.search(builder.build(), limit);
                List<ScoredChunk> result = new ArrayList<>();
                for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                    org.apache.lucene.document.Document doc = searcher.storedFields().document(scoreDoc.doc);
                    result.add(new ScoredChunk(extractPxChunk(doc), scoreDoc.score));
                }
                return result;
            } finally {
                luceneStore.releaseSearcher(searcher);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to full-text search", e);
        }
    }

    private static final double INDEX_SCORE = 1.0;

    /** Analyzed content field; the only field BM25 scores. */
    private static final String CONTENT_FIELD = "content";

    /** Upper bound for the file-name boost clauses; keeps the query small and predictable. */
    private static final int NAME_BOOST_LIMIT = 50;

    /**
     * {@code limit <= 0} means "all matches" — {@code grep} answers "does this occur?", which a
     * ranked window cannot: a chunk ranked below the window would silently drop its file. The
     * hard cap keeps a pathological query from loading the whole index into memory.
     */
    private static final int CANDIDATE_CAP = 5000;

    @Override
    public List<ScoredChunk> searchCandidates(String query, Map<String, String> filters, int limit) {
        if (luceneStore == null) {
            throw new UnsupportedOperationException("Candidate search requires a Lucene store");
        }
        List<String> tokens = tokenize(query);
        int effectiveLimit = limit <= 0 ? CANDIDATE_CAP : Math.min(limit, CANDIDATE_CAP);
        if (tokens.isEmpty() || effectiveLimit <= 0) {
            return new ArrayList<>();
        }
        // Stage 1: prefix of each token. Covers identifiers ("Preload" -> PreloadEventListener)
        // without paying for a term-dictionary walk.
        List<ScoredChunk> hits = execute(candidateQuery(tokens, false, filters), effectiveLimit, "candidates");
        if (hits.size() >= effectiveLimit) {
            return hits;
        }
        // Stage 2: the token may sit mid-word ("Listener" -> PreloadEventListener). Widening is
        // only paid when stage 1 could not fill the window.
        List<ScoredChunk> widened = execute(candidateQuery(tokens, true, filters), effectiveLimit, "candidates");
        return mergeById(hits, widened, effectiveLimit);
    }

    @Override
    public List<ScoredChunk> searchAnyTerms(List<String> terms, Map<String, String> filters, int limit) {
        if (luceneStore == null) {
            throw new UnsupportedOperationException("Any-term search requires a Lucene store");
        }
        List<String> clean = terms == null ? List.of() : terms.stream()
                .filter(term -> term != null && !term.isBlank())
                .map(String::trim)
                .toList();
        if (clean.isEmpty()) {
            return new ArrayList<>();
        }
        int effectiveLimit = limit <= 0 ? CANDIDATE_CAP : Math.min(limit, CANDIDATE_CAP);
        if (effectiveLimit <= 0) {
            return new ArrayList<>();
        }
        // Stage 1: prefix of each term, OR-ed. Covers identifiers without a term-dictionary walk.
        List<ScoredChunk> hits = execute(anyTermQuery(clean, false, filters), effectiveLimit, "any-terms");
        if (hits.size() >= effectiveLimit) {
            return hits;
        }
        // Stage 2: the term may sit mid-word. Widening is only paid when stage 1 could not fill the window.
        List<ScoredChunk> widened = execute(anyTermQuery(clean, true, filters), effectiveLimit, "any-terms");
        return mergeById(hits, widened, effectiveLimit);
    }

    /**
     * OR over the terms: the inner query requires at least one content match (SHOULD per term +
     * {@code minimumNumberShouldMatch(1)}); the outer query wraps it as MUST so that the name
     * boost (SHOULD) and the filters can never satisfy the minimum condition — they only rank.
     */
    private Query anyTermQuery(List<String> terms, boolean contains, Map<String, String> filters) {
        BooleanQuery.Builder inner = new BooleanQuery.Builder();
        for (String term : terms) {
            Query clause = contains
                    ? new WildcardQuery(new Term(CONTENT_FIELD, "*" + term + "*"))
                    : new PrefixQuery(new Term(CONTENT_FIELD, term));
            inner.add(clause, BooleanClause.Occur.SHOULD);
        }
        inner.setMinimumNumberShouldMatch(1);
        BooleanQuery.Builder outer = new BooleanQuery.Builder();
        outer.add(inner.build(), BooleanClause.Occur.MUST);
        addNameBoost(outer, terms);
        for (Map.Entry<String, String> entry : scopedFilters(filters).entrySet()) {
            addFilterTerm(outer, entry.getKey(), entry.getValue());
        }
        return outer.build();
    }

    @Override
    public List<String> findFilesContaining(String token, int limit) {
        if (luceneStore == null) {
            throw new UnsupportedOperationException("File name lookup requires a Lucene store");
        }
        String needle = token == null ? "" : token.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty() || limit <= 0) {
            return List.of();
        }
        try {
            IndexSearcher searcher = luceneStore.getSearcher();
            try {
                IndexReader reader = searcher.getIndexReader();
                Terms terms = MultiTerms.getTerms(reader, PxChunk.PXCHUNK_FILE);
                if (terms == null) {
                    return List.of();
                }
                BitSet projectDocs = projectDocs(searcher, reader.maxDoc());
                List<String> nameMatches = new ArrayList<>();
                List<String> pathMatches = new ArrayList<>();
                TermsEnum termsEnum = terms.iterator();
                for (BytesRef term = termsEnum.next(); term != null; term = termsEnum.next()) {
                    String file = term.utf8ToString();
                    String lower = file.toLowerCase(Locale.ROOT);
                    if (!lower.contains(needle) || !inProject(termsEnum, projectDocs)) {
                        continue;
                    }
                    if (baseName(lower).contains(needle)) {
                        nameMatches.add(file);
                    } else {
                        pathMatches.add(file);   // a directory segment matched, not the file name
                    }
                }
                nameMatches.sort(Comparator.naturalOrder());
                pathMatches.sort(Comparator.naturalOrder());
                List<String> ordered = new ArrayList<>(nameMatches);
                ordered.addAll(pathMatches);
                return ordered.size() > limit ? new ArrayList<>(ordered.subList(0, limit)) : ordered;
            } finally {
                luceneStore.releaseSearcher(searcher);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to lookup files by name", e);
        }
    }

    private Query candidateQuery(List<String> tokens, boolean contains, Map<String, String> filters) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (String token : tokens) {
            Query clause = contains
                    ? new WildcardQuery(new Term(CONTENT_FIELD, "*" + token + "*"))
                    : new PrefixQuery(new Term(CONTENT_FIELD, token));
            builder.add(clause, BooleanClause.Occur.MUST);
        }
        addNameBoost(builder, tokens);
        for (Map.Entry<String, String> entry : scopedFilters(filters).entrySet()) {
            addFilterTerm(builder, entry.getKey(), entry.getValue());
        }
        return builder.build();
    }

    /**
     * A SHOULD clause per file whose name contains a query token. The chunks of those files match
     * the content query as well (the identifier is part of the text), so the boost only lifts them
     * inside the window — it can never introduce a chunk whose line check would fail.
     */
    private void addNameBoost(BooleanQuery.Builder builder, List<String> tokens) {
        LinkedHashSet<String> files = new LinkedHashSet<>();
        for (String token : tokens) {
            files.addAll(findFilesContaining(token, NAME_BOOST_LIMIT));
            if (files.size() >= NAME_BOOST_LIMIT) {
                break;
            }
        }
        for (String file : files) {
            builder.add(new TermQuery(new Term(PxChunk.PXCHUNK_FILE, file)), BooleanClause.Occur.SHOULD);
        }
    }

    private List<ScoredChunk> execute(Query query, int limit, String what) {
        try {
            IndexSearcher searcher = luceneStore.getSearcher();
            try {
                TopDocs topDocs = searcher.search(query, limit);
                List<ScoredChunk> result = new ArrayList<>();
                for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                    org.apache.lucene.document.Document doc = searcher.storedFields().document(scoreDoc.doc);
                    result.add(new ScoredChunk(extractPxChunk(doc), scoreDoc.score));
                }
                return result;
            } finally {
                luceneStore.releaseSearcher(searcher);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to search " + what, e);
        }
    }

    private static List<ScoredChunk> mergeById(List<ScoredChunk> first, List<ScoredChunk> second, int limit) {
        Map<String, ScoredChunk> byId = new LinkedHashMap<>();
        for (ScoredChunk chunk : first) {
            addById(byId, chunk, limit);
        }
        for (ScoredChunk chunk : second) {
            addById(byId, chunk, limit);
        }
        return new ArrayList<>(byId.values());
    }

    private static void addById(Map<String, ScoredChunk> byId, ScoredChunk chunk, int limit) {
        if (byId.size() >= limit) {
            return;
        }
        String id = chunk.chunk() == null ? null : chunk.chunk().getId();
        if (id == null) {
            return;
        }
        byId.putIfAbsent(id, chunk);
    }

    /** Last path segment; the part an identifier query usually names. */
    private static String baseName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }


    @Override
    public List<ScoredChunk> searchByIndex(Map<String, String> filters, int limit) {
        if (luceneStore == null) {
            throw new UnsupportedOperationException("Index search requires a Lucene store");
        }

        Query query = buildFilterQuery(scopedFilters(filters));
        try {
            IndexSearcher searcher = luceneStore.getSearcher();
            try {
                TopDocs topDocs = searcher.search(query, limit);
                List<ScoredChunk> result = new ArrayList<>();
                for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                    org.apache.lucene.document.Document doc = searcher.storedFields().document(scoreDoc.doc);
                    result.add(new ScoredChunk(extractPxChunk(doc), INDEX_SCORE));
                }
                return result;
            } finally {
                luceneStore.releaseSearcher(searcher);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to search by index", e);
        }
    }

    @Override
    public List<ScoredChunk> searchVector(String query, Map<String, String> filters, int limit) {
        if (query == null || query.isBlank()) {
            return new ArrayList<>();
        }

        // Retrieval-only prompt: document embeddings stay untouched (model-card contract).
        Embedding questionEmbedding = embeddingModel.embed(queryPrompt + query).content();
        Filter filter = buildFilter(scopedFilters(filters));

        EmbeddingSearchRequest searchRequest = EmbeddingSearchRequest.builder()
                .queryEmbedding(questionEmbedding)
                .filter(filter)
                .maxResults(limit)
                .build();

        return store.search(searchRequest).matches().stream()
                .map(match -> new ScoredChunk(toPxChunk(match), match.score()))
                .collect(Collectors.toList());
    }

    @Override
    public FileListResult listFiles(String pattern, int limit) {
        if (luceneStore == null) {
            throw new UnsupportedOperationException("File listing requires a Lucene store");
        }

        String normalized = pattern == null ? "" : pattern.trim();
        // A trailing '/' lists a directory like `ls`: its direct entries — files and
        // subdirectories — instead of every file below it. Models reached for '*/' to get
        // their bearings, and answering "No files found" left them looping.
        boolean listing = normalized.endsWith("/");
        int level = listing ? listingLevel(normalized) : 0;
        String glob = listing ? listingGlob(normalized) : toGlob(normalized);

        try {
            IndexSearcher searcher = luceneStore.getSearcher();
            try {
                IndexReader reader = searcher.getIndexReader();
                BitSet projectDocs = projectDocs(searcher, reader.maxDoc());
                LinkedHashSet<String> entries = new LinkedHashSet<>();
                // The path field is indexed untokenized: one term per file. Walking the term
                // dictionary is sorted by construction and stops at the limit, so it never
                // reads documents (the earlier stored-field scan needed a full index pass).
                TermsEnum terms = candidateTerms(reader, glob);
                for (BytesRef term = terms.next(); term != null; term = terms.next()) {
                    if (!inProject(terms, projectDocs)) {
                        continue;
                    }
                    String file = term.utf8ToString();
                    if (listing) {
                        addListingEntries(file, level, entries);
                    } else {
                        entries.add(file);
                    }
                    if (entries.size() > limit) {
                        break;
                    }
                }
                List<String> sorted = entries.stream().sorted().toList();
                return new FileListResult(sorted.stream().limit(limit).toList(), sorted.size() > limit);
            } finally {
                luceneStore.releaseSearcher(searcher);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to list files", e);
        }
    }

    /** Doc ids of the requested project, so unrelated projects cannot consume the limit. */
    private BitSet projectDocs(IndexSearcher searcher, int maxDoc) throws IOException {
        BitSet docs = new BitSet(maxDoc);
        Term project = new Term(PxChunk.PXCHUNK_PROJECT, storeReference.getProjectName());
        for (LeafReaderContext leaf : searcher.getIndexReader().leaves()) {
            PostingsEnum postings = leaf.reader().postings(project);
            if (postings == null) {
                continue;
            }
            for (int doc = postings.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = postings.nextDoc()) {
                docs.set(leaf.docBase + doc);
            }
        }
        return docs;
    }

    /** Terms of the file field matching {@code glob}, in sorted term order. */
    private TermsEnum candidateTerms(IndexReader reader, String glob) throws IOException {
        Terms terms = MultiTerms.getTerms(reader, PxChunk.PXCHUNK_FILE);
        if (terms == null) {
            return TermsEnum.EMPTY;
        }
        // The automaton path handles prefixes and wildcards alike and yields terms in
        // dictionary order, so the limit can stop the walk early.
        CompiledAutomaton automaton = new CompiledAutomaton(
                WildcardQuery.toAutomaton(new Term(PxChunk.PXCHUNK_FILE, glob),
                        Operations.DEFAULT_DETERMINIZE_WORK_LIMIT));
        return automaton.getTermsEnum(terms);
    }

    /** Whether the term under {@code terms} occurs in at least one of {@code projectDocs}. */
    private boolean inProject(TermsEnum terms, BitSet projectDocs) throws IOException {
        PostingsEnum postings = terms.postings(null, PostingsEnum.NONE);
        if (postings == null) {
            return false;
        }
        for (int doc = postings.nextDoc(); doc != DocIdSetIterator.NO_MORE_DOCS; doc = postings.nextDoc()) {
            if (projectDocs.get(doc)) {
                return true;
            }
        }
        return false;
    }

    /** Entries of {@code file} at {@code level}: the directory holding it or the file itself. */
    private void addListingEntries(String file, int level, LinkedHashSet<String> entries) {
        int slashCount = 0;
        for (int i = 0; i < file.length(); i++) {
            if (file.charAt(i) == '/') {
                slashCount++;
            }
        }
        int fileLevel = slashCount + 1;   // segments of the file path
        if (fileLevel == level) {
            entries.add(file);
        } else if (fileLevel > level) {
            String dir = directoryOf(file, level);
            if (dir != null) {
                entries.add(dir);
            }
        }
    }

    /**
     * Level (1 = directly inside the root) a trailing-slash pattern lists. The pattern names
     * the directory like {@code ls} does, so its entries live one level below: '&#42;/' and '/' address
     * the root (level 1), 'src/' addresses level 2, '&#42;/impl/' level 3.
     */
    static int listingLevel(String pattern) {
        String base = pattern.substring(0, pattern.length() - 1).trim();
        if (base.isEmpty() || "*".equals(base) || "**".equals(base) || "?".equals(base)) {
            return 1;
        }
        return (int) base.chars().filter(c -> c == '/').count() + 2;
    }

    /** Directory pattern -> Lucene glob over the files below it ('&#42;/' and '/' = everything). */
    static String listingGlob(String pattern) {
        String base = pattern.substring(0, pattern.length() - 1).trim();
        return listingLevel(pattern) == 1 ? "*" : base + "/*";
    }

    /** {@code depth}-th path segment of {@code file} including its trailing '/', or null. */
    static String directoryOf(String file, int depth) {
        int idx = -1;
        for (int i = 0; i < depth; i++) {
            idx = file.indexOf('/', idx + 1);
            if (idx < 0) {
                return null;
            }
        }
        return file.substring(0, idx + 1);
    }

    /** Pattern -> Lucene glob: blank = everything; no wildcard chars = prefix; else as-is. '*' spans '/'. */
    static String toGlob(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return "*";
        }
        String p = pattern.trim();
        return (p.contains("*") || p.contains("?")) ? p : p + "*";
    }

    /**
     * Combines the filter map into one LangChain4j filter (AND of all entries).
     * Value syntax per entry: "a" = equals; "!a" = not equals; "a|b" = a OR b; "!a|b" = NOT (a OR b).
     */
    private Filter buildFilter(Map<String, String> filters) {
        if (filters == null || filters.isEmpty()) {
            return null;
        }
        Filter combinedFilter = null;
        for (Map.Entry<String, String> entry : filters.entrySet()) {
            String key = PxChunk.metadataFieldKey(entry.getKey());
            Filter currentFilter = filterFor(key, entry.getValue());
            combinedFilter = (combinedFilter == null) ? currentFilter : new And(combinedFilter, currentFilter);
        }
        return combinedFilter;
    }

    /** "a" = equals; "!a" = not equals; "a|b" = a OR b; "!a|b" = NOT (a OR b). */
    private static Filter filterFor(String key, String spec) {
        boolean negated = spec.startsWith("!");
        String values = negated ? spec.substring(1) : spec;
        if (!values.contains("|")) {
            return negated ? new IsNotEqualTo(key, values) : new IsEqualTo(key, values);
        }
        Filter or = null;
        for (String value : values.split("\\|")) {
            Filter equalTo = new IsEqualTo(key, value);
            or = (or == null) ? equalTo : new Or(or, equalTo);
        }
        return negated ? new Not(or) : or;
    }

    private Query buildFilterQuery(Map<String, String> filters) {
        if (filters == null || filters.isEmpty()) {
            return new org.apache.lucene.search.MatchAllDocsQuery();
        }
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (Map.Entry<String, String> entry : filters.entrySet()) {
            addFilterTerm(builder, PxChunk.metadataFieldKey(entry.getKey()), entry.getValue());
        }
        return builder.build();
    }

    /**
     * Lucene-Übersetzung des Filter-Vertrags:
     * "a" = Term, "!a" = NOT Term, "a|b" = OR, "!a|b" = NOT (a OR b).
     * Der Aufrufer entscheidet, ob das Ergebnis FILTER oder MUST_NOT wird.
     * Liefert null, wenn nach dem Trimmen keine Werte übrig sind (keine Klausel).
     */
    private static Query filterQuery(String key, String value) {
        boolean negated = value.startsWith("!");
        String values = negated ? value.substring(1) : value;
        if (!values.contains("|")) {
            String trimmed = values.trim();
            return trimmed.isEmpty() ? null : new TermQuery(new Term(key, trimmed));
        }
        BooleanQuery.Builder or = new BooleanQuery.Builder();
        boolean hasClause = false;
        for (String part : values.split("\\|")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                or.add(new TermQuery(new Term(key, trimmed)), BooleanClause.Occur.SHOULD);
                hasClause = true;
            }
        }
        if (!hasClause) {
            return null;
        }
        or.setMinimumNumberShouldMatch(1);
        return or.build();
    }

    /** A "!"-prefixed value is a negation (MUST_NOT), otherwise a plain FILTER clause. */
    private static void addFilterTerm(BooleanQuery.Builder builder, String key, String value) {
        Query query = filterQuery(key, value);
        if (query == null) {
            return;
        }
        builder.add(query, value.startsWith("!")
                ? BooleanClause.Occur.MUST_NOT
                : BooleanClause.Occur.FILTER);
    }

    private List<String> tokenize(String query) {
        StandardAnalyzer analyzer = new StandardAnalyzer();
        try (TokenStream stream = analyzer.tokenStream("content", query)) {
            CharTermAttribute termAttribute = stream.addAttribute(CharTermAttribute.class);
            List<String> tokens = new ArrayList<>();
            stream.reset();
            while (stream.incrementToken()) {
                String token = termAttribute.toString();
                if (!tokens.contains(token)) {
                    tokens.add(token);
                }
            }
            stream.end();
            return tokens;
        } catch (IOException e) {
            throw new RuntimeException("Failed to tokenize query", e);
        }
    }

    private List<PxChunk> searchAllLucene() {
        try {
            Query luceneQuery = LuceneFilterConverter.convert(projectFilter());
            IndexSearcher searcher = luceneStore.getSearcher();
            try {
                // The scan must never be silently truncated: the old fixed 10_000 cap made
                // findAll() return a prefix of the index for corpora above that size, so
                // callers (e.g. the P2 grep line scan) searched only part of the project.
                TopDocs topDocs = searcher.search(luceneQuery,
                        Math.max(1, searcher.getIndexReader().numDocs()));
                List<PxChunk> result = new ArrayList<>();
                for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                    org.apache.lucene.document.Document doc = searcher.storedFields().document(scoreDoc.doc);
                    result.add(extractPxChunk(doc));
                }
                return result;
            } finally {
                luceneStore.releaseSearcher(searcher);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to search all documents", e);
        }
    }

    private List<PxChunk> searchWithFilter(Filter filter) {
        if (luceneStore != null && filter != null) {
            return searchWithLuceneFilter(filter);
        }

        Embedding dummyEmbedding = Embedding.from(new float[store instanceof LuceneEmbeddingStore
                ? ((LuceneEmbeddingStore) store).getVectorDimension()
                : 1024]);

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(dummyEmbedding)
                .filter(filter)
                .maxResults(100)
                .build();

        return store.search(request).matches().stream()
                .map(this::toPxChunk)
                .collect(Collectors.toList());
    }

    private List<PxChunk> searchWithLuceneFilter(Filter filter) {
        try {
            Query luceneQuery = LuceneFilterConverter.convert(filter);
            IndexSearcher searcher = luceneStore.getSearcher();
            try {
                TopDocs topDocs = searcher.search(luceneQuery, 100);
                List<PxChunk> result = new ArrayList<>();
                for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                    org.apache.lucene.document.Document doc = searcher.storedFields().document(scoreDoc.doc);
                    result.add(extractPxChunk(doc));
                }
                return result;
            } finally {
                luceneStore.releaseSearcher(searcher);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to search with filter", e);
        }
    }

    private PxChunk toPxChunk(EmbeddingMatch<TextSegment> match) {
        TextSegment segment = match.embedded();
        return PxChunk.fromContentAndMap(segment.text(), segment.metadata().toMap());
    }

    private PxChunk extractPxChunk(org.apache.lucene.document.Document doc) {
        String content = doc.get("content");
        if (content == null) {
            content = "";
        }
        Map<String, Object> metadata = new HashMap<>();
        for (org.apache.lucene.index.IndexableField field : doc.getFields()) {
            String name = field.name();
            if (name.equals("_id") || name.equals("content") || name.equals("vector")) {
                continue;
            }
            String value = field.stringValue();
            if (value != null) {
                metadata.put(name, value);
            }
        }
        return PxChunk.fromContentAndMap(content, metadata);
    }
}
