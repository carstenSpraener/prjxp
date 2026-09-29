package de.spraener.prjxp.common.embedding;

import de.spraener.prjxp.common.config.PrjXPConfig;
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

        if (ebCfg.getType() == PrjXPConfig.EmbeddingModelType.OPEN_AI) {
            log.info("Using Open-AI compatible embedding provider " + ebCfg.getApiBaseURL() + " with model " + ebCfg.getModelName());
            return OpenAiEmbeddingModel.builder()
                    .apiKey(ebCfg.getApiKey())
                    .baseUrl(ebCfg.getApiBaseURL())
                    .modelName(ebCfg.getModelName())
                    .timeout(Duration.ofSeconds(ebCfg.getTimeout()))
                    .build();
        }

        log.info("Using Ollama embedding model at " + ebCfg.getApiBaseURL() + " with model " + ebCfg.getModelName());
        return OllamaEmbeddingModel.builder()
                .baseUrl(ebCfg.getApiBaseURL())
                .modelName(ebCfg.getModelName())
                .timeout(Duration.ofSeconds(ebCfg.getTimeout()))
                .build();
    }
}
