package de.spraener.prjxp.lucene.spring;

import dev.langchain4j.model.embedding.EmbeddingModel;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import de.spraener.prjxp.lucene.LucenePxChunkDao;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Configuration
@RequiredArgsConstructor
public class LuceneStoreAutoConfiguration {
    private final PrjXPConfig config;

    /**
     * Declared as the concrete {@link LuceneEmbeddingStore} on purpose: Spring matches @Bean
     * methods by their declared return type, and the hub beans (plus tibed's
     * {@code EmbeddingStoreSupplier}) inject the concrete class — an interface-typed return
     * would make this bean invisible to them.
     */
    @Bean
    public LuceneEmbeddingStore luceneEmbeddingStore() {
        PrjXPConfig.LuceneEmbeddingStoreConfig lc = config.getEmbeddingStoreLucene();
        return new LuceneEmbeddingStore(
                Path.of(lc.getIndexPath()),
                lc.getVectorDimension(),
                lc.getName()
        );
    }

    @Bean
    @ConditionalOnBean(EmbeddingModel.class)
    public List<PxChunkDao> lucenePxChunkDaos(LuceneEmbeddingStore store, EmbeddingModel embeddingModel) {
        List<PxChunkDao> daos = new ArrayList<>();
        for (var ref : config.getEmbeddingStores()) {
            daos.add(new LucenePxChunkDao(store, embeddingModel, ref));
        }
        return daos;
    }
}
