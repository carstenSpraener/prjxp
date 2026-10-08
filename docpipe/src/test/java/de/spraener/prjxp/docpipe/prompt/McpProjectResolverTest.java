package de.spraener.prjxp.docpipe.prompt;

import com.github.jknack.handlebars.Handlebars;
import com.github.jknack.handlebars.Options;
import com.github.jknack.handlebars.Template;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

class McpProjectResolverTest {

    private final McpProjectResolver resolver = new McpProjectResolver();
    private final File dummyBaseDir = new File("/tmp/dummy");
    private final Object dummyContext = new Object();

    // --- ID & Aliases ---

    @Test
    void getID_returnsMcpProject() {
        assertThat(resolver.getID()).isEqualTo("mcp-project");
    }

    @Test
    void getAliases_includesCamelCase() {
        assertThat(resolver.getAliases()).contains("mcpProject");
    }

    @Test
    void getAliases_doesNotContainId() {
        assertThat(resolver.getAliases()).doesNotContain("mcp-project");
    }

    // --- Unit tests via mocked Options ---

    @Test
    void resolve_withNamedProjectParam_returnsProjectContext() throws Exception {
        Options options = Mockito.mock(Options.class);
        when(options.hash("project", "")).thenReturn("prjxp");

        String result = resolver.resolve(dummyBaseDir, dummyContext, options);

        assertThat(result).contains("'prjxp'");
        assertThat(result).contains("vectorSearch");
        assertThat(result).contains("grep");
        assertThat(result).contains("readFile");
        assertThat(result).contains("readBySignature");
    }

    @Test
    void resolve_withoutProjectParam_returnsGenericMessage() throws Exception {
        Options options = Mockito.mock(Options.class);
        when(options.hash("project", "")).thenReturn("");

        String result = resolver.resolve(dummyBaseDir, dummyContext, options);

        assertThat(result).contains("MCP tools");
        assertThat(result).doesNotContain("'null'");
        assertThat(result).contains("vectorSearch");
    }

    @Test
    void resolve_withBlankProjectParam_returnsGenericMessage() throws Exception {
        Options options = Mockito.mock(Options.class);
        when(options.hash("project", "")).thenReturn("  ");

        String result = resolver.resolve(dummyBaseDir, dummyContext, options);

        assertThat(result).contains("embedded project");
        assertThat(result).doesNotContain("'  '");
    }

    @Test
    void resolve_outputContainsAllToolNames() throws Exception {
        Options options = Mockito.mock(Options.class);
        when(options.hash("project", "")).thenReturn("test-project");

        String result = resolver.resolve(dummyBaseDir, dummyContext, options);

        assertThat(result).contains("vectorSearch");
        assertThat(result).contains("grep");
        assertThat(result).contains("readFile");
        assertThat(result).contains("readBySignature");
    }

    @Test
    void resolve_outputContainsGuidanceText() throws Exception {
        Options options = Mockito.mock(Options.class);
        when(options.hash("project", "")).thenReturn("test-project");

        String result = resolver.resolve(dummyBaseDir, dummyContext, options);

        assertThat(result).contains("gather information");
        assertThat(result).contains("structure, modules");
        assertThat(result).contains("before answering");
    }

    // --- Integration tests with real Handlebars compilation ---

    @Test
    void resolve_inRealTemplate_withNamedParam_works() throws IOException {
        Handlebars handlebars = new Handlebars();
        handlebars.registerHelper("mcp-project", (context, options) -> {
            try { return resolver.resolve(dummyBaseDir, context, options); }
            catch (Exception e) { throw new RuntimeException(e); }
        });

        Template template = handlebars.compileInline("{{mcp-project project=\"prjxp\"}}");
        String result = template.apply(new Object() {});

        assertThat(result).contains("prjxp");
        assertThat(result).contains("MCP tools");
    }

    @Test
    void resolve_inRealTemplate_withoutParams_returnsGeneric() throws IOException {
        Handlebars handlebars = new Handlebars();
        handlebars.registerHelper("mcp-project", (context, options) -> {
            try { return resolver.resolve(dummyBaseDir, context, options); }
            catch (Exception e) { throw new RuntimeException(e); }
        });

        Template template = handlebars.compileInline("{{mcp-project}}");
        String result = template.apply(new Object() {});

        assertThat(result).contains("embedded project");
        assertThat(result).doesNotContain("'null'");
    }

    @Test
    void resolve_viaAlias_inRealTemplate_works() throws IOException {
        Handlebars handlebars = new Handlebars();
        handlebars.registerHelper("mcpProject", (context, options) -> {
            try { return resolver.resolve(dummyBaseDir, context, options); }
            catch (Exception e) { throw new RuntimeException(e); }
        });

        Template template = handlebars.compileInline("{{mcpProject project=\"prjxp\"}}");
        String result = template.apply(new Object() {});

        assertThat(result).contains("prjxp");
        assertThat(result).contains("vectorSearch");
    }
}
