# Software Architecture Assessment: TiBed – PrjXP Embedding Pipeline CLI

## 1. Executive Summary

TiBed is a Spring Boot–based CLI application within the PrjXP ecosystem that manages the full lifecycle of text embeddings: ingesting chunked documents, generating vector embeddings (via LangChain4J), persisting them in a Lucene-based vector store, exporting/importing pre-embedded JSONL artifacts (with optional encryption), and optionally computing a Leiden-clustered semantic graph. The architecture follows a pragmatic layered design with a Strategy pattern for embedding backends and a Supplier pattern for store lifecycle management.

**Key Strengths:**
- Clean separation of the three operational modes (store / export / import) into dedicated services.
- Well-thought-out multi-project support with a shared Lucene index and per-project metadata filtering.
- Thoughtful domain decisions documented in code (placeholder vectors for FULLTEXT, compatibility checking, mutual-neighbor edge construction).
- Use of Java records for immutable value objects and a clear `EmbeddingExecutor` strategy interface.

**Most Critical Risks:**
- A global `synchronized` block on a singleton bean serializes all store writes, creating a throughput bottleneck.
- Several services perform `instanceof LuceneEmbeddingStore` checks, leaking the concrete store type into business logic and undermining the `EmbeddingStore` abstraction.
- The `EmbeddingStoreSupplier.getStore()` method can instantiate a new `LuceneEmbeddingStore` on every call when no shared bean is present, risking multiple `IndexWriter` instances on the same directory.
- A stub class (`MixedBredEmebeddingClient`) with a typo in its name and a `null` return value is wired as a Spring `@Component`, posing a runtime `NullPointerException` risk if selected.

## 2. Architectural Style & Patterns

| Pattern / Style | Where Applied | Assessment |
|---|---|---|
| **Layered (Spring Boot idiomatic)** | `@Service` / `@Component` / `@Configuration` beans; `CommandLineRunner` entry point | Standard and appropriate for a CLI tool. |
| **Strategy** | `EmbeddingExecutor` interface with `LangChain4JEmbedderImpl` (`@Primary`) and `MixedBredEmbedding` | Good extensibility point; the `default embed()` method with `UnsupportedOperationException` is a reasonable compromise. |
| **Supplier / Factory** | `EmbeddingStoreSupplier` (implements `DisposableBean`) | Manages store lifecycle; however, the "create-on-demand" fallback path is fragile (see Risks). |
| **Pipeline / Chain of Responsibility** | `EmbeddingService.executeForProject()`: read → deduplicate → embed → store → semantic graph | Linear, synchronous pipeline. Clear and readable, but not easily parallelizable. |
| **Event-Driven (lightweight)** | `CliArgsParsingEvent` + `@EventListener` in `CliArgsParser` | Decouples CLI parsing from the main app class; idiomatic Spring. |
| **Builder / Record** | `EdgeConfig`, `SemanticEdge`, `CheckReport`, `EmbeddedChunkRecord` | Immutable value objects; clean and testable. |
| **Template Method (implicit)** | `EmbeddingExportService.export()` overloads with a `BiConsumer` progress callback | Allows the hub to inject progress reporting without changing the core logic. |

**Component Decoupling:**
- The `EmbeddingExecutor` interface provides a clean seam for swapping embedding providers. However, `LangChain4JEmbedderImpl` also injects `EmbeddingStoreSupplier` and performs the store write internally, coupling the *computation* of embeddings to the *persistence* step. A purer strategy would separate "compute vectors" from "persist vectors."
- `SemanticGraphBatch` and `SemanticEdgeBuilder` depend directly on `LuceneEmbeddingStore` (concrete class) rather than the `EmbeddingStore<TextSegment>` interface, breaking the abstraction boundary.
- `StoreIdChecker` contains an `instanceof LuceneEmbeddingStore` branch, again leaking the concrete type.

## 3. Quality Attribute Evaluation

### Maintainability & Readability

- **Positive:** Consistent use of Lombok `@RequiredArgsConstructor` for constructor injection; descriptive method names (`needsImport`, `hasVector`, `selectInputChunks`); Javadoc on non-obvious decisions (placeholder vector rationale, compatibility checker thresholds).
- **Negative:**
  - Inconsistent logging: most classes use `@Log` (JUL), but `EmbeddingCompatibilityChecker` uses SLF4J directly. This creates two logging facades in the same module.
  - The `firstNonBlank(String...)` utility is duplicated verbatim in `EmbeddingExportService` and `EmbeddingImportService`.
  - `MixedBredEmebeddingClient` contains a typo in the class name ("Emebedding"), which will be painful to rename later due to Spring bean-name coupling.
  - `EmbeddingExportService` is a 150+ line class handling file I/O, encryption, batching, progress reporting, and JSONL serialization. It would benefit from extracting a `JsonlTransferWriter` collaborator.

### Extensibility

- **Embedding providers:** The `EmbeddingExecutor` interface allows adding new providers, but the `execute()` method signature bundles store-write with embedding computation. A new provider that only supports "compute vectors" (like the export path) must still implement `execute()` or inherit the `default` that throws. A two-method interface (`embed()` + `persist()`) or a separate `EmbeddingModel` strategy would be cleaner.
- **Store backends:** The `EmbeddingStore<TextSegment>` interface from LangChain4J is the right abstraction, but three classes (`EmbeddingService`, `EmbeddingImportService`, `SemanticGraphBatch`) perform `instanceof LuceneEmbeddingStore` checks, effectively hard-coding Lucene-specific behavior (e.g., `hasMatch`, `readAllVectors`, `upsertAll`). Adding a non-Lucene store would require touching all of them.
- **Semantic graph:** Tightly coupled to Lucene (`LuceneEmbeddingStore` parameter, `VectorChunk` type). Not swappable.
- **Transfer / encryption:** Well-abstracted behind `TransferPasswordResolver` and `TransferSession`.

### Robustness & Error Handling

- **Batch-level fault isolation:** Both `EmbeddingService.embedChunk()` and `EmbeddingImportService.importBatch()` catch exceptions per batch, log them, and continue. This is a good resilience choice for a long-running CLI pipeline.
- **Gaps:**
  - `EmbeddingImportService.execute()` wraps the entire stream in a `try/catch` that logs and *swallows* the exception. The caller (`CommandLineRunner`) has no way to know the import partially failed. There is no exit-code propagation or aggregate failure summary.
  - `EmbeddingExportService.exportForProject()` rethrows as `RuntimeException`, but the CLI `execute()` path swallows the exception. Inconsistent failure semantics between the two entry points.
  - `EmbeddingStoreSupplier.getStore()` creates a new `LuceneEmbeddingStore` if no shared bean exists. If called multiple times (e.g., for different projects in a loop), it will create multiple `IndexWriter` instances on the same directory, causing `LockObtainFailedException`.
  - `StoreIdChecker.containsChunk()` for non-Lucene stores creates a zero-vector `Embedding` and performs a KNN search. This is a workaround that depends on the store's filter semantics and may behave differently across implementations.
  - `SemanticEdgeBuilder.buildSemanticEdges()` performs one KNN search per input chunk (O(n) searches). For a project with 10 000 chunks, this is 10 000 Lucene queries with no batching or parallelism.

### Performance & Resource Efficiency

- **`synchronized (storeSupplier)` in `LangChain4JEmbedderImpl.execute()`:** This serializes all store writes across the entire JVM. Since `storeSupplier` is a singleton, any concurrent call (e.g., from a hub running multiple projects) will block. This is a significant throughput bottleneck and a code smell indicating a missing concurrency abstraction (e.g., a `StoreWriteLock` or an async write queue).
- **O(n) KNN in `SemanticEdgeBuilder`:** Each chunk triggers a separate `store.searchKnn()` call. For large corpora this is the dominant cost. A batched or parallel KNN (or a single Lucene query with a filter) would be far more efficient.
- **`EmbeddingStoreSupplier` lifecycle:** The `createdStore` field is a single reference. If `getStore()` is called for project A, then project B, the first store is orphaned (never closed) until `destroy()` is called at shutdown. In a hub scenario with many projects, this leaks `IndexWriter` resources.
- **`countInputLines()` in `EmbeddingExportService`:** Reads the entire input file a second time just to count lines. For large files this doubles I/O. A streaming count or a metadata sidecar would avoid this.
- **Memory:** `SemanticGraphBatch.run()` loads *all* project vectors into memory (`store.readAllVectors()`), builds all edges, clusters, and then upserts all updates. For a project with millions of chunks, this will OOM. No pagination or streaming is applied.

## 4. Strengths & Best Practices

1. **Clear operational mode separation:** The `TransferMode` enum (EXPORT / IMPORT / STORE) with a `switch` in the `CommandLineRunner` makes the CLI contract explicit and easy to extend.
2. **Multi-project shared index with metadata filtering:** Using `PXCHUNK_PROJECT` as a metadata key and filtering on it avoids duplicating index files while maintaining logical isolation. The reset logic correctly scopes deletion to the active project.
3. **Placeholder vector for FULLTEXT chunks:** The decision to use an all-ones vector (documented with the rationale that a zero vector breaks Lucene's cosine KNN) is a well-reasoned, pragmatic solution that avoids API cost while preserving document presence.
4. **Embedding compatibility checker:** The `EmbeddingCompatibilityChecker` with its three-tier verdict (COMPATIBLE / PARTIALLY_COMPATIBLE / INCOMPATIBLE) and configurable thresholds is a production-grade safeguard against cross-platform vector drift.
5. **Mutual-neighbor edge construction:** Requiring that A is in B's top-K *and* B is in A's top-K (configurable via `requireMutual`) reduces spurious edges in the semantic graph.
6. **Code-code edge suppression:** The `isCodeCode` filter prevents noisy intra-code edges, showing domain-aware graph construction.
7. **`DisposableBean` for store cleanup:** `EmbeddingStoreSupplier` properly closes the Lucene store on context shutdown, preventing index lock leaks.
8. **Use of records for value objects:** `EdgeConfig`, `SemanticEdge`, `CheckReport`, `Neighbor` are all immutable records—clean, concise, and inherently thread-safe.
9. **Progress reporting via `BiConsumer<Integer, Integer>`:** The `exportForProject` overload allows the hub to track progress without coupling the export service to a specific UI or logging mechanism.
10. **Encryption-aware transfer:** The `TransferSession` / `TransferCrypto` / `TransferPasswordResolver` trio cleanly separates encryption concerns from the data pipeline.

## 5. Identified Risks & Technical Debt

| # | Risk / Debt | Severity | Location |
|---|---|---|---|
| 1 | **Global `synchronized` on singleton** serializes all store writes; blocks concurrent project processing in hub mode. | High | `LangChain4JEmbedderImpl.execute()` |
| 2 | **`instanceof LuceneEmbeddingStore`** in 3+ classes breaks the `EmbeddingStore` abstraction; non-Lucene stores will silently skip Lucene-specific features or throw. | High | `EmbeddingService`, `EmbeddingImportService`, `StoreIdChecker`, `SemanticGraphBatch` |
| 3 | **`EmbeddingStoreSupplier.getStore()` creates a new store per call** when no shared bean exists; multiple calls → multiple `IndexWriter` on same dir → `LockObtainFailedException`. | High | `EmbeddingStoreSupplier` |
| 4 | **Stub `MixedBredEmebeddingClient` returns `null`** and is a `@Component`; if selected (e.g., via `@Primary` removal), causes NPE. Typo in class name. | Medium | `MixedBredEmebeddingClient` |
| 5 | **O(n) KNN searches** in `SemanticEdgeBuilder` with no batching or parallelism; linear scaling with chunk count. | Medium | `SemanticEdgeBuilder.buildSemanticEdges()` |
| 6 | **Full in-memory load** of all project vectors in `SemanticGraphBatch.run()`; OOM risk for large projects. | Medium | `SemanticGraphBatch.run()` |
| 7 | **Inconsistent failure semantics:** CLI `execute()` swallows exceptions; `exportForProject()` rethrows. Hub and CLI paths behave differently on partial failure. | Medium | `EmbeddingExportService`, `EmbeddingImportService` |
| 8 | **Duplicated `firstNonBlank` utility** in two services. | Low | `EmbeddingExportService`, `EmbeddingImportService` |
| 9 | **Inconsistent logging framework** (JUL `@Log` vs. SLF4J). | Low | `EmbeddingCompatibilityChecker` vs. rest |
| 10 | **`EmbeddingExecutor.execute()` bundles compute + persist**, making it hard to reuse the "compute only" path for new providers. | Low | `EmbeddingExecutor` interface |
| 11 | **No visible unit/integration tests** in the provided codebase. | Medium | Entire module |
| 12 | **`countInputLines()` re-reads the entire file** for a line count, doubling I/O on large inputs. | Low | `EmbeddingExportService` |

## 6. Actionable Recommendations

### Priority 1 – Critical (Address Immediately)

1. **Remove the `synchronized` block in `LangChain4JEmbedderImpl`.**
   - Replace with a dedicated `StoreWriteCoordinator` (e.g., a `Semaphore` or a single-threaded `ExecutorService` for writes) that is injected into the embedder. This preserves write atomicity without serializing the entire JVM.
   - Alternatively, if Lucene's `IndexWriter` is already thread-safe for `addDocument`, remove the lock entirely and rely on Lucene's internal synchronization.

2. **Fix `EmbeddingStoreSupplier` to cache created stores per project name.**
   - Use a `ConcurrentHashMap<String, EmbeddingStore<TextSegment>>` keyed by project name. Close all cached stores in `destroy()`. This prevents multiple `IndexWriter` instances on the same directory.

3. **Introduce a `LuceneEmbeddingStore` capability interface** (e.g., `VectorStoreCapabilities` with `hasMatch(Filter)`, `readAllVectors(Filter)`, `upsertAll(List<VectorChunk>)`) to eliminate `instanceof` checks.
   - `StoreIdChecker`, `EmbeddingService`, `EmbeddingImportService`, and `SemanticGraphBatch` should depend on this capability interface (or a `StoreAdapter` that wraps the generic `EmbeddingStore` and exposes Lucene-specific operations conditionally).

### Priority 2 – High (Address in Next Sprint)

4. **Batch or parallelize KNN searches in `SemanticEdgeBuilder`.**
   - Use Lucene's `IndexSearcher` with a single query that retrieves top-K for multiple vectors, or parallelize with a `CompletableFuture` pool (bounded to CPU cores). For very large sets, consider a two-pass approach: first a coarse ANN (e.g., HNSW) to get candidates, then exact re-ranking.

5. **Stream the semantic graph computation.**
   - Instead of loading all vectors into memory, process in windows (e.g., 5 000 chunks at a time), building edges incrementally. The Leiden clustering step still requires the full edge list, but the KNN and edge-construction phases can be streamed.

6. **Unify failure semantics.**
   - Define a `PipelineResult` record (success, partial, failed; list of failed batch indices; error messages). Both `execute()` and `exportForProject()` should return this. The `CommandLineRunner` maps it to an appropriate exit code. The hub maps it to its orchestration logic.

7. **Remove or properly implement `MixedBredEmebeddingClient`.**
   - If it is a placeholder for future work, annotate with `@ConditionalOnProperty` so it is not instantiated by default. Fix the typo. If it is dead code, delete it.

### Priority 3 – Medium (Address in Backlog)

8. **Refactor `EmbeddingExecutor` into two interfaces:**
   - `EmbeddingModel` (compute: `List<PxChunk> → List<EmbeddedChunkRecord>`)
   - `EmbeddingPersister` (persist: `List<EmbeddedChunkRecord> → store.addAll(...)`)
   - `EmbeddingExecutor` becomes a composite that orchestrates both. This allows the export path to use only the model, and the store path to use both.

9. **Extract a `JsonlTransferWriter` / `JsonlTransferReader`** from `EmbeddingExportService` and `EmbeddingImportService` to eliminate the duplicated `firstNonBlank`, `fromJSONL`, and `toJSONL` logic and to centralize encryption/streaming concerns.

10. **Standardize on SLF4J** for all logging in the module. Replace `@Log` (JUL) annotations with `@Slf4j` (Lombok) or explicit `LoggerFactory` calls.

11. **Add a `StoreWriteLock` or `AsyncStoreWriter`** abstraction that encapsulates the concurrency strategy for store mutations, making it swappable (synchronous, queued, batched) without touching the embedder.

12. **Avoid the double-read in `countInputLines()`.**
    - Either maintain a line-count sidecar file written during chunking, or use a streaming count that is integrated into the main read loop (count as you go, report progress incrementally).

### Priority 4 – Long-Term / Structural

13. **Introduce a `TiBedPipeline` orchestrator** that composes the steps (read → deduplicate → embed → persist → semantic graph → sidecar write) as a configurable chain. This would make it trivial for the hub to skip or reorder steps per project, and would centralize the `PipelineResult` propagation.

14. **Add integration tests** that exercise the full pipeline (store / export / import) against a temporary Lucene index, including multi-project scenarios, encryption round-trips, and the semantic graph step. The current codebase has no visible test classes.

15. **Consider an event-driven or reactive variant** for the semantic graph: emit a `ChunksEmbedded` event after each batch, and have the `SemanticGraphBatch` subscribe and incrementally update the graph, avoiding the "load everything, compute, write everything" pattern.

16. **Document the hub/CLI dual-mode contract** in a dedicated architecture decision record (ADR). The `@ConditionalOnProperty("prjxp.hub.enabled")` and the `exportForProject` / `executeForProject` overloads imply a hub orchestration layer that is not visible in this module; an ADR would clarify the boundary and the expected invariants.

_This document was generated with .dp and gemini-3-flash-preview_

