# Phase 05: mcp-server — readFile (Registry + ReaderService + MCP Tool)

> **STRICT EXECUTION RULES FOR THE AGENT:**
> 1. DO NOT run repository scans (`find_files`, `grep`, `search_code`, `list_dir`).
> 2. Read ONLY the files explicitly listed in the "File Scope" section below.
> 3. All required interfaces/DTOs are provided inline in this document. Do not fetch them from the codebase.
> 4. **MANDATORY FINAL STEP:** As soon as the implementation and tests (>= 80% coverage) are green, execute `/compact` IMMEDIATELY to clean up the context window before returning control.

## 1. Target & Scope

* **Objective:** The `readFile` MCP tool: lenient path resolution against the index, dispatch to the per-language reader (auto-detected mime), line offset/limit, output cap. **No REST controller.**
* **Target Files:**
    * Create: `mcp-server/src/main/java/de/spraener/prjxp/mcp/FileViewRegistry.java`
    * Create: `mcp-server/src/main/java/de/spraener/prjxp/mcp/ReaderService.java`
    * Create: `mcp-server/src/test/java/de/spraener/prjxp/mcp/FileViewRegistryTest.java`
    * Create: `mcp-server/src/test/java/de/spraener/prjxp/mcp/ReaderServiceTest.java`
    * Create: `mcp-server/src/test/java/de/spraener/prjxp/mcp/PrjxpMcpToolTest.java`
    * Modify: `mcp-server/src/main/java/de/spraener/prjxp/mcp/PrjxpMcpTool.java` (ONLY: one new constructor field, one new `@McpTool` method, two description text blocks — see §3.4)
    * Modify: `prjxp-common/src/main/java/de/spraener/prjxp/common/config/PrjXPConfig.java` (ONLY: one new field — see §3.3)

## 2. Inline Required Context (Contracts & Signatures)

```java
// Phase 01 types (prjxp-common):
public record FileView(String file, String language, int chunkCount, String content, boolean truncated,
        Integer totalLines, Integer returnedLines, List<String> candidates, String error) {
    static FileView error(String requestedFile, String message);
    static FileView ambiguous(String requestedFile, List<String> candidates);
}
public interface FileViewProvider { String mimeType(); String language(); String render(List<PxChunk> units); }
public class GenericFileViewProvider implements FileViewProvider { /* mimeType "*/*", language "generic" */ }
public final class ReaderSupport {
    static List<PxChunk> combineUnits(List<PxChunk> rawChunks); // group by id, sort by part, PxChunk.combine, sort by fromLine
    static String normalizePath(String path);   // trim, backslash->slash, strip leading+trailing slash; null->null
    static int parseLine(String value);
}

// Existing contracts:
// de.spraener.prjxp.common.store.PxChunkDao (interface):
//   List<ScoredChunk> searchByIndex(Map<String,String> filters, int limit);
//     // Lucene impl: one exact TermQuery per entry, FILTER-clause, AND-ed; limit passed to TopDocs; UNcapped.
//     // keys go through PxChunk.metadataFieldKey (e.g. "pxchunk_file" stays, "symbol_fqn" -> "pxchunk_metadata.symbol_fqn")
//     // EMPTY map -> MatchAllDocsQuery.
//   record ScoredChunk(PxChunk chunk, double score)
// de.spraener.prjxp.common.store.PxChunkDaoProvider:
//   Optional<PxChunkDao> get(String prjName);   // "default" -> the default store
// de.spraener.prjxp.common.config.PrjXPConfig:
//   Optional<ProjectDefinition> getActiveProject();   // ProjectDefinition.getName() -> String
//   // existing fields incl. private String activeProject = "cwd";  (@Data -> getters/setters via Lombok)
// PxChunk constants: PXCHUNK_FILE = "pxchunk_file";  getters: getFile/getMimeType (Strings)

// Existing class to modify — de.spraener.prjxp.mcp.PrjxpMcpTool:
@Component @RequiredArgsConstructor @Log
public class PrjxpMcpTool {
    private final GRPromptEnrichment enrichment;
    private final PrjXPConfig cfg;
    private final GrepSearchService grepSearchService;
    @McpTool(name = "vectorSearch", description = """ ... """)  public String vectorSearch(String userQuestion, String projectName, Double similarity, Integer maxResults, Boolean skeletonsOnly) { ... }
    @McpTool(name = "grep", description = """ ... """)          public List<SearchHit> grep(String query, String project, String language, Integer limit) { ... }
}
// imports used there: org.springaicommunity.mcp.annotation.McpTool / McpToolParam; org.apache.commons.lang3.StringUtils;
// existing constant in this package: SearchLimits { DEFAULT_LIMIT = 10; MAX_LIMIT = 100; static int clamp(int) }
```

## 3. Implementation (exact)

### 3.1 `FileViewRegistry`

```java
@Service
public class FileViewRegistry {
    private final Map<String, FileViewProvider> byMime = new LinkedHashMap<>();
    private final GenericFileViewProvider generic = new GenericFileViewProvider();

    public FileViewRegistry(List<FileViewProvider> providers) {
        for (FileViewProvider p : providers)
            if (p.mimeType() != null && !"*/*".equals(p.mimeType())) byMime.put(p.mimeType(), p);
    }
    /** Never empty: falls back to the language-agnostic generic reader. */
    public FileViewProvider forMime(String mime) {
        return byMime.getOrDefault(mime, generic);
    }
}
```

### 3.2 `ReaderService`

```java
@Service
@RequiredArgsConstructor
public class ReaderService {
    private static final int FILE_LOOKUP_LIMIT = 10_000;

    private final PxChunkDaoProvider chunkDaoProvider;
    private final PrjXPConfig cfg;
    private final FileViewRegistry fileViewRegistry;

    public FileView read(String file, String project, Integer offset, Integer limit) {
        String requested = ReaderSupport.normalizePath(file);
        PxChunkDao dao = resolveDao(project);
        if (dao == null)
            return FileView.error(file, "No embedding store available for project '" + project + "'.");

        List<PxChunk> fileChunks;
        try {
            fileChunks = locateFile(dao, requested, file);
        } catch (UnsupportedOperationException uoe) {
            return FileView.error(file, "Index search is not supported by this embedding store — readFile requires a Lucene index.");
        }
        if (fileChunks == null) return null;   // ambiguous -> see locateFile (returns FileView, see below)

        String mime = fileChunks.stream().map(PxChunk::getMimeType)
                .filter(Objects::nonNull).filter(s -> !s.isBlank()).findFirst().orElse("*/*");
        FileViewProvider provider = fileViewRegistry.forMime(mime);
        String content = provider.render(ReaderSupport.combineUnits(fileChunks));

        List<String> lines = content.lines().toList();
        int off = (offset == null || offset < 1) ? 1 : offset;
        if (off > lines.size()) return new FileView(requested, provider.language(), fileChunks.size(), "", false, lines.size(), 0, null, null);
        int to = (limit == null || limit < 1) ? lines.size() : Math.min(lines.size(), off - 1 + limit);
        String text = String.join("\n", lines.subList(off - 1, to));

        int max = cfg.getReaderMaxOutputChars();
        boolean truncated = false;
        if (max > 0 && text.length() > max) { text = text.substring(0, max); truncated = true; }

        return new FileView(requested, provider.language(), fileChunks.size(), text, truncated,
                lines.size(), to - off + 1, null, null);
    }

    private PxChunkDao resolveDao(String project) {   // same pattern as ByIndexSearchService
        String resolved = (project == null || project.isBlank() || "default".equalsIgnoreCase(project))
                ? cfg.getActiveProject().map(ProjectDefinition::getName).orElse("default") : project;
        Optional<PxChunkDao> opt = chunkDaoProvider.get(resolved);
        if (opt.isEmpty() && !"default".equalsIgnoreCase(resolved)) opt = chunkDaoProvider.get("default");
        return opt.orElse(null);
    }

    private Object locateFile(PxChunkDao dao, String requested, String rawRequested) {
        // 1. exact terms: try requested and "/" + requested (index stores rootDir-relative paths WITH leading slash)
        for (String term : List.of(requested, "/" + requested).stream().distinct().toList()) {
            List<ScoredChunk> hits = dao.searchByIndex(Map.of(PxChunk.PXCHUNK_FILE, term), FILE_LOOKUP_LIMIT);
            if (!hits.isEmpty()) {
                Set<String> files = hits.stream().map(h -> ReaderSupport.normalizePath(h.chunk().getFile()))
                        .filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new));
                if (files.size() == 1) return hits.stream().map(ScoredChunk::chunk).toList();
                if (files.size() > 1)  return FileView.ambiguous(rawRequested, List.copyOf(files));
            }
        }
        // 2. MatchAll fallback: distinct file values, then suffix / basename match in memory
        Set<String> allFiles = new LinkedHashSet<>();
        Map<String, List<PxChunk>> byFile = new HashMap<>();
        for (ScoredChunk h : dao.searchByIndex(Map.of(), FILE_LOOKUP_LIMIT)) {
            String n = ReaderSupport.normalizePath(h.chunk().getFile());
            if (n == null) continue;
            allFiles.add(n);
            byFile.computeIfAbsent(n, k -> new ArrayList<>()).add(h.chunk());
        }
        List<String> matches = allFiles.stream()
                .filter(f -> f.equals(requested) || f.endsWith("/" + requested)
                             || f.substring(f.lastIndexOf('/') + 1).equals(requested.substring(requested.lastIndexOf('/') + 1)))
                .toList();
        if (matches.size() > 1)  return FileView.ambiguous(rawRequested, matches);
        if (matches.size() == 0) return FileView.error(rawRequested, "No file matching '" + requested + "' in the index.");
        return byFile.get(matches.get(0));
    }
}
```
Implementation notes: give `locateFile` return type `Optional<FileView>` for error/ambiguous cases and `null`/stream for the chunk list — or model it as a small private record `Located(Optional<FileView> earlyExit, List<PxChunk> chunks)`; pick whichever compiles cleanly. The `read` method must pass the early-exit `FileView` straight back to the caller.

### 3.3 `PrjXPConfig` — add ONE field (after `embeddingStoreLucene`):

```java
    /** Maximum characters returned by the readFile MCP tool (0 = uncapped). Property: prjxp.reader-max-output-chars */
    private int readerMaxOutputChars = 100_000;
```

### 3.4 `PrjxpMcpTool` — three surgical changes

1. New constructor field: `private final ReaderService readerService;` (Lombok `@RequiredArgsConstructor` picks it up).
2. New method (append after `grep`):

```java
    @McpTool(name = "readFile", description = """
            READER (3rd search level): Returns a COMPLETE source view of one file, reconstructed from the project index.
            WORKFLOW: vectorSearch (discover the file) -> grep (confirm content) -> readFile (read the WHOLE file).
            Use this when you need the entire file — all methods with full bodies — instead of snippets or skeletons.
            The result is a faithful source view, not a byte-identical file: whitespace/comments between units may differ.
            Result fields: content, truncated (100k char cap), totalLines, returnedLines, chunkCount, candidates (on ambiguous paths), error.
            """)
    public FileView readFile(
            @McpToolParam(description = """
                File path — absolute, project-root-relative, or just the file name (lenient: exact -> suffix -> basename).
                If the path is ambiguous the result contains a 'candidates' list instead of content.
                """, required = true)
            String file,

            @McpToolParam(description = "Optional project name. Defaults to the active project.", required = false)
            String project,

            @McpToolParam(description = "Optional 1-based line number to start reading from (paged reading of large files).", required = false)
            Integer offset,

            @McpToolParam(description = "Optional maximum number of lines to return, counted from 'offset'.", required = false)
            Integer limit
    ) {
        if (StringUtils.isEmpty(file)) {
            return FileView.error(file, "Parameter 'file' is required.");
        }
        if (StringUtils.isEmpty(project)) project = "default";
        return readerService.read(file.trim(), project, offset, limit);
    }
```
   (Add imports: `de.spraener.prjxp.common.model.FileView`.)
3. Replace the two existing description text blocks:

**vectorSearch** new description:
```
CRITICAL PRIMARY SEARCH TOOL (level 1 of 3): Delivers relevant semantic information from the project context.

USAGE RULES:
1. ALWAYS call this tool FIRST for discovery: finding classes, understanding structure, locating implementations, exploring the codebase.
2. SEARCH LADDER — vectorSearch (discovery) -> grep (exact string, single method body) -> readFile (COMPLETE file).
   For FULL METHOD BODIES: follow up with grep using the exact method signature (e.g. "public MClass createMClass").
   For the WHOLE FILE: follow up with readFile using the file path from the results.
   Vector search returns skeletons for non-hit methods by design.
3. You can execute multiple follow-up queries with refined search terms to dig deeper.
4. REWRITE the query parameter: Convert the context of the conversation into a targeted, standalone search query optimized for semantic vector retrieval.

RESULT:
The search returns method implementations only if the vector search hits a method chunk AND skeletonsOnly is set to false.
Otherwise it returns simple class skeletons with imports and project dependencies for an architectural overview.
Use grep on the exact signature for full method bodies, or readFile for the entire file.
```

**grep** new description:
```
SEARCH-TOOL (level 2 of 3): Exact full-text search over all chunks of a project.
STRATEGIE: vectorSearch for discovery, then grep for the exact method signature to retrieve its FULL BODY.
Chunks that contain a hit include the complete method body, not just the skeleton.
PARAMETER-RULE: Pass the exact string (e.g., "public MClass createMClass") as query.
NEED THE ENTIRE FILE INSTEAD OF ONE METHOD? Use readFile with the file path from the results (level 3).
```

## 4. TDD — Write These Tests FIRST (>= 80% LOC coverage of new classes)

JUnit 5 + Mockito + AssertJ, plain unit tests. Chunk fixture helper: `PxChunk.create(...)` with file `/src/Foo.java` (leading slash!), mime `text/x-java-code`, id/part/total/content/metadata.

`FileViewRegistryTest`:
1. `knownMimeResolvesProvider` — registry built with a stub `FileViewProvider` (mime `text/x-java-code`) → `forMime("text/x-java-code")` is that stub.
2. `unknownMimeFallsBackToGeneric` — `forMime("text/x-csharp")` is an instance of `GenericFileViewProvider` with language `"generic"`.
3. `wildcardProvidersAreIgnored` — a provider with mime `*/*` is not registered under that key.

`ReaderServiceTest` (mock `PxChunkDao`, mock `PxChunkDaoProvider`, real `PrjXPConfig`):
4. `exactMatchWithLeadingSlashInIndex` — request `src/Foo.java`; dao returns 2 parts of one unit for `/src/Foo.java` on the `"/src/Foo.java"` term query → view: file `src/Foo.java`, language `java`, content = rendered units, chunkCount 2.
5. `basenameMatchViaMatchAllFallback` — exact terms return empty; MatchAll (`Map.of()`) returns chunks for `/a/b/Foo.java` and `/c/Foo2.java` → basename match finds exactly `/a/b/Foo.java`.
6. `suffixMatchViaMatchAllFallback` — request `b/Foo.java` matches `/a/b/Foo.java`.
7. `ambiguousPathReturnsCandidates` — MatchAll yields `/a/Foo.java` and `/b/Foo.java` → `candidates` has both, content null, error mentions candidates.
8. `fileNotFoundReturnsError` — no match → error non-null, content null.
9. `noDaoReturnsError` — provider returns empty for both names → error non-null.
10. `unsupportedStoreReturnsError` — `searchByIndex` throws `UnsupportedOperationException` → error mentions Lucene.
11. `offsetAndLimitSliceLines` — rendered view 10 lines; offset 3, limit 4 → `returnedLines` 4, `totalLines` 10, content = lines 3–6.
12. `capTruncatesContent` — `readerMaxOutputChars = 10` → content length ≤ 10, `truncated` true.

`PrjxpMcpToolTest` (mock all four constructor deps, incl. `ReaderService`):
13. `readFileDelegatesAndDefaultsProject` — null project → service receives `"default"`; blank file → error view, service NOT called.

## 5. Definition of Done

- 3 files created, 2 files modified exactly as specified (no other lines in `PrjxpMcpTool`/`PrjXPConfig` touched).
- `./gradlew :mcp-server:test` green with all 13 tests; ≥ 80% LOC coverage of `ReaderService` + `FileViewRegistry`.
- `./gradlew build` green.
- Execute `/compact`, then return.
