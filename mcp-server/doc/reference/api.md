# API

All PRJXP Hub REST endpoints are active only when the Hub is running in hub mode (`prjxp.hub.enabled=true`). The base URL is the port on which the Hub container is exposed (default `http://localhost:8080`).

Every endpoint returns JSON unless noted otherwise. The API is versioned under `/api/` and is split into three resource groups:

| Group | Base path | Purpose |
|-------|-----------|---------|
| Tools | `/api/tools` | Vector search, health check, project discovery |
| Projects | `/api/projects` | Project lifecycle, imports, reindexing, statistics |
| Docs | `/api/docs` | Generated documentation tree, content, and assets |

---

## Tools (`/api/tools`)

### `GET /api/tools/ping`

Health-check endpoint. Returns the literal string `pong!` to verify network connectivity.

```bash
curl http://localhost:8080/api/tools/ping
# → "pong!"
```

### `GET /api/tools/context`

Performs a vector search against a project's embedded code chunks and returns the enriched context as a plain-text string.

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| `userQuestion` | string | **yes** | — | A targeted, standalone search prompt optimized for vector retrieval. |
| `project` | string | **yes** | — | Project name. Call `GET /api/tools/projects` to discover valid names. |
| `similarity` | double | no | `0.85` | Similarity threshold, range `0.0`–`1.0`. |
| `maxResults` | int | no | `20` | Maximum number of results, range `1`–`20`. |
| `skeletonsOnly` | boolean | no | `false` | Return class skeletons instead of full method context. |

**Behavior notes**

- If **none** of the optional parameters are supplied, the legacy enrichment path is used (no explicit threshold/count/skeleton control).
- If **any** optional parameter is supplied, all three are applied with the defaults above for any that remain `null`.
- An unknown project name returns a plain-text error string: `ERROR: <message>`.

```bash
curl "http://localhost:8080/api/tools/context?userQuestion=How+is+the+chunking+pipeline+configured?&project=my-service&similarity=0.8&maxResults=10"
```

**Response:** `200 OK` with a `text/plain` body containing the retrieved and enriched source context.

### `GET /api/tools/projects`

Returns all registered projects with their current lifecycle status.

```bash
curl http://localhost:8080/api/tools/projects
```

**Response:** `200 OK` — JSON array of `ProjectInfo` objects (see [Data Models](#data-models)).

---

## Projects (`/api/projects`)

### List projects

```
GET /api/projects
```

Returns all projects with lifecycle status. Identical to `GET /api/tools/projects`.

**Response:** `200 OK` — `ProjectInfo[]`

### Create a project

```
POST /api/projects
Content-Type: application/json
```

**Request body** (`ProjectCreateRequest`):

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `name` | string | **yes** | Project identifier (used as directory name under the hub projects root). |
| `kind` | string | **yes** | Project kind enum value (e.g. `SNAPSHOT`, `LIVE`). |
| `rootDir` | string | no | Absolute path to the source tree. |
| `chunoWhiteList` | string | no | Comma-separated file extensions to include (e.g. `"java,ts"`). |
| `tibedBatchSize` | int | no | Batch size for the embedding pipeline. |
| `fulltext` | boolean | no | Enable full-text indexing. |

The endpoint creates the project directory, registers it, persists a `prjxp.yaml` config file, and enqueues the full pipeline (chunk → embed).

**Response:** `200 OK` — `ProjectInfo` with status `IMPORTING`.

### Update a project

```
PUT /api/projects/{name}
Content-Type: application/json
```

**Request body** (`ProjectUpdateRequest`) — all fields optional; only non-null values are applied:

| Field | Type | Description |
|-------|------|-------------|
| `rootDir` | string | New source-tree path. Triggers a reindex if changed. |
| `chunoWhiteList` | string | Comma-separated extensions. |
| `tibedBatchSize` | int | Embedding batch size. |
| `prompt` | string | Custom enrichment prompt (empty string clears it). |
| `fulltext` | boolean | Full-text indexing flag. Takes effect on next reindex. |
| `embedDependencies` | boolean | Dependency-embedding flag. Takes effect on next reindex. |

Changes are persisted to the project's `prjxp.yaml`. A reindex is enqueued **only** when `rootDir` changes.

**Response:** `200 OK` — updated `ProjectInfo`.
**Errors:** `404` if the project does not exist.

### Delete a project

```
DELETE /api/projects/{name}
```

- **SNAPSHOT** projects: scoped index wipe + recursive removal of the hub-managed project directory.
- **LIVE** projects: scoped index wipe + unregistration only. The source tree is never touched. The marker file survives, so the import poller will re-discover and re-enqueue the project on its next cycle. To permanently remove a live project, delete or rename its `prjxp.yaml` (or move it out of the import tree).

**Response:** `204 No Content`.
**Errors:** `404` (via `UnknownProjectException`) if the project is not registered.

### Reindex a project

```
POST /api/projects/{name}/reindex
```

Enqueues a full pipeline re-run (chunk → scoped index reset → embed). The pipeline executes asynchronously on the single worker thread.

**Response:** `202 Accepted`.
**Errors:** `404` if the project is unknown.

### DocPipe-only re-run

```
POST /api/projects/{name}/docpipe
```

Regenerates the project's documentation and re-embeds the generated documents **without** re-chunking or re-embedding the source code. Use to selectively refresh DocPipe results.

**Response:** `202 Accepted`.
**Errors:** `404` if the project is unknown.

### Embed dependencies

```
POST /api/projects/{name}/embed-dependencies
```

Manually triggers dependency materialization (the "Embed Dependencies" action). Non-blocking: materialization runs on a dedicated executor.

**Response:** `202 Accepted`.
**Errors:** `404` if the project is unknown.

### Reimport a library project

```
POST /api/projects/{name}/reimport
```

Force re-download and re-extract of a library (Maven dependency) project. Only valid for projects that carry a `mavenDependency` origin marker.

**Response:** `202 Accepted`.
**Errors:** `404` if the project is not a library project.

### Import a GitHub repository

```
POST /api/projects/import-github
Content-Type: application/json
```

**Request body** (`GithubImportRequest`):

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `url` | string | **yes** | Git clone URL (HTTPS or SSH). |
| `token` | string | no | Personal access token for private repositories. |

The clone runs asynchronously. The project appears with status `IMPORTING`, then transitions to `READY`.

**Response:** `202 Accepted`.
**Errors:** `400 Bad Request` if `url` is null or blank.

### Update a GitHub project

```
POST /api/projects/{name}/update-github
```

Triggers `git pull` + re-embed for a GitHub-origin project.

**Response:** `202 Accepted`.
**Errors:** `404` if the project is unknown; `400` if the project is not a GitHub project (no `gitUrl` in its definition).

### List GitHub operations

```
GET /api/projects/github-operations
```

Returns recent asynchronous GitHub operations (imports, updates) for UI feedback polling.

**Response:** `200 OK` — `GithubOperation[]`

### Reindex status

```
GET /api/projects/{name}/reindex-status
```

Returns the current reindex status and progress percentage.

**Response:** `200 OK`

```json
{
  "status": "CHUNKING",
  "progress": 42
}
```

| Field | Type | Description |
|-------|------|-------------|
| `status` | string | Enum name of the current pipeline stage. |
| `progress` | int | Progress percentage (0–100). |

### Import pre-computed embeddings

```
POST /api/projects/{name}/importEmbeddings
Content-Type: multipart/form-data
```

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | file (JSONL) | **yes** | JSONL file where each line is an `EmbeddedChunkRecord` object. |

**Behavior:**

1. Parses all records from the uploaded JSONL (unparseable lines are skipped with a warning).
2. Runs a compatibility check: compares a sample of imported vectors against locally computed embeddings using cosine similarity.
   - **INCOMPATIBLE** → `400` with a descriptive message (different model or hardware).
   - **PARTIALLY_COMPATIBLE** → import proceeds with a warning logged.
   - **COMPATIBLE** → import proceeds.
3. Wipes the project's existing chunks (scoped reset by project name).
4. Imports in batches sized by the project's `tibedBatchSize`.
5. Refreshes in-memory stats and publishes a live JSONL snapshot.

**Response:** `200 OK` — plain-text summary, e.g. `Imported 1523/1523 chunks for project 'my-service'`.
**Errors:** `400` (no valid records, incompatible model, missing definition); `404` (unknown project).

```bash
curl -X POST \
  -F "file=@embeddings.jsonl" \
  http://localhost:8080/api/projects/my-service/importEmbeddings
```

### Upload a source archive

```
POST /api/projects/upload-archive
Content-Type: multipart/form-data
```

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | file (TAR/ZIP) | **yes** | Archive to import. The project name is derived from the file name (extension stripped). |

The file is placed in the hub import directory; the import poller discovers it on its next cycle.

**Response:** `200 OK` — `ProjectInfo` with status `IMPORTING`.

### Upload a pre-embedded archive

```
POST /api/projects/upload-embedded
Content-Type: multipart/form-data
```

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `file` | file (JSONL, GZ, TGZ, ZIP) | **yes** | Pre-embedded archive. The project name is derived from the file name. |

The archive is validated before processing. The original file name is preserved (path-traversal segments are stripped).

**Response:** `200 OK` — `ProjectInfo`.
**Errors:** `400` (invalid archive); `500` (processing failure).

### Live scan

```
GET /api/projects/live-scan
```

Scans the hub import directory for sub-directories that look like live projects (contain a `prjxp.yaml` or `prjxp.yml`). Returns candidates with detected file extensions.

**Response:** `200 OK` — `LiveProjectCandidate[]`

```json
[
  {
    "name": "my-service",
    "path": "/data/import/my-service",
    "hasConfig": true,
    "languages": ["java", "xml", "yml"]
  }
]
```

### Project statistics

```
GET /api/projects/{name}/stats
```

Returns detailed index statistics for a single project.

**Response:** `200 OK` — `ProjectStats`

```json
{
  "name": "my-service",
  "chunkCount": 1523,
  "embeddingCount": 1523,
  "fileCount": 87,
  "rootDir": "/data/projects/my-service/src",
  "languages": ["java", "ts"]
}
```

**Errors:** `404` if the project is not found.

---

## Documentation (`/api/docs`)

### `GET /api/docs/tree`

Returns the full documentation tree: folders, markdown documents, and H2-level chapters.

```bash
curl http://localhost:8080/api/docs/tree
```

**Response:** `200 OK` — `DocsNode` (nested tree structure).

### `GET /api/docs/content`

Returns the raw markdown of a single document.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `path` | string | **yes** | Path relative to the docs root (e.g. `getting-started/index.md`). |

**Response:** `200 OK` — `text/markdown;charset=UTF-8` body.
**Errors:** `404` if the path does not resolve to an existing document.

```bash
curl "http://localhost:8080/api/docs/content?path=getting-started/index.md"
```

### `GET /api/docs/file`

Returns a static asset (image, CSS, etc.) from the docs root.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `path` | string | **yes** | Path relative to the docs root (e.g. `images/architecture.png`). |

**Response:** `200 OK` — binary body with the appropriate `Content-Type`.
**Errors:** `404` if the asset does not exist.

```bash
curl "http://localhost:8080/api/docs/file?path=images/architecture.png" -o architecture.png
```

---

## Error Handling

| HTTP Status | Condition |
|-------------|-----------|
| `400 Bad Request` | Invalid request body, missing required parameter, incompatible embeddings, non-GitHub project on update, invalid file name. |
| `404 Not Found` | Unknown project name, missing project definition, missing doc path/asset. |
| `500 Internal Server Error` | I/O failure during directory creation, file upload, or archive processing. |

For unknown project names on lifecycle endpoints (`DELETE`, `POST …/reindex`, `POST …/docpipe`, `POST …/embed-dependencies`, `POST …/importEmbeddings`, `POST …/update-github`), the API throws `UnknownProjectException`, which surfaces as a `404` with the list of available project names in the error body.

The `/api/tools/context` endpoint does **not** use HTTP error codes for unknown projects; it returns a `200` with a plain-text body prefixed `ERROR: <message>`.

---

## Data Models

### `ProjectInfo`

Returned by list, create, and update endpoints.

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | Project identifier. |
| `status` | string | Lifecycle status (e.g. `IMPORTING`, `READY`, `FAILED`). |
| `kind` | string | Project kind (`SNAPSHOT`, `LIVE`). |
| `lastError` | string \| null | Last pipeline error message. |
| `rootDir` | string \| null | Source-tree path. |
| `jsonlFile` | string \| null | Path to the chunk JSONL file. |
| `chunoWhiteList` | string \| null | Comma-separated file extensions. |
| `tibedBatchSize` | int \| null | Embedding batch size. |
| `embeddingsFile` | string \| null | Path to the embeddings file. |
| `tibedResetStore` | boolean | Whether the store is reset before embedding. |
| `reindexStatus` | string | Current reindex pipeline stage. |
| `reindexProgress` | int | Reindex progress (0–100). |
| `prompt` | string \| null | Custom enrichment prompt. |
| `fulltext` | boolean | Full-text indexing enabled. |
| `embedDependencies` | boolean | Dependency embedding enabled. |
| `mavenDependency` | string \| null | Maven GAV if the project is a library import. |
| `gitUrl` | string \| null | Git clone URL if the project is a GitHub import. |
| `hasGitToken` | boolean | Whether a git token is configured. |

### `ProjectStats`

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | Project name. |
| `chunkCount` | long | Number of chunks in the index. |
| `embeddingCount` | long | Number of embeddings (equals `chunkCount`). |
| `fileCount` | int | Number of distinct source files. |
| `rootDir` | string \| null | Source-tree path. |
| `languages` | string[] | Detected file extensions (default `"java","ts"` if no whitelist set). |

### `LiveProjectCandidate`

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | Directory name. |
| `path` | string | Absolute path. |
| `hasConfig` | boolean | Whether a `prjxp.yaml`/`prjxp.yml` exists. |
| `languages` | string[] | File extensions detected in the tree. |

_This document was generated with Doc|Pipe._

