# Software Architecture Assessment: ChunkNorris – Multi-Format Document & Code Chunking Pipeline

## 1. Executive Summary

ChunkNorris is a Spring Boot application that walks project file trees, applies exclusion vetoes, routes files to language- and format-specific chunkers, builds code knowledge graphs, and emits structured JSONL output (optionally encrypted) for downstream search and embedding pipelines. It supports Java, TypeScript, Visual Basic, plain text, Markdown, PDF, DOCX, RTF, and HTML inputs.

**Key Strengths:**
- Well-defined `LanguagePack` / `PxChunker` abstraction enabling clean per-language extensibility.
- Annotation-driven component discovery (`@Chunker`, `@ChunkVeto`, `@PostWalkChunker`) decouples registration from implementation.
- A Dijkstra-based `DocConversionRouter` with cost/accuracy estimation provides a principled format-conversion pipeline.
- Precision-first code graph construction (JavaParser AST → weighted graph → Leiden clustering) with graceful degradation.
- Multiple fallback strategies (raw line-split, `DefaultLineBreakTextChunker`) ensure no file is silently dropped.

**Most Critical Risks:**
- The `AnnotationBasedChunkerBrokerImpl` performs lazy, non-thread-safe initialization of reflection-based chunker lists, creating a race condition under concurrent access.
- `VetoContext` relies on a `ThreadLocal` that is not propagated into `parallel()` stream forks, risking incorrect veto decisions in multi-threaded walks.
- The `TypeScriptCodeChunker` uses brittle regex-based structural parsing that will silently mis-chunk any non-trivial TypeScript construct.
- `JavaCodeChunker` is a ~400-line God-class accumulating imports, methods, constructors, enum constants, class frames, and meta-chunks in a single `chunk()` call with duplicated logic.
- Inconsistent logging frameworks (`java.util.logging` vs. `slf4j`) and mixed German/English comments reduce maintainability.

## 2. Architectural Style & Patterns

| Pattern | Where Applied | Assessment |
|---------|--------------|------------|
| **Layered / Spring-Boot-Idiomatic** | `ChunkNorris` (app entry) → `ChunkProcess` (orchestrator) → `ChunkerFactory` → individual `PxChunker` implementations | Clean top-down flow; the `CommandLineRunner` bean is the sole entry point. |
| **Strategy / Plugin** | `PxChunker` interface, `LanguagePack` interface, `DocConversionAgent` interface | Each language/format provides its own strategy; the `LanguagePackRegistry` aggregates them. |
| **Registry** | `LanguagePackRegistry`, `VetoRegistry`, `DependencyRegistryManager` | Central lookup points; `VetoRegistry` uses `BeanPostProcessor` for auto-discovery. |
| **Broker / Facade** | `ChunkerBroker` interface, `LanguagePackBroker`, `ChunkerFactory` | `ChunkerFactory` composes multiple brokers (annotation-scanned + language-pack + `ServiceLoader`) behind a single `createChunker(File)` call. |
| **Event-Driven (Spring)** | `SpringPreWalkEvent`, `CliArgsParsingEvent` | Pre-walk phase (FQName map, code graph) is triggered via `ApplicationEventPublisher`; CLI args via a dedicated event. |
| **Pipeline / Chain of Responsibility** | `DocConversionRouter` → ordered `List<DocConversionAgent>` → `DocContentCollector` | Dijkstra pathfinding selects the cheapest conversion chain; agents are applied sequentially. |
| **Factory** | `JavaParserFactory`, `ChunkerFactory` | Centralized parser configuration; chunker assembly with fallback logic. |
| **Template Method (implicit)** | `AnnotationBasedChunkerBrokerImpl` (abstract) → `ChunkerFactory` (concrete) | Base class handles scanning/wrapping; subclass adds language-pack and `ServiceLoader` brokers. |
| **Visitor (implicit)** | `JavaCodeGraphBuilder.process(File)` walks AST nodes | Collects types, methods, fields, calls into graph nodes/edges. |
| **Builder (functional)** | `PxChunk.create(Consumer<PxChunk>...)` | Fluent, composable chunk construction without mutable builder state. |

**Decoupling Assessment:**
- The `common` module (`de.spraener.prjxp.common`) provides shared models (`PxChunk`, `PxChunker`, `CodeGraph`, `LanguagePack`), utilities (`ContentSplitter`, `LineScanner`), and configuration. The `chuno` module is the application-specific implementation. This is a reasonable module boundary.
- However, `ChunkerFactory` extends `AnnotationBasedChunkerBrokerImpl` (which lives in the same module) and also injects `LanguagePackBroker` and `DefaultLineBreakTextChunker` directly, creating a **concrete dependency** on specific chunker implementations rather than a fully abstract registry. The `ServiceLoader<ChunkerBroker>` hook partially mitigates this for external plugins.
- The `VetoRegistry` as a `BeanPostProcessor` is a powerful but **invasive** pattern: every bean in the context is inspected, and veto methods are collected via reflection. This couples the veto mechanism to the Spring container lifecycle.

## 3. Quality Attribute Evaluation

### Maintainability & Readability

- **Naming:** Generally consistent (`PxChunk`, `PxChunker`, `PxFileType`). However, the project mixes German and English comments (e.g., `TypeScriptCodeChunker` Javadoc is German, `JavaCodeChunker` is mixed). The `Pdf2ImageConversionAgent` imports `javax.print.Doc` (unused, likely a copy-paste artifact).
- **SOLID Adherence:**
  - **S:** `JavaCodeChunker` violates SRP – it handles imports, methods, constructors, enum constants, class frames, meta-chunks, graph metadata stamping, and raw fallback in one class (~400 lines). `MarkdownChunker` similarly handles conversion routing, line scanning, table detection, TOC detection, and section splitting.
  - **O:** The `LanguagePack` / `PxChunker` interfaces support Open/Closed for *new languages*. However, adding a new *chunk type within a language* (e.g., a new Java section) requires modifying `JavaCodeChunker` directly.
  - **L:** `DefaultLineBreakTextChunker.matches()` always returns `true`, making it a catch-all that is only safe because `ChunkerFactory` special-cases it. This is a fragile Liskov substitution – any new catch-all chunker would break the `hasSpecific` check.
  - **I:** `PxChunker` is a small, focused interface (2 methods). `DocConversionAgent<T,C>` is also narrow. Good.
  - **D:** Dependencies on abstractions are mostly respected (`LanguagePackRegistry`, `VetoRegistry`, `CodeGraphBuilder`). The `ChunkerFactory`'s direct injection of `DefaultLineBreakTextChunker` is a minor DIP violation.
- **Code Cleanliness:** Use of records (`SpringPreWalkEvent`, `VisualBasicFileInfo`, `ContainerInfo`, `MemberInfo`, `PendingEdge`, `MethodInfo`) is modern and appropriate. Lombok (`@RequiredArgsConstructor`, `@Data`, `@Log`) reduces boilerplate but the `@Log` (java.util.logging) vs. `@Slf4j` (slf4j) inconsistency is a maintenance hazard.

### Extensibility

- **Adding a new language:** Implement `PxChunker`, create a `LanguagePack` `@Component`, register it in `LanguagePackRegistry`. The `LanguagePackBroker` picks it up automatically. **Effort: Low.**
- **Adding a new format conversion:** Implement `DocConversionAgent<S,T>`, register as `@Component`. The `DocConversionRouter` auto-discovers it via `List<DocConversionAgent>` injection. **Effort: Low.**
- **Adding a new veto:** Annotate a method with `@ChunkVeto` in a `@Service` class. `VetoRegistry` (BeanPostProcessor) picks it up. **Effort: Low.**
- **Adding a new chunker via plugin (external JAR):** Implement `ChunkerBroker`, register in `META-INF/services`. `ChunkerFactory` loads via `ServiceLoader`. **Effort: Medium** (requires understanding the broker contract).
- **Replacing the FQName resolution strategy:** `JavaFQNamesMapper` is a concrete `@Component` injected directly. There is no interface, so swapping the resolution algorithm requires modifying the class. **Effort: Medium-High.**
- **Configuration:** Chunk sizes, overlaps, and veto thresholds are externalized via `@Value` / `@ConfigurationProperties`. Good.

### Robustness & Error Handling

- **Positive:**
  - `JavaCodeChunker` catches `ParseProblemException` separately and falls back to `rawFallback()`.
  - `JavaCodeGraphBuilder.process()` catches all exceptions per-file, preventing one bad file from aborting the walk.
  - `DefaultLineBreakTextChunker` and `FullTextChunker` sniff for null bytes to skip binary files.
  - `VisualBasicCodeChunker` falls back to `windows-1252` on `MalformedInputException`.
  - `ChunkProcess.handlePath()` never throws; errors are logged and the file is skipped.
- **Negative:**
  - `TypeScriptCodeChunker.chunk()` catches `Exception` broadly and returns `Stream.of()` – a file with a single parse issue produces *zero* chunks with only a warning log. No fallback to raw text.
  - `RtfToHtmlAgent.convert()` throws `RuntimeException` on any failure, which will propagate up and abort the entire conversion chain for that document.
  - `Doc2HtmlConversionAgent` opens a `PrintWriter` to a file path derived from the input file name but **never closes it in a finally block** (only `flush()` + `close()` in the happy path; the `catch` block throws before closing).
  - `Pdf2ImageConversionAgent.pageCount()` catches `IOException` and returns 0, silently treating a corrupt PDF as "zero pages" rather than surfacing the error.
  - `TextChunker` throws `RuntimeException` on any exception, which will propagate out of the `@Chunker`-annotated method and crash the pipeline for that file.

### Performance & Resource Efficiency

- **Blocking I/O:** All file reads use `Files.readString()` / `Files.readAllLines()` / `BufferedReader` – synchronous, single-threaded per file. The `ChunkProcess` uses `.parallel()` on the chunker stream, so files are processed concurrently, but within a single file the chunking is sequential. For very large files (e.g., a 50 MB PDF), this is a bottleneck.
- **Memory:** `FullTextChunker` and `DefaultLineBreakTextChunker` read the *entire file* into a `String` before splitting. For files approaching the 1 MB veto limit, this is ~2 MB of heap per file. With parallel processing, memory pressure can be significant.
- **Regex Performance:** `TypeScriptCodeChunker` applies 5 compiled patterns per line in a loop. For a 5000-line file, that's 25,000 regex evaluations. Acceptable but not optimal.
- **Graph Construction:** `JavaCodeGraphBuilder` accumulates all nodes/edges/calls in `ArrayList`s, then deduplicates in `build()`. For a large project (10k+ Java files), this is O(N) memory with no streaming. The `ConcurrentHashMap` in `JavaFQNamesMapper` is appropriate for the pre-walk phase.
- **PDF Rendering:** `Pdf2ImageConversionAgent` renders at 300 DPI with `ImageType.BINARY` – each page image can be 10+ MB in memory. The `ImageSupplier` is lazy (renders on `get()`), which is good, but the `PDDocument` stays open until `postConversionAction` fires.
- **`DependencyRegistryManager`** uses a plain `HashMap` (not `ConcurrentHashMap`) for the registry map, but `DependencyRegistry` methods are `synchronized`. The outer map is not thread-safe – a race condition exists if two threads call `get()` concurrently for the same name.

## 4. Strengths & Best Practices

1. **`LanguagePack` Abstraction:** Clean, minimal interface (`language()`, `mimeType()`, `findPxChunkers()`, `listPostWalkChunkers()`, `codeGraph()`) that groups all per-language concerns. Adding a language is a 30-line task.
2. **`JavaParserFactory` as Shared Singleton:** The Javadoc explicitly explains *why* a shared `JavaParser` instance is safe (thread-safe, stateless between calls) and *why* the old `StaticJavaParser` pattern was problematic. This is excellent architectural documentation.
3. **Precision-First Graph Construction:** `JavaCodeGraphBuilder.lookup()` refuses to create a `CALL` edge when multiple overloads match. This is a deliberate, well-documented trade-off (precision over recall) that prevents noisy embeddings.
4. **`ContentSplitter` with Overlap:** A reusable utility for size-based splitting with configurable overlap, used consistently across all chunkers. The `DefaultLineBreakTextChunker`'s line-aware splitting (never splitting mid-line, tracking `fromLine`/`toLine`) is well-implemented.
5. **Veto System with `BeanPostProcessor`:** The `VetoRegistry` auto-discovers all `@ChunkVeto`-annotated methods across all beans. Adding a new exclusion rule is a single method annotation – no registration code needed.
6. **Dijkstra-Based Conversion Routing:** `DocConversionRouter` models format conversions as a weighted directed graph and finds the cheapest path, with an accuracy surcharge for lossy conversions. This is a principled, extensible approach to multi-step format conversion.
7. **`rebaseIdToProjectPath` in `ChunkProcess`:** A well-documented, targeted fix for a real-world ID collision problem (same-named files in different directories). The Javadoc explains the *why* clearly.
8. **`SpringPreWalkEvent` as a Record:** Immutable, type-safe event carrying both the config and the project definition. Clean separation of the pre-walk phase from the main walk.
9. **Binary File Detection:** Both `FullTextChunker` and `DefaultLineBreakTextChunker` sniff the first 8 KB for null bytes before attempting text processing. Simple, effective, and avoids encoding exceptions.
10. **`DocArtifakt` Tree Structure:** The parent-child artifact tree with `postConversionAction` (for resource cleanup like closing PDF documents) is a clean way to manage multi-step conversion lifecycles.

## 5. Identified Risks & Technical Debt

### Critical

| # | Risk | Location | Impact |
|---|------|----------|--------|
| 1 | **Thread-unsafe lazy initialization** in `AnnotationBasedChunkerBrokerImpl`: `chunkers` and `postWalkChunkers` are initialized on first call without synchronization. Under `parallel()` streams, two threads can trigger `initChunkers()` simultaneously, resulting in a partially-populated list. | `AnnotationBasedChunkerBrokerImpl` | Silent chunker loss; non-deterministic behavior. |
| 2 | **`ThreadLocal` in `VetoContext` not propagated to parallel stream forks.** `VetoRegistry.shouldVeto(path, definition)` sets the ThreadLocal, but `ChunkProcess` uses `.parallel()` on the file stream. Forked threads in the common pool will see `null` from the ThreadLocal, causing vetos that depend on project-specific config (whitelist, exclude dirs) to silently fail. | `VetoContext`, `ChunkProcess.handlePath` | Files that should be excluded (e.g., `node_modules`) may be indexed; whitelist enforcement is bypassed. |
| 3 | **`DependencyRegistryManager` uses a non-thread-safe `HashMap`.** If two parallel chunking threads call `get("JAVA_DEPENDENCIES")` simultaneously, the map can be corrupted. | `DependencyRegistryManager` | ConcurrentModificationException or lost entries. |

### High

| # | Risk | Location | Impact |
|---|------|----------|--------|
| 4 | **Regex-based TypeScript parsing** is fundamentally fragile. The `METHOD_PATTERN`, `CLASS_PATTERN`, etc. will mis-match multi-line signatures, template literals, decorators, or any non-standard formatting. There is no AST-based parser (unlike Java's JavaParser). | `TypeScriptCodeChunker` | Incorrect chunk boundaries, missing methods, corrupted IDs. |
| 5 | **`JavaCodeChunker` is a God-class** (~400 lines) with 7+ distinct responsibilities: import chunking, method chunking, constructor chunking, enum constant chunking, class frame chunking, meta chunking, graph metadata stamping, and raw fallback. The `createContainedMethodChunk` method alone is 80+ lines with duplicated Javadoc/implementation logic for methods and constructors. | `JavaCodeChunker` | Difficult to test, modify, or extend. High cognitive load. |
| 6 | **`MarkdownChunker` has complex, stateful line-by-line parsing** with mutable `StringBuilder`, `int[]` counters, and a `LineScanner` with a sliding window. The `isEndOfParagraph` logic (current line blank, next non-empty is a header) is fragile and will misbehave on documents with unusual formatting. | `MarkdownChunker` | Incorrect section boundaries; content attributed to wrong sections. |
| 7 | **Inconsistent logging frameworks.** `@Log` (java.util.logging) is used in most classes; `@Slf4j` (slf4j) in `AnnotationBasedChunkerBrokerImpl`. The `MetaInfReader` uses `@Log` but also injects `PxLogService`. Three different logging mechanisms coexist. | Multiple | Log aggregation, filtering, and level management are fragmented. |
| 8 | **`Doc2HtmlConversionAgent` leaks file handles.** The `PrintWriter` is opened but only closed in the happy path. If `parser.parse()` throws, the `PrintWriter` is never closed. | `Doc2HtmlConversionAgent` | File descriptor leak; potential file lock issues on Windows. |

### Medium

| # | Risk | Location | Impact |
|---|------|----------|--------|
| 9 | **`Pdf2ImageConversionAgent` imports `javax.print.Doc`** (an unused, legacy JNDI/printing class). This is a copy-paste artifact that will confuse readers and may cause classpath issues. | `Pdf2ImageConversionAgent` | Confusion; potential classloading issues. |
| 10 | **`PdfChapterChunker` is entirely commented out** (`//@Component`, `//@ChunkNorrisComponent`) but remains in the source tree with `System.out.println` debug statements. | `PdfChapterChunker` | Dead code; maintenance confusion. |
| 11 | **`JavaCodeChunker.createMetaChunk()` returns `List.of()`** – a stub that does nothing. It is called in `chunk()` but produces no output. | `JavaCodeChunker` | Dead code; suggests an incomplete feature. |
| 12 | **`TextChunker` uses Groovy scripting** (`ScriptCompileService`) to evaluate user-provided `.groovy` files. This is a code-execution vector if the project directory is not fully trusted. | `TextChunker` | Security risk in multi-tenant or untrusted-repo scenarios. |
| 13 | **`DocArtifakt` uses raw types and unchecked casts** in `DocContentCollector.collectContent()` (`(DocArtifakt) child`). The generic type parameter `C` is not properly propagated. | `DocContentCollector`, `DocArtifakt` | Type-unsafety; potential `ClassCastException` at runtime. |
| 14 | **`FullTextChunker` and `DefaultLineBreakTextChunker` both read the entire file into memory** as a UTF-8 `String`. For the 1 MB veto limit, this is ~2 MB per file. With 100 parallel files, that's 200 MB of transient heap. | Both chunkers | GC pressure; potential OOM on large projects. |
| 15 | **`ChunkProcess.processedFiles` is a `HashSet`** used as a deduplication mechanism, but it is only checked in `executeForProject` (the full walk), not in `executeForFiles` (the hub path). The hub path relies on the caller to not pass duplicates. | `ChunkProcess` | Potential duplicate chunks in hub mode. |

### Low

| # | Risk | Location | Impact |
|---|------|----------|--------|
| 16 | **Mixed German/English comments** reduce accessibility for non-German-speaking contributors. | `TypeScriptCodeChunker`, `JavaCodeChunker`, `MarkdownChunker`, `DocConversionRouter` | Onboarding friction. |
| 17 | **`VisualBasicCodeChunker` has a redundant field initializer** (`private int chunkSize = 1300;` alongside `@Value("${visualbasic.chunksize:1300}")`). The `@Value` will override the initializer, but the initializer is misleading. | `VisualBasicCodeChunker` | Confusion. |
| 18 | **`ConversionRoutesConfig` is annotated with `@Component` and `@Data`** but also has a manual constructor. The `@Data` generates a no-arg constructor that conflicts with the explicit one. | `ConversionRoutesConfig` | Potential Spring wiring issues. |

## 6. Actionable Recommendations

### Priority 1 – Fix Correctness & Safety (Do First)

1. **Make `AnnotationBasedChunkerBrokerImpl` thread-safe.** Replace the lazy `if (chunkers == null) initChunkers()` pattern with `volatile` + double-checked locking, or better, initialize in the constructor / `@PostConstruct`. Since `ChunkerFactory` is a singleton `@Service`, eager initialization in the constructor is simplest and eliminates the race entirely.

2. **Fix `VetoContext` ThreadLocal propagation for parallel streams.** Either:
   - (a) Pass the `ProjectDefinition` explicitly through the stream pipeline (e.g., `forEach(path -> { vetoContext.set(pd); try { ... } finally { vetoContext.clear(); } })` inside the parallel lambda), or
   - (b) Replace the `ThreadLocal` with an explicit parameter on `VetoRegistry.shouldVeto(Path, ProjectDefinition)` and eliminate the ThreadLocal entirely. Option (b) is cleaner and already partially implemented – just remove the ThreadLocal fallback in `StandardVetos` and `DirectoryVetos`.

3. **Make `DependencyRegistryManager` thread-safe.** Change `HashMap` to `ConcurrentHashMap` and use `computeIfAbsent` for the registry lookup. The inner `DependencyRegistry` is already `synchronized`, so only the outer map needs fixing.

4. **Fix `Doc2HtmlConversionAgent` resource leak.** Wrap the `PrintWriter` in a try-with-resources block.

5. **Remove the unused `javax.print.Doc` import** from `Pdf2ImageConversionAgent`.

### Priority 2 – Reduce Complexity & Improve Maintainability

6. **Decompose `JavaCodeChunker`** into focused collaborator classes:
   - `JavaImportChunker` (import range → chunks)
   - `JavaMethodChunker` (methods + constructors + Javadoc → chunks; extract the duplicated method/constructor logic into a shared `createMemberChunks` template)
   - `JavaEnumConstantChunker`
   - `JavaClassFrameChunker`
   - `JavaGraphMetadataApplier` (the `applyGraphMetadata` logic)
   - `JavaRawFallback` (the `rawFallback` method)
   
   `JavaCodeChunker` becomes a thin orchestrator that delegates to these. This also makes each concern independently testable.

7. **Replace `TypeScriptCodeChunker`'s regex parsing with an AST-based approach.** Options:
   - Use the TypeScript compiler API via a Java bridge (e.g., `ts-java` or a Node.js subprocess).
   - Use a lightweight Java-based TS parser (e.g., `com.github.javaparser` does not support TS, but `com.intellij`'s TypeScript parser or `tree-sitter` with a Java binding could work).
   - At minimum, add a **raw-text fallback** (like `JavaCodeChunker` has) so that a regex failure doesn't produce zero chunks.

8. **Unify logging to SLF4J.** Replace all `@Log` (java.util.logging) annotations with `@Slf4j` (slf4j). Configure the logging backend (Logback) in `application.yml`. Remove the `PxLogService` dependency where it is used solely for `log.error` calls (SLF4J handles this natively). Keep `PxLogService` only if it provides structured logging or external log forwarding.

9. **Standardize comments to English.** Translate German Javadoc and inline comments to English for broader team accessibility. Keep German only if the team is exclusively German-speaking (in which case, document this convention in a `CONTRIBUTING.md`).

### Priority 3 – Improve Extensibility & Architecture

10. **Introduce a `FQNameResolver` interface** to abstract the `JavaFQNamesMapper` dependency. This allows swapping the resolution strategy (e.g., a symbol-table-based resolver, a build-tool-integrated resolver) without modifying `JavaCodeGraphBuilder` or `JavaCodeChunker`.

11. **Extract the `MarkdownChunker`'s parsing logic** into a dedicated `MarkdownStructureParser` class that produces a tree of `MarkdownSection` objects. The chunker then walks the tree to produce `PxChunk`s. This separates the fragile line-scanning logic from the chunk-assembly logic and makes the parser independently testable.

12. **Address the `DocArtifakt` raw-type issue.** Either:
    - Use a non-generic `DocArtifakt` with `Object data` and type-safe accessor methods, or
    - Properly propagate the generic type parameter through `DocContentCollector` (use `DocArtifakt<?, ?>` consistently and avoid the unchecked cast).

13. **Remove or complete dead code:**
    - Delete `PdfChapterChunker` (fully commented out, contains `System.out.println`).
    - Either implement `JavaCodeChunker.createMetaChunk()` or remove the call and the method.
    - Remove the redundant field initializer in `VisualBasicCodeChunker`.

### Priority 4 – Performance & Operational Hardening

14. **Stream large files instead of reading them entirely into memory.** For `FullTextChunker` and `DefaultLineBreakTextChunker`, consider a line-by-line reading approach (like `JavaCodeChunker` already does with `Files.readAllLines`) to cap per-file memory at one line + the current part buffer, rather than the entire file content.

15. **Add a configurable parallelism cap** to `ChunkProcess`. The current `.parallel()` uses the common ForkJoinPool (CPU-core-count threads). For I/O-bound file reading, a larger pool or a dedicated `ExecutorService` with a configurable thread count would be more appropriate.

16. **Add integration tests** for the full pipeline:
    - A test project with Java, TypeScript, VB, PDF, DOCX, and TXT files.
    - Verify that vetos exclude `node_modules`, `target`, hidden files.
    - Verify that the JSONL output contains the expected chunk types, IDs, and metadata.
    - Verify that the code graph is built and Leiden metadata is stamped.
    - Verify that the conversion router selects the correct path for each format.

17. **Add a `@ConditionalOnProperty` or profile gate for the Groovy scripting in `TextChunker`.** The ability to execute arbitrary Groovy scripts from the project directory is a significant security risk. At minimum, gate it behind an explicit opt-in property (`prjxp.text.groovy.enabled=true`) and document the risk.

18. **Consider replacing `Files.walk` with a streaming, depth-limited walk** for very large projects. The current approach materializes all paths in memory (the `Stream` from `Files.walk` is lazy, but the `.filter().filter().filter().sorted()` chain may buffer). For projects with 100k+ files, a `FileVisitor`-based approach with early veto pruning would be more memory-efficient.

_This document was generated with .dp and gemini-3-flash-preview_

