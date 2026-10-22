package de.spraener.prjxp.common.embedding;

import de.spraener.prjxp.common.config.EnvPlaceholderResolver;
import de.spraener.prjxp.common.config.PrjXPEmbeddingModelReference;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiEmbeddingModel;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class GeminiEmbeddingModelSupplier implements EmbeddingModelSupplier {

    @Override
    public boolean canProvide(PrjXPEmbeddingModelReference ref) {
        return "gemini".equals(ref.getServerType());
    }

    @Override
    public EmbeddingModel provide(PrjXPEmbeddingModelReference ref) {
        String resolvedApiKey = EnvPlaceholderResolver.resolve(ref.getApiKey());
        return GoogleAiEmbeddingModel.builder()
                .apiKey(resolvedApiKey)
                .modelName(EnvPlaceholderResolver.resolve(ref.getModelName()))
                .timeout(Duration.ofSeconds(OllamaEmbeddingModelSupplier.safeTimeout(ref)))
                .build();
    }
}
