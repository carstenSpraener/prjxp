package de.spraener.prjxp.docpipe.prompt;

import com.github.jknack.handlebars.Options;
import de.spraener.prjxp.common.toolregistry.GroovyToolExecutor;
import de.spraener.prjxp.common.toolregistry.ToolDefinition;
import de.spraener.prjxp.common.toolregistry.ToolRegistry;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class McpProjectResolverTest {

    @Test
    void getID_returnsCorrectId() {
        ToolRegistry toolRegistry = new ToolRegistry(new GroovyToolExecutor());
        McpProjectResolver resolver = new McpProjectResolver(toolRegistry);

        assertThat(resolver.getID()).isEqualTo("mcp-project");
    }

    @Test
    void getAliases_returnsCorrectAliases() {
        ToolRegistry toolRegistry = new ToolRegistry(new GroovyToolExecutor());
        McpProjectResolver resolver = new McpProjectResolver(toolRegistry);

        assertThat(resolver.getAliases()).containsExactly("mcpProject");
    }

    @Test
    void resolve_withProjectParam_includesToolDescriptionsAndProjectName() throws Exception {
        ToolRegistry toolRegistry = new ToolRegistry(new GroovyToolExecutor());
        toolRegistry.register(new ToolDefinition("vectorSearch", "Semantic search over code chunks",
                Map.of("query", "Search query text"), params -> "results"));

        McpProjectResolver resolver = new McpProjectResolver(toolRegistry);
        Options options = mock(Options.class);
        when(options.hash("project", "")).thenReturn("my-project");

        String result = resolver.resolve(null, null, options);

        assertThat(result).contains("You have access to the following tools:");
        assertThat(result).contains("vectorSearch");
        assertThat(result).contains("Semantic search over code chunks");
        assertThat(result).contains("The active project is: 'my-project'");
        assertThat(result).contains("Use these tools to gather information about the project's structure, modules, and key classes before answering.");
    }

    @Test
    void resolve_withoutProjectParam_includesToolDescriptionsButNoProjectName() throws Exception {
        ToolRegistry toolRegistry = new ToolRegistry(new GroovyToolExecutor());
        toolRegistry.register(new ToolDefinition("grep", "Exact text search in source files",
                Map.of("pattern", "Text pattern to search for"), params -> "matches"));

        McpProjectResolver resolver = new McpProjectResolver(toolRegistry);
        Options options = mock(Options.class);
        when(options.hash("project", "")).thenReturn("");

        String result = resolver.resolve(null, null, options);

        assertThat(result).contains("You have access to the following tools:");
        assertThat(result).contains("grep");
        assertThat(result).doesNotContain("The active project is:");
        assertThat(result).contains("Use these tools to gather information before answering the user's question.");
    }

    @Test
    void resolve_withEmptyRegistry_returnsEmptyString() throws Exception {
        ToolRegistry toolRegistry = new ToolRegistry(new GroovyToolExecutor());
        // No tools registered

        McpProjectResolver resolver = new McpProjectResolver(toolRegistry);
        Options options = mock(Options.class);
        when(options.hash("project", "")).thenReturn("some-project");

        String result = resolver.resolve(null, null, options);

        assertThat(result).isEmpty();
    }

    @Test
    void resolve_withNullProject_includesToolDescriptionsButNoProjectName() throws Exception {
        ToolRegistry toolRegistry = new ToolRegistry(new GroovyToolExecutor());
        toolRegistry.register(new ToolDefinition("readFile", "Read full file content",
                Map.of("path", "File path"), params -> "content"));

        McpProjectResolver resolver = new McpProjectResolver(toolRegistry);
        Options options = mock(Options.class);
        when(options.hash("project", "")).thenReturn(null);

        String result = resolver.resolve(null, null, options);

        assertThat(result).contains("You have access to the following tools:");
        assertThat(result).contains("readFile");
        assertThat(result).doesNotContain("The active project is:");
    }

    @Test
    void resolve_withMultipleTools_includesAllDescriptions() throws Exception {
        ToolRegistry toolRegistry = new ToolRegistry(new GroovyToolExecutor());
        toolRegistry.register(new ToolDefinition("tool1", "First tool description",
                Map.of("param1", "Parameter 1"), params -> "result1"));
        toolRegistry.register(new ToolDefinition("tool2", "Second tool description",
                Map.of("param2", "Parameter 2"), params -> "result2"));

        McpProjectResolver resolver = new McpProjectResolver(toolRegistry);
        Options options = mock(Options.class);
        when(options.hash("project", "")).thenReturn("test-project");

        String result = resolver.resolve(null, null, options);

        assertThat(result).contains("tool1");
        assertThat(result).contains("tool2");
        assertThat(result).contains("First tool description");
        assertThat(result).contains("Second tool description");
    }

    @Test
    void resolve_withBlankProject_includesToolDescriptionsButNoProjectName() throws Exception {
        ToolRegistry toolRegistry = new ToolRegistry(new GroovyToolExecutor());
        toolRegistry.register(new ToolDefinition("test-tool", "A test tool",
                Map.of(), params -> "ok"));

        McpProjectResolver resolver = new McpProjectResolver(toolRegistry);
        Options options = mock(Options.class);
        when(options.hash("project", "")).thenReturn("   ");

        String result = resolver.resolve(null, null, options);

        assertThat(result).contains("You have access to the following tools:");
        assertThat(result).doesNotContain("The active project is:");
    }
}
