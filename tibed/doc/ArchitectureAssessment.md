# Software Architecture Assessment: TiBed – PrjXP Embedding & Enrichment Pipeline

## 1. Executive Summary

TiBed is a Spring Boot CLI application within the PrjXP multi-module ecosystem that ingests code/documentation chunks (JSONL), computes vector embeddings via LangChain4J, persists them into a Lucene-based vector store, and enriches the index with two analytical layers: a semantic graph (Leiden clustering over kNN edges) and a TF-IDF signature layer. It supports three transfer modes (store, export, import) for cross-machine vector portability, with optional encryption.

**Key Strengths:**
- Clean pipeline orchestration with well-separated batch stages (ingestion → semantic graph → signatures).
- Strategy pattern for embedding providers, enabling pluggable backends.
- Performance-aware parallelism design (bandwidth-bound kNN with capped thread pool).
- Defensive resource management and thoughtful edge-case handling (placeholder vectors, compatibility checking).

**Most Critical Risks:**
- Per-chunk store lookups in the ingestion loop create O(N) round-trips that will degrade at scale.
- A resource-leak path in `EmbeddingStoreSupplier` when multiple projects are resolved sequentially without a shared bean.
- Silent exception swallowing in `EmbeddingImportService` can produce a partially-populated index without operator awareness.
- A stub implementation (`MixedBredEmebeddingClient`) returns `null`, which will cause a `NullPointerException` downstream if activated.

## 2. Architectural Style & Patterns

| Pattern | Where Applied | Assessment |
|---------|--------------|------------|
| **Layered / Pipeline** | `EmbeddingService.executeForProject` orchestrates ingestion → enrichment as sequential stages | Clear, linear flow; easy to follow |
| **Strategy** | `EmbeddingExecutor` interface with `LangChain4JEmbedderImpl` (`@Primary`) and `MixedBredEmbedding` | Good extensibility; the default `embed()` that throws is a minor code smell |
| **Factory / Supplier** | `EmbeddingStoreSupplier` resolves a shared bean or creates a dedicated store | Appropriate for the hub-vs-CLI duality |
| **Batch Processing** | `BatchingUtils.pack`, `PxChunkFromJsonLReader.readChunksFromJsonlStreamBatched` | Standard streaming-batch idiom |
| **Sidecar File** | `AstNeighboursLoader`, `SemanticGraphSidecar` | Cross-module data sharing without tight coupling |
| **Event-Driven Config** | `CliArgsParser` listens to `CliArgsParsingEvent` | Decouples CLI parsing from application bootstrap |
| **Guard / Guard-Clause** | `EmbeddingCompatibilityChecker`, `StoreIdChecker` | Pre-flight validation before expensive operations |
| **Record-based Value Objects** | `EdgeConfig`, `SemanticEdge`, `CheckReport`, `Neighbor` | Immutable, self-documenting data carriers |

**Separation of Concerns:** Generally strong. The `de.spraener.prjxp.tibed` package contains the orchestration services, `tibed.semantic` the graph logic, `tibed.signature` the TF-IDF layer, and `tibed.embedder` the embedding strategies. The `de.spraener.prjxp.common` and `de.spraener.prjxp.lucene` packages (scanned via `scanBasePackages`) provide shared infrastructure. The one area of mild coupling is that `EmbeddingService` directly depends on both `SemanticGraphBatch` and `SignatureBatch` rather than a higher-level "EnrichmentPipeline" abstraction, making the orchestration logic monolithic.

## 3. Quality Attribute Evaluation

### Maintainability & Readability

- **Positive:** Consistent use of Lombok `@RequiredArgsConstructor` for constructor injection; descriptive Javadoc on public methods; meaningful constant names (`KEYWORDS_FIELD`, `AUTO_PARALLELISM_CAP`); records for immutable value types.
- **Negative:**
  - `MixedBredEmebeddingClient` contains a typo in the class name ("Emebedding") and returns `null` from `embed()`—a landmine for any caller.
  - `EmbeddingService` mixes three responsibilities (ingestion orchestration, per-chunk dedup, enrichment dispatch) in a single ~150-line class. The `embedChunk` method accepts `PxChunk[]` while the rest of the codebase uses `List<PxChunk>`, creating a type-system inconsistency.
  - `firstNonBlank(String...)` is duplicated verbatim in both `EmbeddingExportService` and `EmbeddingImportService`—a candidate for extraction to a utility.
  - Logging is inconsistent: most classes use `@Log` (java.util.logging), but `EmbeddingCompatibilityChecker` uses SLF4J directly. This will produce two different log formats in the same application.
  - The `EmbeddingExecutor` interface's default `embed()` method that throws `UnsupportedOperationException` is a "marker" pattern that forces every alternative implementation to either override it or risk a runtime failure. A `supportsExport()` capability query would be cleaner.

### Extensibility

- **Positive:** Adding a new embedding provider requires implementing `EmbeddingExecutor` and annotating with `@Service` (or `@Primary` to override). The `EmbeddingStoreSupplier` already handles the shared-vs-dedicated store duality. The `EmbeddingCompatibilityChecker` is a standalone component that can be wired into the import path without modifying existing services.
- **Negative:**
  - The enrichment stage is hard-coded in `EmbeddingService.runEnrichment` as two `instanceof LuceneEmbeddingStore` checks. Adding a third enrichment layer (e.g., dependency-graph edges) requires modifying this method. A `List<EnrichmentBatch>` injected via Spring would allow open/closed extension.
  - `SemanticEdgeBuilder` hard-codes MIME-type filtering (`text/x-java-code`, `text/x-typescript-code`, `text/markdown`, etc.) as constants. Adding a new language requires editing the class. A configurable `Set<String>` in `EdgeConfig` or a strategy for "input eligibility" would be more extensible.
  - The `EmbeddingStoreSupplier` is Lucene-specific in its fallback path. If a non-Lucene store (e.g., Qdrant, pgvector) were needed, the supplier would need modification rather than a new implementation.

### Robustness & Error Handling

- **Positive:**
  - `AstNeighboursLoader` treats a missing/unreadable sidecar as non-fatal (returns `Map.of()`), which is correct for an optional enrichment.
  - `EmbeddingCompatibilityChecker` handles null vectors, dimension mismatches, and zero-norm vectors gracefully.
  - `EmbeddingService` uses an `AtomicBoolean ingestionSucceeded` gate to prevent enrichment on a broken index.
  - `SemanticEdgeBuilder` properly propagates `InterruptedException` and wraps `ExecutionException` causes.
- **Negative:**
  - `EmbeddingImportService.execute()` wraps the entire import loop in `catch (Exception e) { logService.error(...) }`—the method returns `void` and the caller has no way to know the import partially failed. The pipeline will proceed to enrichment on a corrupted index.
  - `EmbeddingExportService.exportForProject` catches the exception, logs it, then re-throws as `RuntimeException`—but the `execute()` method (CLI path) catches the same exception and only logs it. The asymmetry means the CLI silently succeeds after a failed export.
  - `LangChain4JEmbedderImpl.execute` uses `synchronized (storeSupplier)` to guard `store.addAll`. This locks on the *supplier* object, not the store. If two different stores were ever used concurrently (multi-project hub), the lock is meaningless. If only one store exists, the lock is unnecessary (Lucene's `IndexWriter` is already internally synchronized).
  - `StoreIdChecker.containsChunk` allocates a zero-vector of the configured dimension for every non-Lucene store lookup—wasteful and semantically odd (a zero vector has no direction in cosine space).

### Performance & Resource Efficiency

- **Positive:**
  - `SemanticEdgeBuilder.searchTopK` uses a bounded thread pool (capped at 4) with a well-documented rationale (memory-bandwidth-bound kNN). The pool is created per call and closed via try-with-resources—no thread leak.
  - `EmbeddingExportService` streams JSONL line-by-line and processes in configurable batches, avoiding loading the entire file into memory.
  - `SignatureBatch` builds all upsert patches in memory and calls `store.upsertAll` once—minimizing Lucene commit overhead.
- **Negative:**
  - **O(N) store lookups:** In `EmbeddingService.embedChunk`, `needsEmbedding` is called per-chunk via `storeIdChecker.needsImport`, which performs a Lucene `hasMatch` (or a full kNN search for non-Lucene stores) for *every* chunk in the batch. For a batch of 500 chunks, this is 500 individual index queries. A batched existence check (e.g., a single Lucene query with a `TermsFilter` on all chunk IDs) would reduce this to one round-trip.
  - **`countInputLines`** reads the entire input file (potentially encrypted, potentially multi-GB) solely to count lines for a progress callback. For stdin (`"-"`) it correctly returns -1, but for a large file this is a full I/O pass before the actual export begins.
  - **`EmbeddingStoreSupplier`** creates a new `LuceneEmbeddingStore` on every `getStore()` call when no shared bean exists, overwriting the `createdStore` field. If the hub calls `getStore` for multiple projects sequentially, all but the last store are leaked (never closed). The `destroy()` method only closes the most recent one.
  - **`SemanticGraphBatch.run`** calls `store.readAllVectors(projectFilter)` to load *all* vectors for the project into memory, then `edgeBuilder.selectInputChunks` filters them. For a project with 100k+ chunks, this is a large heap allocation. A filtered read (if the store supports it) or a streaming API would be preferable.

## 4. Strengths & Best Practices

1. **Pipeline with explicit gates:** The `ingestionSucceeded` flag prevents enrichment on a partially-written index—a pragmatic resilience pattern for batch ETL.
2. **Performance-aware concurrency:** The `AUTO_PARALLELISM_CAP = 4` with a detailed comment explaining the memory-bandwidth bottleneck (and measured timings on M5 Max) shows empirical engineering rather than blind `availableProcessors()` usage.
3. **Placeholder vector design:** Using an all-ones vector (not zeros) for FULLTEXT chunks, with a comment explaining *why* zeros would produce NaN in Lucene's cosine KNN, demonstrates deep understanding of the underlying vector math.
4. **Cross-platform compatibility checking:** `EmbeddingCompatibilityChecker` with its three-tier verdict (COMPATIBLE / PARTIALLY_COMPATIBLE / INCOMPATIBLE) and configurable thresholds is a thoughtful guard against a real operational risk (GPU vs. CPU rounding).
5. **Sidecar file pattern:** `AstNeighboursLoader` and `SemanticGraphSidecar` allow cross-module data sharing (chunk-norris → tibed) without compile-time coupling or shared database tables.
6. **Defensive null/blank handling:** `AstNeighboursMerger`, `SignatureBatch.joined()`, and `EmbeddingStoreSupplier` all gracefully handle absent data rather than throwing.
7. **Unique bean names for multi-module scanning:** `@Component("tibedCliArgsParser")` and `@Bean("tibedRun")` with explanatory comments prevent Spring bean conflicts when the hub app scans multiple modules.
8. **`DisposableBean` for resource cleanup:** `EmbeddingStoreSupplier.destroy()` ensures the Lucene `IndexWriter` is closed on application shutdown.
9. **Canonical edge ordering:** `SemanticEdgeBuilder` normalizes edge endpoints via string comparison before deduplication, ensuring undirected edges are stored consistently.
10. **Progress callback via `BiConsumer<Integer, Integer>`:** The `exportForProject` overload allows the hub to report progress without coupling the export service to a specific UI or logging framework.

## 5. Identified Risks & Technical Debt

| # | Severity | Location | Issue |
|---|----------|----------|-------|
| 1 | **High** | `EmbeddingImportService.execute()` | Catches all exceptions from the import loop and only logs. The method returns `void`; the caller (`TiBedCliApp`'s `CommandLineRunner`) cannot detect failure. A partially-imported index will be silently enriched. |
| 2 | **High** | `EmbeddingService.embedChunk` → `StoreIdChecker.needsImport` | Per-chunk store existence check creates O(batchSize) Lucene queries per batch. At 500-chunk batches over a 50k-chunk project, this is 50k individual index lookups. |
| 3 | **High** | `EmbeddingStoreSupplier.getStore()` | When no shared bean exists, each call creates a new `LuceneEmbeddingStore` and overwrites `createdStore`. Multiple sequential calls leak IndexWriters (file handles, memory). |
| 4 | **Medium** | `MixedBredEmebeddingClient.embed()` | Returns `null`. If this bean is ever activated (e.g., via profile), `LangChain4JEmbedderImpl`-style downstream code will NPE. The class name also has a typo. |
| 5 | **Medium** | `LangChain4JEmbedderImpl.execute()` | `synchronized (storeSupplier)` locks on the wrong object. The supplier is a singleton; the store is the shared mutable resource. This provides no real mutual exclusion for concurrent store writes. |
| 6 | **Medium** | `EmbeddingService.runEnrichment` | Hard-coded `instanceof LuceneEmbeddingStore` checks for each enrichment batch. Adding a new enrichment type requires modifying this method (violates Open/Closed). |
| 7 | **Medium** | `SemanticEdgeBuilder` | MIME-type and section-key filtering is hard-coded. Adding a new language (e.g., Python, Go) requires editing the class. |
| 8 | **Low** | `EmbeddingExportService` / `EmbeddingImportService` | `firstNonBlank(String...)` is duplicated. |
| 9 | **Low** | `EmbeddingCompatibilityChecker` | Uses SLF4J while all other classes use `@Log` (JUL). Inconsistent log output format. |
| 10 | **Low** | `EmbeddingService` | `embedChunk` accepts `PxChunk[]` (array) while the `EmbeddingExecutor` interface and all other call sites use `List<PxChunk>`. The public `embedChunks` method bridges the two, adding an unnecessary conversion. |
| 11 | **Low** | `EmbeddingExportService.exportForProject` | Creates a `FileOutputStream` without try-with-resources; only the `BufferedWriter` is in a try-finally. If `new FileOutputStream(...)` succeeds but `new OutputStreamWriter(...)` throws, the file handle leaks. |
| 12 | **Low** | `SemanticGraphBatch.run` | Loads all project vectors into a `List<VectorChunk>` in memory. For very large projects this is a significant heap allocation. |

## 6. Actionable Recommendations

### Priority 1 – Correctness & Data Integrity (Do First)

1. **Fix the import failure propagation.** In `EmbeddingImportService.execute()`, either:
   - Throw a checked/unchecked exception after the catch block so the `CommandLineRunner` can exit non-zero, or
   - Introduce an `ImportResult` record (success count, failure count, `boolean complete`) and have the runner inspect it.
   At minimum, call `System.exit(1)` or throw after the catch to prevent the pipeline from proceeding on a broken index.

2. **Fix the `EmbeddingStoreSupplier` resource leak.** Change `createdStore` to a `Map<String, EmbeddingStore<TextSegment>>` keyed by project name, and close *all* created stores in `destroy()`. Alternatively, document and enforce that `getStore` is called at most once per application lifecycle (CLI mode), and add an assertion.

3. **Remove or properly implement `MixedBredEmebeddingClient`.** Either delete the stub (and `MixedBredEmbedding`) or implement `embed()` to return `List.of()` with a clear log warning. Fix the class-name typo. If it is a placeholder for future work, guard it with `@Profile("mixedbred")` so it is never active by default.

### Priority 2 – Performance (Next Sprint)

4. **Batch the existence check.** Replace the per-chunk `storeIdChecker.needsImport` call in `EmbeddingService.embedChunk` with a single batched query. For Lucene, this means one `hasMatch` with a `TermsFilter` (or `In` filter) over all chunk IDs in the batch. For the generic `EmbeddingStore` path, a single kNN search with `maxResults = batchSize` and a terms filter. This reduces N index round-trips to 1.

5. **Eliminate the redundant `synchronized(storeSupplier)`.** If the store is a shared singleton `LuceneEmbeddingStore` whose `IndexWriter` is internally thread-safe for `addDocument`, remove the lock. If true mutual exclusion is needed (e.g., for a non-thread-safe store), lock on the *store* object or introduce a dedicated `ReentrantLock` in the supplier.

6. **Avoid the full-file line count in `countInputLines`.** For the progress callback, either:
   - Use a streaming line counter that runs concurrently with the export (a `CountingInputStream` wrapper), or
   - Accept `-1` (unknown total) for all file inputs and only report "N embedded so far" without a percentage, or
   - Cache the line count from a previous run in a sidecar file.

### Priority 3 – Extensibility & Clean Architecture (Backlog)

7. **Introduce an `EnrichmentBatch` interface** (e.g., `void run(String project, LuceneEmbeddingStore store) throws IOException`) and inject `List<EnrichmentBatch>` into `EmbeddingService`. Replace the two hard-coded `instanceof` checks with a loop over the injected list. This allows new enrichment layers (dependency edges, call-graph signatures, etc.) to be added via a new `@Service` class without modifying `EmbeddingService`.

8. **Externalize the MIME/section filter in `SemanticEdgeBuilder`.** Move the `DOC_MIMES`, `JAVA_MIME`, `TS_MIME`, and section-key constants into `EdgeConfig` (or a dedicated `InputEligibilityConfig` in `PrjXPConfig`) so new languages can be added via `prjxp.yaml` without a code change.

9. **Unify logging.** Replace the SLF4J usage in `EmbeddingCompatibilityChecker` with `@Log` (JUL) to match the rest of the codebase, or (better) migrate the entire module to SLF4J for consistent structured logging.

10. **Extract `firstNonBlank` to a shared utility** (e.g., `de.spraener.prjxp.common.util.Strings`) and remove the duplicates.

11. **Standardize on `List<PxChunk>`** throughout. Change `EmbeddingService.embedChunk` to accept `List<PxChunk>` and remove the array-based overload. The public `embedChunks` method can then delegate directly without the `toArray` conversion.

12. **Add a `supportsExport()` capability method** to `EmbeddingExecutor` (replacing the throwing default `embed()`). The export service can check this before calling `embed()`, and the `MixedBredEmbedding` implementation can return `false` cleanly.

### Priority 4 – Observability & Testing (Ongoing)

13. **Add structured metrics/logging to the pipeline.** Log (or emit Micrometer metrics for) per-batch: chunks read, chunks embedded, chunks skipped (already present), chunks failed, elapsed time. This makes operational debugging of large runs tractable.

14. **Write integration tests** for the three transfer modes (store, export, import) using a temporary Lucene index directory. At minimum, test: round-trip fidelity (export → import → search), the `tibedResetStore` path, the enrich-only path, and the compatibility-checker verdicts.

15. **Add a `@Profile("test")` guard** on the `CommandLineRunner` bean (already partially done with `@Profile("!test")`) and ensure the test profile provides mock `EmbeddingModel` and `LuceneEmbeddingStore` beans for unit-level testing of the batch classes.

_This document was generated with .dp and gemini-3-flash-preview_

