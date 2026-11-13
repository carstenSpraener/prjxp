# prjxp mcp-server

The **prjxp mcp-server** is a Spring Boot application that exposes a project's indexed source code and documentation through the **Model Context Protocol (MCP)**, a REST API, and an interactive chat interface. It enables AI agents and developers to search, read, and navigate codebases with semantic and structural precision.

---

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Installation & Configuration](#installation--configuration)
- [Running the Server](#running-the-server)
- [MCP Tools](#mcp-tools)
- [REST API](#rest-api)
- [Hub Mode](#hub-mode)
- [Project Management](#project-management)
- [Chat Interface](#chat-interface)
- [Setup Wizard](#setup-wizard)
- [Documentation](#documentation)
- [Configuration Reference](#configuration-reference)
- [Search Strategy](#search-strategy)

---

## Overview

The mcp-server provides three access surfaces to the same indexed project data:

| Surface | Protocol | Use Case |
|---------|----------|----------|
| **MCP** (`/mcp`) | JSON-RPC 2.0 | AI agent tool calling (Claude, Cursor, etc.) |
| **REST** (`/api/**`) | HTTP + OpenAPI | Programmatic access, Web UI, dashboards |
| **Chat** (`/api/chat`) | HTTP + SSE | Interactive Q&A with agentic tool use |

The server operates in two modes:

- **CLI mode** (default): Non-web application. Bundled CLI runners execute and the process exits. No embedded web server.
- **Hub mode** (`prjxp.hub.enabled=true`): Full web/MCP/REST stack with project management, live updates, chat, and setup wizard.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        mcp-server                               │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌───────────┐  ┌──────────────┐  ┌──────────────────────────┐ │
│  │   MCP     │  │    REST      │  │       Chat (SSE)         │ │
│  │  /mcp     │  │  /api/**     │  │      /api/chat           │ │
│  └─────┬─────┘  └──────┬───────┘  └────────────┬─────────────┘ │
│        │               │                        │               │
│  ┌─────▼───────────────▼────────────────────────▼─────────────┐ │
│  │                    Service Layer                            │ │
│  │  VectorSearchService │ GrepSearchService │ ReaderService    │ │
│  │  FileGraphService    │ SemanticGraphService │ SymbolReader  │ │
│  └────────────────────────────┬────────────────────────────────┘ │
│                               │                                  │
│  ┌────────────────────────────▼────────────────────────────────┐ │
│  │              Lucene Embedding Store (shared)                │ │
│  │         PxChunkDaoProvider → per-project DAOs              │ │
│  └────────────────────────────┬────────────────────────────────┘ │
│                               │                                  │
│  ┌────────────────────────────▼────────────────────────────────┐ │
│  │              Project Registry (HubProjectRegistry)          │ │
│  │         Sources: Snapshot │ Live │ Library │ GitHub         │ │
│  └─────────────────────────────────────────────────────────────┘ │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## Installation & Configuration

### Prerequisites

- Java 21+
- A running embedding model (Ollama, OpenAI-compatible API, or LM Studio)
- A chat model (for the chat interface)
- `git` on PATH (for GitHub import, hub mode)

### Configuration Files

The server reads configuration from:

1. **`application.yaml`** – Main configuration (embedding, chat models, hub settings)
2. **`.env`** – Secrets (API keys, tokens) referenced via `${ENV_VAR}` placeholders
3. **`prjxp.yaml`** (per project) – Project-specific settings

### Minimal `application.yaml`

```yaml
prjxp:
  hub:
    enabled: true                    # Enable hub mode (web/MCP/REST)
    import-dir: /import              # Directory watched for tar uploads
    projects-root: /projects         # Root for extracted snapshot projects
    live-jsonl-dir: /data/chunks     # Hub-managed JSONL for live projects
    poll-interval-ms: 5000
    live-update-enabled: true
    live-update-base-interval-ms: 300000
    live-update-max-interval-ms: 3600000

  embedding:
    type: OLLAMA                     # OLLAMA | OPEN_AI
    api-base-url: http://localhost:11434
    model-name: nomic-embed-text
    dimensions: 768
    timeout: 60
    max-segments-per-batch: 32

  embedding-store-lucene:
    index-path: /data/lucene-index
    vector-dimension: 768

  chat-models:
    - stereo-type: default
      server-type: ollama
      model-name: llama3
      provider-url: http://localhost:11434
      timeout-secs: 120
      temperature: 0.7

  reader-max-output-chars: 100000

  docs:
    dir: mcp-server/doc              # Documentation root for /api/docs
```

### `.env` Example

```bash
PRJXP_EMBEDDING_API_KEY_XXXXXXXX=your-embedding-key
PRJXP_CHAT_API_KEY_XXXXXXXX=your-chat-key
PRJXP_HUB_GITHUB_TOKEN_KEY=your-github-encryption-passphrase
```

---

## Running the Server

### CLI Mode (default)

```bash
java -jar mcp-server.jar
```

The process runs bundled CLI commands and exits. No web server is started.

### Hub Mode

```bash
java -jar mcp-server.jar --prjxp.hub.enabled=true
```

Or set it in `application.yaml`. The server starts an embedded web server (default port 8080) with all REST, MCP, and chat endpoints active.

### Docker

```bash
docker run -d \
  -p 8080:8080 \
  -v /path/to/import:/import \
  -v /path/to/projects:/projects \
  -v /path/to/data:/data \
  -v /path/to/github:/github \
  -v /path/to/libraries:/libraries \
  -v /path/to/config:/config \
  -e PRJXP_EMBEDDING_API_KEY_XXX=... \
  -e PRJXP_CHAT_API_KEY_XXX=... \
  prjxp/mcp-server:latest
```

---

## MCP Tools

When connected via the MCP protocol (`POST /mcp`), the server exposes **14 tools** for AI agents. All tools require a `project` parameter (discover via `listProjects`).

### Discovery

| Tool | Description |
|------|-------------|
| `listProjects` | Lists searchable projects and their last index update timestamp. **Call this first** to learn valid project names. |
| `listFiles` | Lists file paths in the index matching a prefix or glob (`*` spans `/`). Structural discovery, not content search. |

### Search (3-Stage Ladder)

| Tool | Description |
|------|-------------|
| `vectorSearch` | **Stage 1** – Semantic vector search. Returns POINTERS (hints), SKELETON (signatures), or FULL (bodies). Use for broad "where/how" questions. |
| `grep` | **Stage 2** – Exact full-text search. Use for exact identifiers, annotations, method signatures. |
| `byIndex` | **Stage 2b** – Deterministic symbol lookup via index metadata (FQN, methodName, signatureHash, containerFqn). |

### Reading

| Tool | Description |
|------|-------------|
| `readFile` | Reads a complete file from the index. Path resolution: exact → suffix → basename. Supports `offset`/`limit` for paging. |
| `readBySignature` | Reads a symbol by `FQN#name` (method body + javadoc) or class FQN (class frame). |
| `readChunks` | Reads full content of one or more chunks by `chunk_id` (from vectorSearch output). Max 10 chunks. |

### Code Graph (Typed)

| Tool | Description |
|------|-------------|
| `graphFile` | Typed code-graph neighbours of a file (imports, inheritance, calls). |
| `graphNode` | Typed neighbours of a class or method node id. |
| `graphCluster` | Important members of a Leiden cluster, ranked by degree. |
| `graphClusterOverview` | Largest typed-code clusters with sizes and top members. Architecture exploration without a starting class. |

### Semantic Graph (Embedding-Based)

| Tool | Description |
|------|-------------|
| `semanticGraphCluster` | Semantic cluster members across docs and class frames (embedding-space clustering). |
| `semanticGraphNode` | Project-scoped semantic neighbours with files and similarity scores. |
| `semanticGraphOverview` | Largest semantic clusters with member counts, top files, embedding model, and calibration warning. |

### Tool Parameters (Common)

| Parameter | Type | Description |
|-----------|------|-------------|
| `project` / `projectName` | string (required) | Project name from `listProjects` |
| `limit` / `maxResults` | int | Max results (clamped to tool-specific max) |
| `contentLevel` | string | `POINTERS` \| `SKELETON` \| `FULL` |
| `includeTests` | boolean | Include/exclude test sources |
| `includeExternal` | boolean | Include JDK/library edges (graph tools) |
| `maxEdges` | int | Max neighbours per direction (graph tools) |

### MCP Protocol Details

- **Endpoint**: `POST /mcp`
- **Protocol**: JSON-RPC 2.0
- **Supported version**: `2025-06-18`
- **Capabilities**: `tools` (listChanged: false)
- **Compatibility filter**: Strips `elicitation` capability from `initialize` requests for client compatibility

---

## REST API

All REST endpoints are active in hub mode. OpenAPI/Swagger docs are available at `/v3/api-docs`.

### Tools

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/tools/ping` | GET | Health check – returns `"pong!"` |
| `/api/tools/context` | GET | Semantic context retrieval (vector search with enrichment) |
| `/api/tools/projects` | GET | List all projects with lifecycle status |
| `/api/tools/vectorSearch` | GET | Semantic vector search |
| `/api/tools/grep` | GET | Full-text search |
| `/api/tools/byIndex` | GET | Deterministic symbol search |
| `/api/tools/files` | GET | List files (prefix/glob) |
| `/api/tools/meta-search-params` | GET | Machine-readable search parameter schema |

### Example: Vector Search

```
GET /api/tools/vectorSearch?query=authentication+filter&project=my-project&limit=10
```

### Example: Grep

```
GET /api/tools/grep?query=public+MClass+createMClass&project=my-project&language=java&limit=10&contentLevel=FULL
```

### Example: By Index

```
GET /api/tools/byIndex?language=java&fqn=com.example.Foo#bar&project=my-project&limit=10
```

### Example: List Files

```
GET /api/tools/files?path=src/main/java&project=my-project&limit=50
```

### Projects

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/projects` | GET | List all projects with stats and config |
| `/api/projects` | POST | Create a new project |
| `/api/projects/{name}` | PUT | Update project configuration |
| `/api/projects/{name}` | DELETE | Delete project (scoped index wipe) |
| `/api/projects/{name}/reindex` | POST | Trigger full reindex pipeline |
| `/api/projects/{name}/reindex-status` | GET | Current reindex status + progress |
| `/api/projects/{name}/docpipe` | POST | DocPipe-only re-run (docs only) |
| `/api/projects/{name}/embed-dependencies` | POST | Trigger dependency materialization |
| `/api/projects/{name}/reimport` | POST | Re-materialize a library project |
| `/api/projects/{name}/stats` | GET | Detailed project statistics |
| `/api/projects/import-github` | POST | Import a GitHub repository |
| `/api/projects/{name}/update-github` | POST | Update a GitHub project (git pull) |
| `/api/projects/github-operations` | GET | Recent async GitHub operations |
| `/api/projects/upload-archive` | POST | Upload a TAR/ZIP for import |
| `/api/projects/upload-embedded` | POST | Upload a pre-embedded archive |
| `/api/projects/{name}/importEmbeddings` | POST | Import pre-computed embeddings (JSONL) |
| `/api/projects/live-scan` | GET | Scan import dir for live project candidates |

### System

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/system/health` | GET | CPU, memory, disk, connections |
| `/api/system/query-stats` | GET | Query statistics (avg/p95 latency, cache hits) |

### CORS

Configured via `mcp.cors.allowed-patterns` (default: `http://localhost:*`). Applies to `/api/**`, `/mcp`, and `/v3/api-docs/**`.

---

## Hub Mode

Hub mode (`prjxp.hub.enabled=true`) adds:

- **Project lifecycle management** (import → chunk → embed → ready)
- **Multi-project support** with a shared Lucene index
- **Live updates** (adaptive polling of source trees)
- **GitHub import** (shallow clone + pipeline)
- **Dependency embedding** (Maven/Gradle → library projects)
- **DocPipe integration** (documentation generation + embedding)
- **Chat with agentic tool use**
- **Setup wizard** (guided configuration)
- **Web UI endpoints** (project list, stats, reindex progress)

### Project Sources

| Source | Kind | Root | Vanish Policy |
|--------|------|------|---------------|
| Snapshot | SNAPSHOT | `prjxp.hub.projects-root` | Unregister only |
| Live | LIVE | `prjxp.hub.import-dir` | Unregister + index wipe |
| Library | SNAPSHOT | `prjxp.hub.libraries-root` | Unregister only |
| GitHub | SNAPSHOT | `prjxp.hub.github-root` | Unregister + index wipe |

### Project Lifecycle

```
IMPORTING → CHUNKING → EMBEDDING → READY
                ↓           ↓
             FAILED      FAILED
```

- **Keep-old-index semantics**: The old index stays searchable until the new embeddings are fully computed. Only the final swap is briefly offline.
- **Live updates**: Adaptive polling (5 min base, 60 min cap). Files modified on disk are re-embedded incrementally.
- **Pre-embedded imports**: Skip chunking/embedding; validate embedding compatibility, then swap.

### Pipeline Phases

| Phase | Progress Band | Description |
|-------|---------------|-------------|
| Chunking | 0–10% | Source files → PxChunks |
| Embedding | 10–80% | Chunks → vectors (batched) |
| Storing | 80–100% | Scoped reset + batched import |
| Enrichment | (post-swap) | Semantic graph + signatures |
| DocPipe | (retrigger) | Doc generation + doc embedding |

---

## Project Management

### Creating a Project

```bash
curl -X POST http://localhost:8080/api/projects \
  -H "Content-Type: application/json" \
  -d '{
    "name": "my-project",
    "kind": "LIVE",
    "rootDir": "/path/to/source",
    "chunoWhiteList": "java,ts,md"
  }'
```

### Importing via TAR

Drop a `<name>.tar` or `<name>.tgz` file into the `import-dir`. The poller extracts it, registers the project, and enqueues the pipeline.

### Importing Pre-Embedded Archives

File naming: `<ProjectName>-embedded.(jsonl|gz|tgz|zip)`

```bash
curl -X POST http://localhost:8080/api/projects/upload-embedded \
  -F "file=@my-project-embedded.jsonl"
```

### Importing from GitHub

```bash
curl -X POST http://localhost:8080/api/projects/import-github \
  -H "Content-Type: application/json" \
  -d '{"url": "https://github.com/org/repo.git"}'
```

For private repos, include a token:

```bash
curl -X POST http://localhost:8080/api/projects/import-github \
  -H "Content-Type: application/json" \
  -d '{"url": "https://github.com/org/private-repo.git", "token": "ghp_..."}'
```

### Reindexing

```bash
curl -X POST http://localhost:8080/api/projects/my-project/reindex
```

### Deleting

```bash
curl -X DELETE http://localhost:8080/api/projects/my-project
```

SNAPSHOT: index wipe + directory removal. LIVE: index wipe + unregistration only (source tree preserved; the project will be re-discovered on next poll).

---

## Chat Interface

### Send a Message

```bash
curl -X POST http://localhost:8080/api/chat/send \
  -H "Content-Type: application/json" \
  -d '{
    "message": "How does the authentication flow work?",
    "chatModelName": "default",
    "projectContext": "my-project",
    "useTools": true,
    "messages": []
  }'
```

### Streaming (SSE)

```bash
curl -X POST http://localhost:8080/api/chat/send \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream" \
  -d '{"message": "Explain the pipeline", "chatModelName": "default", "projectContext": "my-project", "useTools": true}'
```

SSE events: `phase` (rag_search, llm_generating, tools, tool_result), `complete`, `error`.

### Agentic Tool Use

When `useTools: true`, the chat model can invoke prjxp tools (vectorSearch, grep, readFile, etc.) in an agentic loop (max 3 iterations). Native function calling is used when supported; a text-protocol fallback (`TOOL_CALL[tool](params)`) is used otherwise.

### Available Chat Models

```bash
curl http://localhost:8080/api/globals/chat-models
```

---

## Setup Wizard

The setup wizard (`/api/setup`) guides first-time configuration with versioned, conflict-safe writes.

### Workflow

```
GET  /api/setup/status          → Current step status
GET  /api/setup/configuration   → Current config (secrets masked)
POST /api/setup/test-embedding  → Test embedding model connectivity
POST /api/setup/test-chat       → Test chat model connectivity
POST /api/setup/test-github     → Test GitHub token
POST /api/setup/save            → Save a step (embedding | chat | github | optimization)
POST /api/setup/complete        → Mark setup complete
```

### Versioning & Concurrency

All writes require an `If-Match` header (config version token). A 409 `CONFIG_CHANGED` is returned if the config was modified since the version was read. Secrets are migrated to `.env` on save (never stored in plaintext in YAML).

### Steps

| Step | Required | Description |
|------|----------|-------------|
| `embedding` | Yes | Embedding model config + successful test + index compatibility |
| `chat` | Yes | Chat model config + successful test |
| `github` | No (skippable) | GitHub PAT (encrypted at rest) |
| `optimization` | No (skippable) | Vector dimension, batch size, threads, rerank weights |

---

## Documentation

The server serves its own documentation from the configured `prjxp.docs.dir` (default: `mcp-server/doc`).

| Endpoint | Description |
|----------|-------------|
| `GET /api/docs/tree` | Full documentation tree (folders, markdown docs, H2 chapters) |
| `GET /api/docs/content?path=...` | Raw markdown of a document |
| `GET /api/docs/file?path=...` | Static asset (images, etc.) |

---

## Configuration Reference

### `prjxp.hub.*` (Hub Properties)

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `false` | Master switch for hub mode |
| `import-dir` | `/import` | Directory watched for tar uploads |
| `projects-root` | `/projects` | Root for snapshot projects |
| `live-jsonl-dir` | `/data/chunks` | Hub-managed JSONL for live projects |
| `poll-interval-ms` | `5000` | Import poller interval |
| `live-update-enabled` | `true` | Master switch for live updates |
| `live-update-base-interval-ms` | `300000` | Base polling interval (5 min) |
| `live-update-max-interval-ms` | `3600000` | Max polling interval (60 min) |
| `stats-refresh-interval-ms` | `60000` | Periodic stats cache refresh |
| `max-tar-bytes` | `2 GiB` | Max tar file size |
| `max-extracted-bytes` | `4 GiB` | Max extracted size (compression bomb guard) |
| `max-entries` | `100000` | Max tar entries |
| `scan-exclude-dir-names` | `.git,.svn,.hg,node_modules` | Directories the marker scan skips |
| `scan-max-depth` | `8` | Max recursion depth for marker scan |
| `compatibility-sample-size` | `4` | Samples for embedding compatibility check |
| `compatibility-threshold-high` | `0.995` | Min avg cosine for COMPATIBLE |
| `compatibility-threshold-low` | `0.975` | Min avg cosine for PARTIALLY_COMPATIBLE |
| `libraries-root` | `/libraries` | Root for materialized library projects |
| `maven-repositories` | `https://repo1.maven.org/maven2` | Maven repo base URL |
| `max-dependency-bytes` | `100 MiB` | Max downloaded artifact size |
| `github-root` | `/github` | Root for cloned GitHub projects |
| `github-jsonl-dir` | `/data/github-chunks` | Hub-managed JSONL for GitHub projects |
| `github-clone-depth` | `1` | Shallow clone depth |
| `github-timeout-seconds` | `300` | Git process timeout |
| `github-token-key-env` | `PRJXP_HUB_GITHUB_TOKEN_KEY` | Env var name for token encryption key |

### `prjxp.embedding.*`

| Property | Description |
|----------|-------------|
| `type` | `OLLAMA` or `OPEN_AI` |
| `api-base-url` | Provider URL |
| `api-key` | API key (or `${ENV_VAR}` reference) |
| `model-name` | Embedding model name |
| `dimensions` | Vector dimension |
| `timeout` | Request timeout (seconds) |
| `max-segments-per-batch` | Batch size for embedding calls |

### `prjxp.chat-models[]`

| Property | Description |
|----------|-------------|
| `stereo-type` | Model alias (e.g. `default`) |
| `server-type` | `ollama`, `openai`, `lm-studio`, etc. |
| `model-name` | Model name |
| `api-key` | API key (or `${ENV_VAR}`) |
| `provider-url` | Provider URL |
| `timeout-secs` | Request timeout |
| `temperature` | Sampling temperature (0–2) |
| `prompt` | Standing instructions (system prompt addition) |
| `supports-tools` | Whether the model supports native function calling |

### `prjxp.hub.*` (per-project `prjxp.yaml`)

| Property | Default | Description |
|----------|---------|-------------|
| `name` | dir name | Project name |
| `rootDir` | `.` | Source root (relative to project dir) |
| `jsonlFile` | `px-chunks.jsonl` | Chunk JSONL filename |
| `chunoWhiteList` | `java,ts` | File extensions to chunk |
| `excludeDirNames` | (built-in) | Directories to skip |
| `sourceDirNames` | (built-in) | Source directories |
| `tibedBatchSize` | `32` | Embedding batch size |
| `fulltext` | `false` | Store lossless fulltext copies |
| `embeddingsFile` | – | Pre-embedded JSONL filename |
| `liveUpdate` | `true` | Enable live updates (LIVE projects) |
| `prompt` | – | Standing instructions for chat |
| `embedDependencies` | `false` | Enable dependency embedding |
| `dependencyDepth` | `2` | Max transitive depth |
| `dependencyWhitelist` | – | GroupId prefix filter (CSV) |
| `mavenDependency` | – | Origin marker (hub-generated libraries) |
| `gitUrl` | – | Origin marker (GitHub clones) |
| `gitToken` | – | Encrypted PAT (`v1:...` format) |

### `mcp.cors.allowed-patterns`

Comma-separated list of allowed origin patterns (default: `http://localhost:*`).

---

## Search Strategy

The recommended 3-stage search ladder for AI agents:

```
1. vectorSearch  →  Broad semantic overview (POINTERS by default)
       │
       ▼
2. grep          →  Exact text narrowing (annotations, symbols, signatures)
       │
       ▼
3. readFile / readBySignature  →  Full source for detailed understanding
```

**Orthogonal tools** (use independently of the ladder):

- `listFiles` – structural discovery by path pattern
- `graphFile` / `graphNode` – typed code relationships (imports, calls, inheritance)
- `graphClusterOverview` – architecture exploration from scratch
- `semanticGraphOverview` – embedding-space cluster exploration
- `readChunks` – drill into specific vectorSearch neighbours

**Content levels** (control output verbosity):

| Level | Content |
|-------|---------|
| `POINTERS` | File path + line range + brief hint (cheapest) |
| `SKELETON` | Signatures / class frames |
| `FULL` | Complete method bodies / file content |

**Source types** (vectorSearch):

| Value | Scope |
|-------|-------|
| `code` | Code files only (default) |
| `docs` | Prose documents (markdown, PDF) |
| `all` | Everything |

---

## Error Handling

### Unknown Project

All search tools require a valid `project` parameter. An unknown or missing project returns:

```json
{"error": "unknownProject", "detail": "No searchable store for project 'foo'. Available projects: bar, baz"}
```

### Search Errors

```json
{"error": "missingQuery", "detail": "Parameter 'query' is required."}
{"error": "missingLanguage", "detail": "Parameter 'language' is required."}
{"error": "missingSearchParams", "detail": "Provide at least one of: fqn, methodName, signatureHash, containerFqn."}
{"error": "unknownLanguage", "detail": "Unknown language 'xyz'. Valid languages: java, typescript"}
```

### Setup Errors

| Code | HTTP | Meaning |
|------|------|---------|
| `CONFIG_CHANGED` | 409 | Config modified since version was read |
| `SETUP_NOT_VERIFIED` | 409 | A matching successful test is required |
| `INDEX_REBUILD_REQUIRED` | 409 | Embedding model changed; index needs rebuild |
| `MISSING_SECRET` | 400 | Required environment variable is unset |
| `INVALID_REQUEST` | 400 | Malformed request |
| `HUB_DISABLED` | 409 | Hub is not enabled |
| `TEST_RECEIPT_INVALID` | 409 | Test token expired or doesn't match |

---

## Security

- **Tar/ZIP extraction**: Rejects absolute paths, `..` segments, symlinks, and enforces size/entry limits.
- **GitHub tokens**: Encrypted at rest (AES-256-GCM, PBKDF2 key derivation). Plaintext never persisted.
- **API keys**: Migrated to `.env` on save; YAML stores `${ENV_VAR}` references.
- **CORS**: Configurable origin patterns; credentials allowed.
- **Path traversal**: All file access is confined to the project/docs root.
- **XXE protection**: XML parsing (POM, YAML) disables external entities.

---

## Monitoring

- **`/api/system/health`** – CPU, memory, disk, active connections
- **`/api/system/query-stats`** – Total queries, avg/p95 latency, cache hit/miss, recent queries
- **`/api/projects/{name}/reindex-status`** – Pipeline phase + 0–100 progress
- **`/api/projects`** – Per-project chunk count, file count, live-update status

---

## License

See `LICENSE` file in the repository root.

_This document was generated with Doc|Pipe_

