# prjxp mcp-server

The **prjxp mcp-server** is a Spring Boot application that exposes a project's indexed source code and documentation through the **Model Context Protocol (MCP)** and a rich **REST API**. It enables AI agents and web clients to search, read, and navigate codebases with semantic vector search, exact text search, deterministic symbol lookup, and a typed code knowledge graph.

---

## Architecture

The server operates in two modes, controlled by the `prjxp.hub.enabled` property:

| Mode | `prjxp.hub.enabled` | Web Server | Use Case |
|------|---------------------|------------|----------|
| **CLI** | `false` (default) | None (`spring.main.web-application-type=none`) | Bundled CLI runners execute and exit |
| **Hub** | `true` | Embedded (Tomcat) | Multi-project server with MCP, REST, chat, and project lifecycle management |

In **Hub mode**, the server:

- Serves the MCP endpoint at `POST /mcp` (JSON-RPC 2.0)
- Exposes REST endpoints under `/api/**`
- Manages a shared Lucene embedding store across multiple projects
- Runs a single-threaded pipeline (chunk → embed → swap) per project
- Supports live-update polling, GitHub imports, and library materialization

In **CLI mode**, the `CliModeEnvironmentPostProcessor` forces `spring.main.web-application-type=none` so no embedded web server starts. The bundled CLI runners execute and the process exits.

---

## Quick Start

### Prerequisites

- Java 17+
- A running embedding model (e.g., Ollama with `nomic-embed-text`)
- A project indexed with `prjxp` (chunked + embedded into a Lucene store)

### Configuration

Create or edit `application.yaml`:

```yaml
prjxp:
  hub:
    enabled: true                    # Master switch for hub mode
    import-dir: /import              # Directory watched for tar uploads
    projects-root: /projects         # Root for extracted snapshot projects
    live-jsonl-dir: /data/chunks     # Hub-managed JSONL for live projects
    poll-interval-ms: 5000           # Import poller interval
    live-update-enabled: true        # Master switch for live updates
    live-update-base-interval-ms: 300000   # 5 min
    live-update-max-interval-ms: 3600000   # 60 min
    stats-refresh-interval-ms: 60000       # In-memory stats cache refresh
    max-tar-bytes: 2147483648        # 2 GiB
    max-extracted-bytes: 4294967296  # 4 GiB
    max-entries: 100000
    scan-exclude-dir-names: .git,.svn,.hg,node_modules
    scan-max-depth: 8
    libraries-root: /libraries
    github-root: /github
    github-jsonl-dir: /data/github-chunks
    github-clone-depth: 1
    github-timeout-seconds: 300
    github-token-key-env: PRJXP_HUB_GITHUB_TOKEN_KEY

  mcp:
    grep:
      doc-max-chars: 2000
      doc-window-chars: 500

  docs:
    dir: mcp-server/doc              # Documentation tree root

mcp:
  cors:
    allowed-patterns: http://localhost:*
```

### Running

```bash
# Hub mode (web server + MCP + REST)
java -jar mcp-server.jar --prjxp.hub.enabled=true

# CLI mode (no web server)
java -jar mcp-server.jar
```

---

## MCP Tools

The server registers **10 tools** available via the MCP endpoint (`POST /mcp`) and as native function-calling tools in the chat. All tools require a `project` parameter (discover valid names via `listProjects`).

### 1. `vectorSearch`

Semantic vector search over all chunks of a project, ranked by similarity.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `userQuestion` | string | ✅ | Search prompt for vector retrieval |
| `projectName` | string | ✅ | Project name (from `listProjects`) |
| `similarity` | number | | Threshold 0.0–1.0 (default 0.85) |
| `maxResults` | integer | | Max files (default 10, max 20) |
| `contentLevel` | string | | `POINTERS` (default), `SKELETON`, `FULL` |
| `sourceType` | string | | `code` (default), `docs`, `all` |
| `includeTests` | boolean | | Include test sources (default false) |

**Strategy:** Use first for broad discovery ("where is X", "how does Y work"). Returns file paths with follow-up hints (`readFile`, `readBySignature`, `graphNode`).

### 2. `grep`

Exact full-text search over project chunks.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `query` | string | ✅ | Exact search string |
| `project` | string | ✅ | Project name |
| `language` | string | | Language filter (e.g., `java`) |
| `limit` | integer | | Max hits after grouping (default 10, max 100) |
| `contentLevel` | string | | `POINTERS`, `SKELETON`, `FULL` (default) |
| `includeTests` | boolean | | Include test sources (default true) |

**Strategy:** Use for exact identifiers, method signatures, annotations. `POINTERS` is cheap for enumeration; `FULL` fetches bodies.

### 3. `readFile`

Complete source view of one file, reconstructed from the index.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | ✅ | File path (lenient: exact → suffix → basename) |
| `project` | string | ✅ | Project name |
| `offset` | integer | | 1-based start line |
| `limit` | integer | | Max lines from offset |

### 4. `readBySignature`

Complete implementation of a symbol (javadoc + body) by name.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `method` | string | ✅ | Method name or `FQN#name` (`com.example.Foo#bar`) for the body; a class FQN returns the class frame (signatures only) |
| `container` | string | | Containing class FQN to disambiguate |
| `project` | string | ✅ | Project name |

**Strategy:** For a method body, pass `FQN#name` — it returns javadoc + body only (no imports, no class frame), which is cheaper than `grep` with `FULL`. A class FQN returns the class frame; use it to discover method names, then call again with `FQN#name`.

### 5. `listProjects`

Lists all searchable projects with their last index update timestamp. No parameters.

### 6. `listFiles`

Lists file paths in the index (structure, not content).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `path` | string | | Path prefix or glob (`*` spans `/`); empty = all |
| `project` | string | ✅ | Project name |
| `limit` | integer | | Max files (default 50, max 500) |

### 7. `graphFile`

Typed code-graph neighbours of one file (import/extends/implements/field/param/return/call edges).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | string | ✅ | File path (lenient) |
| `project` | string | ✅ | Project name |
| `maxEdges` | integer | | Max neighbours per direction (default 30, max 100) |
| `includeExternal` | boolean | | Include JDK/library edges (default false) |

### 8. `graphNode`

Typed code-graph neighbours of one node (class FQN or method id).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `symbol` | string | ✅ | Node id: class FQN or `FQN#name(params)`; bare names match as suffix |
| `project` | string | ✅ | Project name |
| `maxEdges` | integer | | Max neighbours per direction (default 30, max 100) |
| `includeExternal` | boolean | | Include JDK/library edges (default false) |

### 9. `graphCluster`

Most important members of one Leiden cluster, ranked by degree centrality.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `clusterId` | string | ✅ | Cluster id (e.g., `c6`, from `vectorSearch` output) |
| `project` | string | ✅ | Project name |
| `topN` | integer | | Top members to return (default 10, max 50) |

### 10. `graphClusterOverview`

Map of the project's clusters — the N largest with size and top members. Use to explore architecture without a starting class.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `project` | string | ✅ | Project name |
| `topN` | integer | | Clusters to return (default 10, max 50) |

---

## Recommended Search Strategy

```
┌─────────────────────────────────────────────────────────────────────┐
│  1. vectorSearch  →  semantic overview, discover relevant files     │
│         │                                                           │
│         ▼                                                           │
│  2. grep          →  exact text, enumerate implementations,         │
│         │              find call sites                              │
│         ▼                                                           │
│  3. readFile /    →  read full source for detailed understanding    │
│     readBySignature                                               │
│                                                                   │
│  Orthogonal: listFiles (structure), graphNode/graphCluster (arch)  │
└─────────────────────────────────────────────────────────────────────┘
```

---

## REST API

All endpoints are active in **Hub mode** only.

### Tools

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/tools/ping` | Health check (returns `"pong!"`) |
| `GET` | `/api/tools/context` | RAG context retrieval (legacy) |
| `GET` | `/api/tools/vectorSearch` | Semantic search (query, project, language, limit) |
| `GET` | `/api/tools/grep` | Full-text search (query, project, language, limit, contentLevel) |
| `GET` | `/api/tools/byIndex` | Deterministic symbol search (language, fqn, methodName, signatureHash, containerFqn, project, limit) |
| `GET` | `/api/tools/files` | List files (path, project, limit) |
| `GET` | `/api/tools/meta-search-params` | Machine-readable search parameter schema |
| `GET` | `/api/tools/projects` | List all projects with lifecycle status |

### Projects (Hub)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/projects` | List all projects with stats and config |
| `POST` | `/api/projects` | Create a new project |
| `PUT` | `/api/projects/{name}` | Update project configuration |
| `DELETE` | `/api/projects/{name}` | Delete a project (scoped index wipe) |
| `POST` | `/api/projects/{name}/reindex` | Trigger full pipeline re-run |
| `POST` | `/api/projects/{name}/docpipe` | DocPipe-only re-run (docs only, code untouched) |
| `POST` | `/api/projects/{name}/embed-dependencies` | Trigger dependency materialization |
| `POST` | `/api/projects/{name}/reimport` | Re-materialize a library project |
| `POST` | `/api/projects/{name}/importEmbeddings` | Upload pre-embedded JSONL |
| `POST` | `/api/projects/import-github` | Import a GitHub repository |
| `POST` | `/api/projects/{name}/update-github` | Update a GitHub project (git pull + re-embed) |
| `GET` | `/api/projects/github-operations` | Recent async GitHub operations |
| `GET` | `/api/projects/{name}/reindex-status` | Current reindex status + progress |
| `GET` | `/api/projects/{name}/stats` | Detailed project statistics |
| `GET` | `/api/projects/live-scan` | Scan import dir for live project candidates |
| `POST` | `/api/projects/upload-archive` | Upload a TAR/ZIP for import |
| `POST` | `/api/projects/upload-embedded` | Upload a pre-embedded archive |

### Chat

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/chat/send` | Send a chat message (JSON body) |
| `POST` | `/api/chat/send` (SSE) | Streaming chat with phase feedback |
| `POST` | `/api/chat/projects` | List available projects for chat |
| `POST` | `/api/chat/tools` | List available tools for chat |

**ChatRequest body:**

```json
{
  "message": "How does the authentication flow work?",
  "chatModelName": "my-llm",
  "projectContext": "my-project",
  "useTools": true,
  "messages": [
    { "role": "user", "content": "..." },
    { "role": "assistant", "content": "..." }
  ]
}
```

### System

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/system/health` | CPU, memory, disk, connections |
| `GET` | `/api/system/query-stats` | Query statistics (total, avg/p95 latency, cache hits) |

### Globals (Model Configuration)

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/globals/chat-models` | List configured chat models |
| `POST` | `/api/globals/chat-models` | Create a chat model |
| `PUT` | `/api/globals/chat-models/{name}` | Update a chat model |
| `DELETE` | `/api/globals/chat-models/{name}` | Delete a chat model |
| `GET` | `/api/globals/server-types` | List supported server types |
| `GET` | `/api/globals/embedding-model` | Get embedding model config |
| `PUT` | `/api/globals/embedding-model` | Update embedding model config |
| `POST` | `/api/globals/chat-models/test` | Test a chat model connection |
| `POST` | `/api/globals/embedding-model/test` | Test an embedding model connection |
| `GET` | `/api/globals/chat-models/models` | List models at a provider |
| `GET` | `/api/globals/embedding-model/models` | List models at a provider |

### Documentation

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/docs/tree` | Documentation tree (folders, docs, H2 chapters) |
| `GET` | `/api/docs/content?path=...` | Raw markdown of a document |
| `GET` | `/api/docs/file?path=...` | Static asset (images, etc.) |

### MCP Endpoint

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/mcp` | JSON-RPC 2.0 MCP endpoint (initialize, tools/list, tools/call, etc.) |

A `McpCompatibilityFilter` intercepts `POST /mcp` requests to:
- Handle `server/discover` (legacy protocol version `2025-06-18`)
- Strip the `elicitation` capability from `initialize` requests
- Buffer the request body for downstream processing

---

## Hub: Project Lifecycle

### Project Kinds

| Kind | Source | Ownership | Vanish Policy |
|------|--------|-----------|---------------|
| `SNAPSHOT` | Tar import, GitHub clone, Library | Hub-managed | Unregister only (or wipe for GitHub) |
| `LIVE` | Marker file in import dir | User-owned | Unregister + wipe index |

### Pipeline Phases

```
IMPORTING → CHUNKING → EMBEDDING → READY
                (10%)     (80%)    (100%)
```

- **Chunking** (0–10%): Source files → chunks (old index untouched, project stays searchable)
- **Embedding** (10–80%): Chunks → vectors via export (old index untouched)
- **Swap** (80–100%): Scoped `removeAll` + batched import (briefly non-searchable)

On failure before the swap: the old index is preserved and the project stays `READY`.

### Live Updates

For `LIVE` projects with `liveUpdate: true` in `prjxp.yaml`:

- Adaptive polling: base 5 min, doubles after each empty scan (cap 60 min)
- Detects file changes via mtime/size comparison against a persisted source state
- Two-phase update: chunk+embed changed files → scoped index update → atomic JSONL publish
- On failure: old index preserved, scheduler retries next cycle

### Pre-Embedded Projects

Projects with `embeddingsFile` set in `prjxp.yaml` skip chunking entirely. The pipeline:
1. Validates embedding compatibility (cosine similarity check against local model)
2. Swaps: scoped reset + batched import of pre-computed vectors

Requires a `.ready` marker file before being enqueued (prevents race conditions during file transfer).

---

## Hub: GitHub Import

Import a GitHub repository as an independent project:

```bash
curl -X POST http://localhost:8080/api/projects/import-github \
  -H "Content-Type: application/json" \
  -d '{"url": "https://github.com/owner/repo.git", "token": "ghp_..."}'
```

- Shallow-clone (`--depth 1`) into `github-root/<name>`
- Generates `prjxp.yaml` with `gitUrl` origin marker
- For private repos: the PAT is **encrypted at rest** (AES-256-GCM) in `prjxp.yaml`
- The encryption key passphrase is read from the env var named by `prjxp.hub.github-token-key-env`
- Update: `POST /api/projects/{name}/update-github` (git pull + re-embed)

---

## Hub: Dependency Embedding (Libraries)

When `embedDependencies: true` in a project's `prjxp.yaml`:

1. Resolves the dependency tree (Maven POM via BFS, or Gradle `dependencies` output)
2. Filters by `dependencyDepth` (default 2) and `dependencyWhitelist` (GroupId prefixes)
3. Probes Maven Central for `-sources` / `-javadoc` classifiers
4. Materializes embeddable dependencies as independent library projects under `libraries-root`
5. Enqueues each library for the standard pipeline

Re-import a library: `POST /api/projects/{name}/reimport`

---

## Hub: Import Poller

The `ImportPoller` runs on a fixed delay (`prjxp.hub.poll-interval-ms`, default 5 s):

1. **Tar files** in `import-dir`: extracts into `projects-root/<name>`, enqueues pipeline
2. **Embedded archives** (`<Name>-embedded.jsonl|gz|tgz|zip`): validates, imports, enqueues
3. **Live projects**: discovers marker files (`prjxp.yaml`/`prjxp.yml`) recursively, enqueues new ones

Security: tar/zip extraction rejects absolute paths, `..` segments, symlinks, and enforces size/entry limits.

---

## Configuration Reference

### `prjxp.hub.*` (HubProperties)

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `false` | Master switch for all hub beans |
| `import-dir` | `/import` | Directory watched for uploads |
| `projects-root` | `/projects` | Root for snapshot projects |
| `live-jsonl-dir` | `/data/chunks` | Hub-managed JSONL for live projects |
| `poll-interval-ms` | `5000` | Import poller interval |
| `live-update-enabled` | `true` | Master switch for live updates |
| `live-update-base-interval-ms` | `300000` | Base polling interval (5 min) |
| `live-update-max-interval-ms` | `3600000` | Max polling interval (60 min) |
| `stats-refresh-interval-ms` | `60000` | Stats cache refresh (0 = disabled) |
| `max-tar-bytes` | `2147483648` | Max tar size (2 GiB) |
| `max-extracted-bytes` | `4294967296` | Max extracted size (4 GiB) |
| `max-entries` | `100000` | Max tar entries |
| `scan-exclude-dir-names` | `.git,.svn,.hg,node_modules` | Dirs never descended into |
| `scan-max-depth` | `8` | Max recursion depth for marker scan |
| `compatibility-sample-size` | `4` | Vectors sampled for compat check |
| `compatibility-threshold-high` | `0.995` | Min avg cosine for COMPATIBLE |
| `compatibility-threshold-low` | `0.975` | Min avg cosine for PARTIALLY_COMPATIBLE |
| `libraries-root` | `/libraries` | Root for materialized libraries |
| `maven-repositories` | `https://repo1.maven.org/maven2` | Maven repo base URL |
| `max-dependency-bytes` | `104857600` | Max artifact size (100 MiB) |
| `maven-connect-timeout-seconds` | `10` | HTTP connect timeout |
| `maven-request-timeout-seconds` | `60` | HTTP request timeout |
| `gradle-timeout-seconds` | `300` | Gradle process timeout |
| `github-root` | `/github` | Root for GitHub clones |
| `github-jsonl-dir` | `/data/github-chunks` | Hub-managed JSONL for GitHub projects |
| `github-clone-depth` | `1` | Shallow-clone depth |
| `github-timeout-seconds` | `300` | Git process timeout |
| `github-token-key-env` | `PRJXP_HUB_GITHUB_TOKEN_KEY` | Env var name for token encryption key |

### `prjxp.mcp.*`

| Property | Default | Description |
|----------|---------|-------------|
| `grep.doc-max-chars` | `2000` | Max chars for a doc snippet before windowing |
| `grep.doc-window-chars` | `500` | Half-window size around a match |

### `prjxp.docs.*`

| Property | Default | Description |
|----------|---------|-------------|
| `dir` | `mcp-server/doc` | Documentation tree root |

### `mcp.cors.*`

| Property | Default | Description |
|----------|---------|-------------|
| `allowed-patterns` | `http://localhost:*` | Comma-separated CORS origin patterns |

---

## Project Configuration (`prjxp.yaml`)

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
# embeddingsFile: my-project-embedded.jsonl   # pre-embedded
# prompt: "Focus on the payment module"        # standing instructions
# embedDependencies: true
# dependencyDepth: 2
# dependencyWhitelist: com.example,org.apache
# mavenDependency: com.example:lib:1.0.0      # library origin marker
# gitUrl: https://github.com/owner/repo.git    # GitHub origin marker
# gitToken: v1:<salt>:<iv>:<ciphertext>        # encrypted PAT
```

---

## MCP Client Integration

Connect any MCP-compatible client (e.g., Claude Desktop, Cursor, custom agents) to the server:

```json
{
  "mcpServers": {
    "prjxp": {
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

The server supports the MCP protocol version `2025-06-18` and advertises `tools` capability.

---

## CORS

By default, CORS is open to `http://localhost:*` for `/api/**`, `/mcp`, and `/v3/api-docs/**`. Override with:

```yaml
mcp:
  cors:
    allowed-patterns: http://localhost:*,https://myapp.example.com
```

---

## Environment Variables

| Variable | Purpose |
|----------|---------|
| `PRJXP_HUB_GITHUB_TOKEN_KEY` | Passphrase for GitHub PAT encryption (AES-256-GCM key derivation) |
| `PRJXP_CONFIG_FILE` | Path to `application.yaml` (overrides default locations) |

---

## Swagger / OpenAPI

Interactive API documentation is available at:

```
GET /v3/api-docs
GET /swagger-ui.html
```

All endpoints are annotated with `@Operation` and `@Parameter` for self-documenting descriptions.

_This document was generated with Doc|Pipe_

