package de.spraener.gldrtrvr.spring;

import de.spraener.gldrtrvr.GldRtrvrCfg;
import de.spraener.gldrtrvr.KIChatModelWrapper;
import de.spraener.prjxp.common.chat.KIChat;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaApiVersion;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class GldRtrvrEmbeddingConfig {
    @Value("${gldrtrvr.embedding.modelName:mxbai-embed-large}")
    private String embeddingModelName;

    @Value("${gldrtrvr.embedding.chroma.database:prjxp}")
    private String embedChromaDatabase;

    @Value("${gldrtrvr.embedding.chroma.tenant:prjxp}")
    private String embedChromaTenant;

    @Value("${gldrtrvr.embedding.chroma.collectioName:chunk_norris}")
    private String collectioName;

    // --- Vektorstore Sektion ---
    @Value("${gldrtrvr.embedding.chroma.url:http://localhost:8000}")
    private String chromaUrl;

    @Bean
    public EmbeddingModel embeddingModel(GldRtrvrCfg cfg) {
        return OllamaEmbeddingModel.builder()
                .baseUrl(cfg.getEmbeddingOllamaUrl())
                .modelName(cfg.getEmbeddingModelName())
                .build();
    }

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(GldRtrvrCfg cfg) {
        return ChromaEmbeddingStore.builder()
                .baseUrl(cfg.getChromaBaseUrl())
                .apiVersion(ChromaApiVersion.V2)
                .tenantName(embedChromaTenant)
                .databaseName(embedChromaDatabase)
                .collectionName(cfg.getChromaCollectionName())
                .build();
    }

    @Bean
    public KIChat chatModel(GldRtrvrCfg cfg) {
        if (cfg.getChatModelName().contains("gemini")) {
            return new KIChatModelWrapper(GoogleAiGeminiChatModel.builder()
                    .apiKey(cfg.getGeminiApiKey())
                    .modelName(cfg.getChatModelName())
                    .temperature(0.1)
                    .build()
            );
        } else {
            return new KIChatModelWrapper(OllamaChatModel.builder()
                    .baseUrl(cfg.getChatOllamaUrl())
                    .modelName(cfg.getChatModelName())
                    .timeout(Duration.ofMinutes(10))
                    .temperature(0.0)
                    .build()
            );
        }
    }
}
