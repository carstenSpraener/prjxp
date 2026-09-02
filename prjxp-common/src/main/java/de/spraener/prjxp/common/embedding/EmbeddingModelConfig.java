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
    @org.springframework.context.annotation.DependsOn("embeddingServerManager")
    public EmbeddingModel embeddingModel(PrjXPConfig cfg) {
        if (cfg.getEmbeddingModelType() == PrjXPConfig.EmbeddingModelType.ONNX_LOCAL) {
            log.info("Using local ONNX embedding model (localhost:" + cfg.getEmbeddingServerPort() + ") via OpenAI-compatible endpoint");
            return OpenAiEmbeddingModel.builder()
                    .baseUrl("http://localhost:" + cfg.getEmbeddingServerPort())
                    .modelName(cfg.getEmbeddingModelName())
                    .timeout(Duration.ofSeconds(cfg.getEmbeddingTimeoutSecs()))
                    .build();
        }

        log.info("Using Ollama embedding model at " + cfg.getEmbeddingOllamaUrl());
        return OllamaEmbeddingModel.builder()
                .baseUrl(cfg.getEmbeddingOllamaUrl())
                .modelName(cfg.getEmbeddingModelName())
                .timeout(Duration.ofSeconds(cfg.getEmbeddingTimeoutSecs()))
                .build();
    }
}
