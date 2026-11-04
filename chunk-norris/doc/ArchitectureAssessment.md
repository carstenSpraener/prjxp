# Software Architecture Assessment: ChunkNorris / Project File Chunking & Document Conversion Pipeline

## 1. Executive Summary

ChunkNorris is a Spring Boot-based CLI/service module that walks configured project directories, applies veto rules, selects matching chunkers, converts/chunks source code and documents, and emits JSONL `PxChunk` records for downstream indexing or embedding pipelines.

The architecture is centered around a pluggable chunking pipeline with several extension mechanisms:

- Spring-managed `PxChunker` implementations.
- Annotation-based chunker discovery via `@ChunkNorrisComponent` and `@Chunker`.
- Language-pack-based chunker aggregation.
- Java `ServiceLoader` support for external `ChunkerBroker` plugins.
- A graph-based document conversion subsystem using `DocConversionAgent`s.
- Reflection-based veto registration via `@ChunkVeto`.

Overall, the project has a clear functional direction and several good architectural ideas: chunkers are separated by language/document type, document conversion is modeled as a routing problem, and veto logic is decoupled from the traversal process. However, the implementation currently mixes multiple plugin/discovery mechanisms, relies heavily on reflection and mutable singleton state, and contains several robustness, concurrency, and resource-management risks.

The most critical risks are:

- Shared mutable singleton state across runs, especially dependency registries, processed-file tracking, and Java FQN maps.
- Reflection-heavy chunker and veto discovery with weak type safety and limited validation.
- Parallel stream usage combined with mutable registries and output writing.
- Incomplete resource management for `Files.walk`, readers, streams, PDF/Tika inputs, and output streams.
- Several likely functional defects in document conversion agents and text chunkers.
- Weak error-handling consistency: some failures are swallowed, some become `RuntimeException`, and CLI parsing calls `System.exit`.

The architecture is workable, but it would benefit significantly from a more explicit plugin registry, run-scoped pipeline context, stronger lifecycle management, and targeted refactoring of the document conversion and chunker discovery layers.

## 2. Architectural Style & Patterns

### Core Architectural Style

The codebase primarily follows a Spring Boot service-oriented layered architecture with plugin-oriented extension points.

Main architectural areas:

| Area | Responsibility | Main Classes |
|---|---|---|
| Application entrypoint | CLI bootstrapping and command execution | `ChunkNorris`, `CliArgsParser` |
| Pipeline orchestration | Project traversal, veto checks, chunker execution, JSONL output | `ChunkProcess` |
| Chunker selection | Aggregates chunker providers and fallback behavior | `ChunkerFactory`, `ChunkerBroker`, `AnnotationBasedChunkerBrokerImpl` |
| Language-specific chunking | Java, TypeScript, Visual Basic chunk extraction | `JavaCodeChunker`, `TypeScriptCodeChunker`, `VisualBasicCodeChunker` |
| Document conversion | Converts documents to Markdown before chunking | `DocConversionRouter`, `DocConversionAgent` implementations |
| Veto subsystem | Pluggable file exclusion rules | `VetoRegistry`, `StandardVetos`, `DirectoryVetos` |
| Dependency extraction | Java symbol dependency metadata | `JavaDependencyHandler`, `DependencyRegistryManager`, `JavaDependenciesChunker` |

### Notable Patterns Used

#### 1. Plugin / Extension Pattern

The project supports extensibility through several mechanisms:

- `PxChunker` interface.
- `ChunkerBroker` interface.
- `LanguagePack` abstraction.
- Java `ServiceLoader`.
- Annotation-based discovery with `@Chunker`, `@PostWalkChunker`, and `@ChunkNorrisComponent`.
- Document conversion agents through `DocConversionAgent`.

This is one of the strongest architectural concepts in the codebase. It allows new chunkers and converters to be added without modifying the core traversal pipeline.

However, the extension model is fragmented. Chunkers can be discovered through:

- Spring injection.
- Reflection scanning.
- Language packs.
- ServiceLoader brokers.
- Annotated methods.
- Annotated classes.

This increases cognitive load and makes behavior harder to predict.

#### 2. Strategy Pattern

Chunkers and conversion agents are strategy implementations:

- `JavaCodeChunker`, `TypeScriptCodeChunker`, `VisualBasicCodeChunker`, `MarkdownChunker`, `FullTextChunker`, etc.
- `Pdf2TextConversionAgent`, `Docx2HtmlConversionAgent`, `Html2MDConversionAgent`, etc.

This separation is appropriate and aligns with the domain.

#### 3. Chain / Pipeline Pattern

`ChunkProcess` effectively implements a pipeline:

1. Resolve project.
2. Prepare transfer session.
3. Publish pre-walk event.
4. Walk files.
5. Apply vetoes.
6. Select chunkers.
7. Execute chunkers.
8. Serialize chunks.
9. Execute post-walk chunkers.

The high-level flow is understandable, but the pipeline lacks an explicit run context object. As a result, state is spread across singleton services and thread-local context.

#### 4. Event-Driven Hook

`SpringPreWalkEvent` allows components such as `JavaFQNamesMapper` to prepare state before traversal. This is a reasonable design for pre-indexing tasks, but the current event payload and state lifecycle are weakly controlled.

#### 5. Graph-Based Routing

`DocConversionRouter` models document format conversion as a weighted directed graph and uses Dijkstra shortest path. This is a strong architectural idea because it allows the system to select conversion chains dynamically based on cost and accuracy.

The model is promising, but the implementation needs stronger typing, validation, and error handling.

### Component Decoupling and Separation of Concerns

#### Good Separation

- File traversal is mostly separated from file chunking.
- Veto rules are separated from traversal.
- Code chunkers are separated by language.
- Document conversion agents are separated by source/target format.
- Language packs provide a coherent abstraction for language-specific features.

#### Weak Separation

- `ChunkProcess` handles traversal, output creation, encryption setup, chunk execution, path normalization, JSON serialization, and post-walk handling. It is becoming a god-service.
- Chunkers sometimes perform parsing, metadata enrichment, splitting, fallback handling, and dependency registration in one class.
- `AnnotationBasedChunkerBrokerImpl` is tightly coupled to Spring, reflection, annotations, file matching, and wrapper creation.
- `DocConversionRouter` handles graph construction, cost estimation, execution, logging, and source content loading.
- Vetoes rely on `ThreadLocal` state to access project-specific configuration.

The codebase would benefit from a more explicit pipeline architecture with smaller services and a run-scoped context.

## 3. Quality Attribute Evaluation

### Maintainability & Readability

#### Strengths

- Package organization is generally understandable:
  - `code.java`
  - `code.typescript`
  - `code.visualbasic`
  - `docs`
  - `docs.pdf`
  - `docs.poi`
  - `language`
  - `veto`
  - `util`
- Naming is mostly descriptive.
- Many classes have a single domain focus.
- Lombok is used consistently for constructor injection and data classes.
- Several comments explain important design decisions, especially around fallback chunkers and path rebasing.
- The language-pack abstraction is clean and easy to understand.

#### Concerns

##### Large Classes with Mixed Responsibilities

Examples:

- `ChunkProcess` combines orchestration, output management, encryption, event publishing, chunk invocation, JSON serialization, path rewriting, and processed-file tracking.
- `JavaCodeChunker` contains parsing, dependency extraction trigger, chunk creation, fallback raw splitting, enum handling, method handling, constructor handling, and frame generation.
- `MarkdownChunker` handles document conversion, metadata loading, markdown parsing, table preprocessing, section tracking, paragraph splitting, and chunk construction.
- `DocConversionRouter` combines route discovery, graph creation, cost estimation, conversion execution, and content collection.

These classes are maintainable now but will become difficult to evolve as more formats and rules are added.

##### Mixed Languages in Comments and Logs

Some comments and logs are German, while much of the code and API naming is English. This is not a functional problem, but it can reduce maintainability for international teams.

Examples:

- `"Fehler beim Chunking"`
- `"Ganze Datei als Zeilen-Units"`
- `"Textextraktion ist für Dokument..."`

A consistent project language would improve readability.

##### Reflection Reduces Type Safety

`AnnotationBasedChunkerBrokerImpl` and `VetoRegistry` depend on runtime method signature checks. This increases flexibility but makes behavior less discoverable and more failure-prone.

Examples:

- Annotated chunker methods are only validated at runtime.
- Generic return types such as `Stream<PxChunk>` are not actually verified.
- Method invocation exceptions are wrapped inconsistently.

##### Raw Types and Unchecked Casts

Several areas use raw types:

- `DocConversionRouter` injects `List<DocConversionAgent>`.
- `DocArtifakt` conversions frequently use raw constructors.
- Reflection wrappers cast `Stream<PxChunk>` unchecked.
- `new ValueContainer(Integer.MAX_VALUE)` lacks generic type parameter in `JavaCodeChunker`.

This weakens compile-time guarantees and can produce runtime failures that are difficult to trace.

##### Possible Java Version Mismatch

`MarkdownChunker` uses `headers.getLast()`, which is available on `List` only in newer Java versions. The Java parser configuration explicitly uses Java 17, suggesting the project may target Java 17. If so, this code will not compile.

### Extensibility

#### Strengths

The project is designed to be extensible in several dimensions:

- New file chunkers can implement `PxChunker`.
- New document conversions can implement `DocConversionAgent`.
- New language packs can implement `LanguagePack`.
- New veto rules can be added via `@ChunkVeto`.
- External chunker brokers can be provided via `ServiceLoader`.

The document conversion graph is especially extensible: adding a conversion edge enables new source-to-target routes without manually coding every route.

#### Concerns

##### Too Many Extension Mechanisms

The system currently has overlapping plugin models:

- `PxChunker` as Spring beans.
- Annotation-discovered chunker methods.
- Annotation-discovered chunker classes.
- `LanguagePackRegistry`.
- `ChunkerBroker`.
- `ServiceLoader<ChunkerBroker>`.
- Post-walk chunkers.
- Fallback chunkers.

This gives flexibility but makes extension behavior difficult to predict. For example, a new chunker may be picked up once, twice, or not at all depending on annotations, package, Spring registration, and language-pack inclusion.

##### Tight Coupling to Spring in Discovery

`AnnotationBasedChunkerBrokerImpl` scans classes manually and then asks Spring for bean instances. If no bean exists for an annotated class, it falls back to direct construction using a no-arg constructor.

This creates two construction modes:

- Spring-managed with dependency injection.
- Manually instantiated without dependency injection.

That can cause surprising failures if an annotated chunker requires dependencies but is not a Spring bean.

##### Fallback Logic Is Type-Based

`ChunkerFactory` treats `FullTextChunker` specially using `instanceof`.

This is brittle:

```text
boolean hasSpecific = matching.stream().anyMatch(c -> !(c instanceof FullTextChunker));
```

If another catch-all chunker is added, it must be manually excluded. A capability marker, priority, or chunker metadata model would scale better.

##### Document Conversion Routes Need Stronger Configuration

`ConversionRoutesConfig` supports predefined routes, but configuration binding appears incomplete or fragile:

- `ConversionRoute` is annotated with `@ConfigurationProperties`, but it represents a single route.
- `ConversionRoutesConfig` contains `predefinedRoutes` but is not itself annotated with `@ConfigurationProperties`.
- This may prevent route configuration from binding as intended.

### Robustness & Error Handling

#### Strengths

- Many chunkers catch parsing errors and return empty streams instead of crashing the entire run.
- Java chunking has a raw fallback when JavaParser fails.
- PDF resources are sometimes managed with try-with-resources.
- Project resolution produces a useful error listing available projects.
- Transfer encryption errors fall back to stdout instead of terminating.
- Veto context is cleared in a `finally` block.

#### Critical Concerns

##### Silent Failure Is Common

Many catch blocks suppress errors or return empty streams:

- `JavaFQNamesMapper.fillFQNamesMap()` catches `Exception` and ignores it with a FIXME.
- `VetoRegistry.VetoMethodWrapper.check()` catches exceptions and returns `false`.
- `DefaultLineBreakTextChunker.chunk()` catches `IOException` and returns empty.
- `FullTextChunker.chunk()` catches `IOException` and returns empty.
- `TypeScriptCodeChunker.chunk()` logs warning and returns empty.
- Several conversion agents wrap exceptions in generic `RuntimeException`.

Silent skipping may be acceptable for best-effort indexing, but it should be observable via structured logs and metrics.

##### Resource Leaks

Several resources are not closed reliably:

- `Files.walk(...)` streams in `ChunkProcess.executeForProject()` and `JavaFQNamesMapper.fillFQNamesMap()` are not used in try-with-resources.
- `TextChunker` opens a `BufferedReader` but does not close it.
- `Pdf2MDWithTikaDocConversionAgent` opens a `FileInputStream` without try-with-resources.
- `Doc2HtmlConversionAgent` opens `FileInputStream` and `PrintWriter` without try-with-resources.
- Output `PrintStream` closing is manual and not protected by `finally`.

These issues can lead to file handle leaks, especially during large project scans.

##### Shared Mutable State Across Runs

Important singleton state is not consistently cleared:

- `ChunkProcess.processedFiles` is cleared per run, but the service itself is singleton and not safe for concurrent execution.
- `JavaFQNamesMapper.name2FQNamesMap` is never cleared before a new pre-walk.
- `DependencyRegistry` is never cleared per project/run.
- `DependencyRegistry.clear()` clears only `dependencies`, not `usedBy`.
- `DependencyRegistryManager` stores registries in a non-concurrent `HashMap`.

This creates correctness risks in hub/in-process usage where multiple projects may be indexed sequentially or concurrently.

##### Thread Safety Risks

The code uses `.parallel()` in several places:

- `ChunkProcess.handlePath()`
- `ChunkProcess.doPostWalk()`
- `ChunkProcess.executeForFiles()`

Parallel execution interacts with:

- Shared dependency registries.
- Shared JavaParser static configuration.
- Shared output stream.
- Singleton chunker services.
- Mutable metadata maps in chunks.
- Non-thread-safe `HashMap` and `HashSet` usage.

`PrintStream.println` is synchronized internally, but output order is nondeterministic. More importantly, dependency collection and post-walk dependency chunking rely on shared mutable state.

##### Functional Defects and Edge Cases

Likely or possible defects include:

- `JavaDependencyHandler.fillDependencies()` calls `cu.getPrimaryType().get()` without checking presence.
- `RtfToHtmlAgent.convert()` creates an HTML artifact but does not add it to the input artifact’s children, so the route likely produces no output.
- `Image2MDConversionAgent.convert()` does not add the converted child when an exception occurs.
- `TextChunker` does not flush the final accumulated content at EOF.
- `TextChunker` does not close the reader.
- `TextChunker` throws `RuntimeException` on chunk failure, unlike most other chunkers.
- `StandardVetos.notListedInWhiteList()` uses substring after the last dot without checking if a dot exists.
- `StandardVetos.notListedInWhiteList()` uses `contains(ending)`, which can match partial extensions accidentally.
- `JavaFQNamesMapper.handleFile()` throws `RuntimeException`, but caller swallows all exceptions for the entire pre-walk.
- `DependencyRegistry.addDependency()` ignores dependencies outside one configured prefix, which may be expected but can make dependency output incomplete.
- `Pdf2ImageConversionAgent` uses lazy image suppliers tied to a PDF context and closes the PDF via post-conversion action; this lifecycle is fragile.
- `DocContentCollector.collectContent()` runs child traversal before applying current artifact conversion action; this ordering should be explicitly validated.

### Performance & Resource Efficiency

#### Strengths

- File traversal streams paths rather than eagerly loading all files.
- Chunkers generally process one file at a time.
- `ContentSplitter` is reused conceptually across formats.
- Java FQN mapping is performed once before walking.
- PDF image rendering is lazy in `Pdf2ImageConversionAgent`, avoiding immediate rendering of all pages.

#### Concerns

##### Repeated Scanning and Reflection

`AnnotationBasedChunkerBrokerImpl` scans the classpath lazily for normal chunkers and post-walk chunkers. This is acceptable for a CLI run, but startup/preparation could be clearer if discovery happened once during Spring initialization.

##### Graph Rebuild Per Conversion

`DocConversionRouter.buildGraph()` rebuilds the conversion graph every time `findBestPath()` is called. For many documents this adds unnecessary overhead. Since agents are mostly static, the base graph could be cached and edge weights recalculated per file.

##### PDF Text Detection Reopens Documents

`Pdf2TextConversionAgent.isPdfWithText()` loads the PDF to inspect text. Conversion may later load it again. `Pdf2MDWithTikaDocConversionAgent.estimateCosts()` also calls this check. This can be expensive for many PDFs.

##### Regex-Based TypeScript Parsing

`TypeScriptCodeChunker` uses regular expressions and brace counting. This is lightweight but brittle for modern TypeScript syntax, decorators, generics, arrow functions, multiline signatures, nested functions, comments, and strings containing braces.

A proper parser would improve correctness but may increase complexity.

##### Full File Reads

Most chunkers read full file contents into memory:

- `Files.readAllLines`
- `Files.readString`
- PDF/Word conversion
- Markdown conversion

This is acceptable for files bounded by veto max size, but some document conversions can still be heavy. The default veto size appears to be 1 MB, but converted content may exceed this.

##### Parallel Streams May Not Improve Throughput

Parallelizing chunkers for a single file is unlikely to produce major benefits because most files have only one or two matching chunkers. The overhead and thread-safety risk likely outweigh the performance gain.

## 4. Strengths & Best Practices

- Clear top-level pipeline for project chunking.
- Uses Spring Boot idioms for dependency injection and component discovery.
- Clean abstraction around `PxChunker`.
- Language pack abstraction is a good step toward modular language support.
- Document conversion modeled as a graph with cost and accuracy attributes.
- Veto subsystem is extensible and decoupled from traversal.
- Supports pre-walk event hooks for preparatory indexing.
- Good fallback design distinction between:
  - `FullTextChunker` for lossless full-text storage.
  - `DefaultLineBreakTextChunker` for searchable fallback chunks.
- Java chunker has a parse-failure raw fallback instead of dropping files completely.
- Chunk metadata includes useful semantic information such as code section, symbol metadata, hierarchy, line ranges, part/total, and parent IDs.
- Project-relative path rebasing reduces chunk ID collisions for same-named files.
- Directory veto logic carefully distinguishes excluded build/vendor folders from source folders.
- Transfer encryption is integrated into output creation.
- CLI bean names are made unique for multi-module hub usage.
- The architecture anticipates both standalone CLI and in-process hub use cases.
- Uses `ServiceLoader` to enable non-Spring plugin integration.
- Separation of document conversion agents makes adding formats straightforward.
- `ContentSplitter` usage standardizes splitting behavior across chunkers.
- Java dependency chunks as post-walk artifacts are a valuable architectural feature.

## 5. Identified Risks & Technical Debt

### 5.1 Fragmented Plugin Discovery

The chunker extension model is split across annotations, Spring beans, language packs, brokers, and `ServiceLoader`.

Risk:

- Duplicate chunkers.
- Missing chunkers.
- Difficult debugging.
- Unclear precedence.
- Special-case fallback behavior.

Impact: Medium to high as the ecosystem grows.

### 5.2 Reflection-Heavy Runtime Validation

`AnnotationBasedChunkerBrokerImpl` and `VetoRegistry` rely on reflection and runtime checks.

Risk:

- Compile-time safety is lost.
- Incorrect signatures fail late.
- Generic return types are unchecked.
- Annotated methods can be private or inaccessible unless explicitly handled.
- Errors are often wrapped as generic `RuntimeException`.

Impact: Medium.

### 5.3 Singleton State Leaks Across Runs

Stateful services are singleton-scoped:

- `ChunkProcess.processedFiles`
- `JavaFQNamesMapper.name2FQNamesMap`
- `DependencyRegistryManager.dependencyRegistryMap`
- `DependencyRegistry.dependencies`
- `DependencyRegistry.usedBy`

Risk:

- Cross-project contamination.
- Incorrect dependency chunks.
- Memory growth.
- Non-repeatable reindex results.
- Unsafe concurrent hub executions.

Impact: High.

### 5.4 Weak Thread Safety

Parallel stream usage is combined with shared mutable state.

Risk:

- Race conditions in registries.
- Nondeterministic output order.
- Potential concurrent modification issues.
- Hard-to-reproduce bugs.

Impact: High if concurrent or large-scale indexing is used.

### 5.5 Resource Management Gaps

Several file streams, readers, walkers, and parser inputs are not closed reliably.

Risk:

- File descriptor leaks.
- Locked files on Windows.
- Memory pressure.
- Broken long-running hub processes.

Impact: High for repeated in-process runs.

### 5.6 Inconsistent Error Handling

The codebase mixes:

- Silent empty streams.
- Warning logs.
- Error logs.
- Swallowed exceptions.
- Generic runtime exceptions.
- `System.exit(0)`.

Risk:

- Operational failures are hard to diagnose.
- Index completeness cannot be trusted.
- CLI behavior is difficult to test.
- Library/in-process usage may unexpectedly terminate the JVM.

Impact: Medium to high.

### 5.7 Document Conversion Bugs

Several conversion agents appear incomplete or risky:

- `RtfToHtmlAgent` does not add the generated HTML artifact as a child.
- `Image2MDConversionAgent` does not add a child on exception.
- `Doc2HtmlConversionAgent` writes an HTML sidecar file as an unexpected side effect.
- `Pdf2MDWithTikaDocConversionAgent` does not close the input stream.
- PDF lazy image conversion depends on post-conversion cleanup timing.

Impact: High for document-heavy projects.

### 5.8 Type Safety Issues in Document Model

`DocArtifakt<T,C>` and `DocConversionAgent<T,C>` attempt to model typed conversion, but raw types are used extensively.

Risk:

- Runtime `ClassCastException`.
- Hard-to-reason conversion paths.
- Loss of generic benefits.
- Incomplete conversion chains hidden until runtime.

Impact: Medium.

### 5.9 TypeScript Parser Fragility

The TypeScript chunker uses regex and brace counting.

Risk:

- Incorrect chunks for modern syntax.
- Missed methods/functions.
- Incorrect ranges when braces occur in strings/comments/templates.
- Poor handling of decorators, interfaces, type aliases, arrow functions, multiline declarations, generics, and nested constructs.

Impact: Medium.

### 5.10 Configuration Binding Risk

`ConversionRoutesConfig` and `ConversionRoute` configuration binding appears questionable.

Risk:

- Predefined routes may not load.
- Operators may believe they configured route preferences that are ignored.

Impact: Medium.

### 5.11 Veto Whitelist Matching Is Imprecise

`StandardVetos.notListedInWhiteList()` checks `def.getChunoWhiteList().contains(ending)`.

Risk:

- Extension `js` matches `json`.
- Extension extraction fails or behaves oddly for extensionless files.
- Case sensitivity may be inconsistent.

Impact: Medium.

### 5.12 Java Dependency Registry Incomplete Clearing

`DependencyRegistry.clear()` clears only outgoing dependencies, not `usedBy`.

Risk:

- Stale incoming dependency data in post-walk chunks.

Impact: Medium.

### 5.13 Output Ordering Is Nondeterministic

Parallel streams write JSONL lines directly to `PrintStream`.

Risk:

- Output order changes across runs.
- Snapshot tests are harder.
- Downstream systems may see inconsistent ordering.

Impact: Low to medium, depending on downstream assumptions.

### 5.14 Hidden Compile-Time Compatibility Risk

`MarkdownChunker` uses `List.getLast()`, which may require Java 21.

Risk:

- Compilation failure if project target is Java 17.

Impact: High if Java 17 is the intended runtime.

## 6. Actionable Recommendations

### Priority 1: Stabilize Runtime State and Resource Management

#### 1. Introduce a Run-Scoped `ChunkingContext`

Create a context object for each indexing run containing:

- Project definition.
- Root path.
- Processed files.
- Dependency registries.
- FQN maps.
- Output settings.
- Run ID.
- Metrics/logging context.

Avoid storing run-specific state in singleton services.

Recommended direction:

- `ChunkProcess.executeForProject(pd)` creates `ChunkingContext`.
- Pass context explicitly to pipeline components where needed.
- Remove or reduce `ThreadLocal` usage in vetoes.
- Clear all per-run registries at the beginning of each run.

#### 2. Close All IO Resources with Try-With-Resources

Refactor:

- `Files.walk(...)`
- `BufferedReader`
- `FileInputStream`
- `PrintWriter`
- `PrintStream`
- Tika/PDF inputs
- output streams

Example targets:

- `ChunkProcess.executeForProject`
- `JavaFQNamesMapper.fillFQNamesMap`
- `TextChunker.chunkTextFile`
- `Pdf2MDWithTikaDocConversionAgent.convert`
- `Doc2HtmlConversionAgent.convert`

#### 3. Remove Parallel Streams from Output Path Initially

Replace direct parallel JSONL writing with deterministic processing.

Recommended short-term change:

- Use sequential streams in `handlePath()` and `doPostWalk()`.
- If parallelism is needed later, parallelize at file level with controlled executor services and collect chunks before writing.

This will reduce thread-safety risks immediately.

#### 4. Fix Known Functional Bugs

Address at minimum:

- Add generated HTML artifact in `RtfToHtmlAgent`.
- Flush final content in `TextChunker`.
- Close `TextChunker` reader.
- Clear both `dependencies` and `usedBy` in `DependencyRegistry.clear()`.
- Clear `JavaFQNamesMapper.name2FQNamesMap` before pre-walk.
- Guard `JavaDependencyHandler.fillDependencies()` against missing primary type.
- Replace `headers.getLast()` if Java 17 compatibility is required.
- Replace whitelist `contains()` with parsed normalized extension set.

### Priority 2: Simplify and Strengthen Chunker Registration

#### 5. Consolidate Chunker Discovery

Choose one primary chunker registration mechanism.

Recommended model:

- Spring beans implementing `PxChunker` for built-in chunkers.
- `LanguagePack` for language-specific grouping.
- Optional external plugins via `ServiceLoader` or Spring Boot auto-configuration, but not both unless clearly documented.

Deprecate or reduce annotation-based method discovery unless it is a core requirement.

#### 6. Introduce Chunker Metadata

Create a metadata/capability interface or annotation:

- Supported file types.
- Priority.
- Whether the chunker is catch-all.
- Whether output is searchable.
- Whether it is post-walk.
- Whether it requires full project context.

This would eliminate brittle checks such as `instanceof FullTextChunker`.

Example conceptual model:

| Property | Purpose |
|---|---|
| `priority` | Determines ordering |
| `catchAll` | Identifies fallback/fulltext chunkers |
| `searchable` | Distinguishes searchable text from fulltext storage |
| `phase` | `PRE_WALK`, `PER_FILE`, `POST_WALK` |
| `fileTypes` | Matching without reflection |

#### 7. Validate Extensions at Startup

Add startup validation for:

- Duplicate chunker IDs.
- Invalid conversion routes.
- Missing conversion beans.
- Ambiguous language packs.
- Post-walk chunkers with shared mutable state.
- Unsupported Java runtime version.

Fail fast when configuration is invalid.

### Priority 3: Improve Document Conversion Architecture

#### 8. Strengthen `DocConversionRouter`

Refactor responsibilities:

- `ConversionGraphFactory`
- `ConversionCostEstimator`
- `ConversionExecutor`
- `ConversionRouteResolver`
- `DocContentLoader`

Benefits:

- Easier testing.
- Cleaner route selection.
- Better error reporting.
- Easier caching of the static graph.

#### 9. Fix Generics or Simplify the Document Model

The current generic model gives limited safety because raw types are common.

Options:

1. Make `DocArtifakt` non-generic and validate payload type at agent boundaries.
2. Keep generics but enforce typed conversion paths more strictly.
3. Add explicit `Class<T> sourcePayloadType()` and `Class<C> targetPayloadType()` to `DocConversionAgent`.

The first option may be simpler and more honest.

#### 10. Add Conversion Result Semantics

Instead of mutation-only `void convert(...)`, consider returning a result:

```text
ConversionResult {
  List<DocArtifakt> children;
  List<ConversionWarning> warnings;
  boolean successful;
}
```

This would improve observability and prevent silent no-output conversions.

#### 11. Remove Unexpected Side Effects

Avoid writing sidecar files such as `input.doc.html` inside conversion agents unless explicitly configured.

If debug artifacts are useful, place them behind a property such as:

```text
prjxp.conversion.debugArtifacts.enabled=true
```

### Priority 4: Improve Error Handling and Observability

#### 12. Standardize Error Policy

Define error behavior per pipeline phase:

| Phase | Suggested Behavior |
|---|---|
| Veto evaluation | Log warning and continue, but count failures |
| File chunking | Record file-level error and continue |
| Conversion | Return failed conversion result with reason |
| Output writing | Fail run unless fallback explicitly configured |
| Configuration parsing | Fail fast with non-zero exit code |

Avoid silent empty streams without diagnostics.

#### 13. Avoid `System.exit` in Application Services

`CliArgsParser` should not directly call `System.exit`.

Instead:

- Throw a typed exception.
- Return an exit code.
- Let the application runner decide termination.

This improves testability and in-process hub safety.

#### 14. Add Structured Metrics

Track:

- Files scanned.
- Files vetoed by reason.
- Files chunked.
- Files skipped due to errors.
- Chunks emitted.
- Conversion route selected.
- Conversion failures.
- Post-walk chunk counts.
- Time per chunker/converter.

This would make large indexing runs diagnosable.

### Priority 5: Improve Parser Correctness and Scalability

#### 15. Replace Regex-Based TypeScript Parsing

Consider a proper parser:

- Tree-sitter TypeScript.
- TypeScript compiler API via external process.
- ANTLR grammar.
- JavaScript parser with TypeScript support.

At minimum, add extensive tests for:

- Decorators.
- Multiline method signatures.
- Arrow functions.
- Interfaces.
- Type aliases.
- Generic methods.
- Nested functions.
- Braces in strings/template literals.

#### 16. Split Large Chunkers into Smaller Collaborators

For `JavaCodeChunker`, extract:

- `JavaImportChunkFactory`
- `JavaMethodChunkFactory`
- `JavaConstructorChunkFactory`
- `JavaEnumChunkFactory`
- `JavaClassFrameChunkFactory`
- `JavaRawFallbackChunker`

For `MarkdownChunker`, extract:

- `MarkdownPreprocessor`
- `MarkdownSectionParser`
- `MarkdownTableNormalizer`
- `MarkdownChunkFactory`

This will improve unit testability and reduce regression risk.

### Priority 6: Testing Strategy

#### 17. Add Architecture-Level Tests

Recommended tests:

- Chunker registry detects expected chunkers exactly once.
- FullText and default fallback behavior works as intended.
- Veto whitelist and directory exclusions behave correctly.
- Dependency registry is cleared between runs.
- Post-walk chunks are generated only after per-file Java dependency collection.
- Document conversion graph selects expected routes.
- Predefined conversion routes override graph routing.

#### 18. Add Golden-File Chunking Tests

For each file type:

- Java
- TypeScript
- Visual Basic
- Markdown
- TXT with Groovy script
- PDF text
- DOCX
- RTF

Assert:

- chunk IDs
- parent IDs
- content
- line ranges
- metadata
- part/total values

#### 19. Add Concurrency and Reindex Tests

Specifically test:

- Running two projects sequentially in the same JVM.
- Running two indexing jobs concurrently, if hub use case requires it.
- Reindexing the same project twice and comparing outputs.
- Dependency chunks not containing stale data.

## Overall Assessment

The project demonstrates a thoughtful and extensible architecture for code/document chunking, with strong domain abstractions around chunkers, language packs, vetoes, and conversion agents. The graph-based document conversion model and fallback chunking strategy are especially good design choices.

The primary architectural challenge is not the absence of structure, but rather the accumulation of overlapping extension mechanisms, mutable singleton state, reflection-based discovery, and inconsistent error/resource handling. These risks are manageable but should be addressed before the system is expanded with many more formats, plugins, or concurrent hub-based workflows.

A focused refactoring around run-scoped context, deterministic pipeline execution, consolidated chunker registration, and robust conversion handling would significantly improve maintainability, correctness, and operational reliability.

_This document was generated with .dp and gemini-3-flash-preview_

