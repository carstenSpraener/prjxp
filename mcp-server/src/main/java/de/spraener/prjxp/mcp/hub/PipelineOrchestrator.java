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
import de.spraener.prjxp.tibed.EmbeddingExportService;
import de.spraener.prjxp.tibed.EmbeddingService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

/**
 * Hub pipeline: a single FIFO worker running chunk → embed (export) → swap per project,
 * driving the registry's lifecycle status. The old index stays in place (project stays
 * searchable) until the new embeddings are fully computed; only the final swap
 * (scoped removeAll + batched import) is briefly offline. Also self-heals on startup:
 * projects whose chunks are already in the shared index go straight to READY,
 * everything else runs the full pipeline.
 */
@Component
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
@RequiredArgsConstructor
public class PipelineOrchestrator implements ImportHandler {

    private static final Logger log = LoggerFactory.getLogger(PipelineOrchestrator.class);

    /**
     * Reindex progress scale on the shared 0-100 axis: 10% for chunking, 70% for embedding,
     * 20% for storing (the swap import). The scope (chunk count) is known after chunking,
     * so embedding and storing report granular progress within their reserved bands.
     */
    private static final int CHUNKING_END = 10;
    private static final int EMBEDDING_END = 80;
    private static final int STORING_END = 100;

    private final HubProjectRegistry registry;
    private final ChunkProcess chunkProcess;          // Phase 01: now a bean in the hub app
    private final EmbeddingExportService exportService;  // chunk JSONL -> EmbeddedChunkRecord JSONL (temp file)
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

    /** Pipeline listeners (Spring injects all beans; empty when none). Setter-injected to keep the constructor stable. */
    private volatile List<PipelineListener> listeners = List.of();

    /** The run currently executing on the pipeline thread (null between runs). */
    private volatile PipelineRun currentRun;

    @Autowired(required = false)
    public void setPipelineListeners(List<PipelineListener> listeners) {
        this.listeners = List.copyOf(listeners);
    }

    /**
     * Tibed's in-process embedder (project-stamp + dedup + store write). Setter-injected to keep the
     * 8-arg constructor stable; a null value (no bean) disables document-embedding runs gracefully.
     */
    private EmbeddingService embeddingService;

    @Autowired(required = false)
    public void setEmbeddingService(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

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
        if (inFlight.containsKey(name)) {
            enqueueForced(name);   // reindex still in flight -> the index data would be stale; supersede instead of skipping
            return;
        }
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

    /** Queues the full pipeline (chunk → embed → swap) for a registered project; idempotent per name. */
    public void enqueue(String name) {
        Object token = new Object();
        if (inFlight.putIfAbsent(name, token) != null) {
            return;   // already queued/running — never double-run a project's pipeline
        }
        submit(PipelineRun.root(name), token);
    }

    /** Like {@link #enqueue} but supersedes a queued/running pipeline of the same name (tar re-import, embedded import). */
    public void enqueueForced(String name) {
        Object token = new Object();
        inFlight.put(name, token);
        submit(PipelineRun.root(name), token);
    }

    /**
     * Queues a DocPipe-only chain for a registered project: documentation generation + document
     * embedding, WITHOUT re-chunking or re-embedding the code (the "DocPipe" button). The code
     * index stays in place throughout, so the project remains searchable. Idempotent per name
     * like {@link #enqueue} — a full reindex already in flight is never superseded.
     */
    public void enqueueDocPipe(String name) {
        Object token = new Object();
        if (inFlight.putIfAbsent(name, token) != null) {
            return;   // already queued/running — never double-run
        }
        submit(docPipeRun(name), token);
    }

    /**
     * Phase-1 run of a DocPipe-only chain: a fresh chain (new ledger) whose work only marks the
     * documentation phase. The actual DocPipe run happens in the pipeline listener (single source
     * of truth), which then schedules the document-embedding retrigger — exactly like after a root
     * run, but without the code pipeline.
     */
    private PipelineRun docPipeRun(String name) {
        return new PipelineRun(name, 1, new HashSet<>(), (runName, def) -> {
            registry.setReindexStatus(runName, ReindexStatus.DOCPIPE);
            registry.setReindexProgress(runName, 0);
        });
    }

    private void submit(PipelineRun run, Object token) {
        worker.submit(() -> {
            try {
                runPipeline(run);
            } finally {
                inFlight.remove(run.name(), token);   // only if this task is still the current one for the name
            }
        });
    }

    /**
     * Schedules a retrigger run for the chain of the currently executing run. Must be called from
     * within a listener callback (pipeline thread). Returns immediately — the FIFO worker picks it up.
     * The retrigger inherits the chain's trigger ledger, so listeners that already fired do not fire again.
     */
    public void scheduleRetrigger(String name, PipelineWork work) {
        PipelineRun current = currentRun;
        if (current == null || !current.name().equals(name)) {
            log.warn("scheduleRetrigger for '{}' outside of its run — ignoring", name);
            return;
        }
        PipelineRun next = current.nextPhase(work);
        current.noteRetriggerScheduled();
        Object token = new Object();
        inFlight.put(name, token);   // forced: the current run's token is still present (removed only after its runPipeline returns)
        submit(next, token);
    }

    /**
     * Schedules a retrigger run that chunks the documents from the given source and embeds them as an
     * ADDITION to the project's index (doc-scoped reset via {@link DocumentSource#DOCPIPE_GENERATED} —
     * code chunks are never wiped). Called from listener callbacks; returns immediately (FIFO queue).
     */
    public void scheduleEmbedding(String name, DocumentSource source) {
        scheduleRetrigger(name, (runName, def) -> runDocumentEmbedding(runName, def, source));
    }

    /**
     * The document-embedding run: resolves the source's (root-relative) paths, chunks them (markdown
     * whitelisted), performs a doc-scoped reset (only previously generated document chunks — code
     * chunks are untouched), stamps the new chunks and embeds them additively.
     */
    private void runDocumentEmbedding(String name, ProjectDefinition def, DocumentSource source) throws Exception {
        if (embeddingService == null) {
            log.warn("EmbeddingService not available — skipping document embedding for '{}'", name);
            return;
        }
        registry.setReindexStatus(name, ReindexStatus.EMBEDDING);
        registry.setReindexProgress(name, 0);
        Path root = Path.of(def.getRootDir()).toAbsolutePath().normalize();
        List<Path> files = source.documentStream()
                .map(p -> root.resolve(p).normalize())
                .filter(Files::isRegularFile)
                .toList();
        if (files.isEmpty()) {
            log.info("Document embedding for '{}': no files from source — nothing to do", name);
            return;
        }

        List<PxChunk> chunks = chunkProcess.executeForFiles(withMarkdownWhitelisted(def), files);
        if (chunks.isEmpty()) {
            log.info("Document embedding for '{}': chunker produced no chunks — nothing to do", name);
            return;
        }

        // Doc-scoped reset: remove only previously generated document chunks (never code chunks).
        // metadataFieldKey() maps the logical key to the indexed field (pxchunk_metadata.* prefix).
        Filter docReset = new IsEqualTo(PxChunk.PXCHUNK_PROJECT, name)
                .and(new IsEqualTo(PxChunk.metadataFieldKey(DocumentSource.DOCPIPE_GENERATED), "true"));
        luceneStore.removeAll(docReset);

        chunks.forEach(c -> {
            c.getMetadata().put(DocumentSource.DOCPIPE_GENERATED, "true");
            c.setProject(name);   // stamp (multi-project store separation)
        });

        embeddingService.embedChunks(luceneStore, name, chunks);
        registry.setReindexProgress(name, 100);
    }

    /** Copy of the definition with "md" ensured in the chuno whitelist (the original is registry-owned — never mutate it). */
    private ProjectDefinition withMarkdownWhitelisted(ProjectDefinition def) {
        ProjectDefinition copy = new ProjectDefinition();
        copy.setName(def.getName());
        copy.setRootDir(def.getRootDir());
        copy.setJsonlFile(def.getJsonlFile());
        copy.setChunoWhiteList(appendWhitelistEntry(def.getChunoWhiteList(), "md"));
        copy.setTibedBatchSize(def.getTibedBatchSize());
        copy.setTibedResetStore(false);   // never a full reset here — the doc-scoped reset is precise
        copy.setEmbeddingsFile(def.getEmbeddingsFile());
        copy.setPrompt(def.getPrompt());
        copy.setFulltext(def.isFulltext());
        return copy;
    }

    /** Appends {@code entry} to a comma-separated whitelist unless it is already present (case-insensitive). */
    private String appendWhitelistEntry(String whitelist, String entry) {
        if (whitelist == null || whitelist.isBlank()) { return entry; }
        for (String part : whitelist.split(",")) {
            if (part.trim().equalsIgnoreCase(entry)) { return whitelist; }   // already present
        }
        return whitelist + "," + entry;
    }

    private void runPipeline(PipelineRun run) {
        String name = run.name();
        currentRun = run;
        try {
            ProjectDefinition def = registry.definitionOf(name);
            if (def == null) {
                return;
            }

            registry.setEmbeddingPhase(name, run.phase());
            dispatchStart(run);

            boolean success;
            try {
                if (run.work() != null) {
                    run.work().execute(name, def);          // retrigger work (later phase)
                    success = true;
                } else if (isPreEmbedded(def)) {
                    success = runPreEmbeddedPipeline(name, def);
                } else {
                    success = runStandardPipeline(name, def);
                }
            } catch (Exception e) {
                log.error("Pipeline failed for project '{}': {}", name, e.toString());
                registry.setStatus(name, ProjectStatus.FAILED, String.valueOf(e.getMessage()));
                registry.setReindexStatus(name, ReindexStatus.FAILED);
                return;   // no finish dispatch on failure — listeners must not act on a broken run
            }

            if (!success) {
                return;   // keep-old-index soft failure: the terminal status was set inside the pipeline; no finish dispatch
            }

            dispatchFinished(run);

            if (run.retriggersScheduled() == 0) {
                registry.setStatus(name, ProjectStatus.READY, null);
                registry.setReindexStatus(name, ReindexStatus.COMPLETE);
                registry.setReindexProgress(name, STORING_END);
            } else {
                log.info("Project '{}' chain continues: {} retrigger run(s) scheduled", name, run.retriggersScheduled());
            }
        } finally {
            currentRun = null;
            wipeIfDeregistered(name);   // race guard: marker removed mid-run -> the entry is gone, wipe what we wrote
        }
    }

    private void dispatchStart(PipelineRun run) {
        for (PipelineListener listener : listeners) {
            String id = listener.getClass().getName() + "#start";
            if (run.isTriggered(id)) { continue; }
            run.markTriggered(id);   // BEFORE the call: a retrigger scheduled mid-callback inherits this mark (loop breaker)
            try {
                listener.onPipelineStart(this, run.name(), run);
            } catch (Exception e) {
                log.warn("PipelineListener {} failed in onPipelineStart for '{}': {}", id, run.name(), e.toString());
            }
        }
    }

    private void dispatchFinished(PipelineRun run) {
        for (PipelineListener listener : listeners) {
            // Distinct ledger key from onPipelineStart: a listener receives BOTH start and finish in the
            // root run, but neither again in a retrigger run (the loop breaker).
            String id = listener.getClass().getName() + "#finish";
            if (run.isTriggered(id)) { continue; }
            run.markTriggered(id);
            try {
                listener.onPipelineFinished(this, run.name(), run);
            } catch (Exception e) {
                log.warn("PipelineListener {} failed in onPipelineFinished for '{}': {}", id, run.name(), e.toString());
            }
        }
    }

    private boolean isPreEmbedded(ProjectDefinition def) {
        return def.getEmbeddingsFile() != null && !def.getEmbeddingsFile().isBlank();
    }

    /**
     * Standard pipeline with keep-old-index semantics:
     * <ol>
     *   <li>chunking — old index untouched, project stays searchable (READY if it had an index)</li>
     *   <li>embedding via export — old index untouched, embeddings land in a temp file</li>
     *   <li>swap — scoped removeAll + batched import (briefly non-searchable)</li>
     * </ol>
     * Failure semantics: with an intact old index (failure before the swap) the project stays
     * READY (old index in use) and only the reindex status is FAILED; otherwise FAILED.
     *
     * @return true when the run completed successfully (the terminal READY/COMPLETE/100 status is
     * then set centrally by {@link #runPipeline}, gated on whether a retrigger was scheduled);
     * false on a keep-old-index failure (the terminal status is set here, inside the catch).
     */
    private boolean runStandardPipeline(String name, ProjectDefinition def) {
        boolean hadIndex = hasIndexedChunks(name);
        boolean swapStarted = false;
        Path embeddedFile = null;
        try {
            // 1. Chunking — old index untouched, project stays searchable
            registry.setStatus(name, hadIndex ? ProjectStatus.READY : ProjectStatus.CHUNKING, null);
            registry.setReindexStatus(name, ReindexStatus.CHUNKING);
            registry.setReindexProgress(name, 0);
            chunkProcess.executeForProject(def);
            registry.setReindexProgress(name, CHUNKING_END);

            // 2. Embedding via export — old index untouched; granular progress within the 10-80 band
            registry.setReindexStatus(name, ReindexStatus.EMBEDDING);
            registry.setReindexProgress(name, CHUNKING_END);
            if (!hadIndex) {
                registry.setStatus(name, ProjectStatus.EMBEDDING, null);   // no old index -> not searchable anyway
            }
            embeddedFile = Files.createTempFile("prjxp-" + name + "-", "-embedded.jsonl");
            exportService.exportForProject(def, embeddedFile,
                    (done, total) -> registry.setReindexProgress(name, mapPhaseProgress(done, total, CHUNKING_END, EMBEDDING_END)));

            // 3. Swap: old index gone, new embeddings in (brief offline window); granular progress within the 80-100 band
            swapStarted = true;
            registry.setReindexStatus(name, ReindexStatus.IMPORTING);
            registry.setReindexProgress(name, EMBEDDING_END);
            if (hadIndex) {
                registry.setStatus(name, ProjectStatus.EMBEDDING, null);   // briefly non-searchable
            }
            luceneStore.removeAll(new IsEqualTo(PxChunk.PXCHUNK_PROJECT, name));
            importEmbeddedFile(name, def, embeddedFile.toString(),
                    (done, total) -> registry.setReindexProgress(name, mapPhaseProgress(done, total, EMBEDDING_END, STORING_END)));

            // 4. Done — the terminal READY/COMPLETE/100 status is set centrally by runPipeline
            // (gated on whether a listener scheduled a retrigger, so the chain can continue).
            return true;
        } catch (Exception e) {
            log.error("Pipeline failed for project '{}': {}", name, e.toString());
            registry.setReindexStatus(name, ReindexStatus.FAILED);
            if (hadIndex && !swapStarted) {
                registry.setStatus(name, ProjectStatus.READY,
                        "Reindex failed (" + e.getMessage() + ") — old index still in use");
            } else {
                registry.setStatus(name, ProjectStatus.FAILED, String.valueOf(e.getMessage()));
            }
            return false;
        } finally {
            if (embeddedFile != null) {
                try {
                    Files.deleteIfExists(embeddedFile);
                } catch (IOException ignored) {
                    // best effort — the file is in the system temp dir
                }
            }
        }
    }

    /**
     * Pipeline for pre-embedded projects: validate embedding compatibility, then swap.
     * Skips chunking entirely since the embeddings are pre-computed externally.
     * Same keep-old-index semantics as the standard pipeline.
     *
     * @return true when the run completed successfully (terminal status set centrally by
     * {@link #runPipeline}); false on a keep-old-index failure (terminal status set here).
     */
    private boolean runPreEmbeddedPipeline(String name, ProjectDefinition def) {
        boolean hadIndex = hasIndexedChunks(name);
        boolean swapStarted = false;
        try {
            String embeddingsPath = def.resolvedEmbeddingsFile();
            if (embeddingsPath == null) {
                throw new IllegalStateException("Pre-embedded project '" + name + "' has no resolved embeddingsFile");
            }

            // Compatibility check: compare imported vectors against locally computed ones
            // (no granular progress — the check samples a handful of vectors)
            registry.setReindexStatus(name, ReindexStatus.EMBEDDING);
            registry.setReindexProgress(name, 50);
            if (!hadIndex) {
                registry.setStatus(name, ProjectStatus.EMBEDDING, null);   // no old index -> not searchable anyway
            }
            List<EmbeddedChunkRecord> samples = readFirstN(embeddingsPath, hubProps.getCompatibilitySampleSize());
            if (samples.isEmpty()) {
                throw new IllegalStateException("No valid embedded chunk records found in " + embeddingsPath);
            }
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
                registry.setReindexStatus(name, ReindexStatus.FAILED);
                registry.setStatus(name, hadIndex ? ProjectStatus.READY : ProjectStatus.FAILED, msg);
                return false;   // do not proceed with import; signal failure to runPipeline (no finish dispatch)
            }

            if (report.verdict() == Verdict.PARTIALLY_COMPATIBLE) {
                log.warn("Pre-embedded import for project '{}' with PARTIAL compatibility (avg cosine={}). "
                                + "Cross-platform rounding differences detected — search quality may be slightly degraded.",
                        name, String.format("%.4f", report.avgCosine()));
            }

            // Swap: old index gone, pre-embedded vectors in (brief offline window); granular progress within the 80-100 band
            swapStarted = true;
            registry.setReindexStatus(name, ReindexStatus.IMPORTING);
            registry.setReindexProgress(name, EMBEDDING_END);
            if (hadIndex) {
                registry.setStatus(name, ProjectStatus.EMBEDDING, null);   // briefly non-searchable
            }
            luceneStore.removeAll(new IsEqualTo(PxChunk.PXCHUNK_PROJECT, name));
            importEmbeddedFile(name, def, embeddingsPath,
                    (done, total) -> registry.setReindexProgress(name, mapPhaseProgress(done, total, EMBEDDING_END, STORING_END)));

            // Done — the terminal READY/COMPLETE/100 status is set centrally by runPipeline.
            return true;
        } catch (Exception e) {
            log.error("Pipeline failed for project '{}': {}", name, e.toString());
            registry.setReindexStatus(name, ReindexStatus.FAILED);
            if (hadIndex && !swapStarted) {
                registry.setStatus(name, ProjectStatus.READY,
                        "Reindex failed (" + e.getMessage() + ") — old index still in use");
            } else {
                registry.setStatus(name, ProjectStatus.FAILED, String.valueOf(e.getMessage()));
            }
            return false;
        }
    }

    /**
     * Imports EmbeddedChunkRecord JSONL into the shared Lucene index (batched).
     * The caller performs the scoped reset (removeAll) before calling this. The progress
     * callback is invoked after each batch with (importedSoFar, totalLines).
     */
    private void importEmbeddedFile(String name, ProjectDefinition def, String jsonlPath, BiConsumer<Integer, Integer> progress) {
        // Count the scope in a first pass — a Stream can only be consumed once
        int total;
        try (Stream<String> counting = Files.lines(Path.of(jsonlPath))) {
            total = (int) counting.filter(line -> !line.isBlank()).count();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read embeddings file " + jsonlPath, e);
        }

        try (Stream<String> lines = Files.lines(Path.of(jsonlPath))) {
            List<EmbeddedChunkRecord> batch = new ArrayList<>();
            int batchSize = Math.max(1, def.getTibedBatchSize());
            AtomicInteger imported = new AtomicInteger();

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
                            imported.addAndGet(flushBatch(name, batch));
                            reportProgress(progress, imported.get(), total);
                            batch.clear();
                        }
                    });

            // Flush remaining
            if (!batch.isEmpty()) {
                imported.addAndGet(flushBatch(name, batch));
                reportProgress(progress, imported.get(), total);
            }

            log.info("Imported embedded chunks for project '{}' from {}", name, jsonlPath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read embeddings file " + jsonlPath, e);
        }
    }

    /** Invokes the progress callback, clamping done to total; no-op when the scope is unknown (total <= 0). */
    private void reportProgress(BiConsumer<Integer, Integer> progress, int done, int total) {
        if (total > 0) {
            progress.accept(Math.min(done, total), total);
        }
    }

    /** Maps a phase-local (done, total) pair onto the global 0-100 scale between phaseStart and phaseEnd. */
    private int mapPhaseProgress(int done, int total, int phaseStart, int phaseEnd) {
        if (total <= 0) {
            return phaseEnd;   // unknown or empty scope: jump to the phase end
        }
        return phaseStart + (int) ((long) (phaseEnd - phaseStart) * Math.min(done, total) / total);
    }

    /** Imports one batch; returns the number of chunks actually written to the store. */
    private int flushBatch(String projectName, List<EmbeddedChunkRecord> batch) {
        List<EmbeddedChunkRecord> toImport = batch.stream()
                .filter(this::needsImport)
                .toList();

        if (toImport.isEmpty()) {
            log.debug("Skipping batch of {} chunks (already in store)", batch.size());
            return 0;
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

        luceneStore.addAllBatched(embeddings, segments);
        log.info("Imported {}/{} chunks for project '{}'", toImport.size(), batch.size(), projectName);
        return toImport.size();
    }

    private boolean needsImport(EmbeddedChunkRecord record) {
        // The scoped reset (removeAll) already wiped the project's chunks, so every record is imported.
        return true;
    }

    private boolean hasVector(EmbeddedChunkRecord record) {
        return record.vector() != null && record.vector().length > 0;
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
