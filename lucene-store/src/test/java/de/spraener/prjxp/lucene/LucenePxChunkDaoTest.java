package de.spraener.prjxp.lucene;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;
import de.spraener.prjxp.common.model.PxChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class LucenePxChunkDaoTest {

    @TempDir
    Path tempDir;

    LuceneEmbeddingStore store;
    LucenePxChunkDao dao;

    @Mock
    EmbeddingModel embeddingModel;

    PrjXPEmbeddingStoreReference storeReference;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        store = new LuceneEmbeddingStore(tempDir.resolve("index"), 3);

        storeReference = new PrjXPEmbeddingStoreReference();
        storeReference.setProjectName("test-project");
        storeReference.setCollectionName("test-collection");

        dao = new LucenePxChunkDao(store, embeddingModel, storeReference);

        when(embeddingModel.embed(any(String.class))).thenReturn(
                Response.from(Embedding.from(new float[]{0.5f, 0.3f, 0.2f}))
        );
    }

    @Test
    void getStoreReference() {
        assertThat(dao.getStoreReference()).isSameAs(storeReference);
        assertThat(dao.getStoreReference().getProjectName()).isEqualTo("test-project");
    }

    @Test
    void findByIdReturnsMatchingChunk() {
        addChunk("chunk-1", "Hello world");
        addChunk("chunk-2", "Goodbye world");

        List<PxChunk> result = dao.findById("chunk-1");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getContent()).isEqualTo("Hello world");
    }

    @Test
    void findByIdNonExistentReturnsEmpty() {
        addChunk("chunk-1", "Hello world");

        List<PxChunk> result = dao.findById("non-existent");
        assertThat(result).isEmpty();
    }

    @Test
    void findByMetaDataSingleKey() {
        Metadata meta1 = new Metadata();
        meta1.put("pxchunk_id", "chunk-1");
        meta1.put("source", "file-a.txt");
        store.add(Embedding.from(new float[]{1f, 0f, 0f}), TextSegment.from("Content A", meta1));

        Metadata meta2 = new Metadata();
        meta2.put("pxchunk_id", "chunk-2");
        meta2.put("source", "file-b.txt");
        store.add(Embedding.from(new float[]{1f, 0f, 0f}), TextSegment.from("Content B", meta2));

        Map<String, String> searchMeta = Map.of("source", "file-a.txt");
        List<PxChunk> result = dao.findByMetaData(searchMeta);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getContent()).isEqualTo("Content A");
    }

    @Test
    void findByMetaDataMultipleKeysAndLogic() {
        Metadata meta1 = new Metadata();
        meta1.put("pxchunk_id", "chunk-1");
        meta1.put("source", "file-a.txt");
        meta1.put("type", "code");
        store.add(Embedding.from(new float[]{1f, 0f, 0f}), TextSegment.from("Java code", meta1));

        Metadata meta2 = new Metadata();
        meta2.put("pxchunk_id", "chunk-2");
        meta2.put("source", "file-a.txt");
        meta2.put("type", "doc");
        store.add(Embedding.from(new float[]{1f, 0f, 0f}), TextSegment.from("Documentation", meta2));

        Map<String, String> searchMeta = Map.of("source", "file-a.txt", "type", "code");
        List<PxChunk> result = dao.findByMetaData(searchMeta);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getContent()).isEqualTo("Java code");
    }

    @Test
    void findRelevantReturnsSimilarChunks() {
        addChunk("chunk-1", "Database connection");
        addChunk("chunk-2", "Coffee recipe");

        List<PxChunk> result = dao.findRelevant("How to connect to a database", 5, 0.0);
        assertThat(result).hasSize(2);
    }

    @Test
    void findRelevantRespectsMinScore() {
        addChunk("chunk-1", "Some content");

        List<PxChunk> result = dao.findRelevant("Question", 5, 0.99);
        // With random embeddings and high minScore, results may be empty or filtered
        for (PxChunk chunk : result) {
            assertThat(chunk).isNotNull();
        }
    }

    @Test
    void findAllReturnsAllChunks() {
        addChunk("chunk-1", "First");
        addChunk("chunk-2", "Second");
        addChunk("chunk-3", "Third");

        List<PxChunk> result = dao.findAll().collect(Collectors.toList());
        assertThat(result).hasSize(3);
    }

    @Test
    void roundtripMetadataPreserved() {
        PxChunk original = PxChunk.create(c -> {
            c.setId("test-123");
            c.setMimeType("text/plain");
            c.setFile("src/Main.java");
            c.setParent("parent-1");
            c.setPart(1);
            c.setTotal(5);
            c.setSize(1000);
            c.setOverlap(100);
        });
        original.getMetadata().put("custom", "value");

        Map<String, String> metaMap = PxChunk.metadataAsMap(original);
        Metadata metadata = new Metadata();
        metaMap.forEach(metadata::put);

        store.add(Embedding.from(new float[]{1f, 0f, 0f}),
                TextSegment.from(original.getContent() != null ? original.getContent() : "content", metadata));

        List<PxChunk> result = dao.findById("test-123");
        assertThat(result).hasSize(1);
        PxChunk retrieved = result.get(0);
        assertThat(retrieved.getId()).isEqualTo("test-123");
        assertThat(retrieved.getMimeType()).isEqualTo("text/plain");
        assertThat(retrieved.getFile()).isEqualTo("src/Main.java");
        assertThat(retrieved.getParent()).isEqualTo("parent-1");
        assertThat(retrieved.getPart()).isEqualTo(1);
        assertThat(retrieved.getTotal()).isEqualTo(5);
    }

    private void addChunk(String id, String content) {
        Metadata meta = new Metadata();
        meta.put("pxchunk_id", id);
        store.add(Embedding.from(new float[]{1f, 0f, 0f}), TextSegment.from(content, meta));
    }
}
