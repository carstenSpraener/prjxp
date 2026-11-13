# prjxp mcp-server

A Spring Boot application that exposes a **Model Context Protocol (MCP)** server and a **REST API** for searching, reading, and navigating indexed source code and documentation of software projects. It supports both a **CLI mode** (non-web, for local use) and a **Hub mode** (web server with multi-project management, chat, and setup wizard).

---

## Table of Contents

- [Overview](#overview)
- [Modes](#modes)
- [Prerequisites](#prerequisites)
- [Configuration](#configuration)
- [MCP Tools](#mcp-tools)
- [REST API](#rest-api)
- [Hub Features](#hub-features)
- [Setup Wizard](#setup-wizard)
- [Chat API](#chat-api)
- [Documentation API](#documentation-api)
- [System API](#system-api)
- [CORS](#cors)
- [Running](#running)

---

## Overview

The mcp-server indexes project source code and documentation into a Lucene-based vector store and exposes the results through:

| Surface | Protocol | Use case |
|---------|----------|----------|
| MCP endpoint (`/mcp`) | JSON-RPC 2.0 | AI agents (Claude, Cursor, etc.) via the MCP protocol |
| REST API (`/api/**`) | HTTP | Web UI, scripts, CI pipelines |
| In-process tools | Java method calls | Hub chat with native function calling |
| CLI runners | Process | Local, non-web usage (no web server started) |

The server supports **multiple projects** simultaneously. Every search tool requires an explicit `project` parameter—there is no implicit default project.

---

## Modes

### CLI Mode (default)

When `prjxp.hub.enabled` is **not** `true`, the server runs as a non-web application. The bundled CLI runners execute and the process exits. No embedded web server is started.

```yaml
# application.yaml
prjxp:
  hub:
    enabled: false   # or omit entirely
```

### Hub Mode

When `prjxp.hub.enabled=true`, the full web stack is activated:

- MCP endpoint at `/mcp`
- REST API at `/api/**`
- Chat with agentic tool calling at `/api/chat`
- Project management (import, reindex, delete)
- Live-update polling for registered projects
- Setup wizard at `/api/setup`
- Documentation browser at `/api/docs`

```yaml
prjxp:
  hub:
    enabled: true
```

> **Note:** The `CliModeEnvironmentPostProcessor` automatically sets `spring.main.web-application-type=none` in CLI mode so no web server starts. An explicit user setting always wins.

---

## Prerequisites

- **Java 17+**
- **Maven** or **Gradle** (for building)
- **git** on PATH (required for GitHub import in hub mode)
- An **embedding model** (Ollama or OpenAI-compatible)
- A **chat model** (Ollama, OpenAI, or other supported provider)
- **Lucene** index directory (configured via `prjxp.embedding-store-lucene.index-path`)

---

## Configuration

### `application.yaml`

```yaml
prjxp:
  hub:
    enabled: true
    import-dir: /import
    projects-root: /projects
    live-jsonl-dir: /data/chunks
    poll-interval-ms: 5000
    live-update-enabled: true
    live-update-base-interval-ms: 300000
    live-update-max-interval-ms: 3600000
    stats-refresh-interval-ms: 60000
    max-tar-bytes: 2147483648
    max-extracted-bytes: 4294967296
    max-entries: 100000
    scan-exclude-dir-names:
      - .git
      - .svn
      - .hg
      - node_modules
    scan-max-depth: 8
    compatibility-sample-size: 4
    compatibility-threshold-high: 0.995
    compatibility-threshold-low: 0.975
    libraries-root: /libraries
    maven-repositories: https://repo1.maven.org/maven2
    max-dependency-bytes: 104857600
    maven-connect-timeout-seconds: 10
    maven-request-timeout-seconds: 60
    gradle-timeout-seconds: 300
    github-root: /github
    github-jsonl-dir: /data/github-chunks
    github-clone-depth: 1
    github-timeout-seconds: 300
    github-token-key-env: PRJXP_HUB_GITHUB_TOKEN_KEY

  embedding:
    type: OLLAMA          # or OPEN_AI
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
    dir: mcp-server/doc
```

### `.env` file

Secrets (API keys, tokens) are stored in a `.env` file and referenced from `application.yaml` via `${ENV_VAR_NAME}` placeholders. The setup wizard automatically migrates plaintext keys into the `.env` file.

```bash
# .env
PRJXP_EMBEDDING_API_KEY_XXXX=your-embedding-key
PRJXP_CHAT_API_KEY_XXXX=your-chat-key
PRJXP_HUB_GITHUB_TOKEN_KEY=your-github-encryption-passphrase
```

### Project configuration (`prjxp.yaml`)

Each project directory contains a `prjxp.yaml` (or `prjxp.yml`) marker file:

```yaml
name: my-project
rootDir: .
jsonlFile: px-chunks.jsonl
chunoWhiteList: java,ts
excludeDirNames: node_modules,.git
sourceDirNames: src
tibedBatchSize: 32
fulltext: false
liveUpdate: true
# prompt: "Focus on the API layer"
# embedDependencies: true
# dependencyDepth: 2
# dependencyWhitelist: com.example
# gitUrl: https://github.com/org/repo
# mavenDependency: com.example:lib:1.0.0
```

---

## MCP Tools

The MCP endpoint (`POST /mcp`) exposes **13 tools** via the MCP protocol. The `McpCompatibilityFilter` handles legacy protocol version negotiation (`2025-06-18`) and strips unsupported capability flags.

### Search Ladder (recommended order)

| # | Tool | Purpose |
|---|------|---------|
| 1 | `vectorSearch` | Semantic search—start here for broad "where/how" questions |
| 2 | `grep` | Exact text search—narrow down with identifiers, annotations, signatures |
| 3 | `readFile` / `readBySignature` | Read the actual source for detailed understanding |

Orthogonal to the ladder: `listFiles` for structural discovery by path pattern.

### Tool Reference

#### `vectorSearch`

Semantic vector search over project chunks, ranked by similarity.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `userQuestion` | string | **Yes** | Search prompt for vector retrieval |
| `projectName` | string | **Yes** | Project name (see `listProjects`) |
| `similarity` | number | No | Threshold 0.0–1.0 (default 0.85) |
| `maxResults` | integer | No | Max files (default 10, max 20) |
| `contentLevel` | string | No | `POINTERS` (default), `SKELETON`, `FULL` |
| `sourceType` | string | No | `code` (default), `docs`, `all` |
| `includeTests` | boolean | No | Include test sources (default false) |

#### `grep`

Exact full-text search over all chunks.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `query` | string | **Yes** | Exact search string |
| `project` | string | **Yes** | Project name |
| `language` | string | No | Language filter (e.g. `java`) |
| `limit` | integer | No | Max hits (default 10, max 100) |
| `contentLevel` | string | No | `POINTERS`, `SKELETON`, `FULL` (default) |
| `includeTests` | boolean | No | Include test sources (default true) |

#### `readFile`

Reads a complete file from the index. Path resolution is lenient: exact → suffix → basename.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | **Yes** | File path (absolute, relative, or name) |
| `project` | string | **Yes** | Project name |
| `offset` | integer | No | 1-based start line |
| `limit` | integer | No | Max lines from offset |

#### `readBySignature`

Reads a symbol (method or class) by its fully qualified name.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `method` | string | **Yes** | `FQN#name` for methods; class FQN for class frames |
| `container` | string | No | Containing class FQN to disambiguate |
| `project` | string | **Yes** | Project name |

#### `listProjects`

Lists all searchable projects with their last index update timestamp. **Call this first** to discover valid project names.

No parameters.

#### `listFiles`

Lists file paths in the index (structure, not content).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `path` | string | No | Path prefix or glob (`*` spans `/`); empty = all |
| `project` | string | **Yes** | Project name |
| `limit` | integer | No | Max files (default 50, max 500) |

#### `graphFile`

Typed code-graph neighbours of a file (imports, inheritance, calls).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | **Yes** | File path |
| `project` | string | **Yes** | Project name |
| `maxEdges` | integer | No | Max neighbours per direction (default 30, max 100) |
| `includeExternal` | boolean | No | Include JDK/library edges (default false) |

#### `graphNode`

Typed neighbours of a class or method node in the code graph.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `symbol` | string | **Yes** | Class FQN or method id; bare names match as suffix |
| `project` | string | **Yes** | Project name |
| `maxEdges` | integer | No | Max neighbours per direction (default 30, max 100) |
| `includeExternal` | boolean | No | Include JDK/library edges (default false) |

#### `graphCluster`

Members of a Leiden cluster in the typed code graph, ranked by degree.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `clusterId` | string | **Yes** | Cluster id (e.g. `c6`) |
| `project` | string | **Yes** | Project name |
| `topN` | integer | No | Top members (default 10, max 50) |

#### `graphClusterOverview`

Largest typed-code clusters with sizes and top members. Use to explore architecture without a starting class.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project` | string | **Yes** | Project name |
| `topN` | integer | No | Clusters to return (default 10, max 50) |

#### `semanticGraphCluster`

Members of a semantic (embedding-based) cluster across docs and class frames.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `clusterId` | string | **Yes** | Semantic cluster id (e.g. `s3`) |
| `project` | string | **Yes** | Project name |
| `topN` | integer | No | Max members (default 10, max 50) |

#### `semanticGraphNode`

Project-scoped semantic neighbours (kNN) with files and similarity scores.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `nodeId` | string | **Yes** | Semantic node id |
| `project` | string | **Yes** | Project name |
| `maxEdges` | integer | No | Max neighbours (default 20, max 100) |

#### `semanticGraphOverview`

Largest semantic clusters with member counts, top files, embedding model name, and calibration warning.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project` | string | **Yes** | Project name |
| `topN` | integer | No | Max clusters (default 10, max 50) |

---

## REST API

All REST endpoints are active only in hub mode (`prjxp.hub.enabled=true`).

### Tools

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/tools/ping` | Health check—returns `"pong!"` |
| `GET` | `/api/tools/context` | RAG context retrieval (vector search with enrichment) |
| `GET` | `/api/tools/projects` | List all projects with lifecycle status |
| `GET` | `/api/tools/vectorSearch` | Semantic vector search |
| `GET` | `/api/tools/grep` | Full-text search |
| `GET` | `/api/tools/byIndex` | Deterministic symbol search over chunk index |
| `GET` | `/api/tools/files` | List files in the project index |
| `GET` | `/api/tools/meta-search-params` | Machine-readable search parameter schema |

#### `GET /api/tools/vectorSearch`

```
GET /api/tools/vectorSearch?query=authentication&project=my-project&language=java&limit=10
```

#### `GET /api/tools/grep`

```
GET /api/tools/grep?query=public%20MClass%20createMClass&project=my-project&language=java&limit=10&contentLevel=FULL
```

#### `GET /api/tools/byIndex`

```
GET /api/tools/byIndex?language=java&fqn=com.example.Foo#bar&project=my-project&limit=10
```

At least one of `fqn`, `methodName`, `signatureHash`, `containerFqn` is required.

#### `GET /api/tools/files`

```
GET /api/tools/files?path=src/main/java&project=my-project&limit=50
```

### Projects

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/projects` | List all projects with stats and config |
| `POST` | `/api/projects` | Create a new project |
| `PUT` | `/api/projects/{name}` | Update project configuration |
| `DELETE` | `/api/projects/{name}` | Delete a project |
| `POST` | `/api/projects/{name}/reindex` | Trigger full reindex pipeline |
| `POST` | `/api/projects/{name}/docpipe` | DocPipe-only re-run (docs only) |
| `POST` | `/api/projects/{name}/embed-dependencies` | Trigger dependency materialization |
| `POST` | `/api/projects/{name}/reimport` | Re-materialize a library project |
| `POST` | `/api/projects/{name}/importEmbeddings` | Import pre-computed embeddings (JSONL upload) |
| `POST` | `/api/projects/import-github` | Import a GitHub repository |
| `POST` | `/api/projects/{name}/update-github` | Update a GitHub project (git pull + re-embed) |
| `GET` | `/api/projects/github-operations` | Recent async GitHub operations |
| `GET` | `/api/projects/{name}/reindex-status` | Current reindex status and progress |
| `GET` | `/api/projects/{name}/stats` | Detailed project statistics |
| `GET` | `/api/projects/live-scan` | Scan import directory for live project candidates |
| `POST` | `/api/projects/upload-archive` | Upload a TAR/ZIP archive for import |
| `POST` | `/api/projects/upload-embedded` | Upload a pre-embedded archive |

### Project Lifecycle

```
IMPORTING → CHUNKING → EMBEDDING → READY
                ↓           ↓
             FAILED      FAILED
```

For **LIVE** projects, live updates run in the background:

```
READY → (live update) → EMBEDDING_PHASE_n → READY
```

---

## Hub Features

### Project Sources

The hub discovers projects from four sources:

| Source | Root | Kind | Vanish Policy |
|--------|------|------|---------------|
| `snapshot` | `prjxp.hub.projects-root` | SNAPSHOT | Unregister only |
| `live` | `prjxp.hub.import-dir` | LIVE | Unregister + index wipe |
| `library` | `prjxp.hub.libraries-root` | SNAPSHOT | Unregister only |
| `github` | `prjxp.hub.github-root` | SNAPSHOT | Unregister + index wipe |

### Pipeline

A single FIFO worker processes projects through:

1. **Chunking** (0–10%): Parse source files into chunks
2. **Embedding** (10–80%): Compute vector embeddings
3. **Swap** (80–100%): Scoped reset + batched import into Lucene

The old index stays in place (project remains searchable) until the final swap.

### Live Updates

For LIVE projects, an adaptive polling scheduler (30 s tick) scans for file changes:

- Base interval: 5 minutes (doubles after each empty scan, caps at 60 minutes)
- Resets to base when changes are found
- Skipped while a pipeline run is in flight
- On completion, forces an immediate rescan (≤ 30 s)

### GitHub Import

```bash
curl -X POST http://localhost:8080/api/projects/import-github \
  -H "Content-Type: application/json" \
  -d '{"url": "https://github.com/org/repo", "token": "ghp_..."}'
```

- Shallow-clone (`--depth 1`) into `github-root/<name>`
- Generates `prjxp.yaml` with `gitUrl` origin marker
- Encrypted PAT stored in YAML (AES-256-GCM, never plaintext)
- Non-blocking: returns 202, poll `/api/projects/github-operations` for status

### Dependency Embedding

For projects with `embedDependencies: true` in `prjxp.yaml`:

- Resolves Maven/Gradle dependency trees (bounded BFS)
- Downloads `-sources` / `-javadoc` JARs from Maven Central
- Materializes each as an independent library project
- Runs the standard pipeline on each library

### Pre-Embedded Import

Upload a pre-computed embeddings file (JSONL, GZ, TGZ, ZIP) named `<ProjectName>-embedded.<ext>`:

```bash
curl -X POST http://localhost:8080/api/projects/upload-embedded \
  -F "file=@my-project-embedded.jsonl"
```

The server validates embedding compatibility (cosine similarity check) before importing.

---

## Setup Wizard

The setup wizard (`/api/setup`) guides first-time configuration with optimistic concurrency control (ETag-based `If-Match` headers).

### Steps

| Step | Required | Description |
|------|----------|-------------|
| `embedding` | Yes | Configure and test the embedding model |
| `chat` | Yes | Configure and test the chat model |
| `github` | No | Configure GitHub token (encrypted at rest) |
| `optimization` | No | Tune performance parameters |

### Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/setup/status` | Current setup status (which steps are done) |
| `GET` | `/api/setup/configuration` | Full configuration (secrets masked) |
| `POST` | `/api/setup/test-embedding` | Test embedding model connectivity |
| `POST` | `/api/setup/test-chat` | Test chat model connectivity |
| `POST` | `/api/setup/test-github` | Test GitHub token |
| `POST` | `/api/setup/save` | Save a step's configuration |
| `POST` | `/api/setup/complete` | Mark setup as complete |

### Concurrency Control

All mutating endpoints require an `If-Match` header with the current `configVersion` (ETag). A `409 CONFIG_CHANGED` is returned if the configuration was modified since the version was read.

---

## Chat API

The chat endpoint supports **agentic tool calling**: the LLM can invoke prjxp tools (vectorSearch, grep, readFile, etc.) in a loop of up to 3 iterations.

### Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/chat/send` | Send a message (JSON response) |
| `POST` | `/api/chat/send` (Accept: `text/event-stream`) | Send a message (SSE streaming) |
| `POST` | `/api/chat/projects` | List available projects for chat |
| `POST` | `/api/chat/tools` | List available tools |

### Request

```json
{
  "message": "How does the authentication flow work?",
  "chatModelName": "default",
  "projectContext": "my-project",
  "useTools": true,
  "messages": [
    {"role": "user", "content": "Tell me about the API"},
    {"role": "assistant", "content": "The API uses..."}
  ]
}
```

### SSE Events

| Event | Data |
|-------|------|
| `phase` | `{"phase": "rag_search", "message": "..."}` |
| `phase` | `{"phase": "llm_generating", "message": "..."}` |
| `phase` | `{"phase": "tools", "toolCalls": [...]}` |
| `phase` | `{"phase": "tool_result", "tool": "grep", "result": "..."}` |
| `complete` | Full `ChatResponse` JSON |
| `error` | `{"message": "..."}` |

### Tool Calling Protocol

- **Native function calling** is used when the model supports it (provider API level)
- **Text protocol fallback**: `TOOL_CALL[toolName](param="value")` for models without native tool support
- **XML recovery**: Some models (e.g. Qwen) emit `<function=...>` tags; these are detected and recovered

---

## Documentation API

Serves the project's documentation tree (Markdown files with H2 chapter extraction).

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/docs/tree` | Full documentation tree (folders, docs, chapters) |
| `GET` | `/api/docs/content?path=guide.md` | Raw Markdown of a document |
| `GET` | `/api/docs/file?path=images/logo.png` | Static asset (image, etc.) |

The docs root is configured via `prjxp.docs.dir` (default: `mcp-server/doc`).

---

## System API

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/system/health` | CPU, memory, disk, active connections |
| `GET` | `/api/system/query-stats` | Query statistics (total, avg/p95 latency, cache hits) |

---

## CORS

Configured via `mcp.cors.allowed-patterns` (comma-separated, default `http://localhost:*`):

```yaml
mcp:
  cors:
    allowed-patterns: "http://localhost:*,http://192.168.1.*:3000"
```

Applies to `/api/**`, `/mcp`, and `/v3/api-docs/**`.

---

## Running

### Build

```bash
mvn clean package -DskipTests
```

### CLI Mode

```bash
java -jar mcp-server.jar --prjxp.hub.enabled=false
```

Or with a `.env` file:

```bash
java -jar mcp-server.jar --spring.config.additional-location=file:/path/to/
```

### Hub Mode

```bash
java -jar mcp-server.jar \
  --prjxp.hub.enabled=true \
  --prjxp.embedding.type=OLLAMA \
  --prjxp.embedding.api-base-url=http://localhost:11434 \
  --prjxp.embedding.model-name=nomic-embed-text \
  --prjxp.embedding-store-lucene.index-path=/data/lucene \
  --prjxp.hub.import-dir=/import \
  --prjxp.hub.projects-root=/projects
```

### Docker

```bash
docker run -d \
  -p 8080:8080 \
  -v /host/import:/import \
  -v /host/projects:/projects \
  -v /host/data:/data \
  -v /host/.env:/app/.env \
  -e PRJXP_HUB_GITHUB_TOKEN_KEY=... \
  mcp-server:latest
```

### MCP Client Configuration

For Claude Desktop or similar MCP clients:

```json
{
  "mcpServers": {
    "prjxp": {
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

---

## Search Limits

| Parameter | Default | Max |
|-----------|---------|-----|
| `limit` (search tools) | 10 | 100 |
| `limit` (listFiles) | 50 | 500 |
| `maxEdges` (graph tools) | 30 | 100 |
| `topN` (cluster tools) | 10 | 50 |
| `maxResults` (vectorSearch) | 10 | 20 |
| `similarity` (vectorSearch) | 0.85 | 1.0 |

---

## Error Handling

- **Unknown project**: `400` with `{"error": "unknownProject", "detail": "No searchable store for project 'X'. Available projects: ..."}`
- **Missing required parameter**: `400` with a specific error code
- **Setup conflicts**: `409` with `CONFIG_CHANGED` or `SETUP_NOT_VERIFIED`
- **Internal errors**: `500` with `INTERNAL_ERROR`

---

## Architecture Notes

- **Single Lucene index** shared across all projects (scoped by `pxchunk_project` field)
- **Single pipeline worker** (FIFO)—the Lucene index allows only one writer at a time
- **In-memory stats cache** refreshed after pipeline runs and periodically (60 s default)
- **FileChannel locking** for configuration file writes (concurrent setup protection)
- **Secrets** stored in `.env`, referenced via `${VAR}` in YAML, never logged
- **GitHub tokens** encrypted at rest (AES-256-GCM, PBKDF2 key derivation)
- **MCP compatibility filter** handles legacy protocol versions and strips unsupported capabilities

_This document was generated with Doc|Pipe_

