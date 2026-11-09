# API

PRJXP Hub exposes a REST API under the base path `/api`. All endpoints are active only when the Hub is enabled (`prjxp.hub.enabled=true` in the application configuration). The API is split into three resource groups:

| Group | Base path | Purpose |
|-------|-----------|---------|
| Tools | `/api/tools` | Vector-search retrieval for AI agents (MCP) |
| Projects | `/api/projects` | Project lifecycle management |
| Documentation | `/api/docs` | Browse generated project documentation |

All endpoints accept and return JSON unless noted otherwise. File-upload endpoints use `multipart/form-data`.

## Tools (`/api/tools`)

The Tools group provides the retrieval interface that AI agents (via MCP or direct HTTP) use to query project source code.

### `GET /api/tools/ping`

Health-check endpoint. Returns the literal string `pong!`.

```
GET /api/tools/ping
→ "pong!"
```

### `GET /api/tools/context`

Performs a vector search over the project's embedded code chunks and returns the most relevant context as a plain-text string.

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `userQuestion` | string | **yes** | — | A targeted, standalone search prompt optimized for vector retrieval. |
| `project` | string | no | `default` | Project name to search. |
| `similarity` | float | no | `0.85` | Similarity threshold, range `0.0`–`1.0`. |
| `maxResults` | int | no | `20` | Maximum number of chunks returned, range `1`–`20`. |
| `skeletonsOnly` | bool | no | `false` | Return class skeletons instead of full method context. |

**Behavior:**

- If `project` is not registered, the response body is the string `ERROR: <message>` (HTTP 200, not an error status).
- If none of `similarity`, `maxResults`, or `skeletonsOnly` is supplied, the legacy enrichment path is used (fixed internal defaults).
- If any of the three tuning parameters is supplied, the explicit overload is used; out-of-range values are clamped to the defaults above.

```
GET /api/tools/context?userQuestion=How+is+the+chunking+pipeline+configured&project=my-project&similarity=0.8&maxResults=10
→ "<enriched context text>"
```

### `GET /api/tools/projects`

Returns all registered projects with their current lifecycle status.

```
GET /api/tools/projects
→ [ { "name": "...", "status": "READY", ... }, ... ]
```

## Projects (`/api/projects`)

The Projects group covers the full project lifecycle: creation, configuration, indexing, deletion, and import.

### List projects

```
GET /api/projects
→ 200  [ ProjectInfo, ... ]
```

### Create a project

```
POST /api/projects
Content-Type: application/json
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | string | **yes** | Project identifier (also used as directory name). |
| `kind` | string | **yes** | `SNAPSHOT` or `LIVE`. |
| `rootDir` | string | no | Path to the source tree. |
| `chunoWhiteList` | string | no | Comma-separated file extensions to chunk (e.g. `java,ts`). |
| `tibedBatchSize` | int | no | Embedding batch size. |
| `fulltext` | bool | no | Enable full-text indexing. |

The endpoint creates the project directory, registers it, persists the `prjxp.yaml` definition, and enqueues the indexing pipeline. Returns a `ProjectInfo` with status `IMPORTING`.

### Update a project

```
PUT /api/projects/{name}
Content-Type: application/json
```

| Field | Type | Description |
|-------|------|-------------|
| `rootDir` | string | New source-tree path. Triggers a reindex if changed. |
| `chunoWhiteList` | string | Comma-separated extensions. |
| `tibedBatchSize` | int | Embedding batch size. |
| `prompt` | string | Custom enrichment prompt (empty string clears it). |
| `fulltext` | bool | Full-text indexing flag (takes effect on next reindex). |
| `embedDependencies` | bool | Dependency-embedding flag (takes effect on next reindex). |

Returns the updated `ProjectInfo`. Returns **404** if the project does not exist.

### Delete a project

```
DELETE /api/projects/{name}
→ 204 No Content
```

- **SNAPSHOT** projects: scoped index wipe + recursive removal of the hub-managed project directory.
- **LIVE** projects: scoped index wipe + unregistration only. The source tree is never touched. The `prjxp.yaml` marker file survives, so the import poller will re-discover the project on its next cycle. To permanently remove a live project, delete or rename its `prjxp.yaml`.

### Reindex

```
POST /api/projects/{name}/reindex
→ 202 Accepted
```

Enqueues a full pipeline re-run (chunk → scoped reset → embed). The pipeline executes asynchronously on the Hub's single worker. Returns **404** (`UnknownProjectException`) if the project is not registered.

### DocPipe re-run

```
POST /api/projects/{name}/docpipe
→ 202 Accepted
```

Regenerates the project's documentation and re-embeds the generated documents **without** re-chunking or re-embedding the code. Use to selectively refresh DocPipe results. Returns **404** if the project is unknown.

### Embed dependencies

```
POST /api/projects/{name}/embed-dependencies
→ 202 Accepted
```

Triggers dependency materialization (the "Embed Dependencies" action). Non-blocking; materialization runs on a dedicated executor. Returns **404** if the project is unknown.

### Reimport a library project

```
POST /api/projects/{name}/reimport
→ 202 Accepted
```

Force re-download and re-extract of a Maven-dependency library project. Gated on the `mavenDependency` origin marker. Returns **404** if the project is not a hub-generated library.

### Import a GitHub repository

```
POST /api/projects/import-github
Content-Type: application/json
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `url` | string | **yes** | Git clone URL. |
| `token` | string | no | Personal access token for private repos. |

The clone runs asynchronously. The project appears as `IMPORTING`, then `READY`. Returns **202** on success, **400** if `url` is blank.

### Update a GitHub project

```
POST /api/projects/{name}/update-github
→ 202 Accepted
```

Triggers `git pull` + re-embed for a GitHub-origin project. Returns **400** if the project is not a GitHub project, **404** if unknown.

### GitHub operations log

```
GET /api/projects/github-operations
→ 200  [ GithubOperation, ... ]
```

Returns recent async GitHub import/update operations. Polled by the Web UI for progress feedback.

### Reindex status

```
GET /api/projects/{name}/reindex-status
→ 200  { "status": "CHUNKING", "progress": 42 }
```

Returns the current pipeline status (enum name) and progress percentage (0–100) for a project.

### Import pre-computed embeddings

```
POST /api/projects/{name}/importEmbeddings
Content-Type: multipart/form-data
```

| Part | Type | Description |
|------|------|-------------|
| `file` | file | JSONL file of `EmbeddedChunkRecord` objects. |

The endpoint:

1. Parses all records from the uploaded JSONL.
2. Runs a compatibility check (cosine similarity against locally computed vectors). If the verdict is `INCOMPATIBLE`, returns **400** with a diagnostic message.
3. Wipes the project's existing chunks (scoped reset).
4. Imports in batches sized by the project's `tibedBatchSize`.
5. Refreshes in-memory stats and publishes a live JSONL snapshot.

Returns **200** with a summary string: `"Imported N/M chunks for project 'name'"`.

### Upload a source archive

```
POST /api/projects/upload-archive
Content-Type: multipart/form-data
```

| Part | Type | Description |
|------|------|-------------|
| `file` | file | TAR or ZIP archive of a source tree. |

The archive is placed in the Hub's import directory. The import poller discovers it on its next cycle. Returns a `ProjectInfo` with status `IMPORTING`.

### Upload a pre-embedded archive

```
POST /api/projects/upload-embedded
Content-Type: multipart/form-data
```

| Part | Type | Description |
|------|------|-------------|
| `file` | file | JSONL, GZ, TGZ, or ZIP archive of pre-embedded chunks. |

The project name is derived from the file name. The archive is validated before processing. Returns **200** with the resulting `ProjectInfo`, **400** on validation failure, **500** on processing failure.

### Live scan

```
GET /api/projects/live-scan
→ 200  [ LiveProjectCandidate, ... ]
```

Scans the Hub's import directory for sub-directories that may contain live projects. Each candidate includes the directory name, path, whether a `prjxp.yaml`/`prjxp.yml` config exists, and detected file extensions.

### Project statistics

```
GET /api/projects/{name}/stats
→ 200  ProjectStats
```

Returns chunk count, embedding count, file count, root directory, and the active language whitelist for a project. Returns **404** if the project is not found.

## Documentation (`/api/docs`)

The Documentation group serves the generated project documentation (produced by DocPipe) for browsing in the Web UI or via API.

### `GET /api/docs/tree`

Returns the full documentation tree: folders, markdown documents, and H2-level chapters.

```
GET /api/docs/tree
→ 200  DocsNode (nested tree structure)
```

### `GET /api/docs/content`

Returns the raw markdown of a single document.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `path` | string | **yes** | Path relative to the docs root (e.g. `api/overview.md`). |

```
GET /api/docs/content?path=api/overview.md
→ 200  Content-Type: text/markdown;charset=UTF-8
→ "<markdown body>"
```

Returns **404** if the path does not resolve to an existing document.

### `GET /api/docs/file`

Returns a static asset (image, CSS, etc.) from the docs root.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `path` | string | **yes** | Path relative to the docs root (e.g. `images/architecture.png`). |

```
GET /api/docs/file?path=images/architecture.png
→ 200  Content-Type: image/png
→ <binary data>
```

Returns **404** if the asset does not exist.

## Error Handling

| HTTP status | When |
|-------------|------|
| **200** | Successful GET / PUT / POST (synchronous). |
| **202** | Async operations accepted (reindex, docpipe, embed-dependencies, reimport, GitHub import/update). |
| **204** | Successful DELETE. |
| **400** | Bad request: missing/blank URL, invalid file name, incompatible embeddings, invalid embedded archive. |
| **404** | Project not found, not a library project, not a GitHub project, doc/asset path not found. |
| **500** | Internal error: directory creation failure, upload I/O failure, archive processing failure. |

**Note on the Tools endpoint:** `GET /api/tools/context` does **not** return an HTTP error for an unknown project. Instead it returns HTTP 200 with a body of `ERROR: <message>`. Clients should inspect the response body for the `ERROR:` prefix.

_This document was generated with Doc|Pipe._

