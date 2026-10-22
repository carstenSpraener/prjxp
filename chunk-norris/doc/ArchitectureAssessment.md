# Software Architecture Assessment: ChunkNorris (PrjXP Document & Code Chunking Engine)

## 1. Executive Summary

ChunkNorris is a Spring Boot application within the PrjXP ecosystem that transforms source code files and documents into semantically structured chunks (JSONL) for downstream search, embedding, and retrieval pipelines. The system employs a multi-strategy chunking architecture: language-specific code chunkers (Java, TypeScript, Visual Basic), a document conversion pipeline with Dijkstra-based routing, and a fallback text chunker.

**Key Strengths:**
- Well-defined extension points via `LanguagePack`, `PxChunker`, and annotation-driven discovery
- Clever Dijkstra-based conversion routing with cost/accuracy trade-offs
- Clean separation between the chunking pipeline, veto system, and document conversion
- Thoughtful fallback semantics (DefaultLineBreakTextChunker, raw fallback in JavaCodeChunker)

**Most Critical Risks:**
- **Thread-safety defects** in `AnnotationBasedChunkerBrokerImpl` (unsynchronized lazy init), `DependencyRegistryManager` (plain `HashMap`), and `StaticJavaParser` global configuration mutation
- **Resource leaks** in `Pdf2ImageConversionAgent` (inner non-static classes, unclosed streams in `RtfToHtmlAgent`)
- **Over-complexity** in `MarkdownChunker` (single class handles line scanning, table detection, section hierarchy, TOC detection, and chunk assembly)
- **Dead code and commented-out components** (`PdfChapterChunker`, `Text2MDConversionAgent` no-op) increasing maintenance surface

## 2. Architectural Style & Patterns

| Pattern | Where Applied | Assessment |
|---------|--------------|------------|
| **Layered Pipeline** | `ChunkProcess` → `ChunkerFactory` → `PxChunker` implementations → JSONL output | Clear unidirectional flow; well-separated |
| **Strategy / Plugin** | `LanguagePack` implementations (`JavaChunkerPack`, `TypeScriptChunkerPack`, `VisualBasicChunkerPack`) | Good extensibility; new languages require a pack + chunker |
| **Annotation-Driven Discovery** | `@Chunker`, `@PostWalkChunker`, `@ChunkNorrisComponent`, `@ChunkVeto` | Flexible but introduces reflection-based coupling; discovery is deferred and non-deterministic in ordering |
| **Graph-Based Routing** | `DocConversionRouter` with JGraphT Dijkstra | Elegant for multi-step format conversion; cost model is extensible |
| **Tree / Composite** | `DocArtifakt<T,C>` with `childs` list | Supports fan-out (PDF → N page images) and fan-in (collect text) |
| **Event-Driven** | `SpringPreWalkEvent`, `CliArgsParsingEvent` | Decouples pre-walk preparation (FQ name mapping) from the main walk |
| **Template Method** | `AnnotationBasedChunkerBrokerImpl` (abstract) → `ChunkerFactory` | Reasonable, but the abstract class mixes discovery logic with broker contract |
| **Thread-Local Context** | `VetoContext` | Pragmatic for passing project definition through veto chain; risk of leaks in async contexts |
| **ServiceLoader (SPI)** | `ChunkerFactory.initBrokerList()` | Allows external JARs to contribute chunkers without Spring coupling |

**Decoupling Assessment:**
- The `ChunkerBroker` interface provides a clean seam between the process and chunker discovery.
- `LanguagePackBroker` bridges the common `LanguagePackRegistry` to the `ChunkerBroker` contract — good adapter usage.
- However, `ChunkerFactory` extends `AnnotationBasedChunkerBrokerImpl` *and* composes `LanguagePackBroker` *and* `DefaultLineBreakTextChunker` — it is both a broker and a composite, violating single responsibility.
- The document conversion subsystem (`DocConversionRouter`, agents, `DocArtifakt`) is well-isolated from the code chunking subsystem, communicating only through `MarkdownChunker`.

## 3. Quality Attribute Evaluation

### Maintainability & Readability

**Positive:**
- Consistent use of Lombok (`@RequiredArgsConstructor`, `@Data`) reduces boilerplate.
- Records for value objects (`SpringPreWalkEvent`, `VisualBasicFileInfo`, `ContainerInfo`, `MemberInfo`).
- Javadoc on key classes explains *why* (e.g., `DefaultLineBreakTextChunker` explains its non-registration rationale).
- Clear package structure: `code.java`, `code.typescript`, `code.visualbasic`, `docs.pdf`, `docs.html`, `docs.poi`, `veto`, `language`.

**Negative:**
- **Mixed logging frameworks:** `java.util.logging` (`@Log`), `slf4j` (`@Slf4j`), and a custom `PxLogService` coexist. This fragments log configuration and makes debugging harder.
- **`MarkdownChunker`** is a 200+ line class with 15+ private methods, magic numbers (window size 4, 40-char threshold, 500/100 splitter params), and inline regex compilation per line. It is the least maintainable component.
- **`Pdf2ImageConversionAgent`** imports `javax.print.Doc` (a JNDI/printing class) — clearly an IDE auto-import error that will confuse readers.
- **`ConversionRoutesConfig`** imports `javax.swing.*` — another spurious import.
- **`TextChunker`** uses a `ValueContainer<BiConsumer<...>>` to pass a mutable lambda — an over-engineered indirection that obscures intent.
- German and English comments are mixed throughout (e.g., "Fehler bei RTF -> HTML Konvertierung" vs. "binary file — skip"), reducing consistency for international teams.
- `DocArtifakt` uses `@Data` which generates `equals()`/`hashCode()` over the `data` field (potentially a multi-MB `BufferedImage` or `String`), making any accidental use in a `HashSet`/`HashMap` a performance bomb.

### Extensibility

**Positive:**
- Adding a new language requires: (1) a `PxChunker` implementation, (2) a `LanguagePack` registration, (3) optionally a `PxFileType` entry. The `LanguagePackBroker` auto-discovers all packs.
- `ServiceLoader<ChunkerBroker>` allows external JARs to inject additional brokers.
- `DocConversionAgent` is a clean interface; new format conversions are plug-and-play.
- `@ChunkVeto` annotation + `BeanPostProcessor` discovery means new veto rules are a single annotated method.
- `ConversionRoutesConfig` models predefined routes (`ConversionRoute` with `@ConfigurationProperties`), bypassing Dijkstra for known paths — though the `predefinedRoutes` list is not currently bound from the shipped `application.yaml`, so the fast path is inactive by default.

**Negative:**
- The `AnnotationBasedChunkerBrokerImpl` hardcodes the discovery mechanism (classpath scan for `@ChunkNorrisComponent`). Adding a new discovery strategy (e.g., SPI-based, config-based) requires modifying this class.
- `ChunkerFactory` special-cases `FullTextChunker` by class identity (`instanceof FullTextChunker`). A new catch-all chunker would require code changes here — the comment acknowledges this.
- `DocConversionRouter` builds the graph on every `findBestPath()` call. For a large number of agents, this is wasteful and makes it harder to add dynamic edge weights without rebuilding.
- The `PxChunker` interface is minimal (`matches`, `chunk`), but the `AnnotationBasedChunkerBrokerImpl` also supports method-level `@Chunker` annotations with a `fileTypes` attribute — two parallel registration mechanisms that can be confusing.

### Robustness & Error Handling

**Critical Issues:**
- **`AnnotationBasedChunkerBrokerImpl.initChunkers()` / `initPostWalkChunkers()`** are called lazily from `findPxChunkers()` / `listPostWalkChunker()` without any synchronization. In a multi-threaded context (`.parallel()` streams in `ChunkProcess`), two threads can trigger concurrent initialization, leading to duplicate or incomplete chunker lists.
- **`DependencyRegistryManager`** uses a plain `HashMap` for `dependencyRegistryMap`. The `get()` method performs a check-then-act (`if null → create → put`) without synchronization. Under concurrent access (`.parallel()` in `ChunkProcess`), this is a race condition.
- **`JavaFQNamesMapper.fillFQNamesMap()`** catches all exceptions with a `// FIXME: handle exceptions!!!` comment. A failure here silently produces an empty FQ-name map, degrading all subsequent Java chunking without alerting the operator.
- **`JavaCodeChunker.chunk()`** calls `StaticJavaParser.getConfiguration().setLanguageLevel(...)` — this mutates a **global singleton**. In a parallel stream, concurrent calls can corrupt the parser state for other threads.
- **`RtfToHtmlAgent.convert()`** creates a `DocArtifakt` (`htmlArt`) but never calls `input.addChild(htmlArt)`. The converted HTML is silently lost.
- **`Pdf2ImageConversionAgent`** inner classes (`Pdf2ImageContext`, `ImageSupplier`) are non-static inner classes of a `@Component`. They hold an implicit reference to the outer agent instance. The `Pdf2ImageContext` implements `AutoCloseable` but is never explicitly closed in the `convert()` method's normal path (only via `postConversionAction`). If the post-conversion action is never invoked, the `PDDocument` leaks.
- **`Doc2HtmlConversionAgent`** writes a `.html` file to disk as a side effect of conversion. This is a hidden I/O operation that can fail (permissions, disk full) and is not part of the `DocArtifakt` tree.

**Positive:**
- `JavaCodeChunker` has a well-structured fallback: `ParseProblemException` → log problems → `rawFallback()`.
- `VisualBasicCodeChunker` handles encoding fallback (UTF-8 → windows-1252).
- `DefaultLineBreakTextChunker` and `FullTextChunker` gracefully return empty streams for binary files (null-byte sniffing).
- `VetoRegistry.VetoMethodWrapper.check()` catches all exceptions and returns `false` (fail-open for vetos) — a reasonable default.

### Performance & Resource Efficiency

**Bottlenecks:**
- **`Files.readString()`** in `DefaultLineBreakTextChunker` and `FullTextChunker` loads the entire file into memory. For large files (the veto limits to 1 MB by default, but this is configurable), this is acceptable. However, `FullTextChunker` then calls `content.split("\n", -1)` which creates a new `String[]` of all lines — doubling memory usage.
- **`JavaCodeChunker`** reads the file twice: once via `Files.readAllLines()` and once via `StaticJavaParser.parse(f)` (which re-reads the file). The `codeLines` list is then used for line-range extraction, but the parser also reads the file independently.
- **`DocConversionRouter.estimateConversionCosts()`** is recursive and creates a new `DocArtifakt` at each step. For a graph with many intermediate formats, this can explode combinatorially. The `visited` set prevents infinite loops but not exponential paths.
- **`AnnotationBasedChunkerBrokerImpl`** performs a full classpath scan (`ClassPathScanningCandidateComponentProvider`) on first use. This is a one-time cost but can be 100s of ms for large classpaths.
- **`MarkdownChunker`** compiles a `Pattern` on every call to `preprocessLine()` (per line). This should be a `static final` compiled pattern.
- **`Pdf2ImageConversionAgent`** renders at 300 DPI with `ImageType.BINARY` — for a 100-page PDF, this creates 100 `BufferedImage` objects (each ~10-50 MB at 300 DPI). The lazy `ImageSupplier` helps, but all pages are created as `DocArtifakt` children upfront.
- **`ChunkProcess.handlePath()`** uses `.parallel()` on the chunker stream. Combined with the thread-safety issues above, this is a correctness risk, not just a performance one.

**Resource Leaks:**
- `RtfToHtmlAgent`: `FileInputStream` is in a try-with-resources (good), but the `DocArtifakt` is never attached to the parent.
- `Pdf2ImageConversionAgent`: `PDDocument` is closed via `postConversionAction`, but if the conversion pipeline is interrupted, the document leaks.
- `Doc2HtmlConversionAgent`: `PrintWriter` is not in a try-with-resources (uses manual `flush()`/`close()`).
- `Pdf2MDWithTikaDocConversionAgent`: `FileInputStream` is never closed (no try-with-resources).

## 4. Strengths & Best Practices

1. **LanguagePack Abstraction:** The `LanguagePack` interface cleanly groups chunkers by language, with `findPxChunkers(File)` and `listPostWalkChunkers()` providing a uniform contract. The `LanguagePackBroker` adapter bridges this to the `ChunkerBroker` interface without coupling.

2. **Dijkstra-Based Conversion Routing:** Using JGraphT with a cost model (including accuracy surcharges) is an elegant solution to the "how do I get from format A to format B" problem. The `ConversionAccuracy` enum provides a principled way to penalize lossy paths.

3. **Veto System Design:** The `@ChunkVeto` + `BeanPostProcessor` discovery pattern is clean. The `VetoContext` ThreadLocal allows project-scoped vetos without polluting method signatures. The `DirectoryVetos.scan()` algorithm (monotonic source-tree flag) is well-reasoned and documented.

4. **Fallback Semantics:** The `DefaultLineBreakTextChunker` is deliberately *not* registered as a `@Chunker` — it's injected into `ChunkerFactory` and only activated when no specific chunker matches. This prevents it from shadowing specialized chunkers.

5. **JavaCodeChunker Fallback:** The `rawFallback()` method ensures that even unparseable Java files produce searchable chunks (line-based, no semantics), rather than being silently dropped.

6. **`DocArtifakt` Tree with `postConversionAction`:** The deferred cleanup mechanism (e.g., closing a `PDDocument` after all page images are consumed) is a practical solution to the "when do I release this resource" problem in a pipeline.

7. **`rebaseIdToProjectPath()` in `ChunkProcess`:** The ID rebasing logic (basename → project-relative path) is a well-thought-out solution to the ID collision problem for same-named files in different directories.

8. **`DependencyRegistry` with `synchronized addDependency()`:** The write path is thread-safe. The read path (`getDependencies`, `getUsedBy`) returns the raw (mutable) `HashSet` — safe for concurrent reads only after the write phase completes (happens-before via the stream pipeline).

9. **`VisualBasicCodeChunker` Records:** Using Java records (`VisualBasicFileInfo`, `ContainerInfo`, `MemberInfo`) for internal value objects is modern and clean.

10. **`ChunkNorris` Application Entry Point:** The `@ConditionalOnProperty` and `@Profile("!test")` on the `CommandLineRunner` bean allow the same application class to be used in tests without triggering the pipeline.

## 5. Identified Risks & Technical Debt

### Critical (Correctness / Data Integrity)

| # | Location | Issue |
|---|----------|-------|
| C1 | `AnnotationBasedChunkerBrokerImpl` | Unsynchronized lazy initialization of `chunkers`/`postWalkChunkers` lists. Under `.parallel()` streams, concurrent `findPxChunkers()` calls can cause `ConcurrentModificationException` or duplicate entries. |
| C2 | `DependencyRegistryManager` | Plain `HashMap` with check-then-act in `get()`. Race condition under parallel chunking. |
| C3 | `JavaCodeChunker.chunk()` | Mutates `StaticJavaParser.getConfiguration()` (global singleton). Thread-unsafe under parallel streams. |
| C4 | `RtfToHtmlAgent.convert()` | Creates `htmlArt` but never calls `input.addChild(htmlArt)`. Converted content is silently discarded. |
| C5 | `Pdf2ImageConversionAgent` | `Pdf2ImageContext` (holding `PDDocument`) is only closed via `postConversionAction`. If the pipeline is interrupted or the action is never called, the PDF document handle leaks. |
| C6 | `JavaFQNamesMapper.fillFQNamesMap()` | Swallows all exceptions (`// FIXME`). A parse failure on one file aborts the entire `Files.walk` stream, leaving the FQ-name map incomplete. |

### High (Maintainability / Extensibility)

| # | Location | Issue |
|---|----------|-------|
| H1 | `MarkdownChunker` | God-class: handles line scanning, table detection, section hierarchy, TOC detection, paragraph splitting, and chunk assembly. 200+ lines, 15+ methods, magic numbers. |
| H2 | `ChunkerFactory` | Extends `AnnotationBasedChunkerBrokerImpl` AND composes `LanguagePackBroker` AND special-cases `FullTextChunker` by `instanceof`. Three responsibilities in one class. |
| H3 | `DocArtifakt` | `@Data` generates `equals()`/`hashCode()` over `data` (can be a multi-MB object). Also, the generic `C` parameter (child data type) is confusing and leads to raw-type casts in `DocContentCollector`. |
| H4 | `PdfChapterChunker` | Entirely commented out (`//@Component`, `//@Chunker`) but still in the source tree. Dead code with `System.out.println` debug statements. |
| H5 | `Text2MDConversionAgent` | A no-op conversion (copies data, changes format label). Adds a node to the graph that does nothing, potentially confusing the Dijkstra router. |
| H6 | Mixed logging | `java.util.logging` (`@Log`), `slf4j` (`@Slf4j`), and `PxLogService` used interchangeably. No unified logging strategy. |
| H7 | `ConversionRoutesConfig` | Imports `javax.swing.*` (spurious). Also, `@ConfigurationProperties` is on `ConversionRoute` (the element) but the `predefinedRoutes` list on `ConversionRoutesConfig` is never bound from the shipped `application.yaml` — so the predefined-route fast path is effectively dead in production (only exercised in tests via a manual `@Bean`). |

### Medium (Performance / Resource Management)

| # | Location | Issue |
|---|----------|-------|
| M1 | `FullTextChunker` / `DefaultLineBreakTextChunker` | `content.split("\n", -1)` creates a full `String[]` in addition to the original `String`. For a 1 MB file, this is ~2 MB transient allocation per file. |
| M2 | `MarkdownChunker.preprocessLine()` | Compiles a `Pattern` on every invocation (per line). Should be `static final`. |
| M3 | `DocConversionRouter.buildGraph()` | Rebuilds the entire graph on every `findBestPath()` call. Should be cached or built once. |
| M4 | `Pdf2MDWithTikaDocConversionAgent` | `FileInputStream` is never closed (no try-with-resources). |
| M5 | `Doc2HtmlConversionAgent` | `PrintWriter` not in try-with-resources. Also, writing to disk is a hidden side effect. |
| M6 | `Pdf2ImageConversionAgent` | Inner classes are non-static, holding implicit reference to the `@Component` outer instance. This prevents GC of the agent if a `Pdf2ImageContext` outlives it. |

### Low (Code Hygiene)

| # | Location | Issue |
|---|----------|-------|
| L1 | `Pdf2ImageConversionAgent` | Imports `javax.print.Doc` — clearly an IDE auto-import error. |
| L2 | `TextChunker` | `ValueContainer<BiConsumer<...>>` for a single mutable lambda — over-engineered. |
| L3 | `DocContentCollector` | Unchecked cast `(DocArtifakt) child` — raw type usage. |
| L4 | `JavaCodeChunker` | `createMetaChunk()` returns `List.of()` — dead method. |
| L5 | `TextDocChunkContext` | Field name `scriptSate` (typo for `scriptState`). |
| L6 | `MarkdownChunker` | `isTabelStart` (typo for `isTableStart`). |

## 6. Actionable Recommendations

### Priority 1: Fix Correctness Bugs (Immediate)

1. **Synchronize `AnnotationBasedChunkerBrokerImpl` initialization.**
   - Replace lazy `if (chunkers == null) initChunkers()` with `volatile` + double-checked locking, or better: initialize in the constructor / `@PostConstruct`. Since `ChunkerFactory` is a singleton `@Service`, eager initialization in the constructor is safe and eliminates the race.

2. **Make `DependencyRegistryManager` thread-safe.**
   - Replace `HashMap` with `ConcurrentHashMap` and use `computeIfAbsent()` for the check-then-act pattern.

3. **Eliminate `StaticJavaParser` global mutation in `JavaCodeChunker`.**
   - Create a dedicated `JavaParser` instance per chunk call (or per thread via `ThreadLocal`), configured with the desired language level. Never mutate the global `StaticJavaParser` configuration.

4. **Fix `RtfToHtmlAgent` to attach the result.**
   - Add `input.addChild(htmlArt)` after creating the `DocArtifakt`.

5. **Ensure `Pdf2ImageContext` is always closed.**
   - Wrap the page-iteration loop in a try-with-resources on `Pdf2ImageContext`, or register the close in a `finally` block. Do not rely solely on `postConversionAction`.

6. **Handle `JavaFQNamesMapper` exceptions per-file.**
   - Move the try-catch inside the `forEach` lambda so one bad file doesn't abort the entire walk. Log a warning and continue.

### Priority 2: Refactor High-Risk Components (Sprint 1-2)

7. **Decompose `MarkdownChunker`.**
   - Extract `MarkdownLinePreprocessor` (table detection, header normalization, section regex).
   - Extract `MarkdownSectionTracker` (hierarchy, counters, TOC detection).
   - Extract `MarkdownParagraphAssembler` (paragraph buffering, splitting).
   - The main class should orchestrate: read lines → preprocess → track sections → assemble paragraphs → split into chunks.
   - Make the `Pattern` a `static final` field.

8. **Separate `ChunkerFactory` responsibilities.**
   - `ChunkerFactory` should only compose brokers and apply the fallback logic.
   - Move the `instanceof FullTextChunker` special-case to a `CatchAllChunker` marker interface or a `@CatchAll` annotation, so the factory can filter by type rather than class identity.
   - Consider making `ChunkerFactory` a pure composition (no inheritance from `AnnotationBasedChunkerBrokerImpl`) — it can delegate to an `AnnotationBasedChunkerBroker` instance.

9. **Fix `DocArtifakt` generics and `@Data`.**
   - Replace `@Data` with `@Getter` + `@Setter` + explicit `equals()`/`hashCode()` on `id` only (exclude `data`, `childs`).
   - Resolve the raw-type cast in `DocContentCollector` by using a wildcard or a non-generic `DocArtifakt<?>` parameter.

10. **Unify logging.**
    - Standardize on `slf4j` (or `PxLogService` if it's the project's canonical logger). Remove `java.util.logging` `@Log` annotations. Configure a single logging backend.

### Priority 3: Performance & Resource Management (Sprint 2-3)

11. **Cache the conversion graph in `DocConversionRouter`.**
    - Build the graph once in the constructor (or `@PostConstruct`). Rebuild only if agents are dynamically registered (unlikely in a Spring Boot app).
    - Cache Dijkstra results per `(start, end, accuracy)` tuple.

12. **Avoid double file reads in `JavaCodeChunker`.**
    - Pass the already-read `codeLines` (or the file content string) to the parser, or use `StaticJavaParser.parse(String)` instead of `StaticJavaParser.parse(File)`.

13. **Close all streams with try-with-resources.**
    - `Pdf2MDWithTikaDocConversionAgent`: wrap `FileInputStream` in try-with-resources.
    - `Doc2HtmlConversionAgent`: wrap `PrintWriter` in try-with-resources.
    - `RtfToHtmlAgent`: already uses try-with-resources for `FileInputStream` (good), but ensure the `DocArtifakt` is attached.

14. **Make `Pdf2ImageConversionAgent` inner classes static.**
    - `Pdf2ImageContext` and `ImageSupplier` should be `static` nested classes (or top-level) to avoid implicit outer-instance references.

### Priority 4: Code Hygiene & Dead Code (Ongoing)

15. **Remove or properly implement `PdfChapterChunker`.**
    - If it's not ready, move it to a `src/experimental` or `src/scratch` directory. Remove the `System.out.println` debug statements.

16. **Remove or implement `Text2MDConversionAgent`.**
    - A no-op conversion agent that just relabels the format is confusing. If it's a placeholder for a future real conversion, add a `@Deprecated` annotation and a TODO. Otherwise, remove it.

17. **Fix typos and spurious imports.**
    - `scriptSate` → `scriptState` in `TextDocChunkContext`.
    - `isTabelStart` → `isTableStart` in `MarkdownChunker`.
    - Remove `javax.print.Doc` import from `Pdf2ImageConversionAgent`.
    - Remove `javax.swing.*` import from `ConversionRoutesConfig`.

18. **Remove dead method `createMetaChunk()` in `JavaCodeChunker`.**
    - It returns `List.of()` and is called but contributes nothing. Remove the call and the method.

19. **Add unit tests for critical paths.**
    - `ChunkerFactory.createChunker()` fallback logic (specific vs. catch-all vs. default).
    - `DocConversionRouter` path selection with multiple agents.
    - `VetoRegistry` with concurrent access.
    - `MarkdownChunker` with representative markdown inputs (tables, nested sections, TOC).
    - `DependencyRegistry` thread-safety under concurrent `addDependency` + `getDependencies`.

20. **Document the `LanguagePack` extension contract.**
    - Add a `README` or Javadoc on `LanguagePack` explaining the expected registration, the `findPxChunkers` vs. `listPostWalkChunkers` semantics, and how to add a new language. This reduces the cognitive load for contributors.

_This document was generated with .dp and gemini-3-flash-preview_

