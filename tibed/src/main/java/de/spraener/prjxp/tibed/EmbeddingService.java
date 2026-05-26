package de.spraener.prjxp.tibed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.PxChunkFromJsonLReader;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.errorlog.PxLogService;
import de.spraener.prjxp.common.config.PrjXPJsonStreamProvider;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.tibed.config.EmbeddingStoreSupplier;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Service;

import java.util.Arrays;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

@Service
@RequiredArgsConstructor
@Log
public class EmbeddingService {
    private final PxLogService logService;
    private final ObjectMapper objMapper;
    private final EmbeddingExecutor embedder;
    private final EmbeddingStoreSupplier embeddingStoreSupplier;
    private final PrjXPJsonStreamProvider streamProvider;
    private final PrjXPConfig cfg;

    public void execute() {
        ProjectDefinition pd = cfg.getActiveProject().orElseThrow(()->new IllegalStateException("No active project!"));
        EmbeddingStore<TextSegment> store = embeddingStoreSupplier.getStore(pd.getName());
        if (pd.isTibedResetStore()) {
            log.warning("Resetting embedding store!");
            store.removeAll(metadataKey("id").isNotEqualTo(0));
        }
        try {
            PxChunkFromJsonLReader reader = new PxChunkFromJsonLReader();
            reader.readChunksFromJsonlStreamBatched(streamProvider.getJsonlStream(pd.getJsonlFile()), pd.getTibedBatchSize(), this::fromJSONL)
                    .forEach(batch -> {
                        embedChunk(store, batch);
                    });
            ;
        } catch (Exception e) {
            logService.error(e, "Error during chunk processing");
        }
    }

    private void embedChunk(EmbeddingStore<TextSegment> store, PxChunk[] chunks) {
        try {
            embedder.execute(store, Arrays.asList(chunks)
                    .stream()
                    .filter( c -> needsEmbedding(store, c))
                    .toList()
            );
            log.info("Embedded batch of " + chunks.length + " chunks");
        } catch (Exception e) {
            logService.error(e, "Embedding of chunk batch failed: %s", e.getMessage());
        }
    }

    private boolean needsEmbedding(EmbeddingStore embeddingStore, PxChunk chunk) {
        Filter filter = new IsEqualTo(PxChunk.PXCHUNK_ID, chunk.getId());
        return !hasEntriesWithFilter(embeddingStore, filter);
    }

    private PxChunk fromJSONL(String line) {
        try {
            return objMapper.readValue(line, PxChunk.class);
        } catch (JsonProcessingException e) {
            logService.error(e, "Error while parsing JSONL as a PxChunk: %s", e.getMessage());
            return null;
        }
    }

    private boolean hasEntriesWithFilter(EmbeddingStore embeddingStore, Filter filter) {
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
