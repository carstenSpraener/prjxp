# Software Architecture Assessment: Golden Retriever (gldrtrvr) – Multi-Language Code Retrieval & LLM Prompt Builder

## 1. Executive Summary

The Golden Retriever project is a Spring Boot–based system that indexes source code (Java, TypeScript, Visual Basic, Markdown) into hierarchical chunks, performs vector-similarity search, and assembles context-aware prompts for LLM consumption. It also includes a Javadoc auto-generation pipeline.

**Current State:** The architecture is functional and demonstrates a clear intent toward pluggability (Language Packs, Strategy-based ranking, Registry aggregation). However, it suffers from **severe code duplication** across four near-identical `PromptSession` classes, **fragile string-manipulation logic** for prompt assembly, **inconsistent error handling**, and **tight coupling** between language-specific components. The system is in an early-to-mid maturity stage: the skeleton is sound, but the internal implementation has accumulated significant technical debt that will compound rapidly as new languages or features are added.

**Key Strengths:**
- Well-defined extension points (LanguagePack, ChunkRankingStrategy, FileViewProvider, GoldenRetriever)
- Clean Spring DI wiring with constructor injection
- Graceful degradation in FileViewProviders (fallback to flat join when structure is missing)
- Deterministic ordering via TreeMap in RetrieverRegistry

**Most Critical Risks:**
- ~400 lines of duplicated PromptSession logic across 4 classes (maintenance multiplier)
- Fragile `String.indexOf` / `String.replace` based prompt splicing that will break on edge cases
- A confirmed **runtime bug** in `TypeScriptRetriever.insertBefore` (substring with negative index)
- No visible test coverage; the system is untestable in its current mutable-session design
- Blocking `Thread.sleep` for LLM rate-limiting in a service context

---

## 2. Architectural Style & Patterns

| Pattern | Where Applied | Assessment |
|---------|--------------|------------|
| **Strategy** | `ChunkRankingStrategy` (Java, TS, VB, Markdown rankers) | Well-applied. `ChunkRankingService` iterates strategies and delegates. Clean OCP. |
| **Registry / Service Locator** | `RetrieverRegistry` | Aggregates `LanguagePack`-provided retrievers + standalone beans. Deterministic ordering via `TreeMap`. Good. |
| **Plugin / Language Pack** | `LanguagePack` interface + `LanguagePacks.merge` | Enables external JARs to contribute languages via `ServiceLoader`. Strong extensibility. |
| **Session / Builder** | `JavaPromptSession`, `TypeScriptPromptSession`, etc. | Manages chunk-tree construction, ranking, budget, and prompt assembly. **Heavily duplicated** (see §5). |
| **Visitor** | `ChunkNode.visit(Consumer<ChunkNode>)` | Tree traversal for prompt building. Functional, but sorts children on every call. |
| **Template Method (implicit)** | `GoldenRetriever` interface (`buildPromptForFindings`, `retrieveSearchHits`) | Each language retriever implements the same contract. Reasonable. |
| **Observer / Event** | `JavaDocGeneratedEvent` + `@EventListener` | Decouples Javadoc generation from post-processing. Good. |
| **Layered (Spring-Boot-Idiomatic)** | Controller → Service → DAO | Standard Spring layering. `GldRtrvrQuestioner` → `GRPromptEnrichment` → `RetrieverRegistry` → `GoldenRetriever` → `PromptSession` → `ChunkNode`. |
| **Adapter** | `FileViewProvider` implementations | Converts internal chunk model to a renderable file view per language. |

**Separation of Concerns:**
- **Good:** FileViewProviders (rendering) are cleanly separated from Retriever/PromptSession (prompt building). The `FileViewProvider` is a read-only view; the `GoldenRetriever` is a prompt assembler.
- **Poor:** The `PromptModifier` interface is re-declared inside each `PromptSession` class rather than being a shared top-level type. The `VisualBasicPromptSession.buildSearchHits` method accepts `JavaPromptSession.PromptModifier` — a **cross-language type leak**.
- **Poor:** `GRPromptEnrichment` is a god-orchestrator: it handles vector search, fallback iteration, multi-retriever aggregation, budget enforcement, and prompt formatting in a single class.

---

## 3. Quality Attribute Evaluation

### Maintainability & Readability

- **Naming:** Generally good (`sortByFromLine`, `applyInDescendingIndexOrder`, `normalizeForMatch`). However, `rootForrest` (typo for "forest") appears in all four session classes. `sanatize` (typo for "sanitize") in `JavaDocEnricher`. `CLAZZ_FRAME` (non-standard spelling) in Java sections.
- **SOLID:**
  - **S:** `GRPromptEnrichment` violates SRP (search + iteration + aggregation + formatting).
  - **O:** `ChunkRankingStrategy` and `LanguagePack` are well-designed for open/closed. `PromptModifier` is NOT open/closed — it's re-declared per session.
  - **L:** `VisualBasicPromptSession.buildSearchHits` accepts `JavaPromptSession.PromptModifier` — a type-substitution violation.
  - **I:** `GoldenRetriever` bundles `buildPromptForFindings` and `retrieveSearchHits`; some implementations (TypeScript, Markdown) return `Collections.EMPTY_LIST` for the latter. Interface segregation could be improved.
  - **D:** Good DI throughout. However, `@Data` on `PromptSession` classes generates `equals`/`hashCode`/`toString` over mutable collections (`rootForrest`, `chunkStore`) — a code smell.
- **Readability:** The `render` methods in FileViewProviders are 80–120 lines of dense logic. The `modifyPromptByChunk` methods use `String.indexOf` + `substring` + `replace` in ways that are hard to reason about. German-language log messages and prompt strings reduce universality.

### Extensibility

- **Adding a new language:** Requires creating (1) a `FileViewProvider`, (2) a `GoldenRetriever`, (3) a `PromptSession`, (4) a `ChunkRankingStrategy`, (5) a `LanguagePack`. Steps 2–3 are ~90% boilerplate duplication. A **generic `AbstractPromptSession`** or a **configuration-driven session** would reduce this to 1–2 new classes.
- **Adding a new LLM provider:** `KIChatProvider` is injected by name (`chatProvider.getByName("default")`). The `@Value`-driven config in `GRPromptEnrichment` is reasonable but the hardcoded `"default"` string is a magic value.
- **Adding a new ranking strategy:** Trivially extensible via Spring auto-wiring of `List<ChunkRankingStrategy>`. **Best-in-class** extensibility point.
- **Adding a new retrieval fallback strategy:** The `reIterate` method is hardcoded to a 0.05 grid. Not easily pluggable.

### Robustness & Error Handling

- **Critical Bug:** `TypeScriptRetriever.insertBefore` calls `prompt.substring(0, splittIdx)` when `splittIdx < 0`, which throws `StringIndexOutOfBoundsException`. The `JavaPromptModifier` version correctly returns early. This is a **production-breaking defect**.
- **Null Safety:** `PxChunkService.findByPredicate` calls `.get()` on `Optional` — will throw `NoSuchElementException` if the project is not configured. `JavaRetriever.buildPromptForFindings` uses `.get()` while `TypeScriptRetriever` uses `.orElseThrow()` — inconsistent.
- **Exception Swallowing:** `GRPromptEnrichment.findRelevantScored` catches `UnsupportedOperationException` silently. `JavaDocEnricher` catches `InterruptedException` in a `Thread.sleep` and ignores it.
- **No Input Validation:** `render` methods in FileViewProviders assume non-null `getContent()`. The `firstLine` helper handles null, but `renderFrame` in TypeScript/VB does not guard against null frame content before `split`.
- **No Circuit Breaker / Retry:** The LLM call in `GldRtrvrQuestioner` has no retry, timeout, or circuit-breaker logic. A single slow LLM response blocks the thread.

### Performance & Resource Efficiency

- **O(n²) Tree Traversal:** `findRootForChunk` iterates all roots, and for each root calls `findChunkNodeInTree` (recursive, O(depth × branching)). For N chunks, worst case is O(N² × depth). No index (e.g., `Map<String, ChunkNode>`) is maintained.
- **Repeated Sorting in `visit`:** `ChunkNode.visit` calls `Collections.sort(childs, ...)` on **every** visit call. If `visit` is called multiple times (e.g., for prompt + search hits), sorting is redundant.
- **String Concatenation in `JavaPromptSession.buildPrompt`:** Uses `context += rp.treeContext()` (immutable String concatenation in a loop) instead of `StringBuilder`. The TypeScript and VB sessions correctly use `StringBuilder`. Inconsistent and a performance issue for large contexts.
- **Blocking I/O:** `JavaDocEnricher` uses `Thread.sleep` for rate-limiting LLM calls. In a Spring service, this blocks a container thread. Should use an async scheduler or a token-bucket.
- **Memory:** `chunkStore` (HashMap) in each session grows unboundedly during `buildGraphToRoot` recursion. No eviction or size cap.
- **Vector Search Window:** `retrievalWindow = Math.max(maxResults, vectorWindow)` with `vectorWindow` defaulting to 50. For large stores, this is a fixed cost per iteration. The fallback loop can call this multiple times.

---

## 4. Strengths & Best Practices

1. **Language Pack Plugin Architecture:** The `LanguagePack` + `LanguagePacks.merge` + `ServiceLoader` pattern allows third-party JARs to contribute new languages without modifying the core. This is a textbook example of the Open/Closed Principle.

2. **Strategy-Based Ranking:** `ChunkRankingService` with `List<ChunkRankingStrategy>` auto-wired by Spring is clean, testable, and trivially extensible. Each language provides its own ranker with a `supports()` guard.

3. **Deterministic Retriever Ordering:** `RetrieverRegistry` uses a `TreeMap` keyed by language name, ensuring stable, reproducible prompt assembly order regardless of Spring bean registration order.

4. **Graceful Degradation in FileViewProviders:** All four `FileViewProvider` implementations include a `joinAllUnits` fallback for when structural units (frames, sections) are missing. The system degrades to a flat document rather than failing.

5. **Splice-Plan Pattern (FileViewProviders):** The `SplicePlan` record + `applyInDescendingIndexOrder` approach for reassembling source files is a clean, functional way to handle in-place line replacement. The descending-order application correctly avoids index invalidation.

6. **Event-Driven Javadoc Post-Processing:** `JavaDocGeneratedEvent` + `@EventListener` decouples the generation pipeline from side-effects (logging, persistence). Easy to add new listeners.

7. **Use of Records for Value Objects:** `SplicePlan`, `RankedPrompt`, `Token`, `Range`, `JavaDocGeneratedEvent` all use Java records — immutable, concise, and appropriate.

8. **Constructor Injection Everywhere:** No field injection. All dependencies are `final` and injected via constructor (Lombok `@RequiredArgsConstructor`). This ensures immutability of dependencies and testability.

9. **Budget-Aware Prompt Assembly:** The `maxContentLength` + `skippedByBudget` pattern with a user-visible notification (`"[weitere %d Klassen wegen Groessenlimit nicht enthalten]"`) is a practical guard against LLM context overflow.

10. **Fallback Iteration with Bounded Loop:** `GRPromptEnrichment.reIterate` uses a 0.05 grid with a hard floor at 0.5 and an `abort` flag, preventing infinite loops while still attempting progressively looser thresholds.

---

## 5. Identified Risks & Technical Debt

### Critical (Production-Breaking)

| # | Issue | Location | Impact |
|---|-------|----------|--------|
| C1 | `insertBefore` with `splittIdx < 0` calls `substring(0, -1)` → `StringIndexOutOfBoundsException` | `TypeScriptRetriever.insertBefore` | Runtime crash when a method name is not found in the prompt |
| C2 | `@Data` on `PromptSession` classes generates `equals`/`hashCode` over mutable `HashMap`/`ArrayList` fields | All 4 `*PromptSession` classes | Incorrect equality, potential `ConcurrentModificationException` if used in collections, misleading `toString` in logs |
| C3 | `VisualBasicPromptSession.buildSearchHits` accepts `JavaPromptSession.PromptModifier` | `VisualBasicPromptSession` | Cross-language type coupling; breaks if Java session is refactored |

### High (Correctness / Maintainability)

| # | Issue | Location | Impact |
|---|-------|----------|--------|
| H1 | **~400 lines of duplicated logic** across 4 `PromptSession` classes (`setChunks`, `setChunksByScore`, `buildPrompt`, `buildSearchHits`, `findRootForChunk`, `findChunkNodeInTree`, `buildGraphToRoot`, `readChunk`) | All sessions | Any bug fix or feature addition must be replicated 4×. Already diverging (e.g., `RankedPrompt` has 2 fields in TS/MD but 3 in Java/VB) |
| H2 | `PromptModifier` interface re-declared in each session class | 4 locations | Cannot be shared; prevents a single generic session |
| H3 | `combineScoredChunksByID` duplicated in all 4 retrievers | 4 locations | Same logic, same bug surface |
| H4 | `buildGraphToRoot` **mutates the input chunk** (`c.setParent(null)`) | All sessions | Side-effect on caller's data; breaks if the same chunk object is reused |
| H5 | `findChunkNodeInTree` is O(n) recursive with no index | All sessions | O(N²) for N chunks in a flat tree; will degrade with large projects |
| H6 | `visit` sorts `childs` on every invocation | `ChunkNode` | Redundant O(n log n) per visit; should sort once at construction |
| H7 | `JavaPromptSession.buildPrompt` uses `String +=` in a loop | `JavaPromptSession` | O(n²) string allocation; inconsistent with other sessions using `StringBuilder` |
| H8 | `Thread.sleep` for LLM rate-limiting in a `@Service` | `JavaDocEnricher` | Blocks a Spring-managed thread; no backpressure; will exhaust thread pool under load |
| H9 | `Collections.EMPTY_LIST` (deprecated since Java 9) | TypeScript, Markdown retrievers | Will generate deprecation warnings; should use `List.of()` |
| H10 | `PxChunkService.findByPredicate` calls `.get()` on `Optional` | `PxChunkService` | `NoSuchElementException` if no active project; should be `orElseThrow` with a meaningful message |

### Medium (Design / Code Quality)

| # | Issue | Location | Impact |
|---|-------|----------|--------|
| M1 | `rootForrest` (typo) used as field name in all 4 sessions | All sessions | Confusing; should be `rootForest` |
| M2 | `sanatize` (typo for "sanitize") | `JavaDocEnricher` | Readability |
| M3 | `CLAZZ_FRAME` (non-standard) vs `CLASS_FRAME` (TS/VB) | Java sections | Inconsistent naming across languages |
| M4 | Hardcoded German strings in prompts and logs | Multiple | Not internationalizable; confusing for non-German developers |
| M5 | `GRPromptEnrichment` is a 200+ line god-class (search + iterate + aggregate + format) | `GRPromptEnrichment` | Violates SRP; hard to test in isolation |
| M6 | `modifyPromptByChunk` in `TypeScriptRetriever` is a 50-line switch with string manipulation | `TypeScriptRetriever` | Fragile; should be a strategy per section type |
| M7 | `JavaPromptModifier.toMethodName` has a "wild hack" for varargs (`...`) | `JavaPromptModifier` | Brittle; will break on other edge cases (generics, annotations) |
| M8 | `CliArgsParser` calls `System.exit(0)` inside an `@EventListener` | `CliArgsParser` | Prevents Spring context shutdown; bad practice in a managed container |
| M9 | No visible unit/integration tests in the provided codebase | Entire project | Unverifiable correctness; refactoring is high-risk |
| M10 | `@Value` fields in `GRPromptEnrichment` are not `final` (Lombok `@RequiredArgsConstructor` won't inject them) | `GRPromptEnrichment` | Potential `null` if Spring doesn't process `@Value` before use |

### Low (Cosmetic / Minor)

| # | Issue | Location |
|---|-------|----------|
| L1 | Unused import `BiFunction` in TypeScript/Java/Markdown sessions | Multiple |
| L2 | Unused import `JavaCodeSection` in `VisualBasicChunkRanker` | `VisualBasicChunkRanker` |
| L3 | `VisualBasicRetriever.retrieveSearchHits` uses variable name `javaChunks` for VB chunks | `VisualBasicRetriever` |
| L4 | `MarkdownRetriever.modifyPromptByChunk` takes `(PxChunk, String)` but the session's `PromptModifier` interface expects `(PxChunkDao, PxChunk, String)` — signature mismatch | `MarkdownRetriever` / `MarkdownPromptSession` |
| L5 | `MarkdownPromptSession` uses `BiFunction<PxChunk, String, String>` while other sessions use a custom `PromptModifier` interface | `MarkdownPromptSession` |

---

## 6. Actionable Recommendations

### Priority 1 – Fix Critical Defects (This Sprint)

1. **Fix `TypeScriptRetriever.insertBefore`:** Add a null/negative guard:
   ```java
   if (splittIdx < 0) {
       log.warning("Method name %s not found in prompt; appending instead.", methodName);
       return prompt + content;
   }
   ```
   Add a unit test that exercises the not-found path.

2. **Replace `@Data` with `@Getter` + `@Setter` (selective) on all `PromptSession` classes:** Remove auto-generated `equals`/`hashCode`/`toString` over mutable collections. If `toString` is needed for logging, write a custom one that excludes `chunkStore` and `rootForrest`.

3. **Decouple `VisualBasicPromptSession.buildSearchHits` from `JavaPromptSession.PromptModifier`:** Extract a shared top-level `PromptModifier` interface (e.g., `de.spraener.prjxp.gldrtrvr.chunks.PromptModifier`) and have all sessions reference it.

### Priority 2 – Eliminate Duplication (Next 1–2 Sprints)

4. **Extract `AbstractPromptSession<T extends PromptModifier>`:** Move `setChunks`, `setChunksByScore`, `buildPrompt`, `buildSearchHits`, `findRootForChunk`, `findChunkNodeInTree`, `buildGraphToRoot`, `readChunk`, `chunkStore`, `rootForest`, `maxContentLength` into a single abstract base class. Language-specific sessions override only the metadata key and the `RankedPrompt` record (or parameterize it). This eliminates ~350 lines of duplication.

5. **Extract `AbstractGoldenRetriever`:** Move `combineScoredChunksByID`, `isXChunk`, `combineChunks` into a base class parameterized by the metadata key. Each language retriever provides only `modifyPromptByChunk` and the section-type switch.

6. **Unify `PromptModifier` into a single shared interface:**
   ```java
   public interface PromptModifier {
       String modifyPrompt(PxChunkDao chunkDao, PxChunk chunk, String prompt);
   }
   ```
   Place it in `de.spraener.prjxp.gldrtrvr.chunks`. All sessions and retrievers reference this one type.

### Priority 3 – Improve Robustness & Performance (Next 2–3 Sprints)

7. **Add a `Map<String, ChunkNode> nodeIndex` to `ChunkNode`** (or to the session) for O(1) chunk lookup instead of recursive `findChunkNodeInTree`. Rebuild the index in `addChild`.

8. **Sort `childs` once in `addChild` (or at tree completion), not in `visit`:** Move the `Collections.sort` out of the hot path.

9. **Replace `String +=` in `JavaPromptSession.buildPrompt` with `StringBuilder`** (align with TS/VB sessions).

10. **Replace `Thread.sleep` in `JavaDocEnricher` with a `RateLimiter` (Guava) or a `Semaphore`-based async queue:** This unblocks the calling thread and allows configurable concurrency.

11. **Add input validation and null-guards in `FileViewProvider.render` methods:** Guard against null `getContent()`, empty lists, and malformed metadata before processing.

12. **Replace `Collections.EMPTY_LIST` with `List.of()`** in all retrievers.

13. **Add a depth/cycle guard to `buildGraphToRoot` and `findRootForChunk`:** Prevent infinite recursion on malformed chunk hierarchies (circular parent references).

### Priority 4 – Architectural Refinement (Backlog)

14. **Split `GRPromptEnrichment` into focused components:**
    - `VectorSearchService` (search + fallback iteration)
    - `PromptAggregator` (multi-retriever assembly + budget)
    - `PromptFormatter` (template application)
    This makes each unit independently testable.

15. **Make `PromptModifier` implementations per-section-type (Strategy):** Instead of a 50-line `switch` in `modifyPromptByChunk`, define a `Map<CodeSection, SectionPromptModifier>` that is auto-wired. Adding a new section type = adding one bean.

16. **Internationalize hardcoded German strings:** Move all user-facing strings (prompt templates, log messages, budget notifications) to a `MessageSource` or a `PromptTemplate` configuration.

17. **Add a comprehensive test suite:**
    - Unit tests for each `FileViewProvider.render` (golden-file tests with sample chunk lists)
    - Unit tests for `PromptSession.buildPrompt` with mock `ChunkNode` trees
    - Integration test for `GRPromptEnrichment` with an in-memory `PxChunkDao`
    - Contract test for `GoldenRetriever` implementations
    - Specifically test the `insertBefore` not-found path (regression for C1)

18. **Introduce a `PromptBuildContext` value object** to replace the `ValueContainer<String>` mutable accumulator in `visit`. This makes the visitor pattern more functional and thread-safe.

19. **Consider a reactive/async pipeline for `JavaDocEnricher`:** Use `CompletableFuture` or Project Reactor to parallelize LLM calls (respecting the rate limit via a `Semaphore`), rather than sequential `Thread.sleep`.

20. **Document the chunk ID convention:** The system relies heavily on ID structure (`FQN.methodName`, `FQN.methodName.jsdoc`, `FQN.methodName.javadoc`, `FQN.dependencies`). This is an implicit contract. Formalize it in a `ChunkId` value object with factory methods and validation.

---

*Assessment generated for the `de.spraener.prjxp.gldrtrvr` module. Scope: 18 source files provided. External dependencies (`PxChunk`, `PxChunkDao`, `ChunkNode` internals, `LanguagePacks`, `KIChatProvider`, `SearchParams`) were assessed based on their usage signatures.*

_This document was generated with .dp and gemini-3-flash-preview_

