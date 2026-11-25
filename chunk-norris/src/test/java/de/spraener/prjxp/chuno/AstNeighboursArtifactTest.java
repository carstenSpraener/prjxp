package de.spraener.prjxp.chuno;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;
import de.spraener.prjxp.chuno.veto.VetoRegistry;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.errorlog.PxLogService;
import de.spraener.prjxp.common.graph.CodeGraphIo;
import de.spraener.prjxp.common.language.CodeGraphBuilder;
import de.spraener.prjxp.common.language.LanguagePack;
import de.spraener.prjxp.common.language.LanguagePackRegistry;
import de.spraener.prjxp.common.model.CodeGraph;
import de.spraener.prjxp.common.model.EdgeType;
import de.spraener.prjxp.common.model.GraphEdge;
import de.spraener.prjxp.common.model.GraphNode;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.model.PxChunker;
import de.spraener.prjxp.common.transfer.TransferCrypto;
import de.spraener.prjxp.common.transfer.TransferPasswordResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.AbstractEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AstNeighboursArtifactTest {

    @TempDir
    Path tempDir;

    @Test
    void writesSidecarFromGraphEdges() throws Exception {
        PrjXPConfig cfg = configWith("PRJXP_TEST_NO_PASSWORD_XYZ");
        CodeGraph graph = new CodeGraph(
                List.of(GraphNode.type("A", "src/A.java", "java"),
                        GraphNode.type("B", "src/B.java", "java"),
                        GraphNode.type("C", "src/C.java", "java")),
                List.of(internal("A", "B"), internal("B", "C")));

        uut(cfg, null, graph, List.of(chunk("A"), chunk("B"), chunk("C"))).execute();

        Map<String, List<String>> sidecar = readSidecar(sidecarPath());
        assertThat(sidecar.get("A")).containsExactly("B");
        assertThat(sidecar.get("B")).containsExactlyInAnyOrder("A", "C");
        assertThat(sidecar.get("C")).containsExactly("B");
    }

    @Test
    void skipsExternalEdges() throws Exception {
        PrjXPConfig cfg = configWith("PRJXP_TEST_NO_PASSWORD_XYZ");
        CodeGraph graph = new CodeGraph(
                List.of(GraphNode.type("A", "src/A.java", "java")),
                List.of(new GraphEdge("A", "JDK.Type", EdgeType.EXTENDS, true)));

        uut(cfg, null, graph, List.of(chunk("A"))).execute();

        // No internal neighbour at all -> nothing to write for A -> no sidecar file.
        assertThat(Files.exists(sidecarPath())).isFalse();
    }

    @Test
    void externalTargetNeverAppearsInNeighbours() throws Exception {
        PrjXPConfig cfg = configWith("PRJXP_TEST_NO_PASSWORD_XYZ");
        CodeGraph graph = new CodeGraph(
                List.of(GraphNode.type("A", "src/A.java", "java"),
                        GraphNode.type("B", "src/B.java", "java")),
                List.of(internal("A", "B"), new GraphEdge("A", "JDK.Type", EdgeType.EXTENDS, true)));

        uut(cfg, null, graph, List.of(chunk("A"), chunk("B"))).execute();

        assertThat(readSidecar(sidecarPath()).get("A")).containsExactly("B");
    }

    @Test
    void capsAtTenNeighbours() throws Exception {
        PrjXPConfig cfg = configWith("PRJXP_TEST_NO_PASSWORD_XYZ");
        List<GraphNode> nodes = new ArrayList<>();
        List<GraphEdge> edges = new ArrayList<>();
        List<PxChunk> chunks = new ArrayList<>();
        nodes.add(GraphNode.type("A", "src/A.java", "java"));
        chunks.add(chunk("A"));
        for (int i = 0; i < 15; i++) {
            String id = "N" + i;
            nodes.add(GraphNode.type(id, "src/" + id + ".java", "java"));
            chunks.add(chunk(id));
            edges.add(internal("A", id));
        }

        uut(cfg, null, new CodeGraph(nodes, edges), chunks).execute();

        assertThat(readSidecar(sidecarPath()).get("A")).hasSize(ChunkProcess.MAX_AST_NEIGHBOURS);
    }

    @Test
    void noGraphNoSidecar() throws Exception {
        PrjXPConfig cfg = configWith("PRJXP_TEST_NO_PASSWORD_XYZ");

        uut(cfg, null, null, List.of(chunk("A"))).execute();

        assertThat(Files.exists(sidecarPath())).isFalse();
    }

    @Test
    void noGraphFileNoSidecar() throws Exception {
        PrjXPConfig cfg = configWith("PRJXP_TEST_NO_PASSWORD_XYZ");
        cfg.getActiveProject().orElseThrow().setJsonlFile(null);   // JSONL to stdout -> no graph file either
        CodeGraph graph = new CodeGraph(
                List.of(GraphNode.type("A", "src/A.java", "java")),
                List.of());

        uut(cfg, null, graph, List.of(chunk("A"))).execute();

        assertThat(Files.exists(sidecarPath())).isFalse();
        assertThat(tempDir.resolve("out.graph.json")).doesNotExist();
    }

    @Test
    void encryptedSidecar() throws Exception {
        PrjXPConfig cfg = configWith("PRJXP_TEST_PASSWORD_XYZ");
        CodeGraph graph = new CodeGraph(
                List.of(GraphNode.type("A", "src/A.java", "java"),
                        GraphNode.type("B", "src/B.java", "java")),
                List.of(internal("A", "B")));

        uut(cfg, "env-pw", graph, List.of(chunk("A"), chunk("B"))).execute();

        byte[] bytes = Files.readAllBytes(sidecarPath());
        assertThat(new String(bytes, 0, 8, StandardCharsets.US_ASCII)).isEqualTo("PRJXPENC");
        try (InputStream in = TransferCrypto.openDecryptedInputStream(new ByteArrayInputStream(bytes), "env-pw".toCharArray())) {
            Map<String, List<String>> sidecar = new JsonMapper()
                    .readValue(in, new TypeReference<Map<String, List<String>>>() {});
            assertThat(sidecar.get("A")).containsExactly("B");
        }
    }

    @Test
    void methodNodeMapping() throws Exception {
        PrjXPConfig cfg = configWith("PRJXP_TEST_NO_PASSWORD_XYZ");
        String methodNode = "de.ex.Foo#bar(java.lang.String)";
        String methodChunkId = "de.ex.Foo.bar(java.lang.String)";
        CodeGraph graph = new CodeGraph(
                List.of(GraphNode.type("de.ex.Foo", "src/Foo.java", "java"),
                        GraphNode.method(methodNode, "src/Foo.java", "java")),
                List.of(new GraphEdge(methodNode, "de.ex.Foo", EdgeType.PARAM, false)));

        uut(cfg, null, graph, List.of(
                chunk("de.ex.Foo", "de.ex.Foo"),
                chunk(methodChunkId, methodNode))).execute();

        Map<String, List<String>> sidecar = readSidecar(sidecarPath());
        assertThat(sidecar).containsKey(methodChunkId);
        assertThat(sidecar.get(methodChunkId)).containsExactly("de.ex.Foo");
        assertThat(sidecar.get("de.ex.Foo")).containsExactly(methodChunkId);
    }

    @Test
    void customGraphFileNameNeverGetsOverwrittenBySidecar() throws Exception {
        PrjXPConfig cfg = configWith("PRJXP_TEST_NO_PASSWORD_XYZ");
        Path customGraph = tempDir.resolve("artifacts/graph.json");
        cfg.getActiveProject().orElseThrow().setGraphFile(customGraph.toString());
        CodeGraph graph = new CodeGraph(
                List.of(GraphNode.type("A", "src/A.java", "java"),
                        GraphNode.type("B", "src/B.java", "java")),
                List.of(internal("A", "B")));

        uut(cfg, null, graph, List.of(chunk("A"), chunk("B"))).execute();

        Path sidecar = tempDir.resolve("artifacts/graph.json.ast-neighbours.json");
        assertThat(sidecar).exists();
        assertThat(readSidecar(sidecar).get("A")).containsExactly("B");
        // the graph artifact itself is still a readable graph
        CodeGraph read = new CodeGraphIo().read(customGraph);
        assertThat(read.nodes()).extracting(GraphNode::id).containsExactlyInAnyOrder("A", "B");
    }

    @Test
    void astNeighboursPathDerivesFromGraphSuffix() {
        assertThat(ChunkProcess.astNeighboursPath("/p/px-chunks.graph.json"))
                .isEqualTo("/p/px-chunks.ast-neighbours.json");
        assertThat(ChunkProcess.astNeighboursPath("/p/graph.json"))
                .isEqualTo("/p/graph.json.ast-neighbours.json");
    }

    // ------------------------------------------------------------------ helpers

    private Path sidecarPath() {
        return tempDir.resolve("out.ast-neighbours.json");
    }

    private Map<String, List<String>> readSidecar(Path path) throws Exception {
        return new JsonMapper().readValue(path.toFile(),
                new TypeReference<LinkedHashMap<String, List<String>>>() {});
    }

    private static GraphEdge internal(String source, String target) {
        return new GraphEdge(source, target, EdgeType.IMPORT, false);
    }

    private static PxChunk chunk(String graphNode) {
        return chunk(graphNode, graphNode);
    }

    private static PxChunk chunk(String id, String graphNode) {
        return PxChunk.create(c -> {
            c.setId(id);
            c.setMimeType("text/x-java");
            Map<String, String> metadata = new HashMap<>();
            metadata.put("graph_node", graphNode);
            c.setMetadata(metadata);
            c.setContent("content of " + id);
        });
    }

    private PrjXPConfig configWith(String passwordEnvKey) throws Exception {
        Path rootDir = tempDir.resolve("src");
        Files.createDirectories(rootDir);
        Files.writeString(rootDir.resolve("src.txt"), "some source text");

        ProjectDefinition pd = new ProjectDefinition();
        pd.setName("cwd");
        pd.setRootDir(rootDir.toString());
        pd.setJsonlFile(tempDir.resolve("out.jsonl").toString());

        PrjXPConfig cfg = new PrjXPConfig();
        cfg.setActiveProject("cwd");
        cfg.getProjects().add(pd);
        cfg.getTransfer().setPasswordEnv(passwordEnvKey);
        return cfg;
    }

    private ChunkProcess uut(PrjXPConfig cfg, String password, CodeGraph graph, List<PxChunk> chunks) {
        return new ChunkProcess(
                Mockito.mock(PxLogService.class),
                chunkFactory(chunks),
                Mockito.mock(ApplicationEventPublisher.class),
                noVeto(),
                cfg,
                resolverWith(cfg.getTransfer().getPasswordEnv(), password),
                registryWith(graph)
        );
    }

    private ChunkerFactory chunkFactory(List<PxChunk> chunks) {
        ChunkerFactory factory = Mockito.mock(ChunkerFactory.class);
        Mockito.when(factory.createChunker(Mockito.any(File.class))).thenAnswer(inv -> Stream.of(new PxChunker() {
            @Override
            public Stream<PxChunk> chunk(File file) {
                return chunks.stream().map(chunk -> {
                    chunk.setFile(file.getAbsolutePath());
                    return chunk;
                });
            }

            @Override
            public boolean matches(File file) {
                return true;
            }
        }));
        Mockito.when(factory.listPostWalkChunker()).thenReturn(Stream.empty());
        return factory;
    }

    private LanguagePackRegistry registryWith(CodeGraph graph) {
        LanguagePack fakePack = new LanguagePack() {
            @Override
            public String language() {
                return "java";
            }

            @Override
            public String mimeType() {
                return "text/x-java-code";
            }

            @Override
            public Optional<CodeGraphBuilder> codeGraph() {
                CodeGraphBuilder builder = new CodeGraphBuilder() {
                    @Override public boolean matches(File f) { return false; }
                    @Override public void beginRun() {}
                    @Override public void process(File f) {}
                    @Override public CodeGraph build() { return graph; }
                    @Override public Optional<CodeGraph> weightedGraph() {
                        return Optional.ofNullable(graph);
                    }
                };
                return Optional.of(builder);
            }
        };
        return new LanguagePackRegistry(List.of(fakePack), List.of());
    }

    private VetoRegistry noVeto() {
        VetoRegistry vetos = Mockito.mock(VetoRegistry.class);
        Mockito.when(vetos.shouldVeto(Mockito.any(Path.class))).thenReturn(false);
        Mockito.when(vetos.shouldVeto(Mockito.any(Path.class), Mockito.any(ProjectDefinition.class))).thenReturn(false);
        return vetos;
    }

    private TransferPasswordResolver resolverWith(String key, String value) {
        AbstractEnvironment env = new AbstractEnvironment() {
        };
        Map<String, Object> props = new HashMap<>();
        if (value != null) {
            props.put(key, value);
        }
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        return new TransferPasswordResolver(env);
    }
}
