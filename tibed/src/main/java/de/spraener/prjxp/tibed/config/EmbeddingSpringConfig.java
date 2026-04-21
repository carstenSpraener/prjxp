package de.spraener.prjxp.tibed.config;

import de.spraener.prjxp.tibed.TiBedConfig;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaApiVersion;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import lombok.extern.java.Log;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.Map;

@Configuration
@Log
public class EmbeddingSpringConfig {


    @Bean
    public EmbeddingModel embeddingModel(TiBedConfig cfg) {
        return OllamaEmbeddingModel.builder()
                .baseUrl(cfg.getOllamaUrl())
                .modelName(cfg.getEmbeddingModelName())
                .timeout(Duration.ofSeconds(cfg.getEmbeddingTimeoutSecs()))
                .build();
    }

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(TiBedConfig cfg) {
        log.info("Using ChromaStore at '%s' as tenant '%s', database '%s' and collection '%s'"
                .formatted(
                        cfg.getChromaUrl(),
                        cfg.getEmbedChromaTenant(),
                        cfg.getEmbedChromaDatabase(),
                        cfg.getCollectionName()
                )
        );
        return ChromaEmbeddingStore.builder()
                .baseUrl(cfg.getChromaUrl())
                .apiVersion(ChromaApiVersion.V2)
                .tenantName(cfg.getEmbedChromaTenant())
                .databaseName(cfg.getEmbedChromaDatabase())
                .timeout(Duration.ofSeconds(cfg.getEmbeddingTimeoutSecs()))
                .collectionName(cfg.getCollectionName())
                .build();
    }

}
