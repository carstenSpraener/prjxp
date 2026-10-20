# prjxp mcp-server

A Spring Boot application that exposes a **Model Context Protocol (MCP)** server for semantic and exact code search over indexed projects. It provides a 3-level search ladder, project lifecycle management (hub mode), an AI chat endpoint with RAG + agentic tool calling, and a REST API for all operations.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                        mcp-server (Spring Boot)                     │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌──────────────┐  ┌──────────────┐  ┌───────────────────────────┐ │
│  │  MCP Tools   │  │   REST API   │  │   Hub (optional)          │ │
│  │  (McpTool)   │  │  (/api/*)    │  │  (prjxp.hub.enabled=true) │ │
│  └──────┬───────┘  └──────┬───────┘  └────────────┬──────────────┘ │
│         │                  │                        │               │
│  ┌──────▼──────────────────▼────────────────────────▼──────────────┐│
│  │                    Core Services                                ││
│  │  VectorSearchService │ GrepSearchService │ ReaderService        ││
│  │  ByIndexSearchService│ SymbolReaderService│ ProjectRegistry     ││
│  └─────────────────────────────────────────────────────────────────┘│
│         │                  │                        │               │
│  ┌──────▼──────────────────▼────────────────────────▼──────────────┐│
│  │              PxChunkDao (Lucene / other)                        ││
│  │         Shared Embedding Store (LuceneEmbeddingStore)           ││
│  └─────────────────────────────────────────────────────────────────┘│
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────────┐│
│  │  ChatService (RAG + Agentic Tool Loop + SSE streaming)          ││
│  └─────────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────────┘
```

---

## Quick Start

### Prerequisites

- Java 17+
- Maven
- A running embedding model (e.g. Ollama)
- A Lucene-compatible embedding store (bundled)

### Configuration

The server is configured via `application.yaml` (or the `PRJXP_CONFIG_FILE` environment variable):

```yaml
prjxp:
  active-project: myProject
  embedding-stores:
    - project-name: myProject
      default: true
      store-type: LUCENE
      store-path: /data/lucene-myProject

  embedding-model:
    server-type: ollama
    provider-url: http://localhost:11434
    model-name: nomic-embed-text
    timeout-secs: 60

  chat-models:
    - stereo-type: main
      server-type: ollama
      provider-url: http://localhost:11434
      model-name: llama3
      timeout-secs: 120
      temperature: 0.1

  hub:
    enabled: false          # set true for hub mode
    import-dir: /import
    projects-root: /projects
    live-jsonl-dir: /data/chunks
    poll-interval-ms: 5000
    max-tar-bytes: 2147483648
    max-extracted-bytes: 4294967296
    max-entries: 100000
    scan-exclude-dir-names: .git,.svn,.hg,node_modules
    scan-max-depth: 8
    compatibility:
      sample-size: 4
      threshold-high: 0.995
      threshold-low: 0.975

mcp:
  cors:
    allowed-patterns: http://localhost:*
```

### Running

```bash
mvn spring-boot:run
# or
java -jar mcp-server.jar
```

The server starts on port **8080** by default.

---

## Search Strategy (3-Level Ladder)

The core design principle is a **progressive refinement** workflow:

| Level | Tool | Purpose | When to Use |
|-------|------|---------|-------------|
| 1 | `vectorSearch` | Semantic discovery | **Always first.** Broad overview, finding classes, understanding structure. |
| 2 | `grep` | Exact full-text match | Narrow down with exact strings, method signatures, identifiers. |
| 3 | `readFile` / `readBySignature` | Complete source | Read the entire file or a specific method's full body + javadoc. |

### Typical Workflow

```
1. vectorSearch("How does authentication work?")
   → Returns class skeletons + hit method bodies

2. grep("public MClass createMClass")
   → Returns the exact method body from the chunk

3. readBySignature("createMClass", container="com.example.Foo")
   → Returns javadoc + complete method implementation

4. readFile("src/main/java/com/example/Foo.java")
   → Returns the entire file
```

---

## MCP Tools

The server exposes five MCP tools (annotated with `@McpTool`):

### `vectorSearch`

Semantic vector search over all chunks of a project.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `userQuestion` | string | **Yes** | A targeted, standalone search prompt optimized for vector retrieval. |
| `projectName` | string | No | Project name to narrow scope. |
| `similarity` | double | No | 0.0–1.0. Default 0.85. Higher = more precise. |
| `maxResults` | int | No | 1–20. Default 20. |
| `skeletonsOnly` | boolean | No | `true` = class skeletons only; `false` (default) = full method context on hits. |

**Result:** Enriched text with method implementations (on hit) or class skeletons with imports.

### `grep`

Exact full-text search over all chunks.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `query` | string | **Yes** | Exact search string (e.g. `"public MClass createMClass"`). |
| `project` | string | No | Project name. |
| `language` | string | No | Filter by language (e.g. `"java"`, `"ts"`). |
| `limit` | int | No | Default 10, max 100. |

**Result:** List of `SearchHit` objects (file, lines, snippet, chunkId, score).

### `readFile`

Returns a complete source view of one file, reconstructed from the index.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | **Yes** | Path — absolute, project-root-relative, or just the file name. |
| `project` | string | No | Project name. |
| `offset` | int | No | 1-based line number to start from. |
| `limit` | int | No | Max lines to return from `offset`. |

**Result:** `FileView` with `content`, `truncated`, `totalLines`, `returnedLines`, `candidates` (on ambiguous paths), `error`.

### `readBySignature`

Returns the complete implementation of a symbol (javadoc + full body). **Java only.**

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `method` | string | **Yes** | Method name or FQN (`com.example.Foo#bar`). Class FQNs return the class frame. |
| `container` | string | No | Containing class FQN to disambiguate a bare method name. |
| `project` | string | No | Project name. |

**Result:** `SymbolReadResult` with up to 10 full matches + `remainingFqns` for overflow.

### `listProjects`

Lists all searchable project names. No parameters.

---

## REST API

### Search Tools

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/tools/vectorSearch` | GET | Semantic search. Params: `query`, `project`, `language`, `limit`. |
| `/api/tools/grep` | GET | Full-text search. Params: `query`, `project`, `language`, `limit`. |
| `/api/tools/byIndex` | GET | Deterministic symbol search. Params: `language` (required), `fqn`, `methodName`, `signatureHash`, `containerFqn`, `symbolType`, `project`, `limit`. |
| `/api/tools/meta-search-params` | GET | Machine-readable description of all valid search parameters per language. |
| `/api/tools/context` | GET | Legacy vector search with enrichment. Params: `userQuestion`, `project`, `similarity`, `maxResults`, `skeletonsOnly`. |
| `/api/tools/ping` | GET | Health check. Returns `"pong!"`. |

### Projects (Hub Mode)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/projects` | GET | List all projects with lifecycle status, stats, and config. |
| `/api/projects` | POST | Create a new project. Body: `ProjectCreateRequest`. |
| `/api/projects/{name}` | PUT | Update project config. Body: `ProjectUpdateRequest`. |
| `/api/projects/{name}` | DELETE | Delete project (scoped index wipe + dir removal for SNAPSHOT). |
| `/api/projects/{name}/reindex` | POST | Trigger full pipeline re-run (chunk → embed → swap). |
| `/api/projects/{name}/docpipe` | POST | DocPipe-only re-run (docs + doc embedding, code index untouched). |
| `/api/projects/{name}/reindex-status` | GET | Current reindex status + progress (0–100). |
| `/api/projects/{name}/stats` | GET | Chunk count, file count, languages. |
| `/api/projects/{name}/importEmbeddings` | POST | Upload pre-embedded JSONL (multipart `file`). |
| `/api/projects/upload-archive` | POST | Upload a TAR/ZIP for import (multipart `file`). |
| `/api/projects/upload-embedded` | POST | Upload a pre-embedded archive (multipart `file`). |
| `/api/projects/live-scan` | GET | Scan import dir for live project candidates. |

### Chat

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/chat/send` | POST | Send a chat message. Body: `ChatRequest`. |
| `/api/chat/send` (SSE) | POST | Streaming variant (`Accept: text/event-stream`). |
| `/api/chat/projects` | POST | List available project names. |
| `/api/chat/tools` | POST | List available tool names. |

**ChatRequest:**

```json
{
  "message": "How does the authentication flow work?",
  "chatModelName": "main",
  "projectContext": "myProject",
  "useTools": true,
  "messages": [
    { "role": "user", "content": "..." },
    { "role": "assistant", "content": "..." }
  ]
}
```

**ChatResponse:**

```json
{
  "id": "uuid",
  "role": "assistant",
  "content": "The authentication flow works as follows...",
  "ragContext": [
    { "projectName": "myProject", "chunkId": "...", "snippet": "...", "score": 0.92 }
  ],
  "toolCalls": [
    { "toolName": "vectorSearch", "arguments": {...}, "result": "..." }
  ]
}
```

### System (Hub Mode)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/system/health` | GET | CPU, memory, disk, active connections. |
| `/api/system/query-stats` | GET | Total queries, avg/p95 response time, cache hits/misses, recent queries. |

### Globals

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/globals/chat-models` | GET | List configured chat models (API keys masked). |
| `/api/globals/chat-models` | POST | Create a chat model. |
| `/api/globals/chat-models/{name}` | PUT | Update a chat model. |
| `/api/globals/chat-models/{name}` | DELETE | Delete a chat model. |
| `/api/globals/server-types` | GET | List supported server types. |
| `/api/globals/embedding-model` | GET | Get embedding model config (API key masked). |
| `/api/globals/embedding-model` | PUT | Update embedding model config. |

### MCP Protocol Endpoint

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/mcp` | POST | JSON-RPC 2.0 endpoint for MCP clients. Supports `initialize`, `tools/list`, `tools/call`, `server/discover`. |

---

## Hub Mode

Hub mode (`prjxp.hub.enabled: true`) adds dynamic project management with a shared Lucene index:

### Project Kinds

| Kind | Description |
|------|-------------|
| **SNAPSHOT** | Extracted from a TAR into the hub's projects root. The hub owns the tree. |
| **LIVE** | Registered in-place from the import dir (marker file `prjxp.yaml`/`prjxp.yml`). The user owns the tree. |

### Project Lifecycle

```
IMPORTING → CHUNKING → EMBEDDING → READY
                ↓           ↓
             FAILED      FAILED
```

- **IMPORTING:** Project registered, pipeline queued.
- **CHUNKING:** Source files being chunked (old index still in use if present).
- **EMBEDDING:** Embeddings being computed (old index still in use).
- **READY:** Searchable.
- **FAILED:** Pipeline error (old index preserved if failure was before the swap).

### Import Flows

1. **TAR upload** (drop into `import-dir` or via `/api/projects/upload-archive`):
   - Extracted into `projects-root/<name>/`
   - Full pipeline: chunk → embed → swap

2. **Pre-embedded archive** (`<Name>-embedded.(jsonl|gz|tgz|zip)`):
   - Validated (naming, size, single-entry, vector presence)
   - Compatibility check against local embedding model
   - Swap: scoped reset + batched import

3. **Live project discovery** (marker file in import dir):
   - Recursive scan (bounded by `scan-max-depth`, pruned by `scan-exclude-dir-names`)
   - `.prjxp-exclude` file excludes a directory
   - `.ready` marker required for pre-embedded live projects

### Pipeline (Single FIFO Worker)

```
┌─────────────────────────────────────────────────────────┐
│  Standard Pipeline                                       │
│                                                          │
│  1. Chunking (0→10%)  — old index untouched             │
│  2. Embedding (10→80%) — old index untouched            │
│  3. Swap (80→100%)    — scoped removeAll + import       │
│                                                          │
│  Pre-Embedded Pipeline                                   │
│                                                          │
│  1. Compatibility check (0→50%)                         │
│  2. Swap (50→100%)     — scoped removeAll + import      │
│                                                          │
│  DocPipe Chain (code index untouched)                    │
│                                                          │
│  1. DocPipe generation                                  │
│  2. Document embedding (doc-scoped reset only)          │
└─────────────────────────────────────────────────────────┘
```

### Project Config File (`prjxp.yaml`)

```yaml
name: myProject
rootDir: /path/to/source
jsonlFile: px-chunks.jsonl
chunoWhiteList: java,ts
tibedBatchSize: 32
fulltext: false
# embeddingsFile: myProject-embedded.jsonl   # for pre-embedded imports
# prompt: "Always answer in German."          # standing instructions for chat
```

---

## MCP Protocol Compatibility

The `McpCompatibilityFilter` intercepts `POST /mcp` requests:

- **`server/discover`**: Returns supported protocol versions (`2025-06-18`) and capabilities (tools, prompts, resources, completions) based on registered `@McpTool`/`@McpPrompt`/`@McpResource`/`@McpComplete` annotations.
- **`initialize`**: Strips the `elicitation` capability from client params for compatibility.
- All other messages pass through to the MCP endpoint.

---

## Chat: Agentic Tool Calling

The `ChatService` supports two tool-calling strategies:

1. **Native function calling** (preferred): Tool schemas are passed to the LLM provider via LangChain4j. The model returns structured `ToolExecutionRequest` objects.

2. **Text protocol fallback** (`TOOL_CALL[tool](params)`): Used when the model doesn't support native tools. The service parses `TOOL_CALL[...]` markers (with quote-aware parenthesis matching) and also recovers XML-style calls (`<function=...>`) that some models emit.

The agentic loop runs up to **3 tool iterations**, feeding results back to the model before producing the final answer.

---

## CORS

Configured via `mcp.cors.allowed-patterns` (comma-separated). Defaults to `http://localhost:*`. Applies to `/api/**`, `/mcp`, and `/v3/api-docs/**`.

---

## Error Handling

- **Unknown project**: `400 Bad Request` with `{"error": "unknownProject", "detail": "No searchable store for project 'X'. Available projects: ..."}`.
- **Missing required parameter**: `400 Bad Request` with a specific error code.
- **Unknown language**: `400 Bad Request` listing valid languages.
- **File not found / ambiguous**: `FileView` with `error` or `candidates` fields.
- **Pipeline failure**: Project status → `FAILED` with `lastError`; old index preserved if failure was before the swap.

---

## Result Size Caps

To protect the LLM context window:

| Tool | Cap |
|------|-----|
| `vectorSearch` | 8,000 characters |
| `grep` | 8,000 characters |
| `readFile` | 10,000 characters |
| `readBySignature` | 10,000 characters |
| `readFile` (content) | 100,000 characters (configurable via `prjxp.reader-max-output-chars`) |

Truncated results end with `... [truncated, total N chars]`.

---

## Docker / Deployment Notes

- The hub expects mounted volumes for `import-dir`, `projects-root`, and `live-jsonl-dir`.
- The shared Lucene store path must be persistent (survives container restarts).
- The import poller runs every `poll-interval-ms` (default 5 s). A missing import dir is tolerated (volume not yet mounted).
- For Windows Docker bind mounts, increase `scan-max-depth` if your `prjxp.yaml` sits deeper than 8 levels.

---

## OpenAPI / Swagger

Interactive API docs are available at `/v3/api-docs` and (if SpringDoc is on the classpath) at `/swagger-ui.html`. All endpoints are annotated with `@Operation` and `@Parameter` for self-documenting schemas.

_This document was generated with Doc|Pipe_

