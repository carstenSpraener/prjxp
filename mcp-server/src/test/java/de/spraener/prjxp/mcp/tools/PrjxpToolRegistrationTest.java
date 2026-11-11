package de.spraener.prjxp.mcp.tools;

import de.spraener.prjxp.common.model.EdgeType;
import de.spraener.prjxp.common.model.FileListResult;
import de.spraener.prjxp.common.model.FileView;
import de.spraener.prjxp.common.model.MethodView;
import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.common.model.SymbolReadResult;
import de.spraener.prjxp.common.toolregistry.GroovyToolExecutor;
import de.spraener.prjxp.common.toolregistry.ToolDefinition;
import de.spraener.prjxp.common.toolregistry.ToolRegistry;
import de.spraener.prjxp.mcp.ClusterMember;
import de.spraener.prjxp.mcp.ClusterOverviewView;
import de.spraener.prjxp.mcp.ClusterSummary;
import de.spraener.prjxp.mcp.ClusterView;
import de.spraener.prjxp.mcp.GraphNeighbor;
import de.spraener.prjxp.mcp.GraphView;
import de.spraener.prjxp.mcp.PrjxpMcpTool;
import de.spraener.prjxp.mcp.ProjectSummary;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PrjxpToolRegistrationTest {

    @Mock
    private PrjxpMcpTool mcpTool;

    private ToolRegistry registry;
    private PrjxpToolRegistration registration;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry(new GroovyToolExecutor());
        registration = new PrjxpToolRegistration(registry, mcpTool);
        registration.registerTools();
    }

    private String execute(String tool, Map<String, Object> params) {
        return registry.execute(tool, params);
    }

    @Test
    void registersAllTools() {
        assertThat(registry.getTools()).extracting(ToolDefinition::getName)
                .containsExactly("vectorSearch", "grep", "readFile", "readBySignature", "listProjects", "listFiles",
                        "graphFile", "graphNode", "graphCluster", "graphClusterOverview");
    }

    @Test
    void registeringTwiceDoesNotThrowButKeepsFirstRegistration() {
        // The second run hits name collisions — they must be logged, not thrown
        registration.registerTools();

        assertThat(registry.getTools()).hasSize(10);
    }

    @Test
    void toolSpecificationsCarryTypedSchema() {
        List<ToolSpecification> specs = registry.specifications();

        ToolSpecification grep = specs.stream().filter(s -> s.name().equals("grep")).findFirst().orElseThrow();
        assertThat(grep.parameters().required()).containsExactly("query", "project");
        assertThat(grep.parameters().properties()).containsKeys("query", "project", "language", "limit", "includeTests");

        ToolSpecification vectorSearch = specs.stream()
                .filter(s -> s.name().equals("vectorSearch")).findFirst().orElseThrow();
        assertThat(vectorSearch.parameters().required()).containsExactly("userQuestion", "projectName");
        assertThat(vectorSearch.parameters().properties()).containsKeys("userQuestion", "projectName", "similarity", "maxResults", "skeletonsOnly", "includeTests");

        ToolSpecification graphCluster = specs.stream()
                .filter(s -> s.name().equals("graphCluster")).findFirst().orElseThrow();
        assertThat(graphCluster.parameters().required()).containsExactly("clusterId", "project");
        assertThat(graphCluster.parameters().properties()).containsKeys("clusterId", "project", "topN");

        ToolSpecification graphClusterOverview = specs.stream()
                .filter(s -> s.name().equals("graphClusterOverview")).findFirst().orElseThrow();
        assertThat(graphClusterOverview.parameters().required()).containsExactly("project");
        assertThat(graphClusterOverview.parameters().properties()).containsKeys("project", "topN");
    }

    // ==================== vectorSearch ====================

    @Test
    void vectorSearchPassesTypedArguments() {
        when(mcpTool.vectorSearch(eq("find foo"), eq("p1"), eq(0.7), eq(5), eq(true), any(), any(), any())).thenReturn("ctx");

        String result = execute("vectorSearch", Map.of(
                "userQuestion", "find foo", "projectName", "p1",
                "similarity", 0.7, "maxResults", 5, "skeletonsOnly", true));

        assertThat(result).contains("ctx");
    }

    @Test
    void vectorSearchCoercesStringNumbers() {
        when(mcpTool.vectorSearch(eq("q"), any(), eq(0.9), eq(3), eq(false), any(), any(), any())).thenReturn("ctx");

        execute("vectorSearch", Map.of(
                "userQuestion", "q", "similarity", "0.9", "maxResults", "3", "skeletonsOnly", "false"));

        verify(mcpTool).vectorSearch(eq("q"), any(), eq(0.9), eq(3), eq(false), any(), any(), any());
    }

    @Test
    void vectorSearchPassesIncludeTestsThrough() {
        when(mcpTool.vectorSearch(eq("q"), any(), any(), any(), any(), any(), any(), eq(true))).thenReturn("ctx");

        execute("vectorSearch", Map.of("userQuestion", "q", "includeTests", true));

        verify(mcpTool).vectorSearch(eq("q"), any(), any(), any(), any(), any(), any(), eq(true));
    }

    @Test
    void vectorSearchWithoutQuestionReturnsError() {
        assertThat(execute("vectorSearch", Map.of())).startsWith("ERROR:");
    }

    // ==================== grep ====================

    @Test
    void grepFormatsHits() {
        SearchHit hit = new SearchHit("chunk-1", 0.9, "Foo.java", 10, 20, "public void foo()", "java", Map.of());
        when(mcpTool.grep(eq("foo"), eq("p1"), any(), any(), any())).thenReturn(List.of(hit));

        String result = execute("grep", Map.of("query", "foo", "project", "p1"));

        assertThat(result).contains("Found 1 result(s)");
        assertThat(result).contains("File: Foo.java");
        assertThat(result).contains("Lines: 10-20");
        assertThat(result).contains("public void foo()");
        assertThat(result).contains("Chunk: chunk-1");
    }

    @Test
    void grepWithoutHitsMentionsProject() {
        when(mcpTool.grep(any(), eq("p1"), any(), any(), any())).thenReturn(List.of());

        String result = execute("grep", Map.of("query", "nothing", "project", "p1"));

        assertThat(result).contains("No results found");
        assertThat(result).contains("p1");
    }

    @Test
    void grepPassesIncludeTestsThrough() {
        when(mcpTool.grep(eq("q"), any(), any(), any(), eq(false))).thenReturn(List.of());

        execute("grep", Map.of("query", "q", "includeTests", false));

        verify(mcpTool).grep(eq("q"), any(), any(), any(), eq(false));
    }

    @Test
    void grepWithoutQueryReturnsError() {
        assertThat(execute("grep", Map.of())).startsWith("ERROR:");
    }

    // ==================== readFile ====================

    @Test
    void readFileRendersContent() {
        FileView view = new FileView("Foo.java", "java", 2, "class Foo {}", false, 1, 1, List.of(), null);
        when(mcpTool.readFile(eq("Foo.java"), any(), any(), any())).thenReturn(view);

        String result = execute("readFile", Map.of("file", "Foo.java"));

        assertThat(result).contains("File: Foo.java");
        assertThat(result).contains("Total lines: 1");
        assertThat(result).contains("--- FILE CONTENT ---");
        assertThat(result).contains("class Foo {}");
    }

    @Test
    void readFileReportsError() {
        when(mcpTool.readFile(any(), any(), any(), any()))
                .thenReturn(FileView.error("X.java", "not found"));

        assertThat(execute("readFile", Map.of("file", "X.java"))).contains("ERROR reading file: not found");
    }

    @Test
    void readFileListsCandidatesWhenAmbiguous() {
        when(mcpTool.readFile(any(), any(), any(), any()))
                .thenReturn(FileView.ambiguous("Foo.java", List.of("a/Foo.java", "b/Foo.java")));

        String result = execute("readFile", Map.of("file", "Foo.java"));

        assertThat(result).contains("Ambiguous file path");
        assertThat(result).contains("a/Foo.java");
        assertThat(result).contains("b/Foo.java");
    }

    @Test
    void readFileWithoutFileReturnsError() {
        assertThat(execute("readFile", Map.of())).startsWith("ERROR:");
    }

    // ==================== readBySignature ====================

    @Test
    void readBySignatureRendersMatches() {
        MethodView match = new MethodView("com.example.Foo#bar", "Foo.java", 5, 9, "/** doc */", "void bar() {}");
        when(mcpTool.readBySignature(eq("bar"), any(), any()))
                .thenReturn(new SymbolReadResult(List.of(match), List.of("com.other.Foo#bar"), null));

        String result = execute("readBySignature", Map.of("method", "bar"));

        assertThat(result).contains("FQN: com.example.Foo#bar");
        assertThat(result).contains("Lines: 5-9");
        assertThat(result).contains("/** doc */");
        assertThat(result).contains("void bar() {}");
        assertThat(result).contains("com.other.Foo#bar");
    }

    @Test
    void readBySignatureWithoutMatches() {
        when(mcpTool.readBySignature(any(), any(), any()))
                .thenReturn(new SymbolReadResult(List.of(), List.of(), null));

        assertThat(execute("readBySignature", Map.of("method", "nope"))).contains("No matches found");
    }

    @Test
    void readBySignatureReportsError() {
        when(mcpTool.readBySignature(any(), any(), any()))
                .thenReturn(SymbolReadResult.error("index missing"));

        assertThat(execute("readBySignature", Map.of("method", "bar"))).contains("ERROR reading symbol: index missing");
    }

    @Test
    void readBySignatureWithoutMethodReturnsError() {
        assertThat(execute("readBySignature", Map.of())).startsWith("ERROR:");
    }

    // ==================== listProjects ====================

    @Test
    void listProjectsJoinsNames() {
        when(mcpTool.listProjects()).thenReturn(List.of(
                new ProjectSummary("a", null),
                new ProjectSummary("b", null)));

        assertThat(execute("listProjects", Map.of())).isEqualTo("a, b");
    }

    @Test
    void listProjectsFormatsTimestamp() {
        when(mcpTool.listProjects()).thenReturn(List.of(
                new ProjectSummary("a", 1700000000000L),
                new ProjectSummary("b", null)));

        assertThat(execute("listProjects", Map.of()))
                .isEqualTo("a (last updated: " + Instant.ofEpochMilli(1700000000000L).toString() + "), b");
    }

    @Test
    void listProjectsWhenEmpty() {
        when(mcpTool.listProjects()).thenReturn(List.of());

        assertThat(execute("listProjects", Map.of())).isEqualTo("No searchable projects available.");
    }

    // ==================== listFiles ====================

    @Test
    void listFilesFormatsPaths() {
        when(mcpTool.listFiles(any(), any(), any()))
                .thenReturn(new FileListResult(List.of("src/a.java", "src/b.java"), false));

        String result = execute("listFiles", Map.of("path", "src"));

        assertThat(result).contains("Found 2 file(s)");
        assertThat(result).contains("src/a.java");
        assertThat(result).contains("src/b.java");
    }

    @Test
    void listFilesMentionsTruncation() {
        when(mcpTool.listFiles(any(), any(), any()))
                .thenReturn(new FileListResult(List.of("src/a.java"), true));

        String result = execute("listFiles", Map.of());

        assertThat(result).contains("truncated");
    }

    @Test
    void listFilesWithoutMatches() {
        when(mcpTool.listFiles(any(), any(), any())).thenReturn(new FileListResult(List.of(), false));

        assertThat(execute("listFiles", Map.of())).contains("No files found");
    }

    // ==================== graphFile / graphNode ====================

    @Test
    void graphFileToolFormatsView() {
        GraphView view = new GraphView("demo", "src/A.java", "demo.A", "c0", 0.9,
                List.of(new GraphNeighbor(EdgeType.IMPORT, "demo.B", "src/B.java", false)),
                List.of(new GraphNeighbor(EdgeType.EXTENDS, "demo.C", "src/C.java", false)),
                List.of(), null);
        when(mcpTool.graphFile(eq("src/A.java"), any(), any(), any())).thenReturn(view);

        String result = execute("graphFile", Map.of("file", "src/A.java"));

        assertThat(result).contains("File: src/A.java");
        assertThat(result).contains("demo.A");
        assertThat(result).contains("cluster:c0");
        assertThat(result).contains("weight:0.90");
        assertThat(result).contains("IMPORT -> demo.B");
        assertThat(result).contains("[src/B.java]");
        assertThat(result).contains("<- EXTENDS  demo.C");
    }

    @Test
    void graphFileToolRendersError() {
        when(mcpTool.graphFile(any(), any(), any(), any()))
                .thenReturn(GraphView.unavailable("Graph nicht verfügbar, bitte reindexieren"));

        assertThat(execute("graphFile", Map.of("file", "src/A.java")))
                .isEqualTo("ERROR: Graph nicht verfügbar, bitte reindexieren");
    }

    @Test
    void graphFileToolRendersCandidates() {
        when(mcpTool.graphFile(any(), any(), any(), any()))
                .thenReturn(GraphView.ambiguous("demo", "A.java", List.of("src/x/A.java", "src/y/A.java")));

        String result = execute("graphFile", Map.of("file", "A.java"));

        assertThat(result).contains("Ambiguous");
        assertThat(result).contains("src/x/A.java");
        assertThat(result).contains("src/y/A.java");
    }

    @Test
    void graphFileToolRendersNullView() {
        when(mcpTool.graphFile(any(), any(), any(), any())).thenReturn(null);

        assertThat(execute("graphFile", Map.of("file", "src/A.java"))).isEqualTo("No graph view returned.");
    }

    @Test
    void graphNodeToolRendersNodeHeaderWhenNoFile() {
        GraphView view = new GraphView("demo", null, "demo.A", null, null,
                List.of(), List.of(), List.of(), null);
        when(mcpTool.graphNode(any(), any(), any(), any())).thenReturn(view);

        String result = execute("graphNode", Map.of("symbol", "demo.A"));

        assertThat(result).contains("Node: demo.A");
        assertThat(result).contains("(no edges)");
    }

    @Test
    void graphNodeToolRequiresSymbol() {
        assertThat(execute("graphNode", Map.of()))
                .isEqualTo("ERROR: 'symbol' parameter is required for graphNode.");
        verifyNoInteractions(mcpTool);
    }

    // ==================== graphCluster ====================

    @Test
    void graphClusterToolFormatsView() {
        ClusterView view = new ClusterView("demo", "c0", 3,
                List.of(new ClusterMember("demo.A", "src/A.java", 0.9, 2, 3),
                        new ClusterMember("demo.B", "src/B.java", 0.5, 1, 1)),
                null);
        when(mcpTool.graphCluster(eq("c0"), any(), any())).thenReturn(view);

        String result = execute("graphCluster", Map.of("clusterId", "c0", "project", "demo"));

        assertThat(result).contains("Cluster c0 — 3 members (top 2):");
        assertThat(result).contains("0.90  in=2 out=3  demo.A  (src/A.java)");
        assertThat(result).contains("0.50  in=1 out=1  demo.B  (src/B.java)");
    }

    @Test
    void graphClusterToolRendersError() {
        when(mcpTool.graphCluster(any(), any(), any()))
                .thenReturn(ClusterView.unavailable("Cluster nicht gefunden: c9"));

        assertThat(execute("graphCluster", Map.of("clusterId", "c9", "project", "demo")))
                .isEqualTo("ERROR: Cluster nicht gefunden: c9");
    }

    @Test
    void graphClusterToolRequiresClusterId() {
        assertThat(execute("graphCluster", Map.of()))
                .isEqualTo("ERROR: 'clusterId' parameter is required for graphCluster.");
        verifyNoInteractions(mcpTool);
    }

    // ==================== graphClusterOverview ====================

    @Test
    void graphClusterOverviewToolFormatsView() {
        ClusterOverviewView view = new ClusterOverviewView("demo", 3,
                List.of(new ClusterSummary("c0", 3, List.of("demo.A", "demo.B", "demo.C")),
                        new ClusterSummary("c1", 2, List.of("demo.D", "demo.E"))),
                null);
        when(mcpTool.graphClusterOverview(any(), any())).thenReturn(view);

        String result = execute("graphClusterOverview", Map.of("project", "demo"));

        assertThat(result).contains("Clusters: 3 total, top 2 largest:");
        assertThat(result).contains("c0  (3 members):  demo.A, demo.B, demo.C");
        assertThat(result).contains("c1  (2 members):  demo.D, demo.E");
    }

    @Test
    void graphClusterOverviewToolRendersError() {
        when(mcpTool.graphClusterOverview(any(), any()))
                .thenReturn(ClusterOverviewView.unavailable("Graph nicht verfügbar, bitte reindexieren"));

        assertThat(execute("graphClusterOverview", Map.of("project", "demo")))
                .isEqualTo("ERROR: Graph nicht verfügbar, bitte reindexieren");
    }

    // ==================== argument coercion & truncation ====================

    @Test
    void stringIgnoresBlankAndNullLiterals() {
        assertThat(PrjxpToolRegistration.string(Map.of("a", "  "), "a")).isNull();
        assertThat(PrjxpToolRegistration.string(Map.of("a", "null"), "a")).isNull();
        assertThat(PrjxpToolRegistration.string(Map.of("a", " x "), "a")).isEqualTo("x");
        assertThat(PrjxpToolRegistration.string(null, "a")).isNull();
    }

    @Test
    void numericCoercionHandlesGarbage() {
        assertThat(PrjxpToolRegistration.integer(Map.of("a", "nope"), "a")).isNull();
        assertThat(PrjxpToolRegistration.number(Map.of("a", "nope"), "a")).isNull();
        assertThat(PrjxpToolRegistration.integer(Map.of("a", 7L), "a")).isEqualTo(7);
        assertThat(PrjxpToolRegistration.number(Map.of("a", 1), "a")).isEqualTo(1.0d);
        assertThat(PrjxpToolRegistration.bool(Map.of("a", "true"), "a")).isTrue();
        assertThat(PrjxpToolRegistration.bool(Map.of(), "a")).isNull();
    }

    @Test
    void truncateAppendsMarker() {
        String text = "x".repeat(50);

        assertThat(PrjxpToolRegistration.truncate(text, 100)).isEqualTo(text);
        assertThat(PrjxpToolRegistration.truncate(text, 10)).startsWith("xxxxxxxxxx").contains("truncated, total 50");
        assertThat(PrjxpToolRegistration.truncate(null, 10)).isEqualTo("(null)");
    }
}

