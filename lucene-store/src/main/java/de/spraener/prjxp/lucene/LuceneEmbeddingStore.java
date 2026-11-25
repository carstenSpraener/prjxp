package de.spraener.prjxp.lucene;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import de.spraener.prjxp.common.model.ChunkPathPreference;
import de.spraener.prjxp.common.model.PxChunk;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.FloatVectorValues;
import org.apache.lucene.index.KnnVectorValues;
import org.apache.lucene.index.LeafReader;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.Weight;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.NIOFSDirectory;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.FixedBitSet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import lombok.extern.java.Log;
import java.util.stream.Collectors;

@Log
public class LuceneEmbeddingStore implements EmbeddingStore<TextSegment>, org.springframework.beans.factory.DisposableBean {
    private static final String VECTOR_FIELD = "vector";
    private static final String ID_FIELD = "_id";
    private static final String SEMANTIC_NODE_ID_FIELD =
            PxChunk.PXCHUNK_METADATA + ".semantic_node_id";

    private final Path indexPath;
    private final int vectorDimension;
    private IndexWriter writer;
    private SearcherManager searcherManager;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final String name;

    public LuceneEmbeddingStore(Path indexPath, int vectorDimension, String name) {
        this.indexPath = indexPath;
        this.vectorDimension = vectorDimension;
        this.name = name;
    }
    public LuceneEmbeddingStore(Path indexPath, int vectorDimension) {
        this(indexPath, vectorDimension, "prjxp");
    }

    private void ensureOpen() {
        lock.writeLock().lock();
        try {
            if (writer == null) {
                NIOFSDirectory directory = new NIOFSDirectory(indexPath);
                IndexWriterConfig config = new IndexWriterConfig();
                try {
                    writer = new IndexWriter(directory, config);
                } catch (org.apache.lucene.store.LockObtainFailedException e) {
                    // flock is released by the OS when a process dies, so a stale lock file is
                    // never the real cause — the lock is held by another LIVE writer. Deleting
                    // write.lock here would let a second writer open the same index and corrupt
                    // it, so we fail fast instead.
                    throw new IllegalStateException(
                            "Lucene index at " + indexPath + " is locked by another process. "
                                    + "Stop the other process (a running mcp-server / tibed / golden-retriever, "
                                    + "or a previous instance) and try again.", e);
                }
                try {
                    searcherManager = new SearcherManager(writer, null);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to create SearcherManager", e);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to open Lucene index at " + indexPath, e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public String add(Embedding embedding) {
        validateDimension(embedding);
        String id = UUID.randomUUID().toString();
        addInternal(id, embedding, null);
        return id;
    }

    @Override
    public void add(String id, Embedding embedding) {
        validateDimension(embedding);
        addInternal(id, embedding, null);
    }

    @Override
    public String add(Embedding embedding, TextSegment textSegment) {
        validateDimension(embedding);
        String id = extractId(textSegment);
        addInternal(id, embedding, textSegment);
        return id;
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        return embeddings.stream()
                .map(this::add)
                .collect(Collectors.toList());
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> textSegments) {
        if (embeddings.size() != textSegments.size()) {
            throw new IllegalArgumentException("embeddings and textSegments must have the same size");
        }
        List<String> ids = new ArrayList<>();
        for (int i : firstOccurrences(textSegments)) {
            ids.add(add(embeddings.get(i), textSegments.get(i)));
        }
        return ids;
    }

    /**
     * Batched variant of addAll: all documents are added and made visible with a SINGLE
     * commit + refresh per call (instead of one commit per document). Used by the hub
     * reindex swap where the whole project is re-written.
     */
    public void addAllBatched(List<Embedding> embeddings, List<TextSegment> textSegments) {
        if (embeddings.size() != textSegments.size()) {
            throw new IllegalArgumentException("embeddings and textSegments must have the same size");
        }
        ensureOpen();
        lock.writeLock().lock();
        try {
            for (int i : firstOccurrences(textSegments)) {
                validateDimension(embeddings.get(i));
                String id = extractId(textSegments.get(i));
                writer.addDocument(createDocument(id, embeddings.get(i), textSegments.get(i)));
            }
            writer.commit();
            // Blocking refresh so the batched documents are visible immediately
            // (see addInternal — fixes the "embedded but not findable" bug).
            searcherManager.maybeRefreshBlocking();
        } catch (IOException e) {
            throw new RuntimeException("Failed to add batched documents", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void validateDimension(Embedding embedding) {
        if (embedding.vector().length != vectorDimension) {
            throw new IllegalArgumentException(
                    "Vector dimension mismatch: expected " + vectorDimension + ", got " + embedding.vector().length);
        }
    }

    /**
     * Indices of the first occurrence of each (project, id, part) identity in a batch.
     * <p>
     * Chunk ids are not unique across source roots — the same FQN may be chunked from two test
     * fixtures, for example. The store identity is (project, id, part), so writing both would
     * leave the index in an inconsistent state and make {@link #upsertAll} reject the project
     * with "Duplicate semantic node id". Keeping the first occurrence makes the import
     * idempotent; the dropped duplicate is logged.
     */
    private List<Integer> firstOccurrences(List<TextSegment> textSegments) {
        Map<String, Integer> kept = new LinkedHashMap<>();
        for (int i = 0; i < textSegments.size(); i++) {
            TextSegment segment = textSegments.get(i);
            String identity = storeIdentity(segment);
            Integer previousIndex = kept.get(identity);
            if (previousIndex == null) {
                kept.put(identity, i);
                continue;
            }
            String previousFile = fileOf(textSegments.get(previousIndex));
            String file = fileOf(segment);
            if (ChunkPathPreference.prefer(file, previousFile)) {
                kept.put(identity, i);
                log.warning("Dropping duplicate chunk identity " + identity
                        + " from " + previousFile + " (keeping production " + file + ")");
            } else {
                log.warning("Dropping duplicate chunk identity " + identity
                        + " from " + file + " (keeping " + previousFile + ")");
            }
        }
        return new ArrayList<>(kept.values());
    }

    private static String fileOf(TextSegment segment) {
        return segment.metadata() == null ? null : segment.metadata().getString(PxChunk.PXCHUNK_FILE);
    }

    /** (project, id, part) — the identity a Lucene document is keyed by at import time. */
    private static String storeIdentity(TextSegment segment) {
        Metadata metadata = segment.metadata();
        String id = metadata.getString(PxChunk.PXCHUNK_ID);
        if (id == null) {
            // No chunk identity (plain embeddingStore.addAll callers): never deduplicate.
            return "unique:" + UUID.randomUUID();
        }
        return metadata.getString(PxChunk.PXCHUNK_PROJECT) + '/'
                + id + '/'
                + metadata.getString(PxChunk.PXCHUNK_PART);
    }

    private String extractId(TextSegment textSegment) {
        Map<String, Object> meta = textSegment.metadata().toMap();
        if (meta.containsKey("id")) {
            return meta.get("id").toString();
        }
        if (meta.containsKey(PxChunkMetadataKeys.PXCHUNK_ID)) {
            return meta.get(PxChunkMetadataKeys.PXCHUNK_ID).toString();
        }
        return UUID.randomUUID().toString();
    }

    private void addInternal(String id, Embedding embedding, TextSegment textSegment) {
        lock.writeLock().lock();
        try {
            ensureOpen();
            Document doc = createDocument(id, embedding, textSegment);
            writer.addDocument(doc);
            writer.commit();
            // Blocking refresh: guarantees the new document is visible to the next
            // search as soon as this call returns. (maybeRefresh() is asynchronous
            // and left the reader stale — the "embedded but not findable" bug,
            // Known Bug in v0.8.0-rc1.)
            searcherManager.maybeRefreshBlocking();
        } catch (IOException e) {
            throw new RuntimeException("Failed to add document", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private Document createDocument(String id, Embedding embedding, TextSegment textSegment) {
        Document doc = new Document();
        doc.add(new KnnFloatVectorField(VECTOR_FIELD, embedding.vector(), VectorSimilarityFunction.COSINE));
        doc.add(new StringField(ID_FIELD, id, Field.Store.YES));

        if (textSegment != null) {
            doc.add(new TextField("content", textSegment.text(), Field.Store.YES));
            Map<String, Object> metadata = textSegment.metadata().toMap();
            for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue() != null ? entry.getValue().toString() : "";
                doc.add(new StringField(key, value, Field.Store.NO));
                doc.add(new StoredField(key, value));
            }
        }
        return doc;
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
        ensureOpen();
        try {
            IndexSearcher searcher = getSearcher();
            try {
                KnnFloatVectorQuery knnQuery = new KnnFloatVectorQuery(
                        "vector", request.queryEmbedding().vector(), request.maxResults());
                BooleanQuery.Builder builder = new BooleanQuery.Builder();
                builder.add(knnQuery, BooleanClause.Occur.MUST);

                if (request.filter() != null) {
                    Query filterQuery = LuceneFilterConverter.convert(request.filter());
                    builder.add(filterQuery, BooleanClause.Occur.FILTER);
                }

                TopDocs topDocs = searcher.search(builder.build(), request.maxResults());

                List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>();
                int maxDoc = searcher.getIndexReader().maxDoc();
                for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                    if (scoreDoc.doc < 0 || scoreDoc.doc >= maxDoc) {
                        log.warning("Skipping invalid Lucene docID " + scoreDoc.doc + " (maxDoc=" + maxDoc + ")");
                        continue;
                    }
                    Document doc = searcher.storedFields().document(scoreDoc.doc);
                    String id = doc.get("_id");
                    TextSegment segment = reconstructSegment(doc);
                    double score = normalizeScore(scoreDoc.score);

                    if (request.minScore() > 0 && score < request.minScore()) {
                        continue;
                    }

                    matches.add(new EmbeddingMatch<>(score, id, null, segment));
                }
                return new EmbeddingSearchResult<>(matches);
            } finally {
                releaseSearcher(searcher);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to search index", e);
        }
    }

    public boolean hasMatch(Filter filter) {
        ensureOpen();
        try {
            IndexSearcher searcher = getSearcher();
            try {
                Query luceneQuery = LuceneFilterConverter.convert(filter);
                TopDocs topDocs = searcher.search(luceneQuery, 1);
                return topDocs.totalHits != null && topDocs.totalHits.value() > 0;
            } finally {
                releaseSearcher(searcher);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to check index for filter", e);
        }
    }

    private TextSegment reconstructSegment(Document doc) {
        String content = doc.get("content");
        if (content == null) {
            content = " ";
        }
        Metadata metadata = new Metadata();
        for (IndexableField field : doc.getFields()) {
            String name = field.name();
            if (name.equals(ID_FIELD) || name.equals("content") || name.equals(VECTOR_FIELD)) {
                continue;
            }
            String value = field.stringValue();
            if (value != null) {
                metadata.put(name, value);
            }
        }
        return TextSegment.from(content, metadata);
    }

    /**
     * Reads all live vector documents matching {@code filter}. The returned vector arrays are
     * detached from Lucene's reusable vector buffers.
     */
    public List<VectorChunk> readAllVectors(Filter filter) throws IOException {
        ensureOpen();
        IndexSearcher searcher = getSearcher();
        try {
            Query query = filter == null ? new MatchAllDocsQuery() : LuceneFilterConverter.convert(filter);
            Weight weight = searcher.createWeight(
                    searcher.rewrite(query), ScoreMode.COMPLETE_NO_SCORES, 1f);
            List<VectorChunk> chunks = new ArrayList<>();
            for (LeafReaderContext leafContext : searcher.getIndexReader().leaves()) {
                LeafReader leaf = leafContext.reader();
                FloatVectorValues values = leaf.getFloatVectorValues(VECTOR_FIELD);
                if (values == null) {
                    continue;
                }

                // Random-access filter membership: the vector iterator is denser than the
                // filter iterator, and DocIdSetIterator only guarantees forward motion.
                FixedBitSet matches = FilterDocs.matchingDocs(weight, leafContext);
                Bits liveDocs = leaf.getLiveDocs();
                KnnVectorValues.DocIndexIterator vectors = values.iterator();
                for (int docId = vectors.nextDoc();
                     docId != DocIdSetIterator.NO_MORE_DOCS;
                     docId = vectors.nextDoc()) {
                    if (liveDocs != null && !liveDocs.get(docId)) {
                        continue;
                    }
                    if (!matches.get(docId)) {
                        continue;
                    }

                    float[] vector = values.vectorValue(vectors.index()).clone();
                    Document document = searcher.storedFields().document(leafContext.docBase + docId);
                    chunks.add(toVectorChunk(document, vector));
                }
            }
            return chunks;
        } finally {
            releaseSearcher(searcher);
        }
    }

    /** Reads one live document by its semantic node id, restricted to the owning project. */
    public Optional<VectorChunk> readChunkById(String nodeId, String project) throws IOException {
        ensureOpen();
        IndexSearcher searcher = getSearcher();
        try {
            Query identity = new BooleanQuery.Builder()
                    .add(new TermQuery(new Term(SEMANTIC_NODE_ID_FIELD, nodeId)), BooleanClause.Occur.FILTER)
                    .add(new TermQuery(new Term(PxChunk.PXCHUNK_PROJECT, project)), BooleanClause.Occur.FILTER)
                    .build();
            TopDocs topDocs = searcher.search(identity, 1);
            if (topDocs.totalHits.value() == 0) {
                return Optional.empty();
            }

            int globalDocId = topDocs.scoreDocs[0].doc;
            for (LeafReaderContext leafContext : searcher.getIndexReader().leaves()) {
                int localDocId = globalDocId - leafContext.docBase;
                LeafReader leaf = leafContext.reader();
                if (localDocId < 0 || localDocId >= leaf.maxDoc()) {
                    continue;
                }
                Bits liveDocs = leaf.getLiveDocs();
                if (liveDocs != null && !liveDocs.get(localDocId)) {
                    return Optional.empty();
                }

                FloatVectorValues values = leaf.getFloatVectorValues(VECTOR_FIELD);
                if (values == null) {
                    return Optional.empty();
                }
                KnnVectorValues.DocIndexIterator vectors = values.iterator();
                if (vectors.advance(localDocId) != localDocId) {
                    return Optional.empty();
                }

                Document document = searcher.storedFields().document(globalDocId);
                float[] vector = values.vectorValue(vectors.index()).clone();
                return Optional.of(toVectorChunk(document, vector));
            }
            return Optional.empty();
        } finally {
            releaseSearcher(searcher);
        }
    }

    /**
     * Searches the vector field using Lucene's kNN query and optional pre-filter. Lucene 10.6
     * does not expose a public efSearch setter for this query.
     */
    public List<EmbeddingMatch<TextSegment>> searchKnn(
            Embedding queryEmbedding, int k, double minScore, Filter filter) throws IOException {
        ensureOpen();
        IndexSearcher searcher = getSearcher();
        try {
            Query filterQuery = filter == null ? null : LuceneFilterConverter.convert(filter);
            KnnFloatVectorQuery query = new KnnFloatVectorQuery(
                    VECTOR_FIELD, queryEmbedding.vector(), k, filterQuery);
            TopDocs topDocs = searcher.search(query, k);
            List<EmbeddingMatch<TextSegment>> matches = new ArrayList<>(topDocs.scoreDocs.length);
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document document = searcher.storedFields().document(scoreDoc.doc);
                double score = normalizeScore(scoreDoc.score);
                if (minScore > 0 && score < minScore) {
                    continue;
                }
                matches.add(new EmbeddingMatch<>(
                        score, semanticNodeId(document), null, reconstructSegment(document)));
            }
            return matches;
        } finally {
            releaseSearcher(searcher);
        }
    }

    private VectorChunk toVectorChunk(Document document, float[] vector) {
        String id = document.get(PxChunk.PXCHUNK_ID);
        if (id == null) {
            id = document.get(ID_FIELD);
        }
        String project = document.get(PxChunk.PXCHUNK_PROJECT);
        String partValue = document.get(PxChunk.PXCHUNK_PART);
        int part = partValue == null ? 0 : Integer.parseInt(partValue);
        String content = document.get("content");
        Map<String, String> metadata = new HashMap<>();
        for (IndexableField field : document.getFields()) {
            String name = field.name();
            if (name.equals(ID_FIELD) || name.equals("content") || name.equals(VECTOR_FIELD)) {
                continue;
            }
            String value = field.stringValue();
            if (value != null) {
                metadata.put(name, value);
            }
        }
        return new VectorChunk(semanticNodeId(document), id, project, part, vector, content, metadata);
    }

    private String semanticNodeId(Document document) {
        String storedNodeId = document.get(SEMANTIC_NODE_ID_FIELD);
        if (storedNodeId != null) {
            return storedNodeId;
        }
        String project = document.get(PxChunk.PXCHUNK_PROJECT);
        String id = document.get(PxChunk.PXCHUNK_ID);
        if (id == null) {
            id = document.get(ID_FIELD);
        }
        String partValue = document.get(PxChunk.PXCHUNK_PART);
        int part = partValue == null ? 0 : Integer.parseInt(partValue);
        return VectorChunk.nodeIdFor(project == null ? "" : project, id, part);
    }

    private record ExistingDoc(Document stored, float[] vector) {}

    /**
     * Replaces the exact (project, logical chunk id, part) records in one commit.
     * Null vectors/content preserve existing values; null values in the metadata patch remove keys.
     */
    public void upsertAll(List<VectorChunk> updates) {
        if (updates.isEmpty()) {
            return;
        }

        lock.writeLock().lock();
        IndexSearcher searcher = null;
        try {
            ensureOpen();
            Set<String> seenNodeIds = new HashSet<>();
            for (VectorChunk update : updates) {
                String expectedNodeId = VectorChunk.nodeIdFor(
                        update.project(), update.id(), update.part());
                if (!expectedNodeId.equals(update.nodeId())) {
                    throw new IllegalArgumentException(
                            "Semantic node id does not match project/id/part: " + update.nodeId());
                }
                if (!seenNodeIds.add(update.nodeId())) {
                    throw new IllegalArgumentException("Duplicate semantic node id: " + update.nodeId());
                }
            }

            searcher = getSearcher();
            List<Document> documents = new ArrayList<>(updates.size());
            for (VectorChunk update : updates) {
                ExistingDoc existing = findExisting(
                        searcher, update.project(), update.id(), update.part());
                float[] vector = update.vector();
                String content = update.content();
                if (existing != null) {
                    if (vector == null) {
                        vector = existing.vector();
                    }
                    if (content == null) {
                        content = existing.stored().get("content");
                    }
                } else if (vector == null) {
                    throw new IllegalArgumentException(
                            "A new document requires a vector: " + update.nodeId());
                }

                if (vector == null) {
                    throw new IllegalStateException(
                            "Existing document has no vector: " + update.nodeId());
                }
                validateDimension(Embedding.from(vector));

                Map<String, String> metadata = mergeMetadata(
                        existing == null ? null : existing.stored(), update.metadata());
                metadata.put(PxChunk.PXCHUNK_ID, update.id());
                metadata.put(PxChunk.PXCHUNK_PROJECT, update.project());
                metadata.put(PxChunk.PXCHUNK_PART, Integer.toString(update.part()));
                metadata.put(SEMANTIC_NODE_ID_FIELD, update.nodeId());
                documents.add(buildDocument(update.id(), vector, content, metadata));
            }

            for (VectorChunk update : updates) {
                writer.deleteDocuments(identityQuery(update.project(), update.id(), update.part()));
            }
            for (Document document : documents) {
                writer.addDocument(document);
            }
            writer.commit();
            searcherManager.maybeRefreshBlocking();
        } catch (IOException e) {
            throw new RuntimeException("Failed to upsert documents", e);
        } finally {
            if (searcher != null) {
                releaseSearcher(searcher);
            }
            lock.writeLock().unlock();
        }
    }

    private ExistingDoc findExisting(IndexSearcher searcher, String project, String id, int part)
            throws IOException {
        TopDocs topDocs = searcher.search(identityQuery(project, id, part), 2);
        if (topDocs.totalHits.value() > 1) {
            throw new IllegalStateException(
                    "Multiple live documents share semantic identity " + project + "/" + id + "/" + part);
        }
        if (topDocs.scoreDocs.length == 0) {
            return null;
        }

        int globalDocId = topDocs.scoreDocs[0].doc;
        for (LeafReaderContext leafContext : searcher.getIndexReader().leaves()) {
            int localDocId = globalDocId - leafContext.docBase;
            LeafReader leaf = leafContext.reader();
            if (localDocId < 0 || localDocId >= leaf.maxDoc()) {
                continue;
            }
            Bits liveDocs = leaf.getLiveDocs();
            if (liveDocs != null && !liveDocs.get(localDocId)) {
                return null;
            }

            FloatVectorValues values = leaf.getFloatVectorValues(VECTOR_FIELD);
            if (values == null) {
                return new ExistingDoc(searcher.storedFields().document(globalDocId), null);
            }
            KnnVectorValues.DocIndexIterator vectors = values.iterator();
            float[] vector = vectors.advance(localDocId) == localDocId
                    ? values.vectorValue(vectors.index()).clone()
                    : null;
            return new ExistingDoc(searcher.storedFields().document(globalDocId), vector);
        }
        return null;
    }

    private Map<String, String> mergeMetadata(Document existing, Map<String, String> provided) {
        Map<String, String> merged = new HashMap<>();
        if (existing != null) {
            for (IndexableField field : existing.getFields()) {
                String name = field.name();
                if (name.equals(ID_FIELD) || name.equals("content") || name.equals(VECTOR_FIELD)) {
                    continue;
                }
                String value = field.stringValue();
                if (value != null) {
                    merged.put(name, value);
                }
            }
        }
        if (provided != null) {
            provided.forEach((key, value) -> {
                if (value == null) {
                    merged.remove(key);
                } else {
                    merged.put(key, value);
                }
            });
        }
        return merged;
    }

    private Query identityQuery(String project, String id, int part) {
        return new BooleanQuery.Builder()
                .add(new TermQuery(new Term(ID_FIELD, id)), BooleanClause.Occur.FILTER)
                .add(new TermQuery(new Term(PxChunk.PXCHUNK_PROJECT, project)), BooleanClause.Occur.FILTER)
                .add(new TermQuery(new Term(PxChunk.PXCHUNK_PART, Integer.toString(part))),
                        BooleanClause.Occur.FILTER)
                .build();
    }

    private Document buildDocument(String id, float[] vector, String content, Map<String, String> metadata) {
        Document document = new Document();
        document.add(new KnnFloatVectorField(VECTOR_FIELD, vector, VectorSimilarityFunction.COSINE));
        document.add(new StringField(ID_FIELD, id, Field.Store.YES));
        if (content != null) {
            document.add(new TextField("content", content, Field.Store.YES));
        }
        metadata.forEach((key, value) -> {
            if (value != null) {
                document.add(new StringField(key, value, Field.Store.NO));
                document.add(new StoredField(key, value));
            }
        });
        return document;
    }

    /**
     * Returns live documents with an exact stored field value, optionally filtered further.
     * The vector is omitted because these metadata views do not need it.
     */
    public List<VectorChunk> searchByMetadata(String field, String value, Filter projectFilter, int max) {
        if (max < 1) {
            return List.of();
        }
        ensureOpen();
        IndexSearcher searcher = null;
        try {
            searcher = getSearcher();
            BooleanQuery.Builder query = new BooleanQuery.Builder()
                    .add(new TermQuery(new Term(field, value)), BooleanClause.Occur.MUST);
            if (projectFilter != null) {
                query.add(LuceneFilterConverter.convert(projectFilter), BooleanClause.Occur.FILTER);
            }
            TopDocs topDocs = searcher.search(query.build(), max);
            List<VectorChunk> chunks = new ArrayList<>(topDocs.scoreDocs.length);
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document document = searcher.storedFields().document(scoreDoc.doc);
                chunks.add(toVectorChunk(document, null));
            }
            return chunks;
        } catch (IOException e) {
            throw new RuntimeException("Failed to search by metadata", e);
        } finally {
            if (searcher != null) {
                releaseSearcher(searcher);
            }
        }
    }

    /** Counts every live stored-field value, optionally restricted by a Lucene filter. */
    public Map<String, Long> countByMetadata(String field, Filter projectFilter) {
        ensureOpen();
        IndexSearcher searcher = null;
        try {
            searcher = getSearcher();
            Query query = projectFilter == null
                    ? new MatchAllDocsQuery()
                    : LuceneFilterConverter.convert(projectFilter);
            int maxDoc = searcher.getIndexReader().maxDoc();
            Map<String, Long> counts = new LinkedHashMap<>();
            if (maxDoc == 0) {
                return counts;
            }
            TopDocs topDocs = searcher.search(query, maxDoc);
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                String value = searcher.storedFields().document(scoreDoc.doc).get(field);
                if (value != null) {
                    counts.merge(value, 1L, Long::sum);
                }
            }
            return counts;
        } catch (IOException e) {
            throw new RuntimeException("Failed to count by metadata", e);
        } finally {
            if (searcher != null) {
                releaseSearcher(searcher);
            }
        }
    }

    private double normalizeScore(float rawScore) {
        if (rawScore < 0.0f) {
            return 0.0;
        }
        if (rawScore > 1.0f) {
            return 1.0;
        }
        return rawScore;
    }

    @Override
    public void removeAll(Filter filter) {
        lock.writeLock().lock();
        try {
            ensureOpen();
            Query luceneQuery = LuceneFilterConverter.convert(filter);
            writer.deleteDocuments(luceneQuery);
            writer.commit();
            // Blocking refresh so the deletions are visible immediately
            // (see addInternal — fixes the "embedded but not findable" bug).
            searcherManager.maybeRefreshBlocking();
        } catch (IOException e) {
            throw new RuntimeException("Failed to remove documents", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void commit() {
        lock.writeLock().lock();
        try {
            if (writer != null) {
                writer.commit();
                // Blocking refresh so the committed changes are visible immediately
                // (see addInternal — fixes the "embedded but not findable" bug).
                searcherManager.maybeRefreshBlocking();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to commit", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void close() {
        lock.writeLock().lock();
        try {
            if (searcherManager != null) {
                try {
                    searcherManager.close();
                } catch (IOException e) {
                    // ignore
                }
                searcherManager = null;
            }
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    // ignore
                }
                writer = null;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public void destroy() {
        close();
    }

    public int count() {
        ensureOpen();
        try {
            IndexSearcher searcher = getSearcher();
            try {
                return searcher.getIndexReader().numDocs();
            } finally {
                releaseSearcher(searcher);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to count documents", e);
        }
    }

    /** Count embeddings for a specific project. */
    public long countByProject(String projectName) {
        ensureOpen();
        try {
            IndexSearcher searcher = getSearcher();
            try {
                Query query = new org.apache.lucene.search.TermQuery(
                    new org.apache.lucene.index.Term(de.spraener.prjxp.common.model.PxChunk.PXCHUNK_PROJECT, projectName));
                return searcher.count(query);
            } finally {
                releaseSearcher(searcher);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to count embeddings for project " + projectName, e);
        }
    }

    /** Count distinct files for a specific project (paged — no arbitrary cap). */
    public int countFilesByProject(String projectName) {
        ensureOpen();
        try {
            IndexSearcher searcher = getSearcher();
            try {
                Query query = new org.apache.lucene.search.TermQuery(
                    new org.apache.lucene.index.Term(de.spraener.prjxp.common.model.PxChunk.PXCHUNK_PROJECT, projectName));
                int maxDoc = searcher.getIndexReader().maxDoc();
                java.util.Set<String> files = new java.util.HashSet<>();
                int from = 0;
                final int pageSize = 5000;
                while (from < maxDoc) {
                    int size = Math.min(pageSize, maxDoc - from);
                    TopDocs docs = searcher.search(query, from + size);
                    int sliceEnd = Math.min(from + size, docs.scoreDocs.length);
                    for (int i = from; i < sliceEnd; i++) {
                        Document d = searcher.storedFields().document(docs.scoreDocs[i].doc);
                        String file = d.get(de.spraener.prjxp.common.model.PxChunk.PXCHUNK_FILE);
                        if (file != null) files.add(file);
                    }
                    if (sliceEnd < docs.totalHits.value()) {
                        from += size;
                    } else {
                        break;
                    }
                }
                return files.size();
            } finally {
                releaseSearcher(searcher);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to count files for project " + projectName, e);
        }
    }

    /**
     * Returns the distinct {@code pxchunk_file} values for a project, optionally further filtered.
     * Paged (no arbitrary cap). {@code filter} may be null (all files of the project).
     */
    public java.util.Set<String> listFilesByProject(String projectName, Filter filter) {
        ensureOpen();
        try {
            IndexSearcher searcher = getSearcher();
            try {
                Query query = new org.apache.lucene.search.TermQuery(
                    new org.apache.lucene.index.Term(de.spraener.prjxp.common.model.PxChunk.PXCHUNK_PROJECT, projectName));
                if (filter != null) {
                    query = new BooleanQuery.Builder()
                            .add(new BooleanClause(query, BooleanClause.Occur.FILTER))
                            .add(LuceneFilterConverter.convert(filter), BooleanClause.Occur.FILTER)
                            .build();
                }
                java.util.Set<String> files = new java.util.HashSet<>();
                int maxDoc = searcher.getIndexReader().maxDoc();
                int from = 0;
                final int pageSize = 5000;
                while (from < maxDoc) {
                    int size = Math.min(pageSize, maxDoc - from);
                    TopDocs docs = searcher.search(query, from + size);
                    int sliceEnd = Math.min(from + size, docs.scoreDocs.length);
                    for (int i = from; i < sliceEnd; i++) {
                        Document d = searcher.storedFields().document(docs.scoreDocs[i].doc);
                        String file = d.get(de.spraener.prjxp.common.model.PxChunk.PXCHUNK_FILE);
                        if (file != null) files.add(file);
                    }
                    if (sliceEnd < docs.totalHits.value()) {
                        from += size;
                    } else {
                        break;
                    }
                }
                return files;
            } finally {
                releaseSearcher(searcher);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to list files for project " + projectName, e);
        }
    }

    public int getVectorDimension() {
        return vectorDimension;
    }

    public IndexSearcher getSearcher() throws IOException {
        ensureOpen();
        return searcherManager.acquire();
    }

    public void releaseSearcher(IndexSearcher searcher) {
        try {
            searcherManager.release(searcher);
        } catch (IOException e) {
            // ignore on release
        }
    }
}
