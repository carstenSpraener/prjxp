# Software Architecture Assessment: Golden Retriever (gldrtrvr) – Code-Aware LLM Prompt Builder

## 1. Executive Summary

The Golden Retriever project is a Spring Boot application that indexes source code (Java, TypeScript, Visual Basic, Markdown, plain text) into hierarchical chunks, retrieves relevant chunks via vector search, and assembles LLM-ready prompts. It also supports Javadoc auto-generation and CLI-driven question answering.

**Current State:** The system is functional and demonstrates a clear domain model (chunks → trees → prompts → LLM). The plugin architecture via `LanguagePack` and `RetrieverRegistry` provides a reasonable extension point for new languages. However, the codebase suffers from **extensive copy-paste duplication** across language-specific components (PromptSessions, Retrievers, FileViewProviders), **fragile string-manipulation-based prompt assembly**, and **inconsistent error handling**. The most critical risks are the maintenance burden from duplication, the potential for silent prompt corruption via `indexOf`/`substring` operations, and the absence of any visible test suite.

**Key Strengths:**
- Clean plugin/registry architecture for language extensibility
- Well-defined domain model (`PxChunk`, `ChunkNode`, `ScoredChunk`)
- Sensible fallback/degradation strategies in FileViewProviders
- Strategy pattern for chunk ranking

**Most Critical Risks:**
- ~60% of the codebase is duplicated across language variants with only metadata-key differences
- Fragile string-index-based prompt mutation (silent corruption on mismatch)
- No visible unit/integration tests
- Blocking `Thread.sleep` for LLM rate limiting
- Unimplemented `retrieveSearchHits` in 3 of 5 retrievers

## 2. Architectural Style & Patterns

### Core Architectural Patterns

| Pattern | Where Applied | Assessment |
|---------|--------------|------------|
| **Strategy** | `ChunkRankingStrategy`, `PromptModifier` | Well-applied; enables per-language ranking and prompt formatting |
| **Plugin / Registry** | `LanguagePack` → `LanguagePackRegistry` → `RetrieverRegistry` | Good extensibility; new languages plug in via a pack |
| **Template Method (implicit)** | `GoldenRetriever` interface | Defines the contract; implementations vary in internal logic |
| **Visitor** | `ChunkNode.visit(Consumer)` | Used for tree traversal during prompt building |
| **Builder (ad-hoc)** | `PromptSession` classes | Manages state accumulation; not a formal Builder |
| **Layered** | CLI → Questioner → Enrichment → Retriever → DAO | Clear request flow, but layers are thin |
| **Observer / Event** | `JavaDocGeneratedEvent` + `@EventListener` | Decouples Javadoc generation from post-processing |

### Component Decoupling & Separation of Concerns

The architecture follows a **modular monolith** with clear package boundaries:

```
gldrtrvr/
├── reader/          → FileViewProvider implementations (full-source rendering)
├── code/{lang}/     → Language-specific Retriever + PromptSession + Ranker
├── md/              → Markdown Retriever + PromptSession + Ranker
├── text/            → Plain-text Retriever
├── chunks/          → ChunkNode, ChunkRankingService, ChunkRankingStrategy
├── language/        → LanguagePack implementations
├── enrichment/      → GRPromptEnrichment (orchestration)
├── javadoc/         → JavaDocEnricher + event
└── (root)           → RetrieverRegistry, GldRtrvrQuestioner, CliArgsParser
```

**Decoupling Assessment:**
- **Good:** `LanguagePack` isolates language-specific retrievers; `RetrieverRegistry` aggregates them without the caller knowing specifics. `ChunkRankingStrategy` is a clean SPI.
- **Poor:** `VisualBasicPromptSession.buildSearchHits` accepts `JavaPromptSession.PromptModifier` — a direct cross-language type dependency that breaks the module boundary.
- **Poor:** The `PromptSession` classes are not abstracted; each language re-implements the entire tree-building and prompt-assembly logic rather than sharing a base class or delegating to a generic engine.

## 3. Quality Attribute Evaluation

### Maintainability & Readability

| Aspect | Rating | Notes |
|--------|--------|-------|
| Naming | ⚠️ Mixed | `rootForrest` (typo for "forest"), `sanatize` (typo for "sanitize"), `CLAZZ_FRAME` (non-standard), `dependenyChunk` (typo). German/English mix in comments and user-facing strings. |
| SOLID – SRP | ⚠️ Violated | `GRPromptEnrichment.enrich()` is a 60+ line method handling search, iteration, budget, formatting, and validation. `JavaDocEnricher.enrichProject()` handles parsing, rate-limiting, LLM calls, file I/O, and event publishing. |
| SOLID – OCP | ⚠️ Partially | Adding a new language requires creating 4–5 new classes (Retriever, PromptSession, Ranker, FileViewProvider, LanguagePack) with ~80% duplicated code. The `LanguagePack` helps, but the internal duplication negates the benefit. |
| SOLID – DIP | ⚠️ Mixed | `ChunkRankingService` depends on the `ChunkRankingStrategy` abstraction (good). But `PromptSession` classes directly depend on `PxChunkDao` concrete type and hardcode metadata keys. |
| Code Cleanliness | ⚠️ Moderate | Inconsistent use of `@Data` on mutable state objects; `Collections.EMPTY_LIST` (deprecated); unused imports (`BiFunction` in several files); `@SafeVarargs` on non-final classes. |

### Extensibility

- **Adding a new language** (e.g., Python): Requires implementing `FileViewProvider`, `GoldenRetriever`, `PromptSession`, `ChunkRankingStrategy`, and `LanguagePack`. The `LanguagePack` registry makes discovery automatic, but the **PromptSession duplication** means ~200 lines of boilerplate per language. A generic `AbstractPromptSession<T>` parameterized by metadata key and section enum would eliminate this.
- **Adding a new LLM provider:** Handled externally via `KIChatProvider` (not in this codebase). The `GldRtrvrQuestioner` calls `chatProvider.getByName("default")` — a string-based lookup that is fragile.
- **Adding a new retrieval strategy:** `ChunkRankingStrategy` is a clean SPI. New strategies are auto-discovered via Spring's `List<ChunkRankingStrategy>` injection.
- **Adding a new file format:** `FileViewProvider` is a clean SPI. New formats plug in via `@Component`.

### Robustness & Error Handling

| Issue | Location | Severity |
|-------|----------|----------|
| `insertBefore` in `TypeScriptRetriever` calls `prompt.substring(0, splittIdx)` when `splittIdx < 0` → **StringIndexOutOfBoundsException** | `TypeScriptRetriever.insertBefore` | 🔴 Critical |
| `Collections.EMPTY_LIST` (deprecated, mutable) returned from 3 retrievers | Multiple | 🟡 Medium |
| `get().get()` on `Optional` without null-check → NPE | `PxChunkService`, `JavaRetriever`, `VisualBasicRetriever` | 🟡 Medium |
| `buildGraphToRoot` mutates input: `c.setParent(null)` | All PromptSessions | 🟡 Medium |
| `Thread.sleep` in `JavaDocEnricher` swallows `InterruptedException` silently | `JavaDocEnricher` | 🟡 Medium |
| `findChunkNodeInTree` returns null; caller in `findRootForChunk` does `findChunkNodeInTree(root, parent).addChild(...)` → **NPE** if parent not found | All PromptSessions | 🔴 Critical |
| No null-check on `chunk.getContent()` in several rankers (NPE if content is null) | `TypeScriptChunkRanker`, `MarkdownChunkRanker` | 🟡 Medium |
| `GRPromptEnrichment` do-while loop has no max-iteration guard beyond the 0.5 score floor | `GRPromptEnrichment` | 🟡 Medium |
| `JavaDocEnricher.sanatize` catches `IOException` from `BufferedReader` on a `StringReader` (impossible) — dead code | `JavaDocEnricher` | 🟢 Low |

### Performance & Resource Efficiency

| Concern | Detail |
|---------|--------|
| **O(n²) tree traversal** | `findChunkNodeInTree` is a recursive linear search. Called for every chunk in `setChunks`. For a tree of depth *d* with *n* nodes, worst case is O(n·d). No index (Map<String, ChunkNode>) for O(1) lookup. |
| **Redundant sorting in `visit`** | `ChunkNode.visit` sorts `childs` on every recursive call → O(n log n) per node → O(n² log n) total for the tree. |
| **`findAll()` in `PxChunkService`** | Loads the entire chunk store into memory, then filters with a `Predicate`. For large projects (10k+ chunks), this is a significant memory and latency issue. |
| **Blocking `Thread.sleep` for rate limiting** | `JavaDocEnricher` blocks the calling thread for 5 s between LLM calls. Not parallelizable; a semaphore or async scheduler would be more appropriate. |
| **String concatenation in `JavaPromptSession.buildPrompt`** | Uses `context += rp.treeContext()` (immutable String concat in a loop) instead of `StringBuilder`. The TypeScript and VisualBasic variants correctly use `StringBuilder`. |
| **Repeated `split("\n", -1)` calls** | In FileViewProviders, content is split multiple times (once for matching, once for replacement). Could be cached. |
| **`computeIfAbsent` with `HashMap` in `readChunk`** | Not thread-safe. If `PromptSession` is ever shared across threads, this will corrupt the cache. |

## 4. Strengths & Best Practices

1. **Plugin Architecture via `LanguagePack`:** The `LanguagePack` → `LanguagePackRegistry` → `RetrieverRegistry` chain is a clean, Spring-idiomatic way to support multiple languages without modifying core logic. New languages are discovered automatically.

2. **`ChunkRankingStrategy` SPI:** The strategy pattern for ranking is well-designed. `ChunkRankingService` iterates strategies and returns the first match — simple, extensible, and testable.

3. **Graceful Degradation in FileViewProviders:** Each `FileViewProvider` has a `joinAllUnits` fallback when the expected structure (frames, sections) is absent. This prevents hard failures on malformed input.

4. **Splice-Plan Pattern in FileViewProviders:** The `SplicePlan` record + descending-index application is a clever way to handle multiple insertions into a line list without index invalidation. The descending-order guarantee is well-documented.

5. **`ValueContainer` for mutable accumulator in visitor:** Using a single-element container to thread state through a `Consumer`-based visitor is a pragmatic solution to Java's lack of mutable visitor return values.

6. **`@SafeVarargs` on `buildPromptForFindings`:** Correctly applied to varargs methods with `Function<String, Boolean>` parameters, suppressing the compiler warning appropriately.

7. **`RetrieverRegistry` deduplication logic:** Merges pack-provided retrievers with standalone beans, using a `TreeMap` for deterministic ordering. This is a thoughtful aggregation pattern.

8. **`GRPromptEnrichment` iterative retrieval with score-flooring:** The `reIterate` method implements a principled fallback strategy (snap to 0.05 grid, abort below 0.5) that prevents infinite loops while maximizing recall.

9. **`TextRetriever` as a simple, well-documented fallback:** The Javadoc clearly explains the design intent (recombine by ID, present as-is) and its relationship to other retrievers.

10. **Use of Java Records for value objects:** `SplicePlan`, `RankedPrompt`, `Token`, `Range`, `JavaDocGeneratedEvent` all use records appropriately.

## 5. Identified Risks & Technical Debt

### 🔴 Critical

| # | Risk | Detail |
|---|------|--------|
| 1 | **Prompt corruption via `indexOf`/`substring`** | `TypeScriptRetriever.insertBefore` will throw `StringIndexOutOfBoundsException` when the method name is not found in the prompt. `JavaPromptModifier.insertBefore` handles this correctly (returns prompt unchanged), but the TypeScript variant does not. This is a **production crash risk**. |
| 2 | **NPE in `findRootForChunk`** | `findChunkNodeInTree(root, parent).addChild(...)` will NPE if the parent chunk was loaded but is not actually in the tree (e.g., due to a race or data inconsistency). Present in all four PromptSession classes. |
| 3 | **No test suite** | No test files are present in the provided codebase. Given the complexity of tree-building, splicing, and prompt assembly, the absence of tests is a severe risk for any refactoring. |

### 🟡 High

| # | Risk | Detail |
|---|------|--------|
| 4 | **Massive code duplication (~60%)** | `JavaPromptSession`, `TypeScriptPromptSession`, `VisualBasicPromptSession`, `MarkdownPromptSession` share ~90% identical code (tree building, ranking, budget, validation). The only differences are the metadata key string and the `PromptModifier` interface signature. Similarly, `combineScoredChunksByID` is duplicated across 4 retrievers. |
| 5 | **Cross-language type coupling** | `VisualBasicPromptSession.buildSearchHits` accepts `JavaPromptSession.PromptModifier` — a type from a different language module. This breaks the module boundary and will cause compile errors if the Java module is refactored. |
| 6 | **`@Data` on mutable state objects** | `ChunkNode`, `JavaPromptSession`, `TypeScriptPromptSession`, etc. use `@Data` which generates `equals`/`hashCode`/`toString` over all fields including mutable collections. This makes them unsuitable for use in `HashSet`/`HashMap` and produces misleading `toString` output. |
| 7 | **Blocking rate-limiting** | `JavaDocEnricher` uses `Thread.sleep(5000)` between LLM calls. This blocks the thread, prevents parallelism, and is not configurable at runtime. |
| 8 | **`findAll()` for predicate queries** | `PxChunkService.findByPredicate` loads all chunks into memory. For a project with 50k+ chunks, this will cause OOM or severe GC pressure. |
| 9 | **Unimplemented `retrieveSearchHits`** | 3 of 5 retrievers (TypeScript, VisualBasic, Markdown) return `Collections.EMPTY_LIST` with a `// TODO` comment. The `GldRtrvrQuestioner` and any consumer expecting search hits will silently get empty results. |
| 10 | **`System.exit(0)` in `CliArgsParser`** | Terminates the JVM from within a Spring `@EventListener`. Breaks testability, prevents graceful shutdown, and is called in both the success (help) and error paths. |

### 🟢 Medium / Low

| # | Risk | Detail |
|---|------|--------|
| 11 | **Inconsistent i18n** | User-facing strings mix German ("Hier ein Rumpf der Klasse", "weitere %d Klassen wegen Groessenlimit") and English. The `JavaDocEnricher` prompt is in German. This makes the system harder to localize and confusing for non-German-speaking users. |
| 12 | **Typo-ridden identifiers** | `rootForrest`, `sanatize`, `CLAZZ_FRAME`, `dependenyChunk`, `DEPENDENCIE_INFO` — these propagate through the codebase and make grep/search harder. |
| 13 | **`@Value` with field initializer** | `@Value("${...:50000}") private int maxContentLength = 50000;` — the field initializer is redundant and confusing; the `@Value` annotation already provides the default. |
| 14 | **`Collections.EMPTY_LIST`** | Deprecated since Java 9. Should be `List.of()` or `Collections.emptyList()`. |
| 15 | **Unused imports** | `BiFunction` imported in `TypeScriptPromptSession`, `JavaPromptSession`, `MarkdownPromptSession` but never used. |
| 16 | **`@SafeVarargs` on non-final class** | `TypeScriptPromptSession` is not `final` but has `@SafeVarargs` on a method — this is only valid on `final` methods, `static` methods, or constructors. The compiler may warn. |
| 17 | **`JavaDocEnricher` path logic** | Hardcoded `"build/enriched-src"` target path; the `src` index search in the path string is fragile (breaks if "src" appears in a directory name earlier in the path). |

## 6. Actionable Recommendations

### Priority 1 – Immediate (Bug Fixes & Crash Prevention)

| # | Action | Effort |
|---|--------|--------|
| 1.1 | **Fix `TypeScriptRetriever.insertBefore`:** Add a null/`< 0` guard before `substring`. Mirror the safe pattern from `JavaPromptModifier.insertBefore`. | 15 min |
| 1.2 | **Guard `findChunkNodeInTree` result in `findRootForChunk`:** Add a null-check before calling `.addChild()`. Apply to all four PromptSession classes. | 30 min |
| 1.3 | **Replace `Collections.EMPTY_LIST`** with `List.of()` in all three unimplemented `retrieveSearchHits` methods. | 10 min |
| 1.4 | **Replace `get().get()`** with `orElseThrow(() -> new IllegalStateException("No chunk store for project: " + projectName))` in `PxChunkService` and all retrievers. | 20 min |
| 1.5 | **Remove `System.exit(0)`** from `CliArgsParser`. Throw a `CliHelpRequestedException` or set a flag; let the Spring context handle shutdown. | 30 min |

### Priority 2 – Structural Refactoring (Deduplication)

| # | Action | Effort |
|---|--------|--------|
| 2.1 | **Extract `AbstractPromptSession`:** Create a generic base class (or a composition-based `PromptSessionEngine`) that encapsulates: `chunkStore`, `rootForrest` (rename to `rootForest`), `maxContentLength`, `findRootForChunk`, `findChunkNodeInTree`, `buildGraphToRoot`, `readChunk`, `loadAndCombine`, `buildPrompt` (budget + validation loop). Language-specific subclasses provide only: the metadata key, the `PromptModifier` type, and the `RankedPrompt` record. This eliminates ~400 lines of duplication. | 1–2 days |
| 2.2 | **Extract `ChunkCombiningUtil`:** The `combineScoredChunksByID` and `combineChunksByID` methods are identical across all retrievers except for the `isXxxChunk` predicate. Extract to a static utility or a shared base class parameterized by a `Predicate<PxChunk>`. | 4 hours |
| 2.3 | **Unify `PromptModifier` interface:** Currently `JavaPromptSession.PromptModifier`, `TypeScriptPromptSession.PromptModifier`, and `VisualBasicPromptSession.PromptModifier` are separate but identical interfaces. Create a single top-level `PromptModifier` interface in the `chunks` package. Fix the `VisualBasicPromptSession.buildSearchHits` signature to use it. | 2 hours |
| 2.4 | **Extract `AbstractFileViewProvider`:** The four `FileViewProvider` implementations share the splice-plan pattern, `sortByFromLine`, `joinAllUnits`, and `applyInDescendingIndexOrder`. Extract a template-method base class with hooks for: `normalizeForMatch`, `lastClosingIndex`, `stripPrefix`, and `sectionKey`. | 1 day |

### Priority 3 – Performance & Robustness

| # | Action | Effort |
|---|--------|--------|
| 3.1 | **Add a `Map<String, ChunkNode>` index** to `ChunkNode` (or the PromptSession) for O(1) chunk lookup instead of recursive `findChunkNodeInTree`. Maintain it in `addChild`. | 4 hours |
| 3.2 | **Move sorting out of `visit`:** Sort `childs` once in `addChild` (insertion sort or a `TreeSet`) rather than on every `visit` call. | 2 hours |
| 3.3 | **Replace `findAll()` in `PxChunkService`** with a DAO-level predicate query (e.g., `chunkDao.findByMetadataKey(key, value)` or a native query). If the DAO doesn't support it, add a `findByPredicate` method to the `PxChunkDao` interface. | 1 day |
| 3.4 | **Replace `Thread.sleep` with a `Semaphore` or `RateLimiter`** (e.g., Guava's `RateLimiter` or a custom token bucket) in `JavaDocEnricher`. Allow parallel processing of files with a bounded concurrency. | 4 hours |
| 3.5 | **Use `StringBuilder` in `JavaPromptSession.buildPrompt`** (currently uses `String +=`). Align with the TypeScript/VisualBasic implementations. | 15 min |
| 3.6 | **Make `chunkStore` thread-safe** or document that `PromptSession` is single-threaded. If multi-threaded access is possible, use `ConcurrentHashMap`. | 1 hour |

### Priority 4 – Code Quality & Consistency

| # | Action | Effort |
|---|--------|--------|
| 4.1 | **Rename typos:** `rootForrest` → `rootForest`, `sanatize` → `sanitize`, `CLAZZ_FRAME` → `CLASS_FRAME`, `DEPENDENCIE_INFO` → `DEPENDENCY_INFO`, `dependenyChunk` → `dependencyChunk`. Use IDE rename-refactor to catch all usages. | 2 hours |
| 4.2 | **Remove `@Data` from `ChunkNode` and PromptSession classes.** Replace with explicit `@Getter`/`@Setter` for only the fields that need it. Add a manual `toString` that excludes large content fields. | 3 hours |
| 4.3 | **Standardize i18n:** Extract all user-facing strings to a `messages.properties` (or `messages_de.properties` / `messages_en.properties`). Use `MessageSource` for resolution. At minimum, make the German strings configurable. | 1 day |
| 4.4 | **Remove unused imports** (`BiFunction` in PromptSessions, `HashMap` where `Map` suffices, etc.). Add a static-analysis rule (Checkstyle/SpotBugs) to CI. | 1 hour |
| 4.5 | **Remove redundant `@Value` field initializers** in `GRPromptEnrichment`. | 15 min |
| 4.6 | **Implement or remove `retrieveSearchHits`** in TypeScript, VisualBasic, and Markdown retrievers. If not needed, remove the method from the `GoldenRetriever` interface or provide a default no-op. | 4 hours |

### Priority 5 – Testing & Observability

| # | Action | Effort |
|---|--------|--------|
| 5.1 | **Add unit tests for `FileViewProvider` implementations:** Test the splice logic with known frame/method inputs, including edge cases (no match, duplicate signatures, orphan methods, empty content). | 2 days |
| 5.2 | **Add unit tests for `PromptSession` tree-building:** Test `buildGraphToRoot`, `findRootForChunk`, `addChild` with various parent/child configurations. Verify the `rootForrest` structure. | 1 day |
| 5.3 | **Add integration tests for `GRPromptEnrichment`:** Mock the `PxChunkDao` and `RetrieverRegistry`; verify the iterative retrieval, budget enforcement, and prompt formatting. | 1 day |
| 5.4 | **Add a `PromptModifier` contract test:** Verify that `insertBefore`/`replaceInPrompt` handle the "not found" case gracefully (no exception, no corruption). | 4 hours |
| 5.5 | **Add structured logging** (replace `log.info` with MDC-enriched logs) for the retrieval pipeline: log the number of chunks retrieved, the effective score threshold, the budget usage, and the final prompt length. | 4 hours |

### Priority 6 – Long-Term Architecture

| # | Action | Effort |
|---|--------|--------|
| 6.1 | **Introduce a `PromptAssemblyStrategy`** to replace the fragile `indexOf`/`substring`/`replace` string manipulation. Define a structured prompt model (e.g., a list of `PromptSection` with anchors) and a deterministic assembler. This eliminates the entire class of "method name not found in prompt" bugs. | 3–5 days |
| 6.2 | **Make `GRPromptEnrichment` a pipeline:** Decompose the monolithic `enrich` method into a sequence of named steps (Retrieve → Combine → Rank → Assemble → Validate → Format), each as a separate method or a `Function<Context, Context>` in a list. This improves testability and allows per-step configuration. | 2 days |
| 6.3 | **Externalize the `LanguagePack` to a SPI or Spring Boot Starter:** Allow third-party JARs to contribute language packs without modifying the core module. Use `SpringFactoriesLoader` or a `META-INF/services` file. | 1 day |
| 6.4 | **Add a `PromptBudget` abstraction:** Currently `maxContentLength` is a raw `int` on each session. Create a `PromptBudget` value object that tracks remaining capacity, supports per-section allocation, and provides a `canFit(String)` method. This makes the budget logic testable and reusable. | 4 hours |

_This document was generated with .dp and gemini-3-flash-preview_

