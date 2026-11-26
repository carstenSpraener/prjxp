package de.spraener.prjxp.tibed;

import de.spraener.prjxp.common.model.EmbeddedChunkRecord;
import de.spraener.prjxp.common.model.PxChunk;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;

import java.util.List;

public interface EmbeddingExecutor {
    void execute(EmbeddingStore<TextSegment> store, List<PxChunk> chunks);

    /**
     * Computes embeddings only (no store write): prefix-aware, FULLTEXT chunks get placeholder vectors.
     * <p>
     * Alternative executors that only support direct store writes do not need to support export;
     * the default implementation therefore throws.
     */
    default List<EmbeddedChunkRecord> embed(List<PxChunk> chunks) {
        throw new UnsupportedOperationException("embed() is not supported by " + getClass().getSimpleName());
    }
}
