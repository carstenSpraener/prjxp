package de.spraener.prjxp.common.embedding;

import de.spraener.prjxp.common.config.PrjXPEmbeddingModelReference;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiEmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EmbeddingModelProviderTest {

    private final EmbeddingModelProvider provider = new EmbeddingModelProvider(
            List.of(new OllamaEmbeddingModelSupplier(), new OpenAiEmbeddingModelSupplier(), new GeminiEmbeddingModelSupplier()));

    private PrjXPEmbeddingModelReference ref(String serverType) {
        PrjXPEmbeddingModelReference ref = new PrjXPEmbeddingModelReference();
        ref.setServerType(serverType);
        ref.setProviderUrl("http://localhost:11434");
        ref.setModelName("mxbai-embed-large");
        return ref;
    }

    @Test
    void ollamaType_returnsOllamaEmbeddingModel() {
        EmbeddingModel model = provider.createEmbeddingModel(ref("ollama"));
        assertThat(model).isInstanceOf(OllamaEmbeddingModel.class);
    }

    @Test
    void openaiType_returnsOpenAiEmbeddingModel() {
        EmbeddingModel model = provider.createEmbeddingModel(ref("openai"));
        assertThat(model).isInstanceOf(OpenAiEmbeddingModel.class);
    }

    @Test
    void openapiType_returnsOpenAiEmbeddingModel() {
        EmbeddingModel model = provider.createEmbeddingModel(ref("openapi"));
        assertThat(model).isInstanceOf(OpenAiEmbeddingModel.class);
    }

    @Test
    void lmStudioType_returnsOpenAiEmbeddingModel() {
        EmbeddingModel model = provider.createEmbeddingModel(ref("lm-studio"));
        assertThat(model).isInstanceOf(OpenAiEmbeddingModel.class);
    }

    @Test
    void geminiType_returnsGeminiEmbeddingModel() {
        PrjXPEmbeddingModelReference ref = ref("gemini");
        ref.setApiKey("sk-test");
        EmbeddingModel model = provider.createEmbeddingModel(ref);
        assertThat(model).isInstanceOf(GoogleAiEmbeddingModel.class);
    }

    @Test
    void customType_throwsIllegalState() {
        assertThatThrownBy(() -> provider.createEmbeddingModel(ref("custom")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("custom");
    }

    @Test
    void nullTimeout_defaultsTo60() {
        PrjXPEmbeddingModelReference ref = ref("ollama");
        ref.setTimeoutSecs(null);
        EmbeddingModel model = provider.createEmbeddingModel(ref);
        assertThat(model).isInstanceOf(OllamaEmbeddingModel.class);
    }
}
