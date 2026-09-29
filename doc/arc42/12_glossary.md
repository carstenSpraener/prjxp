# 12. Glossary

| Term | Definition |
|---|---|
| **PxChunk** | The central data unit of prjxp: a text fragment with logical identity (`id`), MIME type, file coordinates, hierarchy link (`parent`), split position (`part`/`total`), embedding prefix, project stamp and a metadata map. Produced by chunk-norris, stored in Lucene, consumed by retrieval. |
| **Chunk** / **Chunking** | Decomposition of a file into `PxChunk`s. In prjxp this is *structural* (AST-based for code) rather than a blind sliding window. |
| **Chunker** | A `PxChunker` implementation (or `@Chunker`-annotated method) that converts one file into a stream of chunks for a given `PxFileType`. |
| **Broker** (`ChunkerBroker`) | A registry of chunkers; discovered via Java `ServiceLoader` and classpath scanning. `ChunkerFactory` aggregates all brokers for file→chunker selection. |
| **Post-walk chunker** | A `@PostWalkChunker` method (no file argument) that runs after the whole tree walk — used for project-wide units such as the Java dependency graph. |
| **Veto** | A `@ChunkVeto` predicate over a file path that excludes the file from chunking (build artifacts, oversized files, hidden files, images, whitelist misses). |
| **Unit** | A logical chunking unit (e.g. one method, one class frame). All parts of a split unit share the same `id`; units link to their container via `parent`. |
| **Class frame** | The synthesized skeleton of a class: package + imports + javadoc + header + fields + method signature lines. Serves as the tree root for a class and as the "skeleton" view in `vectorSearch`. |
| **Forest of Trees** | Golden-Retriever's model for search results: hits are grouped into trees via `parent` links (one tree per logical root, e.g. class); several trees form a forest that is ranked, enriched and budgeted before reaching the LLM. |
| **Enrichment** | The deterministic expansion of raw search hits into coherent context (frame + method bodies + javadoc, deduplicated) performed by a prompt session/modifier. |
| **Veto system (retrieval)** | Validator predicates (`Function<String,Boolean>`) applied to enriched tree contexts that can reject noise before it reaches the LLM. |
| **Content budget** | Hard character caps on generated context: per-retriever (50k), global across retrievers (60k), reader output (100k). Skipped content is reported. |
| **Search ladder** | The staged retrieval strategy exposed via MCP: `vectorSearch` (semantic discovery) → `grep` (exact full-text) → `readFile` / `readBySignature` (complete source views). |
| **Skeleton mode** | `vectorSearch` behavior for *non-hit* methods: only the signature is shown (bodies are replaced by signatures) to keep context small. |
| **Embedding prefix** | Text (e.g. `package ClassName`) prepended to a chunk's content before embedding, to disambiguate identical methods in different classes. |
| **Symbol metadata** | Index fields written by the Java chunker (`symbol_fqn`, `symbol_name`, `symbol_type`, `symbol_container_fqn`, `symbol_signature_hash`) enabling deterministic lookup for `readBySignature`. |
| **File view** | A complete source rendering of one file reconstructed from its index chunks by a language-specific `FileViewProvider` (splicing frames, methods and docs at their line coordinates). A *view*, not a byte-identical file. |
| **JSONL** | JSON Lines — the pipeline transfer format: one `PxChunk` per line, streamable and pipe-able; optionally AES-256-GCM encrypted. |
| **Transfer (modes)** | Tibed's `STORE` / `EXPORT` / `IMPORT` modes; EXPORT/IMPORT move (optionally encrypted) records between machines for offline embedding. |
| **Project stamp** | The `pxchunk_project` metadata value set at embed time; the mechanism that separates multiple projects inside one shared Lucene index. |
| **Project registry** | The addressing facade of the mcp-server: `StaticProjectRegistry` (config-driven) or `HubProjectRegistry` (directory-driven, runtime registration, lifecycle tracking). |
| **Hub** | The multi-project deployment mode of the mcp-server: one container, shared project-stamped index, import watcher (tars + `prjxp.yaml` markers), in-process pipeline on a single FIFO worker, web UI. |
| **Marker file** (`prjxp.yaml`) | A per-project configuration/marker file; in the hub, its presence registers a *live* project for in-place embedding. |
| **Lifecycle status** | Hub project states: `importing → chunking → embedding → ready` (or `failed`). Only `ready` projects are searchable/listed. |
| **Scoped reset** | Deleting only one project's chunks from the shared index (`removeAll(pxchunk_project = X)`) before re-embedding. |
| **TEI** | `text-embeddings-router` — a CPU embedding server that *can* run inside the Docker image (currently disabled in favor of an external provider via `SKIP_EMBEDDING_SERVER`). |
| **MCP** | Model Context Protocol — the standard through which AI clients (opencode, Copilot) call prjxp's tools over streamable HTTP. |
| **RAG** | Retrieval-Augmented Generation — the overall pattern: retrieve project context, inject it into an LLM prompt. |
| **KIChat** | prjxp's chat abstraction over LangChain4j `ChatModel`s, selected by name or *stereotype* (e.g. `javadoc`), with optional MCP-client decoration. |
| **Stereotype** | A semantic role label for a configured chat model (e.g. `javadoc`) that docpipe jobs use to pick the right LLM. |
| **DocPipe** | The documentation pipeline: `.dp/documents.json` jobs → Handlebars prompt templates with resolvers (`gr`, `source-dump`, `groovy`, …) → LLM → output files. |
| **Documentation Paradox** | prjxp's design stance: zero manual Javadoc — the tool documents itself with its own retrieval pipeline. |
| **Coverage ratchet** | A per-module JaCoCo line-coverage minimum enforced on `check` that may only be raised, never lowered. |
| **cwd project** | The synthetic fallback project (name `cwd`, rootDir `.`, whitelist `java,ts`) created when no project is configured. |
