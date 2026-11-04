# Software Architecture Assessment: Golden Retriever Code Retrieval & Prompt Enrichment Service

## 1. Executive Summary

The project implements a Spring-based retrieval and prompt enrichment service for code and document chunks. It searches indexed `PxChunk` data, reconstructs relevant context for multiple languages, enriches prompts for LLM calls, and includes supporting capabilities such as JavaDoc generation and file-view reconstruction.

At a high level, the architecture is functional and shows a clear intent to support multiple content types through language-specific retrievers, ranking strategies, and file view providers. The use of Spring dependency injection, strategy-based ranking, and language pack registration provides a reasonable foundation for extensibility.

However, the implementation currently contains significant technical debt and architectural inconsistencies. The most critical issues are duplicated prompt-session logic across languages, incomplete `GoldenRetriever` implementations, fragile string-based prompt manipulation, unsafe `Optional` usage, inconsistent error handling, and at least one severe recursion bug in `GldRtrvrQuestioner`. These issues reduce maintainability, make behavior hard to reason about, and increase the likelihood of runtime failures in production.

Key strengths include:

- Clear domain concepts around chunks, ranked retrieval, prompt sessions, retrievers, and file views.
- Pluggable ranking via `ChunkRankingStrategy`.
- Language pack integration for Java, TypeScript, and Visual Basic.
- Deterministic retriever ordering through `RetrieverRegistry`.
- Useful reconstruction providers for Java, TypeScript, Visual Basic, Markdown, and plain text.

Most critical risks include:

- A likely infinite recursion / stack overflow in `GldRtrvrQuestioner.ask(String, List<PxChunk>, ...)`.
- Large-scale duplication between Java, TypeScript, Visual Basic, and Markdown prompt sessions.
- Partially implemented retriever APIs returning empty results.
- Fragile prompt construction based on string replacement and method-name matching.
- Missing null-safety and unsafe `.get()` / `.orElseThrow()` usage.
- Tight coupling to metadata key strings and language-specific chunk conventions.
- Potential performance problems from repeated DAO lookups, recursive tree searches, string concatenation, and loading complete chunks/files.

## 2. Architectural Style & Patterns

### Primary Architectural Style

The project follows a pragmatic Spring service architecture with elements of a layered and plugin-oriented design.

The main layers/components are:

- **Retrieval orchestration**
  - `GRPromptEnrichment`
  - `GldRtrvrQuestioner`
  - `RetrieverRegistry`

- **Language-specific retrievers**
  - `JavaRetriever`
  - `TypeScriptRetriever`
  - `VisualBasicRetriever`
  - `MarkdownRetriever`
  - `TextRetriever`

- **Prompt construction/session model**
  - `JavaPromptSession`
  - `TypeScriptPromptSession`
  - `VisualBasicPromptSession`
  - `MarkdownPromptSession`
  - `ChunkNode`

- **Ranking**
  - `ChunkRankingService`
  - `ChunkRankingStrategy`
  - `JavaChunkRanker`
  - `TypeScriptChunkRanker`
  - `VisualBasicChunkRanker`
  - `MarkdownChunkRanker`

- **Language/plugin registration**
  - `JavaRetrieverPack`
  - `TypeScriptRetrieverPack`
  - `VisualBasicRetrieverPack`
  - `RetrieverRegistry`

- **File rendering / reconstruction**
  - `JavaFileViewProvider`
  - `TypeScriptFileViewProvider`
  - `VisualBasicFileViewProvider`
  - `MarkdownFileViewProvider`

- **LLM-assisted enrichment**
  - `JavaDocEnricher`
  - `JavaDocGeneratedEvent`
  - `JavdocGeneratedPostProcessor`

### Patterns Used

#### Strategy Pattern

`ChunkRankingStrategy` and its implementations are a clear use of the Strategy pattern.

```java
public interface ChunkRankingStrategy {
    boolean supports(PxChunk chunk);
    double rank(PxChunk chunk);
}
```

`ChunkRankingService` selects the first supporting strategy and delegates ranking to it. This is one of the cleaner extension points in the system.

Assessment:

- Positive: Makes ranking behavior independently extensible.
- Risk: Strategy order is implicit and depends on Spring injection order unless explicitly ordered.
- Risk: There is no fallback diagnostics when no strategy supports a chunk.

#### Registry / Plugin Pattern

`RetrieverRegistry` merges retrievers from `LanguagePackRegistry` with direct `GoldenRetriever` beans. This supports a plugin-like architecture.

Assessment:

- Positive: Allows language packs and plain retriever beans to coexist.
- Positive: Uses deterministic sorting by language for language-pack retrievers.
- Risk: Deduplication by object identity may not be enough if multiple wrappers or proxies are involved.
- Risk: Plain retrievers are appended in Spring injection order, which may not be deterministic without ordering annotations.

#### Composite / Tree Pattern

`ChunkNode` models parent-child chunk relationships and supports visiting the tree.

Assessment:

- Positive: Represents chunk hierarchy naturally.
- Positive: Supports tree traversal for context reconstruction.
- Risk: Tree construction logic is duplicated across prompt sessions.
- Risk: No cycle protection exists.
- Risk: `childIDs` is unused and `addChild()` does not maintain it.
- Risk: Tree traversal mutates child order by sorting inside `visit()`.

#### Template-Like Prompt Building

The prompt sessions follow a common algorithm:

1. Group chunks into a tree.
2. Rank root nodes.
3. Visit tree nodes.
4. Apply a language-specific prompt modifier.
5. Sort by rank.
6. Apply budget.
7. Return prompt/search hits.

This is effectively a Template Method pattern, but it is implemented through copy-paste rather than a shared abstraction.

Assessment:

- Positive: The conceptual algorithm is sound.
- Major risk: Duplication across `JavaPromptSession`, `TypeScriptPromptSession`, `VisualBasicPromptSession`, and `MarkdownPromptSession` causes inconsistent behavior and bugs.

#### Adapter / Converter

`TextSegment2PxChunkConverter` adapts LangChain4j embedding matches into `PxChunk`.

Assessment:

- Positive: Simple and focused.
- Risk: Static utility style makes testing and extension harder, but acceptable for this small case.

### Separation of Concerns

The codebase shows mixed separation of concerns.

Good separation exists in:

- Ranking strategies.
- File view providers.
- Language pack wrappers.
- DAO access through `PxChunkDaoProvider`.
- LLM chat access through `KIChatProvider`.

Weak separation exists in:

- Retriever classes combine filtering, chunk recombination, prompt formatting, metadata interpretation, and sometimes search-hit creation.
- Prompt sessions combine tree building, DAO reads, cache management, ranking, validation, and budget handling.
- `GRPromptEnrichment` performs retrieval, fallback search iteration, retriever orchestration, budget enforcement, context validation, and final prompt formatting.
- `JavaDocEnricher` performs source parsing, rate limiting, LLM prompting, JavaDoc sanitation, file output, and event publication in one service.

## 3. Quality Attribute Evaluation

### Maintainability & Readability

Overall maintainability is moderate to weak. The project contains several good domain concepts, but the implementation suffers from duplication, inconsistent naming, fragile string handling, and uneven coding style.

#### Positive observations

- The core concepts are understandable:
  - `GoldenRetriever`
  - `ChunkRankingStrategy`
  - `PromptSession`
  - `FileViewProvider`
  - `LanguagePack`
- Spring stereotypes are used consistently for most services/components.
- Lombok reduces boilerplate in service constructors and data holders.
- Some complex file reconstruction logic is well-commented, especially in the file view providers.
- `VisualBasicRetriever` shows a more robust approach than older retrievers by using regexes and helper methods for signature matching.

#### Maintainability issues

##### 1. Extensive duplication across prompt sessions

The following classes share very similar structure:

- `JavaPromptSession`
- `TypeScriptPromptSession`
- `VisualBasicPromptSession`
- `MarkdownPromptSession`

They all duplicate:

- `chunkStore`
- `rootForrest`
- `setChunks`
- `setChunksByScore`
- `findRootForChunk`
- `findChunkNodeInTree`
- `readChunk`
- `loadAndCombine`
- `buildGraphToRoot`
- budget handling
- rank sorting
- prompt validation

This duplication already led to behavioral drift:

- Java supports `buildSearchHits`.
- Visual Basic supports `buildSearchHits`, but with an incorrect dependency on `JavaPromptSession.PromptModifier`.
- TypeScript does not support `retrieveSearchHits`.
- Markdown does not support `retrieveSearchHits`.
- Budget handling differs between sessions.
- Markdown sorts ranks ascending while other sessions sort descending.

##### 2. Inconsistent naming

Examples:

- `rootForrest` should be `rootForest`.
- `childs` should be `children`.
- `dependenyChunk` typo in `JavaPromptModifier`.
- `splittIdx` typo.
- `JavdocGeneratedPostProcessor` typo.
- `sanatize` should be `sanitize`.
- `DEPENDENCIE_INFO` appears to be misspelled upstream, but the project relies on it.

These inconsistencies reduce readability and make APIs feel unstable.

##### 3. Magic metadata strings

The project relies heavily on string keys such as:

- `"java_code_section"`
- `"typescript_code_section"`
- `"visualbasic_code_section"`
- `"section_number"`
- `"pxchunk_type"`
- `"generated_at"`
- `"promptFormat"`

These are spread across many classes. Some classes define constants; others inline the strings.

This causes tight coupling between retrievers, chunkers, rankers, file providers, and metadata producers.

##### 4. Mixed languages in prompt text and logs

Most prompt templates and budget messages are German, while code, identifiers, and some logs are English. This may be acceptable for the target product, but it should be explicit and centralized. Currently, German text is embedded in many classes, making localization or product-level wording changes difficult.

##### 5. Unused imports and dead code

Examples:

- `ChunkRankingService` imports unused Spring classes.
- Several prompt sessions import `BiFunction` unnecessarily.
- `JavaRetriever.retrieveSearchHits` creates `chunks` but does not use it.
- `combineChunksByID` exists in multiple retrievers but is unused in some.
- `ChunkNode.childIDs` is unused.

These are minor individually but indicate lack of cleanup.

##### 6. SOLID principle adherence

- **Single Responsibility Principle:** Frequently violated. Several services do too much.
- **Open/Closed Principle:** Partially supported through strategies and language packs, but adding a new language still requires duplicating significant code.
- **Liskov Substitution Principle:** The `GoldenRetriever` interface appears inconsistently implemented. Several retrievers return empty lists for `retrieveSearchHits`.
- **Interface Segregation Principle:** `GoldenRetriever` may be too broad if some retrievers cannot reasonably support search hits.
- **Dependency Inversion Principle:** Reasonably good at the Spring service level, but weak inside prompt sessions and retrievers due to direct metadata and DAO assumptions.

### Extensibility

The architecture has some promising extension mechanisms, but adding a new language would currently be more difficult than necessary.

#### Strengths for extensibility

- A new ranking strategy can be added by implementing `ChunkRankingStrategy`.
- A new language can be registered with `LanguagePack`.
- A new retriever can implement `GoldenRetriever`.
- A new file view renderer can implement `FileViewProvider`.

#### Barriers to extensibility

##### 1. New language support requires too much duplication

To add a language like Python or C#, a developer would likely need to create:

- A ranker.
- A retriever.
- A prompt session copied from an existing language.
- A prompt modifier.
- A file view provider.
- A language pack.
- Metadata key conventions.
- Chunk section enum integration in another module.

There is no shared `AbstractPromptSession`, `ChunkHierarchyBuilder`, `ChunkCombiner`, or `PromptBudgetManager`.

##### 2. Metadata schema is implicit

There is no central schema or typed model for chunk metadata. The entire system depends on implicit agreement between chunk producers and retrievers.

A new provider must know exactly which metadata keys and enum names to produce. This makes plugin development fragile.

##### 3. Prompt modification is not abstracted enough

Language-specific retrievers manipulate prompts with ad hoc string replacement. Java and TypeScript both replace method names in class skeletons, while Visual Basic uses a more robust signature-based approach.

There is no common abstraction for:

- Skeleton insertion.
- Documentation insertion.
- Method body replacement.
- Dependency section insertion.
- Freshness notes.
- Budget-aware rendering.

##### 4. Ranking scales are inconsistent

Ranking scores vary dramatically:

- Java method rank: `5`
- TypeScript method rank: `10 * content.length()`
- Markdown rank: `content.length()`
- Visual Basic method rank: `5`

Because all retrievers may receive the same scored chunks and ranking affects prompt inclusion, inconsistent scales can lead to unpredictable cross-language behavior.

### Robustness & Error Handling

Robustness is one of the weakest areas of the codebase.

#### Critical issue: recursive overload bug in `GldRtrvrQuestioner`

```java
public String ask(String question, List<PxChunk> prefetchedChunks, Function<String, Boolean>... contextValidator) {
    return ask(cfg.getActiveProject().get().getName(), prefetchedChunks, contextValidator);
}
```

This method appears intended to call:

```java
ask(String storeName, String question, List<PxChunk> prefetchedChunks, ...)
```

However, it calls another overload with the signature:

```java
ask(String question, List<PxChunk> prefetchedChunks, ...)
```

As written, this can recursively call itself indefinitely, replacing the question with the active project name and eventually causing a `StackOverflowError`.

This is a high-priority production defect.

#### Unsafe `Optional` usage

The code frequently calls `.get()` on optionals:

- `cfg.getActiveProject().get()`
- `chunkDaoProvider.get(projectName).get()`
- `chatProvider.getByName("default").get()`
- `cu.getStorage().get()`
- `cu.getPrimaryType().get()`
- `method.getParentNode().get()`

This can cause runtime exceptions without useful domain-level error messages.

#### Null-safety gaps

Examples:

- `isTypeScriptChunk(PxChunk c)` does not check `c != null`.
- Many calls assume `chunk.getMetadata()` is non-null.
- `ChunkNode.getChunk()` can return null if `chunkReader` returns null.
- Prompt modifiers often assume `pxChunk` is non-null.
- `PxChunk.combine(...)` may return null, but callers often proceed without checking.

#### Fragile string operations

Examples:

- `TypeScriptRetriever.insertBefore()` logs when the method name is not found, but still calls `substring(0, -1)`, causing `StringIndexOutOfBoundsException`.
- `JavaPromptModifier.toMethodName()` contains a comment: `// A wild hack to deal with varargs`.
- Prompt replacement uses `prompt.replace(methodName, content)`, which can replace unintended occurrences.
- Method name extraction with `substring(lastIndexOf('.') + 1)` is fragile for nested types, overloaded methods, generics, lambdas, constructors, and operators.

#### Incomplete interface implementations

Several retrievers return empty search hits:

- `TypeScriptRetriever.retrieveSearchHits`
- `MarkdownRetriever.retrieveSearchHits`
- `TextRetriever.retrieveSearchHits`

These methods contain TODO comments or intentional empty responses. If consumers rely on `retrieveSearchHits`, behavior will be inconsistent by content type.

#### Tree building lacks cycle protection

Prompt sessions recursively follow `parent` links:

```java
private ChunkNode buildGraphToRoot(PxChunk c)
```

If the index contains cyclic parent references, this can cause infinite recursion.

#### Mutating domain data during recovery

Several `buildGraphToRoot` implementations do:

```java
c.setParent(null);
return buildGraphToRoot(c);
```

This mutates the `PxChunk` because a parent is missing in the DAO. That mutation is a side effect of prompt building and may leak into caller-visible state.

#### Exception handling is inconsistent

- `GRPromptEnrichment.findRelevantScored()` gracefully falls back from `searchVector` to `findRelevant`.
- `CliArgsParser` exits the JVM on parse errors.
- `JavaDocEnricher` catches broad exceptions and logs them, continuing processing.
- Many retrieval paths allow `NoSuchElementException`, `NullPointerException`, or `IllegalArgumentException` to escape.

The lack of a consistent error strategy makes operational behavior unpredictable.

### Performance & Resource Efficiency

The code is likely acceptable for small projects, but several patterns can become expensive for larger repositories or larger vector search result sets.

#### 1. Repeated DAO access

Prompt sessions lazily load chunks through:

```java
chunkStore.computeIfAbsent(id, k -> loadAndCombine(k));
```

This is good as a local cache, but graph construction still performs many DAO calls, especially in recursive parent traversal.

`combineScoredChunksByID()` often loads all parts of a chunk when not all parts were returned from search:

```java
chunkDao.findById(c.getId())
```

This may be intentional, but it can significantly expand prompt content and memory usage.

#### 2. Recursive tree search is O(n²)

`findRootForChunk()` iterates through root trees and calls `findChunkNodeInTree()` recursively. For many chunks, this can become quadratic.

A map of `chunkId -> ChunkNode` would be more efficient and simpler.

#### 3. String concatenation in loops

Some prompt builders use repeated string concatenation:

```java
context += rp.treeContext();
```

This can create unnecessary intermediate strings. Some classes use `StringBuilder`, others do not.

#### 4. Full-stream filtering

`PxChunkService.findByPredicate()` loads all chunks and filters in memory:

```java
findAll().filter(p)
```

This can be expensive depending on store size and DAO implementation.

#### 5. Blocking LLM rate limiting

`JavaDocEnricher` uses `Thread.sleep()` for rate limiting:

```java
Thread.sleep(MIN_WAIT - callDuration);
```

This blocks the processing thread and is not suitable for scalable or asynchronous execution.

#### 6. Prompt budget handling is local and global, but imprecise

There are two budget layers:

- Per-retriever/session `maxContentLength`.
- Global `totalContentLength` in `GRPromptEnrichment`.

However:

- The first retriever output is always included even if it exceeds the global budget.
- Budget is based on character count, not token count.
- Retrievers may load and build large prompts only to be skipped later.
- Ranking and budget behavior is inconsistent across retrievers.

#### 7. Sorting during traversal

`ChunkNode.visit()` sorts children every time a node is visited:

```java
Collections.sort(childs, ...)
```

This mutates structure during traversal and repeats sorting work.

## 4. Strengths & Best Practices

- **Clear retrieval domain model:** The project has recognizable domain concepts: chunks, scored chunks, ranking, prompt enrichment, retrievers, and file views.

- **Spring-based dependency injection:** Services, components, and ranking strategies are wired cleanly through Spring.

- **Pluggable ranking mechanism:** `ChunkRankingStrategy` is a clean extension point for content-specific ranking.

- **Language pack integration:** `JavaRetrieverPack`, `TypeScriptRetrieverPack`, and `VisualBasicRetrieverPack` make language retrievers discoverable through the broader platform.

- **Retriever registry abstraction:** `RetrieverRegistry` avoids hard-coding individual retrievers in `GRPromptEnrichment`.

- **Deterministic language retriever ordering:** Language pack retrievers are ordered through a `TreeMap`.

- **Local chunk caching in prompt sessions:** `chunkStore` avoids repeated chunk combination for the same ID within a session.

- **Context validation extension:** `Function<String, Boolean>... contextValidator` allows callers to enforce domain-specific context requirements.

- **Fallback retrieval behavior:** `GRPromptEnrichment` falls back from vector search to traditional relevance search when vector search is unsupported.

- **Iterative search relaxation:** `reIterate()` progressively expands search or lowers similarity thresholds, which improves the chance of finding context.

- **File view providers are well-scoped:** The `FileViewProvider` implementations focus on reconstructing file-level views and are mostly self-contained.

- **Visual Basic prompt reconstruction is relatively robust:** It uses regex-based signature matching, normalized token matching, and helper methods that are cleaner than simple string replacement.

- **Search result scoring is preserved:** `combineScoredChunksByID()` generally carries forward the best similarity score from combined parts.

- **Freshness metadata support:** Java and text prompt generation include `generated_at` information when available.

## 5. Identified Risks & Technical Debt

### Critical Risks

#### 1. Infinite recursion in `GldRtrvrQuestioner`

The overload:

```java
ask(String question, List<PxChunk> prefetchedChunks, ...)
```

appears to recursively call itself instead of forwarding to the store-aware overload. This is likely to fail with `StackOverflowError`.

Impact: Critical runtime failure in core question-answering functionality.

#### 2. Incomplete `GoldenRetriever` implementations

Several retrievers return empty search hits:

- TypeScript
- Markdown
- Text

This violates caller expectations if `GoldenRetriever.retrieveSearchHits()` is part of the contract.

Impact: Inconsistent API behavior and missing search results for supported content types.

#### 3. Fragile prompt mutation can throw exceptions

`TypeScriptRetriever.insertBefore()` can throw if the method marker is missing. Java and TypeScript prompt construction also relies on raw string replacement, which may replace the wrong text.

Impact: Runtime failures or corrupted prompts.

#### 4. Unsafe optional and null handling

The project frequently assumes dependencies, project configuration, chunk metadata, parent nodes, and DAO results exist.

Impact: Runtime failures with low diagnostic value.

### High Risks

#### 5. Excessive duplication between prompt sessions

Four prompt session classes duplicate core tree-building and prompt-ranking logic.

Impact:

- Higher maintenance cost.
- Inconsistent behavior.
- Bugs fixed in one language may remain in others.
- Adding languages becomes expensive.

#### 6. Tight coupling to metadata keys

Metadata keys are embedded throughout the codebase as strings.

Impact:

- Difficult to evolve chunk schema.
- Easy to introduce subtle bugs through typos or inconsistent naming.
- Hard for plugin authors to know required metadata.

#### 7. Mixed responsibilities in retriever classes

Retrievers perform multiple roles:

- Filter by language.
- Combine chunk parts.
- Interpret metadata.
- Build prompt content.
- Query related chunks.
- Create search hits.
- Manage fallback behavior in some cases.

Impact: Harder to test, extend, and reason about.

#### 8. No explicit ordering for ranking strategies

`ChunkRankingService` uses the first strategy whose `supports()` returns true. If a chunk matches multiple strategies, behavior depends on Spring list ordering.

Impact: Potentially nondeterministic ranking behavior.

#### 9. Recursive graph construction without cycle detection

If chunk parent references contain cycles, graph construction can recurse indefinitely.

Impact: Stack overflow or request hangs on corrupt indexes.

#### 10. Mutating chunks during prompt graph construction

Prompt sessions set missing-parent chunks to root by mutating the chunk:

```java
c.setParent(null);
```

Impact: Side effects and hard-to-debug state mutation.

### Medium Risks

#### 11. Character-based prompt budgeting

Budgets are character-based, not token-based. LLM limits are usually token-based.

Impact: Prompts may exceed model limits or underutilize available context.

#### 12. Inefficient prompt construction

Repeated concatenation, repeated tree searches, repeated sorting, and repeated DAO lookups may become costly.

Impact: Slower retrieval on larger projects.

#### 13. CLI parser exits JVM directly

`CliArgsParser` calls `System.exit(0)` on help and parse errors.

Impact: Poor composability and hard testing. This is especially problematic in Spring applications or libraries.

#### 14. JavaDoc enrichment combines too many responsibilities

`JavaDocEnricher` handles parsing, rate limiting, prompt construction, LLM invocation, JavaDoc validation, sanitation, file output, and event publication.

Impact: Hard to test and hard to evolve.

#### 15. Hard-coded output paths

`JavaDocEnricher` writes to:

```java
build/enriched-src
```

Impact: Limited configurability and potential surprises in different build environments.

#### 16. Language-specific rank scales are inconsistent

TypeScript method rank is proportional to content length, while Java and VB use small constants.

Impact: Relevance ordering may be skewed and difficult to tune.

#### 17. Prompt content is embedded in code

Large German prompt templates and output markers are hard-coded.

Impact: Difficult localization, versioning, and prompt experimentation.

#### 18. Use of raw `Collections.EMPTY_LIST`

Several methods return raw collections.

Impact: Type-safety warnings and minor maintainability issue.

#### 19. Unused fields and methods

Examples:

- `ChunkNode.childIDs`
- several `combineChunksByID()` variants
- unused imports

Impact: Noise and possible confusion.

## 6. Actionable Recommendations

### Priority 0: Fix Critical Runtime Defects

#### 1. Fix `GldRtrvrQuestioner.ask()` overload recursion

Current problematic method:

```java
public String ask(String question, List<PxChunk> prefetchedChunks, Function<String, Boolean>... contextValidator) {
    return ask(cfg.getActiveProject().get().getName(), prefetchedChunks, contextValidator);
}
```

It should pass the question to the store-aware overload:

```java
public String ask(String question, List<PxChunk> prefetchedChunks, Function<String, Boolean>... contextValidator) {
    String projectName = cfg.getActiveProject()
            .orElseThrow(() -> new IllegalStateException("No active project configured"))
            .getName();
    return ask(projectName, question, prefetchedChunks, contextValidator);
}
```

#### 2. Fix `TypeScriptRetriever.insertBefore()`

Return safely when the marker is not found.

```java
private String insertBefore(String prompt, String methodName, String content) {
    int splitIdx = prompt.indexOf(methodName);
    if (splitIdx < 0) {
        log.warning("Methodenname %s nicht gefunden in Prompt: %s".formatted(methodName, prompt));
        return prompt;
    }
    return prompt.substring(0, splitIdx) + content + prompt.substring(splitIdx);
}
```

#### 3. Replace unsafe `.get()` calls on critical paths

At minimum, update:

- `chunkDaoProvider.get(projectName).get()`
- `cfg.getActiveProject().get()`
- `chatProvider.getByName("default").get()`

Use domain-specific exceptions with actionable messages.

#### 4. Add null guards for chunks and metadata

Introduce helper methods such as:

```java
static Map<String, String> metadata(PxChunk chunk) {
    return chunk == null || chunk.getMetadata() == null ? Map.of() : chunk.getMetadata();
}
```

Use it consistently in retrievers, rankers, prompt sessions, and file view providers.

### Priority 1: Remove Architectural Duplication

#### 5. Introduce a shared `PromptSession` abstraction

Create a reusable base class for tree construction, ranking, context validation, and budgeting.

Possible abstraction:

```java
public abstract class AbstractPromptSession {
    protected abstract String metadataKey();
    protected abstract String nodeType(PxChunk chunk);
    protected abstract String modifyPrompt(PxChunkDao dao, PxChunk chunk, String currentPrompt);
}
```

Shared responsibilities should include:

- Chunk cache.
- Root forest construction.
- Cycle detection.
- Node lookup map.
- Ranking.
- Prompt budget.
- Search hit creation.
- Context validation.

Language-specific classes should only define:

- Metadata key.
- Section handling.
- Prompt modification.
- Code block language.
- Signature matching strategy.

#### 6. Extract chunk recombination logic

`combineScoredChunksByID()` is duplicated in Java, TypeScript, Visual Basic, Markdown, and Text retrievers.

Create a service:

```java
@Service
public class ChunkCombinationService {
    List<ScoredChunk> combineScoredChunksById(
        PxChunkDao dao,
        List<ScoredChunk> chunks,
        Predicate<PxChunk> filter
    )
}
```

Benefits:

- Consistent null handling.
- Consistent logging.
- Consistent score behavior.
- Fewer duplicated bugs.

#### 7. Create typed metadata constants or metadata model

Introduce constants such as:

```java
public final class ChunkMetadataKeys {
    public static final String JAVA_CODE_SECTION = "java_code_section";
    public static final String TYPESCRIPT_CODE_SECTION = "typescript_code_section";
    public static final String VISUALBASIC_CODE_SECTION = "visualbasic_code_section";
    public static final String GENERATED_AT = "generated_at";
}
```

Preferably create typed wrappers:

```java
public record ChunkMetadata(Map<String, String> raw) {
    Optional<JavaCodeSection> javaSection();
    Optional<TypeScriptCodeSection> typeScriptSection();
    Optional<Instant> generatedAt();
}
```

### Priority 2: Make Retriever Behavior Consistent

#### 8. Complete `retrieveSearchHits()` implementations

Implement search-hit retrieval for:

- `TypeScriptRetriever`
- `MarkdownRetriever`
- `TextRetriever`

If some retrievers should not support search hits, split the interface:

```java
interface PromptRetriever {
    StringBuilder buildPromptForFindings(...);
}

interface SearchHitRetriever {
    List<SearchHit> retrieveSearchHits(...);
}
```

#### 9. Normalize ranking scales

Define ranking semantics across languages.

For example:

- Base section weight: method/class/import/doc/dependency.
- Similarity score multiplier.
- Optional content-size normalization.
- Optional recency/freshness modifier.

Avoid one language using length-scaled values while another uses constants unless intentional and documented.

#### 10. Add explicit ordering for ranking strategies

Use Spring `@Order` or a priority method:

```java
public interface ChunkRankingStrategy {
    int priority();
    boolean supports(PxChunk chunk);
    double rank(PxChunk chunk);
}
```

Then sort strategies in `ChunkRankingService`.

### Priority 3: Improve Robustness

#### 11. Add cycle detection to graph construction

When following parent references, track visited IDs:

```java
private ChunkNode buildGraphToRoot(PxChunk chunk, Set<String> visited)
```

If a cycle is detected, log a warning and treat the current chunk as root.

#### 12. Stop mutating chunks during prompt construction

Replace:

```java
c.setParent(null);
return buildGraphToRoot(c);
```

with a non-mutating root creation path:

```java
return new ChunkNode(c.getId(), nodeType(c), this::readChunk);
```

#### 13. Make prompt modification marker-safe

Avoid raw `String.replace()` for method names. Prefer structured replacement based on:

- Exact signature.
- Normalized signature.
- Placeholder markers generated by the chunker.
- Line ranges from metadata.
- AST-derived reconstruction where possible.

The Visual Basic retriever’s normalized-signature approach is a better direction than Java/TypeScript method-name replacement.

#### 14. Add defensive handling around `PxChunk.combine()`

Centralize combine behavior and explicitly handle null or empty results.

```java
Optional<PxChunk> combineSafely(List<PxChunk> chunks)
```

Avoid passing null chunks into ranking or prompt modifiers.

### Priority 4: Improve Performance and Scalability

#### 15. Use node maps during prompt session construction

Maintain:

```java
Map<String, ChunkNode> nodesById
```

This avoids recursive tree scans for each chunk and reduces complexity from potentially O(n²) to near O(n).

#### 16. Use `StringBuilder` consistently

Replace repeated string concatenation in loops, especially in `JavaPromptSession.buildPrompt()`.

#### 17. Avoid building retriever prompts that will be globally skipped

Move global budget awareness closer to retrievers or provide a budget object:

```java
public interface PromptBudget {
    boolean canFit(int characters);
    void append(String content);
}
```

This prevents expensive construction of large prompt sections that are discarded later.

#### 18. Consider token-aware budgeting

Introduce a token estimator or integrate with the LLM provider’s tokenizer if available. Character count is a weak proxy for model limits.

#### 19. Batch DAO reads where possible

Instead of repeatedly calling `findById()` for parents and related chunks, add DAO methods for batch loading by ID if supported.

### Priority 5: Improve Testability

#### 20. Add unit tests for critical prompt behavior

High-value test areas:

- `GldRtrvrQuestioner` overload forwarding.
- `GRPromptEnrichment.reIterate()`.
- `combineScoredChunksByID()` behavior.
- Missing parent handling.
- Cyclic parent handling.
- Java method signature replacement.
- TypeScript missing marker behavior.
- Visual Basic normalized signature matching.
- Markdown section ordering.
- File view reconstruction.

#### 21. Add contract tests for all `GoldenRetriever` implementations

Every retriever should be tested against the same expectations:

- Filters only supported chunks.
- Combines chunk parts correctly.
- Applies max content length.
- Does not throw on null/empty inputs.
- Returns deterministic output.
- Supports or explicitly does not support search hits.

#### 22. Isolate `JavaDocEnricher` collaborators

Split `JavaDocEnricher` into smaller services:

- `JavaSourceScanner`
- `JavaDocPromptBuilder`
- `JavaDocGenerator`
- `JavaDocSanitizer`
- `EnrichedSourceWriter`
- `RateLimiter`

This would allow focused unit tests and safer evolution.

### Priority 6: Configuration and Operational Improvements

#### 23. Externalize prompt templates

Move hard-coded prompt text to configuration or resource files.

Benefits:

- Easier prompt iteration.
- Easier localization.
- Easier A/B testing.
- Cleaner code.

#### 24. Replace `System.exit()` in CLI parsing

`CliArgsParser` should publish an application event or throw a controlled exception instead of directly terminating the JVM.

#### 25. Make output paths configurable

Replace hard-coded `build/enriched-src` with a configuration property.

#### 26. Improve logging consistency

Use structured, actionable logs for:

- Missing chunks.
- Missing parents.
- Unsupported metadata.
- Retriever skips due to budget.
- Search fallback rounds.
- Empty prompt results.

### Suggested Refactoring Roadmap

#### Phase 1: Stabilization

- Fix `GldRtrvrQuestioner` recursion.
- Fix `TypeScriptRetriever.insertBefore()`.
- Add null and optional safety on critical paths.
- Complete or explicitly split unsupported `retrieveSearchHits()` behavior.
- Add regression tests for these defects.

#### Phase 2: Consolidation

- Extract `ChunkCombinationService`.
- Extract `AbstractPromptSession`.
- Introduce metadata constants/wrappers.
- Remove duplicated `combineChunksByID()` and prompt session tree logic.

#### Phase 3: Robustness

- Add cycle detection.
- Stop mutating chunks during prompt building.
- Normalize signature matching for Java and TypeScript.
- Add consistent exception types and error messages.

#### Phase 4: Performance

- Add node maps for tree construction.
- Introduce token-aware budgeting.
- Batch DAO loading where possible.
- Avoid building globally skipped prompt sections.

#### Phase 5: Productization

- Externalize prompts.
- Make JavaDoc enrichment configurable.
- Add contract tests for retrievers and file view providers.
- Add metrics around retrieval time, chunk count, prompt size, fallback rounds, and skipped content.

_This document was generated with .dp and gemini-3-flash-preview_

