package de.spraener.prjxp.common.embedding;

import de.spraener.prjxp.common.config.PrjXPConfig;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmbeddingModelConfig {
    private final EmbeddingModelFactory factory;

    public EmbeddingModelConfig() { this(new EmbeddingModelFactory()); }

    @Autowired
    public EmbeddingModelConfig(EmbeddingModelFactory factory) { this.factory = factory; }

    @Bean
    public EmbeddingModel embeddingModel(PrjXPConfig cfg) { return factory.create(cfg.getEmbedding()); }
}
