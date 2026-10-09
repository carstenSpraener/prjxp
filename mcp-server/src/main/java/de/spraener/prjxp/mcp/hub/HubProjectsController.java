package de.spraener.prjxp.mcp.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.model.EmbeddedChunkRecord;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import de.spraener.prjxp.mcp.ProjectInfo;
import de.spraener.prjxp.mcp.ProjectRegistry;
import de.spraener.prjxp.mcp.UnknownProjectException;
import de.spraener.prjxp.tibed.EmbeddingCompatibilityChecker;
import de.spraener.prjxp.tibed.PxChunk2TextSegmentConverter;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * REST endpoints for hub project management (lifecycle overview, deletion and reindex).
 * Active only in hub mode; the registry dependency resolves to {@link HubProjectRegistry}.
 */
@RestController
@RequestMapping("/prjxp/projects")
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
@RequiredArgsConstructor
public class HubProjectsController {

    private static final Logger log = LoggerFactory.getLogger(HubProjectsController.class);

    private final ProjectRegistry projectRegistry;   // interface — resolves to HubProjectRegistry in hub mode
    private final ProjectLifecycleService lifecycle;
    private final PipelineOrchestrator orchestrator;
    private final HubProjectRegistry hubRegistry;   // concrete type for definitionOf() access
    private final EmbeddingCompatibilityChecker compatibilityChecker;
    private final EmbeddingModel embeddingModel;
    private final LuceneEmbeddingStore luceneStore;
    private final HubProperties hubProps;
    private final ObjectMapper objMapper;

    @GetMapping
    public List<ProjectInfo> list() {
        return projectRegistry.projectInfos();
    }

    /**
     * Deletes a project from the hub. SNAPSHOT: scoped index wipe + recursive removal of the (hub-managed)
     * project directory. LIVE: scoped index wipe + unregistration only — the source tree belongs to the user
     * and is never touched. Note: for LIVE projects this is temporary by design — the marker file survives,
     * so the import poller re-discovers and re-enqueues the project on its next cycle. Permanent removal of
     * a live project = delete/rename its prjxp.yaml (or move it out of the import tree).
     */
    @DeleteMapping("/{name}")
    public ResponseEntity<Void> delete(@PathVariable("name") String name) {
        lifecycle.delete(name);
        return ResponseEntity.noContent().build();
    }

    /** Full pipeline re-run (chunk → scoped reset + embed) for a known project; the status walks READY/FAILED → CHUNKING → … */
    @PostMapping("/{name}/reindex")
    public ResponseEntity<Void> reindex(@PathVariable("name") String name) {
        if ("UNKNOWN".equals(projectRegistry.statusOf(name))) {
            throw new UnknownProjectException(name, projectRegistry.availableProjects());   // same error behavior as DELETE
        }
        orchestrator.enqueue(name);
        return ResponseEntity.accepted().build();   // the pipeline runs asynchronously on the single worker
    }

    /**
     * Imports pre-computed embeddings for a known project. Accepts a JSONL file containing {@link EmbeddedChunkRecord}
     * objects, validates embedding compatibility against the local model and imports into the shared Lucene index.
     * The project's existing chunks are wiped before import (scoped reset).
     */
    @PostMapping("/{name}/importEmbeddings")
    public ResponseEntity<String> importEmbeddings(
            @PathVariable("name") String name,
            @RequestParam("file") MultipartFile file) {

        if ("UNKNOWN".equals(projectRegistry.statusOf(name))) {
            throw new UnknownProjectException(name, projectRegistry.availableProjects());
        }

        ProjectDefinition def = hubRegistry.definitionOf(name);
        if (def == null) {
            return ResponseEntity.badRequest().body("Project '" + name + "' has no definition");
        }

        // Parse all records from the uploaded JSONL file
        List<EmbeddedChunkRecord> allRecords = parseJsonlFile(file);
        if (allRecords.isEmpty()) {
            return ResponseEntity.badRequest().body("No valid embedded chunk records found in uploaded file");
        }

        // Compatibility check: compare imported vectors against locally computed ones
        EmbeddingCompatibilityChecker.CheckReport report = compatibilityChecker.check(
                allRecords, embeddingModel,
                hubProps.getCompatibilitySampleSize(),
                hubProps.getCompatibilityThresholdHigh(),
                hubProps.getCompatibilityThresholdLow()
        );

        if (report.verdict() == EmbeddingCompatibilityChecker.Verdict.INCOMPATIBLE) {
            String msg = "Embedding model incompatible: avg cosine=" + String.format("%.4f", report.avgCosine())
                    + " (threshold: " + hubProps.getCompatibilityThresholdLow() + "). "
                    + "The uploaded embeddings were likely generated with a different model or incompatible hardware.";
            log.error("Embedding import blocked for project '{}': {}", name, msg);
            return ResponseEntity.badRequest().body(msg);
        }

        if (report.verdict() == EmbeddingCompatibilityChecker.Verdict.PARTIALLY_COMPATIBLE) {
            log.warn("Embedding import for project '{}' with PARTIAL compatibility (avg cosine={:.4f})",
                    name, report.avgCosine());
        }

        // Scoped reset: wipe existing chunks for this project
        Filter resetFilter = new IsEqualTo(PxChunk.PXCHUNK_PROJECT, name);
        luceneStore.removeAll(resetFilter);

        // Import in batches
        int batchSize = Math.max(1, def.getTibedBatchSize());
        List<EmbeddedChunkRecord> batch = new ArrayList<>();
        int importedCount = 0;

        for (EmbeddedChunkRecord record : allRecords) {
            if (record.vector() == null || record.vector().length == 0) {
                continue;   // skip records without vectors
            }
            batch.add(record);
            if (batch.size() >= batchSize) {
                importedCount += flushBatch(name, batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            importedCount += flushBatch(name, batch);
        }

        log.info("Imported {} pre-embedded chunks for project '{}'", importedCount, name);
        return ResponseEntity.ok("Imported " + importedCount + "/" + allRecords.size() + " chunks for project '" + name + "'");
    }

    private List<EmbeddedChunkRecord> parseJsonlFile(MultipartFile file) {
        List<EmbeddedChunkRecord> records = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    EmbeddedChunkRecord record = objMapper.readValue(line, EmbeddedChunkRecord.class);
                    records.add(record);
                } catch (Exception e) {
                    log.warn("Skipping unparseable line: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("Failed to read uploaded file: {}", e.getMessage());
        }
        return records;
    }

    private int flushBatch(String projectName, List<EmbeddedChunkRecord> batch) {
        List<Embedding> embeddings = new ArrayList<>();
        List<TextSegment> segments = new ArrayList<>();

        for (EmbeddedChunkRecord record : batch) {
            embeddings.add(Embedding.from(record.vector()));
            PxChunk chunk = record.toPxChunk();
            chunk.setProject(projectName);   // stamp (multi-project store separation)
            segments.add(PxChunk2TextSegmentConverter.convert(chunk));
        }

        luceneStore.addAll(embeddings, segments);
        return batch.size();
    }
}
