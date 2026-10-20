package de.spraener.prjxp.mcpproxy;

import org.apache.commons.cli.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpProxyTest {

    @Test
    void shouldParseServerUrlOption() throws Exception {
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(McpProxy.options(), new String[]{"--server-url", "http://localhost:7007/mcp"});

        assertThat(cmd.hasOption("server-url")).isTrue();
        assertThat(cmd.getOptionValue("server-url")).isEqualTo("http://localhost:7007/mcp");
        assertThat(cmd.getOptionValue("timeout-seconds", "300")).isEqualTo("300");
    }

    @Test
    void shouldParseTimeoutOption() throws Exception {
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(McpProxy.options(), new String[]{
                "--server-url", "http://localhost:7007/mcp",
                "--timeout-seconds", "60"
        });

        assertThat(cmd.getOptionValue("timeout-seconds")).isEqualTo("60");
    }

    @Test
    void shouldFailWithoutServerUrl() throws Exception {
        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(McpProxy.options(), new String[]{});

        assertThat(cmd.hasOption("server-url")).isFalse();
    }

    @Test
    void shouldNormalizeSingleDashLongOptionForCompatibility() {
        assertThat(McpProxy.normalizeArguments(new String[]{"-server-url", "http://localhost:7007/mcp"}))
                .containsExactly("--server-url", "http://localhost:7007/mcp");
    }

    @Test
    void parseArgsReturnsUrlAndTimeout() throws Exception {
        McpProxy.ParsedArgs parsed = McpProxy.parseArgs(new String[]{
                "--server-url", "http://localhost:7007/mcp", "--timeout-seconds", "60"});

        assertThat(parsed.serverUrl()).isEqualTo("http://localhost:7007/mcp");
        assertThat(parsed.timeoutSeconds()).isEqualTo(60);
    }

    @Test
    void parseArgsDefaultsTimeoutTo300() throws Exception {
        McpProxy.ParsedArgs parsed = McpProxy.parseArgs(new String[]{"--server-url", "http://localhost:7007/mcp"});

        assertThat(parsed.timeoutSeconds()).isEqualTo(300);
    }

    @Test
    void parseArgsAcceptsSingleDashCompatibilityForm() throws Exception {
        McpProxy.ParsedArgs parsed = McpProxy.parseArgs(new String[]{"-server-url", "http://localhost:7007/mcp"});

        assertThat(parsed.serverUrl()).isEqualTo("http://localhost:7007/mcp");
    }

    @Test
    void parseArgsThrowsWithoutServerUrl() {
        assertThatThrownBy(() -> McpProxy.parseArgs(new String[]{})
        ).isInstanceOf(ParseException.class)
                .hasMessageContaining("server-url");
    }

    @Test
    void parseArgsThrowsOnMissingOptionValue() {
        assertThatThrownBy(() -> McpProxy.parseArgs(new String[]{"--server-url"}))
                .isInstanceOf(ParseException.class);
    }
}
