package de.spraener.tibed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.PxChunkFromJsonLReader;
import de.spraener.prjxp.common.model.PxChunk;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

@Service
@RequiredArgsConstructor
@Log
public class EmbeddingService {
    @Value("${tibed.batchsize:50}")
    private int batchSize;

    private final ObjectMapper objMapper;
    private final EmbeddingExecutor embedder;
    private final EmbeddingStore embeddingStore;

    public void execute(TiBedConfig cfg) {
        embeddingStore.removeAll(metadataKey("id").isNotEqualTo(0));
        if (cfg.getBatchSize() > 0) {
            batchSize = cfg.getBatchSize();
        }
        try {
            PxChunkFromJsonLReader reader = new PxChunkFromJsonLReader();
            reader.readChunksFromJsonlStreamBatched(cfg.getJsonlStream(), batchSize, this::fromJSONL)
                    .parallel()
                    .forEach(batch -> {
                        embedChunk(batch);
                    });
            ;
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void embedChunk(PxChunk[] chunks) {
        try {
            embedder.execute(Arrays.asList(chunks));
            log.info("Embedded batch of " + chunks.length + " chunks");
        } catch (Exception e) {
            log.severe("Embedding of chunk batch failed: " + e.getMessage());
        }
    }

    private PxChunk fromJSONL(String line) {
        try {
            return objMapper.readValue(line, PxChunk.class);
        } catch (JsonProcessingException e) {
            log.severe("Error while parsing JSONL as a PxChunk: " + e.getMessage());
            return null;
        }
    }
}
