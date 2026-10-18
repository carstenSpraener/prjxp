package de.spraener.prjxp.mcp.hub;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.spraener.prjxp.common.config.ProjectDefinition;
import de.spraener.prjxp.common.model.EmbeddedChunkRecord;
import de.spraener.prjxp.common.model.PxChunk;
import de.spraener.prjxp.lucene.LuceneEmbeddingStore;
import de.spraener.prjxp.mcp.LiveProjectCandidate;
import de.spraener.prjxp.mcp.ProjectCreateRequest;
import de.spraener.prjxp.mcp.ProjectInfo;
import de.spraener.prjxp.mcp.ProjectRegistry;
import de.spraener.prjxp.mcp.ProjectStats;
import de.spraener.prjxp.mcp.ProjectUpdateRequest;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * REST endpoints for hub project management (lifecycle overview, deletion and reindex).
 * Active only in hub mode; the registry dependency resolves to {@link HubProjectRegistry}.
 */
@RestController
@RequestMapping("/api/projects")
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
    private final ProjectConfigFileParser configParser;
    private final EmbeddedArchiveImporter embeddedImporter;

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

    /** Returns the current reindex status and progress percentage for a project. */
    @GetMapping("/{name}/reindex-status")
    public ResponseEntity<Map<String, Object>> getReindexStatus(@PathVariable("name") String name) {
        ReindexStatus status = hubRegistry.getReindexStatus(name);
        int progress = hubRegistry.getReindexProgress(name);

        Map<String, Object> result = new HashMap<>();
        result.put("status", status.name());
        result.put("progress", progress);
        return ResponseEntity.ok(result);
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

    /** Creates a new project from request body. */
    @PostMapping
    public ProjectInfo createProject(@RequestBody ProjectCreateRequest request) {
        ProjectEntry.Kind kind = ProjectEntry.Kind.valueOf(request.kind());

        // Create a temporary directory for the project in hub's projects root
        Path newDir = Path.of(hubProps.getProjectsRoot()).resolve(request.name());
        try {
            Files.createDirectories(newDir);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create project directory", e);
        }

        // Register the project
        hubRegistry.registerProject(request.name(), newDir, kind);

        // If rootDir is provided, update the definition
        if (request.rootDir() != null) {
            ProjectDefinition def = hubRegistry.definitionOf(request.name());
            if (def != null) {
                def.setRootDir(request.rootDir());
                if (request.chunoWhiteList() != null) def.setChunoWhiteList(request.chunoWhiteList());
                if (request.tibedBatchSize() != null) def.setTibedBatchSize(request.tibedBatchSize());
                if (request.fulltext() != null) def.setFulltext(request.fulltext());
            }
        }

        // Persist project configuration to YAML file
        ProjectDefinition newDef = hubRegistry.definitionOf(request.name());
        if (newDef != null) {
            configParser.save(newDir, newDef);
        }

        // Trigger pipeline
        orchestrator.enqueue(request.name());

        return new ProjectInfo(request.name(), "IMPORTING", kind.name(), null, 0L, 0L, 0,
            null, null, null, null, null, false, "IDLE", 0, null, null);
    }

    /** Updates an existing project's configuration. */
    @PutMapping("/{name}")
    public ProjectInfo updateProject(@PathVariable("name") String name, @RequestBody ProjectUpdateRequest updates) {
        ProjectEntry entry = hubRegistry.entry(name)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found: " + name));

        ProjectDefinition def = entry.getDefinition();
        String oldRootDir = def.getRootDir();

        if (updates.rootDir() != null) def.setRootDir(updates.rootDir());
        if (updates.chunoWhiteList() != null) def.setChunoWhiteList(updates.chunoWhiteList());
        if (updates.tibedBatchSize() != null) def.setTibedBatchSize(updates.tibedBatchSize());
        if (updates.prompt() != null) def.setPrompt(updates.prompt().isBlank() ? null : updates.prompt());
        // fulltext takes effect on the next reindex — deliberately NO orchestrator.enqueue here
        if (updates.fulltext() != null) def.setFulltext(updates.fulltext());

        // Persist changes to YAML file
        configParser.save(entry.getRootDir(), def);

        // Reload definition from disk to ensure in-memory state matches persisted state
        hubRegistry.reloadDefinition(name);

        // Re-index if rootDir changed
        if (updates.rootDir() != null && !updates.rootDir().equals(oldRootDir)) {
            orchestrator.enqueue(name);
        }

        return new ProjectInfo(
            name, entry.getStatus().name(), entry.getKind().name(),
            entry.getLastError(), 0L, 0L, 0,
            def.getRootDir(), def.getJsonlFile(), def.getChunoWhiteList(),
            def.getTibedBatchSize(), def.getEmbeddingsFile(), def.isTibedResetStore(),
            hubRegistry.getReindexStatus(name).name(),
            hubRegistry.getReindexProgress(name),
            def.getPrompt(),
            def.isFulltext()
        );
    }

    /** Uploads a TAR/ZIP archive for import. */
    @PostMapping("/upload-archive")
    public ProjectInfo uploadArchive(@RequestParam("file") MultipartFile file) {
        String fileName = file.getOriginalFilename();
        Path target = Path.of(hubProps.getImportDir()).resolve(fileName);
        try {
            Files.createDirectories(target.getParent());
            file.transferTo(target.toFile());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to upload file", e);
        }

        // Trigger immediate scan for new projects
        // (ImportPoller will discover it on next cycle)

        String projectName = fileName.replaceFirst("\\.[^.]+$", "");
        return new ProjectInfo(projectName, "IMPORTING", null, null, 0L, 0L, 0,
            null, null, null, null, null, false, "IDLE", 0, null, null);
    }

    /** Uploads a pre-embedded archive (JSONL, GZ, TGZ, ZIP) for import. */
    @PostMapping("/upload-embedded")
    public ResponseEntity<ProjectInfo> uploadEmbedded(@RequestParam("file") MultipartFile file) {
        String fileName = sanitizeFileName(file.getOriginalFilename());

        // The importer derives the project name from the file name, so the original
        // name must be preserved — a random temp-file prefix would corrupt it.
        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("embedded-upload-");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create temp storage", e);
        }
        Path tempFile = tempDir.resolve(fileName);

        try {
            file.transferTo(tempFile.toFile());
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tempDir);
            } catch (IOException ignored) {
            }
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to upload file", e);
        }

        try {
            // Validate first for immediate 400 feedback
            embeddedImporter.validate(tempFile);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid embedded archive: " + e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Failed to validate embedded archive: " + e.getMessage());
        }

        try {
            ProjectInfo result = embeddedImporter.process(tempFile);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to process embedded archive {}: {}", fileName, e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to process embedded archive: " + e.getMessage());
        } finally {
            try {
                Files.deleteIfExists(tempFile);
                Files.deleteIfExists(tempDir);
            } catch (IOException ignored) {
            }
        }
    }

    /** Keeps only the last path segment of a browser-supplied file name (rejects traversal). */
    private String sanitizeFileName(String original) {
        if (original == null || original.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File name is missing");
        }
        String base = original.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        if (base.isBlank() || base.equals(".") || base.equals("..")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid file name");
        }
        return base;
    }

    /** Scans import directory for live project candidates. */
    @GetMapping("/live-scan")
    public List<LiveProjectCandidate> scanLiveProjects() {
        Path importDir = Path.of(hubProps.getImportDir());
        List<LiveProjectCandidate> candidates = new ArrayList<>();

        if (!Files.isDirectory(importDir)) {
            return candidates; // Return empty if import dir doesn't exist
        }

        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(importDir)) {
            for (Path entry : stream) {
                if (!Files.isDirectory(entry) || entry.getFileName().toString().startsWith(".")) {
                    continue;
                }

                String name = entry.getFileName().toString();
                boolean hasConfig = Files.exists(entry.resolve("prjxp.yaml")) ||
                                   Files.exists(entry.resolve("prjxp.yml"));

                List<String> languages = detectLanguages(entry);
                candidates.add(new LiveProjectCandidate(name, entry.toString(), hasConfig, languages));
            }
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to scan directories", e);
        }

        return candidates;
    }

    /** Returns detailed statistics for a specific project. */
    @GetMapping("/{name}/stats")
    public ProjectStats getProjectStats(@PathVariable("name") String name) {
        ProjectEntry entry = hubRegistry.entry(name)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found: " + name));

        long chunkCount = luceneStore.countByProject(name);
        int fileCount = luceneStore.countFilesByProject(name);

        ProjectDefinition def = entry.getDefinition();
        String chunoWhiteList = def.getChunoWhiteList() != null ? def.getChunoWhiteList() : "java,ts";

        return new ProjectStats(
            name,
            chunkCount,
            chunkCount, // embeddingCount == chunkCount
            fileCount,
            def.getRootDir(),
            Arrays.asList(chunoWhiteList.split(","))
        );
    }

    /** Detects programming languages from file extensions in a directory. */
    private List<String> detectLanguages(Path dir) {
        Set<String> extensions = new HashSet<>();
        try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                  .map(p -> {
                      String name = p.getFileName().toString();
                      int dotIdx = name.lastIndexOf('.');
                      return dotIdx > 0 ? name.substring(dotIdx + 1) : null;
                  })
                  .filter(Objects::nonNull)
                  .forEach(extensions::add);
        } catch (IOException ignored) {
            // Return what we have so far
        }
        return new ArrayList<>(extensions);
    }
}
