package de.spraener.prjxp.tibed.config;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

/**
 * Lucene-only store supplier (LuceneOnlyStorage Phase 02): returns the shared
 * {@link LuceneEmbeddingStore} bean when present (hub / auto-config — never a second
 * IndexWriter on the same index dir), otherwise creates its own from the lucene config.
 */
@Service
@RequiredArgsConstructor
@Log
public class EmbeddingStoreSupplier implements DisposableBean {
    private final PrjXPConfig cfg;
    private final ObjectProvider<LuceneEmbeddingStore> sharedLucene;
    private EmbeddingStore<TextSegment> createdStore;

    public EmbeddingStore<TextSegment> getStore(String name) {
        LuceneEmbeddingStore shared = sharedLucene.getIfAvailable();
        if (shared != null) { return shared; }   // hub / auto-config bean — never a second writer
        PrjXPConfig.LuceneEmbeddingStoreConfig lc = cfg.getEmbeddingStoreLucene();
        log.info("Initialisiere Lucene Embedding Store für das Projekt: " + name
                + ", index path: " + lc.getIndexPath());
        createdStore = new LuceneEmbeddingStore(Path.of(lc.getIndexPath()), lc.getVectorDimension());
        return createdStore;
    }

    @Override
    public void destroy() {
        if (createdStore instanceof LuceneEmbeddingStore) {
            log.info("Closing Lucene embedding store...");
            ((LuceneEmbeddingStore) createdStore).close();
        }
    }
}
