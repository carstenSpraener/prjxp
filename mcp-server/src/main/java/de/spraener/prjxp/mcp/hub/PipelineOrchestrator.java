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
        enqueue(name);
    }

    @Override
    public void onFailed(String name, String error) {
        if (registry.entry(name).isPresent()) {
            registry.setStatus(name, ProjectStatus.FAILED, error);
        } else {
            log.warn("Import failed for unregistered project '{}': {}", name, error);
        }
    }

    /** Queues the full pipeline (chunk → scoped reset + embed) for a registered project. */
    public void enqueue(String name) {
        worker.submit(() -> runPipeline(name));
    }

    private void runPipeline(String name) {
        ProjectDefinition def = registry.definitionOf(name);
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
        }
    }

    /** On startup: sync the registry with the projects root, then mark indexed projects READY or re-run them. */
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
