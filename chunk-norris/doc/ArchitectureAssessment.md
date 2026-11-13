# Software Architecture Assessment: ChunkNorris (PrjXP Document & Code Chunking Pipeline)

## 1. Executive Summary

ChunkNorris is a Spring Boot–based CLI application that walks project source trees, applies exclusion vetoes, routes files to language-specific chunkers (Java, TypeScript, Visual Basic, plain text, Markdown, PDF, DOCX, RTF, HTML), and emits structured `PxChunk` records as JSONL. It additionally builds a Java AST knowledge graph (with Leiden community detection) and a document-conversion pipeline using Dijkstra shortest-path routing over a graph of format converters.

**Key Strengths:**
- Well-defined extension points via `LanguagePack`, `PxChunker`, `DocConversionAgent`, and annotation-driven discovery (`@Chunker`, `@ChunkVeto`, `@PostWalkChunker`).
- Elegant Dijkstra-based conversion routing with cost/accuracy weighting.
- Robust fallback strategies (raw line-splitting when AST parsing fails; `DefaultLineBreakTextChunker` as universal catch-all).
- Clean separation between the orchestration layer (`ChunkProcess`), the routing layer (`ChunkerFactory` / `LanguagePackBroker`), and the leaf chunkers.

**Most Critical Risks:**
- The `TypeScriptCodeChunker` relies on brittle regex-based parsing with no real AST, making it fragile against code style variations.
- `JavaCodeChunker` is a ~400-line God-class accumulating multiple responsibilities (imports, methods, constructors, enum constants, class frames, meta-chunks, graph metadata stamping).
- `AnnotationBasedChunkerBrokerImpl` performs runtime classpath scanning + reflection with no caching beyond a null-check, and its `PxPostWalkChunkerMethodWrapper` incorrectly reads `@Chunker` annotation instead of `@PostWalkChunker`.
- `DependencyRegistryManager` uses a non-thread-safe `HashMap` in a Spring singleton, creating a race condition under concurrent access.
- `VetoContext` (ThreadLocal) is not cleared on the `shouldVeto(Path)` (single-arg) path, risking stale context in pooled threads.

## 2. Architectural Style & Patterns

| Pattern | Where Applied | Assessment |
|---------|--------------|------------|
| **Layered / Pipeline** | `ChunkNorris` → `ChunkProcess` → `ChunkerFactory` → `PxChunker` implementations | Clear unidirectional flow; the orchestrator delegates to factories which delegate to leaf strategies. |
| **Strategy** | `PxChunker` interface with `matches(File)` / `chunk(File)` | Each language/format provides its own strategy; the factory selects at runtime. |
| **Plugin / SPI** | `LanguagePack` + `LanguagePackRegistry` + `ServiceLoader<ChunkerBroker>` | New languages can be added as separate JARs without modifying the core. |
| **Annotation-Driven Discovery** | `@Chunker`, `@ChunkNorrisComponent`, `@ChunkVeto`, `@PostWalkChunker` | Reduces wiring boilerplate; however, the reflection-based `AnnotationBasedChunkerBrokerImpl` is fragile. |
| **Event-Driven (Spring)** | `SpringPreWalkEvent`, `CliArgsParsingEvent` | Decouples pre-walk graph building from the main walk; allows ordered listeners (`@Order`). |
| **Graph / Dijkstra Routing** | `DocConversionRouter` builds a `SimpleDirectedWeightedGraph` of `DocArtifaktType` nodes | Optimal path selection among conversion agents with cost + accuracy surcharges. |
| **Registry** | `DependencyRegistry`, `VetoRegistry`, `LanguagePackRegistry` | Centralized lookup; `VetoRegistry` doubles as a `BeanPostProcessor`. |
| **Builder (functional)** | `PxChunk.create(Consumer<PxChunk>...)` | Fluent, composable chunk construction without mutable builder state. |
| **Template Method** | `AnnotationBasedChunkerBrokerImpl` (abstract) → `ChunkerFactory` (concrete) | Shared scanning logic with overridable broker composition. |
| **Visitor / Tree-Walk** | `JavaCodeGraphBuilder.collectTypes` (BFS over nested types) | Handles inner/nested classes correctly. |

**Decoupling Assessment:**
- The core pipeline (`ChunkProcess`) is well-decoupled from specific chunkers via the `ChunkerBroker` interface and `LanguagePackRegistry`.
- The document-conversion subsystem (`DocConversionRouter`, `DocArtifakt` tree) is self-contained and loosely coupled to the chunking pipeline (connected only through `MarkdownChunker`).
- The code-graph subsystem (`JavaCodeGraphBuilder`, `JavaFQNamesMapper`) is tightly coupled to the Java chunker via direct injection, but is abstracted behind `CodeGraphBuilder` for the `LanguagePack` contract.
- **Weakness:** `JavaCodeChunker` directly injects `JavaCodeGraphBuilder`, `JavaFQNamesMapper`, and `JavaDependencyHandler`—three collaborators that could be encapsulated behind a single `JavaAnalysisContext` to reduce the chunker's dependency surface.

## 3. Quality Attribute Evaluation

### Maintainability & Readability

- **Positive:** Extensive Javadoc on complex classes (`DefaultLineBreakTextChunker`, `FullTextChunker`, `JavaParserFactory`, `DirectoryVetos`). Use of records (`SpringPreWalkEvent`, `VisualBasicFileInfo`, `ContainerInfo`, `MemberInfo`, `PendingEdge`, `MethodInfo`) for immutable value objects. Consistent naming (`createXxxChunk`, `findXxxEnd`).
- **Negative:**
  - `JavaCodeChunker` is ~400 lines with 10+ private methods of varying complexity. The `createContainedMethodChunks` method alone is ~80 lines with deeply nested lambdas.
  - Inconsistent logging: `@Log` (java.util.logging) in most classes, `@Slf4j` (SLF4J) in `AnnotationBasedChunkerBrokerImpl`, and `System.out.println` in `ChunkNorris.run` and `PdfChapterChunker`.
  - `TextChunker` uses a `ValueContainer<BiConsumer<...>>` to pass a mutable callback—obscure and hard to follow.
  - German and English comments are mixed throughout (e.g., `JavaFQNamesMapper`, `DocConversionRouter`, `Pdf2TextConversionAgent`), reducing consistency for international teams.
  - `PdfChapterChunker` is entirely commented out (`//@Component`) but remains in the source tree—dead code.

### Extensibility

- **Positive:**
  - Adding a new language requires: (1) a `PxChunker` implementation, (2) a `LanguagePack` registration, (3) optionally a `CodeGraphBuilder`. No modification to `ChunkProcess` or `ChunkerFactory` needed.
  - `ServiceLoader<ChunkerBroker>` allows external JARs to contribute additional brokers.
  - `DocConversionAgent` is a clean SPI: implement the interface, register as a `@Component`, and the Dijkstra router picks it up automatically.
  - `@ChunkVeto` annotation + `BeanPostProcessor` discovery means new veto rules are a single method.
- **Negative:**
  - The `AnnotationBasedChunkerBrokerImpl` hardcodes the root package via `super(ChunkerFactory.class.getPackageName())`. A plugin in a different package would not be discovered by the annotation scanner (only by `ServiceLoader`).
  - `JavaCodeChunker`'s `createMetaChunk` returns `List.of()`—a stub that signals an incomplete feature, making it unclear whether the method should be removed or completed.
  - The `DocConversionRouter.estimateConversionCosts` method is recursive with no memoization; adding a new agent that creates many children (e.g., PDF → N images) causes exponential re-evaluation.

### Robustness & Error Handling

- **Positive:**
  - Binary file detection via null-byte sniffing in `FullTextChunker` and `DefaultLineBreakTextChunker`.
  - `JavaCodeChunker` catches `ParseProblemException` separately and falls back to `rawFallback` (line-based splitting).
  - `JavaCodeGraphBuilder.process` catches all exceptions per-file, preventing one bad file from aborting the walk.
  - `VetoRegistry.VetoMethodWrapper.check` catches all exceptions and returns `false` (fail-open for vetoes).
  - `Pdf2ImageConversionAgent.Pdf2ImageContext` implements `AutoCloseable` for resource cleanup.
- **Negative:**
  - `DefaultLineBreakTextChunker.chunk` and `FullTextChunker.chunk` swallow `IOException` silently (return `Stream.of()`). A permission error or I/O failure is indistinguishable from an empty file.
  - `TypeScriptCodeChunker.chunk` catches `Exception` broadly and logs only `e.getMessage()`—stack trace is lost.
  - `Doc2HtmlConversionAgent.convert` writes a `.html` file to disk as a side effect inside the conversion method—this is a hidden I/O operation that can fail silently (PrintWriter swallows I/O errors).
  - `TextChunker` throws `RuntimeException` on any exception, which will propagate up and abort the entire file walk.
  - `RtfToHtmlAgent.convert` wraps all exceptions in `RuntimeException` with a German message—no structured error propagation.
  - `Pdf2ImageConversionAgent.pageCount` catches `IOException` and returns `0`—a corrupt PDF is indistinguishable from an empty one.

### Performance & Resource Efficiency

- **Positive:**
  - `JavaParserFactory` shares a single thread-safe `JavaParser` instance across all components (avoids per-call configuration overhead).
  - `JavaCodeGraphBuilder` uses `ConcurrentHashMap` for `name2FQNamesMap` and `AtomicInteger` for counters in the event listener.
  - `ContentSplitter` is reused across chunkers for consistent splitting logic.
  - `ChunkProcess.handlePath` uses `.parallel()` on the chunker stream for concurrent chunking of a single file's multiple chunkers.
- **Negative:**
  - `FullTextChunker` and `DefaultLineBreakTextChunker` both call `Files.readString` (loads entire file into memory) and then `content.split("\n", -1)` (allocates a String array of all lines). For a 50 MB file, this is ~100 MB+ of transient heap.
  - `JavaCodeChunker.chunk` reads the file **twice**: once via `Files.readAllLines` and once via `javaParserFactory.parse(f)` (which re-reads the file). The TypeScript chunker does the same.
  - `JavaCodeGraphBuilder.onPreWalk` walks the entire project tree a second time (in addition to `ChunkProcess`'s walk), re-parsing every `.java` file. This doubles I/O and CPU for Java projects.
  - `DocConversionRouter.estimateConversionCosts` is a recursive DFS that creates a new `DocArtifakt` and a new `HashSet` at each level—O(2^depth) in the worst case with no pruning.
  - `Pdf2ImageConversionAgent` renders all pages at 300 DPI into `BufferedImage` objects held in memory simultaneously (one per page). A 100-page PDF at 300 DPI ≈ 100 × ~15 MB = 1.5 GB.
  - `DependencyRegistry.addDependency` is `synchronized` but `getDependencies` / `getUsedBy` are not—readers can observe partially-updated state.
  - `DependencyRegistryManager.dependencyRegistryMap` is a plain `HashMap` in a Spring singleton—race condition under concurrent `get()` calls.

## 4. Strengths & Best Practices

1. **LanguagePack Abstraction:** The `LanguagePack` interface cleanly bundles a language's chunkers, post-walk chunkers, and optional code-graph builder into a single registration unit. `LanguagePackBroker` bridges this to the `ChunkerBroker` contract, enabling multi-language support without modifying the core pipeline.

2. **Dijkstra Conversion Routing:** `DocConversionRouter` models format conversion as a weighted directed graph and uses JGraphT's `DijkstraShortestPath` to find the optimal path. The cost model incorporates both computational cost and accuracy rank (with a configurable surcharge for lower-accuracy paths), making the routing decision principled rather than hardcoded.

3. **Annotation-Driven Veto System:** `VetoRegistry` as a `BeanPostProcessor` that discovers `@ChunkVeto`-annotated methods at startup is a clean, low-boilerplate way to register exclusion rules. The `VetoContext` (ThreadLocal) allows project-scoped veto evaluation without polluting method signatures.

4. **Graceful Degradation in Java Chunking:** `JavaCodeChunker` attempts full AST-based chunking; on `ParseProblemException`, it falls back to `rawFallback` (line-based splitting with 10k-char parts). This ensures every file produces *some* chunks even if the parser fails.

5. **Centralized Parser Configuration:** `JavaParserFactory` eliminates the anti-pattern of each component configuring `StaticJavaParser` independently. The Javadoc explicitly explains the thread-safety rationale.

6. **Idempotent Graph Building:** `JavaCodeGraphBuilder.beginRun()` clears all accumulated state, and `build()` is documented as idempotent. The `@Order(20)` on the event listener ensures the FQNames map (`@Order(10)`) is populated before graph construction.

7. **Lossless Reassembly Design:** `DefaultLineBreakTextChunker` carefully preserves trailing newlines in all parts except the last, enabling `PxChunk.combine` / `ContentSplitter.unsplit` to reconstruct the original file byte-for-byte.

8. **Project-Relative Path Normalization:** `ChunkProcess.toProjectRelativePath` and `rebaseIdToProjectPath` handle the common problem of same-named files in different directories colliding on chunk IDs, with clear documentation of the rebasing logic.

9. **Encrypted Transfer Support:** `TransferSession` / `TransferCrypto` integration for optional AES-GCM encryption of output artifacts, with a user-friendly password-repeat banner.

10. **Use of Records for Value Objects:** `SpringPreWalkEvent`, `VisualBasicFileInfo`, `ContainerInfo`, `MemberInfo`, `PendingEdge`, `PendingCall`, `MethodInfo`, `Context`—all use Java records, providing immutability, concise syntax, and value-based equality.

## 5. Identified Risks & Technical Debt

### Critical

| # | Issue | Location | Impact |
|---|-------|----------|--------|
| 1 | **Regex-based TypeScript parsing** | `TypeScriptCodeChunker` | `METHOD_PATTERN`, `CLASS_PATTERN`, `FUNCTION_PATTERN` are single-line regexes that fail on multi-line signatures, decorators, arrow functions, generic constraints, and any non-conventional formatting. The chunker will silently produce incorrect or missing chunks for a large fraction of real-world TS code. |
| 2 | **Thread-unsafe `DependencyRegistryManager`** | `DependencyRegistryManager` | `dependencyRegistryMap` is a plain `HashMap` in a Spring singleton. Concurrent `get()` calls from parallel chunking (`.parallel()` in `ChunkProcess`) can corrupt the map or cause infinite loops in `HashMap`'s internal tree. |
| 3 | **`VetoContext` ThreadLocal leak** | `VetoRegistry.shouldVeto(Path)` (single-arg) | The single-arg overload does **not** set or clear the ThreadLocal. In a thread-pool context (e.g., Spring's `TaskExecutor`), a stale `ProjectDefinition` from a previous task can leak into the next task's veto evaluation. |
| 4 | **`PxPostWalkChunkerMethodWrapper` reads wrong annotation** | `AnnotationBasedChunkerBrokerImpl` | The constructor calls `method.getAnnotation(Chunker.class)` instead of `PostWalkChunker.class`. The `matches()` method then iterates `this.annotation.fileTypes()` on a null or wrong annotation, causing NPE or incorrect matching. |
| 5 | **Double file I/O in Java/TS chunkers** | `JavaCodeChunker.chunk`, `TypeScriptCodeChunker.chunk` | Both read the file via `Files.readAllLines` **and** pass the `File` to `javaParserFactory.parse(f)` which re-reads it. For large codebases, this doubles disk I/O and memory pressure. |

### High

| # | Issue | Location | Impact |
|---|-------|----------|--------|
| 6 | **God-class: `JavaCodeChunker`** | `JavaCodeChunker` (~400 LOC) | Accumulates import chunking, method chunking, constructor chunking, enum-constant chunking, class-frame chunking, meta-chunking, graph-metadata stamping, and raw-fallback logic. Violates SRP; any change to one concern risks regressions in others. |
| 7 | **Exponential cost estimation** | `DocConversionRouter.estimateConversionCosts` | Recursive DFS with no memoization or depth limit. A PDF with 200 pages (each spawning a `BUFFERED_IMAGE` child) causes 200 recursive calls, each re-evaluating all agents. With multiple branching agents, this is exponential. |
| 8 | **`Pdf2ImageConversionAgent` memory** | `Pdf2ImageConversionAgent` | All page `BufferedImage` objects are held in memory simultaneously (300 DPI). No streaming or page-by-page processing. A 50-page A4 PDF ≈ 750 MB heap. |
| 9 | **Inconsistent logging frameworks** | Throughout | `@Log` (JUL) in 15+ classes, `@Slf4j` (SLF4J) in 1, `System.out.println` in 2. No unified logging configuration; log levels and formatting will be inconsistent. |
| 10 | **`Doc2HtmlConversionAgent` hidden file I/O** | `Doc2HtmlConversionAgent.convert` | Writes a `.html` file to disk as a side effect. `PrintWriter` swallows I/O exceptions. This is a hidden, uncontrolled filesystem mutation inside a "conversion" method. |

### Medium

| # | Issue | Location | Impact |
|---|-------|----------|--------|
| 11 | **Dead code: `PdfChapterChunker`** | `PdfChapterChunker` | Entirely commented out (`//@Component`, `//@Chunker`). Contains `System.out.println` debug statements. Should be removed or properly implemented. |
| 12 | **`createMetaChunk` stub** | `JavaCodeChunker` | Returns `List.of()`—an unimplemented feature that adds a no-op call in the chunking pipeline. |
| 13 | **`javax.print.Doc` import** | `Pdf2ImageConversionAgent`, `DocContentCollector` | Unused import of `javax.print.Doc` (Java Print Service API)—clearly a copy-paste error. |
| 14 | **`TextChunker` Groovy scripting** | `TextChunker` | Compiles and evaluates user-supplied `.groovy` scripts at runtime. No sandboxing, no security manager. A malicious `.groovy` file in the project tree can execute arbitrary code. |
| 15 | **`FullTextChunker` and `DefaultLineBreakTextChunker` both match everything** | Both classes | Both have `matches() == true`. The `ChunkerFactory` special-cases `FullTextChunker` by name (`instanceof` check) to determine "specific" vs. "fallback." Adding another catch-all chunker would require modifying the factory. |
| 16 | **`JavaCodeGraphBuilder` mutable state** | `JavaCodeGraphBuilder` | `nodes`, `edges`, `calls` are `ArrayList` fields mutated during the walk. If `process()` were ever called concurrently (e.g., from a parallel stream), the lists would be corrupted. Currently safe only because the walk is sequential. |
| 17 | **`DependencyRegistry` partial synchronization** | `DependencyRegistry` | `addDependency` is `synchronized`, but `getDependencies` and `getUsedBy` are not. Readers can observe a partially-updated `HashSet` (though `HashSet` iteration is not strictly unsafe, the `computeIfAbsent` + `add` sequence is not atomic from the reader's perspective). |
| 18 | **`ConversionRoutesConfig` uses `@Data` on a `@Component`** | `ConversionRoutesConfig` | `@Data` generates setters for all fields including `predefinedRoutes` and `contextSupplier`. The `contextSupplier` is `final` (constructor-injected) but `@Data` will still generate a setter for it (Lombok skips `final` fields, but the `predefinedRoutes` setter is exposed). |

### Low

| # | Issue | Location | Impact |
|---|-------|----------|--------|
| 19 | **`VisualBasicCodeChunker` field initializers** | `VisualBasicCodeChunker` | `@Value` fields have explicit initializers (`= 1300`, `= 100`) that are redundant with the `@Value` default syntax (`:1300`). Confusing but harmless. |
| 20 | **`MetaInfReader` static `ObjectMapper`** | `MetaInfReader` | `private static final ObjectMapper mapper = new ObjectMapper()`—fine for thread safety, but not configurable (no module registration, no serialization features). |
| 21 | **`HtmlStructurePreprocessor` hardcoded class names** | `HtmlStructurePreprocessor` | Matches German class names (`"berschrift"`, `"Listenabsatz"`, `"Titel"`)—fragile to any change in the source document's CSS classes. |
| 22 | **`CostEstimation` is an interface with constants** | `CostEstimation` | Should be an enum or a class with `static final` fields. An interface with only constants is a code smell. |

## 6. Actionable Recommendations

### Priority 1 — Immediate (Correctness / Safety)

1. **Fix `PxPostWalkChunkerMethodWrapper` annotation bug.**
   Change `this.annotation = method.getAnnotation(Chunker.class)` to `method.getAnnotation(PostWalkChunker.class)`. Add a unit test that verifies `matches()` returns `false` for post-walk wrappers.

2. **Make `DependencyRegistryManager` thread-safe.**
   Replace `HashMap` with `ConcurrentHashMap` and use `computeIfAbsent` for the registry lookup. Alternatively, synchronize the `get()` method.

3. **Clear `VetoContext` in the single-arg `shouldVeto(Path)` path.**
   Either (a) always set/clear the context in both overloads, or (b) refactor to pass `ProjectDefinition` explicitly through the veto method signatures (eliminating the ThreadLocal entirely). Option (b) is preferred for testability.

4. **Remove or sandbox the Groovy scripting in `TextChunker`.**
   If the feature is required, run scripts in a `SecureClassLoader` with a restricted `ScriptEngine` (no `Runtime`, no `ProcessBuilder`). If not required, remove the code path.

5. **Remove dead code:** Delete `PdfChapterChunker` (commented out) and the unused `javax.print.Doc` imports.

### Priority 2 — Near-Term (Maintainability / Performance)

6. **Decompose `JavaCodeChunker` into focused collaborators.**
   Extract:
   - `JavaImportChunker` (import range detection + chunking)
   - `JavaMethodChunker` (method + constructor + Javadoc chunking)
   - `JavaClassFrameChunker` (class frame assembly)
   - `JavaEnumConstantChunker` (enum constant chunking)
   - `JavaGraphMetadataStamper` (graph node → Leiden metadata)
   - `JavaRawFallbackChunker` (line-based fallback)

   `JavaCodeChunker` becomes a thin orchestrator that delegates to these. Each collaborator is independently testable.

7. **Replace regex-based TypeScript parsing with a real AST.**
   Integrate a TypeScript parser (e.g., `typescript-java` via JNA, or a tree-sitter binding, or the `@typescript-eslint` parser via a sidecar process). The current regex approach will produce incorrect chunks for any non-trivial TS code. At minimum, add a `ContentSplitter`-based fallback when regex matching fails to find any methods/classes.

8. **Eliminate double file I/O in chunkers.**
   Pass the already-read `List<String>` (or `String` content) to the parser instead of the `File`. For `JavaParserFactory`, add a `parse(String content)` overload (already exists) and use it in `JavaCodeChunker` to avoid the second read.

9. **Add memoization / pruning to `DocConversionRouter.estimateConversionCosts`.**
   Use a `Map<DocArtifaktType, Double>` to cache the minimum cost from each type to the target. Add a depth limit (e.g., max 5 hops). This converts the exponential DFS into a polynomial Dijkstra pre-pass.

10. **Stream PDF page rendering.**
    In `Pdf2ImageConversionAgent`, render pages one at a time and pass each `BufferedImage` to the next agent immediately, releasing the previous image. Alternatively, cap the DPI or use a lower resolution for the AI vision path.

11. **Unify logging to SLF4J.**
    Replace all `@Log` (JUL) annotations with `@Slf4j` (SLF4J). Remove `System.out.println` calls. Configure a single `logback-spring.xml` with appropriate appenders.

### Priority 3 — Medium-Term (Architecture / Extensibility)

12. **Introduce a `JavaAnalysisContext` facade.**
    Bundle `JavaFQNamesMapper`, `JavaCodeGraphBuilder`, `JavaDependencyHandler`, and `JavaParserFactory` behind a single `JavaAnalysisContext` interface. `JavaCodeChunker` depends on this one interface rather than four concrete classes. This reduces the chunker's dependency surface and makes it easier to mock in tests.

13. **Make the catch-all chunker contract explicit.**
    Define a `CatchAllChunker` marker interface (or a `boolean isCatchAll()` method on `PxChunker`). Replace the `instanceof FullTextChunker` check in `ChunkerFactory` with this contract. This allows new catch-all chunkers to be added without modifying the factory.

14. **Add a `CodeGraphBuilder` lifecycle contract.**
    Formalize the `beginRun()` → `process(File)` → `build()` → `weightedGraph()` sequence as a `CodeGraphBuildSession` interface with explicit state transitions. This prevents the current mutable-state pattern in `JavaCodeGraphBuilder` from being misused if the walk is ever parallelized.

15. **Externalize the `DocConversionRouter` graph construction.**
    Currently, the graph is rebuilt on every `findBestPath` call. Cache the graph structure (vertices + edges) and only re-evaluate weights when the input file's characteristics change. This avoids redundant `addVertex`/`addEdge` calls.

16. **Add integration tests for the full pipeline.**
    The codebase contains no test files. At minimum, add:
    - A test that walks a small fixture project (Java + TS + TXT + PDF) and asserts the JSONL output structure.
    - A test for the `DocConversionRouter` Dijkstra path selection with a known agent graph.
    - A test for the veto system with nested `src/target/` paths.
    - A test for the `DefaultLineBreakTextChunker` lossless reassembly.

17. **Address the `Doc2HtmlConversionAgent` side effect.**
    Remove the `PrintWriter` file-write from `convert()`. If a file output is needed, it should be a separate, explicit step in the pipeline (e.g., a `FileWriterAgent` that the router can include in the path).

18. **Make `CostEstimation` a proper enum or record.**
    Replace the interface-with-constants pattern with an enum (`SIMPLE`, `AI_OCR`, `AI_IMAGE`, `IMPOSSIBLE`, `ATOMIC`) or a record with a `double value` field. This enables type-safe cost comparisons and eliminates the magic-number pattern.

---

*Assessment generated for the `de.spraener.prjxp.chuno` (ChunkNorris) module of the PrjXP platform.*

_This document was generated with .dp and gemini-3-flash-preview_

