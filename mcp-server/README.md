# prjxp mcp-server

The **prjxp mcp-server** is a Spring Boot application that exposes a project's indexed source code and documentation through the **Model Context Protocol (MCP)**, a REST API, and an interactive chat interface. It provides AI agents and developers with structured access to code search, file reading, dependency graphs, and semantic analysis.

The server operates in two modes:

| Mode | Trigger | Web Server | Use Case |
|------|---------|------------|----------|
| **CLI** (default) | `prjxp.hub.enabled` is `false` or unset | None (`web-application-type=none`) | Bundled CLI runners execute and exit |
| **Hub** | `prjxp.hub.enabled=true` | Embedded (Tomcat) | Multi-project hub with MCP, REST, chat, and project lifecycle management |

---

## Table of Contents

- [Quick Start](#quick-start)
- [Configuration](#configuration)
- [MCP Tools](#mcp-tools)
- [REST API](#rest-api)
- [Chat API](#chat-api)
- [Project Management (Hub)](#project-management-hub)
- [Setup Wizard](#setup-wizard)
- [MCP Protocol Details](#mcp-protocol-details)
- [Search Strategy](#search-strategy)
- [Content Levels](#content-levels)
- [Live Updates](#live-updates)
- [GitHub Integration](#github-integration)
- [Dependency Embedding](#dependency-embedding)
- [Documentation API](#documentation-api)
- [CORS](#cors)

---

## Quick Start

### Prerequisites

- Java 17+
- A configured embedding model (Ollama or OpenAI-compatible)
- A configured chat model (for the chat API)
- `git` on PATH (for GitHub import, hub mode)

### Running the Server

```bash
# CLI mode (default) — runs bundled CLI tools, no web server
java -jar mcp-server.jar

# Hub mode — full web stack with MCP, REST, chat
java -jar mcp-server.jar --prjxp.hub.enabled=true
```

The server reads a `.env` file from the working directory at startup (via `PrjXPCli.readDotEnv`).

### Verifying the Server

```bash
# Ping (hub mode)
curl http://localhost:8080/api/tools/ping
# → "pong!"

# List projects
curl http://localhost:8080/api/tools/projects

# MCP endpoint
curl -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}'
```

---

## Configuration

All configuration is read from `application.yaml` (or `application.yml`), environment variables, and a `.env` file.

### Core Properties

| Property | Default | Description |
|----------|---------|-------------|
| `prjxp.hub.enabled` | `false` | Master switch for the hub web stack |
| `prjxp.embedding.type` | — | `OLLAMA` or `OPEN_AI` |
| `prjxp.embedding.api-base-url` | — | Embedding model endpoint |
| `prjxp.embedding.api-key` | — | API key (supports `${ENV_VAR}` references) |
| `prjxp.embedding.model-name` | — | Model name |
| `prjxp.embedding.dimensions` | — | Vector dimension (auto-detected on first test) |
| `prjxp.embedding.timeout` | — | Request timeout (seconds) |
| `prjxp.embedding.max-segments-per-batch` | — | Batch size for embedding calls |
| `prjxp.embedding-store-lucene.index-path` | — | Lucene index directory |
| `prjxp.embedding-store-lucene.vector-dimension` | — | Must match the embedding model |
| `prjxp.chat-models` | — | List of chat model definitions |
| `prjxp.reader-max-output-chars` | — | Character cap for `readFile` output |
| `prjxp.docs.dir` | `mcp-server/doc` | Documentation root directory |
| `mcp.cors.allowed-patterns` | `http://localhost:*` | Comma-separated CORS origin patterns |

### Hub Properties

| Property | Default | Description |
|----------|---------|-------------|
| `prjxp.hub.import-dir` | `/import` | Directory watched for tar/embedded uploads |
| `prjxp.hub.projects-root` | `/projects` | Root for extracted snapshot projects |
| `prjxp.hub.live-jsonl-dir` | `/data/chunks` | Hub-managed JSONL for live projects |
| `prjxp.hub.poll-interval-ms` | `5000` | Import poller interval |
| `prjxp.hub.live-update-enabled` | `true` | Master switch for live updates |
| `prjxp.hub.live-update-base-interval-ms` | `300000` | Base polling interval (5 min) |
| `prjxp.hub.live-update-max-interval-ms` | `3600000` | Max polling interval (60 min) |
| `prjxp.hub.stats-refresh-interval-ms` | `60000` | In-memory stats cache refresh |
| `prjxp.hub.max-tar-bytes` | `2 GiB` | Max tar file size |
| `prjxp.hub.max-extracted-bytes` | `4 GiB` | Max extracted size (compression-bomb guard) |
| `prjxp.hub.max-entries` | `100000` | Max tar entries |
| `prjxp.hub.scan-exclude-dir-names` | `.git,.svn,.hg,node_modules` | Directories the marker scan skips |
| `prjxp.hub.scan-max-depth` | `8` | Max recursion depth for marker scan |
| `prjxp.hub.github-root` | `/github` | Root for GitHub clones |
| `prjxp.hub.github-jsonl-dir` | `/data/github-chunks` | JSONL for GitHub projects |
| `prjxp.hub.github-clone-depth` | `1` | Shallow clone depth |
| `prjxp.hub.github-timeout-seconds` | `300` | Git process timeout |
| `prjxp.hub.github-token-key-env` | `PRJXP_HUB_GITHUB_TOKEN_KEY` | Env var name for token encryption key |
| `prjxp.hub.libraries-root` | `/libraries` | Root for materialized library projects |
| `prjxp.hub.maven-repositories` | `https://repo1.maven.org/maven2` | Maven repo base URL |
| `prjxp.hub.max-dependency-bytes` | `100 MiB` | Max artifact download size |
| `prjxp.hub.compatibility-sample-size` | `4` | Samples for embedding compatibility check |
| `prjxp.hub.compatibility-threshold-high` | `0.995` | Cosine threshold for COMPATIBLE |
| `prjxp.hub.compatibility-threshold-low` | `0.975` | Cosine threshold for PARTIALLY_COMPATIBLE |

### Example `application.yaml`

```yaml
prjxp:
  hub:
    enabled: true
    import-dir: /import
    projects-root: /projects
    live-jsonl-dir: /data/chunks
    github-root: /github
    libraries-root: /libraries
    github-token-key-env: PRJXP_HUB_GITHUB_TOKEN_KEY

  embedding:
    type: OLLAMA
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
    dir: ./doc
```

---

## MCP Tools

The server exposes **14 tools** via the MCP protocol (`POST /mcp`) and as native function-calling tools in the chat API. All tools require a `project` parameter (discover via `listProjects`).

### Discovery

| Tool | Description |
|------|-------------|
| `listProjects` | Lists searchable projects with their last index update timestamp. **Call this first** to learn valid project names. |
| `listFiles` | Lists file paths in the index matching a prefix or glob pattern. `*` spans `/` (recursive). |

### Search (3-Stage Ladder)

| Tool | Description |
|------|-------------|
| `vectorSearch` | Semantic vector search. **Start here** for broad "where/how" questions. Returns ranked chunks with pointers to follow up. |
| `grep` | Exact full-text search. Use for specific identifiers, strings, or annotations. Default `contentLevel=NAMES` returns one line per file (`count  path`). |
| `readBySignature` | Deterministic symbol lookup by FQN or method name. Returns javadoc + method body. |

### Reading

| Tool | Description |
|------|-------------|
| `readFile` | Reads a complete file from the index. Path resolution: exact → suffix → basename. Supports `offset`/`limit` for paging. |
| `readChunks` | Reads full content of one or more chunks by `chunk_id` (from vectorSearch output). Max 10 chunks. |

### Code Graph (Typed)

| Tool | Description |
|------|-------------|
| `graphFile` | Typed code-graph neighbours of a file (imports, inheritance, calls). |
| `graphNode` | Typed neighbours of a class or method node. |
| `graphCluster` | Important members of a Leiden cluster, ranked by degree. |
| `graphClusterOverview` | Largest typed-code clusters with sizes and top members. Architecture exploration without a starting class. |

### Semantic Graph (Embedding-Based)

| Tool | Description |
|------|-------------|
| `semanticGraphCluster` | Members of a semantic cluster (embedding-space grouping). |
| `semanticGraphNode` | Project-scoped semantic neighbours with similarity scores. |
| `semanticGraphOverview` | Largest semantic clusters with member counts, top files, and model info. |

### Tool Parameters (Common)

| Parameter | Type | Description |
|-----------|------|-------------|
| `project` | string (required) | Project name from `listProjects` |
| `contentLevel` | string | `POINTERS`, `SKELETON`, `FULL`, `NAMES`, or `AGGREGATED` |
| `limit` / `maxResults` | int | Maximum results (clamped to tool-specific max) |
| `includeTests` | boolean | Include test sources |
| `language` | string | Language filter (e.g. `java`, `ts`) |

### Tool Parameters (Graph Tools)

| Parameter | Type | Description |
|-----------|------|-------------|
| `maxEdges` | int | Max neighbours per direction (default 30, max 500) |
| `includeExternal` | boolean | Include JDK/library edges (default `false`) |
| `offset` | int | Pagination offset per direction |
| `contentLevel` | string | `FULL` (raw edges) or `AGGREGATED` (per-module counts) |

### Tool Parameters (vectorSearch)

| Parameter | Type | Description |
|-----------|------|-------------|
| `userQuestion` | string (required) | Search prompt |
| `projectName` | string (required) | Project name |
| `similarity` | double | Threshold 0.0–1.0 (default 0.85) |
| `maxResults` | int | Max files (default 10, max 20) |
| `contentLevel` | string | `POINTERS` (default), `SKELETON`, `FULL`, `NAMES` |
| `sourceType` | string | `code` (default), `docs`, `all` |
| `includeTests` | boolean | Include test sources (default `false`) |

---

## REST API

All REST endpoints are active in **hub mode** only (`prjxp.hub.enabled=true`).

### Search Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/tools/ping` | Health check → `"pong!"` |
| `GET` | `/api/tools/context` | RAG context enrichment (vector search + prompt) |
| `GET` | `/api/tools/projects` | List all projects with lifecycle status |
| `GET` | `/api/tools/vectorSearch` | Semantic vector search |
| `GET` | `/api/tools/grep` | Full-text search |
| `GET` | `/api/tools/byIndex` | Deterministic symbol search |
| `GET` | `/api/tools/files` | List files in the index |
| `GET` | `/api/tools/meta-search-params` | Machine-readable search parameter schema |

#### Example: Vector Search

```bash
curl "http://localhost:8080/api/tools/vectorSearch?query=how+does+authentication+work&project=myproject&limit=5"
```

#### Example: Grep

```bash
curl "http://localhost:8080/api/tools/grep?query=public+MClass+createMClass&project=myproject&language=java&limit=10"
```

#### Example: ByIndex

```bash
curl "http://localhost:8080/api/tools/byIndex?language=java&fqn=com.example.Foo#bar&project=myproject"
```

#### Example: List Files

```bash
curl "http://localhost:8080/api/tools/files?path=src/main/java&project=myproject&limit=50"
```

### System Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/system/health` | CPU, memory, disk, connections |
| `GET` | `/api/system/query-stats` | Query statistics (avg/p95 latency, cache hits) |

### Documentation Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/docs/tree` | Documentation tree (folders, docs, H2 chapters) |
| `GET` | `/api/docs/content?path=...` | Raw markdown of a document |
| `GET` | `/api/docs/file?path=...` | Static asset (images, etc.) |

---

## Chat API

The chat API provides an LLM-powered assistant with RAG enrichment and agentic tool calling.

### Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/chat/send` | Send a message, get a response (JSON) |
| `POST` | `/api/chat/send` (Accept: `text/event-stream`) | SSE streaming with phase feedback |
| `POST` | `/api/chat/projects` | List available projects for chat |
| `POST` | `/api/chat/tools` | List available tools |

### Request Body

```json
{
  "message": "How does the authentication flow work?",
  "chatModelName": "default",
  "projectContext": "myproject",
  "useTools": true,
  "messages": [
    {"role": "user", "content": "What is the entry point?"},
    {"role": "assistant", "content": "The entry point is Main.java..."}
  ]
}
```

| Field | Type | Description |
|-------|------|-------------|
| `message` | string | The user's question |
| `chatModelName` | string | Stereo type of the chat model (e.g. `"default"`) |
| `projectContext` | string | Project name for RAG enrichment (optional) |
| `useTools` | boolean | Enable agentic tool calling (default `false`) |
| `messages` | array | Conversation history for multi-turn context |

### Response

```json
{
  "id": "uuid",
  "role": "assistant",
  "content": "The authentication flow starts in AuthController...",
  "ragContext": [
    {"projectName": "myproject", "chunkId": "abc123", "snippet": "...", "score": 0.92}
  ],
  "toolCalls": [
    {"toolName": "vectorSearch", "arguments": {"userQuestion": "..."}, "result": "..."}
  ]
}
```

### SSE Streaming Events

When `Accept: text/event-stream` is set, the server emits:

| Event Name | Data |
|------------|------|
| `phase` | `{"phase":"rag_search","message":"RAG-Suche laeuft..."}` |
| `phase` | `{"phase":"llm_generating","message":"LLM generiert Antwort..."}` |
| `phase` | `{"phase":"tools","message":"Executing 2 tool(s)...","toolCalls":[...]}` |
| `phase` | `{"phase":"tool_result","tool":"grep","result":"..."}` |
| `complete` | Full `ChatResponse` JSON |
| `error` | `{"message":"..."}` |

### Tool Calling Protocol

The chat service supports two tool-calling mechanisms:

1. **Native function calling** (preferred): Tool schemas are passed to the LLM provider via the API. The model returns structured `ToolExecutionRequest` objects.
2. **Text protocol fallback** (`TOOL_CALL[tool](params)`): Used when the model does not support native function calling. The server parses `TOOL_CALL[toolName](param="value")` markers from the response.

The agentic loop runs up to **3 tool iterations** per message. Tool results are fed back to the model for a final answer.

---

## Project Management (Hub)

### Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/projects` | List all projects with status, stats, and config |
| `POST` | `/api/projects` | Create a new project |
| `PUT` | `/api/projects/{name}` | Update project configuration |
| `DELETE` | `/api/projects/{name}` | Delete a project |
| `POST` | `/api/projects/{name}/reindex` | Trigger full reindex (202) |
| `POST` | `/api/projects/{name}/docpipe` | DocPipe-only re-run (202) |
| `POST` | `/api/projects/{name}/embed-dependencies` | Trigger dependency materialization (202) |
| `POST` | `/api/projects/{name}/reimport` | Re-materialize a library (202) |
| `POST` | `/api/projects/{name}/update-github` | Git pull + re-embed (202) |
| `POST` | `/api/projects/import-github` | Import a GitHub repo (202) |
| `GET` | `/api/projects/github-operations` | Recent async GitHub operations |
| `GET` | `/api/projects/{name}/reindex-status` | Current reindex status + progress |
| `POST` | `/api/projects/{name}/importEmbeddings` | Upload pre-embedded JSONL |
| `POST` | `/api/projects/upload-archive` | Upload a tar archive |
| `POST` | `/api/projects/upload-embedded` | Upload a pre-embedded archive |
| `GET` | `/api/projects/live-scan` | Scan import dir for live project candidates |
| `GET` | `/api/projects/{name}/stats` | Detailed project statistics |

### Project Lifecycle

```
IMPORTING → CHUNKING → EMBEDDING → READY
                ↓           ↓
             FAILED      FAILED
```

- **SNAPSHOT** projects: extracted from tar into `projects-root`. Deletion removes the directory.
- **LIVE** projects: registered in-place from `import-dir` (user-owned tree). Deletion is temporary (marker survives, poller re-discovers).
- **GitHub** projects: cloned into `github-root`. Deletion removes the clone.
- **Library** projects: materialized into `libraries-root` (Maven-repo layout).

### Project Info Response

```json
{
  "name": "myproject",
  "status": "READY",
  "kind": "LIVE",
  "lastError": null,
  "chunkCount": 15234,
  "embeddingCount": 15234,
  "fileCount": 842,
  "rootDir": "/import/myproject",
  "jsonlFile": "/data/chunks/myproject.jsonl",
  "chunoWhiteList": "java,ts",
  "tibedBatchSize": 32,
  "embeddingsFile": null,
  "tibedResetStore": false,
  "reindexStatus": "COMPLETE",
  "reindexProgress": 100,
  "prompt": null,
  "fulltext": false,
  "liveUpdate": {
    "status": "IDLE",
    "lastGeneratedAt": 1700000000000,
    "nextScanAt": 1700003000000,
    "progress": null
  },
  "embedDependencies": false,
  "mavenDependency": null,
  "gitUrl": null,
  "gitTokenPresent": false
}
```

### Create Project

```bash
curl -X POST http://localhost:8080/api/projects \
  -H "Content-Type: application/json" \
  -d '{
    "name": "myproject",
    "kind": "SNAPSHOT",
    "rootDir": "/path/to/source",
    "chunoWhiteList": "java,ts",
    "tibedBatchSize": 32,
    "fulltext": false
  }'
```

### Reindex

```bash
curl -X POST http://localhost:8080/api/projects/myproject/reindex
# → 202 Accepted (pipeline runs asynchronously)

# Poll status:
curl http://localhost:8080/api/projects/myproject/reindex-status
# → {"status":"EMBEDDING","progress":45}
```

### Import GitHub

```bash
curl -X POST http://localhost:8080/api/projects/import-github \
  -H "Content-Type: application/json" \
  -d '{"url": "https://github.com/owner/repo.git", "token": null}'
# → 202 Accepted

# Poll operations:
curl http://localhost:8080/api/projects/github-operations
# → [{"key":"https://github.com/owner/repo.git","status":"SUCCESS","message":"Imported repo","timestamp":...}]
```

---

## Setup Wizard

The setup wizard guides first-time configuration of embedding, chat, GitHub, and optimization settings. All endpoints use **optimistic concurrency** via the `If-Match` header (ETag = config version).

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

### Setup Flow

```
1. GET /api/setup/status          → learn which step to resume
2. GET /api/setup/configuration   → get current config + configVersion
3. POST /api/setup/test-embedding → verify embedding model (returns testId)
4. POST /api/setup/save           → save with If-Match + X-Setup-Test-Id
5. POST /api/setup/test-chat      → verify chat model
6. POST /api/setup/save           → save chat
7. POST /api/setup/test-github    → verify GitHub token (optional)
8. POST /api/setup/save           → save GitHub (or skip)
9. POST /api/setup/save           → save optimization
10. POST /api/setup/complete      → finalize
```

### Save Request

```json
{
  "configVersion": "abc123def456",
  "step": "embedding",
  "skip": false,
  "testId": "token-from-test",
  "embedding": {
    "type": "OLLAMA",
    "apiBaseURL": "http://localhost:11434",
    "apiKey": null,
    "modelName": "nomic-embed-text",
    "dimensions": 768,
    "timeout": 60,
    "maxSegmentsPerBatch": 32
  }
}
```

Headers:
- `If-Match: abc123def456` (config version from the GET response)
- `X-Setup-Test-Id: <token>` (from the test endpoint)

### Error Responses

```json
{
  "code": "CONFIG_CHANGED",
  "message": "Configuration changed; reload before saving",
  "step": null
}
```

| Code | HTTP | Meaning |
|------|------|---------|
| `CONFIG_CHANGED` | 409 | Config was modified since the version you sent |
| `SETUP_NOT_VERIFIED` | 409 | A matching successful test is required |
| `INDEX_REBUILD_REQUIRED` | 409 | Vector dimension mismatch |
| `MISSING_SECRET` | 400 | Required environment variable is not set |
| `INVALID_REQUEST` | 400 | Malformed request |
| `HUB_DISABLED` | 409 | Hub is not enabled |
| `KEY_ROTATION_REQUIRED` | 409 | Token key name changed with existing tokens |

---

## MCP Protocol Details

The MCP endpoint is at `POST /mcp` and speaks **JSON-RPC 2.0** with protocol version `2025-06-18`.

### Initialize

```json
// Request
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"capabilities":{}}}

// Response
{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18","capabilities":{"tools":{"listChanged":false}}}}
```

### Discover (Legacy Compatibility)

The `McpCompatibilityFilter` intercepts `server/discover` requests and returns the supported protocol version and capabilities (tools, prompts, resources, completions) based on which `@McpTool`/`@McpPrompt`/`@McpResource`/`@McpComplete` annotated methods exist in the application context.

### Tool List

```json
{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
```

Returns all 14 tools with their JSON Schema parameter definitions.

### Tool Call

```json
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"grep","arguments":{"query":"createMClass","project":"myproject"}}}
```

### Compatibility Filter

The `McpCompatibilityFilter` (active in hub mode) handles:
- `server/discover` → returns supported versions + capabilities
- `initialize` → strips the `elicitation` capability (not supported)
- Buffers the request body for re-reading by the downstream MCP handler

---

## Search Strategy

The recommended 3-stage search ladder for AI agents:

```
1. vectorSearch  →  Broad semantic overview ("where is X?")
       ↓
2. grep          →  Exact text narrowing ("find the specific identifier")
       ↓
3. readFile / readBySignature  →  Read the actual source
```

**Orthogonal to the ladder:**
- `listFiles` — structural discovery by path pattern
- `graphFile` / `graphNode` — dependency/relationship exploration
- `graphClusterOverview` — architecture exploration from scratch

**Tips:**
- Use `sourceType=all` in vectorSearch if code search returns nothing (the answer may be in prose documentation)
- Use `contentLevel=NAMES` in grep for a cheap "which files contain X" answer
- Use `contentLevel=AGGREGATED` in graph tools for a module-level summary instead of raw edges
- Use `readChunks` to explore neighbours listed in a vectorSearch result without re-searching

---

## Content Levels

The `contentLevel` parameter controls how much content is returned per hit:

| Level | vectorSearch | grep | graphFile/graphNode |
|-------|-------------|------|-------------------|
| `POINTERS` (default) | File hints + follow-up suggestions | Enumerates matches | — |
| `SKELETON` | Class/method signatures | Signatures | — |
| `FULL` | Complete method bodies | Full matching content | All raw edges |
| `NAMES` | Filenames only | `count  path` per file (grep -l) | — |
| `AGGREGATED` | — | Alias for NAMES | Per-module edge counts + top classes |

---

## Live Updates

LIVE projects (registered from `import-dir` with a `prjxp.yaml` marker) support adaptive polling for incremental updates:

- **Base interval**: 5 minutes (configurable)
- **Backoff**: Doubled after each empty scan, capped at 60 minutes
- **Reset**: Returns to base interval when changes are detected
- **Skip**: While a pipeline run or reindex is actively executing
- **Rescan**: Forced within 30s after a run completes (catches files modified during a long run)

The scanner compares on-disk file state (mtime + size) against a persisted source-state file. Files that changed are re-embedded; files deleted from disk are removed from the index. The old index remains searchable throughout (keep-old-index semantics).

Per-project opt-out: set `liveUpdate: false` in the project's `prjxp.yaml`.

---

## GitHub Integration

### Import

```bash
# Public repo
curl -X POST http://localhost:8080/api/projects/import-github \
  -d '{"url":"https://github.com/owner/repo.git"}'

# Private repo (token is encrypted at rest)
curl -X POST http://localhost:8080/api/projects/import-github \
  -d '{"url":"https://github.com/owner/private.git","token":"ghp_xxx"}'
```

The import:
1. Shallow-clones (`--depth 1`) into `github-root/<name>`
2. Generates a `prjxp.yaml` with the `gitUrl` origin marker
3. Registers the project (source `"github"`, kind `SNAPSHOT`)
4. Enqueues the pipeline

### Update

```bash
curl -X POST http://localhost:8080/api/projects/repo/update-github
# → 202 (git pull + forced re-embed on a dedicated executor)
```

### Token Security

- PATs are encrypted with **AES-256-GCM** (PBKDF2 key derivation, 200k iterations)
- Ciphertext format: `v1:<b64salt>:<b64iv>:<b64ct+tag>`
- The encryption key passphrase is read from the env var named by `prjxp.hub.github-token-key-env`
- The plaintext token is **never** persisted or logged
- Git operations pass the token via `http.extraheader` (never in the URL)

---

## Dependency Embedding

Projects with `embedDependencies: true` in their `prjxp.yaml` trigger automatic materialization of their dependencies as independent library projects:

1. **Maven** (`pom.xml`): BFS over the dependency tree (bounded by `dependencyDepth`, default 2). Test-scope excluded.
2. **Gradle** (`build.gradle` / `build.gradle.kts`): Runs `gradle dependencies` (or falls back to regex parsing).

Each embeddable dependency (has `-sources` or `-javadoc` on Maven Central) is:
- Downloaded and extracted into `libraries-root/<g-as-path>/<a>/<v>/{src,doc}`
- Registered as a SNAPSHOT project with source `"library"`
- Enqueued for the standard pipeline

Manual trigger: `POST /api/projects/{name}/embed-dependencies`
Re-import a library: `POST /api/projects/{name}/reimport`

---

## Documentation API

The docs API serves the project's documentation tree from `prjxp.docs.dir` (default `mcp-server/doc`):

```bash
# Full tree (folders, .md files, H2 chapters)
curl http://localhost:8080/api/docs/tree

# Read a document
curl "http://localhost:8080/api/docs/content?path=guides/getting-started.md"

# Read an asset (image)
curl "http://localhost:8080/api/docs/file?path=images/architecture.png"
```

The tree structure:
```json
{
  "name": "doc",
  "type": "root",
  "children": [
    {"name": "guides", "type": "folder", "children": [
      {"name": "Getting Started", "type": "doc", "path": "guides/getting-started.md",
       "chapters": [{"title": "Installation", "anchor": "installation"}, ...]}
    ]}
  ]
}
```

Path traversal (`..`) and absolute paths are rejected.

---

## CORS

CORS is configured via `mcp.cors.allowed-patterns` (comma-separated, default `http://localhost:*`):

```yaml
mcp:
  cors:
    allowed-patterns: "http://localhost:*,https://myapp.example.com"
```

Applied to:
- `/api/**` — GET, POST, PUT, DELETE, OPTIONS; credentials allowed; `ETag` exposed
- `/mcp` — GET, POST, OPTIONS; credentials allowed
- `/v3/api-docs/**` — GET

---

## Project Configuration File (`prjxp.yaml`)

Each project directory contains a `prjxp.yaml` (or `prjxp.yml`) marker file:

```yaml
name: myproject
rootDir: .
jsonlFile: px-chunks.jsonl
chunoWhiteList: java,ts
excludeDirNames: node_modules,.git,target
sourceDirNames: src
tibedBatchSize: 32
fulltext: false
liveUpdate: true
# prompt: "Focus on the payment module when answering."
# embedDependencies: true
# dependencyDepth: 2
# dependencyWhitelist: com.example,org.springframework
# gitUrl: https://github.com/owner/repo.git
# gitToken: "v1:..."   # encrypted
# mavenDependency: "com.example:lib:1.0.0"   # library origin marker
# embeddingsFile: myproject-embedded.jsonl   # pre-embedded
```

Special marker files:
- `.ready` — signals a pre-embedded project is ready for import
- `.prjxp-exclude` — tells the hub to ignore this directory entirely

---

## CLI Mode

When `prjxp.hub.enabled` is not `true`, the `CliModeEnvironmentPostProcessor` sets `spring.main.web-application-type=none` (lowest priority — an explicit user setting wins). The bundled CLI runners execute and the process exits. No embedded web server starts.

---

## Architecture Notes

- **Single Lucene index**: All projects share one Lucene store, scoped by the `pxchunk_project` field. Multi-project separation is via metadata filters.
- **Single pipeline worker**: The `PipelineOrchestrator` uses a single-thread FIFO executor (Lucene allows one writer). Projects are processed sequentially.
- **Keep-old-index semantics**: During reindex, the old index stays in place (project remains searchable) until the final swap (scoped `removeAll` + batched import).
- **In-memory stats cache**: Project stats (chunk count, file count, watermark) are cached and refreshed after pipeline runs + periodically (60s default).
- **Source-based vanish policy**: Each project source (snapshot, live, library, github) has its own vanish policy. A vanished live project is unregistered AND wiped; a vanished snapshot is unregistered only.
- **Secrets**: API keys and tokens support `${ENV_VAR}` references. The setup wizard migrates plaintext secrets to env-file entries on save.

_This document was generated with Doc|Pipe_

