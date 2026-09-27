package de.spraener.prjxp.lucene.spring;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.PrjXPEmbeddingStoreReference;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LuceneOnlyStorage Phase 01: the auto-configuration must activate WITHOUT any
 * store-type property at all (Lucene is the only store — no switch).
 */
class LuceneStoreAutoConfigurationTest {

    @TempDir
    Path tempDir;

    private PrjXPConfig configWithOneStore() {
        PrjXPConfig cfg = new PrjXPConfig();
        cfg.getEmbeddingStoreLucene().setIndexPath(tempDir.resolve("index").toString());
        cfg.getEmbeddingStoreLucene().setVectorDimension(8);
        PrjXPEmbeddingStoreReference ref = new PrjXPEmbeddingStoreReference();
        ref.setProjectName("alpha");
        cfg.getEmbeddingStores().add(ref);
        return cfg;
    }

    @Test
    void activatesWithoutStoreTypeProperty() {
        new ApplicationContextRunner()
                .withUserConfiguration(LuceneStoreAutoConfiguration.class)
                .withBean(PrjXPConfig.class, this::configWithOneStore)
                .withBean(EmbeddingModel.class, () -> Mockito.mock(EmbeddingModel.class))
                .run(ctx -> {
                    assertThat(ctx.getBeanNamesForType(LuceneEmbeddingStore.class)).contains("luceneEmbeddingStore");
                    @SuppressWarnings("unchecked")
                    List<PxChunkDao> daos = ctx.getBean("lucenePxChunkDaos", List.class);
                    assertThat(daos).hasSize(1);
                });
    }

    @Test
    void daoBeansOnlyWhenEmbeddingModelPresent() {
        new ApplicationContextRunner()
                .withUserConfiguration(LuceneStoreAutoConfiguration.class)
                .withBean(PrjXPConfig.class, this::configWithOneStore)
                .run(ctx -> {
                    assertThat(ctx.getBeanNamesForType(LuceneEmbeddingStore.class)).contains("luceneEmbeddingStore");
                    assertThat(ctx.containsBean("lucenePxChunkDaos")).isFalse();
                });
    }
}
