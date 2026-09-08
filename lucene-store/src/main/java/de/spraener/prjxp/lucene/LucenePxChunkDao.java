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
import dev.langchain4j.store.embedding.filter.logical.And;
import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.ScoredChunk;
import de.spraener.prjxp.common.store.PxChunkDao;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class LucenePxChunkDao implements PxChunkDao {
    private final EmbeddingStore<TextSegment> store;
    private final EmbeddingModel embeddingModel;
    private final PrjXPEmbeddingStoreReference storeReference;
    private final LuceneEmbeddingStore luceneStore;

    public LucenePxChunkDao(EmbeddingStore<TextSegment> store, EmbeddingModel embeddingModel,
                             PrjXPEmbeddingStoreReference storeReference) {
        this.store = store;
        this.embeddingModel = embeddingModel;
        this.storeReference = storeReference;
        this.luceneStore = store instanceof LuceneEmbeddingStore ? (LuceneEmbeddingStore) store : null;
    }

    @Override
    public PrjXPEmbeddingStoreReference getStoreReference() {
        return storeReference;
    }

    @Override
    public List<PxChunk> findById(String id) {
        Filter filter = new IsEqualTo(PxChunk.PXCHUNK_ID, id);
        return searchWithFilter(filter);
    }

    @Override
    public List<PxChunk> findByMetaData(Map<String, String> metaData) {
        if (metaData == null || metaData.isEmpty()) {
            return new ArrayList<>();
        }

        Filter combinedFilter = null;
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
        return searchWithFilter(null).stream();
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
        if (filters != null) {
            for (Map.Entry<String, String> entry : filters.entrySet()) {
                builder.add(new TermQuery(new Term(entry.getKey(), entry.getValue())), BooleanClause.Occur.FILTER);
            }
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
            Query matchAll = new org.apache.lucene.search.MatchAllDocsQuery();
            IndexSearcher searcher = luceneStore.getSearcher();
            try {
                TopDocs topDocs = searcher.search(matchAll, 10000);
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
