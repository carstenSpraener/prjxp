# Configuration

PRJXP is configured through Spring Boot `application.yaml` files and environment variables. All PRJXP-specific properties live under the `prjxp` prefix, bound to the `PrjXPConfig` class. This chapter covers the full runtime configuration surface: file layout, environment variables, and the individual property groups.

## Configuration Files & Profiles

PRJXP ships three YAML profiles that are merged by Spring Boot at startup:

| File | Profile | Purpose |
|------|---------|---------|
| `application.yaml` | *(default)* | Development / local run |
| `application-docker.yaml` | `docker` | Self-hosted Hub inside the Docker image |
| `application-mcp.yaml` | `mcp` | MCP server endpoint (CORS, upload limits) |

Spring Boot activates profiles via the standard `SPRING_PROFILES_ACTIVE` variable or the `--spring.profiles.active` flag. In the Docker image the `docker` profile is the default; in a local IDE run the base `application.yaml` applies.

All PRJXP properties are bound to a single `@ConfigurationProperties(prefix = "prjxp")` class (`PrjXPConfig`). The top-level keys you will see in YAML are:

```yaml
prjxp:
  activeProject: "cwd"
  projects: []
  mcpServers: []
  embeddingStores: []
  chatModels: []
  embeddingStoreLucene: {}
  embedding: {}
  embeddingModel: {}
  transfer: {}
  readerMaxOutputChars: 100000
  hub: {}
  docs: {}
```

## Environment Variables

Every overridable property in the YAML files is exposed through an environment variable. The table below lists all variables referenced in the shipped configuration files.

| Variable | Default | Used in | Purpose |
|----------|---------|---------|---------|
| `SERVER_PORT` | `7007` | all profiles | TCP port for the HTTP server |
| `PRJXP_ROOT_DIR` | `.` | dev | Base directory for Hub data (import, projects, chunks) |
| `PRJXP_MAX_UPLOAD_SIZE` | `100MB` | dev | Spring multipart file/request size limit |
| `PRJXP_EMBEDDING_API_BASE_URL` | `http://localhost:1234/v1` | dev | Base URL for the OpenAI-compatible embedding endpoint |
| `PRJXP_EMBEDDING_MODEL_NAME` | `mxbai-embed-large-v1` | dev | Embedding model name (OpenAI-compatible) |
| `EMBEDDING_API_KEY` | *(no default)* | dev, docker | API key for embedding / chat providers |
| `PRJXP_CHAT_TIMEOUT_SECS` | `300` | dev | Timeout for the default chat model |
| `LUCENE_INDEX_PATH` | `/app-source/.prjxp-data/lucene-index` | docker | Lucene vector index directory |
| `LUCENE_VECTOR_DIMENSION` | `1024` | docker | Dimensionality of stored vectors |
| `EMBEDDING_API_BASE_URL` | `http://host.docker.internal:1234/v1` | docker | Embedding endpoint (Docker network) |
| `EMBEDDING_MODEL_NAME` | `mixedbread-ai/mxbai-embed-large-v1` | docker | Embedding model name (Docker) |
| `EMBEDDING_TIMEOUT` | `180` | docker | Embedding request timeout (seconds) |
| `MCP_CORS_ALLOWED_PATTERNS` | `http://10.*.*.*,http://172.16.*.*,http://192.168.*.*,http://localhost:*` | mcp | Comma-separated CORS origin patterns |
| `PRJXP_HUB_URL` | `http://localhost:7007/mcp` | docker | URL of the Hub MCP endpoint |
| `PRJXP_DOCS_DIR` | `/app/doc` | docker | Source directory for the in-app docs browser |
| `PRJXP_TRANSFER_PASSWORD` | *(no default)* | all | Password for encrypted transfer mode |

> **Note:** In the Docker image, `host.docker.internal` resolves to the host machine. If you run the embedding or chat model on a different host, override `EMBEDDING_API_BASE_URL` (or `PRJXP_EMBEDDING_API_BASE_URL` in dev) accordingly.

## Hub & Storage Paths

The `prjxp.hub` block controls where the Hub stores its data on disk. All paths are resolved relative to `PRJXP_ROOT_DIR` (default `.` in dev, `/app-source` in Docker).

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
| `enabled` | Master switch for the Hub subsystem |
| `import-dir` | Where uploaded archives / JSONL files are staged |
| `projects-root` | Root directory for extracted project trees |
| `live-jsonl-dir` | Directory for live chunk JSONL files |

The in-app documentation browser reads from a separate path:

```yaml
prjxp:
  docs:
    dir: mcp-server/doc          # dev (relative to repo root)
    # dir: ${PRJXP_DOCS_DIR:/app/doc}   # docker (baked into image)
```

In Docker the docs directory is baked into the image at `/app/doc`. You can override it with a bind mount or by setting `PRJXP_DOCS_DIR`.

The Lucene vector index path is configured under `prjxp.embeddingStoreLucene`:

```yaml
prjxp:
  embeddingStoreLucene:
    indexPath: ${LUCENE_INDEX_PATH:/app-source/.prjxp-data/lucene-index}
    vectorDimension: ${LUCENE_VECTOR_DIMENSION:1024}
    name: "prjxp"
```

## Embedding Configuration

PRJXP supports two embedding backends, selected by the `type` field:

| Value | Provider |
|-------|----------|
| `OLLAMA` | Ollama HTTP API |
| `OPEN_AI` | Any OpenAI-compatible `/v1/embeddings` endpoint (LM Studio, Ollama, OpenAI, etc.) |

### `prjxp.embedding` (batch / store embedding)

Used when embedding chunks for the vector store.

```yaml
# Dev (application.yaml)
prjxp:
  embedding:
    type: OLLAMA
    apiBaseURL: http://192.168.188.92:11434
    modelName: mxbai-embed-large
    timeout: 60

# Docker (application-docker.yaml)
prjxp:
  embedding:
    type: "OPEN_AI"
    apiBaseURL: ${EMBEDDING_API_BASE_URL:http://host.docker.internal:1234/v1}
    modelName: ${EMBEDDING_MODEL_NAME:mixedbread-ai/mxbai-embed-large-v1}
    timeout: ${EMBEDDING_TIMEOUT:180}
    apiKey: ${EMBEDDING_API_KEY:dummy}
```

Full property reference (from `EmbeddingConfig`):

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `type` | `OLLAMA` \| `OPEN_AI` | `OPEN_AI` | Backend selector |
| `apiBaseURL` | String | `http://host.docker.internal:1234` | Base URL of the embedding service |
| `apiKey` | String | `lm-studio` | API key (sent as `Authorization` header) |
| `modelName` | String | `mxbai-embed-large-v1` | Model identifier |
| `dimensions` | Integer | *(null)* | Optional output vector dimension (e.g. for `text-embedding-3-large`) |
| `timeout` | int (seconds) | `20` | HTTP request timeout |
| `customHeaders` | Map | `{}` | Extra HTTP headers for OpenAI-compatible providers |
| `customQueryParams` | Map | `{}` | Extra query parameters for OpenAI-compatible providers |
| `maxSegmentsPerBatch` | int | `32` | Max texts per single embedding HTTP request |

> **`maxSegmentsPerBatch`:** LangChain4j's `OpenAiEmbeddingModel` defaults to 2048 texts per request. For local models (LM Studio, Ollama) a single giant request will time out. Capping the batch keeps every HTTP call small enough to complete within `timeout`. This setting only applies to the `OPEN_AI` backend.

### `prjxp.embeddingModel` (single / interactive embedding)

A user-configurable embedding model reference (exposed in the Globals panel of the UI). In the dev profile:

```yaml
prjxp:
  embedding-model:
    server-type: "openai"
    provider-url: "${PRJXP_EMBEDDING_API_BASE_URL:http://localhost:1234/v1}"
    model-name: "${PRJXP_EMBEDDING_MODEL_NAME:mxbai-embed-large-v1}"
    api-key: "${EMBEDDING_API_KEY}"
    timeout-secs: 20
```

### `prjxp.embeddingStores`

A list of named embedding-store references. Each entry maps a project name to a store; one entry is marked `default`:

```yaml
prjxp:
  embedding-stores:
    - project-name: "prjxp"
      default: true
    - project-name: "kigbau"
```

## Chat Model Configuration

Chat models are declared as a list under `prjxp.chatModels` (Docker) or `prjxp.chat-models` (dev). Each entry carries a **stereotype** that selects the model for a specific task.

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
| `stereotype` / `stereo-type` | Task label (`detailed`, `documentation`, `javadoc`, `default`) |
| `serverType` / `server-type` | Provider type (e.g. `lm-studio`) |
| `providerUrl` / `provider-url` | Base URL of the chat endpoint |
| `modelName` / `model-name` | Model identifier |
| `apiKey` | API key (LM Studio accepts any non-empty string) |
| `timeoutSecs` / `timeout-secs` | Request timeout in seconds |
| `bodyProperties` | Extra JSON fields merged into the request body |

> **Qwen3 thinking mode:** The `enable_thinking: false` entry in `bodyProperties` disables Qwen3's internal "thinking" mode. Without it, the model leaks its reasoning into the response content as XML, which breaks native function-calling that DocPipe relies on.

## MCP Server & CORS

### MCP server identity

```yaml
spring:
  ai:
    mcp:
      server:
        protocol: STREAMABLE
        name: "prjxp-mcp"
        version: "1.0.0"
```

The MCP server uses the **STREAMABLE** transport (HTTP streaming). The `name` and `version` are advertised to MCP clients.

### CORS

```yaml
mcp:
  cors:
    allowed-patterns: ${MCP_CORS_ALLOWED_PATTERNS:http://10.*.*.*,http://172.16.*.*,http://192.168.*.*,http://localhost:*}
```

- Patterns are **comma-separated with no spaces** after commas.
- Browsers send an `Origin` header on `POST`/`PUT`/`DELETE`. Origins that do not match any pattern are rejected with **403**.
- When deploying behind a reverse proxy with HTTPS, add your public hostname(s) to the list, e.g.:

```bash
export MCP_CORS_ALLOWED_PATTERNS="https://prjxp.example.com,http://localhost:*"
```

### MCP client references

The Docker profile registers the Hub itself as an MCP server for internal tool-calling:

```yaml
prjxp:
  mcp-servers:
    - name: prjxp
      type: http
      url: ${PRJXP_HUB_URL:http://localhost:7007/mcp}
```

## Server & Upload Limits

### HTTP server

```yaml
server:
  port: ${SERVER_PORT:7007}
  forward-headers-strategy: framework
  tomcat:
    connection-linger: -1
```

| Property | Effect |
|----------|--------|
| `port` | Listening port (default **7007**) |
| `forward-headers-strategy: framework` | Trusts `X-Forwarded-*` headers from a reverse proxy |
| `tomcat.connection-linger: -1` | Disables Tomcat's connection-linger timeout (keeps long-lived MCP streams open) |

### Multipart upload limits

```yaml
# Dev
spring:
  servlet:
    multipart:
      max-file-size: ${PRJXP_MAX_UPLOAD_SIZE:100MB}
      max-request-size: ${PRJXP_MAX_UPLOAD_SIZE:100MB}

# MCP profile (Hub endpoints)
spring:
  servlet:
    multipart:
      max-file-size: 2GB
      max-request-size: 2GB
```

Spring Boot's default is **1 MB** per file, which would reject project archives. The MCP profile raises the limit to **2 GB** to accommodate large `.tar` / `.jsonl` uploads. In dev the limit is tunable via `PRJXP_MAX_UPLOAD_SIZE`.

### Transfer (external embedding)

The `prjxp.transfer` block configures encrypted data transfer for external embedding workflows:

```yaml
prjxp:
  transfer:
    passwordEnv: "PRJXP_TRANSFER_PASSWORD"
    encrypt: AUTO
    input: ""
    output: ""
    mode: STORE
```

| Property | Default | Description |
|----------|---------|-------------|
| `passwordEnv` | `PRJXP_TRANSFER_PASSWORD` | Name of the env var holding the encryption password |
| `encrypt` | `AUTO` | Encryption mode (`AUTO`, `ON`, `OFF`) |
| `input` | *(empty)* | Input path for transfer |
| `output` | *(empty)* | Output path for transfer |
| `mode` | `STORE` | Transfer mode (`STORE`, …) |

### Reader output cap

```yaml
prjxp:
  readerMaxOutputChars: 100000
```

Maximum characters returned by the `readFile` MCP tool. Set to `0` for an uncapped response.

_This document was generated with Doc|Pipe._

