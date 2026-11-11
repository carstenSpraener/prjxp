# Configuration

PRJXP is configured through a combination of a Spring Boot `application.yaml` file and environment variables. The same logical settings are expressed differently in a local development checkout versus the self-hosted Docker Hub image. This chapter documents every runtime setting, the environment variables that override them, and how the two deployment modes resolve their values.

## Configuration Files & Resolution

PRJXP reads its primary configuration from `application.yaml` at the project root (development) or from the baked-in `/app/application.yaml` inside the Docker image. Spring Boot's standard property resolution applies:

1. **Environment variables** (highest priority) — any `${VAR:default}` placeholder in the YAML is replaced at startup.
2. **`application.yaml`** — the checked-in or image-baked file.
3. **Java defaults** — hard-coded defaults in `PrjXPConfig` and its inner classes (e.g. `readerMaxOutputChars = 100_000`).

All PRJXP-specific properties live under the `prjxp` prefix, bound to the `PrjXPConfig` class via `@ConfigurationProperties(prefix = "prjxp")`.

| Deployment | Config file location | Working directory | Typical overrides |
|---|---|---|---|
| Development (IDE / `mvn spring-boot:run`) | `./application.yaml` (repo root) | repo root (`.`) | `PRJXP_ROOT_DIR`, `PRJXP_MAX_UPLOAD_SIZE` |
| Docker Hub | `/app/application.yaml` (baked into image) | `/app` | `EMBEDDING_API_BASE_URL`, `LUCENE_INDEX_PATH`, `PRJXP_DOCS_DIR` |

In development the working directory is the repository root, so relative paths like `.prjxp-data/` resolve to `./prjxp-data/`. In Docker the working directory is `/app`, and the image bakes absolute paths such as `/app-source/.prjxp-data/`.

## Server & Spring Framework Settings

### HTTP Server

| Property | Env var | Dev default | Docker default | Description |
|---|---|---|---|---|
| `server.port` | `SERVER_PORT` | `7007` | `7007` | Port the Hub listens on. |
| `server.forward-headers-strategy` | — | `framework` | `framework` | Trusts `X-Forwarded-*` headers from a reverse proxy. |
| `server.tomcat.connection-linger` | — | `-1` | — | Disables Tomcat connection linger (dev only). |

### Multipart Upload Limits

Project archives (tar, JSONL) are uploaded through Spring's multipart resolver.

| Property | Env var | Dev default | Docker default |
|---|---|---|---|
| `spring.servlet.multipart.max-file-size` | `PRJXP_MAX_UPLOAD_SIZE` | `100MB` | `2GB` |
| `spring.servlet.multipart.max-request-size` | `PRJXP_MAX_UPLOAD_SIZE` | `100MB` | `2GB` |

The Docker image hard-codes `2GB` because the Hub's import pipeline accepts full project archives. In development the lower default keeps accidental large uploads from filling the local disk.

### CORS (MCP Endpoint)

```yaml
mcp:
  cors:
    allowed-patterns: ${MCP_CORS_ALLOWED_PATTERNS:http://10.*.*.*,http://172.16.*.*,http://192.16.*.*,http://localhost:*}
```

- Comma-separated list of origin patterns. **No spaces after commas.**
- Browsers send an `Origin` header on `POST`/`PUT`/`DELETE`; origins that do not match any pattern are rejected with **403**.
- The default allows all RFC-1918 private ranges and `localhost` on any port.
- When deploying behind a reverse proxy with HTTPS, append your public hostname, e.g.:

```bash
MCP_CORS_ALLOWED_PATTERNS="http://10.*.*.*,http://172.16.*.*,http://192.168.*.*,http://localhost:*,https://prjxp.example.com"
```

### MCP Server (Spring AI)

```yaml
spring:
  ai:
    mcp:
      server:
        protocol: STREAMABLE
        name: "prjxp-mcp"
        version: "1.0.0"
```

The Hub exposes a Streamable-HTTP MCP endpoint at `/mcp` (see `server.port`). The `name` and `version` fields are advertised in the MCP handshake.

### Other Spring Settings (Docker image)

```yaml
spring:
  main:
    allow-bean-definition-overriding: true
  devtools:
    restart:
      additional-exclude: "**/package-info.java"
```

These are image-level concerns and are not typically overridden at runtime.

## Hub & Project Settings

### Hub Directories

```yaml
prjxp:
  hub:
    enabled: true
    import-dir: ${PRJXP_ROOT_DIR:.}/.prjxp-data/import
    projects-root: ${PRJXP_ROOT_DIR:.}/.prjxp-data/projects
    live-jsonl-dir: ${PRJXP_ROOT_DIR:.}/.prjxp-data/chunks
```

| Property | Purpose |
|---|---|
| `enabled` | Master switch for the Hub web UI and import endpoints. |
| `import-dir` | Staging directory where uploaded archives are unpacked before processing. |
| `projects-root` | Root under which each project's chunk store and metadata live. |
| `live-jsonl-dir` | Directory for live JSONL chunk files (one per project). |

All three resolve relative to `PRJXP_ROOT_DIR` (default `.`). In Docker the image sets `PRJXP_ROOT_DIR=/app-source` so the effective paths become `/app-source/.prjxp-data/…`.

### Active Project & Project Definitions

```java
private String activeProject = "cwd";
private List<ProjectDefinition> projects = new ArrayList<>();
```

- `prjxp.active-project` selects which project the Hub operates on by default. The sentinel value `"cwd"` triggers a built-in fallback definition (root `.`; JSONL file `px-chunks.jsonl`; whitelist `java,ts`; batch size 32; reset store on import).
- `prjxp.projects` is a list of `ProjectDefinition` entries:

| Field | Default (cwd fallback) | Description |
|---|---|---|
| `name` | `cwd` | Unique project identifier. |
| `rootDir` | `.` | Source root to chunk. |
| `jsonlFile` | `px-chunks.jsonl` | Output chunk file name. |
| `chunoWhiteList` | `java,ts` | Comma-separated file extensions to include. |
| `tibedBatchSize` | `32` | Chunks per embedding batch. |
| `tibedResetStore` | `true` | Clear the vector store before re-embedding. |

If `activeProject` does not match any entry in `projects`, the Hub throws an `IllegalStateException` listing all available project names.

### Embedding Stores

```yaml
prjxp:
  embedding-stores:
    - project-name: "prjxp"
      default: true
    - project-name: "kigbau"
```

Each entry maps a project name to a named vector store. The entry with `default: true` is used when no explicit store is selected.

### Documentation Browser

```yaml
prjxp:
  docs:
    dir: mcp-server/doc          # dev (relative to repo root)
    # dir: ${PRJXP_DOCS_DIR:/app/doc}   # Docker (baked into image)
```

Points the in-app documentation browser at a directory of Markdown/HTML files. In Docker, override with a bind mount on `/app/doc` or set `PRJXP_DOCS_DIR`.

### MCP Client References

```yaml
prjxp:
  mcp-servers:
    - name: prjxp
      type: http
      url: ${PRJXP_HUB_URL:http://localhost:7007/mcp}
```

Used by the Hub to call back into its own MCP endpoint (or an external one). Set `PRJXP_HUB_URL` when the Hub is reachable on a non-default host/port.

### Reader Output Cap

```java
private int readerMaxOutputChars = 100_000;
// Property: prjxp.reader-max-output-chars
```

Maximum characters returned by the `readFile` MCP tool. Set to `0` for uncapped output.

## Embedding Configuration

PRJXP supports two embedding back-ends, selected by the `type` field:

| `type` value | Provider | Typical use |
|---|---|---|
| `OLLAMA` | Ollama HTTP API | Local GPU inference |
| `OPEN_AI` | Any OpenAI-compatible `/v1/embeddings` endpoint (LM Studio, vLLM, hosted) | Local or cloud |

### `prjxp.embedding` (batch / store embedding)

```yaml
prjxp:
  embedding:
    type: OLLAMA                          # or OPEN_AI
    apiBaseURL: http://192.168.188.92:11434
    modelName: mxbai-embed-large
    timeout: 60
    maxSegmentsPerBatch: 32
```

| Property | Env var (Docker) | Dev default | Docker default | Description |
|---|---|---|---|---|
| `type` | — | `OLLAMA` | `OPEN_AI` | Back-end selector. |
| `apiBaseURL` | `EMBEDDING_API_BASE_URL` | `http://192.168.188.92:11434` | `http://host.docker.internal:1234/v1` | Base URL of the embedding service. |
| `modelName` | `EMBEDDING_MODEL_NAME` | `mxbai-embed-large` | `mixedbread-ai/mxbai-embed-large-v1` | Model identifier. |
| `apiKey` | `EMBEDDING_API_KEY` | `lm-studio` | `dummy` | Bearer token (many local servers ignore it). |
| `timeout` | `EMBEDDING_TIMEOUT` | `60` | `180` | HTTP timeout in seconds. |
| `maxSegmentsPerBatch` | — | `32` | `32` | Max texts per single HTTP request. The per-project `tibedBatchSize` is split at this size. |
| `dimensions` | — | *(unset)* | *(unset)* | Optional output vector dimension (e.g. for `text-embedding-3-large`). |
| `customHeaders` | — | `{}` | `{}` | Extra HTTP headers for OpenAI-compatible providers. |
| `customQueryParams` | — | `{}` | `{}` | Extra query parameters for OpenAI-compatible providers. |

> **Why `maxSegmentsPerBatch`?** langchain4j's `OpenAiEmbeddingModel` defaults to 2048 texts per request. A local model (LM Studio / Ollama) times out on a single giant request. Capping at 32 keeps every request small enough to complete within `timeout`.

### `prjxp.embedding-model` (single-model reference, Globals panel)

```yaml
prjxp:
  embedding-model:
    server-type: "openai"
    provider-url: "${PRJXP_EMBEDDING_API_BASE_URL:http://localhost:1234/v1}"
    model-name: "${PRJXP_EMBEDDING_MODEL_NAME:mxbai-embed-large-v1}"
    api-key: "${EMBEDDING_API_KEY}"
    timeout-secs: 20
```

This is the user-selectable embedding model exposed in the Hub's Globals UI panel. It is independent of the batch `prjxp.embedding` block above.

### Lucene Vector Store

```yaml
prjxp:
  embeddingStoreLucene:
    indexPath: ${LUCENE_INDEX_PATH:/app-source/.prjxp-data/lucene-index}
    vectorDimension: ${LUCENE_VECTOR_DIMENSION:1024}
```

| Property | Env var | Default | Description |
|---|---|---|---|
| `indexPath` | `LUCENE_INDEX_PATH` | `.prjxp-data/lucene-index` (dev) / `/app-source/.prjxp-data/lucene-index` (Docker) | On-disk Lucene index directory. |
| `vectorDimension` | `LUCENE_VECTOR_DIMENSION` | `1024` | Must match the embedding model's output dimension. |
| `name` | — | `prjxp` | Logical store name. |

## Chat Models

Chat models are declared as a list under `prjxp.chat-models` (dev) or `prjxp.chatModels` (Docker). Each entry carries a **stereotype** that the DocPipe and other features select by name.

### Development

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

### Docker

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

| Field | Description |
|---|---|
| `stereotype` / `stereo-type` | Logical name used by DocPipe and other features to pick a model. |
| `serverType` / `server-type` | Provider type (`lm-studio`, `openai`, …). |
| `providerUrl` / `provider-url` | Base URL of the chat-completions endpoint. |
| `modelName` / `model-name` | Model identifier sent in the request body. |
| `apiKey` | Bearer token. |
| `timeoutSecs` / `timeout-secs` | Per-request timeout. A local 27 B model can exceed 60 s for long doc generation; the Docker image uses 600 s. |
| `bodyProperties` | Extra JSON fields merged into the request body. `enable_thinking: false` is required for Qwen3 because thinking mode leaks tool calls into the content field as XML, breaking DocPipe's native function-calling. |

> **Note:** In development the single `default` stereotype uses a 300 s timeout (overridable via `PRJXP_CHAT_TIMEOUT_SECS`). In Docker the three stereotypes (`detailed`, `documentation`, `javadoc`) all use 600 s.

## Graph Reranking & Transfer

### Graph Reranking

```yaml
prjxp:
  graph:
    rerank:
      floor: 0.98
      b-graph: 5.0
      cap: 5.0
      w-base: 1.0
```

| Parameter | Value | Purpose |
|---|---|---|
| `floor` | `0.98` | Minimum multiplicative weight applied to a chunk's score after graph-centrality damping. Calibrated on the PRJXP corpus: degree-centrality values (normalized by max degree) range 0 – 0.145 (p99 ≈ 0.026). A floor of 0.8 would demote nearly every code chunk by ~20 %, letting weaker doc chunks win code-specific queries. 0.98 keeps code on top while damping peripheral nodes (measured mean rank shift ≈ 7 positions). |
| `b-graph` | `5.0` | Exponent for the graph-centrality term. |
| `cap` | `5.0` | Upper bound on the graph-centrality multiplier. |
| `w-base` | `1.0` | Base weight for the semantic (embedding) score. |

### Transfer (External Embedding)

```java
private TransferConfig transfer = new TransferConfig();
```

| Property | Default | Description |
|---|---|---|
| `passwordEnv` | `PRJXP_TRANSFER_PASSWORD` | Name of the environment variable holding the transfer password. |
| `encrypt` | `AUTO` | Encryption mode for transferred data (`AUTO`, `ON`, `OFF`). |
| `input` | *(unset)* | Input path for transfer mode. |
| `output` | *(unset)* | Output path for transfer mode. |
| `mode` | `STORE` | Transfer mode (`STORE`, …). |

Set `PRJXP_TRANSFER_PASSWORD` in the environment when using the external-embedding transfer feature.

_This document was generated with Doc|Pipe._

