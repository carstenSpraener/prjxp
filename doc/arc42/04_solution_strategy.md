# 4. Solution Strategy

## 4.1 Key Forces

| # | Force | Consequence for the design |
|---|---|---|
| F1 | **LLM context windows are limited and token cost matters.** A "dump everything relevant" strategy produces mega-contexts that dilute answers. | Context must be *as small as possible without losing precision*: structural reconstruction, ranking, vetoes and hard content budgets everywhere (see [08](08_cross_cutting_concepts.md) § Content Budgets). |
| F2 | **Code is structured** (packages, classes, methods, javadoc). Naive sliding-window chunks destroy that structure and make answers incoherent. | AST-based *structural* chunking: units are imports, methods (+javadoc), class frames — with a **logical identity** (`id`), **hierarchy** (`parent`) and **split parts** (`part`/`total`) so any unit can be reassembled exactly. |
| F3 | **Deployment must be self-contained** — a project owner should not operate a database service for their code base. | Lucene as the only store: file-based index, single Docker image, no external storage service. |
| F4 | **One server should serve many projects** (hub), but Lucene allows one writer per index. | One *shared* index with **project-stamped** chunks; all pipeline work serialized on a single FIFO worker. |
| F5 | **New languages/formats must be addable without touching the core** (Java, TS, VB, Markdown, PDF, … already coexist). | SPI + annotation-based discovery: `ServiceLoader` for brokers, `@Chunker`/`@ChunkNorrisComponent` for chunkers, `@ChunkVeto` for vetoes, `FileViewProvider` beans for readers. |
| F6 | **The index is built from a snapshot; the source may not exist at query time** (hub extracts tars, live trees move on). | The index is the **single source of truth** — even `readFile` reconstructs from stored chunks, never from disk. |
| F7 | **Embedding can be slow/expensive** (CPU TEI, shared GPU). | Batched + *incremental* embedding (skip chunks already in the store); optional encrypted export/import to embed on a different machine. |

## 4.2 Technical Strategy

### 4.2.1 A pipeline of decoupled stages joined by a stable file format

```
source files ──chunk-norris──▶ px-chunks.jsonl ──tibed──▶ Lucene index ◀──mcp-server/golden-retriever
                (structure)      (streamable, pipeable,        (vectors + metadata)   (retrieval)
                                  optionally encrypted)
```

Each stage is an independent executable with its own CLI; the JSONL file is the contract.
This yields: re-runnable stages, pipe-ability (`|`), offline transfer, and — in hub mode —
the same classes invoked *in-process* instead of as separate processes.

### 4.2.2 Structural identity and reconstruction

The `PxChunk` model (see [08](08_cross_cutting_concepts.md)) carries everything needed to
rebuild structure from flat index hits:

- `id` — logical identity; all parts of one split unit share it → `PxChunk.combine()`
  reassembles the original text (overlap-aware unsplitting).
- `parent` — pointer to the containing unit → enables tree building ("Forest of Trees").
- `fromLine`/`toLine`, `file` — coordinates for splicing units back into a file view.
- `metadata` (`java_code_section`, `symbol_*`, …) — typed unit roles for enrichment and
  deterministic symbol lookup.

### 4.2.3 A three-level search ladder (DynamicFocusedSearch)

To keep LLM context minimal, retrieval is *staged* and the MCP tool descriptions steer the
LLM through it:

```
vectorSearch (semantic discovery, skeletons by design)
      │  "I need the full body of X"
      ▼
grep (exact full-text; hit chunks include complete method bodies)
      │  "I need the whole file / all overloads"
      ▼
readFile (complete source view)  ·  readBySignature (javadoc + full method, Java)
```

All levels return a **unified hit format** (`SearchHit`: chunkId, score, file,
lineFrom/lineTo, snippet, source, metadata) so clients can treat them uniformly.

### 4.2.4 Context quality by construction, not by prompt

Golden-Retriever does not ask the LLM to "pick what matters". It deterministically:

1. groups hits into **trees** via `parent` links (a *forest* when several classes hit),
2. **ranks** trees (`ChunkRankingService` + per-language rankers, seeded by vector score),
3. **enriches** each tree (class frame + method bodies + javadoc, deduplicated),
4. applies **vetoes/validators** (reject noise trees) and a **content budget**
   (per-retriever + global), then concatenates the survivors.

### 4.2.5 One image, four modes; static vs hub registries

The same Docker image runs as `chunk`, `embed`, `serve` (single project) or `hub`
(multi-project). Inside the hub, chunk-norris and tibed are *beans in the mcp-server
process* (their CLI runners are switched off via `prjxp.cli.enabled=false`), and a
directory-driven project registry replaces the config-driven one.

### 4.2.6 Self-documentation (the Documentation Paradox)

> "Chunk Norris doesn't need documentation. Chunk Norris **is** the source of it!"

Zero manual Javadoc is a *design decision*: docpipe generates documentation from the
project's own index (retrieval + LLM), so docs can never drift from the code. prjxp
dogfoods this on itself (`.dp/documents.json`).
