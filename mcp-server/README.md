# prjxp mcp-server

**prjxp mcp-server** is a Spring Boot application that exposes a project's source code and documentation as a searchable, embeddable knowledge base. It provides both an **MCP (Model Context Protocol)** endpoint for AI clients and a **REST API** for programmatic access, with an optional **hub mode** for multi-project management.

---

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Installation & Configuration](#installation--configuration)
- [Running the Server](#running-the-server)
- [MCP Tools](#mcp-tools)
- [REST API](#rest-api)
- [Hub Mode](#hub-mode)
- [Project Configuration (`../prjxp.yaml`)](#project-configuration-prjxpyaml)
- [Search Strategy (3-Stage Ladder)](#search-strategy-3-stage-ladder)
- [Chat & RAG](#chat--rag)
- [CORS & Security](#cors--security)
- [Troubleshooting](#troubleshooting)

---

## Overview

The mcp-server indexes an entire project's source files into a Lucene-based embedding store, then exposes that index through:

| Interface | Purpose |
|-----------|---------|
| **MCP endpoint** (`/mcp`) | JSON-RPC 2.0 for AI clients (Claude, Cursor, etc.) |
| **REST API** (`/api/**`) | HTTP endpoints for tools, projects, chat, system health |
| **Chat API** (`/api/chat`) | LLM-powered Q&A with RAG enrichment and agentic tool calling |

The server operates in two modes:

- **CLI / Static mode** (default): A single project is indexed and served. No web server starts unless `prjxp.hub.enabled=true`.
- **Hub mode** (`prjxp.hub.enabled=true`): A multi-project hub with import polling, live updates, pipeline orchestration, and a full web UI backend.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                        mcp-server (Spring Boot)                     │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌──────────────┐   ┌──────────────────┐   ┌───────────────────┐  │
│  │  MCP Filter  │   │  PrjxpMcpTool   │   │  PrjxpToolReg.    │  │
│  │  (/mcp)      │   │  (MCP tools)    │   │  (native fn call) │  │
│  └──────┬───────┘   └────────┬─────────┘   └────────┬──────────┘  │
│         │                    │                       │             │
│         ▼                    ▼                       ▼             │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │              ToolRegistry (central, in-process)             │   │
│  └─────────────────────────────────────────────────────────────┘   │
│         │                    │                       │             │
│         ▼                    ▼                       ▼             │
│  ┌────────────┐     ┌─────────────┐        ┌─────────────────┐   │
│  │VectorSearch│     │GrepSearch   │        │Reader / Symbol  │   │
│  │Service     │     │Service      │        │Reader Service   │   │
│  └─────┬──────┘     └──────┬──────┘        └────────┬────────┘   │
│        │                   │                         │            │
│        ▼                   ▼                         ▼            │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │         PxChunkDaoProvider → LuceneEmbeddingStore           │   │
│  │         (shared, multi-project, scoped by project name)     │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  Hub (conditional on prjxp.hub.enabled=true)               │   │
│  │  ┌──────────────┐ ┌────────────────┐ ┌──────────────────┐  │   │
│  │  │ImportPoller  │ │PipelineOrch.   │ │LiveUpdateSched.  │  │   │
│  │  │(tar/embedded)│ │(chunk→embed→   │ │(adaptive 5-60m)  │  │   │
│  │  │              │ │ swap)          │ │                  │  │   │
│  │  └──────────────┘ └────────────────┘ └──────────────────┘  │   │
│  │  ┌──────────────────┐ ┌──────────────────────────────────┐  │   │
│  │  │HubProjectRegistry│ │DocPipePipelineListener           │  │   │
│  │  │(lifecycle, stats)│ │(doc generation + doc embedding)  │  │   │
│  │  └──────────────────┘ └──────────────────────────────────┘  │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                     │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  Chat (ChatService + ChatController)                        │   │
│  │  RAG enrichment + agentic tool loop (native / text proto)   │   │
│  └─────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────┘
```

---

## Installation & Configuration

### Prerequisites

- **Java 17+**
- **Maven** (or Gradle)
- An **embedding model** (e.g., Ollama with `nomic-embed-text`, or an OpenAI-compatible API)
- A **chat model** (optional, for the Chat API)

### Configuration

All configuration lives in `application.yaml` (or environment variables via `.env`):

```yaml
prjxp:
  # --- Core ---
  active-project: my-project          # default project name
  store-type: LUCENE                  # embedding store backend

  # --- Embedding model ---
  embedding-model:
    server-type: ollama              # ollama | openai | ...
    provider-url: http://localhost:11434
    model-name: nomic-embed-text
    # api-key: ${OPENAI_API_KEY}     # for cloud providers
    timeout-secs: 60

  # --- Chat models (optional, for /api/chat) ---
  chat-models:
    - stereo-type: my-llm
      server-type: ollama
      provider-url: http://localhost:11434
      model-name: llama3
      temperature: 0.7
      # prompt: "You are a helpful code assistant."

  # --- Hub mode (optional) ---
  hub:
    enabled: false                    # master switch for hub beans
    import-dir: /import               # watched for tar/embedded uploads
    projects-root: /projects          # extracted snapshot projects
    live-jsonl-dir: /data/chunks      # hub-managed JSONL for live projects
    poll-interval-ms: 5000
    live-update-enabled: true
    live-update-base-interval-ms: 300000   # 5 min
    live-update-max-interval-ms: 3600000   # 60 min
    stats-refresh-interval-ms: 60000
    max-tar-bytes: 2147483648        # 2 GiB
    max-extracted-bytes: 4294967296  # 4 GiB
    max-entries: 100000
    scan-exclude-dir-names: [.git, .svn, .hg, node_modules]
    scan-max-depth: 8
    compatibility:
      sample-size: 4
      threshold-high: 0.995
      threshold-low: 0.975

  # --- CORS ---
  cors:
    allowed-patterns: "http://localhost:*"
```

### Environment Variables

The server reads a `.env` file (via `PrjXPCli.readDotEnv`) at startup. Common variables:

| Variable | Purpose |
|----------|---------|
| `PRJXP_CONFIG_FILE` | Path to `application.yaml` (overrides default locations) |
| `OPENAI_API_KEY` | API key for OpenAI-compatible providers |
| `OLLAMA_URL` | Ollama server URL |

---

## Running the Server

### CLI / Static Mode (single project)

```bash
# Index and serve a single project (no web server by default)
java -jar mcp-server.jar --prjxp.active-project=my-project

# Or with a project config file in the project root:
cd /path/to/my-project
java -jar mcp-server.jar
```

In CLI mode the bundled CLI runners execute and the process exits. No embedded web server starts (enforced by `CliModeEnvironmentPostProcessor`).

### Hub Mode (multi-project, web server)

```bash
java -jar mcp-server.jar --prjxp.hub.enabled=true
```

This starts the embedded web server with all REST endpoints, the MCP endpoint, the import poller, the pipeline orchestrator, and the live-update scheduler.

### Docker

```bash
docker run -d \
  -p 8080:8080 \
  -v /path/to/import:/import \
  -v /path/to/projects:/projects \
  -v /path/to/data:/data \
  -e PRJXP_HUB_ENABLED=true \
  -e OLLAMA_URL=http://host.docker.internal:11434 \
  prjxp/mcp-server
```

---

## MCP Tools

The MCP endpoint (`POST /mcp`) exposes six tools via JSON-RPC 2.0. The `McpCompatibilityFilter` handles the `initialize` and `server/discover` handshake, stripping unsupported capabilities (e.g., `elicitation`) for broader client compatibility.

### Tool Summary

| Tool | Level | Purpose |
|------|-------|---------|
| `vectorSearch` | 1 (discovery) | Semantic search over project chunks |
| `grep` | 2 (exact) | Full-text search over all chunks |
| `readBySignature` | 3 (reader) | Complete method/class by name or FQN |
| `readFile` | 3 (reader) | Complete source view of one file |
| `listProjects` | discovery | List all searchable projects |
| `listFiles` | structural | List files by path prefix or glob |

### `vectorSearch`

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "vectorSearch",
    "arguments": {
      "userQuestion": "How is the authentication flow implemented?",
      "projectName": "my-project",
      "similarity": 0.85,
      "maxResults": 20,
      "skeletonsOnly": false
    }
  }
}
```

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `userQuestion` | string | **yes** | — | A targeted, standalone search prompt |
| `projectName` | string | no | active | Project to search |
| `similarity` | float | no | 0.85 | 0.0–1.0; higher = more precise |
| `maxResults` | int | no | 20 | 1–20 |
| `skeletonsOnly` | bool | no | false | `true` = class skeletons only |

### `grep`

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "grep",
    "arguments": {
      "query": "public MClass createMClass",
      "project": "my-project",
      "language": "java",
      "limit": 10
    }
  }
}
```

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `query` | string | **yes** | — | Exact search string |
| `project` | string | no | active | Project name |
| `language` | string | no | — | Filter: `java`, `ts`, etc. |
| `limit` | int | no | 10 | Max results (1–100) |

### `readBySignature`

```json
{
  "jsonrpc": "2.0",
  "id": 3,
  "method": "tools/call",
  "params": {
    "name": "readBySignature",
    "arguments": {
      "method": "com.example.Foo#bar",
      "container": "com.example.Foo",
      "project": "my-project"
    }
  }
}
```

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `method` | string | **yes** | — | Method name or FQN (`com.example.Foo#bar`) |
| `container` | string | no | — | Containing class FQN (disambiguates bare names) |
| `project` | string | no | active | Project name |

Returns up to 10 full matches (FQN, file, lines, javadoc, body). Additional matches are listed in `remainingFqns`.

### `readFile`

```json
{
  "jsonrpc": "2.0",
  "id": 4,
  "method": "tools/call",
  "params": {
    "name": "readFile",
    "arguments": {
      "file": "src/main/java/com/example/Foo.java",
      "project": "my-project",
      "offset": 1,
      "limit": 200
    }
  }
}
```

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `file` | string | **yes** | — | Path (absolute, relative, or basename) |
| `project` | string | no | active | Project name |
| `offset` | int | no | 1 | 1-based line to start from |
| `limit` | int | no | all | Max lines to return |

Path resolution is lenient: exact → suffix → basename. Ambiguous paths return a `candidates` list.

### `listProjects`

No parameters. Returns all searchable projects with their last index update timestamp.

### `listFiles`

```json
{
  "jsonrpc": "2.0",
  "id": 5,
  "method": "tools/call",
  "params": {
    "name": "listFiles",
    "arguments": {
      "path": "*Controller.java",
      "project": "my-project",
      "limit": 50
    }
  }
}
```

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `path` | string | no | `""` (all) | Prefix or glob (`*` spans `/`) |
| `project` | string | no | active | Project name |
| `limit` | int | no | 50 | Max files (1–500) |

---

## REST API

All REST endpoints are active in **hub mode** (`prjxp.hub.enabled=true`). OpenAPI/Swagger docs are available at `/v3/api-docs`.

### Tools

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/tools/ping` | Health check (returns `"pong!"`) |
| `GET` | `/api/tools/context` | RAG context (vector search with enrichment) |
| `GET` | `/api/tools/vectorSearch` | Semantic search |
| `GET` | `/api/tools/grep` | Full-text search |
| `GET` | `/api/tools/byIndex` | Deterministic symbol search |
| `GET` | `/api/tools/files` | List files |
| `GET` | `/api/tools/meta-search-params` | Machine-readable search parameters |
| `GET` | `/api/tools/projects` | List all projects with lifecycle status |

#### Example: Vector Search

```bash
curl "http://localhost:8080/api/tools/vectorSearch?query=authentication+flow&project=my-project&limit=10"
```

#### Example: Grep

```bash
curl "http://localhost:8080/api/tools/grep?query=public+MClass+createMClass&project=my-project&language=java"
```

#### Example: By-Index Search

```bash
curl "http://localhost:8080/api/tools/byIndex?language=java&fqn=com.example.Foo#bar&project=my-project"
```

#### Example: List Files

```bash
curl "http://localhost:8080/api/tools/files?path=*Controller.java&project=my-project&limit=50"
```

### Projects (Hub)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/projects` | List all projects with stats |
| `POST` | `/api/projects` | Create a new project |
| `PUT` | `/api/projects/{name}` | Update project config |
| `DELETE` | `/api/projects/{name}` | Delete a project |
| `POST` | `/api/projects/{name}/reindex` | Trigger full reindex |
| `POST` | `/api/projects/{name}/docpipe` | Trigger DocPipe-only re-run |
| `GET` | `/api/projects/{name}/reindex-status` | Reindex progress |
| `GET` | `/api/projects/{name}/stats` | Detailed stats |
| `POST` | `/api/projects/{name}/importEmbeddings` | Upload pre-embedded JSONL |
| `POST` | `/api/projects/upload-archive` | Upload a TAR/ZIP archive |
| `POST` | `/api/projects/upload-embedded` | Upload a pre-embedded archive |
| `GET` | `/api/projects/live-scan` | Scan for live project candidates |

### Chat

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/chat/send` | Send a chat message (JSON response) |
| `POST` | `/api/chat/send` (SSE) | Send with streaming (`Accept: text/event-stream`) |
| `POST` | `/api/chat/projects` | List available projects for RAG |
| `POST` | `/api/chat/tools` | List available tools |

#### Example: Chat with Tools

```bash
curl -X POST http://localhost:8080/api/chat/send \
  -H "Content-Type: application/json" \
  -d '{
    "message": "How does the authentication flow work?",
    "chatModelName": "my-llm",
    "projectContext": "my-project",
    "useTools": true
  }'
```

### System

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/system/health` | CPU, memory, disk, connections |
| `GET` | `/api/system/query-stats` | Query stats (avg/p95 latency, cache hits) |

### Globals (Model Management)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/globals/chat-models` | List chat models (API keys masked) |
| `POST` | `/api/globals/chat-models` | Create a chat model |
| `PUT` | `/api/globals/chat-models/{name}` | Update a chat model |
| `DELETE` | `/api/globals/chat-models/{name}` | Delete a chat model |
| `POST` | `/api/globals/chat-models/test` | Test a chat model connection |
| `GET` | `/api/globals/chat-models/models` | List models at a provider |
| `GET` | `/api/globals/embedding-model` | Get embedding model config |
| `PUT` | `/api/globals/embedding-model` | Update embedding model |
| `POST` | `/api/globals/embedding-model/test` | Test embedding model |
| `GET` | `/api/globals/embedding-model/models` | List embedding models |
| `GET` | `/api/globals/server-types` | List supported server types |

---

## Hub Mode

Hub mode (`prjxp.hub.enabled=true`) adds multi-project management with a full lifecycle pipeline.

### Project Kinds

| Kind | Description |
|------|-------------|
| **SNAPSHOT** | Extracted from a TAR into the hub's `projects-root`. The hub owns the tree. |
| **LIVE** | Registered in-place from the `import-dir` (marker file: `../prjxp.yaml`/`prjxp.yml`). The user owns the tree. Supports live updates. |

### Project Lifecycle

```
IMPORTING → CHUNKING → EMBEDDING → READY
                │            │
                │            └→ FAILED (old index kept if pre-swap)
                └→ FAILED
```

- **IMPORTING**: Project registered, pipeline queued.
- **CHUNKING**: Source files are being chunked (0–10% progress).
- **EMBEDDING**: Embeddings being computed (10–80% progress). Old index stays in use.
- **READY**: Searchable.
- **FAILED**: Pipeline error. Old index kept if failure was pre-swap.

### Import Flows

1. **TAR upload**: Drop a `<name>.tar` / `<name>.tgz` / `<name>.tar.gz` into `import-dir`. The `ImportPoller` extracts it (security-hardened: no absolute paths, no `..`, no symlinks, size/entry limits), registers the project, and enqueues the pipeline.

2. **Pre-embedded archive**: Drop a `<name>-embedded.(jsonl|gz|tgz|zip)` into `import-dir`. The `EmbeddedArchiveImporter` validates the naming scheme, extracts the JSONL, checks embedding compatibility against the local model, and imports.

3. **Live project discovery**: Directories with a `../prjxp.yaml`/`prjxp.yml` marker anywhere below `import-dir` are discovered recursively (bounded by `scan-max-depth`, pruned by `scan-exclude-dir-names` and `.prjxp-exclude` files).

4. **REST upload**: `POST /api/projects/upload-archive` or `POST /api/projects/upload-embedded`.

### Live Updates

LIVE projects with `liveUpdate: true` (default) are polled by the `LiveUpdateScheduler` on a 30-second tick with adaptive intervals:

- **Base interval**: 5 minutes (after first READY)
- **Doubling**: After each empty scan, the interval doubles (5m → 10m → 20m → … → 60m cap)
- **Reset**: When changes are found, the interval resets to base
- **Skip**: While a pipeline run or reindex is in flight

The `LiveUpdateScanner` performs a three-way diff (disk state vs. persisted source state vs. index) to compute `toEmbed` and `toDelete` sets. Updates are applied via a two-phase commit (write `.tmp` JSONL → scoped index update → atomic rename).

### Pipeline Orchestration

The `PipelineOrchestrator` runs a single FIFO worker (the Lucene index allows only one writer at a time). Each run is a `PipelineRun` with a phase number and a shared trigger ledger (loop breaker for listeners).

- **Root run** (phase 1): Standard pipeline (chunk → embed → swap) or pre-embedded pipeline.
- **Retrigger runs** (phase n+1): Scheduled by listeners (e.g., DocPipe document embedding).
- **Live update runs**: Two-phase (chunk+embed → swap), no listener dispatch.

Listeners (`PipelineListener`) are notified at start and finish, gated by the per-chain ledger. The `DocPipePipelineListener` generates documentation and schedules document embedding after a root run.

---

## Project Configuration (`../prjxp.yaml`)

Each project directory contains a `../prjxp.yaml` (or `prjxp.yml`) file:

```yaml
name: my-project
rootDir: .                    # relative to the project directory (or absolute)
jsonlFile: px-chunks.jsonl   # chunk output file name
chunoWhiteList: java,ts      # languages to index (comma-separated)
excludeDirNames: node_modules,.git,target
sourceDirNames: src
tibedBatchSize: 32           # embedding batch size
fulltext: false              # store a lossless fulltext copy (for readFile)
embeddingsFile: null         # pre-embedded JSONL file name (null = compute locally)
liveUpdate: true             # enable live-update polling (LIVE projects only)
prompt: |                    # standing instructions for the chat (optional)
  Focus on the business logic.
  Cite file paths in your answers.
```

### Marker Files

| File | Purpose |
|------|---------|
| `../prjxp.yaml` / `prjxp.yml` | Project marker (triggers live-project discovery) |
| `.ready` | Signals a pre-embedded project is ready for import |
| `.prjxp-exclude` | Tells the hub scan to ignore this directory entirely |

---

## Search Strategy (3-Stage Ladder)

The recommended workflow for AI agents and developers:

```
┌─────────────────────────────────────────────────────────────────┐
│  Stage 1: vectorSearch (discovery)                              │
│  "What classes handle authentication?"                          │
│  → Semantic overview, class skeletons, imports                  │
├─────────────────────────────────────────────────────────────────┤
│  Stage 2: grep (exact)                                          │
│  "public void authenticate(String token)"                       │
│  → Exact text match, full method body in the hit chunk          │
├─────────────────────────────────────────────────────────────────┤
│  Stage 3: readBySignature / readFile (complete)                 │
│  readBySignature("com.example.AuthService#authenticate")        │
│  readFile("src/main/java/com/example/AuthService.java")         │
│  → Complete implementation, all overloads, or whole file        │
└─────────────────────────────────────────────────────────────────┘

Orthogonal: listFiles (structural discovery by path pattern)
```

**Key rules:**
- Always start with `vectorSearch` for broad discovery.
- Use `grep` with the **exact method signature** to get the full body.
- Use `readBySignature` for a specific method by name/FQN.
- Use `readFile` when you need the entire file.
- Use `listFiles` to explore the file tree before reading.
- Always pass the `project` parameter (or use `listProjects` to discover valid names).

---

## Chat & RAG

The Chat API (`/api/chat`) provides LLM-powered Q&A with:

1. **RAG enrichment**: The user's question is embedded and the top 8 most similar chunks from the specified project are injected into the prompt.
2. **Agentic tool calling**: When `useTools: true`, the LLM can call prjxp tools (vectorSearch, grep, readFile, etc.) in a loop (max 3 iterations). Native function calling is used when supported; a text-based `TOOL_CALL[tool](params)` protocol is the fallback.
3. **SSE streaming**: `Accept: text/event-stream` enables live phase feedback (`rag_search` → `llm_generating` → `tools` → `tool_result` → `complete`).
4. **Standing instructions**: Project-level (`../prjxp.yaml` → `prompt`) and model-level (`chat-models[].prompt`) instructions are injected into the system prompt.

---

## CORS & Security

CORS is configured via `mcp.cors.allowed-patterns` (default: `http://localhost:*`). It applies to:

- `/api/**` — all REST endpoints (GET, POST, PUT, DELETE, OPTIONS)
- `/mcp` — the MCP endpoint (GET, POST, OPTIONS)
- `/v3/api-docs/**` — OpenAPI docs

### Import Security

- **TAR**: Rejects absolute paths, `..` segments, symlinks/hardlinks. Enforces max file size (2 GiB), max extracted size (4 GiB), max entry count (100k). Strips a single top-level directory.
- **ZIP**: Same path-traversal protections. Single-entry rule for embedded archives.
- **Embedded archives**: Name must be `<ProjectName>-embedded.(jsonl|gz|tgz|zip)`. First-line vector validation. Size limits enforced.
- **File upload**: File names are sanitized (last path segment only, rejects `.`/`..`).

---

## Troubleshooting

### "No searchable store for project 'X'"

The project is not registered or not in `READY` status. Check:
1. `GET /api/projects` — is the project listed? What is its status?
2. `GET /api/projects/{name}/reindex-status` — is a reindex in progress?
3. If the project was just imported, wait for the pipeline to complete.

### "Unknown language 'X'"

The language is not registered in the `SearchCapabilitiesRegistry`. Valid languages depend on the installed `ChunkerSearchCapabilitiesProvider` beans (built-in: `java`; plugins can add more). Check `GET /api/tools/meta-search-params` for the list.

### Live updates not triggering

1. Is `prjxp.hub.live-update-enabled` set to `true`?
2. Does the project's `../prjxp.yaml` have `liveUpdate: true` (default)?
3. Is the project in `READY` status?
4. Check the logs for "Live scan of 'X' found N file(s) to embed".
5. The adaptive interval may be at its 60-minute cap after many empty scans.

### Embedding compatibility failure on pre-embedded import

The imported vectors don't match the local embedding model (cosine similarity below threshold). This means the embeddings were generated with a different model or incompatible hardware. Solutions:
- Re-embed locally: `tibed --mode store`
- Adjust thresholds: `prjxp.hub.compatibility.threshold-low`
- Use the same embedding model for both generation and the hub.

### "Index search is not supported by this embedding store"

`readFile` and `readBySignature` require a Lucene index. If you're using a different store type, these tools will not work. Set `prjxp.store-type: LUCENE`.

### Web UI shows stale stats

The in-memory stats cache refreshes every `prjxp.hub.stats-refresh-interval-ms` (default 60s) and after pipeline runs. Set it to `0` to disable periodic refresh (stats update only on pipeline events).

---

## MCP Client Configuration

For AI clients that support MCP (e.g., Claude Desktop, Cursor):

```json
{
  "mcpServers": {
    "prjxp": {
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

The server supports the `2025-06-18` protocol version. The `McpCompatibilityFilter` handles the `initialize` handshake (stripping unsupported `elicitation` capability) and `server/discover` (reporting available tools/prompts/resources/completions based on registered `@McpTool`/`@McpPrompt`/`@McpResource`/`@McpComplete` annotations).

_This document was generated with Doc|Pipe_

