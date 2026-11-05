# mcp-server

`mcp-server` is the prjxp Spring Boot server that exposes indexed project knowledge through:

- an MCP endpoint at `/mcp`
- REST search tools under `/api/tools`
- hub project-management endpoints under `/api/projects`
- chat and model-configuration REST APIs under `/api/chat` and `/api/globals`

It is designed for AI assistants and development tools that need semantic code search, exact text search, symbol lookup, and reconstructed source-file reads over one or more indexed projects.

## Core Concepts

### Search Ladder

The server exposes a recommended 3-level workflow:

1. **`vectorSearch`** — semantic discovery over indexed chunks.
2. **`grep`** — exact full-text search for identifiers, signatures, strings, or snippets.
3. **`readFile` / `readBySignature`** — deterministic reading of complete files or symbols.

Use `listFiles` orthogonally when you need to discover file paths.

Typical workflow:

1. Call `listProjects` if you do not know the project name.
2. Call `vectorSearch` to find relevant classes, files, and implementation areas.
3. Call `grep` with exact strings found in the vector results.
4. Call `readFile` for a complete file or `readBySignature` for a complete method/class frame.

## Running the Server

The main class is:

`de.spraener.prjxp.mcp.McpServer`

It reads `.env` values through `PrjXPCli.readDotEnv(args)` before starting Spring Boot.

Example:

```bash
java -jar mcp-server.jar
```

If started with the default Spring Boot settings, the server is available at:

```text
http://localhost:8080
```

The MCP endpoint is:

```text
http://localhost:8080/mcp
```

## MCP Usage

The server exposes MCP tools through `/mcp`.

Supported MCP-related behavior includes:

- POST `/mcp`
- legacy `server/discover` support
- compatibility handling for MCP protocol version `2025-06-18`
- automatic removal of unsupported `elicitation` capability from `initialize` requests
- tool, prompt, resource, and completion capability discovery based on annotated beans

### Available MCP Tools

The built-in prjxp MCP tools are:

| Tool | Purpose |
|---|---|
| `vectorSearch` | Semantic search over indexed project chunks |
| `grep` | Exact full-text search |
| `readFile` | Reconstruct and return a complete indexed source file |
| `readBySignature` | Return complete symbol implementations by method/class name or FQN |
| `listFiles` | List indexed files by prefix or glob |
| `listProjects` | List searchable projects |

### `vectorSearch`

Primary discovery tool.

Parameters:

| Parameter | Required | Description |
|---|---:|---|
| `userQuestion` | yes | Standalone semantic search prompt |
| `projectName` | no | Project name, defaults through project registry |
| `similarity` | no | Similarity threshold, default `0.85` |
| `maxResults` | no | Maximum results, default `20`, max `20` |
| `skeletonsOnly` | no | Return class skeletons instead of full hit context |

Example intent:

```text
Find the service that imports pre-embedded archives
```

### `grep`

Exact search tool.

Parameters:

| Parameter | Required | Description |
|---|---:|---|
| `query` | yes | Exact search string |
| `project` | no | Project name |
| `language` | no | Optional language filter, for example `java` or `ts` |
| `limit` | no | Maximum results, default `10`, max `100` |

Use this after `vectorSearch` when you know an exact identifier or method signature.

### `readFile`

Returns a reconstructed full source view from indexed chunks.

Parameters:

| Parameter | Required | Description |
|---|---:|---|
| `file` | yes | Absolute path, project-relative path, or file name |
| `project` | no | Project name |
| `offset` | no | 1-based line offset |
| `limit` | no | Maximum number of lines |

Path resolution is lenient:

1. exact match
2. suffix match
3. basename match

If the path is ambiguous, the result contains candidate paths.

### `readBySignature`

Returns complete symbol implementations.

Parameters:

| Parameter | Required | Description |
|---|---:|---|
| `method` | yes | Method name, method FQN such as `com.example.Foo#bar`, or class FQN |
| `container` | no | Containing class FQN for disambiguation |
| `project` | no | Project name |

The reader returns:

- full method body
- javadoc/doc unit when available
- class frame when a class FQN is requested
- up to 10 full matches
- remaining FQNs when more than 10 symbols match

Although the tool description says Java-only in places, the implementation understands symbol metadata and section markers for multiple languages when chunkers write compatible metadata.

### `listFiles`

Lists distinct indexed file paths.

Parameters:

| Parameter | Required | Description |
|---|---:|---|
| `path` | no | Prefix or glob |
| `project` | no | Project name |
| `limit` | no | Default `50`, max `500` |

Pattern behavior:

- empty path lists all indexed files, limited by `limit`
- prefix example: `frontend/src`
- glob example: `*Controller.java`
- `*` matches across `/`
- `?` matches one character
- matching is case-sensitive

### `listProjects`

Lists searchable projects.

Returns project names and, for live projects, the last generated timestamp when available.

## REST Tool API

REST search endpoints are available under:

```text
/api/tools
```

### Ping

```http
GET /api/tools/ping
```

Returns:

```text
pong!
```

### Vector Search

```http
GET /api/tools/vectorSearch
```

Query parameters:

| Parameter | Required | Default | Description |
|---|---:|---|---|
| `query` | yes | | Semantic query |
| `project` | no | `default` | Project name |
| `language` | no | | Language filter |
| `limit` | no | `10` | Max `100` |

Example:

```bash
curl "http://localhost:8080/api/tools/vectorSearch?query=embedded%20archive%20import&project=my-project&language=java&limit=10"
```

### Context Search

```http
GET /api/tools/context
```

Legacy semantic context endpoint backed by `GRPromptEnrichment`.

Query parameters:

| Parameter | Required | Description |
|---|---:|---|
| `userQuestion` | yes | Targeted semantic search prompt |
| `project` | no | Defaults to `default` |
| `similarity` | no | `0.0` to `1.0`, default `0.85` |
| `maxResults` | no | `1` to `20`, default `20` |
| `skeletonsOnly` | no | Return skeletons only |

### Grep

```http
GET /api/tools/grep
```

Query parameters:

| Parameter | Required | Default | Description |
|---|---:|---|---|
| `query` | yes | | Exact string |
| `project` | no | `default` | Project name |
| `language` | no | | Language filter |
| `limit` | no | `10` | Max `100` |

Example:

```bash
curl "http://localhost:8080/api/tools/grep?query=public%20ProjectInfo%20process&project=my-project&language=java"
```

### Index Symbol Search

```http
GET /api/tools/byIndex
```

Deterministic metadata search over the chunk index.

Parameters:

| Parameter | Required | Description |
|---|---:|---|
| `language` | yes | Language, for example `java` |
| `fqn` | conditional | Fully qualified class or method name |
| `symbolType` | no | Symbol type filter |
| `methodName` | conditional | Method name |
| `signatureHash` | conditional | Exact signature hash |
| `containerFqn` | conditional | Containing class FQN |
| `project` | no | Defaults to `default` |
| `limit` | no | Default `10`, max `100` |

At least one of the following must be supplied:

- `fqn`
- `methodName`
- `signatureHash`
- `containerFqn`

Example:

```bash
curl "http://localhost:8080/api/tools/byIndex?language=java&methodName=process&project=my-project"
```

### List Files

```http
GET /api/tools/files
```

Parameters:

| Parameter | Required | Default | Description |
|---|---:|---|---|
| `path` | no | empty | Prefix or glob |
| `project` | no | `default` | Project name |
| `limit` | no | `50` | Max `500` |

Example:

```bash
curl "http://localhost:8080/api/tools/files?path=*Controller.java&project=my-project"
```

### Search Metadata

```http
GET /api/tools/meta-search-params
```

Returns machine-readable search parameter metadata:

- schema version
- global parameters
- language-specific capabilities
- valid symbol types
- identifier rules

Use this endpoint when a client needs to discover valid language-specific search parameters.

### REST Projects Shortcut

```http
GET /api/tools/projects
```

Returns known projects with lifecycle status.

## Project Resolution

All search tools use `ProjectRegistry`.

There are two runtime modes, selected by a single switch:

| Mode | Enabled when | Behavior |
|---|---|---|
| Hub mode | `prjxp.hub.enabled=true` | MCP server (WebMVC, `/mcp`), REST API, directory-driven project registry (`HubProjectRegistry`), in-process pipeline, import watcher |
| CLI mode | `prjxp.hub.enabled=false` or missing | The same `mcp-server-all.jar` runs the full pipeline (chunk + embed + docpipe) and exits; no web server |

The hub registry is the only registry: projects are discovered dynamically and have lifecycle states.

If a requested project is not searchable, tools fail explicitly with an error listing available projects.

A project is searchable only when its status is `READY`.

## CLI Mode

CLI mode is the default.

The same `mcp-server-all.jar` runs the full pipeline (chunk + embed + docpipe) and exits.

Behavior:

- no web server, no MCP endpoint, no REST API
- no hub lifecycle, import polling, or live update is active
- the pipeline runs once and the process terminates

## Hub Mode

Enable hub mode with:

```yaml
prjxp:
  hub:
    enabled: true
```

Hub mode provides:

- dynamic project discovery
- archive import
- live project scanning
- lifecycle status tracking
- reindex pipeline
- pre-embedded import
- live updates
- DocPipe integration
- project-management REST API

### Hub Directories

Default hub properties:

| Property | Default | Description |
|---|---|---|
| `prjxp.hub.import-dir` | `/import` | Watched directory for uploads and live projects |
| `prjxp.hub.projects-root` | `/projects` | Root for extracted snapshot projects |
| `prjxp.hub.live-jsonl-dir` | `/data/chunks` | Hub-managed JSONL output for live projects |
| `prjxp.hub.poll-interval-ms` | `5000` | Import poll interval |
| `prjxp.hub.live-update-enabled` | `true` | Master switch for live updates |
| `prjxp.hub.live-update-base-interval-ms` | `300000` | Base live-update scan interval |
| `prjxp.hub.live-update-max-interval-ms` | `3600000` | Max adaptive live-update interval |
| `prjxp.hub.stats-refresh-interval-ms` | `60000` | Project stats cache refresh interval |
| `prjxp.hub.max-tar-bytes` | `2147483648` | Max archive size |
| `prjxp.hub.max-extracted-bytes` | `4294967296` | Max extracted size |
| `prjxp.hub.max-entries` | `100000` | Max tar entries |
| `prjxp.hub.scan-max-depth` | `8` | Max recursive live-project scan depth |

Default live-scan excluded directory names:

- `.git`
- `.svn`
- `.hg`
- `node_modules`

### Project Statuses

Hub projects can have the following lifecycle statuses:

| Status | Meaning |
|---|---|
| `IMPORTING` | Project is registered/importing |
| `CHUNKING` | Source chunks are being generated |
| `EMBEDDING` | Embeddings are being computed/imported |
| `READY` | Searchable |
| `FAILED` | Last pipeline failed |

Reindex status values:

| Status | Meaning |
|---|---|
| `IDLE` | No reindex running |
| `CHUNKING` | Chunking phase |
| `EMBEDDING` | Embedding phase |
| `IMPORTING` | Swapping/importing into index |
| `DOCPIPE` | DocPipe documentation generation |
| `COMPLETE` | Finished successfully |
| `FAILED` | Failed |

## Project Configuration File

Hub projects are configured with either:

```text
prjxp.yaml
```

or:

```text
prjxp.yml
```

Example:

```yaml
name: my-project
rootDir: .
jsonlFile: px-chunks.jsonl
chunoWhiteList: java,ts
excludeDirNames: ".git,node_modules,target,build"
sourceDirNames: "src,app"
tibedBatchSize: 32
fulltext: true
liveUpdate: true
prompt: |
  Use the project-specific architecture guidelines when answering.
```

Supported fields:

| Field | Default | Description |
|---|---|---|
| `name` | directory name | Project name |
| `rootDir` | `.` | Source root |
| `jsonlFile` | `px-chunks.jsonl` | Chunk JSONL output |
| `chunoWhiteList` | `java,ts` | Chunker whitelist |
| `excludeDirNames` | project default | Excluded directories |
| `sourceDirNames` | project default | Source directory names |
| `tibedBatchSize` | `32` | Embedding/import batch size |
| `fulltext` | `false` | Store fulltext copy for lossless `readFile` |
| `embeddingsFile` | unset | Pre-embedded JSONL file |
| `liveUpdate` | `true` | Per-project live-update toggle |
| `prompt` | unset | Standing project instructions |

Special marker files:

| Marker | Meaning |
|---|---|
| `.prjxp-exclude` | Exclude this directory and subtree from live-project discovery |
| `.ready` | Required before pre-embedded projects are imported |

## Hub Project Types

### SNAPSHOT Projects

Snapshot projects are extracted into `projectsRoot`.

Typical sources:

- `.tar`
- `.tgz`
- `.tar.gz`
- uploaded archive through REST

Deleting a snapshot project removes:

- its indexed chunks
- its hub registry entry
- its extracted project directory

### LIVE Projects

Live projects are discovered under `importDir` by searching recursively for `prjxp.yaml` or `prjxp.yml`.

Deleting a live project removes:

- its indexed chunks
- its hub registry entry

It does **not** delete the source tree. If the marker file remains, the import poller will discover and enqueue it again.

To permanently remove a live project, delete or rename its `prjxp.yaml` / `prjxp.yml`.

## Hub Project API

Hub project endpoints are active only when:

```yaml
prjxp:
  hub:
    enabled: true
```

Base path:

```text
/api/projects
```

### List Projects

```http
GET /api/projects
```

Returns `ProjectInfo` objects with:

- name
- lifecycle status
- kind
- last error
- chunk count
- embedding count
- file count
- project YAML settings
- reindex status/progress
- live-update information

### Create Project

```http
POST /api/projects
```

Request body:

```json
{
  "name": "my-project",
  "kind": "LIVE",
  "rootDir": "/workspace/my-project",
  "chunoWhiteList": "java,ts",
  "tibedBatchSize": 32,
  "fulltext": true
}
```

Creates a project directory under `projectsRoot`, writes config, registers it, and enqueues indexing.

### Update Project

```http
PUT /api/projects/{name}
```

Request body:

```json
{
  "rootDir": "/workspace/my-project",
  "chunoWhiteList": "java,ts",
  "tibedBatchSize": 32,
  "prompt": "Project-specific instructions",
  "fulltext": true
}
```

Notes:

- changing `rootDir` triggers reindex
- changing `fulltext` takes effect on the next reindex
- blank `prompt` clears the prompt

### Delete Project

```http
DELETE /api/projects/{name}
```

Behavior:

- scoped index wipe by project name
- unregisters the project
- removes project directory only for `SNAPSHOT` projects
- never removes live source trees

### Reindex Project

```http
POST /api/projects/{name}/reindex
```

Starts a full async pipeline:

1. chunk
2. embed
3. scoped index swap

Returns `202 Accepted`.

### DocPipe Only

```http
POST /api/projects/{name}/docpipe
```

Regenerates DocPipe documentation and embeds generated documents without re-chunking or re-embedding code.

Returns `202 Accepted`.

### Reindex Status

```http
GET /api/projects/{name}/reindex-status
```

Returns:

```json
{
  "status": "EMBEDDING",
  "progress": 42
}
```

### Project Stats

```http
GET /api/projects/{name}/stats
```

Returns:

- chunk count
- embedding count
- file count
- root directory
- configured languages

### Import Embeddings for Existing Project

```http
POST /api/projects/{name}/importEmbeddings
```

Multipart form parameter:

| Name | Description |
|---|---|
| `file` | JSONL file containing embedded chunk records |

The server:

1. parses uploaded JSONL
2. checks embedding compatibility
3. wipes existing chunks for the project
4. imports embedded chunks
5. refreshes stats
6. publishes live JSONL for live projects

### Upload Source Archive

```http
POST /api/projects/upload-archive
```

Multipart form parameter:

| Name | Description |
|---|---|
| `file` | `.tar`, `.tgz`, or `.tar.gz` project archive |

The import poller extracts and enqueues the project.

### Upload Pre-Embedded Archive

```http
POST /api/projects/upload-embedded
```

Multipart form parameter:

| Name | Description |
|---|---|
| `file` | Pre-embedded archive |

Accepted file names:

```text
<ProjectName>-embedded.jsonl
<ProjectName>-embedded.gz
<ProjectName>-embedded.tgz
<ProjectName>-embedded.zip
```

The importer:

1. derives project name from the filename
2. validates the archive
3. extracts or copies the JSONL
4. updates `prjxp.yaml` with `embeddingsFile`
5. writes `.ready`
6. registers/reloads the project
7. enqueues import

### Live Scan Candidates

```http
GET /api/projects/live-scan
```

Returns immediate top-level live project candidates under `importDir`.

## Archive Import Security

Tar import rejects:

- absolute paths
- `..` path segments
- symbolic links
- hard links
- archives over configured size limits
- archives with too many entries
- extraction beyond configured extracted-size limit

Single-root tar archives have their top-level directory stripped automatically.

Pre-embedded TGZ and ZIP imports require exactly one `.jsonl` file entry.

## Pre-Embedded Projects

A project with `embeddingsFile` set in `prjxp.yaml` skips chunking and embedding.

Example:

```yaml
name: my-project
rootDir: .
embeddingsFile: my-project-embedded.jsonl
tibedBatchSize: 32
```

The pre-embedded pipeline:

1. reads the configured embeddings JSONL
2. samples records
3. checks local embedding compatibility
4. performs scoped reset for the project
5. imports the vectors into Lucene

Compatibility settings:

| Property | Default | Description |
|---|---:|---|
| `prjxp.hub.compatibility-sample-size` | `4` | Number of records sampled |
| `prjxp.hub.compatibility-threshold-high` | `0.995` | Compatible threshold |
| `prjxp.hub.compatibility-threshold-low` | `0.975` | Partial compatibility threshold |

If compatibility is below the low threshold, import is blocked.

## Live Updates

Live updates apply only to `LIVE` projects.

The scheduler:

- starts after a project is `READY`
- scans at the base interval
- doubles the interval after empty scans
- caps at the max interval
- resets to base interval when changes are found
- skips active indexing/reindexing runs
- requests a follow-up scan after a live update finishes

Live update detects:

- files to embed
- files to delete
- current source state

The server persists a sidecar source-state file in `liveJsonlDir`.

Live JSONL path:

```text
<liveJsonlDir>/<project>.live-embedded.jsonl
```

Live state path:

```text
<liveJsonlDir>/<project>.live-state.json
```

For live projects, the hub never writes chunk output into the live source tree.

## DocPipe Integration

When hub mode is enabled, the pipeline can run DocPipe after a successful root pipeline run if the project has `.dp/` configuration.

Behavior:

- DocPipe failures are non-fatal
- the code index can still become `READY`
- generated documents are embedded if a manifest exists
- document chunks are stamped with `docpipe_generated=true`
- re-embedding resets only generated document chunks for the affected files

Manual DocPipe-only refresh:

```http
POST /api/projects/{name}/docpipe
```

## Reader Behavior

### `readFile`

`readFile` reconstructs source from indexed chunks.

If a fulltext copy exists, it returns the lossless fulltext content first.

Otherwise, it:

1. combines indexed units
2. selects a MIME-specific `FileViewProvider`
3. falls back to a generic provider
4. pages by line offset/limit
5. applies the configured max output character cap

Result fields include:

- `file`
- `language`
- `chunkCount`
- `content`
- `truncated`
- `totalLines`
- `returnedLines`
- `candidates`
- `error`

### `readBySignature`

`readBySignature` searches symbol metadata in the index and reconstructs:

- method docs
- method bodies
- class/type frames

It supports section markers for:

- Java
- C#
- TypeScript
- Visual Basic
- GDScript

## Language Capabilities

Search capabilities are provided by `ChunkerSearchCapabilitiesProvider` implementations.

The built-in Java provider declares:

| Property | Value |
|---|---|
| language | `java` |
| MIME type | `text/x-java-code` |
| identifier regex | `[A-Za-z_$][A-Za-z0-9_$]*` |
| case-sensitive identifiers | `true` |

The metadata endpoint exposes available capabilities:

```http
GET /api/tools/meta-search-params
```

## Chat API

Base path:

```text
/api/chat
```

### Send Chat Message

```http
POST /api/chat/send
```

Request body:

```json
{
  "message": "Explain how embedded archive import works",
  "chatModelName": "default",
  "projectContext": "my-project",
  "useTools": true,
  "messages": [
    {
      "role": "user",
      "content": "Previous question"
    }
  ]
}
```

Response contains:

- assistant message
- optional RAG context
- optional tool-call records

### Streaming Chat

```http
POST /api/chat/send
Accept: text/event-stream
```

Streams SSE events for phases such as:

- RAG search
- LLM generation
- tool execution
- tool result
- completion
- error

### Chat Projects

```http
POST /api/chat/projects
```

Returns available project names.

### Chat Tools

```http
POST /api/chat/tools
```

Returns available tool names.

## Chat Tool Calling

When `useTools` is true, the chat service first tries native function calling if the configured chat model supports tools.

If native tool calling is unavailable or fails, the service falls back to a text protocol:

```text
TOOL_CALL[toolName](param1="value", param2="value")
```

The fallback parser also attempts to recover XML-style tool calls emitted by some models, but the preferred format is the exact `TOOL_CALL[...]` syntax.

Built-in tools are registered in-process through the central `ToolRegistry`.

## Global Model Configuration API

Base path:

```text
/api/globals
```

### Chat Models

List:

```http
GET /api/globals/chat-models
```

Create:

```http
POST /api/globals/chat-models
```

Update:

```http
PUT /api/globals/chat-models/{name}
```

Delete:

```http
DELETE /api/globals/chat-models/{name}
```

Test:

```http
POST /api/globals/chat-models/test
```

List provider models:

```http
GET /api/globals/chat-models/models
```

### Embedding Model

Get:

```http
GET /api/globals/embedding-model
```

Update:

```http
PUT /api/globals/embedding-model
```

Test:

```http
POST /api/globals/embedding-model/test
```

List provider models:

```http
GET /api/globals/embedding-model/models
```

### Server Types

```http
GET /api/globals/server-types
```

Secrets are masked as:

```text
********
```

Environment placeholders such as `${API_KEY}` are preserved.

## System API

Hub mode only.

Base path:

```text
/api/system
```

### Health

```http
GET /api/system/health
```

Returns:

- CPU usage approximation
- JVM memory
- disk space
- active connection placeholder

### Query Stats

```http
GET /api/system/query-stats
```

Returns:

- total queries
- average response time
- p95 response time
- cache hits
- cache misses
- recent queries

## CORS

CORS is configured for:

- `/api/**`
- `/mcp`
- `/v3/api-docs/**`

Default allowed origin pattern:

```text
http://localhost:*
```

Override with:

```properties
mcp.cors.allowed-patterns=http://localhost:*,https://example.com
```

## Error Handling

Unknown or non-searchable projects return explicit errors.

REST search endpoints typically return:

```json
{
  "error": "unknownProject",
  "detail": "No searchable store for project 'x'. Available projects: ..."
}
```

Common search errors:

| Error | Cause |
|---|---|
| `missingQuery` | Required query string is blank |
| `missingLanguage` | `byIndex` language is missing |
| `missingSearchParams` | `byIndex` has no lookup parameters |
| `unknownLanguage` | No search capability provider for the requested language |
| `unknownProject` | Project is missing or not searchable |

## Limits

General search result limits:

| Constant | Value |
|---|---:|
| default search limit | `10` |
| max search limit | `100` |
| default file-list limit | `50` |
| max file-list limit | `500` |

Invalid limits are clamped into the supported range.

## Recommended Client Workflow

### Discover Projects

```bash
curl "http://localhost:8080/api/tools/projects"
```

or through MCP:

```text
listProjects
```

### Find Relevant Code

```bash
curl "http://localhost:8080/api/tools/vectorSearch?query=how%20live%20updates%20are%20scheduled&project=my-project"
```

### Narrow by Exact String

```bash
curl "http://localhost:8080/api/tools/grep?query=enqueueLiveUpdate&project=my-project&language=java"
```

### Read Complete File

```bash
curl "http://localhost:8080/api/tools/files?path=*LiveUpdateScheduler.java&project=my-project"
```

Then:

```text
readFile(file="src/main/java/.../LiveUpdateScheduler.java", project="my-project")
```

### Read Complete Symbol

```text
readBySignature(method="enqueueLiveUpdate", project="my-project")
```

or:

```text
readBySignature(method="de.spraener.prjxp.mcp.hub.PipelineOrchestrator#enqueueLiveUpdate", project="my-project")
```

## Operational Notes

- Hub indexing uses a single FIFO worker because the shared Lucene index allows only one writer at a time.
- Full reindex keeps the old index available until the final swap.
- If a reindex fails before swap and an old index exists, the project can remain searchable.
- Live update failures keep the old index and retry later.
- Project deletion always performs a project-scoped index wipe.
- In a shared index, all destructive operations are scoped by project name.
- Fulltext chunks are excluded from vector and grep search hits and are used for lossless `readFile` reconstruction.

_This document was generated with Doc|Pipe_

