package de.spraener.prjxp.tibed.semantic;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import de.spraener.prjxp.lucene.VectorChunk;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticEdgeBuilderTest {

    private static final String JAVA_SECTION_KEY = PxChunk.PXCHUNK_METADATA + ".java_code_section";
    private static final String TS_SECTION_KEY = PxChunk.PXCHUNK_METADATA + ".typescript_code_section";

    @TempDir
    Path tempDir;

    private LuceneEmbeddingStore store;
    private SemanticEdgeBuilder builder;

    @BeforeEach
    void setUp() {
        store = new LuceneEmbeddingStore(tempDir.resolve("index"), 3);
        builder = new SemanticEdgeBuilder();
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void selectInputChunksKeepsSupportedDocuments() {
        List<VectorChunk> chunks = List.of(
                chunk("p", "a.md", 0, "text/markdown", null, null, null),
                chunk("p", "b.pdf", 0, "application/pdf", null, null, null),
                chunk("p", "c.txt", 0, "text/plain", null, null, null));

        assertThat(builder.selectInputChunks(chunks)).containsExactlyElementsOf(chunks);
    }

    @Test
    void selectInputChunksKeepsJavaAndTypeScriptClassFrames() {
        VectorChunk java = chunk("p", "de.ex.Foo", 0, "text/x-java-code",
                JAVA_SECTION_KEY, "classFrame", null);
        VectorChunk typescript = chunk("p", "utils.Helper", 0, "text/x-typescript-code",
                TS_SECTION_KEY, "classFrame", null);

        assertThat(builder.selectInputChunks(List.of(java, typescript)))
                .containsExactly(java, typescript);
    }

    @Test
    void selectInputChunksDropsNonClassFramesAndUnsupportedMimeTypes() {
        List<VectorChunk> excluded = List.of(
                chunk("p", "de.ex.Foo.public void bar()", 0, "text/x-java-code",
                        JAVA_SECTION_KEY, "method", null),
                chunk("p", "de.ex.Foo.imports", 0, "text/x-java-code",
                        JAVA_SECTION_KEY, "imports", null),
                chunk("p", "utils.imports", 0, "text/x-typescript-code",
                        TS_SECTION_KEY, "imports", null),
                chunk("p", "utils.run", 0, "text/x-typescript-code",
                        TS_SECTION_KEY, "method", null),
                chunk("p", "raw.txt", 0, "file/text", null, null, null),
                chunk("p", "missing-mime", 0, null, null, null, null));

        assertThat(builder.selectInputChunks(excluded)).isEmpty();
    }

    @Test
    void buildsMutualDocEdgesAndFiltersOtherProjects() throws IOException {
        addChunk("p", "a", new float[]{1f, 0f, 0f}, "text/markdown", null, null);
        addChunk("p", "b", new float[]{1f, 0f, 0f}, "text/plain", null, null);
        addChunk("p", "c", new float[]{0f, 1f, 0f}, "text/markdown", null, null);
        addChunk("q", "foreign", new float[]{1f, 0f, 0f}, "text/markdown", null, null);
        List<VectorChunk> input = inputFor("p");

        List<SemanticEdge> edges = builder.buildSemanticEdges(
                store, "p", input, new EdgeConfig(10, 0.85, true));

        assertThat(edges).hasSize(1);
        assertEdgeBetween(edges.get(0), nodeId("p", "a"), nodeId("p", "b"));
        assertThat(edges.get(0).source()).isLessThan(edges.get(0).target());
    }

    @Test
    void buildsDocToCodeButDropsCodeToCodeEdges() throws IOException {
        addChunk("p", "doc", new float[]{1f, 0f, 0f}, "text/markdown", null, null);
        addChunk("p", "de.ex.Foo", new float[]{1f, 0f, 0f}, "text/x-java-code",
                JAVA_SECTION_KEY, "classFrame");
        addChunk("p", "utils.Helper", new float[]{1f, 0f, 0f}, "text/x-typescript-code",
                TS_SECTION_KEY, "classFrame");
        List<VectorChunk> input = inputFor("p");

        List<SemanticEdge> edges = builder.buildSemanticEdges(
                store, "p", input, new EdgeConfig(10, 0.85, true));

        assertThat(edges).hasSize(2);
        assertThat(edges).allSatisfy(edge -> {
            assertThat(edge.source()).isLessThan(edge.target());
            VectorChunk first = input.stream().filter(vc -> vc.nodeId().equals(edge.source()))
                    .findFirst().orElseThrow();
            VectorChunk second = input.stream().filter(vc -> vc.nodeId().equals(edge.target()))
                    .findFirst().orElseThrow();
            assertThat(isDoc(first) || isDoc(second)).isTrue();
        });
    }

    @Test
    void appliesThresholdAndMutualNeighborSetting() throws IOException {
        addChunk("p", "a", unitAtDegrees(-15), "text/markdown", null, null);
        addChunk("p", "b", unitAtDegrees(0), "text/markdown", null, null);
        addChunk("p", "c", unitAtDegrees(5), "text/markdown", null, null);
        List<VectorChunk> input = inputFor("p");

        List<SemanticEdge> mutual = builder.buildSemanticEdges(
                store, "p", input, new EdgeConfig(2, 0.95, true));
        List<SemanticEdge> nonMutual = builder.buildSemanticEdges(
                store, "p", input, new EdgeConfig(2, 0.95, false));

        assertThat(mutual).noneMatch(edge -> connects(edge, nodeId("p", "a"), nodeId("p", "b")));
        assertThat(nonMutual).anyMatch(edge -> connects(edge, nodeId("p", "a"), nodeId("p", "b")));
        assertThat(nonMutual).allSatisfy(edge -> assertThat(edge.strength()).isGreaterThanOrEqualTo(0.95));
    }

    @Test
    void thresholdRemovesOrthogonalDocsAndNoEdgesAreReturnedForSingletonInput() throws IOException {
        addChunk("p", "orthogonal-a", new float[]{1f, 0f, 0f}, "text/markdown", null, null);
        addChunk("p", "orthogonal-b", new float[]{0f, 1f, 0f}, "text/plain", null, null);
        assertThat(builder.buildSemanticEdges(store, "p", inputFor("p"),
                new EdgeConfig(10, 0.85, false))).isEmpty();

        store.close();
        store = new LuceneEmbeddingStore(tempDir.resolve("empty-index"), 3);
        assertThat(builder.buildSemanticEdges(store, "p", List.of(),
                EdgeConfig.defaults())).isEmpty();
    }

    @Test
    void parallelSearchRunsOnMultipleThreads() throws IOException {
        store.close();
        RecordingStore recording = new RecordingStore(tempDir.resolve("rec-index"), 3);
        store = recording;
        for (int i = 0; i < 12; i++) {
            addChunk("p", "n" + i, unitAtDegrees(i * 3), "text/markdown", null, null);
        }
        List<VectorChunk> input = inputFor("p");

        List<SemanticEdge> edges = builder.buildSemanticEdges(
                store, "p", input, new EdgeConfig(10, 0.5, false, 4));

        assertThat(recording.searchThreads).hasSizeGreaterThan(1);
        assertThat(recording.searchThreads).allSatisfy(name -> assertThat(name).startsWith("semantic-knn-"));
        assertThat(edges).isNotEmpty();
    }

    /**
     * The auto thread count (parallelism 0) is capped at {@code AUTO_PARALLELISM_CAP} because the
     * flat kNN scan is memory-bandwidth-bound — using all cores is the worst choice. Even on a
     * many-core machine the pool must never exceed the cap.
     */
    @Test
    void autoParallelismIsCapped() throws IOException {
        store.close();
        RecordingStore recording = new RecordingStore(tempDir.resolve("cap-index"), 3);
        store = recording;
        for (int i = 0; i < 24; i++) {
            addChunk("p", "n" + i, unitAtDegrees(i * 2), "text/markdown", null, null);
        }
        List<VectorChunk> input = inputFor("p");

        builder.buildSemanticEdges(store, "p", input, new EdgeConfig(10, 0.5, false, 0));

        int cap = Math.min(Runtime.getRuntime().availableProcessors(), 4);
        assertThat(recording.searchThreads).hasSizeLessThanOrEqualTo(cap);
    }

    @Test
    void parallelOutputMatchesSequential() throws IOException {
        for (int i = 0; i < 10; i++) {
            addChunk("p", "n" + i, unitAtDegrees(i * 7), "text/markdown", null, null);
        }
        List<VectorChunk> input = inputFor("p");

        List<SemanticEdge> sequential = builder.buildSemanticEdges(
                store, "p", input, new EdgeConfig(10, 0.5, false, 1));
        List<SemanticEdge> parallel = builder.buildSemanticEdges(
                store, "p", input, new EdgeConfig(10, 0.5, false, 4));

        assertThat(parallel).containsExactlyElementsOf(sequential);
    }

    @Test
    void propagatesSearchFailures() throws IOException {
        store.close();
        store = new LuceneEmbeddingStore(tempDir.resolve("fail-index"), 3) {
            @Override
            public List<EmbeddingMatch<TextSegment>> searchKnn(
                    Embedding queryEmbedding, int k, double minScore, Filter filter)
                    throws IOException {
                throw new IOException("boom");
            }
        };
        addChunk("p", "a", new float[]{1f, 0f, 0f}, "text/markdown", null, null);
        addChunk("p", "b", new float[]{1f, 0f, 0f}, "text/markdown", null, null);
        List<VectorChunk> input = inputFor("p");

        assertThatThrownBy(() -> builder.buildSemanticEdges(
                store, "p", input, new EdgeConfig(10, 0.85, true, 2)))
                .isInstanceOf(IOException.class)
                .hasMessage("boom");
    }

    /** Store that records the thread names its kNN searches run on. */
    private static class RecordingStore extends LuceneEmbeddingStore {
        final Set<String> searchThreads = ConcurrentHashMap.newKeySet();

        RecordingStore(Path indexPath, int vectorDimension) {
            super(indexPath, vectorDimension);
        }

        @Override
        public List<EmbeddingMatch<TextSegment>> searchKnn(
                Embedding queryEmbedding, int k, double minScore, Filter filter) throws IOException {
            searchThreads.add(Thread.currentThread().getName());
            return super.searchKnn(queryEmbedding, k, minScore, filter);
        }
    }

    private void addChunk(String project, String id, float[] vector, String mime,
                          String sectionKey, String section, String file) {
        Metadata metadata = new Metadata();
        metadata.put(PxChunk.PXCHUNK_ID, id);
        metadata.put(PxChunk.PXCHUNK_PROJECT, project);
        metadata.put(PxChunk.PXCHUNK_PART, "0");
        metadata.put(PxChunk.PXCHUNK_MIME_TYPE, mime);
        metadata.put(PxChunk.PXCHUNK_FILE, file == null ? id : file);
        if (sectionKey != null) {
            metadata.put(sectionKey, section);
        }
        metadata.put(PxChunk.PXCHUNK_METADATA + ".semantic_node_id",
                VectorChunk.nodeIdFor(project, id, 0));
        store.add(Embedding.from(vector), TextSegment.from(id, metadata));
    }

    private void addChunk(String project, String id, float[] vector, String mime,
                          String sectionKey, String section) {
        addChunk(project, id, vector, mime, sectionKey, section, null);
    }

    private List<VectorChunk> inputFor(String project) throws IOException {
        return builder.selectInputChunks(store.readAllVectors(
                new IsEqualTo(PxChunk.PXCHUNK_PROJECT, project)));
    }

    private VectorChunk chunk(String project, String id, int part, String mime,
                              String sectionKey, String section, float[] vector) {
        Map<String, String> metadata = new HashMap<>();
        if (mime != null) metadata.put(PxChunk.PXCHUNK_MIME_TYPE, mime);
        if (sectionKey != null) metadata.put(sectionKey, section);
        return new VectorChunk(VectorChunk.nodeIdFor(project, id, part), id, project, part,
                vector, id, metadata);
    }

    private String nodeId(String project, String id) {
        return VectorChunk.nodeIdFor(project, id, 0);
    }

    private float[] unitAtDegrees(double degrees) {
        double radians = Math.toRadians(degrees);
        return new float[]{(float) Math.cos(radians), (float) Math.sin(radians), 0f};
    }

    private void assertEdgeBetween(SemanticEdge edge, String first, String second) {
        assertThat(connects(edge, first, second)).isTrue();
    }

    private boolean connects(SemanticEdge edge, String first, String second) {
        return (edge.source().equals(first) && edge.target().equals(second))
                || (edge.source().equals(second) && edge.target().equals(first));
    }

    private boolean isDoc(VectorChunk chunk) {
        return "text/markdown".equals(chunk.metadata().get(PxChunk.PXCHUNK_MIME_TYPE))
                || "text/plain".equals(chunk.metadata().get(PxChunk.PXCHUNK_MIME_TYPE))
                || "application/pdf".equals(chunk.metadata().get(PxChunk.PXCHUNK_MIME_TYPE));
    }
}
