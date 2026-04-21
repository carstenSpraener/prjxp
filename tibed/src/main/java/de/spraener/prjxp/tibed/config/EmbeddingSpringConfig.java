package de.spraener.prjxp.tibed.config;

import de.spraener.prjxp.common.config.PrjXPConfig;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaApiVersion;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
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

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(PrjXPConfig cfg) {
        log.info("Using ChromaStore at '%s' as tenant '%s', database '%s' and collection '%s'"
                .formatted(
                        cfg.getChromaUrl(),
                        cfg.getChromaTenant(),
                        cfg.getChromaDatabase(),
                        cfg.getChromaCollectionname()
                )
        );
        return ChromaEmbeddingStore.builder()
                .baseUrl(cfg.getChromaUrl())
                .apiVersion(ChromaApiVersion.V2)
                .tenantName(cfg.getChromaTenant())
                .databaseName(cfg.getChromaDatabase())
                .timeout(Duration.ofSeconds(cfg.getEmbeddingTimeoutSecs()))
                .collectionName(cfg.getChromaCollectionname())
                .build();
    }

}
