# Software Architecture Assessment: Golden Retriever (gldrtrvr) – Multi-Language Code Retrieval & Prompt Assembly

## 1. Executive Summary

The Golden Retriever module is a multi-language code retrieval and LLM prompt-assembly subsystem within the broader `prjxp` (Project XP) platform. It ingests indexed code chunks (from a vector/document store), reconstructs hierarchical source views per language (Java, TypeScript, Visual Basic, Markdown, plain text), ranks them, and assembles context-bounded prompts for LLM consumption.

**Key Strengths:**
- Clear layered separation between file rendering, retrieval, prompt building, and ranking.
- Strategy + Registry pattern enables pluggable language support via `LanguagePack`.
- Consistent use of Spring DI, records, and modern Java (17+) idioms.
- Thoughtful content-budget enforcement and fallback iteration logic in `GRPromptEnrichment`.

**Most Critical Risks:**
- **Severe code duplication** across four near-identical `*PromptSession` classes and five near-identical `combineScoredChunksByID` methods, creating a high maintenance burden and divergence risk.
- **Fragile string-based prompt mutation** (`indexOf`/`replace`/`substring`) that will silently corrupt prompts when method names appear in comments, strings, or other contexts.
- **Cross-language type coupling** (`VisualBasicPromptSession.buildSearchHits` accepts `JavaPromptSession.PromptModifier`), violating the Open/Closed Principle.
- **O(n²) tree traversal** and **N+1 DAO queries** in graph construction will degrade under large codebases.

---

## 2. Architectural Style & Patterns

| Layer | Pattern | Components |
|-------|---------|------------|
| File Rendering | Strategy (per-language `FileViewProvider`) | `MarkdownFileViewProvider`, `JavaFileViewProvider`, `TypeScriptFileViewProvider`, `VisualBasicFileViewProvider` |
| Retrieval | Strategy + Registry | `GoldenRetriever` (interface), `RetrieverRegistry`, `LanguagePack` / `LanguagePackRegistry` |
| Prompt Assembly | Template Method + Visitor | `*PromptSession` (template), `ChunkNode.visit()` (visitor), `PromptModifier` (strategy) |
| Ranking | Strategy + Composite | `ChunkRankingStrategy` (interface), `ChunkRankingService` (composite: base + modulator) |
| Enrichment / Orchestration | Pipeline / Iterator | `GRPromptEnrichment` (iterative retrieval with fallback), `GldRtrvrQuestioner` |
| CLI / Config | Event-Driven | `CliArgsParser` (Spring `@EventListener`), `PrjXPConfig` |
| Code Enrichment | Observer | `JavaDocGeneratedEvent` → `JavdocGeneratedPostProcessor` |

**Decoupling Assessment:**

The `LanguagePack` → `LanguagePackRegistry` → `RetrieverRegistry` chain provides a clean plugin boundary for adding new languages. However, the internal coupling is inconsistent:

- `VisualBasicPromptSession.buildSearchHits(JavaPromptSession.PromptModifier, ...)` directly references the Java session's inner interface, creating a **compile-time dependency between language modules**. This breaks the plugin isolation the registry pattern is meant to provide.
- The `PromptModifier` interface is **re-declared independently** in each `*PromptSession` class (Java, TypeScript, VisualBasic) rather than being a shared top-level type, making cross-session composition impossible without type casts.
- `MarkdownPromptSession` uses `BiFunction<PxChunk, String, String>` instead of the 3-argument `PromptModifier`, creating an **inconsistent functional contract** across sessions.

The `FileViewProvider` layer is well-decoupled: each provider is self-contained, depends only on `PxChunk` and `ReaderSupport`, and is registered via Spring `@Component`.

---

## 3. Quality Attribute Evaluation

### Maintainability & Readability

| Aspect | Assessment |
|--------|------------|
| **Duplication** | **Critical.** The four `*PromptSession` classes share ~85% identical code (`setChunks`, `setChunksByScore`, `findRootForChunk`, `findChunkNodeInTree`, `readChunk`, `buildGraphToRoot`, `getRootChunks`). The only meaningful differences are the metadata key string and the `RankedPrompt` record shape. Similarly, `combineScoredChunksByID` is copy-pasted across 5 retrievers with minor null-check variations. |
| **Naming** | Mixed. `rootForrest` (should be `rootForest`), `sanatize` (should be `sanitize`), `CLAZZ_FRAME` (should be `CLASS_FRAME`), `DEPENDENCIE_INFO` (should be `DEPENDENCY_INFO`). These typos propagate through metadata keys and are load-bearing (changing them breaks stored data). |
| **SOLID** | **S** – `FileViewProvider` implementations are single-responsibility. **O** – Violated by the `VisualBasicPromptSession` → `JavaPromptSession.PromptModifier` coupling and by the `switch`-on-enum in every `PromptModifier` (adding a new `CodeSection` requires modifying every language's modifier). **L** – `MarkdownPromptSession` is not substitutable for `JavaPromptSession` due to the different `PromptModifier` signature. **I** – `GoldenRetriever` bundles `buildPromptForFindings` and `retrieveSearchHits`; most implementations stub one or the other with `Collections.EMPTY_LIST`. **D** – Reasonable; dependencies are injected via constructor. |
| **Readability** | Javadoc is present on most public methods but inconsistent (some German, some English). The `JavaPromptModifier.modifyPrompt` method is a 100+ line switch statement that would benefit from decomposition. |

### Extensibility

| Aspect | Assessment |
|--------|------------|
| **Adding a new language** | The `LanguagePack` + `LanguagePackRegistry` + `RetrieverRegistry` chain is the intended extension point. However, a new language requires: (1) a `FileViewProvider`, (2) a `GoldenRetriever`, (3) a `*PromptSession`, (4) a `ChunkRankingStrategy`, (5) a `LanguagePack` – **five new classes**, three of which are near-clones of existing ones. A template/base-class or generic parameterization would reduce this to 2–3. |
| **Adding a new CodeSection** | Requires modifying the `switch` in every language's `PromptModifier` and `ChunkRanker`. No default/fallback case is guaranteed (TypeScript's switch has no `default`). |
| **Replacing the ranking model** | Well-supported via `ChunkRankingStrategy` + `ChunkRankingService` composite. The `LeidenWeightRankingStrategy` modulator pattern is clean. |
| **Replacing the LLM provider** | `KIChatProvider` is injected; the `GldRtrvrQuestioner` hardcodes `chatProvider.getByName("default")` which is a minor coupling. |

### Robustness & Error Handling

| Aspect | Assessment |
|--------|------------|
| **Null safety** | Inconsistent. `TypeScriptFileViewProvider.sortByFromLine` calls `unit.getMetadata().get(...)` without null-checking `getMetadata()`. `JavaFileViewProvider` checks `doc.getContent() != null` in some paths but not others. `MarkdownFileViewProvider` is the most defensive. |
| **Exception handling** | `GRPromptEnrichment.findRelevantScored` catches `UnsupportedOperationException` to fall back to keyword search – reasonable but the exception type is too broad. `JavaDocEnricher` catches `Exception` broadly. `CliArgsParser` catches `Exception` and calls `System.exit(0)`. |
| **Fragile string ops** | `TypeScriptRetriever.insertBefore` will throw `StringIndexOutOfBoundsException` when `splittIdx < 0` (the warning is logged but execution continues to `substring(0, -1)`). `JavaPromptModifier.insertBefore` handles this correctly (returns `prompt` unchanged). This is a **latent crash bug**. |
| **Edge cases** | `MarkdownFileViewProvider` handles empty/null inputs well. `JavaFileViewProvider.planSig` handles annotation blocks above signatures. The `SplicePlan` fallback (insert before last `}`) is a reasonable degradation. However, `firstLine` in `TypeScriptFileViewProvider` will throw on null content. |
| **Concurrency** | `CliArgsParser.options` is a non-final, lazily-initialized field accessed from an event listener – not thread-safe. `ChunkNode.childs` is a plain `ArrayList` mutated by `addChild` – not safe for concurrent access. |

### Performance & Resource Efficiency

| Aspect | Assessment |
|--------|------------|
| **Tree traversal** | `findRootForChunk` iterates all roots, and for each root calls `findChunkNodeInTree` (recursive O(n)). For N chunks and M roots, worst case is O(N × M × depth). With 100 chunks across 10 roots, this is 1000+ node visits per `setChunks` call. |
| **N+1 DAO queries** | `buildGraphToRoot` recursively calls `chunkDao.findById(parent)` for each ancestor. A 5-level hierarchy triggers 5 sequential DAO calls per chunk. No batching. |
| **String concatenation** | `JavaPromptSession.buildPrompt` uses `context += rp.treeContext()` (O(n²) for n appends). `TypeScriptPromptSession` and `VisualBasicPromptSession` correctly use `StringBuilder`. |
| **Sorting in visit** | `ChunkNode.visit` calls `Collections.sort(childs, ...)` on **every** visit invocation. If `visit` is called multiple times (e.g., for prompt + search hits), the sort is redundant. |
| **Memory** | `@Data` on `*PromptSession` generates `toString()` that includes the entire `chunkStore` HashMap and `chunkDao` – a potential OOM in logging. `@ToString.Exclude` is only on `ChunkNode.parent`. |
| **Blocking** | `JavaDocEnricher` uses `Thread.sleep(MIN_WAIT)` for rate limiting – blocks the calling thread. Acceptable for a batch CLI tool but problematic if ever called from a web context. |

---

## 4. Strengths & Best Practices

1. **Clean plugin architecture for languages.** The `LanguagePack` → `LanguagePackRegistry` → `RetrieverRegistry` chain with deduplication and deterministic ordering (`TreeMap`) is a well-designed extension mechanism.

2. **Composite ranking with modulators.** `ChunkRankingService` composes a base strategy and an optional modulator (Leiden weight) with configurable weights and a cap. The `isModulator()` default method on the interface is a clean way to extend without breaking existing strategies.

3. **Content budget enforcement.** The `maxContentLength` / `totalContentLength` / `docMaxContentLength` hierarchy with per-retriever and global caps, plus the "skipped by budget" marker, is a pragmatic approach to LLM context window management.

4. **Fallback iteration in `GRPromptEnrichment`.** The `reIterate` method that progressively lowers the similarity threshold on a 0.05 grid, with an abort floor at 0.5, is a well-thought-out degradation strategy.

5. **`DependencyCompactor`** is a focused, well-documented utility with clear contract (max entries, preserves section headers, appends a marker). Good use of a `final` class with private constructor.

6. **Records for value objects.** `SplicePlan`, `RankedPrompt`, `Token`, `Range`, `JavaDocGeneratedEvent` all use records appropriately.

7. **`FileViewProvider` implementations** are self-contained, stateless, and well-commented. The `MarkdownFileViewProvider` in particular shows careful handling of orphan sections, header levels, and flat-document fallback.

8. **`@SafeVarargs` + `final` methods** on varargs `Function<String, Boolean>...` parameters correctly suppress unchecked warnings.

9. **`TextRetriever`** demonstrates the cleanest implementation of the `GoldenRetriever` contract with proper pointer/content-level handling and file-level deduplication.

10. **`PxChunkService`** provides a thin, composable query facade over the DAO provider.

---

## 5. Identified Risks & Technical Debt

### Critical

| # | Risk | Location | Impact |
|---|------|----------|--------|
| C1 | **`TypeScriptRetriever.insertBefore` crashes on missing marker.** When `splittIdx < 0`, the code logs a warning but proceeds to `prompt.substring(0, splittIdx)` → `StringIndexOutOfBoundsException`. | `TypeScriptRetriever:insertBefore` | Runtime crash in production when a method name is not found in the prompt. |
| C2 | **Cross-language type coupling.** `VisualBasicPromptSession.buildSearchHits(JavaPromptSession.PromptModifier, ...)` creates a compile-time dependency from the VB module to the Java module. | `VisualBasicPromptSession` | Violates module isolation; prevents independent evolution of language modules. |
| C3 | **~400 lines of duplicated `PromptSession` code.** Four classes (`Java`, `TypeScript`, `VisualBasic`, `Markdown`) share 85%+ identical logic. Any bug fix or feature addition must be replicated 4×. | All `*PromptSession` classes | High probability of divergence; a fix in one session will be missed in others. |

### High

| # | Risk | Location | Impact |
|---|------|----------|--------|
| H1 | **Fragile string-based prompt mutation.** `indexOf`/`replace`/`substring` on the accumulated prompt string. A method name appearing in a comment, string literal, or FQN in a *different* class will be incorrectly spliced. | `JavaPromptModifier`, `TypeScriptRetriever`, `VisualBasicRetriever` | Silent prompt corruption → degraded LLM output. |
| H2 | **O(n²) tree search + N+1 DAO calls.** `findRootForChunk` × `findChunkNodeInTree` is quadratic; `buildGraphToRoot` issues one DAO call per ancestor level. | All `*PromptSession` | Performance degradation with large codebases (1000+ chunks). |
| H3 | **`@Data` on mutable session objects.** Generates `equals`/`hashCode`/`toString` over `chunkStore` (HashMap) and `chunkDao` (DAO reference). | All `*PromptSession` | Expensive `toString` in logging; incorrect equality semantics. |
| H4 | **`JavaPromptSession.buildPrompt` uses `String +=` in a loop.** O(n²) memory allocation. | `JavaPromptSession:buildPrompt` | Unnecessary GC pressure; inconsistent with other sessions that use `StringBuilder`. |
| H5 | **`CliArgsParser.options` is not thread-safe.** Non-final field with lazy init in a `@Component` (singleton). | `CliArgsParser` | Potential `ConcurrentModificationException` or duplicate option registration under concurrent event dispatch. |

### Medium

| # | Risk | Location | Impact |
|---|------|----------|--------|
| M1 | **Hardcoded German user-facing strings.** "weitere %d Klassen wegen Groessenlimit nicht enthalten", "Hier ein Rumpf der Klasse", etc. | Multiple retrievers/sessions | Non-localizable; confusing for non-German-speaking users. |
| M2 | **Inconsistent `PromptModifier` signatures.** Java/TS/VB use 3-arg `(dao, chunk, prompt)`; Markdown uses `BiFunction<PxChunk, String, String>`. | `*PromptSession` | Cannot share a common `buildPrompt` implementation; prevents generic session. |
| M3 | **`GoldenRetriever.retrieveSearchHits` is stubbed in 3 of 5 implementations.** Returns `Collections.EMPTY_LIST` with a `// TODO` comment. | `TypeScriptRetriever`, `MarkdownRetriever`, `TextRetriever` | Incomplete feature; callers must handle empty results. |
| M4 | **`@Value` field initializers as "test defaults."** `@Value("${...}") int docMaxChars = 2000;` – the initializer is dead code in Spring context but serves as a non-Spring default. | `MarkdownRetriever`, `TextRetriever` | Confusing; the "default" is not actually used by Spring (the property value always wins). Fragile if the property is missing. |
| M5 | **`ChunkNode.visit` sorts children on every call.** | `ChunkNode:visit` | Redundant O(n log n) work if `visit` is called multiple times. |
| M6 | **`JavaDocEnricher.sanatize` (typo) is fragile string manipulation.** Splits on `/**`, `*/`, trims, re-joins with specific spacing. | `JavaDocEnricher` | Will produce malformed Javadoc for non-standard LLM output. |
| M7 | **`System.exit(0)` in `CliArgsParser` event listener.** | `CliArgsParser:parseArgs` | Makes the class untestable; kills the JVM on any parse error. |
| M8 | **`PxChunkService` is a thin wrapper** that adds no value beyond `chunkDaoProvider.get(...).get().findAll().filter(p)`. | `PxChunkService` | Unnecessary indirection; the `.get()` will throw if the project is not configured. |

### Low

| # | Risk | Location | Impact |
|---|------|----------|--------|
| L1 | **Typo in metadata keys:** `CLAZZ_FRAME`, `DEPENDENCIE_INFO`. | `JavaCodeSection` enum (referenced) | Load-bearing; cannot be fixed without data migration. |
| L2 | **`rootForrest` naming.** | All `*PromptSession` | Cosmetic; "forest" is the correct spelling. |
| L3 | **`DependencyCompactor` in root package.** | `de.spraener.prjxp.gldrtrvr` | Should be in a `support` or `util` sub-package. |
| L4 | **`TextSegment2PxChunkConverter` is a static utility** with no Spring annotation. | `de.spraener.prjxp.gldrtrvr.chunks` | Fine, but inconsistent with the DI-heavy style elsewhere. |

---

## 6. Actionable Recommendations

### Priority 1 – Fix Critical Bugs (Immediate)

1. **Fix `TypeScriptRetriever.insertBefore` null-safety.**
   ```java
   private String insertBefore(String prompt, String methodName, String content) {
       int idx = prompt.indexOf(methodName);
       if (idx < 0) {
           log.warning("Method name '%s' not found in prompt; appending instead.", methodName);
           return prompt + "\n" + content;
       }
       return prompt.substring(0, idx) + content + prompt.substring(idx);
   }
   ```
   Apply the same guard to `replaceInPrompt` (use `replaceFirst` with a word-boundary regex instead of `String.replace` to avoid replacing all occurrences).

2. **Decouple `VisualBasicPromptSession` from `JavaPromptSession`.**
   - Extract a shared `PromptModifier` interface to a common package (e.g., `de.spraener.prjxp.gldrtrvr.chunks.PromptModifier`).
   - Change `VisualBasicPromptSession.buildSearchHits` to accept the shared interface.
   - Unify the `PromptModifier` signature across all sessions (3-arg: `dao, chunk, prompt`). For Markdown, adapt the `BiFunction` usage to the 3-arg form (pass `null` or a no-op `dao` if not needed).

### Priority 2 – Eliminate Duplication (Short-term, 1–2 sprints)

3. **Extract a `AbstractPromptSession` base class** (or a generic `PromptSession<T extends CodeSection>`) that encapsulates:
   - `chunkStore`, `chunkDao`, `chunks`, `rootForest`, `maxContentLength`, `rankingService`
   - `setChunks`, `setChunksByScore`, `findRootForChunk`, `findChunkNodeInTree`, `readChunk`, `buildGraphToRoot`, `getRootChunks`
   - `buildPrompt` and `buildSearchHits` (with the `StringBuilder` fix)
   
   Subclasses provide only: the metadata key, the `RankedPrompt` record shape, and the `PromptModifier` implementation. This reduces ~400 lines of duplication to ~50 lines of configuration per language.

4. **Extract `combineScoredChunksByID` into a shared utility** (e.g., `ChunkCombiner.combineScored(PxChunkDao, List<ScoredChunk>, Predicate<PxChunk> filter)`). The only variation across retrievers is the language filter predicate and the null-check strictness. Standardize on the null-safe version.

5. **Replace `@Data` with `@Getter` + `@Setter`** on `*PromptSession` classes. Add `@ToString.Exclude` on `chunkStore` and `chunkDao`. This eliminates the expensive/incorrect generated methods.

### Priority 3 – Performance (Medium-term)

6. **Replace O(n²) tree search with an index.** Maintain a `Map<String, ChunkNode> nodeIndex` (chunk ID → node) in the session. `findRootForChunk` becomes O(1) lookup + O(depth) walk to root. `findChunkNodeInTree` is eliminated.

7. **Batch the `buildGraphToRoot` DAO calls.** Instead of recursive single-ID lookups, collect all ancestor IDs first (iterative walk), then issue a single `chunkDao.findByIds(Set<String>)` call. This reduces N sequential calls to 1.

8. **Fix `JavaPromptSession.buildPrompt` to use `StringBuilder`** (consistent with the other three sessions).

9. **Cache the sorted child list in `ChunkNode`.** Sort once in `addChild` (or lazily on first `visit`), not on every `visit` call.

### Priority 4 – Robustness & Design (Medium-term)

10. **Replace string-based prompt mutation with a structured prompt model.** Instead of `indexOf`/`replace` on a flat string, represent the prompt as a list of typed blocks (e.g., `PromptBlock(type, id, content)`). Splicing becomes a list operation keyed by block ID, eliminating the "name appears in a comment" failure mode. This is the single highest-impact architectural improvement for prompt correctness.

11. **Externalize user-facing strings to a `messages.properties` / i18n resource bundle.** Replace all hardcoded German strings ("weitere %d Klassen...", "Hier ein Rumpf...") with keyed messages.

12. **Make `CliArgsParser.options` a `final` field initialized in the constructor** (or use a `Supplier<Options>`). Remove `System.exit(0)` – instead, publish a `CliExitEvent` or throw a `CliExitException` that the application entry point handles.

13. **Implement or explicitly deprecate `GoldenRetriever.retrieveSearchHits`.** Either implement the stubbed methods (TypeScript, Markdown, Text) or split the interface into `PromptRetriever` and `SearchHitRetriever` so that implementations only declare what they support.

14. **Add a `default` case to all `switch`-on-`CodeSection` statements** in rankers and prompt modifiers, returning a safe no-op or logging a warning. This prevents silent data loss when a new enum value is added.

### Priority 5 – Hygiene & Long-term (Backlog)

15. **Fix load-bearing typos via a data migration:** `CLAZZ_FRAME` → `CLASS_FRAME`, `DEPENDENCIE_INFO` → `DEPENDENCY_INFO`, `rootForrest` → `rootForest`, `sanatize` → `sanitize`. Coordinate with the chunker/indexer that writes these metadata keys.

16. **Move `DependencyCompactor` to `de.spraener.prjxp.gldrtrvr.support`** and `TextSegment2PxChunkConverter` to a `converter` sub-package for consistent package structure.

17. **Add unit tests** for:
    - Each `FileViewProvider.render` (happy path, empty input, orphan sections, flat document).
    - `ChunkRankingService` composite logic (base + modulator + cap).
    - `GRPromptEnrichment.reIterate` grid-snapping and abort logic.
    - `PromptSession.buildPrompt` budget enforcement and ranking sort.
    - `DependencyCompactor.compact` edge cases (0 entries, exactly max, over max).
    - The `insertBefore`/`replaceInPrompt` methods with adversarial inputs (name in comment, name as substring of another name).

18. **Introduce a `PromptSession` factory or builder** that, given a `LanguagePack`, constructs the appropriate session with the correct metadata key and modifier. This makes the "add a new language" path a single registration rather than five new classes.

---

*Assessment scope: 22 source files across 10 packages. External dependencies (`PxChunk`, `PxChunkDao`, `GoldenRetriever`, `LanguagePack`, `SearchParams`, `ContentLevel`, `KIChatProvider`, `ChunkNode` internals) were assessed based on their usage in this module. The `prjxp.common` module was not available for direct inspection.*

_This document was generated with .dp and gemini-3-flash-preview_

