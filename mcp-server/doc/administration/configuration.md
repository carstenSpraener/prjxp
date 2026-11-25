# Configuration

PRJXP is configured through a combination of `application.yaml` files and environment variables. This chapter covers every runtime setting a system administrator needs to deploy and operate the Hub.

## Configuration Files & Resolution

PRJXP ships with two distinct `application.yaml` profiles:

| Profile | Location | Use case |
|---------|----------|----------|
| **Dev** | `mcp-server/src/main/resources/application.yaml` | Local development; relative paths, Ollama embedding, 100 MB upload cap |
| **Docker** | Baked into the Hub image at `/app/config/application.yaml` | Production; absolute paths, OpenAI-compatible embedding, 2 GB upload cap |

The MCP server component has its own `application.yaml` (the third file in the source tree) that governs the standalone MCP endpoint.

### Environment variable resolution

All `${VAR:default}` placeholders are resolved at startup. The following variables are recognised:

| Variable | Default (dev) | Default (Docker) | Purpose |
|----------|---------------|-------------------|---------|
| `PRJXP_ROOT_DIR` | `.` | — | Base directory for Hub data (import, projects, chunks) |
| `PRJXP_MAX_UPLOAD_SIZE` | `100MB` | — | Spring multipart file/request size cap |
| `PRJXP_EMBEDDING_API_BASE_URL` | `http://localhost:1234/v1` | — | OpenAI-compatible embedding endpoint (dev `embedding-model` block) |
| `PRJXP_EMBEDDING_MODEL_NAME` | `mxbai-embed-large-v1` | — | Embedding model name (dev `embedding-model` block) |
| `EMBEDDING_API_BASE_URL` | — | `http://host.docker.internal:1234/v1` | Embedding endpoint (Docker) |
| `EMBEDDING_MODEL_NAME` | — | `mixedbread-ai/mxbai-embed-large-v1` | Embedding model name (Docker) |
| `EMBEDDING_API_KEY` | *(unset)* | `dummy` | API key for the embedding provider |
| `EMBEDDING_TIMEOUT` | — | `180` | Embedding HTTP timeout in seconds (Docker) |
| `PRJXP_CHAT_TIMEOUT_SECS` | `300` | — | Chat-model HTTP timeout in seconds (dev) |
| `LUCENE_INDEX_PATH` | — | `/app-source/.prjxp-data/lucene-index` | Lucene index directory (Docker) |
| `LUCENE_VECTOR_DIMENSION` | — | `1024` | Vector dimensionality of the Lucene index |
| `PRJXP_DOCS_DIR` | — | `/app/doc` | Source directory for the in-app documentation browser |
| `PRJXP_HUB_URL` | — | `http://localhost:7007/mcp` | URL the MCP client uses to reach the Hub |
| `MCP_CORS_ALLOWED_PATTERNS` | `http://10.*.*.*,http://172.16.*.*,http://192.168.*.*,http://localhost:*` | same | Comma-separated CORS origin patterns |
| `SERVER_PORT` | `7007` | `7007` | HTTP listen port |
| `PRJXP_TRANSFER_PASSWORD` | *(unset)* | *(unset)* | Password for encrypted transfer mode |

### Key dev-vs-Docker differences

- **Embedding backend.** Dev defaults to `OLLAMA` (pointing at a LAN Ollama instance). Docker defaults to `OPEN_AI` (any OpenAI-compatible endpoint, e.g. LM Studio via `host.docker.internal`).
- **Upload limits.** Dev caps multipart uploads at 100 MB (overridable via `PRJXP_MAX_UPLOAD_SIZE`). The MCP server and Docker profiles hard-code 2 GB to accommodate project archives.
- **Network addresses.** Dev uses `localhost` / LAN IPs. Docker uses `host.docker.internal` to reach services on the host from inside the container.
- **Docs directory.** Dev resolves `mcp-server/doc` relative to the working directory (repo root). Docker uses the absolute path `/app/doc`, overridable by bind-mounting a volume onto that path.
- **`maxSegmentsPerBatch`.** Dev sets 32; Docker sets 250. This value caps the number of texts per single embedding HTTP request so that local models do not time out.

## Server & Spring Settings

### HTTP server

```yaml
server:
  port: ${SERVER_PORT:7007}
  forward-headers-strategy: framework
  tomcat:
    connection-linger: -1   # dev only; disables Tomcat's keep-alive linger
```

- `forward-headers-strategy: framework` tells Spring to honour `X-Forwarded-*` headers, which is required when the Hub sits behind a reverse proxy (nginx, Caddy, Traefik, …).
- `connection-linger: -1` (dev profile) disables Tomcat's connection-linger timeout, useful for long-lived SSE/streaming connections during development.

### Multipart upload limits

```yaml
spring:
  servlet:
    multipart:
      max-file-size: ${PRJXP_MAX_UPLOAD_SIZE:100MB}   # dev
      max-request-size: ${PRJXP_MAX_UPLOAD_SIZE:100MB} # dev
      # Docker / MCP-server profile:
      max-file-size: 2GB
      max-request-size: 2GB
```

Spring's built-in default is 1 MB per file. The Hub's import endpoint accepts project archives (tar, JSONL), so the limit must be raised. The MCP-server profile hard-codes 2 GB to match the Hub's `prjxp.hub.max-tar-bytes` default.

### Bean overriding & devtools (MCP-server profile)

```yaml
spring:
  main:
    allow-bean-definition-overriding: true
  devtools:
    restart:
      additional-exclude: "**/package-info.java"
```

These settings are specific to the MCP-server module and are not present in the Hub profile.

### MCP server identity

```yaml
spring:
  ai:
    mcp:
      server:
        protocol: STREAMABLE
        name: "prjxp-mcp"      # dev profile only
        version: "1.0.0"       # dev profile only
```

The `STREAMABLE` protocol enables the MCP streamable-HTTP transport. The `name` and `version` fields are advertised in the MCP handshake and are set only in the dev profile.

## Hub & Project Configuration

All Hub-specific properties live under the `prjxp` prefix, bound to the `PrjXPConfig` class.

### Hub data directories

```yaml
prjxp:
  hub:
    enabled: true
    import-dir: ${PRJXP_ROOT_DIR:.}/.prjxp-data/import
    projects-root: ${PRJXP_ROOT_DIR:.}/.prjxp-data/projects
    live-jsonl-dir: ${PRJXP_ROOT_DIR:.}/.prjxp-data/chunks
```

| Property | Purpose |
|----------|---------|
| `import-dir` | Staging area for uploaded archives before extraction |
| `projects-root` | Root under which each project's working directory is created |
| `live-jsonl-dir` | Location of the live `px-chunks.jsonl` files (one per project) |

In Docker, `PRJXP_ROOT_DIR` is typically unset, so paths resolve to `./.prjxp-data/…` relative to the container's working directory. Override it (or bind-mount a volume) to persist data across container restarts.

### Project definitions

```yaml
prjxp:
  activeProject: "cwd"          # default; name of the project to operate on
  projects: []                  # list of ProjectDefinition entries
```

Each `ProjectDefinition` entry (bound from the `projects` list) carries:

- `name` – unique project identifier
- `rootDir` – source root (default `.`)
- `jsonlFile` – chunk file name (default `px-chunks.jsonl`)
- `chunoWhiteList` – file extensions to chunk (default `java,ts`)
- `tibedBatchSize` – batch size for the tibed pipeline (default `32`)
- `tibedResetStore` – whether to reset the embedding store before ingestion (default `true`)

If `projects` is empty and `activeProject` is `"cwd"`, the framework synthesises a fallback definition with the defaults above.

### Embedding store references

```yaml
prjxp:
  embedding-stores:
    - project-name: "prjxp"
      default: true
    - project-name: "kigbau"
```

Each entry maps a project name to an embedding store. The entry with `default: true` is used when no explicit store is specified.

### Lucene embedding store

```yaml
prjxp:
  embeddingStoreLucene:
    indexPath: ${LUCENE_INDEX_PATH:/app-source/.prjxp-data/lucene-index}
    vectorDimension: ${LUCENE_VECTOR_DIMENSION:1024}
    name: "prjxp"
```

| Property | Default | Description |
|----------|---------|-------------|
| `indexPath` | `.prjxp-data/lucene-index` (dev) / `/app-source/.prjxp-data/lucene-index` (Docker) | Directory for the Lucene index |
| `vectorDimension` | `1024` | Must match the embedding model's output dimension |
| `name` | `prjxp` | Logical store name |

### Documentation browser

```yaml
prjxp:
  docs:
    dir: mcp-server/doc          # dev (relative to repo root)
    # dir: ${PRJXP_DOCS_DIR:/app/doc}   # Docker
```

Points to the source directory scanned by the in-app documentation browser. In Docker, bind-mount your docs tree onto `/app/doc` or set `PRJXP_DOCS_DIR`.

### Reader output cap

```yaml
prjxp:
  reader-max-output-chars: 100000   # 0 = uncapped
```

Maximum characters returned by the `readFile` MCP tool. Set to `0` to disable the cap.

### Enrich-only mode

```yaml
prjxp:
  enrichOnly: false   # set by --enrich-only CLI flag; never true in the Hub
```

When `true`, the tibed pipeline skips ingestion (JSONL read + embedding) and runs only the enrichment phase (semantic graph + signatures) against the existing index. This flag is set exclusively by the `--enrich-only` CLI option and is always `false` in the Hub's in-process pipeline.

### Transfer configuration (external embedding)

```yaml
prjxp:
  transfer:
    passwordEnv: "PRJXP_TRANSFER_PASSWORD"
    encrypt: AUTO        # AUTO | ALWAYS | NEVER
    input: ""
    output: ""
    mode: STORE          # STORE | …
```

Used when the Hub delegates embedding to an external process. `passwordEnv` names the environment variable that holds the transfer password.

## Embedding Configuration

Two YAML blocks configure embedding; they serve different roles:

### `prjxp.embedding` – pipeline embedding model

Used by the tibed ingestion pipeline to generate vectors for chunks.

**Dev profile (Ollama):**

```yaml
prjxp:
  embedding:
    type: OLLAMA
    apiBaseURL: http://192.168.188.92:11434
    modelName: mxbai-embed-large
    timeout: 60
    maxSegmentsPerBatch: 32
```

**Docker profile (OpenAI-compatible):**

```yaml
prjxp:
  embedding:
    type: "OPEN_AI"
    apiBaseURL: ${EMBEDDING_API_BASE_URL:http://host.docker.internal:1234/v1}
    modelName: ${EMBEDDING_MODEL_NAME:mixedbread-ai/mxbai-embed-large-v1}
    timeout: ${EMBEDDING_TIMEOUT:180}
    apiKey: ${EMBEDDING_API_KEY:dummy}
    maxSegmentsPerBatch: 250
```

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `type` | `OLLAMA` \| `OPEN_AI` | `OPEN_AI` | Embedding backend |
| `apiBaseURL` | String | `http://host.docker.internal:1234` | Base URL of the embedding service |
| `apiKey` | String | `lm-studio` | API key (excluded from `toString`) |
| `modelName` | String | `mxbai-embed-large-v1` | Model identifier |
| `dimensions` | Integer | *(null)* | Optional output vector dimension (e.g. for `text-embedding-3-large`) |
| `trustAllCerts` | boolean | `false` | Disables TLS certificate validation |
| `timeout` | int (seconds) | `20` | HTTP timeout per embedding request |
| `customHeaders` | Map | *(empty)* | Extra HTTP headers sent with each request |
| `customQueryParams` | Map | *(empty)* | Extra query parameters appended to the URL |
| `maxSegmentsPerBatch` | int | `32` | Max texts per single HTTP request; the per-project `tibedBatchSize` is split at this boundary |

> **Why `maxSegmentsPerBatch` matters:** langchain4j's `OpenAiEmbeddingModel` defaults to 2048 texts per request. A local model (LM Studio, Ollama) will time out on a request that large. Capping the batch keeps every HTTP call small enough to complete within `timeout`.

### `prjxp.embedding-model` – user-selectable model (Globals panel)

A separate, user-configurable embedding model reference exposed in the Hub's Globals UI:

```yaml
prjxp:
  embedding-model:
    server-type: "openai"
    provider-url: "${PRJXP_EMBEDDING_API_BASE_URL:http://localhost:1234/v1}"
    model-name: "${PRJXP_EMBEDDING_MODEL_NAME:mxbai-embed-large-v1}"
    api-key: "${EMBEDDING_API_KEY}"
    timeout-secs: 20
```

This block is independent of `prjxp.embedding` and allows the operator to switch the embedding model at runtime without restarting the pipeline.

## Chat Model Configuration

Chat models are declared as a list under `prjxp.chat-models` (dev) or `prjxp.chatModels` (Docker). Each entry is a `PrjXPChatModelReference`.

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
| `stereotype` / `stereo-type` | Logical role (e.g. `default`, `detailed`, `documentation`, `javadoc`) |
| `serverType` / `server-type` | Provider type (`lm-studio`, `openai`, …) |
| `providerUrl` / `provider-url` | Base URL of the chat endpoint |
| `modelName` / `model-name` | Model identifier |
| `apiKey` | API key (may reference an env var) |
| `timeoutSecs` / `timeout-secs` | HTTP timeout in seconds |
| `bodyProperties` | Extra JSON fields merged into the request body |

> **Qwen3 thinking mode:** The `enable_thinking: false` body property is required for Qwen3 models. In thinking mode, the model's internal reasoning leaks into the `content` field as XML, which breaks native function calling. DocPipe relies on function calling, so thinking must be disabled for every stereotype that drives document generation.

> **Timeout guidance:** A local 27 B model takes >60 s for document generation (FAQ, architecture, …). The dev profile defaults to 300 s (overridable via `PRJXP_CHAT_TIMEOUT_SECS`); the Docker profile uses 600 s.

## Semantic Graph, Signatures & Reranking

### Semantic graph

```yaml
prjxp:
  semanticGraph:
    enabled: true
    calibrate: false
    gamma: 1.0
    threshold: 0.85
    k: 20
    requireMutual: true
    parallelism: 0
```

| Property | Default | Description |
|----------|---------|-------------|
| `enabled` | `true` | Toggle the semantic-graph enrichment phase |
| `calibrate` | `false` | Run calibration (adjusts `gamma`/`threshold` empirically) |
| `gamma` | `1.0` | Similarity decay exponent |
| `threshold` | `0.85` | Minimum cosine similarity for an edge |
| `k` | `20` | Number of nearest neighbours per chunk |
| `requireMutual` | `true` | Edge exists only if A→B **and** B→A both exceed threshold |
| `parallelism` | `0` | Thread pool size for per-chunk kNN search; `0` = available processors |

### Signatures (null-LLM keyword layer)

```yaml
prjxp:
  signature:
    enabled: true
    topN: 8
    minTermLength: 3
```

Writes TF-IDF keywords per chunk and c-TF-IDF keywords per cluster as chunk metadata after embedding. No LLM call is required.

### Graph reranking

```yaml
prjxp:
  graph:
    rerank:
      floor: 0.98
      b-graph: 5.0
      cap: 5.0
      w-base: 1.0
```

| Property | Value | Description |
|----------|-------|-------------|
| `floor` | `0.98` | Minimum multiplicative weight applied to a chunk's score. Weights are degree-centrality values normalised by the max degree of the whole edge set. A floor of 0.98 keeps code chunks on top while damping peripheral nodes (measured mean rank shift ≈ 7 positions). A lower floor (e.g. 0.8) demotes nearly every code chunk by ~20 %, letting semantically weaker doc chunks win even for code-specific queries. |
| `b-graph` | `5.0` | Graph-similarity boost factor |
| `cap` | `5.0` | Maximum boost applied |
| `w-base` | `1.0` | Base weight multiplier |

## MCP Server & CORS

### MCP client references

```yaml
prjxp:
  mcp-servers:
    - name: prjxp
      type: http
      url: ${PRJXP_HUB_URL:http://localhost:7007/mcp}
```

Declares external MCP servers the Hub can call. The `type: http` entry points back at the Hub's own MCP endpoint by default.

### CORS

```yaml
mcp:
  cors:
    allowed-patterns: ${MCP_CORS_ALLOWED_PATTERNS:http://10.*.*.*,http://172.16.*.*,http://192.168.*.*,http://localhost:*}
```

- Comma-separated origin patterns. **No spaces after commas.**
- Browsers send an `Origin` header on `POST`/`PUT`/`DELETE`. Origins that do not match any pattern are rejected with **403**.
- The default patterns cover RFC-1918 private ranges and `localhost` on any port.
- When deploying behind a reverse proxy with HTTPS, append your public hostname(s), e.g.:

```bash
MCP_CORS_ALLOWED_PATTERNS="http://10.*.*.*,http://172.16.*.*,http://192.168.*.*,http://localhost:*,https://prjxp.example.com"
```

### MCP server protocol

```yaml
spring:
  ai:
    mcp:
      server:
        protocol: STREAMABLE
```

The `STREAMABLE` protocol enables the MCP streamable-HTTP transport (SSE-based). This is the only protocol supported by the current release.

_This document was generated with Doc|Pipe._

