package de.spraener.prjxp.common.embedding;

import de.spraener.prjxp.common.config.PrjXPConfig;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.junit.jupiter.api.Test;

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
    void openAiType_returnsOpenAiEmbeddingModel() {
        Object model = new EmbeddingModelConfig().embeddingModel(cfg(PrjXPConfig.EmbeddingModelType.OPEN_AI));

        assertThat(model).isInstanceOf(OpenAiEmbeddingModel.class);
    }

    @Test
    void otherType_returnsOllamaEmbeddingModel() {
        Object model = new EmbeddingModelConfig().embeddingModel(cfg(PrjXPConfig.EmbeddingModelType.OLLAMA));

        assertThat(model).isInstanceOf(OllamaEmbeddingModel.class);
    }
}
