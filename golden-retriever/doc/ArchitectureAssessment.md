# Software Architecture Assessment: Golden Retriever (gldrtrvr) – Multi-Language RAG Prompt Construction Engine

## 1. Executive Summary

The Golden Retriever project is a Spring Boot–based Retrieval-Augmented Generation (RAG) subsystem that retrieves semantically relevant code and documentation chunks from a project index, assembles them into hierarchical document trees, and renders language-specific prompts for LLM consumption. It supports Java, TypeScript, Visual Basic, Markdown, and plain-text content through a pluggable retriever architecture.

**Key Strengths:**
- Well-defined extension points via `LanguagePack`, `GoldenRetriever`, and `ChunkRankingStrategy` interfaces enable adding new languages without modifying core logic.
- The `RetrieverRegistry` provides a clean merged view over pack-provided and standalone retrievers with deterministic ordering.
- The `FileViewProvider` layer (reader) is cleanly separated from the retrieval/prompt layer, with each provider handling a single MIME type.
- The `ChunkRankingService` with its base/modulator composition model is a well-thought-out strategy pattern implementation.

**Most Critical Risks:**
- **Massive structural duplication** across four `*PromptSession` classes (Java, TypeScript, VisualBasic, Markdown) that are ~90% identical in tree-building logic, creating a high maintenance burden and divergence risk.
- **Fragile string-based prompt manipulation** (`indexOf`/`replace`/`substring`) in prompt modifiers that will silently corrupt output when method names appear multiple times or are absent.
- **Cross-language type coupling**: `VisualBasicPromptSession.buildSearchHits` accepts `JavaPromptSession.PromptModifier`, creating an inappropriate dependency between language modules.
- **O(n²) tree traversal** and recursive DB calls in the session tree-building path will degrade under large codebases.

## 2. Architectural Style & Patterns

| Pattern | Where Applied | Assessment |
|---------|--------------|------------|
| **Layered Architecture** | Reader (FileViewProvider) → Retriever (GoldenRetriever) → PromptSession → PromptModifier → LLM | Clear vertical separation; each layer has a distinct responsibility. |
| **Strategy** | `ChunkRankingStrategy` + `ChunkRankingService` | Well-implemented; the base/modulator split with `isModulator()` is a clean extension point. |
| **Registry / Plugin** | `LanguagePackRegistry`, `RetrieverRegistry`, `LanguagePack` | Enables multi-language support without core modification. Good use of `TreeMap` for deterministic ordering. |
| **Template Method** | `PromptSession.buildPrompt(PromptModifier, ...)` | The session defines the algorithm (tree walk → rank → budget); the modifier supplies per-chunk rendering. |
| **Observer / Event** | `JavaDocGeneratedEvent` + `JavdocGeneratedPostProcessor` | Lightweight decoupling for post-processing; appropriate for the use case. |
| **Facade** | `GRPromptEnrichment` | Orchestrates search → retrieval → formatting; however, it has grown into a god-class with 6+ overloads. |
| **Spring Boot Idiomatic** | `@Component`, `@Service`, `@RequiredArgsConstructor`, `@Value`, `@EventListener` | Standard Spring DI; constructor injection is consistently used. |

**Component Decoupling Analysis:**

The architecture follows a **unidirectional dependency flow**: `GldRtrvrQuestioner` → `GRPromptEnrichment` → `RetrieverRegistry` → individual `GoldenRetriever` implementations → `*PromptSession` → `ChunkRankingService` → `ChunkRankingStrategy` implementations. The `FileViewProvider` layer is a separate concern (rendering for file views) that does not participate in the retrieval pipeline.

However, decoupling breaks down at the **PromptSession level**: the four session classes are structurally identical but not abstracted into a shared base, and the `VisualBasicPromptSession` directly imports `JavaPromptSession.PromptModifier`, creating a lateral dependency between language modules that should be independent.

## 3. Quality Attribute Evaluation

### Maintainability & Readability

- **Naming:** Generally clear (`renderFrame`, `applyInDescendingIndexOrder`, `combineScoredChunksByID`). However, `rootForrest` (misspelling of "forest") appears in all four session classes. `childs` in `ChunkNode` should be `children`. The method `sanatize` in `JavaDocEnricher` is a typo for `sanitize`.
- **SOLID Adherence:**
  - **S:** `FileViewProvider` implementations are single-responsibility. `GRPromptEnrichment` violates SRP by handling search, iteration, budgeting, and formatting.
  - **O:** `ChunkRankingStrategy` is open for extension. `LanguagePack` is open for new languages. However, `PromptSession` is closed (duplicated, not abstracted).
  - **L:** `VisualBasicPromptSession.buildSearchHits(JavaPromptSession.PromptModifier, ...)` violates LSP – a VB session should not accept a Java-specific type.
  - **I:** `GoldenRetriever` interface is reasonably narrow. `PromptModifier` is a single-method interface (good).
  - **D:** The `PointerRenderer` is instantiated via `new` in three retrievers instead of being injected, violating dependency inversion.
- **German strings in production output:** `"weitere %d Klassen wegen Groessenlimit nicht enthalten"`, `"Hier ein Rumpf der Klasse"`, `"Relevante Information aus dem Projekt"` – these are user-facing LLM prompts, so German may be intentional, but they are not externalized to resource bundles, making i18n impossible.
- **`@Data` on session classes:** Generates `equals()`, `hashCode()`, `toString()` over all fields including `chunkStore` (a mutable HashMap) and `chunkDao` (a service reference). This is wasteful and can cause subtle bugs if sessions are used in collections.

### Extensibility

- **Adding a new language:** Requires creating (1) a `*FileViewProvider`, (2) a `*Retriever`, (3) a `*PromptSession`, (4) a `*ChunkRanker`, (5) a `*LanguagePack`. The `RetrieverRegistry` and `LanguagePackRegistry` will auto-discover them. However, because the `PromptSession` logic is duplicated rather than abstracted, each new language requires copy-pasting ~150 lines of tree-building code.
- **Adding a new ranking strategy:** Trivially extensible via `ChunkRankingStrategy` + `@Component`. The `ChunkRankingService` auto-discovers all strategies.
- **Adding a new content level:** `ContentLevel` is referenced in switch statements across multiple retrievers; adding a new level requires touching every retriever's `modifyPromptByChunk`.
- **Replacing the LLM provider:** `KIChatProvider` is injected, but `GldRtrvrQuestioner` hardcodes `chatProvider.getByName("default")`, reducing configurability.

### Robustness & Error Handling

- **Critical bug in `TypeScriptRetriever.insertBefore`:**
  ```java
  int splittIdx = prompt.indexOf(methodName);
  if (splittIdx < 0) {
      log.warning(...);
  }
  String prefix = prompt.substring(0, splittIdx);  // StringIndexOutOfBoundsException if splittIdx < 0
  ```
  The warning is logged but execution continues to `substring(0, -1)`, which throws. The `JavaPromptModifier` version correctly returns early.
- **`replaceInPrompt` uses `String.replace`:** This replaces ALL occurrences of the method name in the prompt, not just the first. If a method name like `get` appears in multiple contexts, the output will be corrupted.
- **Null safety gaps:** `pxChunk.getMetadata()` is called without null checks in `TypeScriptRetriever.modifyPromptByChunk` and `JavaPromptModifier.modifyPrompt`. The `MarkdownFileViewProvider` handles it, but the retrievers do not.
- **`JavaDocEnricher` uses `Thread.sleep` for rate limiting** in a batch loop – this blocks the calling thread and is not interruptible in a meaningful way (the `InterruptedException` is swallowed).
- **`GRPromptEnrichment.findRelevantScored`** catches `UnsupportedOperationException` to fall back to a non-vector search. This is a code smell indicating the DAO interface should declare capability rather than relying on exception-based dispatch.
- **`CliArgsParser` calls `System.exit(0)`** in an `@EventListener` – this makes the application untestable and terminates the JVM on any parse error.

### Performance & Resource Efficiency

- **O(n²) tree search:** `findRootForChunk` iterates over all roots and calls `findChunkNodeInTree` (recursive, O(depth × branching)) for each. With N chunks and M roots, worst case is O(N × M × depth). For a large codebase with hundreds of chunks, this becomes a bottleneck.
- **Recursive DB calls in `buildGraphToRoot`:** Each ancestor triggers a `chunkDao.findById()` call. For a 5-level hierarchy, that's 5 DB round-trips per chunk. The `chunkStore` cache mitigates repeated lookups but not the initial tree construction.
- **`ChunkNode.visit` sorts children on every invocation:** `Collections.sort(childs, ...)` is called on every visit, even though the tree structure doesn't change during a single `buildPrompt` call.
- **`normalizeForMatch` in `TypeScriptFileViewProvider`:** Applies `replaceAll("\\s+", " ")` (regex) on every line in a nested loop (methods × frame lines). For a class with 50 methods and a 200-line frame, that's 10,000 regex operations.
- **`PxChunkService.findByPredicate`** loads ALL chunks from the DAO (`findAll()`) and filters in memory. For a project with 100k+ chunks, this is a full-table scan.
- **`GRPromptEnrichment` do-while loop:** The re-iteration loop can execute multiple full vector searches. The abort condition (`minScore < 0.5`) provides a bound, but each iteration re-queries the vector store.
- **`@Value` field initializers:** `@Value("${...:10}") private int maxDependencyEntries = 10;` – the field initializer is redundant and can cause confusion about which value is actually used (Spring overwrites it, but non-Spring construction uses the initializer).

## 4. Strengths & Best Practices

1. **Clean `FileViewProvider` abstraction:** Each provider is a self-contained `@Component` with a single `render()` method. The `MarkdownFileViewProvider`'s use of a `Block` record for intermediate representation is clean and testable.

2. **Splice-plan pattern in code file providers:** The `SplicePlan` record + `applyInDescendingIndexOrder` approach in `JavaFileViewProvider`, `TypeScriptFileViewProvider`, and `VisualBasicFileViewProvider` is a well-designed algorithm for in-place code reconstruction. The descending-order application to preserve indices is a smart detail.

3. **`ChunkRankingService` composition model:** The separation of base rankers from modulators (via `isModulator()`) with a configurable formula (`wBase * base + min(bGraph * weight, cap)`) is a clean, extensible design that allows graph-based re-ranking without modifying language-specific rankers.

4. **`RetrieverRegistry` merged view:** The `TreeMap`-based deduplication and the "pack-provided first, then standalone" ordering is deterministic and well-documented.

5. **`DependencyCompactor` as a focused utility:** Small, static, well-documented, with clear input/output contract. The `MARKER` constant makes the truncation indicator configurable.

6. **`LanguagePack` as a plugin boundary:** The `LanguagePack` interface (language, mimeType, retriever) provides a clean seam for adding new languages as Spring modules without touching the core.

7. **`GRPromptEnrichment.reIterate` with grid-snapping:** The fallback iteration logic that snaps to a 0.05 grid and aborts below 0.5 is a well-thought-out termination strategy that prevents infinite loops.

8. **`TextRetriever` as a simple, focused implementation:** Unlike the code-language retrievers, it doesn't build a tree – it just formats and budgets. This is appropriate for its use case and demonstrates that the architecture supports different complexity levels.

9. **Use of Java records for value objects:** `SplicePlan`, `RankedPrompt`, `Token`, `Range`, `Block` – all use records, providing immutability and concise syntax.

10. **`MarkdownFileViewProvider`'s hierarchical rendering:** The two-pass approach (sections first, then orphan paragraphs) with `LinkedHashMap` for order preservation is clean and handles edge cases (empty sections, orphan groups) gracefully.

## 5. Identified Risks & Technical Debt

### Critical

| # | Issue | Location | Impact |
|---|-------|----------|--------|
| 1 | **`TypeScriptRetriever.insertBefore` throws `StringIndexOutOfBoundsException`** when the method name is not found in the prompt. The warning is logged but execution continues to `substring(0, -1)`. | `TypeScriptRetriever.java:~130` | Runtime crash on any prompt where the expected marker is absent. |
| 2 | **`String.replace` replaces ALL occurrences** in `replaceInPrompt` (both Java and TS retrievers). A method named `get` will be replaced everywhere it appears in the prompt. | `JavaPromptModifier`, `TypeScriptRetriever` | Silent prompt corruption; LLM receives garbled context. |
| 3 | **`VisualBasicPromptSession.buildSearchHits` accepts `JavaPromptSession.PromptModifier`** – a cross-language type dependency. | `VisualBasicPromptSession.java` | Violates module independence; adding a new language that reuses the VB session would inherit the Java coupling. |

### High

| # | Issue | Location | Impact |
|---|-------|----------|--------|
| 4 | **~150 lines of identical tree-building code duplicated across 4 PromptSession classes** (`findRootForChunk`, `findChunkNodeInTree`, `buildGraphToRoot`, `readChunk`, `loadAndCombine`, `setChunks`, `setChunksByScore`). | All `*PromptSession` classes | Any bug fix or improvement must be applied 4×; divergence is inevitable. |
| 5 | **Inconsistent `PromptModifier` signatures:** Java/TS/VB sessions use a 3-arg `PromptModifier` (dao, chunk, prompt); Markdown uses `BiFunction<PxChunk, String, String>`. | `*PromptSession` classes | Callers must know which signature to use; prevents a unified `buildPrompt` API. |
| 6 | **`PointerRenderer` instantiated via `new`** in `JavaRetriever`, `TypeScriptRetriever`, and `MarkdownRetriever` instead of being injected. | Three retriever classes | Breaks DI; cannot be mocked in tests; cannot be configured via Spring. |
| 7 | **O(n²) tree traversal + recursive DB calls** in `buildGraphToRoot` / `findRootForChunk`. | All `*PromptSession` classes | Performance degradation with large codebases (500+ chunks). |
| 8 | **`GRPromptEnrichment` is a god-class** with 6+ overloaded `enrich` methods, handling search, iteration, budgeting, formatting, and test-filtering. | `GRPromptEnrichment.java` | Hard to test, hard to modify, high cyclomatic complexity. |

### Medium

| # | Issue | Location | Impact |
|---|-------|----------|--------|
| 9 | **`@Data` on mutable session classes** generates `equals`/`hashCode`/`toString` over `chunkStore` (HashMap) and `chunkDao` (service). | All `*PromptSession` | Wasteful; potential `ConcurrentModificationException` in `toString`; misleading equality. |
| 10 | **`ChunkNode.visit` sorts children on every call.** | `ChunkNode.java` | Unnecessary O(n log n) per visit; the tree is static during a single `buildPrompt`. |
| 11 | **`PxChunkService.findByPredicate` loads all chunks** via `findAll()` then filters in memory. | `PxChunkService.java` | Full-table scan; OOM risk with large indices. |
| 12 | **`Thread.sleep` for LLM rate limiting** in `JavaDocEnricher` blocks the thread; `InterruptedException` is swallowed. | `JavaDocEnricher.java` | Not interruptible; not scalable; blocks the event loop if called from a reactive context. |
| 13 | **`System.exit(0)` in `CliArgsParser` event listener.** | `CliArgsParser.java` | Untestable; terminates JVM on any parse error; prevents graceful shutdown. |
| 14 | **German strings hardcoded in production output** (budget-exceeded messages, section headers). | Multiple retrievers/modifiers | Not i18n-ready; not externalized to resource bundles. |
| 15 | **`@Value` with redundant field initializers** (`= 10`, `= 50000`). | Multiple classes | Confusing; the initializer is dead code in Spring context but active in unit tests. |
| 16 | **`retrieveSearchHits` is a TODO stub** in `TypeScriptRetriever` and `MarkdownRetriever`. | Two retrievers | Incomplete feature; callers get empty results silently. |
| 17 | **`ChunkNode` field named `childs`** (should be `children`). | `ChunkNode.java` | Typo in a public API; hard to rename without breaking callers. |
| 18 | **`rootForrest` field name** (misspelling of "forest") in all four session classes. | All `*PromptSession` | Typo in a core data structure; pervasive. |

### Low

| # | Issue | Location | Impact |
|---|-------|----------|--------|
| 19 | **`JavaDocEnricher.sanatize`** (typo) uses fragile string manipulation with multiple `.replace()` calls. | `JavaDocEnricher.java` | Brittle to LLM output format changes. |
| 20 | **`GldRtrvrQuestioner` hardcodes** `chatProvider.getByName("default")` and a German prompt template. | `GldRtrvrQuestioner.java` | Not configurable; not i18n-ready. |
| 21 | **`MarkdownFileViewProvider` uses `MAX_HEADER_LEVEL = 10`** with a linear scan of metadata keys `h1`–`h10`. | `MarkdownFileViewProvider.java` | Minor; could be a loop over a configurable range. |
| 22 | **`TextSegment2PxChunkConverter`** is a static utility with a single method; could be a Spring bean for testability. | `TextSegment2PxChunkConverter.java` | Minor; static is acceptable for a pure function. |

## 6. Actionable Recommendations

### Priority 1 – Fix Critical Bugs (Immediate)

1. **Fix `TypeScriptRetriever.insertBefore`:** Add an early return when `splittIdx < 0` (mirror the `JavaPromptModifier` behavior). Add a unit test for the "marker not found" case.

2. **Replace `String.replace` with first-occurrence replacement** in `replaceInPrompt` (both Java and TS). Use `indexOf` + `substring` to replace only the first match. Add tests for method names that appear multiple times in the prompt.

3. **Decouple `VisualBasicPromptSession` from `JavaPromptSession`:** Define a shared `PromptModifier` interface in a common package (e.g., `de.spraener.prjxp.gldrtrvr.chunks.PromptModifier`) and have all sessions reference it. Remove the `JavaPromptSession.PromptModifier` import from the VB session.

### Priority 2 – Eliminate Structural Duplication (Short-term, 1–2 sprints)

4. **Extract a `AbstractPromptSession` base class** (or a composition-based `PromptSessionSupport` helper) containing:
   - `chunkStore`, `chunkDao`, `rootForest` (rename from `rootForrest`), `maxContentLength`, `rankingService`
   - `findRootForChunk`, `findChunkNodeInTree`, `buildGraphToRoot`, `readChunk`, `loadAndCombine`
   - `setChunks`, `setChunksByScore`
   - `buildPrompt` (with the shared budget/ranking logic)
   
   Each language-specific session then only provides: the `PromptModifier` implementation, the metadata key for `ChunkNode` type, and any language-specific `buildSearchHits` logic.

5. **Unify the `PromptModifier` interface** to a single signature: `String modifyPrompt(PxChunkDao dao, PxChunk chunk, String prompt)`. Update `MarkdownPromptSession` to use this instead of `BiFunction`. This enables a single `buildPrompt(PromptModifier, ...)` API across all sessions.

6. **Inject `PointerRenderer` as a Spring bean** (`@Component`) and add it to the constructor of `JavaRetriever`, `TypeScriptRetriever`, and `MarkdownRetriever` via `@RequiredArgsConstructor`. Remove the `new PointerRenderer()` field initializers.

### Priority 3 – Performance & Scalability (Medium-term)

7. **Replace O(n²) tree search with an ID→Node index:** Maintain a `Map<String, ChunkNode>` (id → node) alongside the tree. `findRootForChunk` becomes O(1) lookup + O(depth) walk to root. `findChunkNodeInTree` becomes a direct map lookup.

8. **Batch the `buildGraphToRoot` DB calls:** Instead of recursive `findById` per ancestor, collect all ancestor IDs first (iterative walk), batch-load them in a single `findByIds(...)` call, then build the tree in memory.

9. **Make `ChunkNode.visit` sort-free:** Sort children once in `addChild` (or at tree construction time) rather than on every `visit` call. Alternatively, use a `TreeSet` with a comparator for children.

10. **Replace `PxChunkService.findByPredicate` with a DAO-level query:** Push the predicate into the store (e.g., as a metadata filter or a SQL/DSL query) to avoid loading the entire table.

### Priority 4 – Robustness & Code Quality (Medium-term)

11. **Externalize all user-facing strings** (budget-exceeded messages, section headers, LLM prompt templates) to `messages.properties` / `messages_de.properties` resource bundles. Use `MessageSource` for resolution.

12. **Refactor `GRPromptEnrichment`** into:
    - `SearchExecutor` (vector search + fallback + re-iteration)
    - `ContextAssembler` (budget management, retriever orchestration, test filtering)
    - `PromptFormatter` (template application)
    
    The `GRPromptEnrichment` facade delegates to these, keeping the public API stable.

13. **Replace `Thread.sleep` in `JavaDocEnricher`** with a proper rate limiter (e.g., Resilience4j `RateLimiter` or a token-bucket) that is configurable and non-blocking.

14. **Remove `System.exit(0)` from `CliArgsParser`:** Throw a `CliParseException` that is handled at the application entry point. This makes the parser testable and allows graceful shutdown.

15. **Add null-safety guards** for `pxChunk.getMetadata()` in all `modifyPromptByChunk` methods. Use a helper: `Map<String, String> meta = Optional.ofNullable(chunk.getMetadata()).orElse(Map.of());`

16. **Replace `@Data` with `@Getter` + `@Setter`** on `*PromptSession` classes (or use `@Value` for truly immutable fields). Exclude `chunkStore` and `chunkDao` from `toString` via `@ToString.Exclude`.

### Priority 5 – Completeness & Long-term Architecture (Long-term)

17. **Implement `retrieveSearchHits`** for `TypeScriptRetriever` and `MarkdownRetriever` (currently TODO stubs). The `JavaPromptSession.buildSearchHits` and `VisualBasicPromptSession.buildSearchHits` patterns can be reused.

18. **Rename `rootForrest` → `rootForest`** and `childs` → `children` across the codebase. Use IDE refactoring to update all references. Add a deprecation period if these are part of a public API.

19. **Introduce a `PromptBudget` value object** to encapsulate `maxContentLength`, `totalContentLength`, and the "skipped by budget" counter. This eliminates the repeated `if (sb.length() + part.length() > budget)` pattern and makes budgeting testable in isolation.

20. **Add integration tests** for the full pipeline: `GRPromptEnrichment.enrich` → `RetrieverRegistry.all()` → each retriever's `buildPromptForFindings` → assert on output structure (headers, code fences, budget markers). This would have caught the `insertBefore` crash and the `replace`-all bug.

21. **Consider a `ContentLevel`-driven rendering strategy** within each retriever: instead of a `switch` on `ContentLevel` inside `modifyPromptByChunk`, use a `Map<ContentLevel, Function<PxChunk, String>>` or a strategy object per level. This makes adding a new content level a one-line registration rather than a switch-case addition in every retriever.

22. **Document the `PxChunk` ID convention** (FQN-based, dot-separated, with suffixes like `.jsdoc`, `.javadoc`, `.dependencies`) in a dedicated `IdConvention` class or architecture decision record (ADR). The implicit coupling between chunkers and retrievers via ID string manipulation is a hidden contract that should be explicit.

_This document was generated with .dp and gemini-3-flash-preview_

