# Phase 06: mcp-server — readBySignature (SymbolReaderService + MCP Tool)

> **STRICT EXECUTION RULES FOR THE AGENT:**
> 1. DO NOT run repository scans (`find_files`, `grep`, `search_code`, `list_dir`).
> 2. Read ONLY the files explicitly listed in the "File Scope" section below.
> 3. All required interfaces/DTOs are provided inline in this document. Do not fetch them from the codebase.
> 4. **MANDATORY FINAL STEP:** As soon as the implementation and tests (>= 80% coverage) are green, execute `/compact` IMMEDIATELY to clean up the context window before returning control.

## 1. Target & Scope

* **Objective:** The `readBySignature` MCP tool: deterministic symbol lookup in the index (`symbol_*` metadata), reassembly of method + javadoc units, max 10 full matches + list of remaining FQNs. Java-only in Phase 1 (only the Java chunker writes symbol metadata) — stated in the description.
* **Target Files:**
    * Create: `mcp-server/src/main/java/de/spraener/prjxp/mcp/SymbolReaderService.java`
    * Create: `mcp-server/src/test/java/de/spraener/prjxp/mcp/SymbolReaderServiceTest.java`
    * Modify: `mcp-server/src/main/java/de/spraener/prjxp/mcp/PrjxpMcpTool.java` (ONLY: one new constructor field, one new `@McpTool` method, final wording of the vectorSearch/grep descriptions — see §3.3)
* **NO modifications to anything else, NO new dependencies, NO REST controller.**

## 2. Inline Required Context (Contracts & Signatures)

```java
// Phase 01 types (prjxp-common):
public record MethodView(String fqn, String file, Integer lineFrom, Integer lineTo, String javadoc, String body) { }
public record SymbolReadResult(List<MethodView> matches, List<String> remainingFqns, String error) {
    static SymbolReadResult error(String message);
}
public final class ReaderSupport { static List<PxChunk> combineUnits(List<PxChunk>); static int parseLine(String); /* ... */ }

// de.spraener.prjxp.common.model.SymbolMetadata (existing constants):
//   SYMBOL_FQN = "symbol_fqn"  // methods: "<containerFqn>#<name>"; classes/imports: the class FQN
//   SYMBOL_NAME = "symbol_name"  // methods: <name>; classes/imports: the simple class name
//   SYMBOL_CONTAINER_FQN = "symbol_container_fqn"  // methods only: the class FQN
//   (also SYMBOL_TYPE, SYMBOL_SIGNATURE_HASH — NOT used by this service)

// Java unit structure (Phase 02 context, restated because symbol metadata lives here):
//   metadata key "java_code_section" values: classFrame / method / methodDoc / imports
//   method unit:   id "<FQN>.<sig>",            parent "<FQN>",  content = full body (ends '\n')
//   methodDoc unit: id "<methodId>.javadoc",    parent "<methodId>", content = javadoc
//   classFrame:    id "<FQN>"  (root);  imports: id "<FQN>.imports"  — both carry class-level symbol metadata

// Store / config (as in Phase 05):
//   PxChunkDao.searchByIndex(Map<String,String>, int limit)  // exact TermQuery per key, uncapped, empty map = MatchAll
//   PxChunkDaoProvider.get(String) -> Optional<PxChunkDao>
//   PrjXPConfig.getActiveProject() -> Optional<ProjectDefinition>;  ProjectDefinition.getName()
//   record ScoredChunk(PxChunk chunk, double score)

// Existing PrjxpMcpTool state AFTER Phase 05:
//   fields: GRPromptEnrichment enrichment; PrjXPConfig cfg; GrepSearchService grepSearchService; ReaderService readerService;
//   tools:  vectorSearch(...), grep(...), readFile(...)
//   imports include: org.apache.commons.lang3.StringUtils, org.springaicommunity.mcp.annotation.McpTool/McpToolParam
```

## 3. Implementation (exact)

### 3.1 `SymbolReaderService`

```java
@Service
@RequiredArgsConstructor
public class SymbolReaderService {
    private static final int LOOKUP_LIMIT = 10_000;
    private static final int MAX_FULL_MATCHES = 10;
    private static final String SECTION_KEY = "java_code_section";

    private final PxChunkDaoProvider chunkDaoProvider;
    private final PrjXPConfig cfg;

    public SymbolReadResult readBySignature(String method, String container, String project) {
        if (method == null || method.isBlank())
            return SymbolReadResult.error("Parameter 'method' is required.");

        PxChunkDao dao = resolveDao(project);
        if (dao == null)
            return SymbolReadResult.error("No embedding store available for project '" + project + "'.");

        Map<String, String> filters = new HashMap<>();
        if (method.contains("#"))
            filters.put(SymbolMetadata.SYMBOL_FQN, method.trim());
        else {
            filters.put(SymbolMetadata.SYMBOL_NAME, method.trim());
            if (container != null && !container.isBlank())
                filters.put(SymbolMetadata.SYMBOL_CONTAINER_FQN, container.trim());
        }

        List<PxChunk> hits;
        try {
            hits = dao.searchByIndex(filters, LOOKUP_LIMIT).stream().map(ScoredChunk::chunk).toList();
        } catch (UnsupportedOperationException uoe) {
            return SymbolReadResult.error("Index search is not supported by this embedding store — readBySignature requires a Lucene index.");
        }
        if (hits.isEmpty())
            return SymbolReadResult.error("No symbol matching '" + method + "'"
                    + (container != null && !container.isBlank() ? " in container '" + container + "'" : "") + " found in the index.");

        List<PxChunk> units = ReaderSupport.combineUnits(hits);
        Map<String, PxChunk> docByMethodId = new HashMap<>();
        List<PxChunk> methodUnits = new ArrayList<>();
        List<PxChunk> classUnits = new ArrayList<>();
        for (PxChunk u : units) {
            String section = u.getMetadata().get(SECTION_KEY);
            if ("method".equals(section)) methodUnits.add(u);
            else if ("methodDoc".equals(section)) docByMethodId.put(u.getId(), u);
            else if ("classFrame".equals(section)) classUnits.add(u);
            // imports / unknown: never rendered
        }

        List<MethodView> views = new ArrayList<>();
        for (PxChunk m : methodUnits)
            views.add(toMethodView(m, docByMethodId.get(m.getId() + ".javadoc")));
        for (PxChunk c : classUnits)   // a class FQN was requested -> return the class frame as the "body"
            views.add(new MethodView(meta(c, SymbolMetadata.SYMBOL_FQN), c.getFile(),
                    ReaderSupport.parseLineOrNull(c.getFromLine()), ReaderSupport.parseLineOrNull(c.getToLine()), null, c.getContent()));

        if (views.isEmpty())
            return SymbolReadResult.error("Symbol found but no renderable method/class units (metadata without section info).");

        if (views.size() <= MAX_FULL_MATCHES)
            return new SymbolReadResult(views, List.of(), null);

        List<MethodView> full = views.subList(0, MAX_FULL_MATCHES);
        List<String> remaining = views.subList(MAX_FULL_MATCHES, views.size()).stream()
                .map(MethodView::fqn).distinct().toList();
        return new SymbolReadResult(List.copyOf(full), remaining, null);
    }

    private MethodView toMethodView(PxChunk m, PxChunk doc) {
        return new MethodView(meta(m, SymbolMetadata.SYMBOL_FQN), m.getFile(),
                ReaderSupport.parseLineOrNull(m.getFromLine()), ReaderSupport.parseLineOrNull(m.getToLine()),
                (doc != null && doc.getContent() != null && !doc.getContent().isBlank()) ? doc.getContent() : null,
                m.getContent());
    }
    // meta(u, key): u.getMetadata().get(key)
    // resolveDao(project): identical pattern to ReaderService (Phase 05) — blank/"default" -> activeProject, then provider.get, then "default" retry
    //
    // NOTE: Phase 01's ReaderSupport defines parseLine with 0-fallback. ADD a second static to it in this phase:
    //   static Integer parseLineOrNull(String value)  // null/blank/NumberFormat -> null, else parsed int
    // (modify ONLY that one file: prjxp-common .../reader/ReaderSupport.java — add the method, keep parseLine).
}
```

### 3.2 `PrjxpMcpTool` — two additions

```java
    private final SymbolReaderService symbolReaderService;   // new constructor field

    @McpTool(name = "readBySignature", description = """
            READER (3rd search level): Returns the COMPLETE implementation of a symbol — javadoc + full method body — located by its name in the index.
            Java ONLY in this version: TypeScript and Visual Basic are not indexed with symbol metadata yet.
            WORKFLOW: vectorSearch (discover) -> grep (locate) -> readBySignature (complete method, ALL overloads).
            RESULT: up to 10 full matches (fqn, file, lineFrom/lineTo, javadoc, body). When more than 10 match,
            'remainingFqns' lists the rest — call again with one of them as 'method' (FQN form) to fetch it in full.
            If the symbol is a class, the class frame (structure + signatures) is returned as 'body'.
            """)
    public SymbolReadResult readBySignature(
            @McpToolParam(description = """
                Method name (e.g. 'createMClass') or fully qualified 'com.example.Foo#bar'.
                Class FQNs (e.g. 'com.example.Foo') return the class frame.
                """, required = true)
            String method,

            @McpToolParam(description = "Optional containing class FQN to disambiguate a bare method name.", required = false)
            String container,

            @McpToolParam(description = "Optional project name. Defaults to the active project.", required = false)
            String project
    ) {
        if (StringUtils.isEmpty(project)) project = "default";
        return symbolReaderService.readBySignature(method, container, project);
    }
```
(Add imports: `de.spraener.prjxp.common.model.SymbolReadResult`.)

### 3.3 Final wording of the two earlier descriptions (replace the Phase-05 text in `PrjxpMcpTool`)

**vectorSearch** — replace line 2 ("SEARCH LADDER …") with:
```
2. SEARCH LADDER — vectorSearch (discovery) -> grep (exact string, single method body) -> readBySignature (complete method by name) -> readFile (COMPLETE file).
   For FULL METHOD BODIES: follow up with grep using the exact method signature (e.g. "public MClass createMClass") or readBySignature with the method name.
   For the WHOLE FILE: follow up with readFile using the file path from the results.
   Vector search returns skeletons for non-hit methods by design.
```
(Keep the rest of the Phase-05 vectorSearch description verbatim.)

**grep** — append one line at the end:
```
NEED THE COMPLETE METHOD BY NAME OR THE ENTIRE FILE? Use readBySignature (method) or readFile (whole file) — level 3.
```
(Replace the Phase-05 last line "NEED THE ENTIRE FILE INSTEAD OF ONE METHOD? Use readFile with the file path from the results (level 3).".)

## 4. TDD — Write These Tests FIRST (>= 80% LOC coverage of the new service)

JUnit 5 + Mockito + AssertJ. Fixture helper builds Java units (`PxChunk.create`) with `java_code_section` + `symbol_*` metadata; `method` id `de.ex.Foo.public void bar()`, doc id `...bar().javadoc`, classFrame id `de.ex.Foo`; mock the dao's `searchByIndex` to return the matching parts.

1. `fqnLookupReturnsMethodWithJavadoc` — `readBySignature("de.ex.Foo#bar", null, "p")` → 1 match: fqn `de.ex.Foo#bar`, javadoc = doc content, body = method content, lineFrom/To parsed.
2. `bareNameWithContainerReturnsAllOverloads` — two method units `de.ex.Foo.public void bar()` / `de.ex.Foo.public void bar(int)` + 2 docs; `readBySignature("bar", "de.ex.Foo", "p")` → 2 matches, each paired with ITS OWN doc (id `+ ".javadoc"` pairing).
3. `bareNameWithoutContainerUsesNameOnlyFilter` — verify the filter map passed to `searchByIndex` is exactly `{symbol_name: bar}` (ArgumentCaptor / verify with `Map.of`).
4. `classFqnReturnsFrameAsBody` — request `de.ex.Foo` (no `#`) with a classFrame + imports unit in the hits → 1 match, body = frame content, javadoc null, imports NOT returned.
5. `moreThan10MatchesCapsAt10PlusRemaining` — 13 method units (names `m1`…`m13`, container `C`) → 10 matches, `remainingFqns` = distinct fqns of m11–m13.
6. `noHitsReturnsError` — empty list → error mentions the searched name; matches empty.
7. `unsupportedStoreReturnsError` — `searchByIndex` throws UOE → error mentions Lucene.
8. `noDaoReturnsError` — provider empty → error.
9. `blankMethodReturnsError` — `""` → error, dao never called.

## 5. Definition of Done

- `SymbolReaderService` + test created; `PrjxpMcpTool` modified exactly as specified; `ReaderSupport.parseLineOrNull` added (one file in prjxp-common).
- `./gradlew :prjxp-common:test :mcp-server:test` green with all 9 tests; ≥ 80% LOC coverage of `SymbolReaderService`.
- `./gradlew build` green (full reactor: prjxp-common, golden-retriever, mcp-server, …).
- Execute `/compact`, then return.
