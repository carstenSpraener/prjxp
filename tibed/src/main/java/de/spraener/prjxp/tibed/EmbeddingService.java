package de.spraener.prjxp.tibed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.PxChunkFromJsonLReader;
import de.spraener.prjxp.common.config.PrjXPConfig;
import de.spraener.prjxp.common.errorlog.PxLogService;
import de.spraener.prjxp.common.config.PrjXPJsonStreamProvider;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.common.transfer.TransferPasswordResolver;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import de.spraener.prjxp.tibed.signature.SignatureBatch;
import de.spraener.prjxp.tibed.semantic.SemanticGraphBatch;
import de.spraener.prjxp.tibed.config.EmbeddingStoreSupplier;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import lombok.RequiredArgsConstructor;
import lombok.extern.java.Log;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final TransferPasswordResolver transferPasswordResolver;
    private final StoreIdChecker storeIdChecker;
    private final PrjXPConfig cfg;
    private final SemanticGraphBatch semanticGraphBatch;
    private final AstNeighboursLoader astNeighboursLoader;
    private final SignatureBatch signatureBatch;

    public void execute() {
        ProjectDefinition pd = cfg.getActiveProject().orElseThrow(()->new IllegalStateException(
                "No active project '" + cfg.getActiveProjectName() + "' defined. Available projects: "
                        + cfg.getProjects().stream().map(ProjectDefinition::getName).toList()));
        EmbeddingStore<TextSegment> store = embeddingStoreSupplier.getStore(pd.getName());
        executeForProject(pd, store);
    }

    /** Runs the embedding pipeline for an explicitly given project + store (hub in-process use). */
    public void executeForProject(ProjectDefinition pd, EmbeddingStore<TextSegment> store) {
        AtomicBoolean ingestionSucceeded = new AtomicBoolean(true);
        // Free choice per project (prjxp.yaml tibedBatchSize): the embedding provider's own limits apply —
        // a batch it rejects fails per-batch in embedChunk (logged, pipeline continues). Only non-positive
        // values are guarded, since BatchingUtils.pack would divide by zero / allocate negative capacity.
        int configuredBatchSize = pd.getTibedBatchSize();
        int effectiveBatchSize = Math.max(1, configuredBatchSize);
        if (configuredBatchSize < 1) {
            log.warning("Configured tibedBatchSize=" + configuredBatchSize + " is invalid, using 1");
        }
        if (cfg.isEnrichOnly()) {
            // Enrich-only: the index is assumed to be already populated. Skip the reset and the
            // ingestion (JSONL read + embedding) and go straight to the enrichment batches below.
            log.info("Enrich-only mode for project '" + pd.getName() + "': skipping ingestion, "
                    + "running the enrichment against the existing index.");
            runEnrichment(pd, store, ingestionSucceeded);
            return;
        }
        if (pd.isTibedResetStore()) {
            log.warning("Resetting embedding store for project '" + pd.getName() + "'!");
            Filter resetFilter = store instanceof LuceneEmbeddingStore
                    ? new IsEqualTo(PxChunk.PXCHUNK_PROJECT, pd.getName())  // shared index: only this project
                    : metadataKey("id").isNotEqualTo(0);                     // dedicated store: wipe all
            store.removeAll(resetFilter);
        }
        try {
            PxChunkFromJsonLReader reader = new PxChunkFromJsonLReader();
            // AST neighbours are a per-project sidecar written by chunk-norris; they are merged into
            // the chunk metadata before embedding so the pointer block can expose them.
            Map<String, List<String>> astNeighbours = astNeighboursLoader.load(pd);
            // resolvedJsonlFile(): rootDir-relative, mirroring the writer (null/blank -> stdin, as before)
            // getTransferJsonlStream auto-detects the PRJXPENC magic: a chunk JSONL that chunk-norris
            // encrypted (transfer password available) is decrypted, plaintext files pass through unchanged.
            char[] password = transferPasswordResolver.resolvePassword(cfg.getTransfer().getPasswordEnv());
            log.info("Reading JSONL from " + pd.resolvedJsonlFile() + " for project '" + pd.getName() + "': chunk batch " + effectiveBatchSize);
            reader.readChunksFromJsonlStreamBatched(streamProvider.getTransferJsonlStream(pd.resolvedJsonlFile(), password), effectiveBatchSize, this::fromJSONL)
                    .forEach(batch -> {
                        if (!embedChunk(store, pd.getName(), batch, astNeighbours)) {
                            ingestionSucceeded.set(false);
                        }
                    });
        } catch (Exception e) {
            logService.error(e, "Error during chunk processing: "+e.getMessage());
            ingestionSucceeded.set(false);
        }
        runEnrichment(pd, store, ingestionSucceeded);
    }

    /**
     * Runs the enrichment batches (semantic graph + signatures) against the index. Skipped when
     * the ingestion failed (a broken/partial index must not be enriched). In enrich-only mode the
     * ingestion is skipped entirely, so {@code ingestionSucceeded} is still true and the batches run.
     */
    private void runEnrichment(ProjectDefinition pd, EmbeddingStore<TextSegment> store,
                               AtomicBoolean ingestionSucceeded) {
        if (!ingestionSucceeded.get()) {
            return;
        }
        if (store instanceof LuceneEmbeddingStore luceneStore
                && cfg.getSemanticGraph().isEnabled()) {
            try {
                semanticGraphBatch.run(pd.getName(), luceneStore);
            } catch (IOException e) {
                throw new UncheckedIOException("Semantic-graph step failed for " + pd.getName(), e);
            }
        }

        // The signature layer is independent of the semantic graph: it only needs content in the index.
        if (store instanceof LuceneEmbeddingStore luceneStore
                && cfg.getSignature().isEnabled()) {
            try {
                signatureBatch.run(pd.getName(), luceneStore);
            } catch (IOException e) {
                throw new UncheckedIOException("Signature step failed for " + pd.getName(), e);
            }
        }
    }

    private boolean embedChunk(EmbeddingStore<TextSegment> store, String projectName, PxChunk[] chunks) {
        return embedChunk(store, projectName, chunks, Map.of());
    }

    private boolean embedChunk(EmbeddingStore<TextSegment> store, String projectName, PxChunk[] chunks,
                               Map<String, List<String>> astNeighbours) {
        try {
            embedder.execute(store, Arrays.asList(chunks)
                    .stream()
                    .peek(c -> {
                        c.setProject(projectName);   // stamp BEFORE needsEmbedding (StoreIdChecker is project-scoped)
                        AstNeighboursMerger.merge(c, astNeighbours);
                    })
                    .filter( c -> needsEmbedding(store, projectName, c))
                    .toList()
            );
            log.info("Embedded chunk batch of " + chunks.length + " chunks for project " + projectName);
            return true;
        } catch (Exception e) {
            logService.error(e, "Embedding of chunk batch failed: %s", e.getMessage());
            return false;
        }
    }

    /**
     * Embeds the given chunks into the store (hub document-embedding runs). Chunks are project-stamped
     * and deduplicated exactly like in the standard pipeline; batch failures are logged, not thrown.
     */
    public void embedChunks(EmbeddingStore<TextSegment> store, String projectName, List<PxChunk> chunks) {
        embedChunk(store, projectName, chunks.toArray(new PxChunk[0]));
    }

    private boolean needsEmbedding(EmbeddingStore<TextSegment> embeddingStore, String projectName, PxChunk chunk) {
        return storeIdChecker.needsImport(embeddingStore, chunk.getId(), projectName);
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
