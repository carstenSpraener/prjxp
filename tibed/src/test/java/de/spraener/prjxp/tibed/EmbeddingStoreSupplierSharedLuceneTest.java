package de.spraener.prjxp.tibed;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import de.spraener.prjxp.tibed.config.EmbeddingStoreSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Lucene-only supplier (LuceneOnlyStorage Phase 02): with a shared {@link LuceneEmbeddingStore}
 * bean present (hub / auto-config), the supplier must reuse it — never open a second IndexWriter
 * on the same index dir. Without one, it creates its own store from the lucene config —
 * no project definition or store reference lookup anymore.
 */
class EmbeddingStoreSupplierSharedLuceneTest {

    @TempDir
    Path tempDir;

    private LuceneEmbeddingStore sharedStore;

    @AfterEach
    void tearDown() {
        if (sharedStore != null) {
            sharedStore.close();
        }
    }

    private PrjXPConfig cfg() {
        return mock(PrjXPConfig.class);
    }

    @Test
    void withSharedBeanReturnsTheSharedStore() {
        sharedStore = new LuceneEmbeddingStore(tempDir.resolve("shared-index"), 8);
        org.springframework.beans.factory.ObjectProvider<LuceneEmbeddingStore> shared = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(shared.getIfAvailable()).thenReturn(sharedStore);

        EmbeddingStoreSupplier supplier = new EmbeddingStoreSupplier(cfg(), shared);

        EmbeddingStore<TextSegment> store = supplier.getStore("any-project");

        assertThat(store).isSameAs(sharedStore);   // hub / auto-config bean — never a second writer
        supplier.destroy();                        // must not close the shared bean (Spring does that)
    }

    @Test
    void withoutSharedBeanCreatesOwnStoreFromLuceneConfig() {
        org.springframework.beans.factory.ObjectProvider<LuceneEmbeddingStore> shared = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(shared.getIfAvailable()).thenReturn(null);

        PrjXPConfig cfg = cfg();
        PrjXPConfig.LuceneEmbeddingStoreConfig lc = mock(PrjXPConfig.LuceneEmbeddingStoreConfig.class);
        when(lc.getIndexPath()).thenReturn(tempDir.resolve("own-index").toString());
        when(lc.getVectorDimension()).thenReturn(8);
        when(cfg.getEmbeddingStoreLucene()).thenReturn(lc);

        EmbeddingStoreSupplier supplier = new EmbeddingStoreSupplier(cfg, shared);

        EmbeddingStore<TextSegment> store = supplier.getStore("any-project");

        assertThat(store).isInstanceOf(LuceneEmbeddingStore.class);
        supplier.destroy();                        // closes the self-created store
    }

    @Test
    void getStoreNoLongerRequiresProjectDefinition() {
        org.springframework.beans.factory.ObjectProvider<LuceneEmbeddingStore> shared = mock(org.springframework.beans.factory.ObjectProvider.class);
        when(shared.getIfAvailable()).thenReturn(null);

        PrjXPConfig cfg = cfg();
        PrjXPConfig.LuceneEmbeddingStoreConfig lc = new PrjXPConfig.LuceneEmbeddingStoreConfig();
        lc.setIndexPath(tempDir.resolve("own-index-2").toString());
        lc.setVectorDimension(8);
        when(cfg.getEmbeddingStoreLucene()).thenReturn(lc);

        EmbeddingStoreSupplier supplier = new EmbeddingStoreSupplier(cfg, shared);

        // Unknown project name must NOT throw — the single shared index needs no per-project lookup.
        assertThat(supplier.getStore("nope")).isInstanceOf(LuceneEmbeddingStore.class);
        supplier.destroy();
    }
}
