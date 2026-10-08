package de.spraener.prjxp.common.mcp;

import de.spraener.prjxp.common.chat.KIChat;
import de.spraener.prjxp.common.config.McpServerReference;
import de.spraener.prjxp.common.config.PrjXPConfig;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;

import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

class McpClientManagerTest {

    @Test
    void init_nullServers_noClients() {
        PrjXPConfig cfg = mock(PrjXPConfig.class);
        when(cfg.getMcpServers()).thenReturn(null);

        McpClientManager manager = new McpClientManager(cfg);
        manager.init();

        assertThat(manager.getActiveClients()).isEmpty();
    }

    @Test
    void init_emptyList_noClients() {
        PrjXPConfig cfg = mock(PrjXPConfig.class);
        when(cfg.getMcpServers()).thenReturn(Collections.emptyList());

        McpClientManager manager = new McpClientManager(cfg);
        manager.init();

        assertThat(manager.getActiveClients()).isEmpty();
    }

    @Test
    void init_nonStdioType_skipped() {
        McpServerReference ref = new McpServerReference();
        ref.setName("sse-server");
        ref.setType("sse");
        ref.setUrl("http://localhost:8080");

        PrjXPConfig cfg = mock(PrjXPConfig.class);
        when(cfg.getMcpServers()).thenReturn(List.of(ref));

        McpClientManager manager = new McpClientManager(cfg);
        manager.init();

        assertThat(manager.getActiveClients()).isEmpty();
    }

    @Test
    void init_stdioBuildsClient_viaMockedConstruction() {
        StdioMcpTransport transportMock = mock(StdioMcpTransport.class);
        DefaultMcpClient clientMock = mock(DefaultMcpClient.class);

        McpServerReference ref = new McpServerReference();
        ref.setName("fs");
        ref.setType("stdio");
        ref.setCommand("npx");
        ref.setArgs(List.of("-y", "x"));

        PrjXPConfig cfg = mock(PrjXPConfig.class);
        when(cfg.getMcpServers()).thenReturn(List.of(ref));

        McpClientManager manager = new McpClientManager(cfg);

        try (MockedConstruction<StdioMcpTransport.Builder> t = Mockito.mockConstruction(
                StdioMcpTransport.Builder.class,
                (m, c) -> {
                    when(m.command(anyList())).thenReturn(m);
                    when(m.logEvents(anyBoolean())).thenReturn(m);
                    when(m.build()).thenReturn(transportMock);
                })) {
            try (MockedConstruction<DefaultMcpClient.Builder> d = Mockito.mockConstruction(
                    DefaultMcpClient.Builder.class,
                    (m, c) -> {
                        when(m.key(anyString())).thenReturn(m);
                        when(m.transport(any(McpTransport.class))).thenReturn(m);
                        when(m.toolExecutionTimeout(any(Duration.class))).thenReturn(m);
                        when(m.build()).thenReturn(clientMock);
                    })) {
                manager.init();
            }
        }

        assertThat(manager.getActiveClients()).containsExactly(clientMock);
    }

    @Test
    void init_httpServer_createsClient() {
        StreamableHttpMcpTransport transportMock = mock(StreamableHttpMcpTransport.class);
        DefaultMcpClient clientMock = mock(DefaultMcpClient.class);

        McpServerReference ref = new McpServerReference();
        ref.setName("http-server");
        ref.setType("http");
        ref.setUrl("http://localhost:7007/mcp");

        PrjXPConfig cfg = mock(PrjXPConfig.class);
        when(cfg.getMcpServers()).thenReturn(List.of(ref));

        McpClientManager manager = new McpClientManager(cfg);

        try (MockedConstruction<StreamableHttpMcpTransport.Builder> t = Mockito.mockConstruction(
                StreamableHttpMcpTransport.Builder.class,
                (m, c) -> {
                    when(m.url(anyString())).thenReturn(m);
                    when(m.logRequests(anyBoolean())).thenReturn(m);
                    when(m.logResponses(anyBoolean())).thenReturn(m);
                    when(m.build()).thenReturn(transportMock);
                })) {
            try (MockedConstruction<DefaultMcpClient.Builder> d = Mockito.mockConstruction(
                    DefaultMcpClient.Builder.class,
                    (m, c) -> {
                        when(m.key(anyString())).thenReturn(m);
                        when(m.transport(any(McpTransport.class))).thenReturn(m);
                        when(m.toolExecutionTimeout(any(Duration.class))).thenReturn(m);
                        when(m.build()).thenReturn(clientMock);
                    })) {
                manager.init();
            }
        }

        assertThat(manager.getActiveClients()).containsExactly(clientMock);
    }

    @Test
    void init_httpServerMissingUrl_skipsWithWarning() {
        McpServerReference ref = new McpServerReference();
        ref.setName("http-no-url");
        ref.setType("http");
        // no URL set

        PrjXPConfig cfg = mock(PrjXPConfig.class);
        when(cfg.getMcpServers()).thenReturn(List.of(ref));

        McpClientManager manager = new McpClientManager(cfg);
        manager.init();

        assertThat(manager.getActiveClients()).isEmpty();
    }

    @Test
    void init_httpServerBlankUrl_skipsWithWarning() {
        McpServerReference ref = new McpServerReference();
        ref.setName("http-blank-url");
        ref.setType("http");
        ref.setUrl("   ");

        PrjXPConfig cfg = mock(PrjXPConfig.class);
        when(cfg.getMcpServers()).thenReturn(List.of(ref));

        McpClientManager manager = new McpClientManager(cfg);
        manager.init();

        assertThat(manager.getActiveClients()).isEmpty();
    }

    @Test
    void decorate_emptyOptional_returnsSame() {
        PrjXPConfig cfg = mock(PrjXPConfig.class);
        McpClientManager manager = new McpClientManager(cfg);

        Optional<KIChat> empty = Optional.empty();
        Optional<KIChat> result = manager.decorate(empty);

        assertThat(result).isEmpty();
        assertThat(result).isSameAs(empty);
    }

    @Test
    void decorate_chatWithoutClients_returnsOriginal() {
        PrjXPConfig cfg = mock(PrjXPConfig.class);
        McpClientManager manager = new McpClientManager(cfg);

        KIChat chatMock = mock(KIChat.class);
        Optional<KIChat> input = Optional.of(chatMock);

        Optional<KIChat> result = manager.decorate(input);

        assertThat(result).contains(chatMock);
    }

    @Test
    void decorate_chatWithClients_wrapsInDecorator() {
        StdioMcpTransport transportMock = mock(StdioMcpTransport.class);
        DefaultMcpClient clientMock = mock(DefaultMcpClient.class);

        McpServerReference ref = new McpServerReference();
        ref.setName("fs");
        ref.setType("stdio");
        ref.setCommand("npx");
        ref.setArgs(List.of("-y", "x"));

        PrjXPConfig cfg = mock(PrjXPConfig.class);
        when(cfg.getMcpServers()).thenReturn(List.of(ref));

        McpClientManager manager = new McpClientManager(cfg);

        try (MockedConstruction<StdioMcpTransport.Builder> t = Mockito.mockConstruction(
                StdioMcpTransport.Builder.class,
                (m, c) -> {
                    when(m.command(anyList())).thenReturn(m);
                    when(m.logEvents(anyBoolean())).thenReturn(m);
                    when(m.build()).thenReturn(transportMock);
                })) {
            try (MockedConstruction<DefaultMcpClient.Builder> d = Mockito.mockConstruction(
                    DefaultMcpClient.Builder.class,
                    (m, c) -> {
                        when(m.key(anyString())).thenReturn(m);
                        when(m.transport(any(McpTransport.class))).thenReturn(m);
                        when(m.toolExecutionTimeout(any(Duration.class))).thenReturn(m);
                        when(m.build()).thenReturn(clientMock);
                    })) {
                manager.init();
            }
        }

        KIChat chatMock = mock(KIChat.class);
        Optional<KIChat> result = manager.decorate(Optional.of(chatMock));

        assertThat(result).isNotEmpty();
        assertThat(result.get()).isInstanceOf(McPEnablingKIChatDecorator.class);
    }

    @Test
    void shutdown_closesAllClients_andSwallowsExceptions() {
        PrjXPConfig cfg = mock(PrjXPConfig.class);
        McpClientManager manager = new McpClientManager(cfg);

        McpClient clientA = mock(McpClient.class);
        McpClient clientB = mock(McpClient.class);
        try { doThrow(new RuntimeException("boom")).when(clientB).close(); } catch (Exception ignored) {}

        ReflectionTestUtils.setField(manager, "activeClients", List.of(clientA, clientB));

        // Should not throw
        manager.shutdown();

        try { verify(clientA).close(); } catch (Exception ignored) {}
        try { verify(clientB).close(); } catch (Exception ignored) {}
    }
}
