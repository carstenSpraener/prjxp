package de.spraener.prjxp.mcpproxy;

import org.apache.commons.cli.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
}
