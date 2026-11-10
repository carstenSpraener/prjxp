package de.spraener.prjxp.mcp;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.graph.CodeGraphIo;
import de.spraener.prjxp.common.model.CodeGraph;
import de.spraener.prjxp.common.model.EdgeType;
import de.spraener.prjxp.common.model.GraphEdge;
import de.spraener.prjxp.common.model.GraphNode;
import de.spraener.prjxp.common.transfer.TransferCrypto;
import de.spraener.prjxp.common.transfer.TransferPasswordResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProjectGraphStoreTest {

    @Mock
    private PrjXPConfig cfg;
    @Mock
    private TransferPasswordResolver passwordResolver;
    @Mock
    private ProjectRegistry projectRegistry;

    @TempDir
    Path tempDir;

    private ProjectGraphStore store;

    @BeforeEach
    void setUp() {
        store = new ProjectGraphStore(cfg, passwordResolver, projectRegistry);
    }

    private ProjectDefinition definition() {
        ProjectDefinition pd = new ProjectDefinition();
        pd.setName("demo");
        pd.setRootDir(tempDir.toString());
        pd.setJsonlFile("out.jsonl");   // resolvedGraphFile() -> <tempDir>/out.graph.json
        return pd;
    }

    private Path graphPath() {
        return tempDir.resolve("out.graph.json");
    }

    private CodeGraph sampleGraph() {
        return new CodeGraph(
                List.of(
                        new GraphNode("demo.A", "src/A.java", "java", false, "c0", 0.9),
                        new GraphNode("demo.B", "src/B.java", "java", false, "c1", 0.2)),
                List.of(new GraphEdge("demo.A", "demo.B", EdgeType.IMPORT, false)));
    }

    private CodeGraph secondGraph() {
        return new CodeGraph(
                List.of(new GraphNode("demo.Z", "src/Z.java", "java", false, "c9", 0.5)),
                List.of());
    }

    /** Stubs only what every test uses: project resolution + definition lookup. */
    private void stubProject() {
        when(projectRegistry.resolve("demo")).thenReturn("demo");
        when(cfg.getProjectDefinition("demo")).thenReturn(Optional.of(definition()));
    }

    /** Stubs the transfer config + password resolution (only needed when a file is actually loaded). */
    private void stubPassword(char[] password) {
        PrjXPConfig.TransferConfig transfer = new PrjXPConfig.TransferConfig();
        transfer.setPasswordEnv("TEST_GRAPH_PW");
        when(cfg.getTransfer()).thenReturn(transfer);
        when(passwordResolver.resolvePassword("TEST_GRAPH_PW")).thenReturn(password);
    }

    @Test
    void loadsPlainGraphFile() {
        stubProject();
        stubPassword(null);
        new CodeGraphIo().write(graphPath(), sampleGraph());

        Optional<CodeGraph> result = store.graphFor("demo");

        assertThat(result).isPresent();
        assertThat(result.get().nodes()).hasSize(2);
        assertThat(result.get().edges()).hasSize(1);
    }

    @Test
    void cachesUntilMtimeChanges() throws Exception {
        stubProject();
        stubPassword(null);
        new CodeGraphIo().write(graphPath(), sampleGraph());
        long oldMtime = Files.getLastModifiedTime(graphPath()).toMillis();

        Optional<CodeGraph> first = store.graphFor("demo");
        assertThat(first).isPresent();
        assertThat(first.get().nodes()).extracting(GraphNode::id).containsExactly("demo.A", "demo.B");

        // Unchanged mtime/size: the cached instance is served
        assertThat(store.graphFor("demo")).containsSame(first.get());

        // A vanished file is NOT served from the cache
        Files.delete(graphPath());
        assertThat(store.graphFor("demo")).isEmpty();

        // New graph with a changed mtime: the next load returns the new graph
        new CodeGraphIo().write(graphPath(), secondGraph());
        Files.setLastModifiedTime(graphPath(), FileTime.fromMillis(oldMtime + 5000));

        Optional<CodeGraph> reloaded = store.graphFor("demo");
        assertThat(reloaded).isPresent();
        assertThat(reloaded.get().nodes()).extracting(GraphNode::id).containsExactly("demo.Z");
    }

    @Test
    void missingFileReturnsEmpty() {
        stubProject();

        assertThat(store.graphFor("demo")).isEmpty();
    }

    @Test
    void nullGraphFileReturnsEmpty() {
        when(projectRegistry.resolve("demo")).thenReturn("demo");
        ProjectDefinition pd = new ProjectDefinition();
        pd.setName("demo");
        pd.setRootDir(tempDir.toString());
        // no jsonlFile, no graphFile -> resolvedGraphFile() is null
        when(cfg.getProjectDefinition("demo")).thenReturn(Optional.of(pd));

        assertThat(store.graphFor("demo")).isEmpty();
    }

    @Test
    void unknownProjectReturnsEmpty() {
        when(projectRegistry.resolve("demo")).thenReturn("demo");
        when(cfg.getProjectDefinition("demo")).thenReturn(Optional.empty());

        assertThat(store.graphFor("demo")).isEmpty();
    }

    @Test
    void encryptedGraphIsReadWithPassword() throws Exception {
        stubProject();
        stubPassword("secret".toCharArray());
        char[] pw = "secret".toCharArray();
        try (OutputStream raw = Files.newOutputStream(graphPath());
             OutputStream enc = TransferCrypto.openEncryptedOutputStream(raw, pw)) {
            new CodeGraphIo().write(enc, sampleGraph());
        }

        Optional<CodeGraph> result = store.graphFor("demo");

        assertThat(result).isPresent();
        assertThat(result.get().nodes()).hasSize(2);
        assertThat(result.get().edges()).hasSize(1);
    }

    @Test
    void encryptedGraphWithoutPasswordReturnsEmpty() throws Exception {
        stubProject();
        stubPassword(null);
        char[] pw = "secret".toCharArray();
        try (OutputStream raw = Files.newOutputStream(graphPath());
             OutputStream enc = TransferCrypto.openEncryptedOutputStream(raw, pw)) {
            new CodeGraphIo().write(enc, sampleGraph());
        }

        assertThat(store.graphFor("demo")).isEmpty();
    }

    @Test
    void corruptGraphReturnsEmpty() throws Exception {
        stubProject();
        stubPassword(null);
        Files.writeString(graphPath(), "not json");

        assertThat(store.graphFor("demo")).isEmpty();
    }

    @Test
    void registryDefinitionIsUsedWhenConfigHasNoProject() {
        when(projectRegistry.resolve("hubproj")).thenReturn("hubproj");
        when(cfg.getProjectDefinition("hubproj")).thenReturn(Optional.empty());
        ProjectDefinition pd = new ProjectDefinition();
        pd.setName("hubproj");
        pd.setRootDir(tempDir.toString());
        pd.setJsonlFile("out.jsonl");   // resolvedGraphFile() -> <tempDir>/out.graph.json
        when(projectRegistry.definitionOf("hubproj")).thenReturn(pd);
        stubPassword(null);
        new CodeGraphIo().write(graphPath(), sampleGraph());

        Optional<CodeGraph> result = store.graphFor("hubproj");

        assertThat(result).isPresent();
        assertThat(result.get().nodes()).hasSize(2);
        assertThat(result.get().edges()).hasSize(1);
    }

    @Test
    void unresolvableDefinitionReturnsEmpty() {
        when(projectRegistry.resolve("ghost")).thenReturn("ghost");
        when(cfg.getProjectDefinition("ghost")).thenReturn(Optional.empty());
        when(projectRegistry.definitionOf("ghost")).thenReturn(null);

        assertThat(store.graphFor("ghost")).isEmpty();
    }
}
