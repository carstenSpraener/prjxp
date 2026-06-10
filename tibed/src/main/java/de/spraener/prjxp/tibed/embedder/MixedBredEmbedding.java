package de.spraener.prjxp.tibed.embedder;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.tibed.EmbeddingExecutor;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class MixedBredEmbedding implements EmbeddingExecutor {
    private final MixedBredEmebeddingClient client;
    @Override
    public void execute(EmbeddingStore<TextSegment> store, List<PxChunk> chunks) {
        client.embed(chunks);
    }
}
