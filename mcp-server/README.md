# prjxp MCP Server

The **prjxp MCP Server** is a Spring Boot application that exposes project source code and documentation as a searchable, embeddable knowledge base. It implements the **Model Context Protocol (MCP)** for AI-assisted code exploration and provides a rich REST API for programmatic access.

The server indexes entire projects (Java, TypeScript, and other languages) into a Lucene-based embedding store, enabling semantic vector search, exact full-text search, deterministic symbol lookup, and complete file/symbol reading.

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                        MCP Server (Spring Boot)                     │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌──────────────┐   ┌──────────────────────────────────────────┐   │
│  │  MCP Layer   │   │           REST API Layer                  │   │
│  │  /mcp        │   │  /api/tools/*  /api/projects  /api/chat  │   │
│  │  (JSON-RPC)  │   │  /api/globals  /api/system               │   │
│  └──────┬───────┘   └──────────────────┬───────────────────────┘   │
│         │                              │                           │
│         ▼                              ▼                           │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │              PrjxpMcpTool / ToolRegistry                    │   │
│  │  vectorSearch · grep · readFile · readBySignature ·         │   │
│  │  listProjects · listFiles                                   │   │
│  └────────────────────────────┬────────────────────────────────┘   │
│                               │                                     │
│         ┌─────────────────────┼─────────────────────┐              │
│         ▼                     ▼                     ▼              │
│  ┌─────────────┐    ┌───────────────┐    ┌──────────────────┐     │
│  │ VectorSearch│    │  GrepSearch   │    │  ByIndexSearch   │     │
│  │  Service    │    │   Service     │    │    Service       │     │
│  └──────┬──────┘    └──────┬────────┘    └────────┬─────────┘     │
│         │                  │                      │                │
│         ▼                  ▼                      ▼                │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │              PxChunkDao (Lucene Embedding Store)            │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │         ProjectRegistry (Static | Hub)                      │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                     │
│  [Hub Mode: PipelineOrchestrator, ImportPoller, TarExtractor,      │
│   EmbeddedArchiveImporter, DocPipePipelineListener]                │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Configuration

The server is configured via `application.yaml` (or the path in `PRJXP_CONFIG_FILE`):

```yaml
prjxp:
  # --- Projects (static mode) ---
  active-project: my-project
  embedding-stores:
    - project-name: my-project
      default: true
      store-type: LUCENE
      store-path: /data/index/my-project

  # --- Hub mode (optional) ---
  hub:
    enabled: false              # Master switch for all hub beans
    import-dir: /import        # Directory watched for tar/embedded uploads
    projects-root: /projects   # Root for extracted snapshot projects
    live-jsonl-dir: /data/chunks
    poll-interval-ms: 5000
    max-tar-bytes: 2147483648
    max-extracted-bytes: 4294967296
    max-entries: 100000
    scan-exclude-dir-names: [.git, .svn, .hg, node_modules]
    scan-max-depth: 8
    compatibility:
      sample-size: 4
      threshold-high: 0.995
      threshold-low: 0.975

  # --- Embedding model ---
  embedding-model:
    server-type: ollama
    provider-url: http://localhost:11434
    model-name: nomic-embed-text
    timeout-secs: 60

  # --- Chat models ---
  chat-models:
    - stereo-type: my-llm
      server-type: openai
      provider-url: https://api.openai.com/v1
      model-name: gpt-4o
      api-key: ${OPENAI_API_KEY}
      temperature: 0.0
      timeout-secs: 120

# --- CORS ---
mcp:
  cors:
    allowed-patterns: http://localhost:*
```

### Project Configuration (`prjxp.yaml`)

Each project directory contains a `prjxp.yaml` (or `prjxp.yml`):

```yaml
name: my-project
rootDir: .
jsonlFile: px-chunks.jsonl
chunoWhiteList: java,ts
excludeDirNames: node_modules,.git,target
sourceDirNames: src
tibedBatchSize: 32
fulltext: false
# embeddingsFile: my-project-embedded.jsonl   # for pre-embedded imports
# prompt: "Always answer in German."           # standing instructions for chat
```

---

## MCP Tools

The server exposes **six tools** via the MCP protocol (`POST /mcp`) and as native function-calling tools. These are the primary interface for AI agents.

### Search Ladder (3 Levels)

| Level | Tool | Purpose |
|-------|------|---------|
| 1 | `vectorSearch` | Semantic discovery — broad overview |
| 2 | `grep` | Exact full-text match — locate specific strings |
| 3 | `readBySignature` / `readFile` | Complete source — full method or file |

**Orthogonal:** `listFiles` (structural discovery by path) and `listProjects` (project discovery).

---

### `vectorSearch`

**Level 1 — Semantic discovery.** Always call this first to find relevant code.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `userQuestion` | string | **Yes** | A targeted, standalone search prompt optimized for vector retrieval |
| `projectName` | string | No | Project name (defaults to active project) |
| `similarity` | number | No | 0.0–1.0 threshold. Default 0.85. Higher = more precise |
| `maxResults` | integer | No | Max results. Default 20, max 20 |
| `skeletonsOnly` | boolean | No | `true` = class skeletons only; `false` (default) = full method context on hits |

**Example (MCP JSON-RPC):**
```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "vectorSearch",
    "arguments": {
      "userQuestion": "How is the user authentication flow implemented?",
      "projectName": "my-project",
      "similarity": 0.85,
      "maxResults": 10
    }
  }
}
```

**REST equivalent:** `GET /api/tools/vectorSearch?query=...&project=...&language=...&limit=...`

---

### `grep`

**Level 2 — Exact full-text search.** Use the exact method signature or string to retrieve its full body.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `query` | string | **Yes** | Exact search string (e.g. `"public MClass createMClass"`) |
| `project` | string | No | Project name |
| `language` | string | No | Language filter (`java`, `ts`, etc.) |
| `limit` | integer | No | Max results. Default 10, max 100 |

**Example:**
```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "grep",
    "arguments": {
      "query": "public MClass createMClass",
      "project": "my-project"
    }
  }
}
```

**REST equivalent:** `GET /api/tools/grep?query=...&project=...&language=...&limit=...`

---

### `readBySignature`

**Level 3 — Complete symbol implementation.** Returns javadoc + full method body. Java only (C#, TypeScript, VB supported via index metadata).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `method` | string | **Yes** | Method name (`createMClass`) or FQN (`com.example.Foo#bar`). Class FQNs return the class frame |
| `container` | string | No | Containing class FQN to disambiguate a bare method name |
| `project` | string | No | Project name |

**Result:** Up to 10 full matches (FQN, file, line range, javadoc, body). Additional matches listed in `remainingFqns` — call again with one of them.

**REST equivalent:** `GET /api/tools/byIndex?language=java&fqn=...&methodName=...&signatureHash=...&containerFqn=...`

---

### `readFile`

**Level 3 — Complete file view.** Reconstructs the entire source file from the index.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | **Yes** | File path — absolute, project-root-relative, or just the file name |
| `project` | string | No | Project name |
| `offset` | integer | No | 1-based line number to start from (paged reading) |
| `limit` | integer | No | Max lines to return from `offset` |

**Path resolution:** exact → suffix → basename. Ambiguous paths return a `candidates` list.

**REST equivalent:** No direct REST endpoint (MCP tool only).

---

### `listFiles`

**Structural discovery.** Lists file paths in the project index matching a prefix or glob.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `path` | string | No | Path prefix (`frontend/src`) or glob (`*Controller.java`). `*` spans `/`. Empty = all |
| `project` | string | No | Project name |
| `limit` | integer | No | Max files. Default 50, max 500 |

**REST equivalent:** `GET /api/tools/files?path=...&project=...&limit=...`

---

### `listProjects`

**Discovery.** Lists all searchable project names.

No parameters.

**REST equivalent:** `GET /api/tools/projects`

---

## REST API

### Tool Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/tools/ping` | Health check — returns `"pong!"` |
| `GET` | `/api/tools/context` | Semantic context (vector search with enrichment) |
| `GET` | `/api/tools/vectorSearch` | Vector search |
| `GET` | `/api/tools/grep` | Full-text search |
| `GET` | `/api/tools/byIndex` | Deterministic symbol search |
| `GET` | `/api/tools/files` | List files |
| `GET` | `/api/tools/projects` | List projects with lifecycle status |
| `GET` | `/api/tools/meta-search-params` | Machine-readable search parameter schema |

#### `/api/tools/context`

```
GET /api/tools/context?userQuestion=How+does+the+pipeline+work&project=my-project&similarity=0.85&maxResults=10&skeletonsOnly=false
```

Returns enriched text context (vector search + GR prompt enrichment).

#### `/api/tools/byIndex`

```
GET /api/tools/byIndex?language=java&fqn=com.example.Foo#bar&symbolType=method&limit=10
```

At least one of `fqn`, `methodName`, `signatureHash`, `containerFqn` is required.

#### `/api/tools/meta-search-params`

Returns the schema version, global parameters, and per-language capabilities (valid symbol types, parameter definitions). Call this once before using language-specific search parameters.

---

### Chat API

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/chat/send` | Send a chat message (JSON response) |
| `POST` | `/api/chat/send` (Accept: `text/event-stream`) | Send with SSE streaming |
| `POST` | `/api/chat/projects` | List available projects for chat |
| `POST` | `/api/chat/tools` | List available tools |

**Request body:**
```json
{
  "message": "How does the authentication flow work?",
  "chatModelName": "my-llm",
  "projectContext": "my-project",
  "useTools": true,
  "messages": [
    { "role": "user", "content": "Earlier question" },
    { "role": "assistant", "content": "Earlier answer" }
  ]
}
```

**Response:**
```json
{
  "id": "uuid",
  "role": "assistant",
  "content": "The authentication flow works as follows...",
  "ragContext": [
    { "projectName": "my-project", "chunkId": "abc123", "snippet": "...", "score": 0.92 }
  ],
  "toolCalls": [
    { "toolName": "vectorSearch", "arguments": {...}, "result": "..." }
  ]
}
```

The chat supports **native function calling** (when the model supports it) and falls back to a text-based `TOOL_CALL[tool](params)` protocol. An agentic loop of up to 3 tool iterations is executed before the final answer.

---

### Project Management (Hub Mode)

Available when `prjxp.hub.enabled=true`:

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/projects` | List all projects with lifecycle status |
| `POST` | `/api/projects` | Create a new project |
| `PUT` | `/api/projects/{name}` | Update project configuration |
| `DELETE` | `/api/projects/{name}` | Delete a project |
| `POST` | `/api/projects/{name}/reindex` | Trigger full reindex pipeline |
| `POST` | `/api/projects/{name}/docpipe` | Trigger DocPipe-only re-run |
| `GET` | `/api/projects/{name}/reindex-status` | Get reindex progress |
| `GET` | `/api/projects/{name}/stats` | Get project statistics |
| `POST` | `/api/projects/upload-archive` | Upload a TAR archive |
| `POST` | `/api/projects/upload-embedded` | Upload a pre-embedded archive |
| `GET` | `/api/projects/live-scan` | Scan for live project candidates |

**Project lifecycle:** `IMPORTING → CHUNKING → EMBEDDING → READY` (or `FAILED`)

**Reindex progress:** `IDLE → CHUNKING (0-10%) → EMBEDDING (10-80%) → IMPORTING (80-100%) → COMPLETE`

---

### System Monitoring (Hub Mode)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/system/health` | CPU, memory, disk, connections |
| `GET` | `/api/system/query-stats` | Query statistics (total, avg/p95 response time, cache hits/misses, recent queries) |

---

### Globals (Model Configuration)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/globals/chat-models` | List chat models (API keys masked) |
| `POST` | `/api/globals/chat-models` | Create a chat model |
| `PUT` | `/api/globals/chat-models/{name}` | Update a chat model |
| `DELETE` | `/api/globals/chat-models/{name}` | Delete a chat model |
| `GET` | `/api/globals/server-types` | List supported server types |
| `GET` | `/api/globals/embedding-model` | Get embedding model config |
| `PUT` | `/api/globals/embedding-model` | Update embedding model config |

---

## MCP Protocol Endpoint

The server implements the MCP protocol at `POST /mcp` (JSON-RPC 2.0).

**Compatibility:** The `McpCompatibilityFilter` handles:
- `server/discover` — returns supported protocol versions and capabilities
- `initialize` — strips unsupported `elicitation` capability for older clients

**Supported MCP methods:**
- `tools/list` — lists all registered tools
- `tools/call` — executes a tool by name with arguments

---

## Hub Mode: Project Import

When `prjxp.hub.enabled=true`, the server watches the import directory for:

### TAR Archives (Snapshot Projects)
- Files: `*.tar`, `*.tgz`, `*.tar.gz`
- Extracted into `projects-root/<name>/`
- Full pipeline: chunk → embed → swap
- Security: rejects absolute paths, `..` segments, symlinks; enforces size/entry limits

### Pre-Embedded Archives
- Files: `<Name>-embedded.jsonl`, `<Name>-embedded.gz`, `<Name>-embedded.tgz`, `<Name>-embedded.zip`
- Must contain exactly one `.jsonl` entry with valid `EmbeddedChunkRecord` objects
- Pipeline: compatibility check → swap (skips chunking/embedding)
- Requires a `.ready` marker file before import (race-condition protection)

### Live Projects
- Directories containing `prjxp.yaml` or `prjxp.yml` anywhere below the import dir
- Discovered recursively (bounded by `scan-max-depth`)
- Excluded via `.prjxp-exclude` marker file or `scan-exclude-dir-names` config
- JSONL output redirected to `live-jsonl-dir` (never into the live tree)

---

## Search Strategy Guide

For AI agents and developers, the recommended workflow:

```
1. listProjects          → Which projects are available?
2. vectorSearch          → "What is the authentication flow?" (broad discovery)
3. grep                  → "public void authenticate" (exact match, full body)
4. readBySignature       → "com.example.AuthService#authenticate" (complete method)
   OR readFile           → "src/main/java/com/example/AuthService.java" (whole file)
5. listFiles             → "*Controller.java" (structural exploration)
```

**Key rules:**
- Always start with `vectorSearch` for discovery
- Use `grep` with the **exact** method signature for full method bodies
- Use `readBySignature` for complete implementation by name
- Use `readFile` when you need the entire file
- `listFiles` is orthogonal — use it to explore the file tree
- Pass the `project` parameter when multiple projects exist

---

## Result Size Caps

To protect the model's context window, results are truncated:

| Tool | Cap |
|------|-----|
| `vectorSearch` / `grep` / `listFiles` | 8,000 characters |
| `readFile` / `readBySignature` | 10,000 characters |
| `readFile` content | 100,000 characters (configurable via `readerMaxOutputChars`) |

Truncated results include a `... [truncated, total N chars]` marker.

---

## Running the Server

```bash
# Static mode (default)
java -jar mcp-server.jar

# Hub mode
java -jar mcp-server.jar --prjxp.hub.enabled=true \
  --prjxp.hub.import-dir=/import \
  --prjxp.hub.projects-root=/projects

# With environment variables
PRJXP_CONFIG_FILE=/path/to/application.yaml java -jar mcp-server.jar
```

The server starts on port **8080** by default (configurable via `server.port`).

---

## CORS

Configured via `mcp.cors.allowed-patterns` (comma-separated). Defaults to `http://localhost:*`. Applies to `/api/**`, `/mcp`, and `/v3/api-docs/**`.

---

## Error Handling

- **Unknown project:** `400 Bad Request` with `{"error": "unknownProject", "detail": "No searchable store for project 'X'. Available projects: ..."}`
- **Missing required parameter:** `400 Bad Request` with a specific error code
- **Unknown language:** `400 Bad Request` listing valid languages
- **MCP tool errors:** Returned as text in the tool result (e.g. `"ERROR: No searchable store for project 'X'..."`)

---

## Swagger / OpenAPI

Interactive API documentation is available at `/v3/api-docs` (OpenAPI 3.0 JSON) and `/swagger-ui.html` (if the SpringDoc dependency is included).

_This document was generated with Doc|Pipe_

