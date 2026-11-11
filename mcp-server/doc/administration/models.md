# Models

PRJXP uses two categories of AI models: **embedding models** (which convert code chunks into vectors for retrieval) and **chat models** (which generate answers via retrieval-augmented generation). Both are configured in the Hub's `application.yml` and can be overridden with environment variables.

## Embedding Models

The embedding model is responsible for converting source-code chunks into dense vectors. PRJXP supports two configuration keys depending on the deployment profile:

### `prjxp.embedding`

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `type` | String | — | Model backend: `OLLAMA` or `OPEN_AI` |
| `apiBaseURL` | String | — | Base URL of the embedding service |
| `modelName` | String | — | Model identifier (e.g. `mxbai-embed-large`) |
| `timeout` | int (seconds) | — | HTTP timeout for embedding requests |
| `apiKey` | String | `dummy` | API key (required for `OPEN_AI`) |
| `maxSegmentsPerBatch` | int | `32` | Maximum texts per embedding HTTP request; the per-project `tibedBatchSize` is split at this size |

Example (Ollama backend):

```yaml
prjxp:
  embedding:
    type: OLLAMA
    apiBaseURL: http://192.168.188.92:11434
    modelName: mxbai-embed-large
    timeout: 60
    maxSegmentsPerBatch: 32
```

Example (OpenAI-compatible backend, e.g. LM Studio):

```yaml
prjxp:
  embedding:
    type: "OPEN_AI"
    apiBaseURL: ${EMBEDDING_API_BASE_URL:http://host.docker.internal:1234/v1}
    modelName: ${EMBEDDING_MODEL_NAME:mixedbread-ai/mxbai-embed-large-v1}
    timeout: ${EMBEDDING_TIMEOUT:180}
    apiKey: ${EMBEDDING_API_KEY:dummy}
    maxSegmentsPerBatch: 32
```

### `prjxp.embedding-model`

An alternative configuration key with a different field naming convention:

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `server-type` | String | — | Provider type (e.g. `openai`) |
| `provider-url` | String | — | Base URL of the embedding service |
| `model-name` | String | — | Model identifier |
| `api-key` | String | — | API key |
| `timeout-secs` | int | — | HTTP timeout in seconds |

```yaml
prjxp:
  embedding-model:
    server-type: "openai"
    provider-url: "${PRJXP_EMBEDDING_API_BASE_URL:http://localhost:1234/v1}"
    model-name: "${PRJXP_EMBEDDING_MODEL_NAME:mxbai-embed-large-v1}"
    api-key: "${EMBEDDING_API_KEY}"
    timeout-secs: 20
```

## Embedding Stores

Embedding stores define where vectors are persisted per project. Configure the list under `prjxp.embedding-stores`:

```yaml
prjxp:
  embedding-stores:
    - project-name: "prjxp"
      default: true

    - project-name: "kigbau"
```

- `project-name` — identifier matching the project name.
- `default` — set to `true` on exactly one entry to mark it as the fallback store.

### Lucene Index

The vector index backend is configured under `prjxp.embeddingStoreLucene`:

| Field | Env Variable | Default | Description |
|-------|-------------|---------|-------------|
| `indexPath` | `LUCENE_INDEX_PATH` | `/app-source/.prjxp-data/lucene-index` | Filesystem path for the Lucene index |
| `vectorDimension` | `LUCENE_VECTOR_DIMENSION` | `1024` | Dimensionality of the embedding vectors |

```yaml
prjxp:
  embeddingStoreLucene:
    indexPath: ${LUCENE_INDEX_PATH:/app-source/.prjxp-data/lucene-index}
    vectorDimension: ${LUCENE_VECTOR_DIMENSION:1024}
```

> **Note:** `vectorDimension` must match the output dimension of the configured embedding model. `mxbai-embed-large` produces 1024-dimensional vectors.

## Chat Models

Chat models are defined as a list under `prjxp.chatModels` (camelCase) or `prjxp.chat-models` (kebab-case). Each entry is a **stereotype** — a named role that the DocPipe and retrieval pipeline selects for a specific task.

### Stereotypes

| Stereotype | Typical Use |
|------------|-------------|
| `default` | General Q&A and interactive chat |
| `detailed` | Long-form document generation (FAQ, architecture docs) |
| `documentation` | Structured documentation output |
| `javadoc` | Javadoc / API-comment generation |

### Example Configuration

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

Kebab-case variant (single default stereotype):

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

> **Timeout guidance:** A local 27 B model can take well over 60 s for document generation. The default timeout is 60 s; raise it (e.g. `300` or `600`) for large local models. Override at runtime with `PRJXP_CHAT_TIMEOUT_SECS`.

## Chat Model Configuration Fields

Each chat-model entry maps to the `PrjXPChatModelReference` class. The following fields are available:

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `stereotype` / `stereo-type` | String | — | Named role for the model |
| `serverType` / `server-type` | String | — | Provider backend (e.g. `lm-studio`) |
| `modelName` / `model-name` | String | — | Model identifier |
| `apiKey` / `api-key` | String | — | API key (excluded from log output) |
| `providerUrl` / `provider-url` | String | — | Base URL of the chat endpoint |
| `timeoutSecs` / `timeout-secs` | int | `60` | HTTP timeout in seconds |
| `temperature` | double | `0.0` | Sampling temperature |
| `prompt` | String | — | Standing instructions prepended to every conversation. Supports `${ENV}` placeholders |
| `supportsTools` | Boolean | `null` | `null` = auto-detect (tools attempted, falls back to text protocol if rejected). `true`/`false` to force |
| `args` | Map\<String, String\> | `{}` | Client/connection-level parameters (e.g. Azure endpoint, `api-version`, `trust-all-certs`). Consumed by the `ChatModelSupplier` implementation |
| `bodyProperties` | Map\<String, Object\> | `{}` | Request-level properties merged into every chat-completion request body as top-level JSON fields. Use for provider-specific knobs with no dedicated config field |

### `bodyProperties` in Practice

The `bodyProperties` map is serialized as top-level JSON fields in the request body. String values that look like booleans or numbers are coerced to their native JSON type by the supplier.

A common use case: Qwen3's "thinking" mode leaks tool calls into the response content as XML, breaking native function calling. Disable it per stereotype:

```yaml
bodyProperties:
  enable_thinking: false
```

### `supportsTools`

- **`null`** (default): PRJXP attempts native function calling. If the provider rejects the tools parameter, the chat falls back to a text-based protocol.
- **`true`**: Forces native function calling; the request fails if the provider does not support it.
- **`false`**: Disables tool sending entirely; the model responds in plain text.

## Provider Types and Environment Variables

### Supported `serverType` / `type` Values

| Value | Context | Notes |
|-------|---------|-------|
| `OLLAMA` | Embedding | Connects to an Ollama instance (`/api/embeddings`) |
| `OPEN_AI` | Embedding | Any OpenAI-compatible `/v1/embeddings` endpoint |
| `openai` | Embedding (`embedding-model`) | Same as `OPEN_AI`, kebab-case variant |
| `lm-studio` | Chat | Any OpenAI-compatible `/v1/chat/completions` endpoint (LM Studio, vLLM, etc.) |

### Environment Variables

| Variable | Purpose | Default |
|----------|---------|---------|
| `EMBEDDING_API_BASE_URL` | Base URL for the embedding service | `http://host.docker.internal:1234/v1` |
| `EMBEDDING_MODEL_NAME` | Embedding model identifier | `mixedbread-ai/mxbai-embed-large-v1` |
| `EMBEDDING_API_KEY` | API key for embedding (and chat) | `dummy` |
| `EMBEDDING_TIMEOUT` | Embedding HTTP timeout (seconds) | `180` |
| `PRJXP_EMBEDDING_API_BASE_URL` | Base URL for `embedding-model` key | `http://localhost:1234/v1` |
| `PRJXP_EMBEDDING_MODEL_NAME` | Model name for `embedding-model` key | `mxbai-embed-large-v1` |
| `PRJXP_CHAT_TIMEOUT_SECS` | Chat HTTP timeout (seconds) | `300` |
| `LUCENE_INDEX_PATH` | Lucene index directory | `/app-source/.prjxp-data/lucene-index` |
| `LUCENE_VECTOR_DIMENSION` | Vector dimensionality | `1024` |
| `PRJXP_MAX_UPLOAD_SIZE` | Max upload size for Hub endpoints | `100MB` |

### Docker Networking

When the Hub runs in Docker and the model server runs on the host, use `host.docker.internal` in `providerUrl` / `apiBaseURL`:

```yaml
providerUrl: http://host.docker.internal:1234/v1
```

For bare-metal or same-network deployments, use the host IP directly:

```yaml
apiBaseURL: http://192.168.188.92:11434
```

### Graph Rerank (Retrieval Scoring)

Retrieved chunks are re-scored using graph centrality before being passed to the chat model. Configure under `prjxp.graph.rerank`:

| Field | Default | Description |
|-------|---------|-------------|
| `floor` | `0.98` | Minimum multiplier applied to a chunk's score. Values near 1.0 keep code chunks ranked high while damping peripheral nodes. |
| `b-graph` | `5.0` | Exponent for the graph-centrality boost |
| `cap` | `5.0` | Maximum multiplier |
| `w-base` | `1.0` | Base weight |

```yaml
prjxp:
  graph:
    rerank:
      floor: 0.98
      b-graph: 5.0
      cap: 5.0
      w-base: 1.0
```

> **Calibration note:** Weights are degree-centrality values normalized by the maximum degree in the edge set. External hub types (e.g. `java.util.List`) dominate the maximum, so the observed range is 0–0.145 (p99 ≈ 0.026). A `floor` of 0.8 demotes nearly every code chunk by ~20 %, letting semantically weaker documentation chunks win even for code-specific queries. `0.98` keeps code on top while still damping peripheral nodes (measured mean rank shift ≈ 7 positions).

_This document was generated with Doc|Pipe._

