package de.spraener.prjxp.tibed.config;

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

@Configuration
@Log
public class EmbeddingSpringConfig {
    // --- Embedding Sektion ---
    @Value("${tibed.ollama.url:http://192.168.1.228:11434}")
    private String ollamaUrl;

    @Value("${tibed.embedding.modelName:mxbai-embed-large}")
    private String embeddingModelName;

    @Value("${tibed.embedding.chroma.database:prjxp}")
    private String embedChromaDatabase;

    @Value("${tibed.embedding.chroma.tenant:prjxp}")
    private String embedChromaTenant;

    @Value("${tibed.embedding.collectioName:chunk_norris}")
    private String collectioName;

    // --- Vektorstore Sektion ---
    @Value("${tibed.chroma.url:http://localhost:8000}")
    private String chromaUrl;

    @Bean
    public EmbeddingModel embeddingModel() {
        return OllamaEmbeddingModel.builder()
                .baseUrl(ollamaUrl)
                .modelName(embeddingModelName)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        log.info("Using ChromaStore as tenant '%s', database '%s' and collection '%s'"
                .formatted(
                        embedChromaTenant,
                        embedChromaDatabase,
                        collectioName
                )
        );
        return ChromaEmbeddingStore.builder()
                .baseUrl(chromaUrl)
                .apiVersion(ChromaApiVersion.V2)
                .tenantName(embedChromaTenant)
                .databaseName(embedChromaDatabase)
                .timeout(Duration.ofSeconds(60))
                .collectionName(collectioName)
                .build();
    }

}
