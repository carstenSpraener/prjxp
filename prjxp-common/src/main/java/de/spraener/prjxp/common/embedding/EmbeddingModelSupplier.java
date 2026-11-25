package de.spraener.prjxp.common.embedding;

import de.spraener.prjxp.common.config.PrjXPEmbeddingModelReference;
import dev.langchain4j.model.embedding.EmbeddingModel;

public interface EmbeddingModelSupplier {
    boolean canProvide(PrjXPEmbeddingModelReference ref);
    EmbeddingModel provide(PrjXPEmbeddingModelReference ref);
}
