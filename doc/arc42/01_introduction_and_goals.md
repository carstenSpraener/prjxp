# 1. Introduction and Goals

## 1.1 Task Description (Purpose)

**prjxp** is an AI toolset that turns a software project into a queryable *project expert*
for LLM-based coding assistants. It answers the question:

> "How can an AI assistant (opencode, GitHub Copilot, …) get **precise, structurally
> correct** knowledge about *my* code base — without me pasting files into a chat?"

prjxp solves this with a pipeline of five cooperating modules:

| Module | Role in the task |
|---|---|
| **chunk-norris** | Decomposes source code and documents into structured `PxChunk`s (AST-based for Java/TS/VB, conversion agents for PDF/DOCX/HTML/RTF/TXT) and writes them as **JSONL**. |
| **lucene-store** | Self-contained, file-based vector + full-text index (Lucene 9.12) implementing the LangChain4j `EmbeddingStore` and the prjxp `PxChunkDao`. |
| **tibed** | Batch embedding engine: reads the JSONL, calls an external embedding model (OpenAI-compatible or Ollama), writes vectors + metadata into the Lucene index. Supports incremental re-embedding and an encrypted export/import mode for offline embedding on another machine. |
| **golden-retriever** | The RAG engine: turns raw search hits into high-quality LLM context via structural reconstruction ("Forest of Trees"), ranking, veto/validator filters and content budgets. Also provides per-language *file view* readers that reconstruct complete source files from the index. |
| **mcp-server** | Exposes the knowledge: MCP tools (`vectorSearch`, `grep`, `readFile`, `readBySignature`, `listProjects`) over streamable HTTP, a REST API, and — in **hub mode** — a multi-project runtime with import watcher, lifecycle management and web UI. |
| **docpipe** *(optional)* | Generates project documentation (e.g. Javadoc-style Markdown) by combining Handlebars prompt templates, prjxp's own retrieval and an LLM. |
| **prjxp-common** | Shared kernel: the `PxChunk` data model, configuration binding (`PrjXPConfig`), chat abstraction (`KIChat` + provider suppliers), store abstractions, transfer crypto, annotations and utilities used by all other modules. |

The system closes a loop that is unique to prjxp: **prjxp documents itself.** The project
ships with zero manual Javadoc by design — its own chunker, retriever and docpipe generate
its technical documentation ("the Documentation Paradox").

### Disclaimer (from the README)

$$\{ \langle M \rangle \mid \mathcal{P}(L(M)) = \text{true} \}\ \text{is undecidable.}$$

No retrieval system can guarantee that the context it builds is *the* right one; prjxp
mitigates this with precision-oriented retrieval (thresholds, vetoes, budgets) instead of
claiming completeness.

## 1.2 Quality Goals

| # | Quality Goal | Motivation (from code) |
|---|---|---|
| QG-1 | **Retrieval precision** — the context handed to an LLM must be relevant and structurally coherent | Forest-of-trees reconstruction, veto system, similarity thresholds with fallback iteration, global content budgets (`prjxp.gldrtrvr.*`) |
| QG-2 | **Self-contained deployment** — no external database service required | Lucene as the *only* store (ChromaDB/MySQL removed); single Docker image; index is a directory on disk |
| QG-3 | **Extensibility** — new languages/formats without core changes | Java SPI (`ServiceLoader`) + annotation-based discovery for chunkers, vetoes, file-view providers, chat suppliers |
| QG-4 | **Performance** — indexing and re-indexing must scale to large projects | Parallel file processing, batched embedding (`tibedBatchSize`), incremental embed (skip already-embedded chunks), hub scan pruning |
| QG-5 | **Security** — project code may be sensitive | Optional AES-256-GCM encryption of the JSONL transfer; tar-import limits (size/entry count, zip-slip rejection); CORS allow-list |
| QG-6 | **Maintainability** — the code base must stay testable and navigable (also for AI agents) | Strict module layering, version catalog only, per-module JaCoCo coverage ratchets (never lowered), zero manual Javadoc + generated docs |

## 1.3 Stakeholders

| Role | Contact | Expectation |
|---|---|---|
| **Developer using an MCP client** (opencode, GitHub Copilot in VS Code/JetBrains) | — | Ask questions about the project and get precise, well-structured answers; discover what is searchable (`listProjects`); read complete files/methods on demand. |
| **Project owner** (indexes their own code base) | — | One-command setup (`./prjxp <path> mcp`); re-index after code changes; no database administration. |
| **Hub operator** (serves many projects from one container) | — | Drop tar archives or marker files; see lifecycle status in the web UI (`importing → chunking → embedding → ready`); delete/reindex projects via REST. |
| **Contributor / maintainer** of prjxp itself | — | Clear module boundaries, TDD-friendly design (phase documents in `doc/Tasks/`), coverage ratchets that only move up, self-documenting code base. |
| **AI agent working on prjxp** (dogfooding) | — | The project must be indexable by its own tooling; documentation is generated, not hand-written. |
