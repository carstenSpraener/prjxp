# Software Architecture Assessment: Golden Retriever (gldrtrvr) – Multi-Language Code Context Retrieval for LLM Prompts

## 1. Executive Summary

The Golden Retriever project is a Spring Boot application that retrieves, ranks, and assembles relevant code and documentation chunks from a project's indexed codebase into structured prompts for LLM consumption. It supports five content types (Java, TypeScript, Visual Basic, Markdown, plain text) through a pluggable retriever architecture.

**Current State:** The architecture is functional and demonstrates a clear layered design with a Strategy/Registry pattern for extensibility. However, it suffers from significant code duplication across language-specific components (PromptSessions, Retriever combine logic), inconsistent abstractions, and several naming/quality issues that will compound maintenance cost as the project grows.

**Key Strengths:**
- Clean separation between retrieval, ranking, and prompt-assembly concerns
- Well-designed `LanguagePack` / `RetrieverRegistry` plugin architecture for adding new languages
- Thoughtful fallback/degradation strategies in FileViewProviders (splice → orphan → flat join)
- Configurable ranking via `ChunkRankingStrategy` with a composable base + modulator formula

**Most Critical Risks:**
- ~40% of the codebase is duplicated boilerplate across the four PromptSession classes and five Retriever classes
- A latent `StringIndexOutOfBoundsException` bug in `TypeScriptRetriever.insertBefore`
- O(n²) tree-traversal in `findRootForChunk` combined with N+1 database calls in `buildGraphToRoot`
- Inconsistent `PromptModifier` contracts across sessions (3-arg vs. `BiFunction`)

## 2. Architectural Style & Patterns

| Pattern | Where Applied | Assessment |
|---------|--------------|------------|
| **Layered Architecture** | Reader (FileViewProvider) → Retriever (GoldenRetriever) → PromptSession → PromptModifier → LLM | Clear vertical flow; each layer has a distinct responsibility |
| **Strategy** | `ChunkRankingStrategy` + `ChunkRankingService` | Well-implemented; `isModulator()` default method enables additive scoring without breaking existing strategies |
| **Registry / Plugin** | `LanguagePack` → `LanguagePackRegistry` → `RetrieverRegistry` | Good extensibility point; new languages plug in via a single `LanguagePack` bean |
| **Template Method** | `PromptSession.buildPrompt(PromptModifier, ...)` | The session defines the algorithm (tree-walk → rank → budget); the modifier supplies per-chunk rendering |
| **Observer** | `JavaDocGeneratedEvent` + `JavdocGeneratedPostProcessor` | Lightweight Spring event for decoupling Javadoc generation from post-processing |
| **Facade** | `GRPromptEnrichment` | Orchestrates vector search, iteration, multi-retriever assembly, and budget enforcement |
| **Spring Boot Idiomatic** | `@Component`, `@Service`, `@RequiredArgsConstructor`, `@Value`, `@EventListener` | Standard DI; constructor injection preferred over field injection (good) |

**Component Decoupling:**
- The `FileViewProvider` interface cleanly separates *rendering* (how a file looks) from *retrieval* (which chunks are relevant).
- The `GoldenRetriever` interface is the primary extension point for content types.
- **Weakness:** The `PromptSession` classes are *not* behind a shared interface or base class. Each language has its own concrete class with a *different* `PromptModifier` signature, preventing polymorphic use. The `RetrieverRegistry` returns `List<GoldenRetriever>`, but the sessions are instantiated *inside* each retriever, making them invisible to the registry.

## 3. Quality Attribute Evaluation

### Maintainability & Readability

- **Naming inconsistencies:** `rootForrest` (should be `rootForest`), `DEPENDENCIE_INFO` (should be `DEPENDENCY_INFO`), `CLAZZ_FRAME` (should be `CLASS_FRAME`), `sanatize` (should be `sanitize`). These propagate across multiple files and are confusing.
- **Language mixing:** German comments/strings (`"Hier ein Rumpf der Klasse"`, `"weitere %d Klassen wegen Groessenlimit nicht enthalten"`) are interspersed with English Javadoc. This is a team convention issue but hurts readability for non-German-speaking contributors.
- **SOLID violations:**
  - **SRP:** `TypeScriptRetriever` and `VisualBasicRetriever` embed their `modifyPromptByChunk` logic inline rather than delegating to a separate `PromptModifier` class (as `JavaRetriever` does with `JavaPromptModifier`). This makes the retriever 200+ lines and harder to test in isolation.
  - **OCP:** Adding a new language requires creating 4–5 new classes (Retriever, PromptSession, ChunkRanker, FileViewProvider, LanguagePack) with near-identical boilerplate. The PromptSession duplication means the "open for extension" principle is undermined by "closed for modification" of the duplicated code.
  - **DIP:** `PromptSession` classes depend on concrete `PxChunkDao` and `ChunkRankingService` via constructor, which is acceptable, but the `@Data` annotation generates setters for these, violating the immutability intent.
- **Code cleanliness:** Generally good use of records (`SplicePlan`, `RankedPrompt`, `Token`, `Range`), streams, and modern Java (switch expressions, `var`, text blocks). The `FileViewProvider` implementations are well-structured with clear private helpers.

### Extensibility

- **Adding a new language** (e.g., Python): Requires implementing `FileViewProvider`, `GoldenRetriever`, a `PromptSession`, a `ChunkRankingStrategy`, and a `LanguagePack`. The `LanguagePack` + `RetrieverRegistry` pattern makes the *registration* trivial, but the *implementation* is ~600 lines of near-duplicated code per language.
- **Adding a new ranking factor:** Clean. Implement `ChunkRankingStrategy`, annotate `@Component`, and it's auto-discovered by `ChunkRankingService`. The `isModulator()` default method is a nice touch.
- **Replacing the LLM provider:** `KIChatProvider` is injected into `GldRtrvrQuestioner`; the `getByName("default")` call suggests a registry pattern, but the hardcoded `"default"` string is a minor coupling.
- **Adding a new content type to an existing language:** The `FileViewProvider` and `GoldenRetriever` are per-language, not per-content-type. Adding, say, a "Kotlin" variant of the Java retriever would require a new `LanguagePack` rather than a configuration change.

### Robustness & Error Handling

- **Critical bug:** `TypeScriptRetriever.insertBefore()` logs a warning when `splittIdx < 0` but then *proceeds* to call `prompt.substring(0, splittIdx)`, which throws `StringIndexOutOfBoundsException`. The `JavaPromptModifier.insertBefore()` correctly returns the unmodified prompt in this case.
- **Null safety gaps:**
  - `sortByFromLine` in all FileViewProviders calls `unit.getMetadata().get(SECTION_KEY)` without null-checking `getMetadata()`.
  - `firstParagraph` in `MarkdownFileViewProvider` calls `paragraphs.get(0)` as a fallback after `min()` — safe only if the list is non-empty (guaranteed by the caller, but not self-documenting).
  - `buildGraphToRoot` mutates the input chunk (`c.setParent(null)`) when the parent is missing — a side effect that could corrupt shared state.
- **Exception handling:** `GRPromptEnrichment.findRelevantScored` catches `UnsupportedOperationException` to fall back to a different search method — a reasonable degradation strategy. However, `JavaDocEnricher` catches broad `Exception` and logs, which can mask bugs.
- **Edge cases:** The `FileViewProvider` implementations handle empty/null inputs well (e.g., `units == null ? List.of() : units`). The `DependencyCompactor` handles `maxEntries <= 0` gracefully.
- **`System.exit(0)` in `CliArgsParser`:** Prevents Spring context shutdown, finalizers, and makes the class untestable.

### Performance & Resource Efficiency

- **N+1 database calls:** `buildGraphToRoot` recursively calls `chunkDao.findById(parent)` for each ancestor. For a chunk 5 levels deep, this is 5 sequential DB round-trips. The `chunkStore` cache mitigates repeated lookups within a session, but the *initial* tree construction is O(depth) DB calls per chunk.
- **O(n²) tree search:** `findRootForChunk` iterates all roots and calls `findChunkNodeInTree` (recursive, O(tree-size)) for each. With N chunks and M roots, worst case is O(N × M × treeDepth). For a project with 500 chunks across 50 root classes, this is 25,000+ node visits.
- **Redundant sorting in `visit`:** `ChunkNode.visit` calls `Collections.sort(childs, ...)` on *every* invocation. Since `visit` is called once per root in `buildPrompt`, this is O(k log k) per node per call — unnecessary if the tree is static during the visit.
- **String concatenation in `JavaPromptSession.buildPrompt`:** Uses `context += rp.treeContext()` (String concatenation in a loop) instead of `StringBuilder`. The TypeScript and VisualBasic sessions correctly use `StringBuilder`. This creates O(n²) memory allocation for large prompts.
- **Blocking `Thread.sleep` in `JavaDocEnricher`:** Rate-limits LLM calls with a 5-second sleep in a sequential loop. For 100 undocumented methods, this is 500 seconds of blocking. An async or batched approach would be preferable.
- **`@Value` on non-final fields with `@Data`:** The `@Data` annotation generates setters for `docMaxChars`, `docWindowChars`, etc., meaning the `@Value`-injected values can be accidentally overwritten by generated setters.

## 4. Strengths & Best Practices

1. **`LanguagePack` + `RetrieverRegistry` plugin architecture:** Adding a new language is a single `@Component` class. The registry deduplicates by language name and merges with standalone retriever beans. This is a clean, testable extension mechanism.

2. **Composable ranking formula:** `ChunkRankingService` combines a base rank with an optional modulator (Leiden weight) using a configurable formula (`wBase * base + min(bGraph * weight, cap)`). The `isModulator()` default method on the interface is elegant — existing strategies are unaffected.

3. **Graceful degradation in FileViewProviders:** Each provider has a clear fallback chain (structured splice → orphan append → flat join-all). The `SplicePlan` record with `-1` sentinel for "not found" is clean.

4. **`DependencyCompactor`:** A focused, well-documented utility with a clear contract (truncate to N entries, preserve section headers, append a marker). Easy to test in isolation.

5. **`GRPromptEnrichment` iteration strategy:** The `reIterate` method implements a principled fallback (increase maxResults up to 16, then step down the similarity threshold on a 0.05 grid, abort below 0.5). The epsilon (`1e-9`) prevents infinite loops from float drift.

6. **Use of Java records for value objects:** `SplicePlan`, `RankedPrompt`, `Token`, `Range`, `JavaDocGeneratedEvent` — all appropriate uses of records for immutable data carriers.

7. **`@RequiredArgsConstructor` for DI:** Consistent constructor injection via Lombok, avoiding field-injection anti-patterns.

8. **`FileViewProvider` separation:** The rendering logic (how a file *looks*) is cleanly separated from the retrieval logic (which chunks are *relevant*). The `MarkdownFileViewProvider` in particular is well-structured with clear helper methods.

9. **`TextRetriever` as a simple fallback:** Demonstrates that not every content type needs the full tree/ranking machinery. It's a lightweight, self-contained retriever.

10. **`@SafeVarargs` on varargs methods:** Correctly applied to `buildPromptForFindings` and `retrieveSearchHits` to suppress unchecked warnings.

## 5. Identified Risks & Technical Debt

### Critical

| # | Issue | Location | Impact |
|---|-------|----------|--------|
| 1 | **`StringIndexOutOfBoundsException` bug** | `TypeScriptRetriever.insertBefore()` | When `splittIdx < 0`, the method logs a warning but then calls `prompt.substring(0, splittIdx)` → runtime crash. The `JavaPromptModifier` version handles this correctly. |
| 2 | **Massive PromptSession duplication** | `JavaPromptSession`, `TypeScriptPromptSession`, `VisualBasicPromptSession`, `MarkdownPromptSession` | ~150 lines × 4 = 600 lines of near-identical code. Any bug fix or feature addition must be replicated 4×. The `PromptModifier` interface is *different* across sessions (3-arg vs. `BiFunction`), preventing a shared base. |
| 3 | **Retriever `combineScoredChunksByID` duplication** | All 5 retrievers | ~25 lines of identical logic (group by ID, check `total > size`, load from DAO or combine in-memory) repeated 5×. |

### High

| # | Issue | Location | Impact |
|---|-------|----------|--------|
| 4 | **O(n²) + N+1 in tree construction** | `PromptSession.findRootForChunk` / `buildGraphToRoot` | For large projects (1000+ chunks), the recursive DB calls and linear tree scans create significant latency. No batch-loading or index-based lookup. |
| 5 | **`@Data` on PromptSession classes** | All 4 PromptSessions | Generates `equals`/`hashCode`/`toString` over `chunkStore` (HashMap), `chunkDao`, `rankingService` — expensive and semantically wrong. Also generates public setters for dependencies that should be immutable. |
| 6 | **Inconsistent `PromptModifier` contracts** | `MarkdownPromptSession` uses `BiFunction<PxChunk, String, String>`; others use a 3-arg interface with `PxChunkDao` | Prevents a unified `PromptSession` abstraction. Callers must know which session type they're dealing with. |
| 7 | **`buildGraphToRoot` mutates input** | All PromptSessions | `c.setParent(null)` modifies the caller's chunk object. If the same `PxChunk` instance is shared (e.g., from a cache), this corrupts state. |
| 8 | **`System.exit(0)` in Spring component** | `CliArgsParser` | Prevents graceful shutdown, breaks testability, and is a code smell in a managed container. |

### Medium

| # | Issue | Location | Impact |
|---|-------|----------|--------|
| 9 | **Typo propagation** | `rootForrest`, `DEPENDENCIE_INFO`, `CLAZZ_FRAME`, `sanatize` | Confusing for new developers; makes grep/search harder; suggests insufficient code review. |
| 10 | **Unimplemented `retrieveSearchHits`** | `TypeScriptRetriever`, `MarkdownRetriever` | Return `Collections.EMPTY_LIST` with a `// TODO` comment. Callers get silently empty results. |
| 11 | **Hardcoded German prompt strings** | `JavaPromptModifier`, `TypeScriptRetriever`, `VisualBasicRetriever`, `GldRtrvrQuestioner` | `"Hier ein Rumpf der Klasse"`, `"FRAGE: "` — not externalized to resource bundles. Makes i18n impossible and couples prompt text to code. |
| 12 | **`visit()` sorts on every call** | `ChunkNode.visit` | `Collections.sort(childs, ...)` inside the recursive visitor. Unnecessary if the tree is static during a single `buildPrompt` call. |
| 13 | **String concatenation in loop** | `JavaPromptSession.buildPrompt` | `context += rp.treeContext()` instead of `StringBuilder`. O(n²) for large prompts. |
| 14 | **Blocking `Thread.sleep` for rate limiting** | `JavaDocEnricher` | 5-second sleep per LLM call in a sequential loop. Not scalable; blocks the calling thread. |
| 15 | **Hardcoded path in `JavaDocEnricher`** | `Path.of("build/enriched-src")` | Not configurable; will break in different build environments. |
| 16 | **`@Value` on non-final fields with `@Data`** | `MarkdownRetriever`, `TextRetriever` | Generated setters can override `@Value`-injected values. The field initializer (`= 2000`) is redundant with `@Value`. |

### Low

| # | Issue | Location | Impact |
|---|-------|----------|--------|
| 17 | **Unused imports** | `TypeScriptPromptSession` imports `BiFunction`; `VisualBasicRetriever` imports `JavaCodeSection` | Minor code smell; IDE warnings. |
| 18 | **`Collections.EMPTY_LIST` vs `Collections.emptyList()`** | Multiple retrievers | `EMPTY_LIST` is deprecated since Java 9. |
| 19 | **`firstParagraph` fallback to `get(0)`** | `MarkdownFileViewProvider` | Works but is implicit; a `@NonNull` annotation or explicit check would be clearer. |
| 20 | **`normalizeForMatch` regex per line** | `TypeScriptFileViewProvider` | `replaceAll("\\s+", " ")` on every line comparison. Could be pre-computed. |

## 6. Actionable Recommendations

### Priority 1 – Fix Critical Bugs (This Sprint)

1. **Fix `TypeScriptRetriever.insertBefore`:** Add an early return when `splittIdx < 0` (mirror the `JavaPromptModifier` behavior):
   ```java
   if (splittIdx < 0) {
       log.warning("Methodenname %s nicht gefunden in Prompt", methodName);
       return prompt; // ← ADD THIS
   }
   ```

2. **Fix `JavaPromptSession.buildPrompt` string concatenation:** Replace `context += rp.treeContext()` with a `StringBuilder` (as the TypeScript/VisualBasic sessions already do).

3. **Remove mutation in `buildGraphToRoot`:** Instead of `c.setParent(null)`, create a defensive copy or use a local variable:
   ```java
   if (parentChunk == null || parentChunk.isEmpty()) {
       PxChunk copy = PxChunk.copy(c);
       copy.setParent(null);
       return buildGraphToRoot(copy);
   }
   ```

### Priority 2 – Eliminate Duplication (Next Sprint)

4. **Extract a `BasePromptSession` (or generic `PromptSession<T>`):**
   - Unify the `PromptModifier` interface to a single 3-arg signature: `String modifyPrompt(PxChunkDao dao, PxChunk chunk, String currentPrompt)`.
   - Move `findRootForChunk`, `findChunkNodeInTree`, `buildGraphToRoot`, `readChunk`, `setChunks`, `setChunksByScore`, `buildPrompt`, `buildSearchHits` into the base class.
   - Allow subclasses to override: the metadata key for type identification, the `RankedPrompt` record shape, and the ranking formula.
   - This eliminates ~500 lines of duplicated code and ensures bug fixes propagate to all languages.

5. **Extract `ChunkCombiner` utility:** Create a shared static method:
   ```java
   public static <T> List<T> combineById(PxChunkDao dao, List<T> items, 
       Function<T, PxChunk> chunkExtractor, Predicate<PxChunk> typeFilter)
   ```
   Replace the 5× duplicated `combineScoredChunksByID` / `combineChunksByID` in all retrievers.

6. **Extract `PromptModifier` implementations for TS and VB:** Follow the `JavaPromptModifier` pattern — create `TypeScriptPromptModifier` and `VisualBasicPromptModifier` classes, removing the inline `modifyPromptByChunk` from the retrievers. This makes each retriever ~80 lines instead of ~200.

### Priority 3 – Performance (Next 2 Sprints)

7. **Batch-load ancestors in `buildGraphToRoot`:** Collect all parent IDs first, issue a single `chunkDao.findByIds(Set<String>)` call, then build the tree in-memory. Reduces N sequential DB calls to 1.

8. **Index the tree for `findRootForChunk`:** Maintain a `Map<String, ChunkNode>` (chunkId → node) alongside the tree. Replace the O(n) recursive `findChunkNodeInTree` with an O(1) map lookup.

9. **Remove per-visit sorting in `ChunkNode.visit`:** Sort children once in `addChild` or in a `prepareVisit()` method called before the traversal, not on every recursive call.

10. **Replace `Thread.sleep` in `JavaDocEnricher` with an async rate limiter:** Use a `Semaphore` or a token-bucket pattern with a scheduled executor to parallelize LLM calls while respecting the rate limit.

### Priority 4 – Code Quality & Consistency (Ongoing)

11. **Fix all typos in a single PR:** `rootForrest` → `rootForest`, `DEPENDENCIE_INFO` → `DEPENDENCY_INFO`, `CLAZZ_FRAME` → `CLASS_FRAME`, `sanatize` → `sanitize`. Use IDE rename-refactoring to ensure all references update.

12. **Replace `@Data` with `@Getter` + `@Setter` (selective) on PromptSessions:** Remove generated `equals`/`hashCode`/`toString` over collections and dependencies. Make `chunkDao` and `rankingService` final (constructor-injected, no setter).

13. **Externalize prompt strings to resource bundles:** Move all German/English prompt templates to `messages.properties` (or a `PromptTemplates` class with configurable strings). This enables i18n and makes prompt tuning a configuration change rather than a code change.

14. **Implement or remove `retrieveSearchHits` TODOs:** Either implement the method for `TypeScriptRetriever` and `MarkdownRetriever`, or throw `UnsupportedOperationException` with a clear message. Silently returning an empty list is a trap for callers.

15. **Replace `System.exit(0)` in `CliArgsParser`:** Publish a `CliHelpRequestedEvent` or set a flag that the main application class checks after the Spring context is ready. Alternatively, use `SpringApplication.exit()`.

16. **Make `JavaDocEnricher` target path configurable:** Inject via `@Value("${prjxp.gldrtrvr.enriched-src-path:build/enriched-src}")`.

17. **Add unit tests for the `FileViewProvider` splice logic:** The `SplicePlan` / `applyInDescendingIndexOrder` / `normalizeForMatch` logic is complex and currently untested. Test with synthetic frame/method content covering: exact match, normalized match, no match (fallback), multiple methods in one frame, orphan methods.

18. **Add integration tests for `GRPromptEnrichment` iteration:** Verify the `reIterate` fallback loop terminates correctly at the 0.5 floor and that the 0.05 grid stepping works as documented.

19. **Standardize `@Value` usage:** For fields that need a default for non-Spring construction, use constructor parameters with defaults rather than field initializers + `@Value` on non-final fields. This avoids the `@Data`-generated-setter override risk.

20. **Introduce a `ContentLevel`-aware `PromptModifier` base:** The `JavaPromptModifier` already branches on `ContentLevel` (FULL / SKELETON / POINTERS). Generalize this so all language modifiers can support the same content-level semantics without duplicating the branching logic.

_This document was generated with .dp and gemini-3-flash-preview_

