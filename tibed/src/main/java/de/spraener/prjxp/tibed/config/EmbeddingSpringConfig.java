package de.spraener.prjxp.tibed.config;

import de.spraener.prjxp.common.config.PrjXPConfig;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import lombok.extern.java.Log;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@Log
public class EmbeddingSpringConfig {


    @Bean
    public EmbeddingModel embeddingModel(PrjXPConfig cfg) {
        return OllamaEmbeddingModel.builder()
                .baseUrl(cfg.getEmbeddingOllamaUrl())
                .modelName(cfg.getEmbeddingModelName())
                .timeout(Duration.ofSeconds(cfg.getEmbeddingTimeoutSecs()))
                .build();
    }

}
