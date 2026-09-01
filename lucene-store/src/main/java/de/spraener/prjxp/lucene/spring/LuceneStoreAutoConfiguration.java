package de.spraener.prjxp.lucene.spring;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.store.PxChunkDao;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import de.spraener.prjxp.lucene.LucenePxChunkDao;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(name = "prjxp.embedding-store-type", havingValue = "lucene")
public class LuceneStoreAutoConfiguration {
    private final PrjXPConfig config;

    @Bean
    public LuceneEmbeddingStore luceneEmbeddingStore() {
        PrjXPConfig.LuceneEmbeddingStoreConfig lc = config.getEmbeddingStoreLucene();
        return new LuceneEmbeddingStore(
                Path.of(lc.getIndexPath()),
                lc.getVectorDimension()
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

    @Bean
    public EmbeddingStore<TextSegment> luceneEmbeddingStoreBean(LuceneEmbeddingStore store) {
        return store;
    }
}
