# 8. Cross-cutting Concepts

## 8.1 The PxChunk Data Model (and its metadata keys)

`PxChunk` is the single data contract of the whole system — produced by chunk-norris,
serialized to JSONL, stored in Lucene, and consumed by golden-retriever/mcp-server.
(Structure: see [05](05_building_block_view.md) § 5.3.1.)

**System metadata keys** (flat `pxchunk_*` fields, mapped by `PxChunk.metadataAsMap` /
`fromContentAndMap`):

| Key | Meaning |
|---|---|
| `pxchunk_id` | logical identity (shared by split parts) |
| `pxchunk_mimeType` | content type (`text/x-java-code`, …) |
| `pxchunk_file` | rootDir-relative path with leading slash |
| `pxchunk_parent` | id of the containing unit (tree edge) |
| `pxchunk_part` / `pxchunk_total` | split position / part count |
| `pxchunk_fromLine` / `pxchunk_toLine` | 0-based line coordinates |
| `pxchunk_size` / `pxchunk_overlap` | splitter parameters used |
| `pxchunk_embedding_prefix` | text prepended to the embedded content (disambiguation) |
| `pxchunk_project` | owning project name — **the multi-project separator** |

**Typed unit-role metadata** (written by chunkers, read by retrievers/readers):
`java_code_section` (`imports|method|methodDoc|classFrame|…`), `typescript_code_section`,
`visualbasic_code_section`, and the symbol keys from `SymbolMetadata`:
`symbol_fqn`, `symbol_name`, `symbol_type`, `symbol_container_fqn`,
`symbol_signature_hash` (SHA-256 of the declaration — overload-safe).

Any other metadata key is stored under `pxchunk_metadata.<key>` in the index.

## 8.2 JSONL as the Pipeline Transfer Format

- One `PxChunk` per line; streamable (line-by-line read/write), pipe-able, diff-able.
- `PxChunkFromJsonLReader` reads it in **batches** (`tibedBatchSize`, per project).
- Optional **encryption at rest/transfer**: `TransferCrypto` (AES-256-GCM, key derived
  via PBKDF2WithHmacSHA256). Modes: `AUTO` (encrypt iff a password is resolvable from
  `PRJXP_TRANSFER_PASSWORD`), explicit on/off; an explicitly-encrypted run without a
  password generates one and prints it at start **and** end of the run.

## 8.3 Configuration Layering (three levels)

| Level | File / Source | Scope | Examples |
|---|---|---|---|
| 1. Application | `application.yaml` (Spring, prefix `prjxp`) + `.env` (dotenv-java) | whole process | embedding endpoint, Lucene path/dimension, chat models, MCP CORS, hub properties (`prjxp.hub.*`) |
| 2. Project | `prjxp.yaml` marker (or `projects[]` in application.yaml) | one project | `name`, `rootDir`, `jsonlFile`, `chunoWhiteList` (default `java,ts`), `tibedBatchSize` (32), `tibedResetStore` |
| 3. Component | `@Value` properties | one component | `prjxp.java.chunksize/overlap`, `prjxp.gldrtrvr.maxcontentlength/vector-window/totalcontentlength`, `chunknorris.veto.maxsize`, `prjxp.reader-max-output-chars`, `prjxp.docpipe.maxthreads` |

Fallback: with no project configured, a synthetic **`cwd`** project (rootDir `.`,
whitelist `java,ts`) is created — the "just works" default.

## 8.4 SPI & Annotation-based Extensibility

| Extension point | Mechanism | Example implementations |
|---|---|---|
| Chunker broker | Java `ServiceLoader` (`META-INF/services/de.spraener.chuno.ChunkerBroker`) + classpath scan for `@ChunkNorrisComponent` | `ChunkerFactory` (built-in), third-party JARs drop in |
| Chunker | `@Chunker(fileTypes = PxFileType.…)` on methods (or classes) returning `Stream<PxChunk>` | Java/TS/VB code chunkers, Markdown, Text, PDF chapter |
| Post-walk chunker | `@PostWalkChunker` (no-arg methods, run after the tree walk) | `JavaDependenciesChunker` (jgrapht project dependency graph) |
| File veto | `@ChunkVeto` on `boolean(Path)` methods, collected by a `BeanPostProcessor` | build artifacts, >1 MB files, hidden files, images, whitelist misses |
| File view reader | `FileViewProvider` beans (mime → renderer), registry in mcp-server | Java/TS/VB/Markdown providers + `GenericFileViewProvider` fallback |
| Chat model supplier | `ChatModelSupplier` beans (`canProvide`/`provide`) | Ollama, OpenAI-compatible, Gemini, LM Studio, Azure |
| Retriever | `GoldenRetriever` beans (auto-collected list) | per-language retrievers |

**Rule of thumb:** adding a new language = one chunker + one ranker/prompt session +
one file-view provider (+ capability registration) — no core changes.

## 8.5 Multi-Project Separation (one shared index)

- Every chunk is **stamped with its project name at embed time** (`pxchunk_project`).
- `LucenePxChunkDao` scopes *every* query (vector, full-text, index, find-by-id) with
  `pxchunk_project = <project>`; resets are scoped the same way.
- `PxChunkDaoProvider` holds one DAO per project (runtime register/unregister in hub).
- `ProjectRegistry` is the addressing facade: **static** (config-driven store references,
  unknown names fail explicitly with the available list) vs **hub** (directory-driven,
  lifecycle-aware; only `READY` projects are searchable/listed).

## 8.6 Content Budgets & Token Economy

Context handed to an LLM is capped at three levels (all configurable):

| Budget | Default | Where |
|---|---|---|
| Per-retriever output | `prjxp.gldrtrvr.maxcontentlength` = 50k chars | prompt session (trees that don't fit are skipped + counted) |
| Global across retrievers | `prjxp.gldrtrvr.totalcontentlength` = 60k chars | `GRPromptEnrichment` (first output always in; later ones only if they fit) |
| Reader output | `prjxp.reader-max-output-chars` = 100k chars | `ReaderService` (`FileView.truncated` flag) |

Skipped trees/retrievers are reported in the output (transparency), and vector search
reports its effective similarity threshold + fallback round count.

## 8.7 Error Handling & Exit Codes

- `PxLogService` collects structured log messages (`PxLogMessage`) with levels; CLI apps
  print a summary of `SEVERE` messages and exit non-zero (docpipe: `System.exit(1)`).
- Chunking is **fault-isolated per file**: parse/conversion errors log a warning and yield
  an empty stream — one broken file never aborts the run.
- Embedding failures are logged **per batch** — the pipeline continues with remaining batches.
- DevTools is excluded from fat JARs specifically to keep error exit codes honest in Docker.

## 8.8 Bean Wiring Discipline (multi-module app)

- `McpServer` uses `@ComponentScan("de.spraener.prjxp")` — all module beans are visible in
  the hub process; other apps scan narrower package sets.
- CLI `CommandLineRunner`s have **unique bean names** (`chunoRun`, `tibedRun`) and are
  gated by `prjxp.cli.enabled` (hub sets it to `false`) so that libraries on the classpath
  don't start their CLIs.
- `spring.main.allow-bean-definition-overriding=true` is set to tolerate the overlapping
  component scans.
