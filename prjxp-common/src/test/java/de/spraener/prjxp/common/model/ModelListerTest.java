package de.spraener.prjxp.common.model;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelListerTest {
    private ModelLister lister = new ModelLister();
    private HttpServer server;
    private int port;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void serve(String path, String json) throws Exception {
        server.createContext(path, ex -> {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
    }

    private String base() {
        return "http://localhost:" + port;
    }

    @Test
    void ollama_listsModelNames() throws Exception {
        serve("/api/tags", "{\"models\":[{\"name\":\"llama3:latest\"},{\"name\":\"gemma:2b\"}]}");
        List<String> names = lister.listModels("ollama", base(), null, 10);
        assertThat(names).containsExactly("llama3:latest", "gemma:2b");
    }

    @Test
    void openAiCompatible_listsModelIds() throws Exception {
        serve("/v1/models", "{\"data\":[{\"id\":\"gpt-4o\"},{\"id\":\"gpt-4o-mini\"}]}");
        List<String> names = lister.listModels("openai", base(), "sk-test", 10);
        assertThat(names).containsExactly("gpt-4o", "gpt-4o-mini");
    }

    @Test
    void openApiTypeAlsoWorks() throws Exception {
        serve("/v1/models", "{\"data\":[{\"id\":\"gpt-4o\"},{\"id\":\"gpt-4o-mini\"}]}");
        List<String> names = lister.listModels("openapi", base(), "sk-test", 10);
        assertThat(names).containsExactly("gpt-4o", "gpt-4o-mini");
    }

    @Test
    void lmStudioTypeAlsoWorks() throws Exception {
        serve("/v1/models", "{\"data\":[{\"id\":\"gpt-4o\"},{\"id\":\"gpt-4o-mini\"}]}");
        List<String> names = lister.listModels("lm-studio", base(), "sk-test", 10);
        assertThat(names).containsExactly("gpt-4o", "gpt-4o-mini");
    }

    @Test
    void baseUrlWithTrailingSlashAndV1() throws Exception {
        serve("/v1/models", "{\"data\":[{\"id\":\"gpt-4o\"}]}");
        List<String> names = lister.listModels("openai", base() + "/v1/", "sk-test", 10);
        assertThat(names).containsExactly("gpt-4o");
    }

    @Test
    void customType_throwsIllegalArgument() {
        assertThatThrownBy(() -> lister.listModels("custom", base(), null, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownType_throwsIllegalArgument() {
        assertThatThrownBy(() -> lister.listModels("foo", base(), null, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void geminiWithoutKey_throwsIllegalArgument() {
        assertThatThrownBy(() -> lister.listModels("gemini", base(), null, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void non2xxResponse_throwsIllegalState() throws Exception {
        server.createContext("/api/tags", ex -> {
            ex.sendResponseHeaders(500, -1);
            ex.close();
        });
        assertThatThrownBy(() -> lister.listModels("ollama", base(), null, 10))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void emptyModelsArray_returnsEmptyList() throws Exception {
        serve("/api/tags", "{\"models\":[]}");
        List<String> names = lister.listModels("ollama", base(), null, 10);
        assertThat(names).isEmpty();
    }
}
