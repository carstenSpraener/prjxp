package de.spraener.prjxp.tibed.embedder;

import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.tibed.EmbeddingExecutor;
import de.spraener.prjxp.tibed.PxChunk2TextSegmentConverter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

@Service
@RequiredArgsConstructor
@Log
public class LangChain4JEmbedderImpl implements EmbeddingExecutor {
    public final EmbeddingModel embeddingModel;
    public final EmbeddingStore<TextSegment> store;

    @Override
    public void execute(List<PxChunk> chunks) {
        if (chunks.isEmpty()) {
            log.info("Skipping empty batch of chunks");
            return;
        }
        // PxChunks in TextSegments umwandeln
        List<TextSegment> segments = chunks.stream()
                .filter(c -> StringUtils.hasText(c.getContent()))
                .map(PxChunk2TextSegmentConverter::convert)
                .toList();
        // Embeddings berechnen
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();

        // Nur das Schreiben in die DB synchronisieren
        synchronized (store) {
            store.addAll(embeddings, segments);
        }
    }
}
