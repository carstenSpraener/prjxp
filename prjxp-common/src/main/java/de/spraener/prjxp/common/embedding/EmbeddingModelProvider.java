package de.spraener.prjxp.common.embedding;

import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.config.PrjXPEmbeddingModelReference;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class EmbeddingModelProvider {
    private final List<EmbeddingModelSupplier> suppliers;
    private final PrjXPConfig cfg;

    public EmbeddingModel createEmbeddingModel(PrjXPEmbeddingModelReference ref) {
        for (EmbeddingModelSupplier s : suppliers) {
            if (s.canProvide(ref)) {
                // Wrap uniformly so Ollama/OpenAI/Gemini all honour the same per-request cap.
                return new CappedEmbeddingModel(s.provide(ref), Math.max(1, cfg.getEmbedding().getMaxSegmentsPerBatch()));
            }
        }
        throw new IllegalStateException(
            "There is no embedding supplier for server type '" + ref.getServerType() + "'. Please check configuration.");
    }
}
