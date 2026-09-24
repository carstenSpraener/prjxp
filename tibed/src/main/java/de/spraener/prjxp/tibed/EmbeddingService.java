package de.spraener.prjxp.tibed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.PxChunkFromJsonLReader;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.errorlog.PxLogService;
import de.spraener.prjxp.common.config.PrjXPJsonStreamProvider;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import de.spraener.prjxp.tibed.config.EmbeddingStoreSupplier;
import dev.langchain4j.data.segment.TextSegment;
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
    private final StoreIdChecker storeIdChecker;
    private final PrjXPConfig cfg;

    public void execute() {
        ProjectDefinition pd = cfg.getActiveProject().orElseThrow(() -> new IllegalStateException(
                "No active project '" + cfg.getActiveProjectName() + "' defined. Available projects: "
                        + cfg.getProjects().stream().map(ProjectDefinition::getName).toList()));
        EmbeddingStore<TextSegment> store = embeddingStoreSupplier.getStore(pd.getName());
        if (pd.isTibedResetStore()) {
            log.warning("Resetting embedding store for project '" + pd.getName() + "'!");
            Filter resetFilter = store instanceof LuceneEmbeddingStore
                    ? new IsEqualTo(PxChunk.PXCHUNK_PROJECT, pd.getName())  // shared index: only this project
                    : metadataKey("id").isNotEqualTo(0);                     // dedicated store: wipe all
            store.removeAll(resetFilter);
        }
        try {
            PxChunkFromJsonLReader reader = new PxChunkFromJsonLReader();
            reader.readChunksFromJsonlStreamBatched(streamProvider.getJsonlStream(pd.resolvedJsonlFile()), pd.getTibedBatchSize(), this::fromJSONL)
                    .forEach(batch -> {
                        embedChunk(store, pd.getName(), batch);
                    });
            ;
        } catch (Exception e) {
            logService.error(e, "Error during chunk processing: "+e.getMessage());
        }
    }

    private void embedChunk(EmbeddingStore<TextSegment> store, String projectName, PxChunk[] chunks) {
        try {
            embedder.execute(store, Arrays.asList(chunks)
                    .stream()
                    .peek(c -> c.setProject(projectName))   // stamp BEFORE needsEmbedding (StoreIdChecker is project-scoped)
                    .filter( c -> needsEmbedding(store, c))
                    .toList()
            );
            log.info("Embedded batch of " + chunks.length + " chunks");
        } catch (Exception e) {
            logService.error(e, "Embedding of chunk batch failed: %s", e.getMessage());
        }
    }

    private boolean needsEmbedding(EmbeddingStore<TextSegment> embeddingStore, PxChunk chunk) {
        return storeIdChecker.needsImport(embeddingStore, chunk.getId());
    }

    private PxChunk fromJSONL(String line) {
        try {
            return objMapper.readValue(line, PxChunk.class);
        } catch (JsonProcessingException e) {
            logService.error(e, "Error while parsing JSONL as a PxChunk: %s", e.getMessage());
            return null;
        }
    }

}
