# prjxp mcp-server

A Spring Boot application that exposes a **Model Context Protocol (MCP)** server and a **REST API** for searching, reading, and managing indexed software projects. It provides semantic vector search, full-text search, deterministic symbol lookup, file reading, and project lifecycle management — all backed by a shared Lucene embedding store.

---

## Table of Contents

- [Overview](#overview)
- [Two Operating Modes](#two-operating-modes)
- [Getting Started](#getting-started)
- [Configuration](#configuration)
- [MCP Tools](#mcp-tools)
- [REST API](#rest-api)
- [Search Strategy (3-Stage Ladder)](#search-strategy-3-stage-ladder)
- [Project Management (Hub Mode)](#project-management-hub-mode)
- [Chat with RAG & Tools](#chat-with-rag--tools)
- [MCP Protocol Endpoint](#mcp-protocol-endpoint)
- [Documentation Endpoint](#documentation-endpoint)
- [System & Monitoring](#system--monitoring)
- [CORS](#cors)
- [Project Configuration File (prjxp.yaml)](#project-configuration-file-prjxpyaml)
- [Live Updates](#live-updates)
- [GitHub Import](#github-import)
- [Library (Maven Dependency) Embedding](#library-maven-dependency-embedding)
- [Pre-Embedded Archive Import](#pre-embedded-archive-import)
- [DocPipe Documentation](#docpipe-documentation)
- [Security](#security)

---

## Overview

The mcp-server indexes source code and documentation of software projects into a shared Lucene embedding store and exposes them through:

| Interface | Purpose |
|-----------|---------|
| **MCP tools** (`/mcp`) | Native function-calling for AI agents (JSON-RPC) |
| **REST API** (`/api/**`) | HTTP endpoints for search, project management, chat, and monitoring |
| **CLI** (hub disabled) | Bundled CLI runners execute and the process exits |

The server is a single Spring Boot application. All web/MCP/REST beans are conditional on `prjxp.hub.enabled=true`.

---

## Two Operating Modes

| Mode | Trigger | Behavior |
|------|---------|----------|
| **CLI** (default) | `prjxp.hub.enabled` is `false` or unset | No web server starts (`spring.main.web-application-type=none`). Bundled CLI runners execute and the process exits. |
| **Hub** | `prjxp.hub.enabled=true` | Embedded web server starts. All MCP tools, REST controllers, project lifecycle, live updates, and the pipeline orchestrator are active. |

The `CliModeEnvironmentPostProcessor` automatically sets `spring.main.web-application-type=none` as a lowest-priority default when hub mode is not explicitly enabled. An explicit user setting always wins.

---

## Getting Started

### Prerequisites

- Java 17+
- A running embedding model (e.g. Ollama, OpenAI-compatible API)
- `git` on PATH (for GitHub import)
- `gradle` on PATH (optional, for Gradle dependency resolution)

### Configuration

Create or edit `application.yaml`:

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
    github-root: /github
    github-jsonl-dir: /data/github-chunks
    libraries-root: /libraries

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

mcp:
  cors:
    allowed-patterns: "http://localhost:*"
```

### Run

```bash
java -jar mcp-server.jar
```

Or with a `.env` file (read by `PrjXPCli.readDotEnv`):

```bash
java -jar mcp-server.jar --spring.config.location=./application.yaml
```

### Verify

```bash
curl http://localhost:8080/api/tools/ping
# → "pong!"
```

---

## Configuration

All hub properties are under `prjxp.hub.*` (bound to `HubProperties`):

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `false` | Master switch for all hub beans |
| `import-dir` | `/import` | Directory watched for tar/embedded uploads |
| `projects-root` | `/projects` | Root for extracted snapshot projects |
| `live-jsonl-dir` | `/data/chunks` | Hub-managed JSONL output for live projects |
| `poll-interval-ms` | `5000` | Import poller interval |
| `live-update-enabled` | `true` | Master switch for live updates |
| `live-update-base-interval-ms` | `300000` | Base adaptive polling interval (5 min) |
| `live-update-max-interval-ms` | `3600000` | Max adaptive polling interval (60 min) |
| `stats-refresh-interval-ms` | `60000` | Periodic in-memory stats cache refresh |
| `max-tar-bytes` | `2 GiB` | Max tar file size |
| `max-extracted-bytes` | `4 GiB` | Max total extracted size (compression-bomb guard) |
| `max-entries` | `100000` | Max tar entries |
| `scan-exclude-dir-names` | `.git,.svn,.hg,node_modules` | Directories the marker scan never enters |
| `scan-max-depth` | `8` | Max recursion depth for live-project discovery |
| `compatibility-sample-size` | `4` | Chunks sampled for embedding compatibility check |
| `compatibility-threshold-high` | `0.995` | Min avg cosine for COMPATIBLE |
| `compatibility-threshold-low` | `0.975` | Min avg cosine for PARTIALLY_COMPATIBLE |
| `libraries-root` | `/libraries` | Root for materialized library projects |
| `maven-repositories` | `https://repo1.maven.org/maven2` | Comma-separated Maven repo base URLs |
| `max-dependency-bytes` | `100 MiB` | Max downloaded artifact size |
| `maven-connect-timeout-seconds` | `10` | HTTP connect timeout for Maven |
| `maven-request-timeout-seconds` | `60` | HTTP request timeout for Maven |
| `gradle-timeout-seconds` | `300` | Timeout for `gradle dependencies` |
| `github-root` | `/github` | Root for cloned GitHub projects |
| `github-jsonl-dir` | `/data/github-chunks` | Hub-managed JSONL for GitHub projects |
| `github-clone-depth` | `1` | Shallow-clone depth |
| `github-timeout-seconds` | `300` | Timeout for `git clone`/`git pull` |
| `github-token-key-env` | `PRJXP_HUB_GITHUB_TOKEN_KEY` | Env-var name for the token-encryption key |

CORS is configured via `mcp.cors.allowed-patterns` (comma-separated, default `http://localhost:*`).

---

## MCP Tools

Six tools are registered in the MCP server (via `@McpTool` annotations on `PrjxpMcpTool`) and also in the central `ToolRegistry` for native function calling in the chat service.

### Search Ladder

```
vectorSearch (discovery) → grep (exact string) → readBySignature / readFile (complete content)
```

Orthogonal to the ladder: `listProjects` (which projects exist) and `listFiles` (which files exist).

---

### `vectorSearch`

**Level 1 of 3 — Semantic discovery.**

Delivers relevant semantic information from the project context. Always call this first for discovery: finding classes, understanding structure, locating implementations.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `userQuestion` | string | **Yes** | A targeted, standalone search prompt optimized for vector retrieval |
| `projectName` | string | No | Optional project name to narrow the scope |
| `similarity` | number | No | Similarity threshold 0.0–1.0 (default 0.85). Higher = more precise |
| `maxResults` | integer | No | Maximum results (default 20, max 20) |
| `skeletonsOnly` | boolean | No | `true` = class skeletons only; `false` (default) = full method context on hits |

**Similarity guide:** 1.0–0.9 very precise · 0.9–0.8 precise · 0.8–0.7 balanced · <0.7 low precision

**Result:** Method implementations (when a method chunk is hit and `skeletonsOnly=false`) or class skeletons with imports for an architectural overview.

---

### `grep`

**Level 2 of 3 — Exact full-text search.**

Exact full-text search over all chunks of a project. Use the exact method signature as query to retrieve its full body.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `query` | string | **Yes** | Exact search string (e.g. `"public MClass createMClass"`) |
| `project` | string | No | Optional project name (default: active project) |
| `language` | string | No | Optional language filter (e.g. `"java"`, `"ts"`) |
| `limit` | integer | No | Max results (default 10, max 100) |

**Result:** List of `SearchHit` objects with file, line range, snippet, and chunk ID.

---

### `readFile`

**Level 3 of 3 — Complete file view.**

Returns a complete source view of one file, reconstructed from the project index. Use when you need the entire file instead of snippets or skeletons.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | **Yes** | File path — absolute, project-root-relative, or just the file name |
| `project` | string | No | Optional project name |
| `offset` | integer | No | 1-based line number to start reading from |
| `limit` | integer | No | Maximum number of lines to return |

**Path resolution:** exact → suffix → basename. If ambiguous, the result contains a `candidates` list.

**Result:** `FileView` with `content`, `truncated` (100k char cap), `totalLines`, `returnedLines`, `chunkCount`, `candidates`, `error`.

---

### `readBySignature`

**Level 3 of 3 — Complete symbol by name.**

Returns the complete implementation of a symbol (javadoc + full method body). Java only in this version.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `method` | string | **Yes** | Method name (e.g. `"createMClass"`) or FQN (`"com.example.Foo#bar"`) |
| `container` | string | No | Containing class FQN to disambiguate a bare method name |
| `project` | string | No | Optional project name |

**Result:** Up to 10 full matches (FQN, file, line range, javadoc, body). If more than 10 match, `remainingFqns` lists the rest — call again with one of them. Class FQNs return the class frame.

---

### `listProjects`

**Discovery tool.** Lists all projects with a searchable index, with the timestamp of each project's last index update.

No parameters.

**Result:** List of `ProjectSummary` (name + `lastGeneratedAt` epoch millis). Pass the `name` verbatim as the `project` argument of search tools.

---

### `listFiles`

**Structural discovery (orthogonal to the search ladder).** Lists which files exist in the project index.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `path` | string | No | Path prefix or glob. `*` matches any characters including `/` (recursive). Empty = all files |
| `project` | string | No | Optional project name |
| `limit` | integer | No | Max files (default 50, max 500) |

**Result:** `FileListResult` with sorted file paths and a `truncated` flag.

---

## REST API

All endpoints are active only in hub mode (`prjxp.hub.enabled=true`).

### Search Tools

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/tools/vectorSearch` | Semantic vector search |
| `GET` | `/api/tools/grep` | Full-text search |
| `GET` | `/api/tools/byIndex` | Deterministic symbol search over chunk index |
| `GET` | `/api/tools/files` | List files in the project index |
| `GET` | `/api/tools/meta-search-params` | Machine-readable search parameters |
| `GET` | `/api/tools/ping` | Health check (returns `"pong!"`) |
| `GET` | `/api/tools/context` | RAG context retrieval (legacy) |
| `GET` | `/api/tools/projects` | List all projects with lifecycle status |

#### `GET /api/tools/vectorSearch`

```
GET /api/tools/vectorSearch?query=authentication+filter&project=myapp&language=java&limit=10
```

| Param | Required | Default | Description |
|-------|----------|---------|-------------|
| `query` | **Yes** | — | Semantic search query |
| `project` | No | `default` | Project name |
| `language` | No | — | Language filter |
| `limit` | No | `10` | Max results (max 100) |

#### `GET /api/tools/grep`

```
GET /api/tools/grep?query=public+MClass+createMClass&project=myapp&language=java&limit=10
```

| Param | Required | Default | Description |
|-------|----------|---------|-------------|
| `query` | **Yes** | — | Exact search string |
| `project` | No | `default` | Project name |
| `language` | No | — | Language filter |
| `limit` | No | `10` | Max results (max 100) |

#### `GET /api/tools/byIndex`

```
GET /api/tools/byIndex?language=java&fqn=com.example.Foo#bar&limit=10
```

| Param | Required | Default | Description |
|-------|----------|---------|-------------|
| `language` | **Yes** | — | Language (e.g. `java`) |
| `fqn` | * | — | Fully qualified name |
| `symbolType` | * | — | Symbol type filter |
| `methodName` | * | — | Method name |
| `signatureHash` | * | — | Signature hash |
| `containerFqn` | * | — | Containing class FQN |
| `project` | No | `default` | Project name |
| `limit` | No | `10` | Max results (max 100) |

\* At least one of `fqn`, `methodName`, `signatureHash`, `containerFqn` is required.

#### `GET /api/tools/files`

```
GET /api/tools/files?path=frontend/src&project=myapp&limit=50
```

| Param | Required | Default | Description |
|-------|----------|---------|-------------|
| `path` | No | `""` | Path prefix or glob |
| `project` | No | `default` | Project name |
| `limit` | No | `50` | Max files (max 500) |

#### `GET /api/tools/meta-search-params`

Returns a machine-readable description of all valid search parameters (global + per-language). Call this once before using language-specific parameters.

---

### Project Management

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/projects` | List all projects with lifecycle status and stats |
| `POST` | `/api/projects` | Create a new project |
| `PUT` | `/api/projects/{name}` | Update project configuration |
| `DELETE` | `/api/projects/{name}` | Delete a project |
| `POST` | `/api/projects/{name}/reindex` | Trigger full pipeline re-run |
| `POST` | `/api/projects/{name}/docpipe` | DocPipe-only re-run |
| `POST` | `/api/projects/{name}/embed-dependencies` | Trigger dependency materialization |
| `POST` | `/api/projects/{name}/reimport` | Re-materialize a library project |
| `POST` | `/api/projects/{name}/update-github` | Update a GitHub project (git pull + re-embed) |
| `POST` | `/api/projects/import-github` | Import a GitHub repository |
| `GET` | `/api/projects/{name}/reindex-status` | Get reindex status and progress |
| `GET` | `/api/projects/{name}/stats` | Get detailed project statistics |
| `GET` | `/api/projects/live-scan` | Scan import directory for live project candidates |
| `GET` | `/api/projects/github-operations` | Recent async GitHub operations |
| `POST` | `/api/projects/upload-archive` | Upload a TAR/ZIP archive |
| `POST` | `/api/projects/upload-embedded` | Upload a pre-embedded archive |
| `POST` | `/api/projects/{name}/importEmbeddings` | Import pre-computed embeddings (JSONL upload) |

#### Project Lifecycle

```
IMPORTING → CHUNKING → EMBEDDING → READY
                ↓           ↓
             FAILED      FAILED
```

- **SNAPSHOT** projects: extracted from tar into `projects-root`. Deletion removes the directory.
- **LIVE** projects: registered in place from `import-dir`. Deletion is temporary (the marker file survives; the poller re-discovers it).

#### `POST /api/projects`

```json
{
  "name": "myapp",
  "kind": "LIVE",
  "rootDir": "/path/to/source",
  "chunoWhiteList": "java,ts",
  "tibedBatchSize": 32,
  "fulltext": false
}
```

#### `PUT /api/projects/{name}`

```json
{
  "rootDir": "/new/path",
  "chunoWhiteList": "java,ts,md",
  "tibedBatchSize": 64,
  "prompt": "Focus on the API layer.",
  "fulltext": true,
  "embedDependencies": true
}
```

#### `POST /api/projects/import-github`

```json
{
  "url": "https://github.com/owner/repo.git",
  "token": "ghp_xxx"
}
```

The token is optional (public repos). If provided, it is encrypted at rest (AES-256-GCM) and never persisted in plaintext.

---

### Chat with RAG & Tools

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/chat/send` | Send a chat message (JSON response) |
| `POST` | `/api/chat/send` (Accept: `text/event-stream`) | Send a chat message (SSE streaming) |
| `POST` | `/api/chat/projects` | List available projects for RAG |
| `POST` | `/api/chat/tools` | List available tools |

#### `POST /api/chat/send`

```json
{
  "message": "How does the authentication flow work?",
  "chatModelName": "main",
  "projectContext": "myapp",
  "useTools": true,
  "messages": [
    {"role": "user", "content": "What does the login endpoint do?"},
    {"role": "assistant", "content": "It validates the token..."}
  ]
}
```

| Field | Required | Description |
|-------|----------|-------------|
| `message` | **Yes** | The user's question |
| `chatModelName` | **Yes** | Stereo-type of the configured chat model |
| `projectContext` | No | Project name for RAG enrichment |
| `useTools` | No | Enable agentic tool loop (default `false`) |
| `messages` | No | Conversation history for multi-turn context |

**Response:**

```json
{
  "id": "uuid",
  "role": "assistant",
  "content": "The authentication flow works as follows...",
  "ragContext": [
    {"projectName": "myapp", "chunkId": "abc123", "snippet": "...", "score": 0.92}
  ],
  "toolCalls": [
    {"toolName": "vectorSearch", "arguments": {"userQuestion": "..."}, "result": "..."}
  ]
}
```

When `useTools=true`, the service runs an agentic loop (max 3 iterations) using native function calling (or a text-protocol fallback for models that don't support it). The LLM can call `vectorSearch`, `grep`, `readFile`, `readBySignature`, `listProjects`, and `listFiles` autonomously.

---

## Search Strategy (3-Stage Ladder)

The recommended workflow for AI agents and developers:

```
┌─────────────────────────────────────────────────────────────────────┐
│  1. vectorSearch  →  Semantic discovery (broad overview)           │
│         ↓                                                          │
│  2. grep          →  Exact text match (confirm, locate)            │
│         ↓                                                          │
│  3. readBySignature / readFile  →  Complete content                │
└─────────────────────────────────────────────────────────────────────┘

Orthogonal:
  listProjects  →  Which projects are available?
  listFiles     →  Which files exist in the index?
```

**Rules:**
1. **Always** call `vectorSearch` first for discovery.
2. Use `grep` with the exact method signature for full method bodies.
3. Use `readFile` for the entire file, `readBySignature` for a specific method/class.
4. Use `listFiles` to explore the file tree before reading.
5. Use `listProjects` when you don't know valid project names.
6. If a tool returns no results, try alternative search terms.

---

## Project Management (Hub Mode)

### Importing Projects

**Tar archive (snapshot):**
Drop a `.tar`, `.tgz`, or `.tar.gz` file into the `import-dir`. The poller extracts it into `projects-root/<name>` and enqueues the pipeline.

**Pre-embedded archive:**
Drop a `<Name>-embedded.(jsonl|gz|tgz|zip)` file into the `import-dir`. The importer validates the naming scheme, extracts the JSONL, updates `prjxp.yaml`, writes a `.ready` marker, and enqueues the pre-embedded pipeline.

**Live project:**
Place a `prjxp.yaml` (or `prjxp.yml`) marker file in a directory under `import-dir`. The recursive marker walk discovers it and registers it as a LIVE project.

**GitHub:**
`POST /api/projects/import-github` with a git URL. The hub shallow-clones into `github-root/<name>`, generates a `prjxp.yaml` with the `gitUrl` origin marker, and enqueues the pipeline.

### Pipeline

A single FIFO worker thread runs the pipeline per project:

```
Chunking (0-10%) → Embedding (10-80%) → Swap/Import (80-100%)
```

- **Keep-old-index semantics:** The old index stays in place (project stays searchable) until the new embeddings are fully computed. Only the final swap is briefly offline.
- **Failure:** If the old index is intact (failure before swap), the project stays READY with the old index. Otherwise FAILED.
- **Pre-embedded:** Skips chunking; validates embedding compatibility, then swaps.

---

## Chat with RAG & Tools

(See [Chat with RAG & Tools](#chat-with-rag--tools) above.)

The chat service:
1. Resolves the LLM by `chatModelName` (stereo-type).
2. Builds an enriched prompt with system context, available projects, project/model standing instructions, and RAG context (vector search over the project's chunks).
3. Optionally runs an agentic tool loop (native function calling or text-protocol fallback).
4. Returns the response with RAG context and tool call records.

---

## MCP Protocol Endpoint

The `/mcp` endpoint speaks the Model Context Protocol (JSON-RPC 2.0).

- **Protocol version:** `2025-06-18`
- **`McpCompatibilityFilter`** intercepts POST requests to `/mcp`:
  - `server/discover` → returns supported versions and capabilities (tools, prompts, resources, completions) based on registered `@McpTool`/`@McpPrompt`/`@McpResource`/`@McpComplete` annotations.
  - `initialize` → strips the `elicitation` capability from the client's capabilities object (compatibility shim).
  - All other messages pass through to the MCP endpoint layer.

Connect an MCP-compatible client (e.g. an AI IDE or agent framework) to `http://localhost:8080/mcp`.

---

## Documentation Endpoint

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/docs/tree` | Documentation tree (folders, markdown docs, H2 chapters) |
| `GET` | `/api/docs/content?path=...` | Raw markdown of a document |
| `GET` | `/api/docs/file?path=...` | Static asset (images, etc.) |

The docs root is configured via `prjxp.docs.dir` (default `mcp-server/doc`). The tree is built by scanning `.md` files, extracting H1 titles and H2 chapters (with deduplicated slugs). Path traversal is prevented.

---

## System & Monitoring

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/system/health` | CPU, memory, disk, active connections |
| `GET` | `/api/system/query-stats` | Query statistics (total, avg/p95 response time, cache hits/misses, recent queries) |

---

## CORS

Configured via `mcp.cors.allowed-patterns` (comma-separated Ant-style patterns, default `http://localhost:*`). Applied to:

- `/api/**` — GET, POST, PUT, DELETE, OPTIONS; credentials allowed
- `/mcp` — GET, POST, OPTIONS; credentials allowed
- `/v3/api-docs/**` — GET

---

## Project Configuration File (prjxp.yaml)

Each project directory contains a `prjxp.yaml` (or `prjxp.yml`) marker file:

```yaml
name: myapp
rootDir: .
jsonlFile: px-chunks.jsonl
chunoWhiteList: java,ts
excludeDirNames: node_modules,.git,target
sourceDirNames: src
tibedBatchSize: 32
fulltext: false
liveUpdate: true
prompt: |
  Focus on the API layer and data access.
embedDependencies: true
dependencyDepth: 2
dependencyWhitelist: com.example
# --- Origin markers (set by the hub, not by the user) ---
# mavenDependency: com.example:lib:1.0.0
# gitUrl: https://github.com/owner/repo.git
# gitToken: v1:<salt>:<iv>:<ciphertext>
# embeddingsFile: myapp-embedded.jsonl
```

| Key | Default | Description |
|-----|---------|-------------|
| `name` | directory name | Project name |
| `rootDir` | `.` | Source root (relative to project dir) |
| `jsonlFile` | `px-chunks.jsonl` | Chunk JSONL output file |
| `chunoWhiteList` | `java,ts` | Comma-separated language whitelist for chunking |
| `excludeDirNames` | built-in defaults | Directories to skip |
| `sourceDirNames` | built-in defaults | Directories to include |
| `tibedBatchSize` | `32` | Embedding batch size |
| `fulltext` | `false` | Store a lossless fulltext copy (for `readFile`) |
| `liveUpdate` | `true` | Enable live updates (LIVE projects) |
| `prompt` | — | Standing instructions for the chat model |
| `embedDependencies` | `false` | Materialize Maven/Gradle dependencies as library projects |
| `dependencyDepth` | `2` | Max transitive depth for dependency resolution |
| `dependencyWhitelist` | `""` | GroupId prefix whitelist (empty = all) |
| `embeddingsFile` | — | Pre-embedded JSONL filename (triggers pre-embedded pipeline) |
| `mavenDependency` | — | Origin marker for hub-generated library projects |
| `gitUrl` | — | Origin marker for hub-cloned GitHub projects |
| `gitToken` | — | Encrypted PAT (never plaintext) |

**Marker files:**
- `.prjxp-exclude` — tells the live-project scan to never enter this directory.
- `.ready` — signals a pre-embedded project is ready for import (prevents race conditions during file transfer).

---

## Live Updates

LIVE projects are monitored by an adaptive polling scheduler (30 s tick):

- **Base interval:** 5 minutes (configurable)
- **After an empty scan:** interval doubles (cap 60 min)
- **After changes found:** interval resets to base
- **While a run is in flight or reindex is active:** scan is skipped (interval does NOT double)
- **`rescanRequested` flag:** forces an immediate rescan (≤30 s) after a long run completes

The scanner performs a three-way split:
- **`toEmbed`** — files with `mtime > generatedAt` OR not yet in the index
- **`toDelete`** — in-index files no longer on disk (veto-passing, not docpipe-generated)

On success, the source state is published atomically (`.live-state.json` sidecar). On failure, the old index and old JSONL are kept; the scheduler retries next cycle.

---

## GitHub Import

- **Import:** `POST /api/projects/import-github` with `{"url": "...", "token": "..."}`. Non-blocking (202 Accepted). The clone runs on a dedicated single-thread executor.
- **Update:** `POST /api/projects/{name}/update-github`. Runs `git pull --ff-only` then a forced re-embed.
- **Idempotency:** An existing populated clone (`.git` present) is skipped.
- **Token security:** The PAT is passed to git via `http.extraheader` (never in the URL, never logged). Its encrypted form (AES-256-GCM, PBKDF2 key derivation) is stored in `prjxp.yaml` as `gitToken`.
- **Vanish policy:** A genuinely vanished clone is unregistered AND wiped from the shared index. A missing/unlistable root (volume glitch) skips the sync entirely (never wipes).

---

## Library (Maven Dependency) Embedding

When `embedDependencies: true` in a project's `prjxp.yaml`:

1. The dependency resolver (Maven POM BFS or Gradle `dependencies` output) resolves the dependency tree (bounded by `dependencyDepth`, filtered by `dependencyWhitelist`).
2. Each embeddable dependency (has `-sources` or `-javadoc` classifier on Maven Central) is materialized as an independent SNAPSHOT library project under `libraries-root` in Maven-repo layout.
3. The library is registered (source `"library"`) and enqueued for the standard pipeline.
4. Materialization runs on a dedicated executor (network I/O never on the pipeline worker).

**Reimport:** `POST /api/projects/{name}/reimport` forces a re-download + re-extract of a library.

---

## Pre-Embedded Archive Import

For projects where embeddings were computed externally (e.g. on a GPU cluster):

1. Name the file `<ProjectName>-embedded.(jsonl|gz|tgz|zip)`.
2. Drop it into `import-dir` or upload via `POST /api/projects/upload-embedded`.
3. The importer:
   - Validates the naming scheme and file size.
   - Extracts the JSONL (security-hardened: rejects absolute paths, `..` segments, links; enforces size/entry limits).
   - Validates the first line (non-null vector).
   - Updates `prjxp.yaml` with `embeddingsFile`.
   - Writes the `.ready` marker.
   - Enqueues the pre-embedded pipeline (compatibility check → swap).

**Compatibility check:** Samples N chunks, computes local embeddings, compares cosine similarity. Verdicts: COMPATIBLE (≥ high threshold), PARTIALLY_COMPATIBLE (≥ low threshold), INCOMPATIBLE (blocks import).

---

## DocPipe Documentation

After a root pipeline run finishes, if the project has a `.dp/` configuration (DocPipe's convention):

1. DocPipe generates documentation (markdown files).
2. A manifest file is written listing the generated documents.
3. A document-embedding retrigger run is scheduled: chunks the documents, performs a file-scoped reset (only previously generated document chunks — code chunks are never touched), and embeds them additively.

DocPipe failures are **non-fatal**: the project still becomes READY (the code index is complete).

**Manual trigger:** `POST /api/projects/{name}/docpipe` — regenerates docs + re-embeds documents without re-chunking/re-embedding code.

---

## Security

- **Tar/ZIP extraction:** Rejects absolute paths, `..` segments, symbolic/hard links. Enforces max file size, max extracted size, max entry count. Strips a single common top-level directory. Path-escape validation on every entry.
- **Embedded archive import:** Same security model as tar/zip extraction. Single-entry rule. First-line JSONL validation.
- **GitHub token:** AES-256-GCM encryption (128-bit tag, 12-byte IV, 16-byte salt, PBKDF2WithHmacSHA256 @ 200k iterations). Key from an environment variable (name configurable, value never stored). Passed to git via `http.extraheader`, never in the URL or logs.
- **Docs endpoint:** Path traversal prevention (rejects absolute paths, `..` segments, paths that escape the docs root).
- **CORS:** Configurable origin patterns; credentials allowed only for explicitly listed origins.
- **Maven/Gradle downloads:** Size limits, timeout enforcement, XXE guard in POM parsing (DOCTYPE + external entities disabled).
- **File upload:** File name sanitization (last path segment only; rejects `.` and `..`).

---

## Swagger / OpenAPI

Interactive API documentation is available at:

```
http://localhost:8080/swagger-ui.html
```

All controllers use `@Operation` and `@Parameter` annotations for self-documenting endpoints.

_This document was generated with Doc|Pipe_

