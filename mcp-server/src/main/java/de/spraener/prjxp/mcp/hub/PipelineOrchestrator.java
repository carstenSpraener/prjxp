package de.spraener.prjxp.mcp.hub;

import de.spraener.prjxp.chuno.ChunkProcess;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import de.spraener.prjxp.tibed.EmbeddingService;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private final EmbeddingService embeddingService;  // Phase 01
    private final LuceneEmbeddingStore luceneStore;   // shared bean

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
        worker.shutdownNow();
    }

    @Override
    public void onImported(String name, Path projectDir) {
        if (registry.entry(name).isPresent()) {
            registry.unregisterProject(name);   // re-import: drop stale DAO/entry first
        }
        registry.registerProject(name, projectDir);   // status IMPORTING
        enqueueForced(name);   // a re-import must run even if the previous pipeline for this name is still in flight
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
            wipeIfDeregistered(name);   // deregistered between enqueue and start — nothing was written, but be safe
            return;
        }
        try {
            registry.setStatus(name, ProjectStatus.CHUNKING, null);
            chunkProcess.executeForProject(def);
            registry.setStatus(name, ProjectStatus.EMBEDDING, null);
            def.setTibedResetStore(true);   // import = source of truth: scoped reset + re-embed
            embeddingService.executeForProject(def, luceneStore);
            registry.setStatus(name, ProjectStatus.READY, null);
        } catch (Exception e) {
            log.error("Pipeline failed for project '{}': {}", name, e.toString());
            registry.setStatus(name, ProjectStatus.FAILED, String.valueOf(e.getMessage()));
        } finally {
            wipeIfDeregistered(name);   // race guard: marker removed mid-run -> the entry is gone, wipe what we wrote
        }
    }

    private void wipeIfDeregistered(String name) {
        if (registry.entry(name).isEmpty()) {
            log.warn("Project '{}' was deregistered mid-pipeline — wiping its chunks from the shared index", name);
            luceneStore.removeAll(new IsEqualTo(PxChunk.PXCHUNK_PROJECT, name));   // scoped (shared index!)
        }
    }

    /** On startup: sync the registry with both roots (snapshots + live), then mark indexed projects READY or re-run them. */
    @EventListener(ApplicationReadyEvent.class)
    public void selfHeal() {
        for (String name : registry.discoverProjects()) {
            if (luceneStore.hasMatch(new IsEqualTo(PxChunk.PXCHUNK_PROJECT, name))) {
                registry.setStatus(name, ProjectStatus.READY, null);   // index already has this project's chunks
            } else {
                enqueue(name);   // nothing/partial in index -> full pipeline
            }
        }
    }
}
