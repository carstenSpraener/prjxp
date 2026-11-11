# Configuration

PRJXP is configured through two Spring Boot YAML profiles and a set of environment variables. The application binds all `prjxp.*` properties to the `PrjXPConfig` class (prefix `prjxp`), which exposes them to the rest of the platform.

## Configuration Files

PRJXP ships two YAML profiles:

| File | Profile | Use |
|------|---------|-----|
| `application.yaml` | (default) | Local / development run from the repository root |
| `application-docker.yaml` | `docker` | Self-hosted Hub inside the Docker image |

Spring Boot selects the profile via the standard `SPRING_PROFILES_ACTIVE` variable. The Docker entrypoint sets it to `docker` automatically.

### Key differences between the two profiles

| Setting | Dev (`application.yaml`) | Docker (`application-docker.yaml`) |
|---------|--------------------------|-------------------------------------|
| `prjxp.embedding.type` | `OLLAMA` | `OPEN_AI` |
| `prjxp.embedding.apiBaseURL` | `http://192.168.188.92:11434` | `${EMBEDDING_API_BASE_URL:http://host.docker.internal:1234/v1}` |
| `spring.servlet.multipart.max-file-size` | `${PRJXP_MAX_UPLOAD_SIZE:100MB}` | `2GB` (hard-coded) |
| `prjxp.docs.dir` | `mcp-server/doc` (relative to repo root) | `${PRJXP_DOCS_DIR:/app/doc}` (baked into image) |
| `prjxp.hub.*` | Present (import-dir, projects-root, live-jsonl-dir) | Not present (Hub endpoints are the primary interface) |
| `prjxp.embeddingStoreLucene` | Not present | Present (indexPath, vectorDimension) |
| `spring.main.allow-bean-definition-overriding` | Not set | `true` |
| `server.tomcat.connection-linger` | `-1` | Not set |

### `PrjXPConfig` binding

All properties under the `prjxp` prefix are bound to `de.spraener.prjxp.common.config.PrjXPConfig`. The class exposes:

- `activeProject` (default `"cwd"`) – name of the project to operate on.
- `projects` – list of `ProjectDefinition` entries.
- `mcpServers` – list of external MCP server references.
- `embeddingStores` – list of named embedding-store references.
- `chatModels` – list of chat-model references.
- `embeddingStoreLucene` – Lucene vector-store settings.
- `embedding` – primary embedding-model settings.
- `embeddingModel` – user-selectable single embedding model (Globals panel).
- `readerMaxOutputChars` – cap for the `readFile` MCP tool (default `100 000`; `0` = uncapped).
- `transfer` – external-embedding transfer settings.

If `projects` is empty and `activeProject` is `"cwd"`, a fallback `ProjectDefinition` is created with `rootDir = "."`, `jsonlFile = "px-chunks.jsonl"`, `chunoWhiteList = "java,ts"`, `tibedBatchSize = 32`, and `tibedResetStore = true`.

## Environment Variables

The following environment variables override values in the YAML files. All are optional; the defaults shown apply when the variable is unset.

| Variable | Default | Scope | Description |
|----------|---------|-------|-------------|
| `SERVER_PORT` | `7007` | Both | TCP port for the HTTP server. |
| `PRJXP_ROOT_DIR` | `.` | Dev | Base directory for `.prjxp-data/` sub-directories (import, projects, chunks). |
| `PRJXP_MAX_UPLOAD_SIZE` | `100MB` | Dev | Spring multipart limit for archive / JSONL uploads. |
| `PRJXP_EMBEDDING_API_BASE_URL` | `http://localhost:1234/v1` | Dev | Base URL for the `embedding-model` reference. |
| `PRJXP_EMBEDDING_MODEL_NAME` | `mxbai-embed-large-v1` | Dev | Model name for the `embedding-model` reference. |
| `EMBEDDING_API_KEY` | *(none)* | Both | API key for OpenAI-compatible embedding / chat endpoints. |
| `PRJXP_CHAT_TIMEOUT_SECS` | `300` | Dev | Timeout for the default chat model. |
| `LUCENE_INDEX_PATH` | `/app-source/.prjxp-data/lucene-index` | Docker | Filesystem path for the Lucene vector index. |
| `LUCENE_VECTOR_DIMENSION` | `1024` | Docker | Dimensionality of stored vectors. |
| `EMBEDDING_API_BASE_URL` | `http://host.docker.internal:1234/v1` | Docker | Base URL for the OpenAI-compatible embedding endpoint. |
| `EMBEDDING_MODEL_NAME` | `mixedbread-ai/mxbai-embed-large-v1` | Docker | Model identifier for the embedding endpoint. |
| `EMBEDDING_TIMEOUT` | `180` | Docker | HTTP timeout (seconds) for embedding requests. |
| `MCP_CORS_ALLOWED_PATTERNS` | `http://10.*.*.*,http://172.16.*.*,http://192.16.*.*,http://localhost:*` | Docker | Comma-separated CORS origin patterns (no spaces after commas). |
| `PRJXP_HUB_URL` | `http://localhost:7007/mcp` | Docker | URL of the Hub MCP endpoint for external MCP clients. |
| `PRJXP_DOCS_DIR` | `/app/doc` | Docker | Source directory for the in-app documentation browser. |
| `PRJXP_TRANSFER_PASSWORD` | *(none)* | Both | Password for encrypted transfer mode. |

> **CORS note:** Browsers send an `Origin` header on POST/PUT/DELETE. Origins that do not match one of the patterns in `mcp.cors.allowed-patterns` are rejected with HTTP 403. When deploying behind a reverse proxy with HTTPS, add your public hostname(s) to the list.

## Embedding Configuration

PRJXP supports two embedding backends, selected by the `type` field:

| `type` value | Backend | Typical use |
|--------------|---------|-------------|
| `OLLAMA` | Ollama HTTP API | Local GPU inference (dev) |
| `OPEN_AI` | Any OpenAI-compatible `/v1/embeddings` endpoint | LM-Studio, vLLM, hosted APIs (Docker) |

### `prjxp.embedding` (primary embedding model)

```yaml
prjxp:
  embedding:
    type: OPEN_AI            # OLLAMA | OPEN_AI
    apiBaseURL: http://host.docker.internal:1234/v1
    modelName: mixedbread-ai/mxbai-embed-large-v1
    timeout: 180             # seconds
    apiKey: dummy            # from ${EMBEDDING_API_KEY}
    # Optional:
    dimensions: 1024         # output vector dimension (e.g. for text-embedding-3-large)
    maxSegmentsPerBatch: 32  # max texts per single HTTP request
    customHeaders: {}        # extra HTTP headers
    customQueryParams: {}    # extra query parameters
```

- **`maxSegmentsPerBatch`** – langchain4j's `OpenAiEmbeddingModel` defaults to 2048 texts per request. Capping it (default `32`) keeps each HTTP call small enough to complete within `timeout` on local models. Only applies to the `OPEN_AI` type.
- **`dimensions`** – optional; pass the desired output dimension for models that support it (e.g. `text-embedding-3-large`).

### `prjxp.embedding-model` (user-selectable, Globals panel)

A single, user-configurable embedding model reference exposed in the web UI:

```yaml
prjxp:
  embedding-model:
    server-type: "openai"
    provider-url: "${PRJXP_EMBEDDING_API_BASE_URL:http://localhost:1234/v1}"
    model-name: "${PRJXP_EMBEDDING_MODEL_NAME:mxbai-embed-large-v1}"
    api-key: "${EMBEDDING_API_KEY}"
    timeout-secs: 20
```

### `prjxp.embeddingStoreLucene` (Docker profile)

```yaml
prjxp:
  embeddingStoreLucene:
    indexPath: ${LUCENE_INDEX_PATH:/app-source/.prjxp-data/lucene-index}
    vectorDimension: ${LUCENE_VECTOR_DIMENSION:1024}
    name: "prjxp"
```

The `vectorDimension` must match the dimensionality of the vectors produced by the active embedding model.

### `prjxp.embedding-stores` (dev profile)

A list of named embedding-store references; one is marked `default`:

```yaml
prjxp:
  embedding-stores:
    - project-name: "prjxp"
      default: true
    - project-name: "kigbau"
```

## Chat Models

Chat models are declared as a list under `prjxp.chatModels` (Docker) or `prjxp.chat-models` (dev). Each entry carries a **stereotype** that the DocPipe and other subsystems use to select the appropriate model.

### Docker profile

```yaml
prjxp:
  chatModels:
    - stereotype: detailed
      serverType: lm-studio
      providerUrl: http://host.docker.internal:1234/v1
      modelName: qwen3.8-27b-splash
      timeoutSecs: 600
      apiKey: ${EMBEDDING_API_KEY}
      bodyProperties:
        enable_thinking: false

    - stereotype: documentation
      serverType: lm-studio
      providerUrl: http://host.docker.internal:1234/v1
      modelName: qwen3.8-27b-splash
      timeoutSecs: 600
      apiKey: ${EMBEDDING_API_KEY}
      bodyProperties:
        enable_thinking: false

    - stereotype: javadoc
      serverType: lm-studio
      providerUrl: http://host.docker.internal:1234/v1
      modelName: qwen3.8-27b-splash
      timeoutSecs: 600
      apiKey: ${EMBEDDING_API_KEY}
      bodyProperties:
        enable_thinking: false
```

> **Why `enable_thinking: false`?** Qwen3's thinking mode leaks the tool-call into the response body as XML, which breaks native function calling. DocPipe relies on function calling, so thinking is disabled for every stereotype.

### Dev profile

```yaml
prjxp:
  chat-models:
    - stereo-type: "default"
      server-type: "lm-studio"
      model-name: "qwen3.8-27B"
      api-key: "lm-studio"
      provider-url: "http://localhost:1234/v1"
      timeout-secs: ${PRJXP_CHAT_TIMEOUT_SECS:300}
```

The dev default timeout is 300 s (5 min) because a local 27 B model takes >60 s for document generation (FAQ, architecture, etc.). Override with `PRJXP_CHAT_TIMEOUT_SECS`.

## Hub, Storage & MCP Server

### Hub storage directories (dev profile)

All paths are relative to `PRJXP_ROOT_DIR` (default `.`):

| Property | Default path | Purpose |
|----------|-------------|---------|
| `prjxp.hub.import-dir` | `.prjxp-data/import` | Staging area for uploaded archives / JSONL files. |
| `prjxp.hub.projects-root` | `.prjxp-data/projects` | Extracted project trees. |
| `prjxp.hub.live-jsonl-dir` | `.prjxp-data/chunks` | Live chunk JSONL files. |

Set `PRJXP_ROOT_DIR=/data` to relocate all three under `/data/.prjxp-data/`.

### MCP server

```yaml
spring:
  ai:
    mcp:
      server:
        protocol: STREAMABLE
        name: "prjxp-mcp"      # dev only
        version: "1.0.0"       # dev only
```

The MCP endpoint is served at `/mcp` on the configured `server.port`. External MCP clients reference it via:

```yaml
prjxp:
  mcp-servers:
    - name: prjxp
      type: http
      url: ${PRJXP_HUB_URL:http://localhost:7007/mcp}
```

### CORS

```yaml
mcp:
  cors:
    allowed-patterns: ${MCP_CORS_ALLOWED_PATTERNS:http://10.*.*.*,http://172.16.*.*,http://192.16.*.*,http://localhost:*}
```

Patterns are comma-separated with **no spaces** after commas. The defaults cover RFC-1918 private ranges and `localhost` on any port.

### Graph rerank (dev profile)

```yaml
prjxp:
  graph:
    rerank:
      floor: 0.98
      b-graph: 5.0
      cap: 5.0
      w-base: 1.0
```

- **`floor`** – minimum degree-centrality weight. Calibrated on the PRJXP corpus: with `0.8` nearly every code chunk is demoted ~20 %; `0.98` keeps code on top while damping peripheral nodes (mean rank shift ≈ 7 positions).
- **`b-graph` / `cap`** – bounds for the graph-based score.
- **`w-base`** – base weight multiplier.

### Server

```yaml
server:
  port: ${SERVER_PORT:7007}
  forward-headers-strategy: framework
  tomcat:
    connection-linger: -1   # dev only; disables keep-alive linger
```

## Transfer Configuration

The `prjxp.transfer` block controls the external-embedding transfer pipeline (used when embedding is performed outside the Hub):

```yaml
prjxp:
  transfer:
    passwordEnv: PRJXP_TRANSFER_PASSWORD   # name of the env var holding the password
    encrypt: AUTO                          # AUTO | ALWAYS | NEVER
    input:                                 # source path (JSONL or directory)
    output:                                # destination path
    mode: STORE                            # STORE | QUERY
```

| Field | Description |
|-------|-------------|
| `passwordEnv` | Name of the environment variable that holds the transfer password. |
| `encrypt` | `AUTO` – encrypt only if a password is set; `ALWAYS` – always encrypt; `NEVER` – plaintext. |
| `input` | Path to the source data (JSONL file or directory). |
| `output` | Path where the result is written. |
| `mode` | `STORE` – write vectors to the store; `QUERY` – return similarity results. |

_This document was generated with Doc|Pipe._

