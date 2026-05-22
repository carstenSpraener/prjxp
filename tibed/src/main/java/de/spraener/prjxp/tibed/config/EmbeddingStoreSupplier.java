package de.spraener.prjxp.tibed.config;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.chroma.ChromaApiVersion;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Log
public class EmbeddingStoreSupplier {
    private final PrjXPConfig cfg;

    public EmbeddingStore<TextSegment> getStore(String name) {
        PrjXPEmbeddingStoreReference ref = cfg.getEmbeddingStores()
                .stream()
                .filter(r -> r.getProjectName().equals(cfg.getProjectName()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No store found for project " + cfg.getProjectName()));
        log.info(
                String.format("Using ChromaStore at '%s' as tenant '%s', database '%s' and collection '%s'",
                        ref.getStoreURL(),
                        ref.getStoreTenant(),
                        ref.getStoreDBName(),
                        ref.getStoreCollectionName()
                )
        );
        return ChromaEmbeddingStore.builder()
                .baseUrl(ref.getStoreURL())
                .apiVersion(ChromaApiVersion.V2)
                .tenantName(ref.getStoreTenant())
                .databaseName(ref.getStoreDBName())
                .collectionName(ref.getStoreCollectionName())
                .build();
    }
}
