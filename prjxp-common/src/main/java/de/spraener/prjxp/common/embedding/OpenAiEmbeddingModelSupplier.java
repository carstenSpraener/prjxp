package de.spraener.prjxp.common.embedding;

import de.spraener.prjxp.common.config.EnvPlaceholderResolver;
import de.spraener.prjxp.common.config.PrjXPEmbeddingModelReference;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class OpenAiEmbeddingModelSupplier implements EmbeddingModelSupplier {

    @Override
    public boolean canProvide(PrjXPEmbeddingModelReference ref) {
        String serverType = ref.getServerType();
        return "openai".equals(serverType) || "openapi".equals(serverType) || "lm-studio".equals(serverType);
    }

    @Override
    public EmbeddingModel provide(PrjXPEmbeddingModelReference ref) {
        String baseUrl = EnvPlaceholderResolver.resolve(ref.getProviderUrl());
        if (baseUrl != null && !baseUrl.endsWith("/v1") && !baseUrl.endsWith("/v1/")) {
            baseUrl = baseUrl.replaceAll("/$", "") + "/v1";
        }
        String resolvedApiKey = EnvPlaceholderResolver.resolve(ref.getApiKey());
        return OpenAiEmbeddingModel.builder()
                .baseUrl(baseUrl)
                .apiKey(resolvedApiKey)
                .modelName(EnvPlaceholderResolver.resolve(ref.getModelName()))
                .timeout(Duration.ofSeconds(OllamaEmbeddingModelSupplier.safeTimeout(ref)))
                .build();
    }
}
