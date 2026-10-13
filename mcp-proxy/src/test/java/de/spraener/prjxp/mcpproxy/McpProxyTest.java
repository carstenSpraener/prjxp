package de.spraener.prjxp.mcpproxy;

import org.apache.commons.cli.*;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpProxyTest {

    @Test
    void shouldParseServerUrlOption() throws Exception {
        Options options = new Options();
        options.addOption("server-url", true, "Upstream URL");
        options.addOption("timeout-seconds", true, "Timeout in seconds");

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, new String[]{"--server-url", "http://localhost:7007/mcp"});

        assertThat(cmd.hasOption("server-url")).isTrue();
        assertThat(cmd.getOptionValue("server-url")).isEqualTo("http://localhost:7007/mcp");
        assertThat(cmd.getOptionValue("timeout-seconds", "300")).isEqualTo("300");
    }

    @Test
    void shouldParseTimeoutOption() throws Exception {
        Options options = new Options();
        options.addOption("server-url", true, "Upstream URL");
        options.addOption("timeout-seconds", true, "Timeout in seconds");

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, new String[]{
                "--server-url", "http://localhost:7007/mcp",
                "--timeout-seconds", "60"
        });

        assertThat(cmd.getOptionValue("timeout-seconds")).isEqualTo("60");
    }

    @Test
    void shouldFailWithoutServerUrl() throws Exception {
        Options options = new Options();
        options.addOption("server-url", true, "Upstream URL");

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, new String[]{});

        assertThat(cmd.hasOption("server-url")).isFalse();
    }
}
