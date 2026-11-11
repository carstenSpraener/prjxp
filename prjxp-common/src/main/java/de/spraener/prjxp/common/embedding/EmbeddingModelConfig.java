package de.spraener.prjxp.common.embedding;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.EnvPlaceholderResolver;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import lombok.extern.java.Log;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.net.http.HttpClient;
import java.security.KeyStore;
import java.time.Duration;

@Configuration
@Log
public class EmbeddingModelConfig {

    @Bean
    public EmbeddingModel embeddingModel(PrjXPConfig cfg) {
        PrjXPConfig.EmbeddingConfig ebCfg = cfg.getEmbedding();
        String apiBaseUrl = EnvPlaceholderResolver.resolve(ebCfg.getApiBaseURL());
        String apiKey = EnvPlaceholderResolver.resolve(ebCfg.getApiKey());
        String modelName = EnvPlaceholderResolver.resolve(ebCfg.getModelName());

        // Max texts per single provider HTTP request. Applied uniformly via CappedEmbeddingModel
        // so every model-creation path behaves the same, independent of the concrete provider.
        int cap = Math.max(1, ebCfg.getMaxSegmentsPerBatch());

        if (ebCfg.getType() == PrjXPConfig.EmbeddingModelType.OPEN_AI) {
            log.info("Using Open-AI compatible embedding provider " + apiBaseUrl + " with model " + modelName
                    + ", cap " + cap + " segment(s) per request");
            OpenAiEmbeddingModel model = OpenAiEmbeddingModel.builder()
                    .apiKey(apiKey)
                    .baseUrl(apiBaseUrl)
                    .modelName(modelName)
                    .dimensions(ebCfg.getDimensions())
                    .timeout(Duration.ofSeconds(ebCfg.getTimeout()))
                    .customHeaders(EnvPlaceholderResolver.resolveMap(ebCfg.getCustomHeaders()))
                    .customQueryParams(EnvPlaceholderResolver.resolveMap(ebCfg.getCustomQueryParams()))
                    .httpClientBuilder(JdkHttpClient.builder()
                            .httpClientBuilder(HttpClient.newBuilder().sslContext(sslContext())))
                    // langchain4j defaults maxSegmentsPerBatch to 2048 -> a large embedAll() becomes
                    // one giant request that a local model times out on. Cap it so every request
                    // stays small enough to finish within the timeout (split into many requests).
                    .maxSegmentsPerBatch(cap)
                    .build();
            return new CappedEmbeddingModel(model, cap);
        }

        log.info("Using Ollama embedding model at " + apiBaseUrl + " with model " + modelName
                + ", cap " + cap + " segment(s) per request");
        OllamaEmbeddingModel model = OllamaEmbeddingModel.builder()
                .baseUrl(apiBaseUrl)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(ebCfg.getTimeout()))
                .build();
        return new CappedEmbeddingModel(model, cap);
    }

    private SSLContext sslContext() {
        try {
            if (!System.getProperty("os.name").toLowerCase().contains("win")) {
                return SSLContext.getDefault();
            }

            KeyStore windowsRootStore = KeyStore.getInstance("Windows-ROOT");
            windowsRootStore.load(null, null);
            TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(
                    TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(windowsRootStore);

            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagers.getTrustManagers(), null);
            return sslContext;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to initialize the TLS truststore for Azure OpenAI", e);
        }
    }
}
