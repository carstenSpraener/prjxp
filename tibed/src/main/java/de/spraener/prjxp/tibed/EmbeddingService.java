package de.spraener.prjxp.tibed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.PxChunkFromJsonLReader;
import de.spraener.prjxp.common.model.PxChunk;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

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
        log.info("Starting embedding process with\n" +
                "  embedding model: '%s'\n" +
                "  ollama server url: '%s'\n" +
                "  chroma-tenant: '%s'\n" +
                "  chroma-database: '%s'\n" +
                "  chroma-collection: '%s'".formatted(
                        cfg.getEmbeddingModelName(),
                        cfg.getOllamaUrl(),
                        cfg.getEmbedChromaTenant(),
                        cfg.getEmbedChromaDatabase(),
                        cfg.getCollectionName()
                ));
        if( cfg.isResetStore() ) {
            embeddingStore.removeAll(metadataKey("id").isNotEqualTo(0));
        }
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
            embedder.execute(Arrays.asList(chunks)
                    .stream()
                    .filter(this::needsEmbeddeding)
                    .toList()
            );
            log.info("Embedded batch of " + chunks.length + " chunks");
        } catch (Exception e) {
            log.severe("Embedding of chunk batch failed: " + e.getMessage());
        }
    }

    private boolean needsEmbeddeding(PxChunk chunk) {
        Filter filter = new IsEqualTo(PxChunk.PXCHUNK_ID, chunk.getId());
        return !hasEntriesWithFilter(filter);
    }

    private PxChunk fromJSONL(String line) {
        try {
            return objMapper.readValue(line, PxChunk.class);
        } catch (JsonProcessingException e) {
            log.severe("Error while parsing JSONL as a PxChunk: " + e.getMessage());
            return null;
        }
    }

    private boolean hasEntriesWithFilter(Filter filter) {
        Embedding dummyEmbedding = Embedding.from(new float[1024]);
        // Wir führen eine Suche aus, die nur auf Metadaten basiert (max 100 Treffer)
        // Hinweis: EmbeddingStore.search gibt oft Scored-Matches zurück
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(dummyEmbedding)
                .filter(filter)
                .maxResults(100)
                .build();

        return !embeddingStore.search(request)
                .matches().isEmpty();
    }

}
