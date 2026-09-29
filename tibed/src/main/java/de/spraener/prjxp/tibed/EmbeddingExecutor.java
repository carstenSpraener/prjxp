package de.spraener.prjxp.tibed;

import de.spraener.prjxp.common.model.PxChunk;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;

import java.util.List;

public interface EmbeddingExecutor {
    void execute(EmbeddingStore<TextSegment> store, List<PxChunk> chunks);
}
