# Phase 06: API Backend Extensions

> **STRICT EXECUTION RULES FOR THE AGENT:**
> 1. DO NOT run repository scans.
> 2. Read ONLY the files explicitly listed in the "File Scope" section below.
> 3. All required interfaces/DTOs are provided inline in this document.
> 4. **MANDATORY FINAL STEP:** Execute `/compact` IMMEDIATELY after green tests (>= 80% coverage).

## 1. Target & Scope
* **Objective:** Extend the mcp-server with new REST endpoints for project CRUD, statistics, system health, and live project scanning.
* **Target Files to Create/Modify:**
    * Modify: `mcp-server/src/main/java/de/spraener/prjxp/mcp/ProjectInfo.java` - Add chunkCount, embeddingCount, fileCount, kind fields
    * Modify: `mcp-server/src/main/java/de/spraener/prjxp/mcp/hub/HubProjectRegistry.java` - Update projectInfos() to include stats
    * Modify: `mcp-server/src/main/java/de/spraener/prjxp/mcp/StaticProjectRegistry.java` - Update projectInfos() to include stats
    * Modify: `mcp-server/src/main/java/de/spraener/prjxp/mcp/hub/HubProjectsController.java` - Add new endpoints
    * Create: `mcp-server/src/main/java/de/spraener/prjxp/mcp/SystemHealthController.java` - System health & query stats endpoints
    * Create: `mcp-server/src/main/java/de/spraener/prjxp/mcp/ProjectStats.java` - DTO for project statistics
    * Create: `mcp-server/src/main/java/de/spraener/prjxp/mcp/SystemHealth.java` - DTO for system health
    * Create: `mcp-server/src/main/java/de/spraener/prjxp/mcp/QueryStats.java` - DTO for query statistics
    * Create: `mcp-server/src/main/java/de/spraener/prjxp/mcp/QueryStatEntry.java` - DTO for individual query stats
    * Create: `mcp-server/src/main/java/de/spraener/prjxp/mcp/LiveProjectCandidate.java` - DTO for live project candidates
    * Create: `mcp-server/src/main/java/de/spraener/prjxp/mcp/ProjectCreateRequest.java` - DTO for project creation
    * Create: `mcp-server/src/main/java/de/spraener/prjxp/mcp/ProjectUpdateRequest.java` - DTO for project updates
    * Create: `mcp-server/src/main/java/de/spraener/prjxp/mcp/hub/HubProjectsControllerTest.java` - Tests for new endpoints
    * Create: `mcp-server/src/main/java/de/spraener/prjxp/mcp/SystemHealthControllerTest.java` - Tests for system health endpoints
    * Modify: `lucene-store/src/main/java/de/spraener/prjxp/lucene/LuceneEmbeddingStore.java` - Add countByProject() method

## 2. Inline Required Context

### Existing Files to Read
| File | Purpose |
|------|---------|
| `mcp-server/src/main/java/de/spraener/prjxp/mcp/ProjectInfo.java` | Current DTO: `record ProjectInfo(String name, String status, String lastError)` |
| `mcp-server/src/main/java/de/spraener/prjxp/mcp/hub/HubProjectRegistry.java` | Registry with `projectInfos()` method (lines 373-378) |
| `mcp-server/src/main/java/de/spraener/prjxp/mcp/StaticProjectRegistry.java` | Non-hub registry implementation |
| `mcp-server/src/main/java/de/spraener/prjxp/mcp/hub/HubProjectsController.java` | Existing controller with DELETE, reindex, importEmbeddings endpoints |
| `lucene-store/src/main/java/de/spraener/prjxp/lucene/LuceneEmbeddingStore.java` | Has `count()` method (line 333), needs `countByProject(String)` |
| `prjxp-common/src/main/java/de/spraener/prjxp/common/config/ProjectDefinition.java` | Project config model with all YAML fields |
| `mcp-server/src/main/java/de/spraener/prjxp/mcp/hub/ProjectEntry.java` | Runtime entry with Kind enum (SNAPSHOT, LIVE) |
| `mcp-server/src/main/java/de/spraener/prjxp/mcp/hub/ProjectStatus.java` | Status enum: IMPORTING, CHUNKING, EMBEDDING, READY, FAILED |
| `mcp-server/src/main/java/de/spraener/prjxp/mcp/hub/ProjectConfigFileParser.java` | YAML parser (lines 53-60) |
| `mcp-server/src/main/java/de/spraener/prjxp/mcp/hub/ImportPoller.java` | Discovery logic (line 103: `luceneStore.hasMatch(...)`) |

### New DTOs (Java Records)

#### ProjectInfo (extended)
```java
// Replace existing record with extended version
public record ProjectInfo(
    String name,
    String status,       // IMPORTING|CHUNKING|EMBEDDING|READY|FAILED
    String kind,         // LIVE|SNAPSHOT (new)
    String lastError,
    Long chunkCount,     // new - from LuceneEmbeddingStore.countByProject()
    Long embeddingCount, // new - same as chunkCount for now (1:1 mapping)
    Integer fileCount    // new - distinct files in project
) {
    // Backward-compatible constructor for existing code
    public ProjectInfo(String name, String status, String lastError) {
        this(name, status, null, lastError, 0L, 0L, 0);
    }
}
```

#### ProjectStats (new)
```java
public record ProjectStats(
    String name,
    Long chunkCount,
    Long embeddingCount,
    Integer fileCount,
    String rootDir,
    List<String> languages  // chunoWhiteList split by comma
) {}
```

#### SystemHealth (new)
```java
public record SystemHealth(
    Double cpuUsage,       // 0.0-1.0
    Long totalMemory,      // bytes (Runtime.totalMemory())
    Long usedMemory,       // bytes (total - free)
    Long freeDiskSpace,    // bytes (project root's parent dir)
    Long totalDiskSpace,   // bytes
    Integer activeConnections  // TODO: track via Servlet filter
) {}
```

#### QueryStats (new)
```java
public record QueryStats(
    Long totalQueries,
    Double avgResponseTimeMs,
    Double p95ResponseTimeMs,
    Long cacheHits,
    Long cacheMisses,
    List<QueryStatEntry> recentQueries  // last 10 queries
) {}

public record QueryStatEntry(
    String query,
    Long responseTimeMs,
    Integer resultCount,
    Boolean fromCache
) {}
```

#### LiveProjectCandidate (new)
```java
public record LiveProjectCandidate(
    String name,
    String directory,
    Boolean hasConfigFile,   // prjxp.yaml/.yml exists?
    List<String> languages   // detected file extensions
) {}
```

#### ProjectCreateRequest (new)
```java
public record ProjectCreateRequest(
    String name,
    String kind,            // "LIVE" or "SNAPSHOT"
    @Nullable String rootDir,         // for LIVE: directory path; for SNAPSHOT: tar/zip path
    @Nullable String chunoWhiteList,  // default "java,ts"
    @Nullable Integer tibedBatchSize   // default 32
) {}
```

#### ProjectUpdateRequest (new)
```java
public record ProjectUpdateRequest(
    @Nullable String rootDir,
    @Nullable String chunoWhiteList,
    @Nullable Integer tibedBatchSize
) {}
```

## 3. Implementation Details

### 3.1 LuceneEmbeddingStore.countByProject() (NEW)
Add to `LuceneEmbeddingStore.java`:

```java
/**
 * Count embeddings for a specific project.
 */
public long countByProject(String projectName) {
    try (Searcher searcher = this.indexReaderManager.acquire()) {
        Query query = new TermQuery(new Term(PxChunk.PXCHUNK_PROJECT, projectName));
        return searcher.count(query);
    } catch (IOException e) {
        throw new UncheckedIOException("Failed to count embeddings for project " + projectName, e);
    }
}

/**
 * Count distinct files for a specific project.
 */
public int countFilesByProject(String projectName) {
    try (Searcher searcher = this.indexReaderManager.acquire()) {
        Query query = new TermQuery(new Term(PxChunk.PXCHUNK_PROJECT, projectName));
        TopDocs docs = searcher.search(query, 10000); // fetch all matching chunks
        Set<String> files = new HashSet<>();
        for (ScoreDoc doc : docs.scoreDocs) {
            Document d = searcher.doc(doc.doc);
            String file = d.get(PxChunk.PXCHUNK_FILE);
            if (file != null) files.add(file);
        }
        return files.size();
    } catch (IOException e) {
        throw new UncheckedIOException("Failed to count files for project " + projectName, e);
    }
}
```

### 3.2 HubProjectRegistry.projectInfos() (MODIFY)
Update the method at lines 373-378 to include stats:

```java
public List<ProjectInfo> projectInfos() {
    return entries.values().stream()
        .map(entry -> {
            long chunkCount = luceneStore.countByProject(entry.getName());
            int fileCount = luceneStore.countFilesByProject(entry.getName());
            return new ProjectInfo(
                entry.getName(),
                entry.getStatus().name(),
                entry.getKind().name(),
                entry.getLastError(),
                chunkCount,
                chunkCount, // embeddingCount == chunkCount (1:1)
                fileCount
            );
        })
        .toList();
}
```

### 3.3 HubProjectsController (MODIFY - add new endpoints)
Add to existing controller:

```java
@PostMapping("/prjxp/projects")
public ProjectInfo createProject(@RequestBody ProjectCreateRequest request) {
    // Validate kind (LIVE or SNAPSHOT)
    ProjectEntry.Kind kind = ProjectEntry.Kind.valueOf(request.kind());

    // Create ProjectDefinition from request
    ProjectDefinition definition = new ProjectDefinition();
    definition.setName(request.name());
    definition.setRootDir(request.rootDir() != null ? request.rootDir() : ".");
    definition.setChunoWhiteList(request.chunoWhiteList() != null ? request.chunoWhiteList() : "java,ts");
    definition.setTibedBatchSize(request.tibedBatchSize() != null ? request.tibedBatchSize() : 32);

    // Register with registry
    return projectRegistry.registerProject(definition, kind);
}

@PutMapping("/prjxp/projects/{name}")
public ProjectInfo updateProject(@PathVariable String name, @RequestBody ProjectUpdateRequest updates) {
    // Find existing project and update fields
    ProjectEntry entry = projectRegistry.getEntry(name)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found: " + name));

    ProjectDefinition def = entry.getDefinition();
    if (updates.rootDir() != null) def.setRootDir(updates.rootDir());
    if (updates.chunoWhiteList() != null) def.setChunoWhiteList(updates.chunoWhiteList());
    if (updates.tibedBatchSize() != null) def.setTibedBatchSize(updates.tibedBatchSize());

    // Re-index if rootDir changed
    if (updates.rootDir() != null && !updates.rootDir().equals(entry.getDefinition().getRootDir())) {
        lifecycleService.reindex(name);
    }

    return new ProjectInfo(
        name, entry.getStatus().name(), entry.getKind().name(),
        entry.getLastError(), 0L, 0L, 0 // stats will be updated on next poll
    );
}

@PostMapping("/prjxp/projects/upload")
public ProjectInfo uploadArchive(@RequestParam("file") MultipartFile file) {
    // Save to import directory, trigger discovery
    Path target = properties.getImportDir().resolve(file.getOriginalFilename());
    try {
        file.transferTo(target.toFile());
    } catch (IOException e) {
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to upload file", e);
    }

    // Trigger immediate scan
    importPoller.scan();

    // Return placeholder - actual project will be discovered by ImportPoller
    return new ProjectInfo(file.getOriginalFilename().replaceFirst("\\.[^.]+$", ""), "IMPORTING", null, null, 0L, 0L, 0);
}

@GetMapping("/prjxp/live-scan")
public List<LiveProjectCandidate> scanLiveProjects() {
    // Scan import directory for directories with source files
    Path importDir = properties.getImportDir();
    List<LiveProjectCandidate> candidates = new ArrayList<>();

    try (Stream<Path> stream = Files.list(importDir)) {
        stream.filter(Files::isDirectory)
              .filter(path -> !path.getFileName().toString().startsWith("."))
              .forEach(dir -> {
                  String name = dir.getFileName().toString();
                  boolean hasConfig = Files.exists(dir.resolve("prjxp.yaml")) ||
                                     Files.exists(dir.resolve("prjxp.yml"));

                  // Detect languages from file extensions
                  List<String> languages = detectLanguages(dir);

                  candidates.add(new LiveProjectCandidate(name, dir.toString(), hasConfig, languages));
              });
    } catch (IOException e) {
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to scan directories", e);
    }

    return candidates;
}

@GetMapping("/prjxp/projects/{name}/stats")
public ProjectStats getProjectStats(@PathVariable String name) {
    ProjectEntry entry = projectRegistry.getEntry(name)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found: " + name));

    long chunkCount = luceneStore.countByProject(name);
    int fileCount = luceneStore.countFilesByProject(name);

    return new ProjectStats(
        name,
        chunkCount,
        chunkCount, // embeddingCount == chunkCount
        fileCount,
        entry.getDefinition().getRootDir(),
        Arrays.asList(entry.getDefinition().getChunoWhiteList().split(","))
    );
}

private List<String> detectLanguages(Path dir) {
    Set<String> extensions = new HashSet<>();
    try (Stream<Path> stream = Files.walk(dir).filter(Files::isRegularFile)) {
        stream.map(p -> {
            String name = p.getFileName().toString();
            int dotIdx = name.lastIndexOf('.');
            return dotIdx > 0 ? name.substring(dotIdx + 1) : null;
        })
        .filter(Objects::nonNull)
        .forEach(extensions::add);
    } catch (IOException ignored) {}
    return new ArrayList<>(extensions);
}
```

### 3.4 SystemHealthController (NEW)
New controller for system health and query stats:

```java
@RestController
@RequestMapping("/prjxp")
@ConditionalOnProperty(name = "prjxp.hub.enabled", havingValue = "true")
@RequiredArgsConstructor
public class SystemHealthController {

    private final QueryStatsCollector queryStatsCollector; // NEW bean, see below

    @GetMapping("/system/health")
    public SystemHealth getSystemHealth() {
        Runtime runtime = Runtime.getRuntime();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();

        // Get disk space for project root's parent
        File rootDir = new File(System.getProperty("user.dir"));
        long freeDiskSpace = rootDir.getFreeSpace();
        long totalDiskSpace = rootDir.getTotalSpace();

        // CPU usage (approximate via available processors)
        double cpuUsage = Math.min(1.0, (double)(Runtime.getRuntime().availableProcessors() - getIdleCores()) / Runtime.getRuntime().availableProcessors());

        return new SystemHealth(
            cpuUsage,
            totalMemory,
            totalMemory - freeMemory,
            freeDiskSpace,
            totalDiskSpace,
            0 // TODO: track active connections via Servlet filter
        );
    }

    @GetMapping("/system/query-stats")
    public QueryStats getQueryStats() {
        return queryStatsCollector.getStats();
    }

    // Placeholder - implement with OS-specific library or omit
    private int getIdleCores() { return 0; }
}

// QueryStatsCollector bean (new)
@Component
public class QueryStatsCollector {
    private final List<QueryStatEntry> recentQueries = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong totalQueries = new AtomicLong(0);
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    private final ConcurrentLinkedQueue<Long> responseTimes = new ConcurrentLinkedQueue<>();

    public void recordQuery(String query, long responseTimeMs, int resultCount, boolean fromCache) {
        totalQueries.incrementAndGet();
        if (fromCache) cacheHits.incrementAndGet(); else cacheMisses.incrementAndGet();

        responseTimes.add(responseTimeMs);
        if (responseTimes.size() > 1000) responseTimes.poll(); // keep last 1000

        recentQueries.add(0, new QueryStatEntry(query, responseTimeMs, resultCount, fromCache));
        if (recentQueries.size() > 10) recentQueries.remove(recentQueries.size() - 1); // keep last 10
    }

    public QueryStats getStats() {
        List<Long> times = new ArrayList<>(responseTimes);
        double avg = times.stream().mapToLong(Long::longValue).average().orElse(0);
        double p95 = times.isEmpty() ? 0 : times.stream().sorted().skip((long)(times.size() * 0.95)).findFirst().orElse(0L);

        return new QueryStats(
            totalQueries.get(), avg, p95,
            cacheHits.get(), cacheMisses.get(),
            new ArrayList<>(recentQueries)
        );
    }
}
```

## 4. Tests (TDD)

### HubProjectsController Tests
- `createProject()` should register project with correct definition and kind
- `updateProject()` should update existing project's fields
- `uploadArchive()` should save file to import directory and trigger scan
- `scanLiveProjects()` should return candidates for directories in import dir
- `getProjectStats()` should return stats with correct chunk/file counts

### SystemHealthController Tests
- `getSystemHealth()` should return non-negative values for all fields
- `getQueryStats()` should return stats from QueryStatsCollector

### LuceneEmbeddingStore Tests (extended)
- `countByProject()` should return correct count for existing project
- `countFilesByProject()` should return distinct file count

### QueryStatsCollector Tests
- `recordQuery()` should add entry to recentQueries (max 10)
- `getStats()` should calculate correct avg and p95 response times
- Cache hit/miss counters should increment correctly

## 5. Acceptance Criteria
- [ ] `GET /prjxp/tools/projects` returns extended ProjectInfo with stats
- [ ] `POST /prjxp/projects` creates new project from request body
- [ ] `PUT /prjxp/projects/{name}` updates project configuration
- [ ] `POST /prjxp/projects/upload` accepts TAR/ZIP file upload
- [ ] `GET /prjxp/live-scan` returns live project candidates
- [ ] `GET /prjxp/projects/{name}/stats` returns detailed statistics
- [ ] `GET /prjxp/system/health` returns system health metrics
- [ ] `GET /prjxp/system/query-stats` returns query performance stats
- [ ] All tests pass with >= 80% coverage
