package de.spraener.prjxp.lucene;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.NIOFSDirectory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

public class LuceneEmbeddingStore implements EmbeddingStore<TextSegment> {
    private final Path indexPath;
    private final int vectorDimension;
    private IndexWriter writer;
    private SearcherManager searcherManager;
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public LuceneEmbeddingStore(Path indexPath, int vectorDimension) {
        this.indexPath = indexPath;
        this.vectorDimension = vectorDimension;
    }

    private void ensureOpen() {
        lock.writeLock().lock();
        try {
            if (writer == null) {
                NIOFSDirectory directory = new NIOFSDirectory(indexPath);
                IndexWriterConfig config = new IndexWriterConfig();
                writer = new IndexWriter(directory, config);
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
        for (int i = 0; i < embeddings.size(); i++) {
            String id = add(embeddings.get(i), textSegments.get(i));
            ids.add(id);
        }
        return ids;
    }

    private void validateDimension(Embedding embedding) {
        if (embedding.vector().length != vectorDimension) {
            throw new IllegalArgumentException(
                    "Vector dimension mismatch: expected " + vectorDimension + ", got " + embedding.vector().length);
        }
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
        ensureOpen();
        try {
            Document doc = createDocument(id, embedding, textSegment);
            writer.addDocument(doc);
            writer.commit();
            searcherManager.maybeRefresh();
        } catch (IOException e) {
            throw new RuntimeException("Failed to add document", e);
        }
    }

    private Document createDocument(String id, Embedding embedding, TextSegment textSegment) {
        Document doc = new Document();
        doc.add(new KnnFloatVectorField("vector", embedding.vector(), VectorSimilarityFunction.COSINE));
        doc.add(new StringField("_id", id, Field.Store.YES));

        if (textSegment != null) {
            doc.add(new StoredField("content", textSegment.text()));
            Map<String, Object> metadata = textSegment.metadata().toMap();
            for (Map.Entry<String, Object> entry : metadata.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue() != null ? entry.getValue().toString() : "";
                doc.add(new StringField(key, value, Field.Store.YES));
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
                for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
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

    private TextSegment reconstructSegment(Document doc) {
        String content = doc.get("content");
        if (content == null) {
            content = " ";
        }
        Metadata metadata = new Metadata();
        for (IndexableField field : doc.getFields()) {
            String name = field.name();
            if (name.equals("_id") || name.equals("content") || name.equals("vector")) {
                continue;
            }
            String value = field.stringValue();
            if (value != null) {
                metadata.put(name, value);
            }
        }
        return TextSegment.from(content, metadata);
    }

    private double normalizeScore(float rawScore) {
        return Math.pow(2, -rawScore);
    }

    @Override
    public void removeAll(Filter filter) {
        ensureOpen();
        try {
            Query luceneQuery = LuceneFilterConverter.convert(filter);
            writer.deleteDocuments(luceneQuery);
            writer.commit();
            searcherManager.maybeRefresh();
        } catch (IOException e) {
            throw new RuntimeException("Failed to remove documents", e);
        }
    }

    public void commit() {
        lock.writeLock().lock();
        try {
            if (writer != null) {
                writer.commit();
                searcherManager.maybeRefresh();
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
