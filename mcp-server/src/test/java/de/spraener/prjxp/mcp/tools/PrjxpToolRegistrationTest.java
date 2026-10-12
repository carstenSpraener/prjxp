package de.spraener.prjxp.mcp.tools;

import de.spraener.prjxp.common.model.FileView;
import de.spraener.prjxp.common.model.MethodView;
import de.spraener.prjxp.common.model.SearchHit;
import de.spraener.prjxp.common.model.SymbolReadResult;
import de.spraener.prjxp.common.toolregistry.GroovyToolExecutor;
import de.spraener.prjxp.common.toolregistry.ToolDefinition;
import de.spraener.prjxp.common.toolregistry.ToolRegistry;
import de.spraener.prjxp.mcp.PrjxpMcpTool;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
    void registersAllFiveTools() {
        assertThat(registry.getTools()).extracting(ToolDefinition::getName)
                .containsExactly("vectorSearch", "grep", "readFile", "readBySignature", "listProjects");
    }

    @Test
    void registeringTwiceDoesNotThrowButKeepsFirstRegistration() {
        // The second run hits name collisions — they must be logged, not thrown
        registration.registerTools();

        assertThat(registry.getTools()).hasSize(5);
    }

    @Test
    void toolSpecificationsCarryTypedSchema() {
        List<ToolSpecification> specs = registry.specifications();

        ToolSpecification grep = specs.stream().filter(s -> s.name().equals("grep")).findFirst().orElseThrow();
        assertThat(grep.parameters().required()).containsExactly("query");
        assertThat(grep.parameters().properties()).containsKeys("query", "project", "language", "limit");

        ToolSpecification vectorSearch = specs.stream()
                .filter(s -> s.name().equals("vectorSearch")).findFirst().orElseThrow();
        assertThat(vectorSearch.parameters().required()).containsExactly("userQuestion");
    }

    // ==================== vectorSearch ====================

    @Test
    void vectorSearchPassesTypedArguments() {
        when(mcpTool.vectorSearch(eq("find foo"), eq("p1"), eq(0.7), eq(5), eq(true))).thenReturn("ctx");

        String result = execute("vectorSearch", Map.of(
                "userQuestion", "find foo", "projectName", "p1",
                "similarity", 0.7, "maxResults", 5, "skeletonsOnly", true));

        assertThat(result).contains("ctx");
    }

    @Test
    void vectorSearchCoercesStringNumbers() {
        when(mcpTool.vectorSearch(eq("q"), any(), eq(0.9), eq(3), eq(false))).thenReturn("ctx");

        execute("vectorSearch", Map.of(
                "userQuestion", "q", "similarity", "0.9", "maxResults", "3", "skeletonsOnly", "false"));

        verify(mcpTool).vectorSearch(eq("q"), any(), eq(0.9), eq(3), eq(false));
    }

    @Test
    void vectorSearchWithoutQuestionReturnsError() {
        assertThat(execute("vectorSearch", Map.of())).startsWith("ERROR:");
    }

    // ==================== grep ====================

    @Test
    void grepFormatsHits() {
        SearchHit hit = new SearchHit("chunk-1", 0.9, "Foo.java", 10, 20, "public void foo()", "java", Map.of());
        when(mcpTool.grep(eq("foo"), eq("p1"), any(), any())).thenReturn(List.of(hit));

        String result = execute("grep", Map.of("query", "foo", "project", "p1"));

        assertThat(result).contains("Found 1 result(s)");
        assertThat(result).contains("File: Foo.java");
        assertThat(result).contains("Lines: 10-20");
        assertThat(result).contains("public void foo()");
        assertThat(result).contains("Chunk: chunk-1");
    }

    @Test
    void grepWithoutHitsMentionsProject() {
        when(mcpTool.grep(any(), eq("p1"), any(), any())).thenReturn(List.of());

        String result = execute("grep", Map.of("query", "nothing", "project", "p1"));

        assertThat(result).contains("No results found");
        assertThat(result).contains("p1");
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
        when(mcpTool.listProjects()).thenReturn(List.of("a", "b"));

        assertThat(execute("listProjects", Map.of())).isEqualTo("a, b");
    }

    @Test
    void listProjectsWhenEmpty() {
        when(mcpTool.listProjects()).thenReturn(List.of());

        assertThat(execute("listProjects", Map.of())).isEqualTo("No searchable projects available.");
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

