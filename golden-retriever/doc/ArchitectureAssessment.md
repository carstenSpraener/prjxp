# Software Architecture Assessment: Golden Retriever (gldrtrvr) – Multi-Language Code Retrieval & Prompt Enrichment

## 1. Executive Summary

The Golden Retriever is a Spring Boot–based code retrieval and LLM prompt enrichment engine that supports multiple programming languages (Java, TypeScript, Visual Basic) and document formats (Markdown, plain text). It reconstructs source files from indexed chunks, ranks them via pluggable strategies, and assembles context-aware prompts for LLM consumption with configurable content budgets and detail levels.

**Key Strengths:**
- Well-defined layered pipeline (FileViewProvider → Retriever → PromptSession → PromptModifier) with clear responsibility boundaries.
- Strategy pattern for chunk ranking and a LanguagePack/Registry plugin architecture that enables adding new languages without modifying core logic.
- Robust fallback mechanisms (degraded join, orphan handling, budget-aware skipping) that prevent hard failures.
- Content-level abstraction (FULL / SKELETON / POINTERS / NAMES / AGGREGATED) providing a clean multi-resolution retrieval model.

**Most Critical Risks:**
- **Severe code duplication** across four near-identical PromptSession classes (~200 lines of tree-building logic copy-pasted), creating a high maintenance burden and divergence risk.
- **Fragile string-based prompt manipulation** (indexOf/replace on method names) that will silently corrupt prompts when names appear in comments, strings, or other contexts.
- **A confirmed null-safety bug** in `TypeScriptRetriever.insertBefore` that will throw `StringIndexOutOfBoundsException` when the target method name is not found.
- **Cross-language coupling**: `VisualBasicPromptSession.buildSearchHits` accepts `JavaPromptSession.PromptModifier`, violating the language-isolation principle.
- **N+1 database access** in recursive tree construction (`buildGraphToRoot`) with no batching or depth limit.

## 2. Architectural Style & Patterns

| Pattern | Where Applied | Assessment |
|---------|--------------|------------|
| **Layered Architecture** | Reader (FileViewProvider) → Retriever (GoldenRetriever) → PromptSession → PromptModifier | Clear vertical separation; each layer has a single responsibility. |
| **Strategy** | `ChunkRankingStrategy` + `ChunkRankingService` | Well-implemented; `isModulator()` extension point is clean. |
| **Plugin / Registry** | `LanguagePack` → `LanguagePackRegistry` → `RetrieverRegistry` | Good extensibility; new languages plug in via a pack. |
| **Template Method** | `PromptSession.buildPrompt(PromptModifier, ...)` | The session defines the algorithm skeleton; the modifier supplies per-chunk rendering. |
| **Visitor** | `ChunkNode.visit(Consumer<ChunkNode>)` | Simple depth-first traversal; adequate for the tree sizes involved. |
| **Composite** | `ChunkNode` (parent/children tree) | Standard; supports arbitrary nesting depth. |
| **Facade** | `GRPromptEnrichment` | Orchestrates search → cap → per-retriever prompt → format. |
| **Observer / Event** | `JavaDocGeneratedEvent` + `JavdocGeneratedPostProcessor` | Decouples Javadoc generation from post-processing. |
| **Spring Boot Idiomatic** | `@Component`, `@Service`, `@RequiredArgsConstructor`, `@Value`, `@EventListener` | Consistent DI usage; constructor injection throughout. |

**Component Decoupling:**
- The `FileViewProvider` interface cleanly separates *file rendering* (reconstructing a full source file from chunks) from *retrieval* (selecting and ranking chunks). This is a strong separation.
- The `GoldenRetriever` interface is the primary extension point for new content types.
- **Weakness:** The PromptSession classes are *not* behind a shared interface. Each language has its own concrete class with a slightly different `PromptModifier` signature, preventing polymorphic use. The `RetrieverRegistry` works around this by calling `buildPromptForFindings` on the retriever, but the sessions themselves are tightly coupled to their retriever.

## 3. Quality Attribute Evaluation

### Maintainability & Readability

- **Naming:** Generally good (`sortByFromLine`, `applyInDescendingIndexOrder`, `normalizeForMatch`). However, the field `rootForrest` (typo for "rootForest") appears in all four PromptSession classes. The method `sanatize` in `JavaDocEnricher` is a typo. The constant `CLAzz_FRAME` vs `CLASS_FRAME` inconsistency between Java and TypeScript sections is confusing.
- **SOLID:**
  - **S:** FileViewProviders are single-responsibility. Retriever classes, however, combine retrieval, prompt modification, and chunk-combining logic (God-class tendency in `TypeScriptRetriever` and `VisualBasicRetriever`).
  - **O:** The Strategy pattern for ranking is open for extension. The LanguagePack pattern is open for new languages. However, the PromptSession classes are *closed*—adding a new language requires copy-pasting the entire session.
  - **L:** `VisualBasicPromptSession.buildSearchHits` accepts `JavaPromptSession.PromptModifier`—a clear LSP violation.
  - **I:** The `PromptModifier` interface is small (one method), which is good. But the *inconsistency* between sessions (3-arg vs 2-arg) fragments the interface.
  - **D:** Retriever classes depend on concrete `PointerRenderer` (instantiated with `new`), not an abstraction.
- **German strings in production code** ("weitere %d Klassen wegen Groessenlimit nicht enthalten", "Hier ein Rumpf der Klasse", "Du bist ein erfahrener Software-Architekt") reduce readability for international teams and complicate i18n.
- **`@Data` on PromptSession classes** generates `equals`/`hashCode`/`toString` over mutable collections (`chunkStore`, `rootForrest`), which is semantically wrong and a performance hazard.

### Extensibility

- **Adding a new language** requires: (1) a `FileViewProvider`, (2) a `Retriever`, (3) a `PromptSession`, (4) a `ChunkRanker`, (5) a `LanguagePack`. Steps 3 and 4 are the bottleneck—the PromptSession is ~200 lines of duplicated tree logic. A generic `AbstractPromptSession` would reduce this to ~30 lines of configuration.
- **Adding a new ranking strategy** is trivial (implement `ChunkRankingStrategy`, annotate `@Component`). The `LeidenWeightRankingStrategy` modulator pattern is a clean extension.
- **Adding a new content level** requires touching every `modifyPromptByChunk` switch statement. An enum-driven strategy map would be more extensible.
- **The `PointerRenderer` is instantiated with `new`** in each retriever rather than injected, making it impossible to swap implementations via DI.

### Robustness & Error Handling

- **Critical Bug:** `TypeScriptRetriever.insertBefore` logs a warning when `splittIdx < 0` but then proceeds to `prompt.substring(0, -1)`, which throws `StringIndexOutOfBoundsException`. The `JavaPromptModifier` version correctly returns early.
- **NPE risks:** `frame.getContent().split("\n", -1)` in all FileViewProviders will NPE if `getContent()` returns null. `chunk.getMetadata().get(...)` in several places assumes non-null metadata.
- **Inconsistent null handling:** `JavaRetriever` uses `.get()` (throws `NoSuchElementException`), `TypeScriptRetriever` uses `.orElseThrow()`, `VisualBasicRetriever` uses `.orElseThrow()`. The `JavaDocEnricher` calls `cu.getPrimaryType().get()` without checking `isPresent()`.
- **`buildGraphToRoot` mutates input:** `c.setParent(null)` modifies the caller's chunk object as a side effect of a "read" operation. This is a surprising mutation that can corrupt upstream state.
- **No depth limit** in `buildGraphToRoot` recursion—circular parent references would cause `StackOverflowError`.
- **`CliArgsParser` calls `System.exit(0)`** on parse errors, preventing Spring's graceful shutdown and making the application untestable in integration contexts.

### Performance & Resource Efficiency

- **N+1 DAO calls:** `buildGraphToRoot` recursively calls `chunkDao.findById(parent)` for each ancestor. For a 10-level-deep class hierarchy, this is 10 sequential DB calls per chunk. With 50 chunks, that's 500 calls. No batching, no memoization at the DAO level (the `chunkStore` cache in the session helps, but `buildGraphToRoot` bypasses it by calling `chunkDao.findById` directly).
- **O(n²) tree search:** `findRootForChunk` iterates all roots and calls `findChunkNodeInTree` (recursive O(depth × branching)) for each. With R roots and T total nodes, worst case is O(R × T). For 50 chunks across 10 roots, this is manageable, but scales poorly.
- **Redundant sorting in `visit`:** `Collections.sort(childs, ...)` is called on every `visit` invocation. If `visit` is called multiple times (e.g., for prompt building + search hit building), the sort is redundant.
- **String concatenation in `JavaPromptSession.buildPrompt`:** Uses `context += rp.treeContext()` (String concatenation in a loop) instead of `StringBuilder`. For large prompts, this creates O(n²) intermediate String objects.
- **`normalizeForMatch` regex:** `replaceAll("\\s+", " ")` is called for every line comparison in the TypeScript splicing algorithm. For a 500-line file with 50 methods, that's 25,000 regex compilations (the Pattern is not pre-compiled).
- **`@Value` fields with initializers** (e.g., `int maxDependencyEntries = 10`) are a known Spring anti-pattern that can cause confusion about which value is active, though functionally correct.

## 4. Strengths & Best Practices

1. **Clean FileViewProvider abstraction:** The `FileViewProvider` interface with `mimeType()`, `language()`, and `render(List<PxChunk>)` is a well-designed SPI. The splice-based reconstruction (Java, TypeScript, VB) is a sophisticated approach that preserves original file structure.

2. **SplicePlan record with descending-order application:** The `applyInDescendingIndexOrder` pattern (sort plans by index descending, then apply) is a correct and elegant solution to the index-invalidation problem when splicing multiple methods into a frame.

3. **ContentLevel multi-resolution model:** The `ContentLevel` enum (FULL, SKELETON, POINTERS, NAMES, AGGREGATED) provides a clean abstraction for "how much detail" the LLM needs, and the `effectiveContentLevel()` method centralizes the resolution logic.

4. **Budget-aware prompt assembly:** The `maxContentLength` / `totalContentLength` / `docMaxContentLength` hierarchy with skip-counting and user-visible markers ("[weitere N Klassen wegen Groessenlimit nicht enthalten]") is a practical approach to context-window management.

5. **`capByFile` / `capChunksPerFile` efficiency measures:** The file-level deduplication and per-file chunk capping in `GRPromptEnrichment` directly addresses the "long document floods context" problem.

6. **`DependencyCompactor` as a focused utility:** A small, well-documented, stateless utility with clear input/output contract. The `MARKER` constant makes the truncation visible to the LLM.

7. **`RetrieverRegistry` merge logic:** Combining pack-provided retrievers (deduped by language, sorted) with standalone retriever beans is a clean way to support both "full language packs" and "single retriever" use cases.

8. **`LeidenWeightRankingStrategy` modulator pattern:** The `isModulator()` flag on `ChunkRankingStrategy` allows additive graph-based bonuses without disrupting the base ranking formula. The `ChunkRankingService` formula (`wBase*base + min(bGraph*weight, cap)`) is well-documented.

9. **`PointerRenderer` dual-graph references:** The pointer layer (graphNode, semanticGraphNode, readFile, readBySignature) provides the LLM with navigational handles for multi-step retrieval, a thoughtful design for agentic workflows.

10. **`@SafeVarargs` on varargs methods:** Correctly applied to all `Function<String, Boolean>...` parameters, avoiding unchecked warnings.

## 5. Identified Risks & Technical Debt

### Critical (P0)

| # | Issue | Location | Impact |
|---|-------|----------|--------|
| 1 | **`insertBefore` NPE/IOOBE bug** | `TypeScriptRetriever.insertBefore` | When a method name is not found in the prompt, the code logs a warning but then calls `substring(0, -1)`, crashing the entire prompt build. |
| 2 | **Cross-language type coupling** | `VisualBasicPromptSession.buildSearchHits(JavaPromptSession.PromptModifier, ...)` | VB session depends on Java's modifier interface. Any change to Java's modifier breaks VB. Violates the language-isolation principle. |
| 3 | **`buildGraphToRoot` mutates input + unbounded recursion** | All PromptSession classes | `c.setParent(null)` corrupts the caller's data. Circular references cause `StackOverflowError`. No depth guard. |

### High (P1)

| # | Issue | Location | Impact |
|---|-------|----------|--------|
| 4 | **~800 lines of duplicated PromptSession code** | Java/TS/VB/Markdown PromptSession | Tree-building, ranking, budget logic, and `buildSearchHits` are copy-pasted. Any fix must be applied 4×. Divergence is already visible (different `PromptModifier` signatures, different ranking formulas, different `RankedPrompt` records). |
| 5 | **Fragile string-based prompt mutation** | `JavaPromptModifier.replaceInPrompt`, `TypeScriptRetriever.replaceInPrompt` | `String.replace(methodName, content)` replaces *all* occurrences. If a method name appears in a string literal, comment, or another method's body, the prompt is silently corrupted. |
| 6 | **N+1 DAO access in tree construction** | `buildGraphToRoot` in all sessions | Each ancestor is a separate `chunkDao.findById` call. No batching, no depth limit. For deep hierarchies with many chunks, this is a significant latency source. |
| 7 | **`PointerRenderer` instantiated with `new`** | Java/TS/VB/Markdown/Text Retriever | Bypasses DI. Cannot be mocked in tests, cannot be swapped, cannot be configured via `@Value`. |
| 8 | **`@Data` on mutable session classes** | All PromptSession classes | Generates `equals`/`hashCode`/`toString` over `HashMap` and `List<ChunkNode>`. Wasteful, potentially recursive (ChunkNode has parent reference), and semantically incorrect. |

### Medium (P2)

| # | Issue | Location | Impact |
|---|-------|----------|--------|
| 9 | **German strings in production output** | All retrievers, `GRPromptEnrichment`, `GldRtrvrQuestioner` | "weitere %d Klassen wegen Groessenlimit nicht enthalten", "Hier ein Rumpf der Klasse", "Du bist ein erfahrener Software-Architekt". Not i18n-ready; confusing for non-German developers. |
| 10 | **`rootForrest` typo** | All PromptSession classes | Misleading field name; will propagate to any refactoring. |
| 11 | **`sanatize` typo** | `JavaDocEnricher` | Method name error. |
| 12 | **Inconsistent `PromptModifier` signatures** | Markdown uses `BiFunction`, others use 3-arg `PromptModifier` | Prevents a unified `PromptSession` interface. |
| 13 | **`@Log` (java.util.logging) instead of SLF4J** | Multiple classes | Spring Boot's default logging is Logback/SLF4J. Using JUL bypasses the logging framework, making log levels and appenders inconsistent. |
| 14 | **`System.exit(0)` in `CliArgsParser`** | `CliArgsParser.parseArgs` | Prevents Spring context shutdown, breaks integration tests. |
| 15 | **TODO stubs** | `TypeScriptRetriever.retrieveSearchHits`, `MarkdownRetriever.retrieveSearchHits` | Return `Collections.EMPTY_LIST`. Incomplete feature. |
| 16 | **Hardcoded path in `JavaDocEnricher`** | `Path.of("build/enriched-src")` | Not configurable; will break in different build environments. |
| 17 | **`visit` sorts children on every call** | `ChunkNode.visit` | Redundant O(n log n) work if called multiple times. |
| 18 | **`CLAzz_FRAME` vs `CLASS_FRAME` inconsistency** | `JavaCodeSection` vs `TypeScriptCodeSection` | Different enum constant names for the same concept across languages. |

### Low (P3)

| # | Issue | Location | Impact |
|---|-------|----------|--------|
| 19 | **`DependencyCompactor` in root package** | `de.spraener.prjxp.gldrtrvr` | Should be in a `util` or `support` subpackage. |
| 20 | **`TextSegment2PxChunkConverter` is a static utility** | `de.spraener.prjxp.gldrtrvr.chunks` | Fine, but the class name is verbose and the method is trivial. |
| 21 | **`PxChunkService` is a thin wrapper** | `de.spraener.prjxp.gldrtrvr.chunks` | Adds little value over calling `chunkDaoProvider` directly. |
| 22 | **`MarkdownPromptSession` uses a different ranking formula** | `MarkdownPromptSession.buildPrompt` | `(100.0 * rootRank) / treeContext.length()` normalizes by length, unlike other sessions. Inconsistent ranking semantics. |

## 6. Actionable Recommendations

### Priority 0 – Immediate (Bug Fixes)

1. **Fix `TypeScriptRetriever.insertBefore`:** Add an early return when `splittIdx < 0`:
   ```java
   if (splittIdx < 0) {
       log.warning("Method name '%s' not found in prompt; skipping insertion.", methodName);
       return prompt;
   }
   ```
   Mirror the `JavaPromptModifier` pattern. Add a unit test for the not-found case.

2. **Decouple `VisualBasicPromptSession.buildSearchHits` from `JavaPromptSession.PromptModifier`:** Define a shared `PromptModifier` interface (or use the existing 3-arg one) in a neutral package. Change the VB method signature to accept its own `PromptModifier`.

3. **Guard `buildGraphToRoot` against cycles and depth:** Add a `Set<String> visited` parameter and a max-depth constant (e.g., 50). Remove the `c.setParent(null)` mutation—instead, return a new `ChunkNode` with `parent = null`.

### Priority 1 – Structural Refactoring (1–2 sprints)

4. **Extract `AbstractPromptSession<T extends PromptModifier>`:** Factor the ~200 lines of shared logic (tree building, `findRootForChunk`, `buildGraphToRoot`, `findChunkNodeInTree`, `readChunk`, `loadAndCombine`, `buildPrompt` skeleton, `buildSearchHits` skeleton) into a single abstract class. Each language session becomes a ~30-line subclass that provides:
   - The metadata key for its language
   - The `PromptModifier` implementation
   - Any language-specific ranking adjustments
   
   This eliminates the 4× duplication and the `rootForrest` typo (fix to `rootForest` in one place).

5. **Unify the `PromptModifier` interface:** Define a single `PromptModifier` interface in `de.spraener.prjxp.gldrtrvr.chunks` (or a new `prompt` package) with the 3-arg signature `(PxChunkDao, PxChunk, String) → String`. Update `MarkdownPromptSession` to use it (wrap the `BiFunction` in an adapter if needed). This enables a `PromptSession` interface and polymorphic use.

6. **Inject `PointerRenderer` via DI:** Create a `@Component PointerRenderer` bean. Remove all `new PointerRenderer()` instantiations from retrievers. This enables testing and future replacement.

7. **Replace `@Data` with `@Getter` on PromptSession classes:** Remove auto-generated `equals`/`hashCode`/`toString` over mutable collections. If `toString` is needed for debugging, write a custom one that excludes `chunkStore` and `rootForest`.

8. **Batch the DAO calls in `buildGraphToRoot`:** Collect all parent IDs needed, call `chunkDao.findByIds(Set<String>)` (or a new batch method) once, then build the tree from the results. Add a depth limit.

### Priority 2 – Robustness & Quality (2–3 sprints)

9. **Replace string-based prompt mutation with structured markers:** Instead of `prompt.indexOf(methodName)` / `prompt.replace(methodName, content)`, use unique delimiters (e.g., `⟦method:com.example.Foo.bar⟧`) that are inserted during frame rendering and then replaced. This eliminates the "name appears in a comment" corruption risk.

10. **Internationalize user-facing strings:** Move all German strings to a `messages.properties` (or `messages_de.properties` + `messages_en.properties`) file. Use `MessageSource` for resolution. At minimum, extract them to constants in a `PromptTemplates` class.

11. **Fix `@Log` → SLF4J:** Replace `lombok.extern.java.Log` with `lombok.extern.slf4j.Slf4j` (or `@Log4j2`) across all classes. This aligns with Spring Boot's default logging stack.

12. **Remove `System.exit(0)` from `CliArgsParser`:** Throw a `CliParseException` (or similar) and let the Spring Boot error handler / `main` method decide on exit. This preserves testability.

13. **Implement the TODO `retrieveSearchHits` methods** in `TypeScriptRetriever` and `MarkdownRetriever`, or remove the `@Override` and the method if the feature is intentionally deferred.

14. **Make `JavaDocEnricher`'s output path configurable:** `@Value("${prjxp.gldrtrvr.enriched-src-path:build/enriched-src}")`.

### Priority 3 – Performance & Polish (ongoing)

15. **Pre-compile the `normalizeForMatch` regex** in `TypeScriptFileViewProvider`: The `replaceAll("\\s+", " ")` should use a static `Pattern` constant.

16. **Sort children once in `ChunkNode`:** Move the `Collections.sort` out of `visit` into `addChild` (maintain sorted invariant) or into a one-time `prepare()` call.

17. **Use `StringBuilder` in `JavaPromptSession.buildPrompt`:** Replace `context += rp.treeContext()` with `contextBuilder.append(rp.treeContext())`.

18. **Add a `PromptSession` interface** with `buildPrompt`, `buildSearchHits`, `setChunks`, `setChunksByScore`, `setMaxContentLength`. This allows the `GRPromptEnrichment` to work with any session polymorphically and enables testing with fakes.

19. **Add integration tests** for the full pipeline: chunk ingestion → retrieval → prompt assembly → budget enforcement. The current codebase has no visible test classes.

20. **Consolidate enum constant naming:** Standardize on `CLASS_FRAME` (or `TYPE_FRAME`) across all `*CodeSection` enums. The `CLAzz_FRAME` / `CLASS_FRAME` split is a source of confusion.

---

*Assessment scope: 22 source files across 10 packages. The project is a well-structured retrieval engine with a clear architectural vision, but the PromptSession duplication and string-mutation fragility are the primary technical debt items that will compound with each new language or content type added.*

_This document was generated with .dp and gemini-3-flash-preview_

