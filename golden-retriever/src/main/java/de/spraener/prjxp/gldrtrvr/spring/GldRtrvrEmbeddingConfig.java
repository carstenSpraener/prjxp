package de.spraener.prjxp.gldrtrvr.spring;

import de.spraener.prjxp.common.chat.EmptyKiChat;
import de.spraener.prjxp.common.chat.KIChat;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.gldrtrvr.KIChatModelWrapper;
import de.spraener.prjxp.gldrtrvr.chunks.ChromaDBPxChunkDao;
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
import org.springframework.context.annotation.Profile;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

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
    @Profile("!test")
    public List<PxChunkDao> embeddingStore(PrjXPConfig cfg, EmbeddingModel embeddingModel) {
        List<PxChunkDao> embeddingStores = new ArrayList<>();
        for( var r : cfg.getEmbeddingStores() ) {
            try {
                EmbeddingStore<TextSegment> store = ChromaEmbeddingStore.builder()
                        .baseUrl(r.getStoreURL())
                        .apiVersion(ChromaApiVersion.V2)
                        .tenantName(r.getStoreTenant())
                        .databaseName(r.getStoreDBName())
                        .collectionName(r.getStoreCollectionName())
                        .build();
                embeddingStores.add( new ChromaDBPxChunkDao(store, embeddingModel, r));
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
        return embeddingStores;
    }

    @Bean
    public List<KIChat> chatModel(PrjXPConfig cfg) {
        List<KIChat> chatModels = new ArrayList<>();
        for( var r :  cfg.getChatModels() ) {
            try {
                if (r.getProviderType().equals("gemini")) {
                    chatModels.add(new KIChatModelWrapper(GoogleAiGeminiChatModel.builder()
                            .apiKey(r.getApiKey())
                            .modelName(r.getModelName())
                            .temperature(0.1)
                            .build(),
                            r
                    ));
                } else if (r.getProviderType().equals("ollama")) {
                    chatModels.add(new KIChatModelWrapper(OllamaChatModel.builder()
                            .baseUrl(r.getApiUrl())
                            .modelName(r.getModelName())
                            .timeout(Duration.ofMinutes(20))
                            .temperature(0.2)
                            .build(), r
                    ));
                } else if (r.getProviderType().equals("openAI")) {
                    chatModels.add(new KIChatModelWrapper(
                            OpenAiChatModel.builder()
                                    .apiKey(r.getApiKey())
                                    .modelName(r.getModelName())
                                    .temperature(0.2)
                                    .baseUrl(r.getApiUrl())
                                    .build(), r)
                    );
                } else if (r.getProviderType().equals("none")) {
                    chatModels.add( new EmptyKiChat(r));
                } else {
                    throw new IllegalArgumentException("Unsupported chat API kind: " + r.getProviderType());
                }
            } catch (Exception e) {
                log.severe(String.format(
                        "Connection to ChatModel failed! \n" +
                                "   provider-type: '%s'\n" +
                                "   api-url: '%s'\n" +
                                "   modelName: '%s'\n" +
                                "",
                        r.getProviderType(),
                        r.getApiUrl(),
                        r.getModelName()
                ));

                throw new RuntimeException(e);
            }
        }
        return chatModels;
    }
}
