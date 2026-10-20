# prjxp mcp-server

**prjxp mcp-server** is a Spring Boot application that indexes the source code and documentation of software projects and exposes them through the **Model Context Protocol (MCP)**, a **REST API**, and an **agentic chat interface**. It lets LLMs and other AI agents discover, search, and read project code with a structured 3-level search ladder.

---

## Table of Contents

- [Architecture](#architecture)
- [Quick Start](#quick-start)
- [Configuration](#configuration)
- [MCP Tools](#mcp-tools)
- [Search Strategy (3-Level Ladder)](#search-strategy-3-level-ladder)
- [REST API](#rest-api)
- [Hub Mode](#hub-mode)
- [Chat API](#chat-api)
- [Project Configuration (`prjxp.yaml`)](#project-configuration-prjxpyaml)
- [MCP Protocol](#mcp-protocol)
- [System Monitoring](#system-monitoring)
- [Globals & Model Management](#globals--model-management)
- [Troubleshooting](#troubleshooting)

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                        mcp-server (Spring Boot)                     │
│                                                                     │
│  ┌──────────────┐  ┌──────────────┐  ┌───────────────────────────┐ │
│  │  MCP Layer   │  │  REST Layer  │  │  Chat Layer (agentic)     │ │
│  │  /mcp        │  │  /api/**     │  │  /api/chat/**             │ │
│  │  JSON-RPC    │  │  JSON        │  │  SSE + JSON               │ │
│  └──────┬───────┘  └──────┬───────┘  └────────────┬──────────────┘ │
│         │                  │                       │                │
│  ┌──────▼──────────────────▼───────────────────────▼──────────────┐ │
│  │                    PrjxpMcpTool / ToolRegistry                  │ │
│  │  vectorSearch · grep · readFile · readBySignature ·            │ │
│  │  listProjects · listFiles                                      │ │
│  └──────────────────────────┬──────────────────────────────────────┘ │
│                             │                                       │
│  ┌──────────────────────────▼──────────────────────────────────────┐ │
│  │              Search & Reader Services                            │ │
│  │  VectorSearchService · GrepSearchService · ByIndexSearchService │ │
│  │  ReaderService · SymbolReaderService · FileListService          │ │
│  └──────────────────────────┬──────────────────────────────────────┘ │
│                             │                                       │
│  ┌──────────────────────────▼──────────────────────────────────────┐ │
│  │              PxChunkDao / LuceneEmbeddingStore                  │ │
│  │              (per-project or shared)                            │ │
│  └──────────────────────────┬──────────────────────────────────────┘ │
│                             │                                       │
│  ┌──────────────────────────▼──────────────────────────────────────┐ │
│  │              ProjectRegistry                                     │ │
│  │  StaticProjectRegistry (default)  |  HubProjectRegistry (hub)   │ │
│  └─────────────────────────────────────────────────────────────────┘ │
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────────┐ │
│  │  Hub (prjxp.hub.enabled=true)                                   │ │
│  │  ImportPoller · PipelineOrchestrator · TarExtractor ·           │ │
│  │  EmbeddedArchiveImporter · ProjectLifecycleService ·            │ │
│  │  DocPipePipelineListener                                        │ │
│  └─────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────┘
```

The server operates in two modes:

| Mode | Trigger | Project Registry | Use Case |
|------|---------|-----------------|----------|
| **Static** (default) | `prjxp.hub.enabled=false` or unset | `StaticProjectRegistry` | Pre-configured projects from `application.yaml` |
| **Hub** | `prjxp.hub.enabled=true` | `HubProjectRegistry` | Dynamic project import, lifecycle management, shared Lucene index |

---

## Quick Start

### 1. Configure `application.yaml`

```yaml
prjxp:
  # --- Static mode (default) ---
  active-project: my-project
  embedding-stores:
    - project-name: my-project
      default: true
      store-type: LUCENE
      store-path: /data/index/my-project

  # --- Hub mode (optional) ---
  hub:
    enabled: true
    import-dir: /import
    projects-root: /projects
    live-jsonl-dir: /data/chunks
    poll-interval-ms: 5000

  # --- Embedding model ---
  embedding-model:
    server-type: ollama
    provider-url: http://localhost:11434
    model-name: nomic-embed-text
    timeout-secs: 60

  # --- Chat models (for the chat API) ---
  chat-models:
    - stereo-type: gpt4
      server-type: openai
      model-name: gpt-4
      api-key: ${OPENAI_API_KEY}
      provider-url: https://api.openai.com/v1
```

### 2. Define a project (static mode)

Place a `prjxp.yaml` in your project root:

```yaml
name: my-project
rootDir: .
chunoWhiteList: java,ts
excludeDirNames: node_modules,.git,target
tibedBatchSize: 32
fulltext: true
```

### 3. Run

```bash
java -jar mcp-server.jar
```

The server starts on port **8080** by default. Verify with:

```bash
curl http://localhost:8080/api/tools/ping
# → "pong!"
```

---

## Configuration

### Core Properties

| Property | Default | Description |
|----------|---------|-------------|
| `prjxp.active-project` | `default` | Project name used when no `project` parameter is passed |
| `prjxp.hub.enabled` | `false` | Master switch for hub mode |
| `mcp.cors.allowed-patterns` | `http://localhost:*` | Comma-separated CORS origin patterns |

### Hub Properties (`prjxp.hub.*`)

| Property | Default | Description |
|----------|---------|-------------|
| `import-dir` | `/import` | Directory watched for tar/embedded uploads |
| `projects-root` | `/projects` | Root for extracted snapshot projects |
| `live-jsonl-dir` | `/data/chunks` | JSONL output for live projects (hub-managed) |
| `poll-interval-ms` | `5000` | Import poller interval |
| `max-tar-bytes` | `2 GiB` | Max tar file size |
| `max-extracted-bytes` | `4 GiB` | Max total extracted size (compression-bomb guard) |
| `max-entries` | `100000` | Max number of tar entries |
| `scan-exclude-dir-names` | `.git,.svn,.hg,node_modules` | Directory names the live scan never enters |
| `scan-max-depth` | `8` | Max recursion depth for live project discovery |
| `compatibility-sample-size` | `4` | Chunks sampled for embedding compatibility check |
| `compatibility-threshold-high` | `0.995` | Min avg cosine for COMPATIBLE verdict |
| `compatibility-threshold-low` | `0.975` | Min avg cosine for PARTIALLY_COMPATIBLE verdict |

### Search Limits

| Constant | Value | Applies To |
|----------|-------|------------|
| `DEFAULT_LIMIT` | 10 | grep, byIndex, vectorSearch |
| `MAX_LIMIT` | 100 | grep, byIndex, vectorSearch |
| `LIST_DEFAULT_LIMIT` | 50 | listFiles |
| `LIST_MAX_LIMIT` | 500 | listFiles |

---

## MCP Tools

The server exposes **six tools** via the MCP protocol (`/mcp` endpoint) and as in-process function-calling tools for the chat layer.

### 1. `vectorSearch` — Level 1 (Discovery)

Semantic vector search over all chunks of a project. **Always call this first** for broad discovery.

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `userQuestion` | string | **Yes** | — | A targeted, standalone search prompt optimized for vector retrieval |
| `projectName` | string | No | active project | Project name to narrow scope |
| `similarity` | number | No | `0.85` | Similarity threshold 0.0–1.0 (higher = more precise) |
| `maxResults` | integer | No | `20` | Maximum results (1–20) |
| `skeletonsOnly` | boolean | No | `false` | `true` = class skeletons only; `false` = full method context on hits |

**Similarity guide:**

| Range | Quality |
|-------|---------|
| 1.0 – 0.9 | Very precise |
| 0.9 – 0.8 | Precise |
| 0.8 – 0.7 | Balanced |
| < 0.7 | Low precision ("fantasy land") |

**Result:** Method implementations (when a method chunk is hit and `skeletonsOnly=false`) or class skeletons with imports for an architectural overview.

### 2. `grep` — Level 2 (Exact Text)

Exact full-text search over all chunks. Use the **exact method signature** as query to retrieve its full body.

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `query` | string | **Yes** | — | Exact search string, e.g. `public MClass createMClass` |
| `project` | string | No | active project | Project name |
| `language` | string | No | — | Language filter, e.g. `java`, `ts` |
| `limit` | integer | No | `10` | Max results (1–100) |

**Result:** `SearchHit` list with `file`, `lineFrom`/`lineTo`, `snippet`, `chunkId`, `score`, `source="grep"`.

### 3. `readFile` — Level 3 (Complete File)

Returns a **complete source view** of one file, reconstructed from the project index.

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `file` | string | **Yes** | — | File path (absolute, project-root-relative, or basename) |
| `project` | string | No | active project | Project name |
| `offset` | integer | No | `1` | 1-based line number to start from |
| `limit` | integer | No | all | Max lines to return from `offset` |

**Path resolution:** exact → suffix → basename. Ambiguous paths return a `candidates` list instead of content.

**Result:** `FileView` with `content`, `truncated` (100k char cap), `totalLines`, `returnedLines`, `chunkCount`, `candidates`, `error`.

### 4. `readBySignature` — Level 3 (Complete Symbol)

Returns the **complete implementation** of a symbol (javadoc + full method body). **Java only.**

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `method` | string | **Yes** | — | Method name (`createMClass`) or FQN (`com.example.Foo#bar`). Class FQNs return the class frame |
| `container` | string | No | — | Containing class FQN to disambiguate a bare method name |
| `project` | string | No | active project | Project name |

**Result:** `SymbolReadResult` with up to 10 full matches (`fqn`, `file`, `lineFrom`/`lineTo`, `javadoc`, `body`). Additional matches appear in `remainingFqns` — call again with one of them.

### 5. `listProjects` — Discovery

Lists all project names that have a searchable index. **Call before other tools** when you don't know valid project names.

No parameters. Returns a list of project name strings.

### 6. `listFiles` — Structural Discovery

Lists file paths in the project index matching a prefix or glob. Orthogonal to the search ladder.

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `path` | string | No | `""` (all) | Path prefix or glob. `*` matches any characters including `/` (recursive). `?` matches one character |
| `project` | string | No | active project | Project name |
| `limit` | integer | No | `50` | Max files (1–500) |

**Result:** `FileListResult` with sorted file paths and a `truncated` flag.

---

## Search Strategy (3-Level Ladder)

The recommended workflow for any code question:

```
┌─────────────────────────────────────────────────────────────────────┐
│  1. vectorSearch (discovery)                                        │
│     "Find the class that handles user authentication"               │
│     → Returns relevant classes/methods/skeletons                    │
│                                                                     │
│  2. grep (exact text)                                               │
│     "public MClass createMClass"                                    │
│     → Returns the exact chunk containing that signature             │
│                                                                     │
│  3. readBySignature / readFile (complete content)                   │
│     readBySignature("createMClass")  or  readFile("Foo.java")       │
│     → Returns the full method body or entire file                   │
└─────────────────────────────────────────────────────────────────────┘

Orthogonal:
  listProjects  →  "Which projects are available?"
  listFiles     →  "Which files match *Controller.java?"
```

**Key rules:**
- **Always start with `vectorSearch`** for semantic discovery.
- **Use `grep`** when you know the exact identifier or signature.
- **Use `readBySignature`** for a complete method by name; **`readFile`** for the whole file.
- **Use `listFiles`** to explore the file tree before reading.
- **Use `listProjects`** when you don't know valid project names.
- You can execute **multiple follow-up queries** with refined terms to dig deeper.

---

## REST API

All endpoints are under `/api/` and return JSON. CORS is configured via `mcp.cors.allowed-patterns`.

### Tools (`/api/tools`)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/tools/ping` | Health check → `"pong!"` |
| `GET` | `/api/tools/vectorSearch?query=...&project=...&language=...&limit=...` | Semantic vector search |
| `GET` | `/api/tools/grep?query=...&project=...&language=...&limit=...` | Full-text search |
| `GET` | `/api/tools/byIndex?language=...&fqn=...&methodName=...&signatureHash=...&containerFqn=...&symbolType=...&project=...&limit=...` | Deterministic symbol search over chunk index |
| `GET` | `/api/tools/files?path=...&project=...&limit=...` | List files in the index |
| `GET` | `/api/tools/meta-search-params` | Machine-readable search parameter schema |
| `GET` | `/api/tools/context?userQuestion=...&project=...&similarity=...&maxResults=...&skeletonsOnly=...` | Enriched RAG context (legacy) |
| `GET` | `/api/tools/projects` | All projects with lifecycle status |

#### `byIndex` Parameters

| Parameter | Required | Description |
|-----------|----------|-------------|
| `language` | **Yes** | e.g. `java` |
| `fqn` | At least one of the four below | Fully qualified name, e.g. `com.example.Foo#bar` |
| `methodName` | | Method name |
| `signatureHash` | | Signature hash for exact matching |
| `containerFqn` | | Containing class FQN |
| `symbolType` | No | e.g. `method`, `classFrame` |
| `project` | No | Defaults to active project |
| `limit` | No | Default 10, max 100 |

#### `meta-search-params` Response

```json
{
  "version": "1.0",
  "globalParams": [
    { "name": "project", "description": "Optional project name...", "required": false },
    { "name": "limit", "description": "Maximum number of results (default 10, max 100).", "required": false },
    { "name": "mode", "description": "Optional search mode...", "required": false }
  ],
  "languages": {
    "java": {
      "language": "java",
      "mimeTypes": ["text/x-java-code"],
      "symbolTypes": ["method", "classFrame", "import", ...],
      "params": [ ... ],
      "identifierRules": { "pattern": "[A-Za-z_$][A-Za-z0-9_$]*", "caseSensitive": true }
    }
  }
}
```

### Error Format

All tool endpoints return a consistent error object on failure:

```json
{
  "error": "missingQuery",
  "detail": "Parameter 'query' is required."
}
```

Common error codes: `missingQuery`, `missingLanguage`, `missingSearchParams`, `unknownLanguage`, `unknownProject`.

---

## Hub Mode

Enable with `prjxp.hub.enabled: true`. The hub adds dynamic project management, a shared Lucene index, and an import pipeline.

### Project Lifecycle

```
IMPORTING → CHUNKING → EMBEDDING → READY
    │           │           │
    │           │           └──→ FAILED
    │           └──────────────→ FAILED
    └──────────────────────────→ FAILED

Reindex:  READY → CHUNKING → EMBEDDING → READY
DocPipe:  READY → DOCPIPE → READY  (code index untouched)
```

### Projects API (`/api/projects`)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/projects` | List all projects with status, stats, and config |
| `POST` | `/api/projects` | Create a new project (body: `ProjectCreateRequest`) |
| `PUT` | `/api/projects/{name}` | Update project config (body: `ProjectUpdateRequest`) |
| `DELETE` | `/api/projects/{name}` | Delete project (scoped index wipe; SNAPSHOT dirs removed, LIVE trees preserved) |
| `POST` | `/api/projects/{name}/reindex` | Trigger full pipeline re-run (async) |
| `POST` | `/api/projects/{name}/docpipe` | Trigger DocPipe-only re-run (code index stays) |
| `GET` | `/api/projects/{name}/reindex-status` | Current reindex status + progress (0–100) |
| `GET` | `/api/projects/{name}/stats` | Chunk count, file count, languages |
| `POST` | `/api/projects/{name}/importEmbeddings` | Upload pre-embedded JSONL (multipart) |
| `POST` | `/api/projects/upload-archive` | Upload a tar/tgz/zip archive (multipart) |
| `POST` | `/api/projects/upload-embedded` | Upload a pre-embedded archive (multipart) |
| `GET` | `/api/projects/live-scan` | Scan import dir for live project candidates |

### `ProjectCreateRequest`

```json
{
  "name": "my-project",
  "kind": "LIVE",
  "rootDir": "/path/to/source",
  "chunoWhiteList": "java,ts",
  "tibedBatchSize": 32,
  "fulltext": true
}
```

### `ProjectUpdateRequest`

All fields optional; only non-null values are applied.

```json
{
  "rootDir": "/new/path",
  "chunoWhiteList": "java",
  "tibedBatchSize": 64,
  "prompt": "Always explain in German.",
  "fulltext": false
}
```

### `ProjectInfo` Response

```json
{
  "name": "my-project",
  "status": "READY",
  "kind": "LIVE",
  "lastError": null,
  "chunkCount": 1523,
  "embeddingCount": 1523,
  "fileCount": 87,
  "rootDir": "/projects/my-project",
  "jsonlFile": "px-chunks.jsonl",
  "chunoWhiteList": "java,ts",
  "tibedBatchSize": 32,
  "embeddingsFile": null,
  "tibedResetStore": false,
  "reindexStatus": "COMPLETE",
  "reindexProgress": 100,
  "prompt": null,
  "fulltext": true
}
```

### Import Pipeline

The `ImportPoller` watches the `import-dir` every `poll-interval-ms`:

1. **Tar files** (`*.tar`, `*.tgz`, `*.tar.gz`): Extracted into `projects-root/<name>/`, then the pipeline runs (chunk → embed → swap).
2. **Embedded files** (`*-embedded.jsonl|gz|tgz|zip`): Pre-embedded archives are validated (embedding compatibility check), extracted, and imported.
3. **Live projects**: Directories with a `prjxp.yaml`/`prjxp.yml` marker are discovered recursively (up to `scan-max-depth`), registered, and enqueued.

**Security:** Tar/zip extraction rejects absolute paths, `..` segments, symbolic links, and enforces size/entry limits. A single top-level directory is stripped automatically.

**Keep-old-index semantics:** During chunking and embedding, the old index stays in place (project remains searchable). Only the final swap (scoped `removeAll` + batched import) is briefly offline.

### Pre-Embedded Projects

A project with `embeddingsFile` set in its `prjxp.yaml` skips chunking and embedding. The pipeline:
1. Validates embedding compatibility (samples N vectors, computes avg cosine against the local model).
2. If **INCOMPATIBLE** (avg cosine < `threshold-low`): import is blocked, project stays READY (old index) or FAILED.
3. If **PARTIALLY_COMPATIBLE** (between thresholds): import proceeds with a warning.
4. If **COMPATIBLE** (≥ `threshold-high`): import proceeds.

Pre-embedded projects require a `.ready` marker file before being enqueued (prevents race conditions during file transfers).

---

## Chat API

The chat layer provides an agentic interface with RAG enrichment and tool calling.

### Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/chat/send` | Send a message, get a response (JSON) |
| `POST` | `/api/chat/send` (with `Accept: text/event-stream`) | SSE streaming with live phase feedback |
| `POST` | `/api/chat/projects` | List available project names |
| `POST` | `/api/chat/tools` | List available tool names |

### `ChatRequest`

```json
{
  "message": "How does the authentication flow work?",
  "chatModelName": "gpt4",
  "projectContext": "my-project",
  "useTools": true,
  "messages": [
    { "role": "user", "content": "What classes handle auth?" },
    { "role": "assistant", "content": "The main classes are..." }
  ]
}
```

| Field | Required | Description |
|-------|----------|-------------|
| `message` | **Yes** | The user's question |
| `chatModelName` | **Yes** | Stereo type of the LLM (must match a configured `chat-models` entry) |
| `projectContext` | No | Project name for RAG enrichment |
| `useTools` | No | `true` enables the agentic tool loop (default `false`) |
| `messages` | No | Conversation history for multi-turn context |

### `ChatResponse`

```json
{
  "id": "uuid",
  "role": "assistant",
  "content": "The authentication flow starts in AuthController...",
  "ragContext": [
    { "projectName": "my-project", "chunkId": "abc123", "snippet": "...", "score": 0.92 }
  ],
  "toolCalls": [
    { "toolName": "vectorSearch", "arguments": { "userQuestion": "..." }, "result": "..." }
  ]
}
```

### SSE Streaming Events

When using `Accept: text/event-stream`, the server emits:

| Event Name | Data | Description |
|------------|------|-------------|
| `phase` | `{ "phase": "rag_search", "message": "..." }` | RAG search started |
| `phase` | `{ "phase": "llm_generating", "message": "..." }` | LLM generating |
| `phase` | `{ "phase": "tools", "message": "...", "toolCalls": [...] }` | Tool calls requested |
| `phase` | `{ "phase": "tool_result", "tool": "...", "result": "..." }` | Individual tool result |
| `complete` | Full `ChatResponse` JSON | Final answer |
| `error` | `{ "message": "..." }` | Error |

### Tool Calling

When `useTools=true`, the chat service runs an **agentic loop** (max 3 iterations):

1. **Native function calling** (preferred): Tool schemas are passed to the LLM provider; the model returns structured `ToolExecutionRequest` objects.
2. **Text protocol fallback** (`TOOL_CALL[tool](params)`): Used when the model doesn't support native function calling.
3. **XML recovery**: Some models (e.g. Qwen) emit XML-style calls; these are detected and recovered.

The system prompt includes the 3-level search strategy and the list of available projects.

---

## Project Configuration (`prjxp.yaml`)

Each project directory contains a `prjxp.yaml` (or `prjxp.yml`) file:

```yaml
name: my-project
rootDir: .
jsonlFile: px-chunks.jsonl
chunoWhiteList: java,ts
excludeDirNames: node_modules,.git,target
sourceDirNames: src
tibedBatchSize: 32
fulltext: true
# embeddingsFile: my-project-embedded.jsonl   # for pre-embedded projects
# tibedResetStore: true                        # reset the store before embedding
# prompt: |
#   Always explain code in German.
#   Focus on the business logic, not boilerplate.
```

| Key | Default | Description |
|-----|---------|-------------|
| `name` | directory name | Project name (used in all API calls) |
| `rootDir` | `.` | Source root (relative to project dir or absolute) |
| `jsonlFile` | `px-chunks.jsonl` | Chunk JSONL output filename |
| `chunoWhiteList` | `java,ts` | Comma-separated languages to index |
| `excludeDirNames` | built-in defaults | Directory names to skip during chunking |
| `sourceDirNames` | built-in defaults | Source directories to include |
| `tibedBatchSize` | `32` | Embedding batch size |
| `fulltext` | `false` | Store a lossless fulltext copy (for byte-identical `readFile`) |
| `embeddingsFile` | — | Pre-embedded JSONL filename (skips chunking + embedding) |
| `tibedResetStore` | `false` | Reset the embedding store before a new run |
| `prompt` | — | Standing instructions appended to the chat system prompt |

### Marker Files

| File | Purpose |
|------|---------|
| `prjxp.yaml` / `prjxp.yml` | Project marker — the hub's live scan registers directories containing this |
| `.prjxp-exclude` | Exclusion marker — the hub's live scan never enters this directory |
| `.ready` | Readiness marker — required for pre-embedded projects before import |

---

## MCP Protocol

The server implements the **Model Context Protocol** (JSON-RPC 2.0) at the `/mcp` endpoint.

### Protocol Version

`2025-06-18`

### Capabilities

The `McpCompatibilityFilter` auto-detects which capabilities are available based on registered beans:

| Capability | Condition |
|------------|-----------|
| `tools` | At least one `@McpTool`-annotated method exists |
| `prompts` | At least one `@McpPrompt`-annotated method exists |
| `resources` | At least one `@McpResource`-annotated method exists |
| `completions` | At least one `@McpComplete`-annotated method exists |

### `server/discover`

A non-standard method handled by the compatibility filter. Returns:

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "result": {
    "supportedVersions": ["2025-06-18"],
    "capabilities": {
      "tools": { "listChanged": false },
      "prompts": { "listChanged": false },
      "resources": { "listChanged": false, "subscribe": false }
    }
  }
}
```

### `initialize`

The filter strips the `elicitation` capability from the client's `initialize` params before forwarding to the MCP handler (compatibility with clients that send it).

### Tool Registration

The six prjxp tools are registered in the central `ToolRegistry` via `PrjxpToolRegistration` (highest precedence — first registration of a name wins, so external MCP or Groovy tools cannot shadow them). They are executed **in-process** as plain Java calls on `PrjxpMcpTool`, without a JSON-RPC round trip.

---

## System Monitoring

Available in hub mode (`prjxp.hub.enabled=true`).

### `GET /api/system/health`

```json
{
  "cpuUsage": 0.5,
  "totalMemory": 4294967296,
  "usedMemory": 1073741824,
  "freeDiskSpace": 53687091200,
  "totalDiskSpace": 107374182400,
  "activeConnections": 0
}
```

### `GET /api/system/query-stats`

```json
{
  "totalQueries": 152,
  "avgResponseTimeMs": 45.2,
  "p95ResponseTimeMs": 120.0,
  "cacheHits": 89,
  "cacheMisses": 63,
  "recentQueries": [
    { "query": "auth flow", "responseTimeMs": 32, "resultCount": 5, "fromCache": true }
  ]
}
```

---

## Globals & Model Management

### Chat Models (`/api/globals/chat-models`)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/globals/chat-models` | List all configured chat models (API keys masked) |
| `POST` | `/api/globals/chat-models` | Create a new chat model |
| `PUT` | `/api/globals/chat-models/{name}` | Update by stereo type |
| `DELETE` | `/api/globals/chat-models/{name}` | Delete by stereo type |
| `GET` | `/api/globals/server-types` | List supported server types |

### Embedding Model (`/api/globals/embedding-model`)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/globals/embedding-model` | Get current embedding model config (API key masked) |
| `PUT` | `/api/globals/embedding-model` | Update embedding model config |

Secrets (API keys, sensitive args) are masked as `********` in GET responses. Environment variable placeholders (e.g. `${OPENAI_API_KEY}`) are returned as-is.

---

## Troubleshooting

### "No searchable store for project 'X'. Available projects: ..."

The project name doesn't match any registered project. Call `listProjects` (MCP) or `GET /api/tools/projects` (REST) to see valid names. In hub mode, the project may still be in `IMPORTING`/`CHUNKING`/`EMBEDDING` state — wait for `READY`.

### `readFile` returns `candidates` instead of content

The file path is ambiguous (multiple files match). Use one of the listed candidate paths in a follow-up call.

### `readBySignature` returns `remainingFqns`

More than 10 overloads/matches were found. Call again with one of the FQNs from `remainingFqns` to fetch it in full.

### Hub: "tar entry with '..' path segment" / "tar entry escapes target directory"

The uploaded archive contains a path traversal attempt. The file is quarantined as `<name>.tar.failed`. Re-upload a clean archive.

### Hub: "Embedding model incompatible"

The pre-embedded vectors were generated with a different embedding model or incompatible hardware. Re-embed using the local model, or adjust `prjxp.hub.compatibility.*` thresholds.

### Hub: "Hub import dir /import does not exist (yet)"

The import directory volume is not mounted. The live project scan is skipped until the directory appears. This warning is logged once per outage.

### Chat: "Chat model 'X' not found"

The `chatModelName` in the request doesn't match any configured `stereo-type` in `prjxp.chat-models`. Check `GET /api/globals/chat-models`.

### CORS errors in the browser

Adjust `mcp.cors.allowed-patterns` in `application.yaml` to include your frontend origin, e.g. `http://localhost:3000,http://localhost:*`.

_This document was generated with Doc|Pipe_

