package de.spraener.prjxp.common.embedding;

import de.spraener.prjxp.common.config.PrjXPConfig;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingModelConfigTest {

    private PrjXPConfig cfg(PrjXPConfig.EmbeddingModelType type) {
        PrjXPConfig cfg = new PrjXPConfig();
        PrjXPConfig.EmbeddingConfig embedding = new PrjXPConfig.EmbeddingConfig();
        embedding.setType(type);
        embedding.setApiBaseURL("http://x");
        embedding.setModelName("m");
        embedding.setApiKey("k");
        embedding.setTimeout(30);
        cfg.setEmbedding(embedding);
        return cfg;
    }

    @Test
    void openAiType_returnsCappedOpenAiEmbeddingModel() {
        CappedEmbeddingModel model = (CappedEmbeddingModel) new EmbeddingModelConfig().embeddingModel(cfg(PrjXPConfig.EmbeddingModelType.OPEN_AI));

        assertThat(model.delegate()).isInstanceOf(OpenAiEmbeddingModel.class);
    }

    @Test
    void otherType_returnsCappedOllamaEmbeddingModel() {
        CappedEmbeddingModel model = (CappedEmbeddingModel) new EmbeddingModelConfig().embeddingModel(cfg(PrjXPConfig.EmbeddingModelType.OLLAMA));

        assertThat(model.delegate()).isInstanceOf(OllamaEmbeddingModel.class);
    }

    /**
     * Regression: langchain4j's OpenAiEmbeddingModel defaults maxSegmentsPerBatch to 2048, which
     * makes a large embedAll() a single giant request that a local model times out on. The config
     * must cap it so every request stays small enough to finish within the timeout.
     */
    @Test
    void openAiType_appliesMaxSegmentsPerBatch() throws Exception {
        PrjXPConfig cfg = cfg(PrjXPConfig.EmbeddingModelType.OPEN_AI);
        cfg.getEmbedding().setMaxSegmentsPerBatch(16);
        CappedEmbeddingModel model = (CappedEmbeddingModel) new EmbeddingModelConfig().embeddingModel(cfg);

        // The wrapper applies the cap uniformly ...
        assertThat(model.maxSegmentsPerBatch()).isEqualTo(16);

        // ... and the underlying OpenAI model is capped too (defence in depth for direct use).
        OpenAiEmbeddingModel delegate = (OpenAiEmbeddingModel) model.delegate();
        Field f = OpenAiEmbeddingModel.class.getDeclaredField("maxSegmentsPerBatch");
        f.setAccessible(true);
        assertThat((int) f.get(delegate)).isEqualTo(16);
    }

    @Test
    void openAiType_appliesConfiguredDimensions() throws Exception {
        PrjXPConfig cfg = cfg(PrjXPConfig.EmbeddingModelType.OPEN_AI);
        cfg.getEmbedding().setDimensions(1024);
        CappedEmbeddingModel model = (CappedEmbeddingModel) new EmbeddingModelConfig().embeddingModel(cfg);

        OpenAiEmbeddingModel delegate = (OpenAiEmbeddingModel) model.delegate();
        Field f = OpenAiEmbeddingModel.class.getDeclaredField("dimensions");
        f.setAccessible(true);
        assertThat((Integer) f.get(delegate)).isEqualTo(1024);
    }
}
