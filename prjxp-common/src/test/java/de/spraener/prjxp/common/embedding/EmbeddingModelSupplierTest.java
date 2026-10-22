package de.spraener.prjxp.common.embedding;

import de.spraener.prjxp.common.config.PrjXPEmbeddingModelReference;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingModelSupplierTest {

    private final OllamaEmbeddingModelSupplier ollama = new OllamaEmbeddingModelSupplier();
    private final OpenAiEmbeddingModelSupplier openAi = new OpenAiEmbeddingModelSupplier();
    private final GeminiEmbeddingModelSupplier gemini = new GeminiEmbeddingModelSupplier();

    private PrjXPEmbeddingModelReference ref(String serverType) {
        PrjXPEmbeddingModelReference ref = new PrjXPEmbeddingModelReference();
        ref.setServerType(serverType);
        ref.setProviderUrl("http://localhost:11434");
        ref.setModelName("mxbai-embed-large");
        return ref;
    }

    @Test
    void ollama_canProvideOnlyOllama() {
        assertThat(ollama.canProvide(ref("ollama"))).isTrue();
        assertThat(ollama.canProvide(ref("openai"))).isFalse();
    }

    @Test
    void openAi_canProvideOpenAiOpenApiLmStudio() {
        assertThat(openAi.canProvide(ref("openai"))).isTrue();
        assertThat(openAi.canProvide(ref("openapi"))).isTrue();
        assertThat(openAi.canProvide(ref("lm-studio"))).isTrue();
        assertThat(openAi.canProvide(ref("ollama"))).isFalse();
        assertThat(openAi.canProvide(ref("gemini"))).isFalse();
        assertThat(openAi.canProvide(ref("custom"))).isFalse();
    }

    @Test
    void gemini_canProvideOnlyGemini() {
        assertThat(gemini.canProvide(ref("gemini"))).isTrue();
        assertThat(gemini.canProvide(ref("openai"))).isFalse();
    }

    @Test
    void openAi_provideAppendsV1Suffix() {
        PrjXPEmbeddingModelReference ref = ref("openai");
        ref.setProviderUrl("http://host:1234");
        EmbeddingModel model = openAi.provide(ref);
        assertThat(model).isInstanceOf(OpenAiEmbeddingModel.class);
    }
}
