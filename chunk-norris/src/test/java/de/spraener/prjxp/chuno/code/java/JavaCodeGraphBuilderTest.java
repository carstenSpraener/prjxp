package de.spraener.prjxp.chuno.code.java;

import de.spraener.prjxp.chuno.spring.SpringPreWalkEvent;
import de.spraener.prjxp.chuno.veto.VetoRegistry;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.language.LeidenWeighter;
import de.spraener.prjxp.common.model.CodeGraph;
import de.spraener.prjxp.common.model.EdgeType;
import de.spraener.prjxp.common.model.GraphEdge;
import de.spraener.prjxp.common.model.GraphNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plain-JUnit tests for {@link JavaCodeGraphBuilder} (no Spring context). The real
 * {@link JavaFQNamesMapper} is driven directly via its public listener method.
 */
class JavaCodeGraphBuilderTest {

    @TempDir
    Path root;

    private JavaFQNamesMapper mapper;
    private JavaCodeGraphBuilder builder;
    private ProjectDefinition pd;
    private VetoRegistry vetoRegistry;

    @BeforeEach
    void setUp() throws Exception {
        Files.createDirectories(root.resolve("demo"));
        Files.writeString(root.resolve("demo/Base.java"),
                "package demo;\npublic class Base {}\n");
        Files.writeString(root.resolve("demo/Helper.java"), """
                package demo;
                public class Helper {
                    public static Helper make() { return new Helper(); }
                    public String value() { return ""; }
                }
                """);
        Files.writeString(root.resolve("demo/Service.java"), """
                package demo;
                import java.util.List;
                import java.util.*;
                import static java.lang.Math.max;
                import static java.lang.Math.*;
                public class Service extends Base implements Runnable {
                    private Helper helper;
                    public Service(Helper h) { this.helper = h; }
                    public Helper getHelper() { return helper; }
                    public String run(Helper h) { return h.value() + helper.value(); }
                    public void f(int n) {}
                    public void external(List<String> list) {
                        list.add("x");
                        System.out.println("x");
                    }
                    public String chained() { return getHelper().value(); }
                    public String fieldCall() { this.helper.value(); return ""; }
                    public String localVar() { Helper local = helper; return local.value(); }
                    public String staticCall() { return Helper.make().value(); }
                    public void newExpr() { new Helper().value(); }
                    public void superCall() { super.toString(); }
                    class Inner { String outerField() { return helper.value(); } }
                }
                """);

        pd = new ProjectDefinition();
        pd.setName("p");
        pd.setRootDir(root.toString());

        mapper = new JavaFQNamesMapper();
        mapper.fillFQNamesMap(new SpringPreWalkEvent<>(new PrjXPConfig(), pd));

        vetoRegistry = mock(VetoRegistry.class);
        when(vetoRegistry.shouldVeto(any(Path.class), any(ProjectDefinition.class))).thenReturn(false);
        builder = new JavaCodeGraphBuilder(mapper, new LeidenWeighter(), vetoRegistry);
        builder.setRootDir(root);
    }

    private CodeGraph buildAll() {
        builder.beginRun();
        builder.process(root.resolve("demo/Base.java").toFile());
        builder.process(root.resolve("demo/Helper.java").toFile());
        builder.process(root.resolve("demo/Service.java").toFile());
        return builder.build();
    }

    private boolean hasNode(CodeGraph graph, String id) {
        return graph.nodes().stream().anyMatch(n -> n.id().equals(id));
    }

    private boolean hasEdge(CodeGraph graph, String source, String target, EdgeType type) {
        return graph.edges().stream()
                .anyMatch(e -> e.source().equals(source) && e.target().equals(target) && e.type() == type);
    }

    private boolean hasEdge(CodeGraph graph, String source, String target, EdgeType type, boolean external) {
        return graph.edges().stream()
                .anyMatch(e -> e.source().equals(source) && e.target().equals(target)
                        && e.type() == type && e.external() == external);
    }

    @Test
    void build_producesExpectedNodes() {
        CodeGraph graph = buildAll();
        assertThat(hasNode(graph, "demo.Base")).isTrue();
        assertThat(hasNode(graph, "demo.Helper")).isTrue();
        assertThat(hasNode(graph, "demo.Service")).isTrue();
        assertThat(hasNode(graph, "demo.Helper#value()")).isTrue();
        assertThat(hasNode(graph, "demo.Service#run(demo.Helper)")).isTrue();
        assertThat(hasNode(graph, "demo.Service#f(int)")).isTrue();
    }

    @Test
    void build_producesExpectedEdges() {
        CodeGraph graph = buildAll();
        assertThat(hasEdge(graph, "demo.Service", "demo.Base", EdgeType.EXTENDS, false)).isTrue();
        assertThat(hasEdge(graph, "demo.Service", "Runnable", EdgeType.IMPLEMENTS, true)).isTrue();
        assertThat(hasEdge(graph, "demo.Service", "java.util.List", EdgeType.IMPORT)).isTrue();
        assertThat(hasEdge(graph, "demo.Service", "demo.Helper", EdgeType.FIELD, false)).isTrue();
        assertThat(hasEdge(graph, "demo.Service#run(demo.Helper)", "demo.Helper", EdgeType.PARAM)).isTrue();
        assertThat(hasEdge(graph, "demo.Service#getHelper()", "demo.Helper", EdgeType.RETURN)).isTrue();
        assertThat(hasEdge(graph, "demo.Service#run(demo.Helper)", "demo.Helper#value()", EdgeType.CALL)).isTrue();
    }

    @Test
    void build_noCallEdgesToExternalReceivers() {
        CodeGraph graph = buildAll();
        assertThat(graph.edges().stream()
                .filter(e -> e.type() == EdgeType.CALL)
                .map(GraphEdge::target)
                .toList())
                .noneMatch(t -> t.contains("add") || t.contains("println"));
    }

    @Test
    void build_dedupesDuplicateCallEdges() {
        CodeGraph graph = buildAll();
        long callCount = graph.edges().stream()
                .filter(e -> e.type() == EdgeType.CALL
                        && e.source().equals("demo.Service#run(demo.Helper)")
                        && e.target().equals("demo.Helper#value()"))
                .count();
        // h.value() and helper.value() both resolve to demo.Helper#value() -> one deduped edge
        assertThat(callCount).isEqualTo(1);
    }

    @Test
    void weightedGraph_emptyBeforeOnPreWalk_presentAfter() {
        assertThat(builder.weightedGraph()).isEmpty();

        builder.onPreWalk(new SpringPreWalkEvent<>(new PrjXPConfig(), pd));

        Optional<CodeGraph> weighted = builder.weightedGraph();
        assertThat(weighted).isPresent();
        CodeGraph graph = weighted.get();
        assertThat(graph.nodes()).isNotEmpty();
        assertThat(graph.nodes())
                .allSatisfy(n -> {
                    assertThat(n.clusterId()).isNotNull();
                    assertThat(n.weight()).isBetween(0.0, 1.0);
                });
    }

    @Test
    void build_resolvesCallChainsFieldAccessLocalsStaticAndNewReceivers() {
        CodeGraph graph = buildAll();

        assertThat(hasEdge(graph, "demo.Service#chained()", "demo.Helper#value()", EdgeType.CALL)).isTrue();
        assertThat(hasEdge(graph, "demo.Service#fieldCall()", "demo.Helper#value()", EdgeType.CALL)).isTrue();
        assertThat(hasEdge(graph, "demo.Service#localVar()", "demo.Helper#value()", EdgeType.CALL)).isTrue();
        assertThat(hasEdge(graph, "demo.Service#staticCall()", "demo.Helper#make()", EdgeType.CALL)).isTrue();
        assertThat(hasEdge(graph, "demo.Service#staticCall()", "demo.Helper#value()", EdgeType.CALL)).isTrue();
        assertThat(hasEdge(graph, "demo.Service#newExpr()", "demo.Helper#value()", EdgeType.CALL)).isTrue();

        // Nested type: derive the node id from the graph instead of hardcoding the $ format.
        String innerMethodId = graph.nodes().stream()
                .map(GraphNode::id)
                .filter(id -> id.contains("Inner") && id.contains("outerField"))
                .findFirst()
                .orElseThrow();
        assertThat(hasEdge(graph, innerMethodId, "demo.Helper#value()", EdgeType.CALL)).isTrue();

        // superCall() must not throw and must not produce a CALL edge to toString.
        assertThat(graph.edges().stream()
                .filter(e -> e.type() == EdgeType.CALL && e.source().equals("demo.Service#superCall()"))
                .anyMatch(e -> e.target().contains("toString")))
                .isFalse();
    }

    @Test
    void build_enumAndRecordImplementEdges() throws Exception {
        Files.writeString(root.resolve("demo/Color.java"),
                "package demo;\npublic enum Color implements Runnable { RED; public void run() {} }\n");
        Files.writeString(root.resolve("demo/Point.java"),
                "package demo;\npublic record Point(int x) implements Runnable { public void run() {} }\n");

        builder.beginRun();
        builder.process(root.resolve("demo/Color.java").toFile());
        builder.process(root.resolve("demo/Point.java").toFile());
        CodeGraph graph = builder.build();

        assertThat(hasEdge(graph, "demo.Color", "Runnable", EdgeType.IMPLEMENTS, true)).isTrue();
        assertThat(hasEdge(graph, "demo.Point", "Runnable", EdgeType.IMPLEMENTS, true)).isTrue();
    }

    @Test
    void build_staticImportsTargetDeclaringType() {
        CodeGraph graph = buildAll();

        long mathImports = graph.edges().stream()
                .filter(e -> e.type() == EdgeType.IMPORT
                        && e.source().equals("demo.Service")
                        && e.target().equals("java.lang.Math"))
                .count();
        // `import static java.lang.Math.max` and `import static java.lang.Math.*` both
        // target the declaring type and must dedupe to exactly one edge.
        assertThat(mathImports).isEqualTo(1);

        assertThat(graph.edges()).noneMatch(e -> e.type() == EdgeType.IMPORT
                && (e.target().equals("java.lang.Math.*") || e.target().equals("java.lang.Math.max")));
    }

    @Test
    void build_wildcardNonStaticImportAddsNoEdge() {
        CodeGraph graph = buildAll();
        assertThat(graph.edges()).noneMatch(e -> e.target().endsWith(".*"));
    }

    @Test
    void process_invalidJavaFile_doesNotThrowAndAddsNothing() throws Exception {
        Path broken = root.resolve("demo/Broken.java");
        Files.writeString(broken, "package demo; public class Broken {\n"); // missing closing brace

        builder.beginRun();
        assertThatCode(() -> builder.process(broken.toFile())).doesNotThrowAnyException();

        CodeGraph graph = builder.build();
        assertThat(graph.nodes()).isEmpty();
    }

    @Test
    void onPreWalk_missingRootDir_doesNotThrow() {
        pd.setRootDir(root.resolve("does-not-exist").toString());

        assertThatCode(() -> builder.onPreWalk(new SpringPreWalkEvent<>(new PrjXPConfig(), pd)))
                .doesNotThrowAnyException();

        assertThat(builder.weightedGraph()).isPresent();
        assertThat(builder.weightedGraph().get().nodes()).isEmpty();
    }

    @Test
    void build_withoutRootDir_usesAbsoluteFilePaths() {
        JavaCodeGraphBuilder noRoot = new JavaCodeGraphBuilder(mapper, new LeidenWeighter(), vetoRegistry);
        // no setRootDir call

        noRoot.beginRun();
        noRoot.process(root.resolve("demo/Helper.java").toFile());
        CodeGraph graph = noRoot.build();

        GraphNode helper = graph.nodes().stream()
                .filter(n -> n.id().equals("demo.Helper"))
                .findFirst()
                .orElseThrow();
        assertThat(helper.file()).contains("Helper.java");
        assertThat(Path.of(helper.file())).isAbsolute();
    }
}
