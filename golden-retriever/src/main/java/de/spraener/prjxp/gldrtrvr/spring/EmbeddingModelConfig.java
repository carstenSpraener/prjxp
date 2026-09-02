package de.spraener.prjxp.gldrtrvr.spring;

import de.spraener.prjxp.common.config.PrjXPConfig;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import lombok.extern.java.Log;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@Log
public class EmbeddingModelConfig {

    @Bean
    public EmbeddingModel embeddingModel(PrjXPConfig cfg) {
        if (cfg.getEmbeddingModelType() == PrjXPConfig.EmbeddingModelType.ONNX_LOCAL) {
            log.info("Using local ONNX embedding model (localhost:11435)");
            return OpenAiEmbeddingModel.builder()
                    .baseUrl("http://localhost:11435")
                    .modelName(cfg.getEmbeddingModelName())
                    .build();
        }

        log.info("Using Ollama embedding model at " + cfg.getEmbeddingOllamaUrl());
        return OllamaEmbeddingModel.builder()
                .baseUrl(cfg.getEmbeddingOllamaUrl())
                .modelName(cfg.getEmbeddingModelName())
                .build();
    }

}
