package de.spraener.prjxp.common.embedding;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.EnvPlaceholderResolver;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import lombok.extern.java.Log;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

        if (ebCfg.getType() == PrjXPConfig.EmbeddingModelType.OPEN_AI) {
            log.info("Using Open-AI compatible embedding provider " + apiBaseUrl + " with model " + modelName);
            return OpenAiEmbeddingModel.builder()
                    .apiKey(apiKey)
                    .baseUrl(apiBaseUrl)
                    .modelName(modelName)
                    .timeout(Duration.ofSeconds(ebCfg.getTimeout()))
                    // langchain4j defaults maxSegmentsPerBatch to 2048 -> a large embedAll() becomes
                    // one giant request that a local model times out on. Cap it so every request
                    // stays small enough to finish within the timeout (split into many requests).
                    .maxSegmentsPerBatch(Math.max(1, ebCfg.getMaxSegmentsPerBatch()))
                    .build();
        }

        log.info("Using Ollama embedding model at " + apiBaseUrl + " with model " + modelName);
        return OllamaEmbeddingModel.builder()
                .baseUrl(apiBaseUrl)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(ebCfg.getTimeout()))
                .build();
    }
}
