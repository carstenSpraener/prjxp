package de.spraener.prjxp.common.mcp;

import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that StreamableHttpMcpTransport is importable and accessible
 * after upgrading langchain4j-mcp to 1.21.0-beta31.
 */
class StreamableHttpMcpTransportImportTest {

    @Test
    void streamableHttpMcpTransport_isImportable() {
        // Verify the class is loadable and has a builder method
        assertThat(StreamableHttpMcpTransport.class).isNotNull();
        assertThat(StreamableHttpMcpTransport.builder()).isNotNull();
    }
}
