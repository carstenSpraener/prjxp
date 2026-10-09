package de.spraener.prjxp.common.toolregistry;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolRegistryTest {

    private ToolRegistry registry;
    private GroovyToolExecutor groovyToolExecutor;

    @BeforeEach
    void setUp() {
        groovyToolExecutor = new GroovyToolExecutor();
        registry = new ToolRegistry(groovyToolExecutor);
    }

    @Test
    void register_addsToolToRegistry() {
        ToolDefinition tool = new ToolDefinition("test-tool", "A test tool",
                Map.of("input", "The input parameter"),
                params -> "result");

        registry.register(tool);

        assertThat(registry.getTools()).hasSize(1);
        assertThat(registry.getTools().get(0).getName()).isEqualTo("test-tool");
    }

    @Test
    void register_multipleTools() {
        registry.register(new ToolDefinition("tool-1", "First tool", Map.of(), params -> "one"));
        registry.register(new ToolDefinition("tool-2", "Second tool", Map.of(), params -> "two"));

        assertThat(registry.getTools()).hasSize(2);
    }

    @Test
    void getToolDescriptions_returnsFormattedDescriptions() {
        registry.register(new ToolDefinition("my-tool", "Does something useful",
                Map.of("param1", "First param", "param2", "Second param"),
                params -> "ok"));

        List<String> descriptions = registry.getToolDescriptions();

        assertThat(descriptions).hasSize(1);
        String desc = descriptions.get(0);
        assertThat(desc).contains("my-tool(");
        assertThat(desc).contains("param1: First param");
        assertThat(desc).contains("param2: Second param");
        assertThat(desc).contains("- Does something useful");
    }

    @Test
    void execute_registeredToolReturnsResult() {
        registry.register(new ToolDefinition("calc", "Calculator tool",
                Map.of("x", "Number x"),
                params -> String.valueOf((Integer) params.get("x") * 2)));

        String result = registry.execute("calc", Map.of("x", 5));

        assertThat(result).isEqualTo("10");
    }

    @Test
    void execute_unknownToolThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> registry.execute("non-existent", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown tool: non-existent");
    }

    @Test
    void execute_toolThatThrowsExceptionReturnsErrorMessage() {
        registry.register(new ToolDefinition("failing-tool", "A tool that fails",
                Map.of(),
                params -> { throw new RuntimeException("boom"); }));

        String result = registry.execute("failing-tool", Map.of());

        assertThat(result).startsWith("Error executing tool 'failing-tool':");
        assertThat(result).contains("boom");
    }

    @Test
    void toDescription_emptyParameters() {
        ToolDefinition tool = new ToolDefinition("simple", "No params tool",
                Map.of(), params -> "done");

        String desc = tool.toDescription();

        assertThat(desc).isEqualTo("simple(\n) - No params tool");
    }

    @Test
    void toDescription_withMultipleParameters() {
        ToolDefinition tool = new ToolDefinition("complex", "Multi param tool",
                Map.of("a", "Param A", "b", "Param B"),
                params -> "done");

        String desc = tool.toDescription();

        assertThat(desc).contains("complex(\n");
        assertThat(desc).contains("    a: Param A\n");
        assertThat(desc).contains("    b: Param B\n");
        assertThat(desc).contains(") - Multi param tool");
    }

    @Test
    void getTools_returnsCopy() {
        registry.register(new ToolDefinition("t1", "Tool 1", Map.of(), params -> "r"));

        List<ToolDefinition> tools = registry.getTools();
        tools.clear(); // Modify the returned list

        assertThat(registry.getTools()).hasSize(1); // Original should be unaffected
    }

    @Test
    void execute_withNullParams() {
        registry.register(new ToolDefinition("null-safe", "Handles null params",
                Map.of(),
                params -> params == null ? "null" : "not-null"));

        String result = registry.execute("null-safe", null);
        assertThat(result).isEqualTo("null");
    }
}
