package de.spraener.prjxp.gldrtrvr.spring;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.gldrtrvr.chunks.ChromaDBPxChunkDao;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaApiVersion;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import lombok.extern.java.Log;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

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
                        .baseUrl(r.getProviderUrl())
                        .apiVersion(ChromaApiVersion.V2)
                        .tenantName(r.getTenant())
                        .databaseName(r.getDbName())
                        .collectionName(r.getCollectionName())
                        .build();
                embeddingStores.add( new ChromaDBPxChunkDao(store, embeddingModel, r));
            } catch (Exception e) {
                log.severe(String.format(
                        "Connection to ChromaStore failed! \n" +
                                "   chromaURL: '%s'\n" +
                                "   Tenant: '%s'\n" +
                                "   ChromaDatabase: '%s'\n" +
                                "   Collection: '%s'",
                        r.getProviderUrl(),
                        r.getTenant(),
                        r.getDbName(),
                        r.getCollectionName()
                ));
                throw new RuntimeException(e);
            }
        }
        return embeddingStores;
    }

}
