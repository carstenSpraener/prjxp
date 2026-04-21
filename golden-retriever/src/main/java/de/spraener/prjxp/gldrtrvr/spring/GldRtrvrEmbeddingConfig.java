package de.spraener.prjxp.gldrtrvr.spring;

import de.spraener.prjxp.common.chat.KIChat;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.gldrtrvr.KIChatModelWrapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaApiVersion;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import lombok.extern.java.Log;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@Log
public class GldRtrvrEmbeddingConfig {

    @Bean
    public EmbeddingModel embeddingModel(PrjXPConfig cfg) {
        try {
            return OllamaEmbeddingModel.builder()
                    .baseUrl(cfg.getEmbeddingOllamaUrl())
                    .modelName(cfg.getEmbeddingModelName())
                    .build();
        } catch (Exception e) {
            log.severe(String.format(
                    "Connection to EmbeddingModel failed! \n" +
                            "   ollamaUrl: '%s'\n" +
                            "   embeddingModelName: '%s'\n",
                    cfg.getEmbeddingOllamaUrl(),
                    cfg.getEmbeddingModelName()
            ));
            throw new RuntimeException(e);
        }
    }

    @Bean
    public EmbeddingStore<TextSegment> embeddingStore(PrjXPConfig cfg) {
        try {
            return ChromaEmbeddingStore.builder()
                    .baseUrl(cfg.getChromaUrl())
                    .apiVersion(ChromaApiVersion.V2)
                    .tenantName(cfg.getChromaTenant())
                    .databaseName(cfg.getChromaDatabase())
                    .collectionName(cfg.getChromaCollectionname())
                    .build();
        } catch (Exception e) {
            log.severe(String.format(
                    "Connection to ChromaStore failed! \n" +
                            "   chromaURL: '%s'\n" +
                            "   Tenant: '%s'\n" +
                            "   ChromaDatabase: '%s'\n" +
                            "   Collection: '%s'",
                    cfg.getChromaUrl(),
                    cfg.getChromaTenant(),
                    cfg.getChromaDatabase(),
                    cfg.getChromaCollectionname()
            ));
            throw new RuntimeException(e);
        }
    }

    @Bean
    public KIChat chatModel(PrjXPConfig cfg) {
        try {
            if (cfg.getChatApiKind().equals("gemini")) {
                return new KIChatModelWrapper(GoogleAiGeminiChatModel.builder()
                        .apiKey(cfg.getGeminiApiKey())
                        .modelName(cfg.getChatModelName())
                        .temperature(0.1)
                        .build()
                );
            } else if (cfg.getChatApiKind().equals("ollama")) {
                return new KIChatModelWrapper(OllamaChatModel.builder()
                        .baseUrl(cfg.getChatApiUrl())
                        .modelName(cfg.getChatModelName())
                        .timeout(Duration.ofMinutes(20))
                        .temperature(0.2)
                        .build()
                );
            } else if (cfg.getChatApiKind().equals("openai")) {
                return new KIChatModelWrapper(
                        OpenAiChatModel.builder()
                                .apiKey(cfg.getChatApiKey())
                                .modelName(cfg.getChatModelName())
                                .temperature(0.2)
                                .baseUrl(cfg.getChatApiUrl())
                                .build()
                );
            } else if (cfg.getChatApiKind().equals("none")) {
                return new KIChat() {
                    @Override
                    public String chat(String question) {
                        return "";
                    }
                };
            } else {
                throw new IllegalArgumentException("Unsupported chat API kind: " + cfg.getChatApiKind());
            }
        } catch (Exception e) {
            log.severe(String.format(
                    "Connection to ChatModel failed! \n" +
                            "   api-kind: '%s'\n" +
                            "   api-url: '%s'\n" +
                            "   modelName: '%s'\n" +
                            "   gemine.apikey (only required for gemini models): '%s'",
                    cfg.getChatApiKind(),
                    cfg.getChatApiUrl(),
                    cfg.getChatModelName(),
                    cfg.getGeminiApiKey()
            ));

            throw new RuntimeException(e);
        }
    }
}
