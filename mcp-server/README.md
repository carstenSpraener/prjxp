# prjxp mcp-server

A Spring Boot application that exposes a project's indexed source code and documentation through the **Model Context Protocol (MCP)**, a REST API, and an interactive chat interface. It supports multi-project management with a full lifecycle pipeline (chunking → embedding → indexing), live updates, GitHub imports, and dependency materialization.

---

## Table of Contents

- [Overview](#overview)
- [Two Operating Modes](#two-operating-modes)
- [Prerequisites](#prerequisites)
- [Configuration](#configuration)
- [MCP Tools](#mcp-tools)
- [REST API](#rest-api)
- [Chat API](#chat-api)
- [Project Lifecycle (Hub Mode)](#project-lifecycle-hub-mode)
- [Importing Projects](#importing-projects)
- [Live Updates](#live-updates)
- [GitHub Integration](#github-integration)
- [Dependency Embedding](#dependency-embedding)
- [Documentation API](#documentation-api)
- [System & Monitoring](#system--monitoring)
- [MCP Protocol Endpoint](#mcp-protocol-endpoint)
- [CORS](#cors)
- [Search Strategy (3-Stage Ladder)](#search-strategy-3-stage-ladder)

---

## Overview

The mcp-server indexes entire codebases (Java, TypeScript, and other languages via pluggable language packs) into a shared Lucene-based embedding store. It then exposes that index to AI agents and developers through:

| Interface | Purpose |
|-----------|---------|
| **MCP tools** (`/mcp`) | Native function-calling for AI agents (Claude, Qwen, etc.) |
| **REST API** (`/api/**`) | Programmatic access for scripts, dashboards, and the Web UI |
| **Chat API** (`/api/chat`) | Conversational Q&A with RAG enrichment and agentic tool use |
| **CLI** (hub disabled) | Standalone runners that execute and exit (no web server) |

---

## Two Operating Modes

| Mode | Trigger | Web Server | Use Case |
|------|---------|------------|----------|
| **CLI** (default) | `prjxp.hub.enabled` is `false` or unset | **None** (`spring.main.web-application-type=none`) | Bundled CLI runners; process exits after work |
| **Hub** | `prjxp.hub.enabled=true` | Embedded (Tomcat) | Multi-project server with MCP, REST, chat, and lifecycle management |

The `CliModeEnvironmentPostProcessor` automatically sets `spring.main.web-application-type=none` in CLI mode (lowest priority, so an explicit user setting always wins).

---

## Prerequisites

- **Java 17+**
- **Git** on `PATH` (required for GitHub import/update)
- **Gradle** on `PATH` (optional; for Gradle dependency resolution)
- An **embedding model** (e.g. Ollama, OpenAI, or any LangChain4j-compatible provider)
- A **Lucene** embedding store (the hub requires `store-type: LUCENE`)

---

## Configuration

All configuration lives in `application.yaml` (or the file pointed to by `PRJXP_CONFIG_FILE`).

### Core (both modes)

```yaml
prjxp:
  # Embedding model
  embedding-model:
    server-type: ollama          # ollama | openai | azure | ...
    provider-url: http://localhost:11434
    model-name: nomic-embed-text
    api-key: ${OLLAMA_KEY:}
    timeout-secs: 60

  # Chat models (list)
  chat-models:
    - stereo-type: local-llm
      server-type: ollama
      provider-url: http://localhost:11434
      model-name: llama3
      temperature: 0.1
      timeout-secs: 120

  # Transfer encryption (for encrypted graph/JSONL files)
  transfer:
    password-env: PRJXP_TRANSFER_PASSWORD
```

### Hub-specific

```yaml
prjxp:
  hub:
    enabled: true                  # Master switch for all hub beans

    # Directories
    import-dir: /import            # Watched for tar/embedded uploads
    projects-root: /projects       # Extracted snapshot projects
    live-jsonl-dir: /data/chunks   # Hub-managed JSONL for LIVE projects
    libraries-root: /libraries     # Materialized Maven/Gradle libraries
    github-root: /github           # Cloned GitHub repos
    github-jsonl-dir: /data/github-chunks

    # Polling
    poll-interval-ms: 5000         # Import poller tick
    stats-refresh-interval-ms: 60000  # In-memory stats cache refresh

    # Live updates (adaptive polling)
    live-update-enabled: true
    live-update-base-interval-ms: 300000   # 5 min
    live-update-max-interval-ms: 3600000   # 60 min

    # Security limits
    max-tar-bytes: 2147483648          # 2 GiB
    max-extracted-bytes: 4294967296   # 4 GiB (compression-bomb guard)
    max-entries: 100000

    # Scan pruning
    scan-exclude-dir-names: [".git", ".svn", ".hg", "node_modules"]
    scan-max-depth: 8

    # Pre-embedded compatibility check
    compatibility:
      sample-size: 4
      threshold-high: 0.995
      threshold-low: 0.975

    # Dependency embedding
    maven-repositories: "https://repo1.maven.org/maven2"
    max-dependency-bytes: 104857600   # 100 MiB
    maven-connect-timeout-seconds: 10
    maven-request-timeout-seconds: 60
    gradle-timeout-seconds: 300

    # GitHub
    github-clone-depth: 1
    github-timeout-seconds: 300
    github-token-key-env: PRJXP_HUB_GITHUB_TOKEN_KEY  # env var NAME (not value)
```

### MCP-specific

```yaml
mcp:
  cors:
    allowed-patterns: "http://localhost:*"   # Comma-separated
```

### Docs

```yaml
prjxp:
  docs:
    dir: mcp-server/doc   # Root of the documentation tree
```

---

## MCP Tools

Eight tools are registered via `@McpTool` annotations on `PrjxpMcpTool` and also in the central `ToolRegistry` for native function calling. All are active only in hub mode.

### Search Ladder (3 stages)

```
vectorSearch  →  grep  →  readFile / readBySignature
 (discovery)   (exact)   (complete source)
```

| Tool | Level | Description |
|------|-------|-------------|
| `vectorSearch` | 1 | Semantic vector search. **Always call first** for discovery. |
| `grep` | 2 | Exact full-text search. Use the exact method signature to get its full body. |
| `readFile` | 3 | Complete source view of one file (reconstructed from index). |
| `readBySignature` | 3 | Complete implementation of a symbol (javadoc + body). Java only. |

### Discovery & Structure

| Tool | Description |
|------|-------------|
| `listProjects` | Lists all searchable projects with last-update timestamps. **Call before any search tool** to discover valid `project` values. |
| `listFiles` | Lists file paths in the index by prefix or glob. `*` matches across `/` (recursive). |
| `graphFile` | Typed code-graph neighbours of a file (imports, extends, implements, calls, fields, params, returns). |
| `graphNode` | Typed code-graph neighbours of a specific node (FQN or `FQN#method(params)`). |

### Tool Parameters

#### `vectorSearch`

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `userQuestion` | string | ✅ | Targeted, standalone search prompt. |
| `projectName` | string | ✅ | Project name (from `listProjects`). |
| `similarity` | double | | 0.0–1.0. Default 0.85. Higher = more precise. |
| `maxResults` | int | | Default 20, max 20. |
| `skeletonsOnly` | boolean | | `true` = class skeletons only. Default `false`. |
| `includeTests` | boolean | | Include test sources. Default `false`. |

#### `grep`

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `query` | string | ✅ | Exact search string (e.g. `"public MClass createMClass"`). |
| `project` | string | ✅ | Project name. |
| `language` | string | | Filter by language (e.g. `"java"`, `"ts"`). |
| `limit` | int | | Default 10, max 100. |
| `includeTests` | boolean | | Default `true` (exact search is deliberate). |

#### `readFile`

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | ✅ | Path (absolute, project-relative, or basename). Lenient: exact → suffix → basename. |
| `project` | string | ✅ | Project name. |
| `offset` | int | | 1-based line to start from. |
| `limit` | int | | Max lines to return from `offset`. |

#### `readBySignature`

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `method` | string | ✅ | Method name or FQN (`com.example.Foo#bar`). Class FQNs return the class frame. |
| `container` | string | | Containing class FQN to disambiguate a bare name. |
| `project` | string | ✅ | Project name. |

#### `listProjects`

No parameters. Returns `[{name, lastGeneratedAt}]`.

#### `listFiles`

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `path` | string | | Prefix or glob. `*` spans `/`. Empty = all. |
| `project` | string | ✅ | Project name. |
| `limit` | int | | Default 50, max 500. |

#### `graphFile` / `graphNode`

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` / `symbol` | string | ✅ | File path or node id. |
| `project` | string | ✅ | Project name. |
| `maxEdges` | int | | Default 30, max 100 per direction. |
| `includeExternal` | boolean | | Include edges to JDK/third-party types. Default `false`. |

---

## REST API

All endpoints are active only in hub mode (`prjxp.hub.enabled=true`).

### Search Tools

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/tools/vectorSearch?query=&project=&language=&limit=` | Semantic search |
| `GET` | `/api/tools/grep?query=&project=&language=&limit=` | Full-text search |
| `GET` | `/api/tools/byIndex?language=&fqn=&project=&limit=` | Deterministic symbol lookup |
| `GET` | `/api/tools/files?path=&project=&limit=` | List files |
| `GET` | `/api/tools/context?userQuestion=&project=&similarity=&maxResults=&skeletonsOnly=` | Enriched context (legacy) |
| `GET` | `/api/tools/meta-search-params` | Machine-readable search parameter schema |
| `GET` | `/api/tools/ping` | Health check → `"pong!"` |
| `GET` | `/api/tools/projects` | List all projects with lifecycle status |

### Project Management

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/projects` | List all projects (status, stats, config) |
| `POST` | `/api/projects` | Create a new project |
| `PUT` | `/api/projects/{name}` | Update project configuration |
| `DELETE` | `/api/projects/{name}` | Delete project (scoped index wipe) |
| `POST` | `/api/projects/{name}/reindex` | Trigger full reindex pipeline |
| `POST` | `/api/projects/{name}/docpipe` | Regenerate docs + re-embed documents only |
| `POST` | `/api/projects/{name}/embed-dependencies` | Trigger dependency materialization |
| `POST` | `/api/projects/{name}/reimport` | Force re-download a library |
| `GET` | `/api/projects/{name}/reindex-status` | `{status, progress}` |
| `GET` | `/api/projects/{name}/stats` | Chunk/file counts, languages |
| `POST` | `/api/projects/import-github` | Import a GitHub repo (async) |
| `POST` | `/api/projects/{name}/update-github` | Pull + re-embed a GitHub project |
| `GET` | `/api/projects/github-operations` | Recent async GitHub operations |
| `POST` | `/api/projects/{name}/importEmbeddings` | Upload pre-embedded JSONL |
| `POST` | `/api/projects/upload-archive` | Upload a tar for import |
| `POST` | `/api/projects/upload-embedded` | Upload a pre-embedded archive |
| `GET` | `/api/projects/live-scan` | Scan import dir for live candidates |

### Chat

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/chat/send` | Send a message (JSON response) |
| `POST` | `/api/chat/send` (Accept: `text/event-stream`) | SSE streaming with phase feedback |
| `POST` | `/api/chat/projects` | List projects for RAG |
| `POST` | `/api/chat/tools` | List available tools |

### Globals (Model Configuration)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/globals/chat-models` | List chat models (API keys masked) |
| `POST` | `/api/globals/chat-models` | Create a chat model |
| `PUT` | `/api/globals/chat-models/{name}` | Update a chat model |
| `DELETE` | `/api/globals/chat-models/{name}` | Delete a chat model |
| `GET` | `/api/globals/server-types` | Supported server types |
| `GET` | `/api/globals/embedding-model` | Get embedding model config |
| `PUT` | `/api/globals/embedding-model` | Update embedding model |
| `POST` | `/api/globals/chat-models/test` | Test a chat model connection |
| `POST` | `/api/globals/embedding-model/test` | Test an embedding model connection |
| `GET` | `/api/globals/chat-models/models` | List models at a provider |
| `GET` | `/api/globals/embedding-model/models` | List models at a provider |

### System

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/system/health` | CPU, memory, disk, connections |
| `GET` | `/api/system/query-stats` | Query stats (avg/p95 latency, cache hits) |

### Documentation

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/docs/tree` | Full documentation tree (folders, docs, H2 chapters) |
| `GET` | `/api/docs/content?path=` | Raw markdown of a document |
| `GET` | `/api/docs/file?path=` | Static asset (image, etc.) |

---

## Chat API

### Request

```json
POST /api/chat/send
{
  "message": "How does the chunking pipeline work?",
  "chatModelName": "local-llm",
  "projectContext": "my-project",
  "useTools": true,
  "messages": [
    {"role": "user", "content": "earlier question"},
    {"role": "assistant", "content": "earlier answer"}
  ]
}
```

### Response

```json
{
  "id": "uuid",
  "role": "assistant",
  "content": "The chunking pipeline works as follows…",
  "ragContext": [
    {"projectName": "my-project", "chunkId": "…", "snippet": "…", "score": 0.92}
  ],
  "toolCalls": [
    {"toolName": "vectorSearch", "arguments": {"userQuestion": "…", "projectName": "my-project"}, "result": "…"}
  ]
}
```

### SSE Streaming

Send the same request with `Accept: text/event-stream`. Events:

| Event Name | Data |
|------------|------|
| `phase` | `{"phase": "rag_search", "message": "…"}` |
| `phase` | `{"phase": "llm_generating", "message": "…"}` |
| `phase` | `{"phase": "tools", "message": "…", "toolCalls": […]}` |
| `phase` | `{"phase": "tool_result", "tool": "…", "result": "…"}` |
| `complete` | Full `ChatResponse` JSON |
| `error` | `{"message": "…"}` |

### Tool Calling

When `useTools: true`, the chat service runs an **agentic loop** (max 3 iterations):

1. **Native function calling** (preferred): tool schemas are passed to the LLM provider; the model returns structured `ToolExecutionRequest` objects.
2. **Text protocol fallback**: if the model doesn't support native tools, the prompt includes a `TOOL_CALL[tool](params)` format. XML-style calls from models that ignore the protocol are also recovered.

The project context is auto-filled into tool arguments when the model omits it.

---

## Project Lifecycle (Hub Mode)

```
IMPORTING → CHUNKING → EMBEDDING → READY
                ↓           ↓
             FAILED      FAILED
```

| Status | Meaning |
|--------|---------|
| `IMPORTING` | Project registered, pipeline queued |
| `CHUNKING` | Source files being split into chunks (0–10% progress) |
| `EMBEDDING` | Embeddings being computed (10–80% progress) |
| `READY` | Searchable |
| `FAILED` | Pipeline error (old index kept if it existed) |

**Keep-old-index semantics**: the old index stays in place (project remains searchable) until the final swap. Only the swap step (scoped `removeAll` + batched import) is briefly offline.

### Reindex Progress

The 0–100 progress bar is split into bands:

| Band | Phase |
|------|-------|
| 0–10 | Chunking |
| 10–80 | Embedding |
| 80–100 | Storing (swap) |

---

## Importing Projects

### Tar Archive (Snapshot)

Drop a `<ProjectName>.tar` or `<ProjectName>.tgz` into the `import-dir`. The poller extracts it into `projects-root/<name>`, registers it, and enqueues the pipeline.

### Pre-Embedded Archive

Drop a `<ProjectName>-embedded.(jsonl|gz|tgz|zip)` into the `import-dir`. The file must contain `EmbeddedChunkRecord` JSONL with pre-computed vectors. A `.ready` marker file is required before the pipeline runs (prevents race conditions during transfer).

### Live Project (Marker File)

Place a `prjxp.yaml` (or `prjxp.yml`) in any directory under `import-dir`. The recursive marker walk discovers it. The project is registered in-place (user owns the tree).

### GitHub

```bash
curl -X POST http://localhost:8080/api/projects/import-github \
  -H "Content-Type: application/json" \
  -d '{"url": "https://github.com/org/repo", "token": "ghp_…"}'
```

Shallow-clones into `github-root/<name>`, generates a `prjxp.yaml` with the `gitUrl` origin marker, registers it, and enqueues the pipeline. For private repos, the PAT is encrypted at rest (AES-256-GCM) and never stored in plaintext.

### Upload via API

```bash
# Tar archive
curl -X POST http://localhost:8080/api/projects/upload-archive \
  -F "file=@my-project.tar"

# Pre-embedded
curl -X POST http://localhost:8080/api/projects/upload-embedded \
  -F "file=@my-project-embedded.jsonl"
```

---

## Live Updates

LIVE projects with `liveUpdate: true` in their `prjxp.yaml` are polled on an **adaptive interval**:

- Base: 5 minutes
- Doubled after each empty scan (cap: 60 minutes)
- Reset to base when changes are found
- A `rescanRequested` flag forces an immediate rescan (≤30 s) after a long pipeline run

The scanner compares on-disk file state (mtime + size) against a persisted source-state file. Changed files are re-embedded; deleted files are removed from the index. The old index stays searchable throughout.

---

## GitHub Integration

| Operation | Endpoint | Behavior |
|-----------|----------|----------|
| Import | `POST /api/projects/import-github` | Async clone → register → pipeline |
| Update | `POST /api/projects/{name}/update-github` | `git pull --ff-only` → forced re-embed |
| Status | `GET /api/projects/github-operations` | Recent operations (PENDING/SUCCESS/FAILED) |

The `git` executable must be on `PATH`. Tokens are passed via `http.extraheader` (never in the URL) and encrypted with AES-256-GCM for at-rest storage.

---

## Dependency Embedding

When `embedDependencies: true` in a project's `prjxp.yaml`, the hub resolves its dependency tree (Maven POM via BFS, or Gradle via `gradle dependencies`) and materializes each embeddable dependency as an independent library project under `libraries-root` in Maven-repo layout:

```
/libraries/com/example/lib/1.0.0/
├── src/          # extracted -sources.jar
├── doc/          # extracted -javadoc.jar
└── prjxp.yaml    # generated (mavenDependency marker)
```

- Test-scope dependencies are excluded.
- A dependency is embeddable if it has at least a `-sources` or `-javadoc` classifier.
- Depth is bounded by `dependencyDepth` (default 2).
- A `dependencyWhitelist` (comma-separated GroupId prefixes) can filter which dependencies are imported.

Trigger manually: `POST /api/projects/{name}/embed-dependencies`.

---

## Documentation API

The docs tree is built from a directory of Markdown files (configured via `prjxp.docs.dir`). Each `.md` file is parsed for its H1 title and H2 chapters (with deduplicated anchors).

```bash
# Full tree
curl http://localhost:8080/api/docs/tree

# Document content
curl http://localhost:8080/api/docs/content?path=guides/getting-started.md

# Image asset
curl http://localhost:8080/api/docs/file?path=images/architecture.png
```

Path traversal (`..`) and absolute paths are rejected.

---

## System & Monitoring

```bash
# Health
curl http://localhost:8080/api/system/health
# → {"cpuUsage":0.5,"totalMemory":…,"usedMemory":…,"freeDiskSpace":…,"totalDiskSpace":…,"activeConnections":0}

# Query stats
curl http://localhost:8080/api/system/query-stats
# → {"totalQueries":…,"avgResponseTimeMs":…,"p95ResponseTimeMs":…,"cacheHits":…,"cacheMisses":…,"recentQueries":[…]}
```

---

## MCP Protocol Endpoint

The `/mcp` endpoint speaks JSON-RPC 2.0 (MCP protocol). A compatibility filter handles:

- **`server/discover`**: returns supported protocol versions and capabilities (tools, prompts, resources, completions) based on what's actually registered.
- **`initialize`**: strips the `elicitation` capability from the client's request (not supported).
- **Legacy protocol version**: `2025-06-18`.

The filter only intercepts `POST /mcp` requests. All other methods/paths pass through.

---

## CORS

Configured via `mcp.cors.allowed-patterns` (default: `http://localhost:*`). Applied to:

- `/api/**` — GET, POST, PUT, DELETE, OPTIONS; credentials allowed
- `/mcp` — GET, POST, OPTIONS; credentials allowed
- `/v3/api-docs/**` — GET

---

## Search Strategy (3-Stage Ladder)

The recommended workflow for AI agents and developers:

```
┌─────────────────────────────────────────────────────────────────────┐
│  1. listProjects                                                    │
│     → discover valid project names                                   │
├─────────────────────────────────────────────────────────────────────┤
│  2. vectorSearch (Level 1 – Discovery)                              │
│     → semantic overview: "How does authentication work?"            │
│     → returns skeletons + hit methods                                │
├─────────────────────────────────────────────────────────────────────┤
│  3. grep (Level 2 – Exact)                                          │
│     → "public MClass createMClass"                                  │
│     → returns the FULL method body from the chunk                    │
├─────────────────────────────────────────────────────────────────────┤
│  4. readFile / readBySignature (Level 3 – Complete)                 │
│     → entire file or complete symbol (javadoc + body)               │
└─────────────────────────────────────────────────────────────────────┘

Orthogonal: listFiles (find paths) → graphFile / graphNode (structure)
```

**Key rules:**
- Always call `vectorSearch` first for broad discovery.
- Use `grep` with the exact signature for full method bodies.
- Use `readFile` for the whole file; `readBySignature` for a specific method/class.
- `includeTests` defaults to `false` for `vectorSearch` (tests are noise for semantic search) but `true` for `grep` (exact search is deliberate).
- The `project` parameter is **required** on all search tools — there is no implicit default.

---

## Project Configuration File (`prjxp.yaml`)

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
liveUpdate: true
# embeddingsFile: my-project-embedded.jsonl   # for pre-embedded projects
# prompt: |
#   Focus on the payment module.
# embedDependencies: true
# dependencyDepth: 2
# dependencyWhitelist: com.example,org.springframework
# mavenDependency: com.example:lib:1.0.0     # origin marker (library)
# gitUrl: https://github.com/org/repo         # origin marker (GitHub)
# gitToken: v1:<salt>:<iv>:<ciphertext>       # encrypted PAT
```

A `.prjxp-exclude` file in a directory tells the hub to ignore it entirely (never descend, never register). A `.ready` file signals that a pre-embedded project's JSONL transfer is complete.

---

## Running

```bash
# CLI mode (default)
java -jar mcp-server.jar

# Hub mode
java -jar mcp-server.jar --prjxp.hub.enabled=true

# With environment variables
PRJXP_HUB_GITHUB_TOKEN_KEY=supersecret java -jar mcp-server.jar --prjxp.hub.enabled=true
```

The server starts on port **8080** by default (configurable via `server.port`).

_This document was generated with Doc|Pipe_

