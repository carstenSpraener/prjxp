package de.spraener.prjxp.mcp.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.chuno.ChunkProcess;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.model.EmbeddedChunkRecord;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import de.spraener.prjxp.tibed.EmbeddingCompatibilityChecker;
import de.spraener.prjxp.tibed.EmbeddingCompatibilityChecker.CheckReport;
import de.spraener.prjxp.tibed.EmbeddingCompatibilityChecker.Verdict;
import de.spraener.prjxp.tibed.EmbeddingImportService;
import de.spraener.prjxp.tibed.EmbeddingService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * Hub pipeline: a single FIFO worker running chunk → scoped reset + embed per project,
 * driving the registry's lifecycle status. Also self-heals on startup: projects whose chunks
 * are already in the shared index go straight to READY, everything else runs the full pipeline.
 */
@Component
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
@RequiredArgsConstructor
public class PipelineOrchestrator implements ImportHandler {

    private static final Logger log = LoggerFactory.getLogger(PipelineOrchestrator.class);

    private final HubProjectRegistry registry;
    private final ChunkProcess chunkProcess;          // Phase 01: now a bean in the hub app
    private final EmbeddingService embeddingService;  // Phase 01: normal (chunk → embed) pipeline
    private final EmbeddingImportService embeddingImportService;  // pre-embedded import (no chunking)
    private final EmbeddingCompatibilityChecker compatibilityChecker;  // cross-platform embedding validation
    private final EmbeddingModel embeddingModel;      // for compatibility checks
    private final LuceneEmbeddingStore luceneStore;   // shared bean
    private final HubProperties hubProps;              // compatibility thresholds & sample size
    private final ObjectMapper objMapper;               // for reading EmbeddedChunkRecord from JSONL

    /** FIFO: single worker — the Lucene index allows only one writer at a time. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "prjxp-pipeline");
        t.setDaemon(true);
        return t;
    });

    /**
     * Names with a pipeline task queued or running, mapped to a per-task token — enqueue is idempotent
     * per name (no double runs); a re-import supersedes any in-flight run of the same name.
     */
    private final Map<String, Object> inFlight = new ConcurrentHashMap<>();

    @PreDestroy
    public void shutdown() {
        worker.shutdown();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                worker.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            worker.shutdownNow();
        }
    }

    @Override
    public void onImported(String name, Path projectDir) {
        if (registry.entry(name).isPresent()) {
            registry.unregisterProject(name);   // re-import: drop stale DAO/entry first
        }
        registry.registerProject(name, projectDir);   // status IMPORTING
        if (hasIndexedChunks(name)) {
            registry.setStatus(name, ProjectStatus.READY, null);
            log.info("Imported project '{}' already has index entries — skipping pipeline and marking READY", name);
            return;
        }
        enqueueForced(name);   // no index data yet -> run full pipeline
    }

    @Override
    public void onFailed(String name, String error) {
        if (registry.entry(name).isPresent()) {
            registry.setStatus(name, ProjectStatus.FAILED, error);
        } else {
            log.warn("Import failed for unregistered project '{}': {}", name, error);
        }
    }

    /** Queues the full pipeline (chunk → scoped reset + embed) for a registered project; idempotent per name. */
    public void enqueue(String name) {
        Object token = new Object();
        if (inFlight.putIfAbsent(name, token) != null) {
            return;   // already queued/running — never double-run a project's pipeline
        }
        submit(name, token);
    }

    /** Like {@link #enqueue} but supersedes a queued/running pipeline of the same name (tar re-import). */
    private void enqueueForced(String name) {
        Object token = new Object();
        inFlight.put(name, token);
        submit(name, token);
    }

    private void submit(String name, Object token) {
        worker.submit(() -> {
            try {
                runPipeline(name);
            } finally {
                inFlight.remove(name, token);   // only if this task is still the current one for the name
            }
        });
    }

    private void runPipeline(String name) {
        ProjectDefinition def = registry.definitionOf(name);
        if (def == null) {
            return;
        }
        try {
            if (isPreEmbedded(def)) {
                runPreEmbeddedPipeline(name, def);
            } else {
                runStandardPipeline(name, def);
            }
        } catch (Exception e) {
            log.error("Pipeline failed for project '{}': {}", name, e.toString());
            registry.setStatus(name, ProjectStatus.FAILED, String.valueOf(e.getMessage()));
        } finally {
            wipeIfDeregistered(name);   // race guard: marker removed mid-run -> the entry is gone, wipe what we wrote
        }
    }

    private boolean isPreEmbedded(ProjectDefinition def) {
        return def.getEmbeddingsFile() != null && !def.getEmbeddingsFile().isBlank();
    }

    private void runStandardPipeline(String name, ProjectDefinition def) throws Exception {
        registry.setStatus(name, ProjectStatus.CHUNKING, null);
        chunkProcess.executeForProject(def);
        registry.setStatus(name, ProjectStatus.EMBEDDING, null);
        def.setTibedResetStore(true);   // import = source of truth: scoped reset + re-embed
        embeddingService.executeForProject(def, luceneStore);
        registry.setStatus(name, ProjectStatus.READY, null);
    }

    /**
     * Pipeline for pre-embedded projects: validate embedding compatibility, then import.
     * Skips chunking entirely since the embeddings are pre-computed externally.
     */
    private void runPreEmbeddedPipeline(String name, ProjectDefinition def) throws Exception {
        String embeddingsPath = def.resolvedEmbeddingsFile();
        if (embeddingsPath == null) {
            throw new IllegalStateException("Pre-embedded project '" + name + "' has no resolved embeddingsFile");
        }

        // Read sample chunks for compatibility check
        List<EmbeddedChunkRecord> samples = readFirstN(embeddingsPath, hubProps.getCompatibilitySampleSize());
        if (samples.isEmpty()) {
            throw new IllegalStateException("No valid embedded chunk records found in " + embeddingsPath);
        }

        // Compatibility check: compare imported vectors against locally computed ones
        CheckReport report = compatibilityChecker.check(
                samples, embeddingModel,
                hubProps.getCompatibilitySampleSize(),
                hubProps.getCompatibilityThresholdHigh(),
                hubProps.getCompatibilityThresholdLow()
        );

        if (report.verdict() == Verdict.INCOMPATIBLE) {
            String msg = "Embedding model incompatible: avg cosine=" + String.format("%.4f", report.avgCosine())
                    + " (threshold: " + hubProps.getCompatibilityThresholdLow() + "). "
                    + "The imported embeddings were likely generated with a different model or incompatible hardware. "
                    + "Re-embed using the local model (tibed --mode store) or adjust thresholds in prjxp.hub.compatibility.*";
            log.error("Pre-embedded import blocked for project '{}': {}", name, msg);
            registry.setStatus(name, ProjectStatus.FAILED, msg);
            return;   // do not proceed with import
        }

        if (report.verdict() == Verdict.PARTIALLY_COMPATIBLE) {
            log.warn("Pre-embedded import for project '{}' with PARTIAL compatibility (avg cosine={:.4f}). "
                    + "Cross-platform rounding differences detected — search quality may be slightly degraded.",
                    name, report.avgCosine());
        }

        // Proceed with import: scoped reset + import pre-embedded vectors
        registry.setStatus(name, ProjectStatus.EMBEDDING, null);
        def.setTibedResetStore(true);   // scoped reset before import
        importPreEmbeddedChunks(name, def, embeddingsPath);
        registry.setStatus(name, ProjectStatus.READY, null);
    }

    /**
     * Imports pre-embedded chunks from a JSONL file into the shared Lucene index.
     * Handles scoped reset, batched import and project stamping.
     */
    private void importPreEmbeddedChunks(String name, ProjectDefinition def, String jsonlPath) {
        try (var lines = Files.lines(Path.of(jsonlPath))) {
            List<EmbeddedChunkRecord> batch = new ArrayList<>();
            int batchSize = Math.max(1, def.getTibedBatchSize());

            // Scoped reset if requested
            if (def.isTibedResetStore()) {
                Filter resetFilter = new IsEqualTo(PxChunk.PXCHUNK_PROJECT, name);  // shared index: only this project
                luceneStore.removeAll(resetFilter);
            }

            lines.map(line -> {
                try {
                    return objMapper.readValue(line, EmbeddedChunkRecord.class);
                } catch (Exception e) {
                    log.warn("Skipping unparseable line in {}: {}", jsonlPath, e.getMessage());
                    return null;
                }
            }).filter(record -> record != null && hasVector(record))
                    .forEach(record -> {
                        batch.add(record);
                        if (batch.size() >= batchSize) {
                            flushBatch(name, batch);
                            batch.clear();
                        }
                    });

            // Flush remaining
            if (!batch.isEmpty()) {
                flushBatch(name, batch);
            }

            log.info("Imported pre-embedded chunks for project '{}' from {}", name, jsonlPath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read embeddings file " + jsonlPath, e);
        }
    }

    private void flushBatch(String projectName, List<EmbeddedChunkRecord> batch) {
        EmbeddingStore<TextSegment> store = luceneStore;   // shared bean

        List<EmbeddedChunkRecord> toImport = batch.stream()
                .filter(record -> needsImport(store, record))
                .toList();

        if (toImport.isEmpty()) {
            log.debug("Skipping batch of {} chunks (already in store)", batch.size());
            return;
        }

        List<Embedding> embeddings = toImport.stream()
                .map(record -> Embedding.from(record.vector()))
                .toList();

        List<TextSegment> segments = toImport.stream()
                .map(record -> {
                    PxChunk chunk = record.toPxChunk();
                    chunk.setProject(projectName);   // stamp (multi-project store separation)
                    return de.spraener.prjxp.tibed.PxChunk2TextSegmentConverter.convert(chunk);
                })
                .toList();

        store.addAll(embeddings, segments);
        log.info("Imported {}/{} chunks for project '{}'", toImport.size(), batch.size(), projectName);
    }

    private boolean hasVector(EmbeddedChunkRecord record) {
        return record.vector() != null && record.vector().length > 0;
    }

    private boolean needsImport(EmbeddingStore<TextSegment> store, EmbeddedChunkRecord record) {
        // Check if this chunk ID is already in the store (avoid duplicates)
        // StoreIdChecker logic: if the record's ID is not in the store, it needs import
        // For simplicity here we use a basic check — StoreIdChecker is project-scoped and more sophisticated
        return true;   // always import (scoped reset already wiped the project's chunks)
    }

    private List<EmbeddedChunkRecord> readFirstN(String jsonlPath, int n) {
        List<EmbeddedChunkRecord> result = new ArrayList<>();
        try (var lines = Files.lines(Path.of(jsonlPath))) {
            lines.map(line -> {
                try {
                    return objMapper.readValue(line, EmbeddedChunkRecord.class);
                } catch (Exception e) {
                    return null;
                }
            }).filter(r -> r != null && hasVector(r))
                    .limit(n)
                    .forEach(result::add);
        } catch (IOException e) {
            log.warn("Could not read embeddings file {} for compatibility check: {}", jsonlPath, e.getMessage());
        }
        return result;
    }

    private void wipeIfDeregistered(String name) {
        if (registry.entry(name).isEmpty()) {
            log.warn("Project '{}' was deregistered mid-pipeline — wiping its chunks from the shared index", name);
            luceneStore.removeAll(new IsEqualTo(PxChunk.PXCHUNK_PROJECT, name));   // scoped (shared index!)
        }
    }

    private boolean hasIndexedChunks(String name) {
        return luceneStore.hasMatch(new IsEqualTo(PxChunk.PXCHUNK_PROJECT, name));
    }

    /** On startup: sync the registry with both roots (snapshots + live), then mark indexed projects READY or re-run them.
     * Pre-embedded projects require a {@value ProjectConfigFileParser#READY_MARKER_FILE_NAME} marker before being enqueued. */
    @EventListener(ApplicationReadyEvent.class)
    public void selfHeal() {
        for (String name : registry.discoverProjects()) {
            if (hasIndexedChunks(name)) {
                registry.setStatus(name, ProjectStatus.READY, null);   // index already has this project's chunks
            } else if (isPreEmbeddedAtStartup(name)) {
                log.info("Pre-embedded project '{}' missing .ready marker at startup — skipping until ready", name);
                // do not enqueue; the poller will pick it up once .ready appears
            } else {
                enqueue(name);   // nothing/partial in index -> full pipeline
            }
        }
    }

    private boolean isPreEmbeddedAtStartup(String name) {
        ProjectDefinition def = registry.definitionOf(name);
        if (def == null || !isPreEmbedded(def)) {
            return false;
        }
        // Check for .ready marker in the project's root directory
        var entry = registry.entry(name);
        if (entry.isEmpty()) {
            return false;
        }
        Path rootDir = entry.get().getRootDir();
        return !Files.exists(rootDir.resolve(ProjectConfigFileParser.READY_MARKER_FILE_NAME));
    }
}
