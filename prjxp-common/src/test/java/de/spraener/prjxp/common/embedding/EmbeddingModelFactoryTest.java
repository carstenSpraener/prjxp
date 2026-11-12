package de.spraener.prjxp.common.embedding;

import com.sun.net.httpserver.HttpServer;
import de.spraener.prjxp.common.config.ConfigSecretResolver;
import de.spraener.prjxp.common.config.PrjXPConfig;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class EmbeddingModelFactoryTest {
    @TempDir Path directory;

    @Test
    void runtimeAndCandidateShareExactUrlSecretsHeadersQueryDimensionsAndBatchCap() throws Exception {
        List<String> paths = new ArrayList<>(), headers = new ArrayList<>(), bodies = new ArrayList<>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().toString());
            headers.add(exchange.getRequestHeaders().getFirst("X-Test"));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            byte[] response = """
                    {"data":[{"embedding":[1.0,0.0],"index":0}],"model":"test","usage":{"prompt_tokens":1,"total_tokens":1}}
                    """.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
            exchange.close();
        });
        server.start();
        try {
            Path env = directory.resolve(".env");
            Files.writeString(env, "FACTORY_URL=http://127.0.0.1:" + server.getAddress().getPort()
                    + "/runtime/\nFACTORY_KEY=local-key\nFACTORY_HEADER=local-header\n");
            var factory = new EmbeddingModelFactory(new ConfigSecretResolver(env));
            var cfg = new PrjXPConfig();
            var candidate = cfg.getEmbedding();
            candidate.setApiBaseURL("${FACTORY_URL}");
            candidate.setApiKey("${FACTORY_KEY}");
            candidate.setModelName("test");
            candidate.setDimensions(2);
            candidate.setTimeout(2);
            candidate.setMaxSegmentsPerBatch(1);
            candidate.setCustomHeaders(Map.of("X-Test", "${FACTORY_HEADER}"));
            candidate.setCustomQueryParams(Map.of("mode", "candidate"));
            var fresh = factory.create(candidate);
            var runtime = new EmbeddingModelConfig(factory).embeddingModel(cfg);
            assertThat(paths).isEmpty();
            fresh.embed("test");
            runtime
                    .embedAll(List.of(TextSegment.from("one"), TextSegment.from("two")));
            assertThat(paths).hasSize(3).allMatch(path -> path.equals("/runtime/embeddings?mode=candidate"));
            assertThat(headers).containsOnly("local-header");
            for (String body : bodies) {
                var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
                assertThat(json.get("dimensions").intValue()).isEqualTo(2);
                assertThat(json.get("input").size()).isEqualTo(1);
            }
            assertThat(candidate.getApiKey()).isEqualTo("${FACTORY_KEY}");
            assertThat(candidate.getApiBaseURL()).isEqualTo("${FACTORY_URL}");
            assertThat(candidate.getCustomHeaders()).containsEntry("X-Test", "${FACTORY_HEADER}");
        } finally { server.stop(0); }
    }

    @Test
    void supportsExplicitTlsAndOllamaWithoutProviderRequestsAndRejectsInvalidCandidates() {
        var factory = new EmbeddingModelFactory();
        var candidate = new PrjXPConfig.EmbeddingConfig();
        candidate.setTrustAllCerts(true);
        assertThat(factory.create(candidate)).isInstanceOf(CappedEmbeddingModel.class);
        candidate.setType(PrjXPConfig.EmbeddingModelType.OLLAMA);
        assertThat(factory.create(candidate)).isInstanceOf(CappedEmbeddingModel.class);
        candidate.setType(null);
        assertThatThrownBy(() -> factory.create(candidate)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.create(null)).isInstanceOf(IllegalArgumentException.class);
        var trust = new EmbeddingModelFactory.TrustAllCertificates();
        assertThat(trust.getAcceptedIssuers()).isEmpty();
        trust.checkClientTrusted(null, null);
        trust.checkServerTrusted(null, null);
    }

    @Test
    void unreadableSecretFileAndInvalidBoundsFailExplicitlyWithoutSecretValues() throws Exception {
        Path env = directory.resolve(".env");
        Files.createDirectory(env);
        var factory = new EmbeddingModelFactory(new ConfigSecretResolver(env));
        var candidate = new PrjXPConfig.EmbeddingConfig();
        candidate.setApiKey("${UNAVAILABLE_FACTORY_SECRET}");
        assertThatThrownBy(() -> factory.create(candidate)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Cannot read embedding configuration secrets");
        candidate.setTimeout(0);
        assertThatThrownBy(() -> factory.create(candidate)).isInstanceOf(IllegalArgumentException.class);
        candidate.setTimeout(20);
        candidate.setMaxSegmentsPerBatch(0);
        assertThatThrownBy(() -> factory.create(candidate)).isInstanceOf(IllegalArgumentException.class);
    }
}
