# Software Architecture Assessment: ChunkNorris (PrjXP Document & Code Chunking Engine)

## 1. Executive Summary

ChunkNorris is a Spring Boot–based chunking engine within the PrjXP ecosystem. It walks a project's file tree, applies a veto/filter pipeline, dispatches files to language- or format-specific chunkers (Java, TypeScript, Visual Basic, Markdown, PDF, RTF, DOCX, TXT), and emits JSONL output. A secondary subsystem performs multi-step document format conversion (PDF → Text → Markdown, DOCX → HTML → Markdown, etc.) using a Dijkstra-weighted graph of conversion agents.

**Key Strengths:**
- Well-defined extension points via `LanguagePack`, `ChunkerBroker`, and annotation-driven discovery.
- A genuinely clever Dijkstra-based conversion router that selects optimal format-conversion paths with cost/accuracy weighting.
- Clean separation between the chunking pipeline, the veto system, and the document conversion pipeline.
- Thoughtful fallback semantics (e.g., `DefaultLineBreakTextChunker` as a catch-all, raw-fallback in `JavaCodeChunker`).

**Most Critical Risks:**
- **Thread-safety gaps** in `AnnotationBasedChunkerBrokerImpl` (unsynchronized lazy init), `DependencyRegistryManager` (plain `HashMap`), and `StaticJavaParser` (global mutable state) — all of which are exercised in parallel streams.
- **A god-class tendency** in `MarkdownChunker` (~250 lines of mixed concerns: line scanning, section hierarchy, table detection, TOC detection, content splitting, metadata injection).
- **Fragile regex-based parsing** in `TypeScriptCodeChunker` and `VisualBasicCodeChunker` that will silently mis-chunk on non-trivial code.
- **Inconsistent i18n** (German comments/error messages mixed with English) and multiple typos in public API names (`ATOMAR`, `isTabelStart`, `scriptSate`).
- **Dead/commented-out code** (`PdfChapterChunker`) and unused imports (`javax.print.Doc`, `javax.swing.*`) indicating incomplete refactoring.

---

## 2. Architectural Style & Patterns

| Pattern | Where Applied | Assessment |
|---------|--------------|------------|
| **Layered / Pipeline** | `ChunkProcess` → `ChunkerFactory` → `ChunkerBroker` → `PxChunker` | Clear unidirectional flow; well-separated. |
| **Strategy / Plugin** | `LanguagePack` implementations (`JavaChunkerPack`, `TypeScriptChunkerPack`, `VisualBasicChunkerPack`) | Good extensibility; adding a new language requires one pack class. |
| **Annotation-Driven Discovery** | `@Chunker`, `@ChunkNorrisComponent`, `@PostWalkChunker`, `@ChunkVeto` + `AnnotationBasedChunkerBrokerImpl` / `VetoRegistry` | Powerful but introduces a second discovery mechanism alongside `LanguagePack`; the dual path in `ChunkerFactory` adds cognitive load. |
| **Broker / Mediator** | `ChunkerBroker` interface, `LanguagePackBroker`, `ChunkerFactory` | Decouples chunker selection from the process; `ServiceLoader` support for external plugins. |
| **Graph / Dijkstra Routing** | `DocConversionRouter` + `DocConversionAgent` | Elegant for multi-hop format conversion; cost + accuracy weighting is a smart domain model. |
| **Tree / Composite** | `DocArtifakt<T,C>` with `childs` list | Models the conversion DAG; supports fan-out (PDF → N page-images). |
| **Event-Driven (Spring)** | `SpringPreWalkEvent`, `CliArgsParsingEvent` | Lightweight decoupling for pre-walk initialization and CLI parsing. |
| **Template Method (implicit)** | `AnnotationBasedChunkerBrokerImpl` with `PxChunkerMethodWrapper` / `PxPostWalkChunkerMethodWrapper` | Wraps arbitrary annotated methods into the `PxChunker` interface. |
| **Thread-Local Context** | `VetoContext` | Passes `ProjectDefinition` through the veto chain without polluting method signatures. |

**Decoupling Assessment:**
- The core chunking pipeline is well-decoupled: `ChunkProcess` knows nothing about specific chunkers; it delegates to `ChunkerFactory`.
- The document conversion subsystem is self-contained (`docs` package) and communicates with the chunking layer only through `MarkdownChunker`.
- The veto system is cleanly isolated but tightly coupled to `VetoContext` (ThreadLocal), which creates a hidden dependency.
- The `common` package (referenced but not shown) provides shared models (`PxChunk`, `PxChunker`, `ContentSplitter`, `PrjXPConfig`), acting as a kernel. This is appropriate but creates a hard dependency on the `common` module's API stability.

---

## 3. Quality Attribute Evaluation

### Maintainability & Readability

**Positive:**
- Javadoc is present and often excellent on key classes (`DefaultLineBreakTextChunker`, `FullTextChunker`, `DirectoryVetos`, `ChunkProcess`).
- Naming conventions are generally consistent (camelCase, descriptive method names).
- Package structure is logical: `code.java`, `code.typescript`, `code.visualbasic`, `docs.pdf`, `docs.html`, `docs.poi`, `veto`, `language`, `util`.

**Negative:**
- **Inconsistent language in comments/messages:** German (`"Fehler bei RTF -> HTML Konvertierung"`, `"Das PDF ... enthält keinen extrahierbaren Text"`) is mixed with English throughout. This is a significant maintainability hazard for a team that may not be uniformly German-speaking.
- **Typos in public/semi-public identifiers:**
  - `CostEstimation.ATOMAR` (should be `ATOMIC`)
  - `TextDocChunkContext.scriptSate` (should be `scriptState`)
  - `MarkdownChunker.isTabelStart` (should be `isTableStart`)
  - `HtmlStructurePreprocessor` references `"berschrift"` (missing `Ü` — likely an encoding artifact)
  - `Pdf2ImageConversionAgent`: `"Renderd page"` (should be `"Rendered"`)
- **`MarkdownChunker`** is a ~250-line class that handles: line scanning, header detection, section hierarchy tracking, table detection, TOC detection, paragraph splitting, metadata injection, and sidecar-meta merging. This violates SRP and will be difficult to modify without regression.
- **`JavaCodeChunker`** is ~300 lines with heavy duplication between method and constructor chunking logic (nearly identical blocks for Javadoc + implementation).
- **`Pdf2ImageConversionAgent`** defines two non-static inner classes (`Pdf2ImageContext`, `ImageSupplier`) that implicitly capture the outer instance — a subtle memory-leak vector and a readability issue.
- **Unused imports:** `javax.print.Doc` in `Pdf2ImageConversionAgent` and `DocContentCollector`; `javax.swing.*` in `ConversionRoutesConfig`. These suggest copy-paste or IDE auto-import noise.

### Extensibility

**Positive:**
- **`LanguagePack`** is the primary extension point for new languages. Adding a language requires: (1) a `PxChunker` implementation, (2) a `LanguagePack` implementation, (3) registration as a Spring `@Component`. This is clean and low-friction.
- **`ServiceLoader<ChunkerBroker>`** in `ChunkerFactory` allows external JARs to contribute additional chunker brokers without modifying the core.
- **`DocConversionAgent`** is a well-designed SPI for new format converters. Adding a new conversion (e.g., LaTeX → Markdown) requires one class implementing the interface.
- **`@ChunkVeto`** annotation + `VetoRegistry` (BeanPostProcessor) allows new veto rules to be added by simply annotating a method in any Spring bean.
- **`@Chunker` / `@PostWalkChunker`** annotations on methods allow non-`PxChunker` classes to contribute chunking logic.

**Negative:**
- The **dual discovery mechanism** (annotation-scanning in `AnnotationBasedChunkerBrokerImpl` AND `LanguagePack` via `LanguagePackBroker`) creates ambiguity. A new contributor must understand *both* paths and the `ChunkerFactory`'s merge logic. The `FullTextChunker` exclusion hack (`!(c instanceof FullTextChunker)`) is a code smell indicating the two mechanisms are not cleanly reconciled.
- **`DocConversionRouter`** builds the graph from all injected `DocConversionAgent` beans. Adding a new agent is easy, but the recursive `estimateConversionCosts` method creates a *new* `DocArtifakt` at each step without a depth limit — a deeply nested conversion chain could cause a `StackOverflowError`.
- **`DependencyRegistryManager`** uses a plain `HashMap` with no synchronization. If two projects are processed concurrently (hub mode), this is a data race.

### Robustness & Error Handling

**Positive:**
- `JavaCodeChunker` has a well-structured fallback: on `ParseProblemException`, it logs individual problems and falls back to `rawFallback()` (line-based splitting). On any other exception, it also attempts the raw fallback.
- `DefaultLineBreakTextChunker` and `FullTextChunker` gracefully return `Stream.of()` on `IOException` or binary detection.
- `VisualBasicCodeChunker` handles non-UTF-8 files with a `windows-1252` fallback.
- `VetoRegistry.VetoMethodWrapper.check()` catches all exceptions and returns `false` (fail-open), preventing a single buggy veto from crashing the pipeline.
- `ChunkProcess` handles `FileNotFoundException` and `IOException` when creating the output writer, falling back to `System.out`.

**Negative:**
- **`JavaFQNamesMapper.fillFQNamesMap()`** has a bare `catch (Exception e) { // FIXME: handle exceptions!!! }` — a swallowed exception that will silently produce an incomplete FQN map, leading to incorrect dependency resolution.
- **`JavaFQNamesMapper.handleFile()`** wraps any exception in `RuntimeException` and rethrows — this will abort the entire pre-walk event if *any* single `.java` file fails to parse.
- **`Doc2HtmlConversionAgent.convert()`** writes a `.html` file to disk as a side effect (`new PrintWriter(artifakt.getData().getAbsolutePath()+".html")`). This is a hidden filesystem mutation that is not documented, not cleaned up, and will fail in read-only environments.
- **`Pdf2ImageConversionAgent`** opens a `PDDocument` in the `Pdf2ImageContext` constructor but the `close()` method is only invoked via `postConversionAction`. If the conversion pipeline is interrupted (exception in a downstream agent), the PDF document handle leaks.
- **`TextChunker`** compiles and evaluates Groovy scripts from the filesystem. This is a **code-execution vulnerability** if the project directory is not fully trusted.
- **`RtfToHtmlAgent`** wraps all exceptions in `RuntimeException` with a German message — no structured error propagation.
- **`DocContentCollector.collectContent()`** performs an unchecked cast `(DocArtifakt) child` and calls `artifakt.getPostConversionAction().accept(artifakt)` without a null check — if `postConversionAction` is null (the default is a no-op lambda, but `@Data` could regenerate it), this is an NPE.

### Performance & Resource Efficiency

**Positive:**
- `ChunkProcess` uses `.parallel()` on the chunker stream, enabling concurrent file processing.
- `ContentSplitter` is a reusable utility that avoids re-reading files.
- `JavaFQNamesMapper` pre-computes the FQN map in a single pre-walk pass (event-driven), avoiding per-file re-scanning.
- `Pdf2TextConversionAgent.isPdfWithText()` only reads the first page for the cost estimation — a smart optimization.

**Negative:**
- **`AnnotationBasedChunkerBrokerImpl.initChunkers()`** performs a full classpath scan (`ClassPathScanningCandidateComponentProvider`) on first invocation. For a large classpath, this is expensive and is done *without synchronization* (see thread-safety below).
- **`StaticJavaParser`** (used in `JavaCodeChunker` and `JavaFQNamesMapper`) is a **global singleton with mutable configuration**. The call `StaticJavaParser.getConfiguration().setLanguageLevel(...)` in `JavaCodeChunker.chunk()` mutates shared state. In a parallel stream, two threads could race on this configuration.
- **`DependencyRegistryManager`** uses a non-synchronized `HashMap`. In parallel processing, concurrent `get()` calls that trigger `put()` will corrupt the map.
- **`MarkdownChunker`** reads the entire converted Markdown into a `ByteArrayInputStream` and scans it line-by-line with a `LineScanner` that maintains a sliding window. For very large documents (e.g., a 500-page PDF converted to Markdown), this holds the entire text in memory.
- **`Pdf2ImageConversionAgent`** renders each page at 300 DPI as a `BufferedImage` (BINARY mode). A single A4 page at 300 DPI is ~2480×3508 pixels × 1 byte ≈ 8.7 MB. A 100-page PDF would hold ~870 MB of image data in the `DocArtifakt` tree simultaneously (since all children are created before any are consumed).
- **`DocConversionRouter.estimateConversionCosts()`** is recursive and creates a new `DocArtifakt` at each level. For a graph with many intermediate formats, this could create a large object graph just for cost estimation.
- **`TypeScriptCodeChunker`** and `VisualBasicCodeChunker` perform O(n) regex matching per line, with multiple patterns. For a 5000-line file, this is 5000 × (number of patterns) regex evaluations. Not catastrophic, but suboptimal compared to a proper parser.
- **`JavaCodeChunker`** calls `Files.readAllLines()` *and* `StaticJavaParser.parse(f)` — reading the file twice (once for lines, once for the AST). The `codeLines` list is then used to reconstruct content from line ranges, which is O(n) per chunk.

---

## 4. Strengths & Best Practices

1. **LanguagePack as a first-class extension point.** The `LanguagePack` interface (`language()`, `mimeType()`, `findPxChunkers()`, `listPostWalkChunkers()`) provides a clean, discoverable way to add new languages. The `LanguagePackBroker` bridges this into the `ChunkerBroker` hierarchy elegantly.

2. **Dijkstra-based conversion routing.** `DocConversionRouter` models format conversion as a weighted directed graph and uses JGraphT's `DijkstraShortestPath` to find the optimal path. The cost model incorporates both computational cost (`estimateCosts`) and accuracy (`ConversionAccuracy` rank with a surcharge for lower-accuracy paths). This is a sophisticated and appropriate design for a multi-format conversion system.

3. **Layered fallback strategy in chunking.** `ChunkerFactory.createChunker()` first tries all registered chunkers; if only the catch-all `FullTextChunker` matches, it appends `DefaultLineBreakTextChunker` as a searchable fallback. This ensures no whitelisted file is silently dropped.

4. **Veto system with ThreadLocal context.** `VetoContext` + `VetoRegistry` (as a `BeanPostProcessor`) allows project-specific veto rules to be evaluated in a multi-project context without passing the `ProjectDefinition` through every method signature. The `shouldVeto(Path, ProjectDefinition)` overload sets/clears the ThreadLocal in a try-finally block.

5. **Comprehensive Javadoc on architectural intent.** Classes like `DefaultLineBreakTextChunker`, `FullTextChunker`, `DirectoryVetos`, and `ChunkProcess` carry detailed Javadoc explaining *why* design decisions were made (e.g., why `DefaultLineBreakTextChunker` is deliberately *not* a `@Chunker`, why the `:text` ID suffix is used, the monotonic source-directory scan logic).

6. **`DocArtifakt` tree with `postConversionAction`.** The ability to attach a cleanup callback (e.g., closing a `PDDocument`) to a node in the conversion tree is a practical solution to resource lifecycle management in a pipeline.

7. **`rebaseIdToProjectPath` in `ChunkProcess`.** The logic to rebase basename-based chunk IDs onto project-relative paths (preventing ID collisions for same-named files in different directories) is a well-thought-out solution to a real-world problem, with clear Javadoc explaining the edge cases.

8. **`DirectoryVetos.scan()` monotonic source-tree logic.** The left-to-right scan that makes the rest of the path "sacred" once a source directory is entered (so `src/.../target/Foo.java` is preserved but `build/generated/src/Foo.java` is vetoed) is a clean, well-documented algorithm.

9. **Use of records for value objects.** `SpringPreWalkEvent`, `VisualBasicFileInfo`, `ContainerInfo`, `MemberInfo` use Java records — appropriate for immutable data carriers.

10. **`@ConditionalOnProperty` for feature toggles.** `StandardVetos` and `DirectoryVetos` are conditionally activated via `no-standard-vetos`, and the `CommandLineRunner` is gated on `prjxp.hub.enabled`. This allows the same codebase to run in CLI mode or as an in-process hub component.

---

## 5. Identified Risks & Technical Debt

### Critical

| # | Risk | Location | Impact |
|---|------|----------|--------|
| C1 | **Unsynchronized lazy initialization** in `AnnotationBasedChunkerBrokerImpl` (`chunkers`/`postWalkChunkers` fields). In a parallel stream, two threads can both see `null` and both call `initChunkers()`, leading to duplicate entries or a `ConcurrentModificationException`. | `AnnotationBasedChunkerBrokerImpl` | Data corruption, duplicate chunks, or runtime exceptions under concurrent load. |
| C2 | **`StaticJavaParser` global mutable state.** `JavaCodeChunker.chunk()` calls `StaticJavaParser.getConfiguration().setLanguageLevel(...)` on every invocation. In a parallel stream, this is a race condition. | `JavaCodeChunker` | Non-deterministic parse behavior, potential `ConcurrentModificationException` inside JavaParser. |
| C3 | **Non-thread-safe `HashMap` in `DependencyRegistryManager`.** The `dependencyRegistryMap` is a plain `HashMap` with no synchronization. Concurrent `get()` calls that trigger `put()` will corrupt the map. | `DependencyRegistryManager` | Silent data loss, `NullPointerException`, or infinite loops in the HashMap under concurrent access. |
| C4 | **Swallowed exception in `JavaFQNamesMapper`.** The `catch (Exception e) { // FIXME }` in `fillFQNamesMap` means a failure to walk the file tree silently produces an empty FQN map, causing all dependency resolution to fall back to unqualified names. | `JavaFQNamesMapper` | Incorrect dependency chunks; silent degradation of search quality. |
| C5 | **Groovy script execution from filesystem** in `TextChunker`. If the project directory is writable by an untrusted party, an attacker can place a `.groovy` file that executes arbitrary code. | `TextChunker` | Remote code execution / arbitrary code execution. |

### High

| # | Risk | Location | Impact |
|---|------|----------|--------|
| H1 | **`MarkdownChunker` is a god class.** ~250 lines mixing line scanning, section hierarchy, table detection, TOC detection, paragraph splitting, metadata injection, and sidecar-meta merging. Any change to one concern risks breaking others. | `MarkdownChunker` | High regression risk; difficult to test in isolation; violates SRP. |
| H2 | **`Doc2HtmlConversionAgent` writes to the filesystem** as a side effect of conversion. This is undocumented, not idempotent, and will fail in read-only or containerized environments. | `Doc2HtmlConversionAgent` | Unexpected file creation; failure in restricted environments. |
| H3 | **`Pdf2ImageConversionAgent` memory footprint.** All page images are created eagerly (300 DPI BINARY ≈ 8.7 MB/page) and held in the `DocArtifakt` tree. A 200-page PDF ≈ 1.7 GB. | `Pdf2ImageConversionAgent` | `OutOfMemoryError` on large PDFs. |
| H4 | **Fragile regex-based parsing** in `TypeScriptCodeChunker` and `VisualBasicCodeChunker`. Multi-line method signatures, decorators, template literals, or non-standard formatting will cause mis-chunking or missed methods. | `TypeScriptCodeChunker`, `VisualBasicCodeChunker` | Incomplete or incorrect chunks; silent quality degradation. |
| H5 | **`DocArtifakt` uses `@Data`** which generates `equals()`/`hashCode()` over all fields including `data` (a `File` or a multi-MB `String`). Any use of `DocArtifakt` in a `HashSet` or as a `Map` key will be extremely slow or OOM. | `DocArtifakt` | Performance degradation or OOM if used in collections. |
| H6 | **`DocContentCollector.collectContent()` unchecked cast and potential NPE.** The `(DocArtifakt) child` cast and the unconditional `artifakt.getPostConversionAction().accept(artifakt)` call are unsafe. | `DocContentCollector` | `ClassCastException` or `NullPointerException` at runtime. |

### Medium

| # | Risk | Location | Impact |
|---|------|----------|--------|
| M1 | **Dual chunker-discovery mechanism.** `AnnotationBasedChunkerBrokerImpl` (classpath scan) and `LanguagePackBroker` (Spring bean list) both feed into `ChunkerFactory`. The `FullTextChunker` exclusion is a `instanceof` check — a new catch-all chunker would need to be added to this check. | `ChunkerFactory` | Maintenance burden; risk of duplicate or missing chunkers. |
| M2 | **`PdfChapterChunker` is commented out** but still present in the codebase with `System.out.println` debug statements. | `PdfChapterChunker` | Dead code; confusion about intended functionality. |
| M3 | **`JavaCodeChunker.createMetaChunk()` returns `List.of()`** — a dead method that is called but produces nothing. | `JavaCodeChunker` | Misleading; suggests incomplete implementation. |
| M4 | **Inconsistent i18n.** German error messages and comments mixed with English. | Multiple files | Barrier to non-German-speaking contributors; inconsistent user-facing output. |
| M5 | **Typos in identifiers:** `ATOMAR`, `scriptSate`, `isTabelStart`, `"berschrift"`, `"Renderd"`. | Multiple files | Searchability; API confusion; suggests insufficient code review. |
| M6 | **`ConversionRoutesConfig` has an unused `javax.swing.*` import** and a `@ConfigurationProperties` annotation on `ConversionRoute` (a `@Component` + `@ConfigurationProperties` combo is unusual). | `ConversionRoutesConfig`, `ConversionRoute` | IDE warnings; potential Spring Boot configuration binding issues. |
| M7 | **`Pdf2ImageConversionAgent` has an unused `javax.print.Doc` import.** | `Pdf2ImageConversionAgent` | IDE warning; suggests copy-paste origin. |
| M8 | **`TextChunker` uses `BufferedReader`/`FileReader`** (deprecated in favor of `Files.lines()`) and does not close the reader in a `finally`/try-with-resources block. | `TextChunker` | Resource leak on exception. |
| M9 | **`RtfToHtmlAgent` creates a `DocArtifakt` but never adds it to the parent** — the `htmlArt` is created, populated, but the method returns `void` and the artifact is lost. | `RtfToHtmlAgent` | The RTF→HTML conversion result is silently discarded. |

### Low

| # | Risk | Location | Impact |
|---|------|----------|--------|
| L1 | **`ChunkProcess.processedFiles` is a `final` field** that is cleared at the start of each run. The `final` modifier is misleading — the *reference* is final, but the *contents* are mutated. | `ChunkProcess` | Minor readability issue. |
| L2 | **`VisualBasicCodeChunker` has redundant field initializers** (`private int chunkSize = 1300;` alongside `@Value("${visualbasic.chunksize:1300}")`). | `VisualBasicCodeChunker` | Confusing; the `@Value` default and the field initializer are redundant. |
| L3 | **`HtmlStructurePreprocessor` is not a Spring bean** — it is instantiated directly in `Html2MDConversionAgent`. This prevents configuration or testing via the Spring context. | `Html2MDConversionAgent` | Minor; limits testability. |
| L4 | **`MetaInfReader` uses a `static` `ObjectMapper`** — this is fine for thread safety but prevents per-bean configuration. | `MetaInfReader` | Minor. |

---

## 6. Actionable Recommendations

### Priority 1 — Fix Thread-Safety & Correctness (Immediate)

1. **Synchronize lazy initialization in `AnnotationBasedChunkerBrokerImpl`.**
   - Replace the `if (chunkers == null) { initChunkers(); }` pattern with `volatile` + double-checked locking, or better, initialize in the constructor / `@PostConstruct`.
   - Alternatively, use `AtomicReference<List<PxChunker>>` with `compareAndSet`.

2. **Replace `StaticJavaParser` with an instance-based `JavaParser`.**
   - In `JavaCodeChunker`, create a `JavaParser` instance (with the desired `ParserConfiguration`) in the constructor and inject it. Remove the `StaticJavaParser.getConfiguration().setLanguageLevel(...)` call.
   - Do the same in `JavaFQNamesMapper`.

3. **Make `DependencyRegistryManager` thread-safe.**
   - Replace `new HashMap<>()` with `new ConcurrentHashMap<>()`.
   - Use `computeIfAbsent` for the get-or-create pattern.

4. **Fix the swallowed exception in `JavaFQNamesMapper.fillFQNamesMap()`.**
   - At minimum, log the exception at `WARNING` level with the file path.
   - Better: collect per-file errors and log a summary at the end of the walk. Do not let a single unparseable file abort the entire pre-walk.

5. **Fix the `RtfToHtmlAgent` lost artifact.**
   - The `htmlArt` is created but never added to `input`. Add `input.addChild(htmlArt)` before the method returns.

### Priority 2 — Refactor High-Risk Components (Short-Term)

6. **Decompose `MarkdownChunker` into focused collaborators.**
   - Extract a `MarkdownSectionParser` (header detection, hierarchy tracking, section counters).
   - Extract a `MarkdownTableDetector` (table start/separator detection, header creation).
   - Extract a `MarkdownTocDetector` (TOC detection).
   - Extract a `MarkdownParagraphSplitter` (paragraph accumulation, `ContentSplitter` invocation).
   - `MarkdownChunker` becomes a thin orchestrator that composes these.

7. **Address `Pdf2ImageConversionAgent` memory pressure.**
   - Make page rendering **lazy**: instead of creating all `ImageSupplier` children upfront, create them on demand.
   - Or, add a configurable max-DPI and a max-pages guard.
   - Ensure `Pdf2ImageContext.close()` is called in a `finally` block or via `AutoCloseable` propagation, not just via `postConversionAction`.

8. **Remove the filesystem side-effect from `Doc2HtmlConversionAgent`.**
   - Delete the `PrintWriter` block. The HTML is already stored in the `DocArtifakt` data. If a file output is needed, it should be a separate, explicitly configured step.

9. **Guard against Groovy script execution in `TextChunker`.**
   - Add a configuration flag (e.g., `prjxp.text.groovy.enabled=false` by default).
   - If enabled, use a `SecureClassLoader` or a sandboxed `ScriptEngine` with restricted permissions.
   - At minimum, log a prominent warning when a Groovy script is found and executed.

10. **Fix `DocArtifakt` `@Data` → `@Getter`/`@Setter` + explicit `equals`/`hashCode`.**
    - Exclude `data` and `childs` from `equals`/`hashCode`. Use only `id` and `format`.
    - Or, annotate `data` and `childs` with `@EqualsAndHashCode.Exclude`.

### Priority 3 — Improve Robustness & Consistency (Medium-Term)

11. **Replace regex-based parsing in `TypeScriptCodeChunker` with a proper parser.**
    - Use `typescript-java-parser` or the TypeScript compiler API via JNI/JS bridge.
    - If a full parser is not feasible, at minimum add integration tests with a corpus of real-world TypeScript files (multi-line signatures, decorators, template literals, nested classes) to validate the regex approach.

12. **Standardize i18n.**
    - Choose one language (English) for all user-facing messages, log output, and Javadoc.
    - If German is required for a specific audience, use a `MessageSource` / resource-bundle approach rather than hard-coded strings.
    - Fix all identified typos: `ATOMAR` → `ATOMIC`, `scriptSate` → `scriptState`, `isTabelStart` → `isTableStart`, `"berschrift"` → `"Überschrift"`, `"Renderd"` → `"Rendered"`.

13. **Remove dead code and unused imports.**
    - Delete or properly implement `PdfChapterChunker` (currently fully commented out with `System.out.println` debug statements).
    - Remove the `createMetaChunk()` method from `JavaCodeChunker` (returns `List.of()`).
    - Remove unused imports: `javax.print.Doc` (in `Pdf2ImageConversionAgent`, `DocContentCollector`), `javax.swing.*` (in `ConversionRoutesConfig`).

14. **Fix `TextChunker` resource management.**
    - Replace `BufferedReader`/`FileReader` with `Files.lines(f.toPath(), StandardCharsets.UTF_8)` in a try-with-resources block.
    - Or, at minimum, wrap the `BufferedReader` in a try-with-resources.

15. **Add a depth limit to `DocConversionRouter.estimateConversionCosts()`.**
    - Add a `maxDepth` parameter (e.g., 10) to prevent `StackOverflowError` on cyclic or deeply nested conversion graphs.
    - The `visited` set already prevents infinite loops, but the recursion depth is unbounded.

### Priority 4 — Architectural Improvements (Long-Term)

16. **Unify the dual chunker-discovery mechanism.**
    - Choose one primary mechanism. Recommendation: make `LanguagePack` the sole discovery path. `AnnotationBasedChunkerBrokerImpl` should be deprecated or reduced to a `LanguagePack`-backed implementation.
    - Remove the `instanceof FullTextChunker` check in `ChunkerFactory` by giving `FullTextChunker` a `catchAll()` method on the `PxChunker` interface (default `false`).

17. **Introduce a `ChunkerContext` object** to replace the implicit `File` parameter and the `VetoContext` ThreadLocal.
    - A `ChunkerContext` carrying `File`, `ProjectDefinition`, `PrjXPConfig`, and any per-run state would eliminate the need for ThreadLocal and make the dependency graph explicit.
    - This would also simplify the `VetoRegistry` (no more `BeanPostProcessor` + ThreadLocal).

18. **Add a `DocConversionAgent` registry with explicit route configuration.**
    - The current `ConversionRoutesConfig` uses bean names as strings, which is fragile. Consider a typed route definition: `List<Class<? extends DocConversionAgent>>` or a `@ConversionRoute` annotation on the agent classes.
    - This would allow compile-time verification of route validity.

19. **Introduce structured logging / observability.**
    - Replace ad-hoc `log.warning(...)` / `log.info(...)` calls with a structured logging approach (e.g., MDC with file path, chunker name, project name).
    - Add metrics: chunks produced per file, conversion time per agent, veto hit rate. This would make performance bottlenecks (e.g., the PDF image rendering) visible in production.

20. **Add a comprehensive test suite for the conversion pipeline.**
    - The `DocConversionRouter`'s Dijkstra logic, the `DocArtifakt` tree traversal, and the `DocContentCollector` are complex and currently appear to lack dedicated unit tests.
    - Add tests for: multi-hop conversions (PDF → Image → MD), fan-out (PDF → N pages), cost/accuracy weighting, and the `postConversionAction` lifecycle.

_This document was generated with .dp and gemini-3-flash-preview_

