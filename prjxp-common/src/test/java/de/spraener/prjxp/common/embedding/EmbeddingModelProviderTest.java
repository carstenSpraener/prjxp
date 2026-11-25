package de.spraener.prjxp.common.embedding;

import de.spraener.prjxp.common.config.PrjXPConfig;
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

    private final PrjXPConfig cfg = new PrjXPConfig(); // embedding.maxSegmentsPerBatch defaults to 32
    private final EmbeddingModelProvider provider = new EmbeddingModelProvider(
            List.of(new OllamaEmbeddingModelSupplier(), new OpenAiEmbeddingModelSupplier(), new GeminiEmbeddingModelSupplier()),
            cfg);

    private PrjXPEmbeddingModelReference ref(String serverType) {
        PrjXPEmbeddingModelReference ref = new PrjXPEmbeddingModelReference();
        ref.setServerType(serverType);
        ref.setProviderUrl("http://localhost:11434");
        ref.setModelName("mxbai-embed-large");
        return ref;
    }

    private CappedEmbeddingModel capped(PrjXPEmbeddingModelReference ref) {
        return (CappedEmbeddingModel) provider.createEmbeddingModel(ref);
    }

    @Test
    void ollamaType_returnsOllamaEmbeddingModel() {
        EmbeddingModel delegate = capped(ref("ollama")).delegate();
        assertThat(delegate).isInstanceOf(OllamaEmbeddingModel.class);
    }

    @Test
    void openaiType_returnsOpenAiEmbeddingModel() {
        EmbeddingModel delegate = capped(ref("openai")).delegate();
        assertThat(delegate).isInstanceOf(OpenAiEmbeddingModel.class);
    }

    @Test
    void openapiType_returnsOpenAiEmbeddingModel() {
        EmbeddingModel delegate = capped(ref("openapi")).delegate();
        assertThat(delegate).isInstanceOf(OpenAiEmbeddingModel.class);
    }

    @Test
    void lmStudioType_returnsOpenAiEmbeddingModel() {
        EmbeddingModel delegate = capped(ref("lm-studio")).delegate();
        assertThat(delegate).isInstanceOf(OpenAiEmbeddingModel.class);
    }

    @Test
    void geminiType_returnsGeminiEmbeddingModel() {
        PrjXPEmbeddingModelReference ref = ref("gemini");
        ref.setApiKey("sk-test");
        EmbeddingModel delegate = capped(ref).delegate();
        assertThat(delegate).isInstanceOf(GoogleAiEmbeddingModel.class);
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
        EmbeddingModel delegate = capped(ref).delegate();
        assertThat(delegate).isInstanceOf(OllamaEmbeddingModel.class);
    }

    @Test
    void returnedModel_isCappedWithConfiguredCap() {
        PrjXPConfig cfg = new PrjXPConfig();
        cfg.getEmbedding().setMaxSegmentsPerBatch(16);
        EmbeddingModelProvider provider = new EmbeddingModelProvider(
                List.of(new OllamaEmbeddingModelSupplier(), new OpenAiEmbeddingModelSupplier(), new GeminiEmbeddingModelSupplier()),
                cfg);

        CappedEmbeddingModel model = (CappedEmbeddingModel) provider.createEmbeddingModel(ref("ollama"));

        assertThat(model.maxSegmentsPerBatch()).isEqualTo(16);
        assertThat(model.delegate()).isInstanceOf(OllamaEmbeddingModel.class);
    }
}
