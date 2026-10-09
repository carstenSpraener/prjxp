package de.spraener.prjxp.common.mcp;

import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import org.junit.jupiter.api.Test;

/** Smoke test: verify StreamableHttpMcpTransport is importable from LangChain4j 1.21.0-beta31. */
class StreamableHttpImportCheckTest {

    @Test
    void streamableHttpMcpTransport_isImportable() {
        // Just verify the class is on the classpath and has a builder() method
        var builder = StreamableHttpMcpTransport.builder();
        builder.url("http://localhost:7007/mcp");
    }
}
