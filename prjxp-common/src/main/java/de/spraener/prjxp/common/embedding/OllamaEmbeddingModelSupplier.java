package de.spraener.prjxp.common.embedding;

import de.spraener.prjxp.common.config.EnvPlaceholderResolver;
import de.spraener.prjxp.common.config.PrjXPEmbeddingModelReference;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class OllamaEmbeddingModelSupplier implements EmbeddingModelSupplier {

    @Override
    public boolean canProvide(PrjXPEmbeddingModelReference ref) {
        return "ollama".equals(ref.getServerType());
    }

    @Override
    public EmbeddingModel provide(PrjXPEmbeddingModelReference ref) {
        return OllamaEmbeddingModel.builder()
                .baseUrl(EnvPlaceholderResolver.resolve(ref.getProviderUrl()))
                .modelName(EnvPlaceholderResolver.resolve(ref.getModelName()))
                .timeout(Duration.ofSeconds(safeTimeout(ref)))
                .build();
    }

    static int safeTimeout(PrjXPEmbeddingModelReference ref) {
        return ref.getTimeoutSecs() != null ? ref.getTimeoutSecs() : 60;
    }
}
