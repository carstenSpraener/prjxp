# Configuration

PRJXP is configured through a combination of `application.yaml` files and environment variables. The same logical settings appear in two physical files depending on how you run the platform:

| Context | File | Notes |
|---|---|---|
| Development (repo root) | `application.yaml` | Paths are relative to the working directory; the docs browser reads from `mcp-server/doc`. |
| Docker Hub | `application.yaml` (baked into the image) | Paths are absolute (`/app/…`); the docs browser reads from `/app/doc` (override via bind mount). |

Both files are standard Spring Boot property sources. The Java class `PrjXPConfig` (annotated `@ConfigurationProperties(prefix = "prjxp")`) binds every `prjxp.*` key. Spring's own keys (`server.*`, `spring.*`, `mcp.*`) are resolved by the framework directly.

## Environment Variables

All overridable values are exposed as environment variables with a sensible default. Set them in your shell (dev) or in the Docker `run` / `compose` invocation.

| Variable | Default | Scope | Purpose |
|---|---|---|---|
| `SERVER_PORT` | `7007` | both | TCP port for the Hub HTTP server. |
| `PRJXP_ROOT_DIR` | `.` | dev | Base directory for `.prjxp-data/` sub-directories (import, projects, chunks). |
| `PRJXP_MAX_UPLOAD_SIZE` | `100MB` | dev | Spring multipart limit for archive / JSONL uploads. |
| `PRJXP_EMBEDDING_API_BASE_URL` | `http://localhost:1234/v1` | dev | Base URL for the OpenAI-compatible embedding endpoint. |
| `PRJXP_EMBEDDING_MODEL_NAME` | `mxbai-embed-large-v1` | dev | Model name sent to the embedding endpoint. |
| `PRJXP_CHAT_TIMEOUT_SECS` | `300` | dev | Per-request timeout for the default chat model. |
| `EMBEDDING_API_KEY` | *(none)* | both | API key for the embedding provider. |
| `EMBEDDING_API_BASE_URL` | `http://host.docker.internal:1234/v1` | Docker | Embedding endpoint reachable from inside the container. |
| `EMBEDDING_MODEL_NAME` | `mixedbread-ai/mxbai-embed-large-v1` | Docker | Model name for the Docker embedding config. |
| `EMBEDDING_TIMEOUT` | `180` | Docker | Embedding HTTP timeout in seconds. |
| `LUCENE_INDEX_PATH` | `/app-source/.prjxp-data/lucene-index` | Docker | Filesystem path for the Lucene vector index. |
| `LUCENE_VECTOR_DIMENSION` | `1024` | Docker | Dimensionality of stored vectors. |
| `PRJXP_HUB_URL` | `http://localhost:7007/mcp` | Docker | URL the MCP client uses to reach the Hub. |
| `PRJXP_DOCS_DIR` | `/app/doc` | Docker | Source directory for the in-app documentation browser. |
| `MCP_CORS_ALLOWED_PATTERNS` | `http://10.*.*.*,http://172.16.*.*,http://192.168.*.*,http://localhost:*` | both | Comma-separated CORS origin patterns (no spaces after commas). |
| `PRJXP_TRANSFER_PASSWORD` | *(none)* | both | Password for encrypted transfer mode. |

> **Docker note:** Inside the container, `localhost` refers to the container itself. Use `host.docker.internal` (or the Docker bridge IP) to reach services running on the host, e.g. LM Studio on port 1234.

## Hub & Project Settings

All keys live under the `prjxp.hub` and `prjxp` prefixes.

### Hub directories (dev)

```yaml
prjxp:
  hub:
    enabled: true
    import-dir: ${PRJXP_ROOT_DIR:.}/.prjxp-data/import
    projects-root: ${PRJXP_ROOT_DIR:.}/.prjxp-data/projects
    live-jsonl-dir: ${PRJXP_ROOT_DIR:.}/.prjxp-data/chunks
```

| Property | Default | Description |
|---|---|---|
| `prjxp.hub.enabled` | `true` | Toggles the Hub feature. |
| `prjxp.hub.import-dir` | `./.prjxp-data/import` | Where uploaded archives are unpacked. |
| `prjxp.hub.projects-root` | `./.prjxp-data/projects` | Root for per-project working directories. |
| `prjxp.hub.live-jsonl-dir` | `./.prjxp-data/chunks` | Location of live chunk JSONL files. |

### Active project & project list

```yaml
prjxp:
  active-project: "cwd"
  projects:
    - name: "my-service"
      root-dir: /opt/src/my-service
      jsonl-file: px-chunks.jsonl
      chuno-white-list: "java,ts"
      tibed-batch-size: 32
      tibed-reset-store: true
```

- `active-project` – name of the project selected in the UI. Defaults to `"cwd"`, which triggers a built-in fallback definition (root `.`).
- `projects[]` – list of `ProjectDefinition` entries. If the list is empty and the active project is `"cwd"`, a fallback with `rootDir="."`, `jsonlFile="px-chunks.jsonl"`, `chunoWhiteList="java,ts"`, `tibedBatchSize=32`, `tibedResetStore=true` is created automatically.

### Embedding stores

```yaml
prjxp:
  embedding-stores:
    - project-name: "prjxp"
      default: true
    - project-name: "kigbau"
```

Each entry maps a project name to an embedding store. Exactly one entry should carry `default: true`.

### Lucene vector store

```yaml
prjxp:
  embedding-store-lucene:
    index-path: ${LUCENE_INDEX_PATH:/app-source/.prjxp-data/lucene-index}
    vector-dimension: ${LUCENE_VECTOR_DIMENSION:1024}
    name: "prjxp"
```

| Property | Default | Description |
|---|---|---|
| `index-path` | `.prjxp-data/lucene-index` | Directory for the Lucene index. |
| `vector-dimension` | `1024` | Must match the embedding model's output dimension. |
| `name` | `prjxp` | Logical store name. |

### Reader, semantic graph, and signature

```yaml
prjxp:
  reader-max-output-chars: 100000
  semantic-graph:
    enabled: true
    calibrate: false
    gamma: 1.0
    threshold: 0.85
    k: 20
    require-mutual: true
  signature:
    enabled: true
    top-n: 8
    min-term-length: 3
```

- `reader-max-output-chars` – maximum characters returned by the `readFile` MCP tool (`0` = uncapped).
- `semantic-graph` – controls the semantic graph construction (enabled, calibration flag, similarity threshold, top-k neighbours, mutual-edge requirement).
- `signature` – TF-IDF / c-TF-IDF keyword layer written as chunk metadata after embedding.

### Transfer (external embedding)

```yaml
prjxp:
  transfer:
    password-env: PRJXP_TRANSFER_PASSWORD
    encrypt: AUTO
    input: /path/to/input
    output: /path/to/output
    mode: STORE
```

| Property | Default | Description |
|---|---|---|
| `password-env` | `PRJXP_TRANSFER_PASSWORD` | Name of the env var holding the transfer password. |
| `encrypt` | `AUTO` | `AUTO`, `ON`, or `OFF`. |
| `mode` | `STORE` | `STORE` or `EXPORT`. |

## Embedding Configuration

Two parallel blocks exist; which one is active depends on the `type` field.

### Ollama (dev default)

```yaml
prjxp:
  embedding:
    type: OLLAMA
    api-base-url: http://192.168.188.92:11434
    model-name: mxbai-embed-large
    timeout: 60
    max-segments-per-batch: 32
```

### OpenAI-compatible (Docker default)

```yaml
prjxp:
  embedding:
    type: OPEN_AI
    api-base-url: ${EMBEDDING_API_BASE_URL:http://host.docker.internal:1234/v1}
    model-name: ${EMBEDDING_MODEL_NAME:mixedbread-ai/mxbai-embed-large-v1}
    timeout: ${EMBEDDING_TIMEOUT:180}
    api-key: ${EMBEDDING_API_KEY:dummy}
    max-segments-per-batch: 250
```

| Property | Description |
|---|---|
| `type` | `OLLAMA` or `OPEN_AI`. Selects the client implementation. |
| `api-base-url` | Base URL of the embedding service. |
| `model-name` | Model identifier sent in the request body. |
| `timeout` | HTTP timeout in seconds. |
| `api-key` | Bearer token (OpenAI-compatible only). |
| `dimensions` | *(optional)* Output vector dimension for models like `text-embedding-3-large`. |
| `trust-all-certs` | Disables TLS certificate validation for self-signed local providers. |
| `custom-headers` | Map of extra HTTP headers. |
| `custom-query-params` | Map of extra query parameters. |
| `max-segments-per-batch` | Maximum texts per single embedding HTTP request. The per-project `tibedBatchSize` is split at this size. Lower values (e.g. 32) keep requests small enough for local models; higher values (e.g. 250) suit hosted APIs. |

### User-configurable embedding model (Globals panel)

```yaml
prjxp:
  embedding-model:
    server-type: "openai"
    provider-url: "${PRJXP_EMBEDDING_API_BASE_URL:http://localhost:1234/v1}"
    model-name: "${PRJXP_EMBEDDING_MODEL_NAME:mxbai-embed-large-v1}"
    api-key: "${EMBEDDING_API_KEY}"
    timeout-secs: 20
```

This is the single embedding model exposed in the Hub's Globals UI panel. It is independent of the `prjxp.embedding` block used during import.

## Chat Models

Chat models are declared as a list. The dev file uses kebab-case keys; the Docker file uses camelCase. Both bind to the same `PrjXPChatModelReference` class.

### Dev (single default model)

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

### Docker (multiple stereotypes)

```yaml
prjxp:
  chat-models:
    - stereotype: detailed
      server-type: lm-studio
      provider-url: http://host.docker.internal:1234/v1
      model-name: qwen3.8-27b-splash
      timeout-secs: 600
      api-key: ${EMBEDDING_API_KEY}
      body-properties:
        enable_thinking: false

    - stereotype: documentation
      server-type: lm-studio
      provider-url: http://host.docker.internal:1234/v1
      model-name: qwen3.8-27b-splash
      timeout-secs: 600
      api-key: ${EMBEDDING_API_KEY}
      body-properties:
        enable_thinking: false

    - stereotype: javadoc
      server-type: lm-studio
      provider-url: http://host.docker.internal:1234/v1
      model-name: qwen3.8-27b-splash
      timeout-secs: 600
      api-key: ${EMBEDDING_API_KEY}
      body-properties:
        enable_thinking: false
```

| Property | Description |
|---|---|
| `stereotype` / `stereo-type` | Logical role: `default`, `detailed`, `documentation`, `javadoc`, etc. |
| `server-type` / `serverType` | Provider backend (e.g. `lm-studio`). |
| `model-name` / `modelName` | Model identifier. |
| `provider-url` / `providerUrl` | Base URL of the chat endpoint. |
| `api-key` / `apiKey` | Auth token (LM Studio accepts the literal string `lm-studio`). |
| `timeout-secs` / `timeoutSecs` | Per-request timeout. A local 27 B model can exceed 60 s for doc generation; raise this accordingly. |
| `body-properties` | Extra JSON fields merged into the request body. `enable_thinking: false` is required for Qwen 3 models because thinking mode breaks native function calling (the tool call leaks into `content` as XML). |

## MCP Server, CORS & Upload Limits

### MCP server

```yaml
spring:
  ai:
    mcp:
      server:
        protocol: STREAMABLE
        name: "prjxp-mcp"
        version: "1.0.0"
```

The Hub exposes a single MCP server over the **STREAMABLE** transport. The `name` and `version` are informational metadata returned in the MCP handshake.

### MCP client references (Docker)

```yaml
prjxp:
  mcp-servers:
    - name: prjxp
      type: http
      url: ${PRJXP_HUB_URL:http://localhost:7007/mcp}
```

Used by the Hub to call back into its own MCP endpoint (e.g. for tool execution during RAG).

### CORS

```yaml
mcp:
  cors:
    allowed-patterns: ${MCP_CORS_ALLOWED_PATTERNS:http://10.*.*.*,http://172.16.*.*,http://192.168.*.*,http://localhost:*}
```

- Comma-separated list of origin patterns. **No spaces after commas.**
- Browsers send an `Origin` header on `POST`/`PUT`/`DELETE`. Origins that do not match any pattern are rejected with **403**.
- When deploying behind a reverse proxy with HTTPS, add your public hostname(s), e.g.:

```bash
MCP_CORS_ALLOWED_PATTERNS="https://prjxp.example.com,http://localhost:*"
```

### Upload limits

| Context | `max-file-size` | `max-request-size` |
|---|---|---|
| Dev | `${PRJXP_MAX_UPLOAD_SIZE:100MB}` | `${PRJXP_MAX_UPLOAD_SIZE:100MB}` |
| Docker | `2GB` | `2GB` |

Spring's default is 1 MB per file, which would reject project archives. The Docker image hard-codes 2 GB to match the Hub's import limit. In dev, raise `PRJXP_MAX_UPLOAD_SIZE` if your archives exceed 100 MB.

### Server

```yaml
server:
  port: ${SERVER_PORT:7007}
  forward-headers-strategy: framework
  tomcat:
    connection-linger: -1
```

- `forward-headers-strategy: framework` – required when running behind a reverse proxy so that `X-Forwarded-*` headers are honoured.
- `tomcat.connection-linger: -1` – disables the connection-linger timeout (avoids premature socket close on long-lived MCP streams).

### Spring extras (Docker)

```yaml
spring:
  main:
    allow-bean-definition-overriding: true
  devtools:
    restart:
      additional-exclude: "**/package-info.java"
```

- `allow-bean-definition-overriding` prevents startup failures when the Docker image layers multiple auto-configuration modules.
- `devtools.restart.additional-exclude` stops the dev-restart watcher from triggering on `package-info.java` changes.

## Graph Reranking

```yaml
prjxp:
  graph:
    rerank:
      floor: 0.98
      b-graph: 5.0
      cap: 5.0
      w-base: 1.0
```

| Property | Description |
|---|---|
| `floor` | Minimum multiplicative weight applied to a chunk's score. Calibrated on the `prjxp` corpus: degree-centrality values are normalised by the max degree of the whole edge set, yielding an observed range of 0 – 0.145 (p99 ≈ 0.026). A floor of 0.98 keeps code chunks on top while damping peripheral nodes (measured mean rank shift ≈ 7 positions). A lower floor (e.g. 0.8) demotes nearly every code chunk by ~20 %, letting semantically weaker doc chunks win even for code-specific queries. |
| `b-graph` | Graph boost factor. |
| `cap` | Maximum additive graph contribution. |
| `w-base` | Base weight multiplier. |

_This document was generated with Doc|Pipe._

