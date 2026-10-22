package de.spraener.prjxp.common.embedding;

import de.spraener.prjxp.common.config.PrjXPEmbeddingModelReference;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class EmbeddingModelProvider {
    private final List<EmbeddingModelSupplier> suppliers;

    public EmbeddingModel createEmbeddingModel(PrjXPEmbeddingModelReference ref) {
        for (EmbeddingModelSupplier s : suppliers) {
            if (s.canProvide(ref)) {
                return s.provide(ref);
            }
        }
        throw new IllegalStateException(
            "There is no embedding supplier for server type '" + ref.getServerType() + "'. Please check configuration.");
    }
}
