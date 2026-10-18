package de.spraener.prjxp.tibed.embedder;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import de.spraener.prjxp.tibed.EmbeddingExecutor;
import de.spraener.prjxp.tibed.PxChunk2TextSegmentConverter;
import de.spraener.prjxp.tibed.config.EmbeddingStoreSupplier;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Log
@Primary
public class LangChain4JEmbedderImpl implements EmbeddingExecutor {
    public final EmbeddingModel embeddingModel;
    public final EmbeddingStoreSupplier storeSupplier;

    @Override
    public void execute(EmbeddingStore<TextSegment> store, List<PxChunk> chunks) {
        if (chunks.isEmpty()) {
            log.info("Skipping empty batch of chunks");
            return;
        }
        List<PxChunk> valid = chunks.stream()
                .filter(c -> StringUtils.hasText(c.getContent()))
                .toList();

        // FULLTEXT parts are stored with a deterministic placeholder vector (no embedding API cost);
        // the doc still exists in the index for lossless readFile.
        List<PxChunk> fulltext = valid.stream().filter(LangChain4JEmbedderImpl::isFulltext).toList();
        List<PxChunk> normal = valid.stream().filter(c -> !isFulltext(c)).toList();

        int dim = store instanceof LuceneEmbeddingStore l ? l.getVectorDimension() : embeddingModel.dimension();
        List<Embedding> embeddings = new ArrayList<>();
        if (!normal.isEmpty()) {
            embeddings.addAll(embeddingModel.embedAll(
                    normal.stream().map(PxChunk2TextSegmentConverter::convertWithEmbeddingPrefix).toList()).content());
        }
        for (int i = 0; i < fulltext.size(); i++) {
            embeddings.add(placeholderEmbedding(dim));
        }

        // PxChunks in TextSegments umwandeln (Reihenfolge muss zu den Embeddings passen)
        List<TextSegment> segmentsForStorage = new ArrayList<>(normal.stream()
                .map(PxChunk2TextSegmentConverter::convert)
                .toList());
        segmentsForStorage.addAll(fulltext.stream().map(PxChunk2TextSegmentConverter::convert).toList());

        // Nur das Schreiben in die DB synchronisieren
        synchronized (storeSupplier) {
            store.addAll(embeddings, segmentsForStorage);
        }
    }

    private static boolean isFulltext(PxChunk c) {
        return c.getMetadata() != null
                && PxChunk.FULLTEXT_MARKER.equals(c.getMetadata().get(PxChunk.PXCHUNK_TYPE));
    }

    /**
     * Deterministic placeholder vector for FULLTEXT parts (no embedding API cost).
     * All ones — a zero vector is NOT usable: Lucene's COSINE KNN would produce NaN
     * (VectorUtil.cosine asserts finiteness). A fixed unit vector scores ~0.5 against
     * any real query, i.e. it never outranks genuinely similar chunks; FULLTEXT docs
     * are excluded from vector results via the pxchunk_type marker filter anyway.
     */
    private static Embedding placeholderEmbedding(int dim) {
        float[] vector = new float[dim];
        java.util.Arrays.fill(vector, 1f);
        return Embedding.from(vector);
    }
}
