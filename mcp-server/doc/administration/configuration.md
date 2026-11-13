# Configuration

PRJXP is configured through a combination of a Spring Boot `application.yaml` file and environment variables. The same logical settings are expressed differently in the two deployment modes:

| Mode | Config file | Working directory | Typical use |
|------|-------------|-------------------|-------------|
| **Dev** (bare JVM / IDE) | `application.yaml` at repo root | repository root (`.`) | Local development, single-project work |
| **Docker Hub** | `application.yaml` baked into the image | `/app` | Self-hosted multi-project hub |

All PRJXP-specific properties live under the `prjxp` prefix (bound to `PrjXPConfig` via `@ConfigurationProperties(prefix = "prjxp")`). Spring-native properties (`server.*`, `spring.*`) and the MCP server block (`mcp.*`) follow standard Spring Boot conventions.

## Configuration Files & Resolution

### Dev profile

In a development checkout the working directory is the repository root. Paths that are relative (e.g. `mcp-server/doc`, `.prjxp-data/…`) resolve against that directory. The dev `application.yaml` uses:

- `OLLAMA` as the embedding backend (pointing at a local Ollama instance).
- A single `chat-models` entry with `stereo-type: default`.
- `maxSegmentsPerBatch: 32` for embedding requests.
- `PRJXP_MAX_UPLOAD_SIZE` (default `100MB`) for multipart uploads.

### Docker Hub profile

The Docker image ships a second `application.yaml` tuned for the container:

- `OPEN_AI` as the embedding backend (any OpenAI-compatible endpoint, e.g. LM Studio).
- Multiple `chatModels` entries with stereotypes `detailed`, `documentation`, and `javadoc`.
- `maxSegmentsPerBatch: 250` (the container can handle larger batches).
- Hard-coded `2GB` multipart limits (matching the hub's 2 GiB import cap).
- `spring.main.allow-bean-definition-overriding: true` and a devtools restart exclude for `**/package-info.java`.
- Paths anchored under `/app` (e.g. `/app/doc`, `/app-source/.prjxp-data/lucene-index`).

### Resolution order

Spring Boot resolves property values in the usual order:

1. Command-line arguments (`--prjxp.hub.enabled=false`)
2. Environment variables (referenced via `${VAR:default}` placeholders in YAML)
3. `application.yaml` (profile-specific `application-{profile}.yaml` if present)
4. Hard-coded defaults in `PrjXPConfig` Java fields

When the same logical setting appears in both the dev and Docker YAML files, the file that is on the classpath at runtime wins. In practice you run **one** of the two profiles, never both simultaneously.

## Environment Variables

Every `${VAR:default}` placeholder in the YAML files is overridable at runtime. The table below lists all variables referenced in the shipped configuration files.

| Variable | Default (dev) | Default (Docker) | Purpose |
|----------|---------------|------------------|---------|
| `SERVER_PORT` | `7007` | `7007` | HTTP port for the Hub / MCP server |
| `PRJXP_ROOT_DIR` | `.` | — | Base directory for `.prjxp-data/` sub-paths |
| `PRJXP_MAX_UPLOAD_SIZE` | `100MB` | — | Multipart upload limit (dev only; Docker hard-codes 2 GB) |
| `PRJXP_EMBEDDING_API_BASE_URL` | `http://localhost:1234/v1` | — | Base URL for the `embedding-model` reference (dev) |
| `PRJXP_EMBEDDING_MODEL_NAME` | `mxbai-embed-large-v1` | — | Model name for the `embedding-model` reference (dev) |
| `EMBEDDING_API_KEY` | *(unset)* | `dummy` | API key for OpenAI-compatible embedding / chat endpoints |
| `EMBEDDING_API_BASE_URL` | — | `http://host.docker.internal:1234/v1` | Base URL for the `embedding` block (Docker) |
| `EMBEDDING_MODEL_NAME` | — | `mixedbread-ai/mxbai-embed-large-v1` | Model name for the `embedding` block (Docker) |
| `EMBEDDING_TIMEOUT` | — | `180` | Embedding HTTP timeout in seconds (Docker) |
| `PRJXP_CHAT_TIMEOUT_SECS` | `300` | — | Chat-model HTTP timeout in seconds (dev) |
| `LUCENE_INDEX_PATH` | — | `/app-source/.prjxp-data/lucene-index` | Lucene index directory (Docker) |
| `LUCENE_VECTOR_DIMENSION` | — | `1024` | Vector dimension for the Lucene store (Docker) |
| `PRJXP_DOCS_DIR` | — | `/app/doc` | Source directory for the in-app docs browser (Docker) |
| `MCP_CORS_ALLOWED_PATTERNS` | `http://10.*.*.*,http://172.16.*.*,http://192.168.*.*,http://localhost:*` | same | Comma-separated CORS origin patterns |
| `PRJXP_HUB_URL` | — | `http://localhost:7007/mcp` | URL of the Hub MCP endpoint (consumed by external MCP clients) |
| `PRJXP_TRANSFER_PASSWORD` | *(see `TransferConfig`)* | *(see `TransferConfig`)* | Password for encrypted transfer mode |

> **Note on `host.docker.internal`:** In the Docker profile, `http://host.docker.internal:1234` is used so the container can reach an LM Studio or Ollama instance running on the host. Replace with your actual host IP if `host.docker.internal` is not resolved on your platform.

## Hub & Storage Paths

The `prjxp.hub` block (dev profile) controls where the Hub stores project data on disk:

```yaml
prjxp:
  hub:
    enabled: true
    import-dir: ${PRJXP_ROOT_DIR:.}/.prjxp-data/import
    projects-root: ${PRJXP_ROOT_DIR:.}/.prjxp-data/projects
    live-jsonl-dir: ${PRJXP_ROOT_DIR:.}/.prjxp-data/chunks
```

| Property | Meaning |
|----------|---------|
| `enabled` | Master switch for the Hub subsystem. Set `false` to disable all Hub endpoints. |
| `import-dir` | Staging directory for uploaded archives / JSONL files before they are processed. |
| `projects-root` | Root under which each project's working directory is created. |
| `live-jsonl-dir` | Directory for live chunk JSONL files (the `px-chunks.jsonl` per project). |

All three paths are relative to `PRJXP_ROOT_DIR` (defaults to `.` in dev). In Docker the equivalent paths are anchored under `/app-source/.prjxp-data/`.

### Lucene embedding store

```yaml
prjxp:
  embeddingStoreLucene:
    indexPath: ${LUCENE_INDEX_PATH:/app-source/.prjxp-data/lucene-index}
    vectorDimension: ${LUCENE_VECTOR_DIMENSION:1024}
```

| Property | Default | Description |
|----------|---------|-------------|
| `indexPath` | `.prjxp-data/lucene-index` (Java) / `/app-source/.prjxp-data/lucene-index` (Docker) | Directory for the Lucene vector index. |
| `vectorDimension` | `1024` | Dimensionality of the embedding vectors. Must match the model's output. |
| `name` | `prjxp` | Logical store name (used in `embedding-stores` references). |

### Embedding-store references

The `prjxp.embedding-stores` list maps project names to embedding stores:

```yaml
prjxp:
  embedding-stores:
    - project-name: "prjxp"
      default: true
    - project-name: "kigbau"
```

The entry with `default: true` is used when no explicit store is specified for a project.

### Docs browser

```yaml
prjxp:
  docs:
    dir: mcp-server/doc          # dev (relative to repo root)
    # dir: ${PRJXP_DOCS_DIR:/app/doc}   # Docker
```

Points to the source directory that the in-app documentation browser reads. In Docker the directory is baked into the image; override with a bind mount on `/app/doc` if you need to swap the docs at runtime.

## Embedding Configuration

PRJXP supports two embedding backends, selected by the `type` field:

| `type` value | Backend | Typical use |
|--------------|---------|-------------|
| `OLLAMA` | Ollama HTTP API | Local GPU inference, dev |
| `OPEN_AI` | Any OpenAI-compatible `/v1/embeddings` endpoint | LM Studio, vLLM, cloud APIs |

### `prjxp.embedding` block

```yaml
prjxp:
  embedding:
    type: OLLAMA                          # or OPEN_AI
    apiBaseURL: http://192.168.188.92:11434
    modelName: mxbai-embed-large
    timeout: 60
    maxSegmentsPerBatch: 32
```

| Property | Default (Java) | Description |
|----------|----------------|-------------|
| `type` | `OPEN_AI` | `OLLAMA` or `OPEN_AI`. |
| `apiBaseURL` | `http://host.docker.internal:1234` | Base URL of the embedding service. |
| `apiKey` | `lm-studio` | API key (ignored by Ollama; required for most OpenAI-compatible servers). |
| `modelName` | `mxbai-embed-large-v1` | Model identifier sent in the request body. |
| `dimensions` | *(null)* | Optional output vector dimension (e.g. for `text-embedding-3-large`). |
| `trustAllCerts` | `false` | Disables TLS certificate validation for self-signed local endpoints. |
| `timeout` | `20` | HTTP timeout in seconds per embedding request. |
| `customHeaders` | *(empty map)* | Extra HTTP headers appended to every embedding request. |
| `customQueryParams` | *(empty map)* | Extra query parameters appended to every embedding request. |
| `maxSegmentsPerBatch` | `32` | Maximum number of texts per single HTTP request. The per-project `tibedBatchSize` is split into sub-batches of this size. |

> **Why `maxSegmentsPerBatch` matters:** langchain4j's `OpenAiEmbeddingModel` defaults to 2048 segments per request. A local model (LM Studio / Ollama) will time out on a request that large. Capping the batch keeps every HTTP call small enough to complete within `timeout`.

### `prjxp.embedding-model` reference (dev)

A separate, user-configurable single-model reference exposed in the Hub's Globals panel:

```yaml
prjxp:
  embedding-model:
    server-type: "openai"
    provider-url: "${PRJXP_EMBEDDING_API_BASE_URL:http://localhost:1234/v1}"
    model-name: "${PRJXP_EMBEDDING_MODEL_NAME:mxbai-embed-large-v1}"
    api-key: "${EMBEDDING_API_KEY}"
    timeout-secs: 20
```

This is bound to `PrjXPEmbeddingModelReference` and is distinct from the `prjxp.embedding` block above. It allows the Hub UI to switch the active embedding model without restarting the container.

## Chat Models

Chat models are declared as a list. The dev profile uses the key `chat-models` (kebab-case); the Docker profile uses `chatModels` (camelCase). Both bind to `List<PrjXPChatModelReference>`.

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

| Property | Description |
|----------|-------------|
| `stereotype` / `stereo-type` | Logical role identifier (e.g. `default`, `detailed`, `documentation`, `javadoc`). DocPipe selects a stereotype per generation task. |
| `serverType` / `server-type` | Backend type: `lm-studio`, `openai`, etc. |
| `providerUrl` / `provider-url` | Base URL of the chat-completions endpoint. |
| `modelName` / `model-name` | Model identifier. |
| `apiKey` | API key sent in the `Authorization` header. |
| `timeoutSecs` / `timeout-secs` | HTTP timeout in seconds. |
| `bodyProperties` | Extra JSON fields merged into the request body (e.g. `enable_thinking: false` to disable Qwen3's thinking mode, which breaks native function-calling). |

> **Timeout guidance:** A local 27 B model takes > 60 s for document generation (FAQ, architecture docs). The dev default is 300 s (`PRJXP_CHAT_TIMEOUT_SECS`); the Docker profile uses 600 s. Increase further if you run a smaller GPU.

## MCP Server, CORS & HTTP

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

The MCP server exposes PRJXP's tools (chunk retrieval, file reading, project listing) over the **Streamable HTTP** transport. The endpoint is served at `/mcp` on the main HTTP port.

External MCP clients (e.g. an IDE plugin) reference the Hub via:

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
    allowed-patterns: ${MCP_CORS_ALLOWED_PATTERNS:http://10.*.*.*,http://172.16.*.*,http://192.168.*.*,http://localhost:*}
```

- Comma-separated list of origin patterns. **No spaces after commas.**
- Browsers send an `Origin` header on `POST`/`PUT`/`DELETE`. Origins that do not match any pattern are rejected with **403**.
- The default patterns cover RFC-1918 private ranges and `localhost` on any port.
- When deploying behind a reverse proxy with HTTPS, add your public hostname(s), e.g.:

```bash
MCP_CORS_ALLOWED_PATTERNS="https://prjxp.example.com,http://10.*.*.*,http://localhost:*"
```

### HTTP server

```yaml
server:
  port: ${SERVER_PORT:7007}
  forward-headers-strategy: framework
  tomcat:
    connection-linger: -1   # dev only; disables keep-alive linger
```

| Property | Default | Description |
|----------|---------|-------------|
| `server.port` | `7007` | Port for the Hub HTTP + MCP server. |
| `server.forward-headers-strategy` | `framework` | Trusts `X-Forwarded-*` headers from a reverse proxy. |
| `server.tomcat.connection-linger` | `-1` (dev) | Disables Tomcat's connection-linger timeout (avoids hanging idle connections in dev). |

### Multipart upload limits

```yaml
spring:
  servlet:
    multipart:
      max-file-size: ${PRJXP_MAX_UPLOAD_SIZE:100MB}   # dev
      max-request-size: ${PRJXP_MAX_UPLOAD_SIZE:100MB} # dev
      # Docker hard-codes:
      # max-file-size: 2GB
      # max-request-size: 2GB
```

Spring's default is **1 MB per file**, which would reject project archives. The Docker profile sets both limits to `2 GB` to match the hub's import cap. In dev, override with `PRJXP_MAX_UPLOAD_SIZE` if you need to test larger archives.

### Semantic graph reranking

```yaml
prjxp:
  graph:
    rerank:
      floor: 0.98
      b-graph: 5.0
      cap: 5.0
      w-base: 1.0
```

| Property | Default | Description |
|----------|---------|-------------|
| `floor` | `0.98` | Minimum degree-centrality multiplier. Values below this are clamped, preventing peripheral nodes from being over-demoted. |
| `b-graph` | `5.0` | Graph-similarity boost factor. |
| `cap` | `5.0` | Upper bound on the graph-similarity contribution. |
| `w-base` | `1.0` | Base weight for the semantic (embedding) score. |

The `floor` value is calibrated against the PRJXP corpus: degree-centrality values are normalized by the maximum degree in the edge set, yielding an observed range of 0 – 0.145 (p99 ≈ 0.026). A floor of `0.98` keeps code chunks ranked on top while still damping peripheral nodes (measured mean rank shift ≈ 7 positions).

### Transfer (external embedding)

```yaml
prjxp:
  transfer:
    passwordEnv: PRJXP_TRANSFER_PASSWORD
    encrypt: AUTO          # AUTO | ALWAYS | NEVER
    input:                 # source path (file or directory)
    output:                # destination path
    mode: STORE            # STORE | QUERY
```

Used when embedding is performed on an external machine and the resulting vectors are transferred back. The password is read from the environment variable named by `passwordEnv`.

### Reader output cap

```yaml
prjxp:
  reader-max-output-chars: 100000   # 0 = uncapped
```

Maximum number of characters returned by the `readFile` MCP tool. Set to `0` to remove the cap (useful for very large single-file projects, but increases token consumption in the LLM context).

_This document was generated with Doc|Pipe._

