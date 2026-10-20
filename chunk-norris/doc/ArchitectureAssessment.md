# Software Architecture Assessment: ChunkNorris – Multi-Format Document & Code Chunking Pipeline

## 1. Executive Summary

ChunkNorris is a Spring Boot application within the PrjXP ecosystem that transforms source code files and documents into structured, semantically-aware chunks (JSONL) for downstream RAG/embedding pipelines. The architecture employs a **plugin-based, annotation-driven discovery model** combined with a **graph-theoretic document conversion router** (Dijkstra shortest path) to handle heterogeneous input formats.

**Key Strengths:**
- Well-defined extension points via `LanguagePack`, `ChunkerBroker`, and `DocConversionAgent` interfaces
- Clean orchestration layer (`ChunkProcess`) separating file-walking, veto evaluation, chunking, and serialization
- Configuration-driven behavior (project definitions, chunk sizes, overlap, encryption)
- Graceful degradation strategies (raw fallback on parse failure, charset fallback for VB)

**Most Critical Risks:**
- **Fragile regex-based parsing** in `TypeScriptCodeChunker` and `VisualBasicCodeChunker` will silently produce incorrect chunks for non-trivial code structures
- **Thread-safety gap** in `DependencyRegistry` (synchronized writes on non-concurrent `HashMap`) under parallel stream execution
- **Resource leak** in `Pdf2ImageConversionAgent` (PDDocument not closed on mid-loop exception) and `Doc2HtmlConversionAgent` (PrintWriter not in try-with-resources)
- **Dead/incomplete code** (`PdfChapterChunker` commented out, `createMetaChunk` returns empty list) signals unfinished features

---

## 2. Architectural Style & Patterns

| Pattern | Where Applied | Assessment |
|---------|--------------|------------|
| **Layered / Pipeline** | `ChunkProcess` → `ChunkerFactory` → `ChunkerBroker` → `PxChunker` | Clear unidirectional flow; each layer has a single responsibility |
| **Strategy** | `PxChunker` implementations (Java, TS, VB, FullText, Markdown, Text) | Each chunker encapsulates its own matching + splitting logic |
| **Plugin / SPI** | `LanguagePack` + `ServiceLoader<ChunkerBroker>` | External modules can register new language packs without modifying core |
| **Annotation-Driven Discovery** | `@Chunker`, `@PostWalkChunker`, `@ChunkVeto`, `@ChunkNorrisComponent` | Reflection-based scanning in `AnnotationBasedChunkerBrokerImpl` and `VetoRegistry` |
| **Graph / Dijkstra Routing** | `DocConversionRouter` + `SimpleDirectedWeightedGraph` | Optimal multi-step conversion path selection (e.g., PDF → Image → MD via AI) |
| **Observer / Event-Driven** | `SpringPreWalkEvent`, `CliArgsParsingEvent` | Decouples pre-processing hooks from the main walk loop |
| **Template Method** | `AnnotationBasedChunkerBrokerImpl` (abstract) → `ChunkerFactory` | Shared discovery logic, specialized aggregation |
| **Builder / Fluent API** | `PxChunk.create(Consumer<PxChunk>...)`, `DocArtifakt` chaining | Readable chunk construction with optional refinements |
| **Registry / Service Locator** | `DependencyRegistryManager`, `VetoRegistry` | Centralized lookup for per-project registries and veto methods |

**Decoupling Assessment:**
- The core `ChunkProcess` is well-decoupled from specific chunkers via the `ChunkerFactory` → `ChunkerBroker` indirection.
- The document conversion subsystem (`DocConversionRouter`, `DocArtifakt`, agents) is self-contained and loosely coupled from the code-chunking path.
- **Coupling concern:** `JavaCodeChunker` directly depends on `JavaDependencyHandler`, which in turn depends on `JavaFQNamesMapper` and `DependencyRegistryManager`. This creates a tight intra-language cluster that would be harder to replace or test in isolation.
- **Coupling concern:** `MarkdownChunker` depends on `DocConversionRouter`, `MetaInfReader`, and `LineScanner` — a reasonable fan-in, but the `DocConversionRouter` itself depends on `ConversionRoutesConfig` which uses `SpringContextSupplier` (a service-locator anti-pattern).

---

## 3. Quality Attribute Evaluation

### Maintainability & Readability

**Positive:**
- Consistent use of Lombok (`@RequiredArgsConstructor`, `@Data`) reduces boilerplate.
- Javadoc is present on key classes (`FullTextChunker`, `TypeScriptCodeChunker`, `DirectoryVetos`) explaining *why*, not just *what*.
- Records (`SpringPreWalkEvent`, `VisualBasicFileInfo`, `ContainerInfo`, `MemberInfo`) provide clean value objects.
- Naming conventions are generally clear (`createMethodChunks`, `findClassEndLine`, `toProjectRelativePath`).

**Negative:**
- **Mixed logging frameworks:** `java.util.logging` (`@Log`), `slf4j` (`@Slf4j`), and a custom `PxLogService` coexist. This fragments log configuration and makes centralized log-level management difficult.
- **Language mixing in comments:** German and English comments are interleaved (e.g., `// Pfad zur Sidecar-Datei konstruieren` next to `// Binary file — skip`). While not a functional issue, it hampers team-wide readability.
- **`TypeScriptCodeChunker` is 300+ lines** with deeply nested loops and repeated JSDoc-extraction logic (duplicated between `createClassMethodChunks` and `createTopLevelFunctionChunks`). This violates DRY and makes maintenance error-prone.
- **`MarkdownChunker.processFile` / `chunkMarkdownText`** contains a complex state machine (paragraph accumulation, section counters, TOC detection) that is hard to follow without extensive comments.
- **`javax.print.Doc`** is imported in `Pdf2ImageConversionAgent` — this is almost certainly a wrong IDE auto-import (should be `javax.print.Doc` from the JFP API, unrelated to PDF). This is a code smell indicating insufficient review.

### Extensibility

**Positive:**
- Adding a new language requires: (1) a `PxChunker` implementation, (2) a `LanguagePack` wrapper, (3) registration as a Spring `@Component`. No core modification needed.
- `ServiceLoader<ChunkerBroker>` allows fully external JARs to contribute chunkers without Spring.
- `DocConversionAgent` is a clean SPI: implement `getSourceFormat()`, `getTargetFormat()`, `convert()`, and the Dijkstra router picks it up automatically.
- `@ChunkVeto` annotation on any Spring bean method auto-registers it via `VetoRegistry` (a `BeanPostProcessor`).

**Negative:**
- The `AnnotationBasedChunkerBrokerImpl` uses `ClassPathScanningCandidateComponentProvider` with a hardcoded `rootPkg` (the package of `ChunkerFactory`). This means **only classes in the same package** are discovered. If a new chunker is placed in a sub-package (e.g., `de.spraener.prjxp.chuno.code.kotlin`), it will be missed unless the `LanguagePack` mechanism is used. This is a subtle extensibility trap.
- `DependencyRegistryManager` creates registries lazily by name string. Adding a new dependency-tracking language requires knowing the internal string constant (`JAVA_DEPENDENCIES`). A typed enum or interface would be more robust.
- The `DocArtifakt<T, C>` generic design is awkward: the type parameter `C` represents the *child's* data type, but it's used inconsistently (sometimes `?`, sometimes concrete). This makes it difficult for external contributors to implement new agents.

### Robustness & Error Handling

**Positive:**
- `JavaCodeChunker` has a well-structured fallback: `ParseProblemException` → log individual problems → `rawFallback()` → if that fails, return empty stream.
- `FullTextChunker` gracefully skips binary files (null-byte sniff) and empty files.
- `VisualBasicCodeChunker` handles non-UTF-8 files with a `windows-1252` fallback.
- `VetoRegistry.VetoMethodWrapper.check()` catches all exceptions and returns `false` (fail-open for vetos — a file is not vetoed if the veto method throws).

**Negative:**
- **`JavaFQNamesMapper.fillFQNamesMap()`** has a `// FIXME: handle exceptions!!!` comment with an empty catch block. If `Files.walk` throws (e.g., permission denied on a subdirectory), the entire FQN map is silently incomplete, leading to incorrect dependency resolution downstream.
- **`Pdf2ImageConversionAgent.convert()`** creates a `Pdf2ImageContext` (which holds an open `PDDocument`) and iterates pages. If `renderImageWithDPI` throws on page N, the `PDDocument` is **never closed** (the `postConversionAction` is only invoked by `DocContentCollector` after the full tree is processed). This is a resource leak for large PDFs.
- **`Doc2HtmlConversionAgent.convert()`** opens a `PrintWriter` without try-with-resources. If `parser.parse()` throws, the file handle leaks.
- **`TextChunker.chunkTextFile()`** throws `RuntimeException` on any exception, which will propagate up and potentially abort the entire file-walk. Other chunkers return `Stream.of()` on failure — inconsistent failure semantics.
- **`RtfToHtmlAgent.convert()`** creates a `DocArtifakt` and sets data but **never calls `input.addChild(htmlArt)`** — the converted HTML is silently discarded. This appears to be a bug.
- **`DependencyRegistry`** uses `synchronized` on `addDependency` but the backing maps are plain `HashMap`. Under `parallel()` stream execution in `ChunkProcess.handlePath()`, concurrent reads of `dependencies`/`usedBy` during a write can cause `ConcurrentModificationException` or infinite loops in `HashMap` (pre-Java 8; in Java 8+ it's data corruption).

### Performance & Resource Efficiency

**Positive:**
- `ContentSplitter` is reused per chunker instance (not per call), avoiding repeated allocation.
- `FullTextChunker` uses `Files.readString` (NIO) rather than line-by-line reading — efficient for the "read whole file" use case.
- The Dijkstra router builds the graph once per `findBestPath` call and reuses it for cost estimation.
- `processedFiles` set in `ChunkProcess` prevents re-processing (though it's cleared per run, so it's a safety net, not a performance optimization).

**Negative:**
- **`TypeScriptCodeChunker`** reads the entire file into a `List<String>`, then performs **multiple full passes** over the lines (imports scan, class scan, method scan per class, top-level function scan, class-frame construction). For a 5000-line file, this is O(n × passes) with high constant factors. The brace-matching helpers (`findClassEndLine`, `findMethodEndLine`) each scan to end-of-file in the worst case, making the total complexity O(n²) for files with many classes/methods.
- **`JavaFQNamesMapper.fillFQNamesMap()`** walks the **entire project tree** and parses every `.java` file with JavaParser. For a large monorepo (10k+ files), this is a significant startup cost. There's no incremental/caching mechanism.
- **`Pdf2ImageConversionAgent`** renders every page at 300 DPI as a `BufferedImage` in memory. A 100-page PDF at 300 DPI ≈ 100 × (2480×3508×4 bytes) ≈ **3.4 GB** of heap. The `ImageSupplier` lazy-loads on `get()`, but `DocContentCollector.collectContent` will call `getData()` on every child, materializing all images simultaneously.
- **`MarkdownChunker`** uses a `LineScanner` with a window of 4 lines and calls `scanner.peek(i)` in a loop for `isEndOfParagraph` — this is O(window) per line, acceptable, but the overall algorithm is single-threaded and sequential.
- **`DependencyRegistry`** stores all dependencies in memory. For a project with 10k Java files, each with ~10 dependencies, this is ~100k entries in two `HashMap<String, Set<String>>` structures. Acceptable, but unbounded.

---

## 4. Strengths & Best Practices

1. **Clean Pipeline Orchestration:** `ChunkProcess.executeForProject()` is a well-structured method that clearly delineates phases: pre-walk event → file walk with vetos → per-file chunking → post-walk → output. The separation of `executeForProject` (full walk) and `executeForFiles` (explicit list) supports both CLI and programmatic (hub) use cases.

2. **Graph-Based Conversion Routing:** The use of JGraphT's `DijkstraShortestPath` with cost/accuracy-weighted edges is an elegant solution to the "how do I convert format A to format B" problem. The `inaccurateSurcharge` mechanism allows the system to prefer analytic conversions over AI-driven ones when the caller demands high accuracy.

3. **Annotation-Driven Veto System:** `VetoRegistry` as a `BeanPostProcessor` that auto-discovers `@ChunkVeto`-annotated methods is a clean, zero-configuration extension point. The `VetoContext` (ThreadLocal) for project-scoped veto evaluation is a pragmatic solution to the "which project's config applies" problem in a multi-project setup.

4. **Graceful Degradation in Java Chunking:** The `JavaCodeChunker`'s fallback to `rawFallback()` (line-based splitting with lossless reconstruction) when JavaParser fails is a production-grade resilience pattern. The comment explaining *why* the fallback uses line-boundary splits (to preserve concatenation fidelity) shows architectural intent.

5. **LanguagePack Abstraction:** The `LanguagePack` interface + `LanguagePackBroker` bridge cleanly separates "which chunkers apply to this file" from "how to chunk it." The `ServiceLoader` integration allows non-Spring modules to participate.

6. **Idempotent Re-Indexing:** `processedFiles.clear()` at the start of `executeForProject` ensures that re-running the tool produces identical output (no stale state from a previous run).

7. **Path Rebase Logic:** The `rebaseIdToProjectPath` method in `ChunkProcess` solves a real-world problem (same-named files in different directories colliding on ID) with a well-documented, targeted transformation.

8. **Configuration-Driven Behavior:** Chunk sizes, overlaps, max file sizes, and encryption are all externalized via `@Value` / `@ConfigurationProperties`, enabling per-environment tuning without code changes.

9. **Use of Modern Java:** Records, `var`, text blocks (implicit in string formatting), `Stream` API, and `List.of()` / `Set.of()` demonstrate current Java idioms.

10. **Defensive File Handling:** `FullTextChunker`'s binary-file sniff (null-byte check on first 8KB) and `VisualBasicCodeChunker`'s charset fallback show awareness of real-world file diversity.

---

## 5. Identified Risks & Technical Debt

### Critical

| # | Risk | Location | Impact |
|---|------|----------|--------|
| C1 | **Thread-unsafe `DependencyRegistry`** under parallel streams | `DependencyRegistry` (synchronized write, non-concurrent `HashMap` read) | Data corruption, `ConcurrentModificationException`, or silent data loss when `ChunkProcess.handlePath` runs in parallel |
| C2 | **Resource leak in PDF image conversion** | `Pdf2ImageConversionAgent.convert()` | OOM on large PDFs if any page render fails; `PDDocument` never closed |
| C3 | **Silent data loss in RTF conversion** | `RtfToHtmlAgent.convert()` — creates `htmlArt` but never calls `input.addChild(htmlArt)` | RTF files produce no output; the conversion is a no-op |
| C4 | **Unbounded exception swallowing in FQN mapper** | `JavaFQNamesMapper.fillFQNamesMap()` — empty catch with `// FIXME` | Incomplete FQN map → incorrect dependency resolution → wrong chunks for Java files |

### High

| # | Risk | Location | Impact |
|---|------|----------|--------|
| H1 | **Regex-based TypeScript parsing** | `TypeScriptCodeChunker` (all patterns) | Misidentifies methods/classes in non-trivial code (decorators, multi-line signatures, template literals with braces). Produces incorrect chunk boundaries silently. |
| H2 | **O(n²) complexity in TS/VB chunkers** | `findClassEndLine`, `findMethodEndLine` called per method | 5000-line file with 100 methods → 500K+ line scans. Unacceptable for large codebases. |
| H3 | **Inconsistent failure semantics** | `TextChunker` throws `RuntimeException`; others return `Stream.of()` | A single malformed TXT file can abort the entire walk |
| H4 | **`javax.print.Doc` wrong import** | `Pdf2ImageConversionAgent` | Compilation risk; indicates insufficient code review |
| H5 | **`Doc2HtmlConversionAgent` PrintWriter leak** | `convert()` method | File descriptor leak on exception; also writes to a file path derived from input (side effect not documented) |

### Medium

| # | Risk | Location | Impact |
|---|------|----------|--------|
| M1 | **Dead code: `PdfChapterChunker`** | Commented-out `@Component` / `@ChunkNorrisComponent` | Confusion; suggests an unfinished feature; should be removed or completed |
| M2 | **Empty `createMetaChunk`** | `JavaCodeChunker.createMetaChunk()` returns `List.of()` | Dead code path; the method is called but produces nothing |
| M3 | **Mixed logging frameworks** | `@Log` (JUL), `@Slf4j` (SLF4J), `PxLogService` (custom) | Fragmented log configuration; no single log-level control |
| M4 | **`SpringContextSupplier` service locator** | `ConversionRoutesConfig` | Breaks testability; makes `ConversionRoutesConfig` hard to unit-test without a full Spring context |
| M5 | **`DocArtifakt` generic design** | `DocArtifakt<T, C>` where C is child data type | Awkward to implement; `C` is often `?` or `Object`; the `childs` field is `List<DocArtifakt<C, ?>>` which is recursive and hard to reason about |
| M6 | **`FullTextChunker` reads entire file into memory** | `Files.readString` | A 50MB text file consumes 50MB+ heap. No streaming alternative. |
| M7 | **`MarkdownChunker` complexity** | 200+ line method with state machine | Hard to test, hard to extend (e.g., for new header styles) |
| M8 | **`TextChunker` Groovy scripting** | Dynamic script compilation per file | Security risk (arbitrary code execution); performance cost of compilation; no sandboxing visible |

### Low

| # | Risk | Location | Impact |
|---|------|----------|--------|
| L1 | **German/English comment mixing** | Throughout | Minor readability issue for non-German-speaking contributors |
| L2 | **`ValueContainer` as mutable state carrier** | `TypeScriptCodeChunker`, `JavaCodeChunker`, `TextChunker` | Unnecessary indirection; a simple `int[]` or `AtomicInteger` would suffice |
| L3 | **`CostEstimation` as interface with constants** | `CostEstimation` | Should be an enum or a class with `static final` fields; an interface with only constants is a code smell |
| L4 | **`ConversionRoutesConfig` uses `@Data`** | Generates equals/hashCode/toString for a config bean | Unnecessary; `@Data` on a `@Component` is misleading |

---

## 6. Actionable Recommendations

### Priority 1 – Fix Critical Defects (Immediate)

1. **Make `DependencyRegistry` thread-safe.**
   Replace `HashMap` with `ConcurrentHashMap` and `HashSet` with `ConcurrentHashMap.newKeySet()`. Remove `synchronized` from `addDependency`. Alternatively, if the registry is only written during the pre-walk phase and read during the post-walk phase (sequential), document this contract explicitly and add a `freeze()` method that switches to unmodifiable views.

2. **Fix `RtfToHtmlAgent` to actually attach the result.**
   Add `input.addChild(htmlArt)` at the end of the try block. Add a unit test that verifies the child is present after conversion.

3. **Close `PDDocument` in `Pdf2ImageConversionAgent` on failure.**
   Wrap the page-iteration loop in a try-finally that calls `context.close()`. Alternatively, make `Pdf2ImageContext` implement `AutoCloseable` and use try-with-resources around the entire conversion.

4. **Handle exceptions in `JavaFQNamesMapper.fillFQNamesMap()`.**
   At minimum, log a warning per file that fails to parse. Consider collecting a list of failed files and logging a summary. Do not silently skip.

### Priority 2 – Address High-Risk Architecture Issues (Sprint 1–2)

5. **Replace regex-based TypeScript parsing with a proper parser.**
   Integrate `typescript-java-parser` (or the TypeScript compiler API via a sidecar process) to produce an AST. This eliminates the O(n²) brace-matching and the fragility of single-line regex patterns. If a full parser is too heavy, at minimum use a **state-machine-based** scanner that tracks brace depth to find method/class boundaries in O(n).

6. **Unify failure semantics across all chunkers.**
   Define a contract: `PxChunker.chunk(File)` must **never throw**. All exceptions should be caught, logged, and result in `Stream.of()`. Update `TextChunker` to comply. Add a `@Test` that verifies no chunker throws for a corrupted file.

7. **Fix `Doc2HtmlConversionAgent` resource management.**
   Use try-with-resources for both the `FileInputStream` and the `PrintWriter`. Remove the side-effect of writing to a file (or make it configurable and documented).

8. **Remove or complete `PdfChapterChunker`.**
   If the feature is not ready, delete the class. If it is planned, add a `@Disabled` annotation and a tracking issue. Dead code with commented-out annotations is worse than no code.

### Priority 3 – Improve Maintainability & Extensibility (Sprint 2–3)

9. **Standardize logging on SLF4J.**
   Replace all `@Log` (JUL) annotations with `@Slf4j` (SLF4J). Configure `PxLogService` as a thin wrapper over SLF4J (or remove it and use SLF4J directly). This enables a single logback/log4j2 configuration for the entire application.

10. **Extract the JSDoc-extraction logic in `TypeScriptCodeChunker` into a shared helper.**
    The pattern "check if previous line is `*`, find start, build content, create chunk" is duplicated in `createClassMethodChunks` and `createTopLevelFunctionChunks`. Extract to a `JsDocExtractor` utility.

11. **Replace `SpringContextSupplier` with direct `ApplicationContext` injection.**
    `ConversionRoutesConfig` should inject `ApplicationContext` directly (or better, inject the specific beans it needs). The `SpringContextSupplier` indirection adds a service-locator anti-pattern that complicates testing.

12. **Refactor `DocArtifakt` generics.**
    Simplify to `DocArtifakt<T>` where `T` is the data type. The child list should be `List<DocArtifakt<?>>`. The current `<T, C>` design forces implementors to think about their child's type, which is an implementation detail of the conversion agent, not the artifact.

13. **Add a `LanguagePack` for the `FullTextChunker` and `TextChunker`.**
    Currently, `FullTextChunker` is discovered via `@ChunkNorrisComponent` + `@Chunker` (annotation scanning) while language-specific chunkers use `LanguagePack`. This dual discovery mechanism is confusing. Standardize on `LanguagePack` for all file-type-specific chunkers.

### Priority 4 – Performance & Scalability (Sprint 3–4)

14. **Stream large files instead of reading into memory.**
    For `FullTextChunker` and `TextChunker`, process files line-by-line (or in fixed-size buffers) rather than loading the entire content. This bounds memory usage to O(chunk_size) regardless of file size.

15. **Add incremental/caching to `JavaFQNamesMapper`.**
    Cache parsed FQNs keyed by file path + last-modified timestamp. On re-runs, only re-parse changed files. This turns the O(all_files) startup cost into O(changed_files).

16. **Bound PDF image memory.**
    In `Pdf2ImageConversionAgent`, process pages in a bounded window (e.g., 5 pages at a time) and flush to disk or a downstream consumer before rendering the next batch. Alternatively, reduce DPI for the initial pass and only render at 300 DPI for pages that need AI analysis.

17. **Make `MarkdownChunker`'s state machine testable.**
    Extract the paragraph/section accumulation logic into a separate `MarkdownSectionParser` class with a clear state enum (IN_HEADER, IN_PARAGRAPH, IN_TABLE, IN_TOC). This enables unit testing of edge cases without a full Spring context.

### Priority 5 – Code Hygiene (Ongoing)

18. **Remove `javax.print.Doc` import** from `Pdf2ImageConversionAgent` (wrong auto-import).
19. **Remove empty `createMetaChunk` method** or implement it.
20. **Unify comment language** — pick English for all code comments (keep German only in user-facing strings if the target audience is German).
21. **Replace `CostEstimation` interface** with a `final class` containing `public static final` constants, or an enum with associated cost values.
22. **Add `@Data` → `@Getter @Setter`** on `ConversionRoutesConfig` to avoid generating misleading `equals`/`hashCode` on a singleton bean.
23. **Add integration tests** that exercise the full pipeline (file → veto → chunk → JSONL) for at least one file type per chunker. The current codebase has no visible test files.

_This document was generated with .dp and gemini-3-flash-preview_

