# Models

PRJXP uses two categories of AI models:

- **Embedding models** – convert source-code chunks into fixed-size vectors for similarity search.
- **Chat models** – generate natural-language answers (and documentation) from retrieved context.

Both are configured in the Hub's `application.yml` (or `application.yaml`) and can be overridden with environment variables. This chapter covers every relevant field, the supported provider types, and the stereotype mechanism for chat models.

---

## Embedding Models

The embedding model is the component that turns each code chunk into a vector. PRJXP supports two provider types, selected via the `type` field:

| `type` value | Provider | Typical use |
|---|---|---|
| `OLLAMA` | Ollama server | Local, self-hosted inference |
| `OPEN_AI` | Any OpenAI-compatible API (LM Studio, vLLM, OpenAI, etc.) | Local or remote |

### Configuration block

```yaml
prjxp:
  embedding:
    type: "OPEN_AI"
    apiBaseURL: ${EMBEDDING_API_BASE_URL:http://host.docker.internal:1234/v1}
    modelName: ${EMBEDDING_MODEL_NAME:mixedbread-ai/mxbai-embed-large-v1}
    timeout: ${EMBEDDING_TIMEOUT:180}
    apiKey: ${EMBEDDING_API_KEY:dummy}
```

| Field | Type | Default | Description |
|---|---|---|---|
| `type` | String | – | `OLLAMA` or `OPEN_AI`. Determines which HTTP client the Hub uses. |
| `apiBaseURL` | String | – | Base URL of the embedding endpoint (e.g. `http://host.docker.internal:1234/v1`). |
| `modelName` | String | – | Model identifier as expected by the provider (e.g. `mixedbread-ai/mxbai-embed-large-v1`). |
| `timeout` | int (seconds) | `180` | Per-request timeout for the embedding call. |
| `apiKey` | String | `dummy` | Bearer token. For local providers (LM Studio, Ollama) a placeholder is sufficient. |

### Ollama example

```yaml
prjxp:
  embedding:
    type: OLLAMA
    apiBaseURL: http://192.168.188.92:11434
    modelName: mxbai-embed-large
    timeout: 60
```

### Alternative `embedding-model` block

A second, kebab-case configuration path exists for environments that prefer explicit provider naming:

```yaml
prjxp:
  embedding-model:
    server-type: "openai"
    provider-url: "${PRJXP_EMBEDDING_API_BASE_URL:http://localhost:1234/v1}"
    model-name: "${PRJXP_EMBEDDING_MODEL_NAME:mxbai-embed-large-v1}"
    api-key: "${EMBEDDING_API_KEY}"
    timeout-secs: 20
```

The fields map one-to-one to the `embedding` block above (`server-type` ≡ `type`, `provider-url` ≡ `apiBaseURL`, etc.). Use whichever block your deployment profile expects; do not define both simultaneously.

---

## Embedding Stores

Each project gets its own vector index. The Lucene-based store is configured under `prjxp.embeddingStoreLucene`:

```yaml
prjxp:
  embeddingStoreLucene:
    indexPath: ${LUCENE_INDEX_PATH:/app-source/.prjxp-data/lucene-index}
    vectorDimension: ${LUCENE_VECTOR_DIMENSION:1024}
```

| Field | Type | Default | Description |
|---|---|---|---|
| `indexPath` | String | `/app-source/.prjxp-data/lucene-index` | Filesystem path where Lucene writes the vector index. Must be writable by the Hub process. |
| `vectorDimension` | int | `1024` | Dimensionality of the embedding vectors. **Must match** the output dimension of the configured embedding model. |

### Per-project store registration

Individual projects are registered in a list. The entry marked `default: true` is used when no explicit project is specified:

```yaml
prjxp:
  embedding-stores:
    - project-name: "prjxp"
      default: true

    - project-name: "kigbau"
```

Add one entry per project you want the Hub to index. The `project-name` must match the name used during project import.

---

## Chat Models

Chat models are defined as a **list** of entries. Each entry carries a **stereotype** – a logical role that PRJXP's internal pipelines (DocPipe, Q&A, Javadoc generation) use to select the appropriate model.

### Stereotypes observed in the source

| Stereotype | Typical use |
|---|---|
| `default` | General Q&A / interactive chat |
| `detailed` | Long-form document generation (FAQ, architecture docs) |
| `documentation` | Structured documentation output |
| `javadoc` | Per-method / per-class Javadoc generation |

You may define additional stereotypes; any string is valid. PRJXP falls back to the `default` stereotype if the requested one is not configured.

### Configuration block (Docker / camelCase)

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

### Configuration block (kebab-case, non-Docker)

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

> **Note:** The kebab-case variant uses `stereo-type` (not `stereotype`). Both forms bind to the same Java class; use the form that matches your Spring profile.

### Supported `serverType` values

| Value | Provider |
|---|---|
| `lm-studio` | LM Studio (OpenAI-compatible local server) |
| `openai` | OpenAI API or any OpenAI-compatible endpoint |
| `ollama` | Ollama |

The `providerUrl` must point to the provider's OpenAI-compatible `/v1` endpoint (or the Ollama root for `ollama`).

---

## Chat Model Configuration Fields

Every entry in the `chatModels` list maps to the `PrjXPChatModelReference` class. The full field set:

| Field | Type | Default | Description |
|---|---|---|---|
| `stereotype` / `stereo-type` | String | – | Logical role identifier (see table above). |
| `serverType` / `server-type` | String | – | Provider: `lm-studio`, `openai`, `ollama`. |
| `modelName` / `model-name` | String | – | Model identifier as the provider expects. |
| `apiKey` / `api-key` | String | – | Auth token. Excluded from log output (`@ToString.Exclude`). |
| `providerUrl` / `provider-url` | String | – | Base URL of the chat endpoint. |
| `timeoutSecs` / `timeout-secs` | int | `60` | Per-request timeout in seconds. |
| `temperature` | double | `0.0` | Sampling temperature. |
| `prompt` | String | – | Optional standing system prompt, prepended to every conversation. Supports `${ENV_VAR}` placeholders. |
| `supportsTools` | Boolean | `null` | `true`/`false` to force native function calling on or off. `null` (default) = auto-detect: tools are attempted; if the provider rejects them, the chat falls back to a text protocol. |
| `args` | Map\<String, String\> | `{}` | Client/connection-level parameters consumed by the provider-specific `ChatModelSupplier` (e.g. Azure endpoint, `api-version`, `trust-all-certs`). |
| `bodyProperties` | Map\<String, Object\> | `{}` | Request-level properties merged into every chat-completion request body as top-level JSON fields. Use for provider-specific knobs that have no dedicated config field. |

### `bodyProperties` in practice

The most common use case is disabling Qwen3's thinking mode, which otherwise leaks tool-call XML into the response content and breaks DocPipe's function-calling pipeline:

```yaml
bodyProperties:
  enable_thinking: false
```

Values may be booleans, numbers, strings, or nested objects. String values that look like booleans or numbers are coerced to their native JSON type by the supplier before the request is sent.

### `supportsTools`

Set this explicitly when you know the provider's tool-calling behaviour:

```yaml
supportsTools: true    # force native function calling
supportsTools: false   # disable; use text-protocol tool simulation
# omit (null)         # auto-detect with graceful fallback
```

### `args`

Supplier-specific connection parameters. Example (hypothetical Azure deployment):

```yaml
args:
  azure-endpoint: "https://my-resource.openai.azure.com"
  api-version: "2024-02-01"
```

These are passed to the `ChatModelSupplier` implementation and do **not** appear in the request body.

---

## Environment Variables

All configuration values that accept `${...}` placeholders can be overridden at container start. The following variables are referenced in the source material:

| Variable | Default | Used by |
|---|---|---|
| `EMBEDDING_API_BASE_URL` | `http://host.docker.internal:1234/v1` | `prjxp.embedding.apiBaseURL` |
| `EMBEDDING_MODEL_NAME` | `mixedbread-ai/mxbai-embed-large-v1` | `prjxp.embedding.modelName` |
| `EMBEDDING_TIMEOUT` | `180` | `prjxp.embedding.timeout` |
| `EMBEDDING_API_KEY` | `dummy` | `prjxp.embedding.apiKey`, chat `apiKey` |
| `PRJXP_EMBEDDING_API_BASE_URL` | `http://localhost:1234/v1` | `prjxp.embedding-model.provider-url` |
| `PRJXP_EMBEDDING_MODEL_NAME` | `mxbai-embed-large-v1` | `prjxp.embedding-model.model-name` |
| `PRJXP_CHAT_TIMEOUT_SECS` | `300` | `prjxp.chat-models[].timeout-secs` |
| `LUCENE_INDEX_PATH` | `/app-source/.prjxp-data/lucene-index` | `prjxp.embeddingStoreLucene.indexPath` |
| `LUCENE_VECTOR_DIMENSION` | `1024` | `prjxp.embeddingStoreLucene.vectorDimension` |
| `PRJXP_ROOT_DIR` | `.` | Hub data directories (`import`, `projects`, `chunks`) |
| `PRJXP_MAX_UPLOAD_SIZE` | `100MB` | `spring.servlet.multipart.max-file-size` / `max-request-size` |
| `SERVER_PORT` | `7007` | `server.port` |
| `MCP_CORS_ALLOWED_PATTERNS` | `http://10.*.*.*,http://172.16.*.*.*,http://192.168.*.*.*,http://localhost:*` | `mcp.cors.allowed-patterns` |
| `PRJXP_HUB_URL` | `http://localhost:7007/mcp` | `prjxp.mcp-servers[].url` |
| `PRJXP_DOCS_DIR` | `/app/doc` | `prjxp.docs.dir` |

### Docker `docker run` example

```bash
docker run -d \
  --name prjxp-hub \
  -p 7007:7007 \
  -e EMBEDDING_API_BASE_URL=http://host.docker.internal:1234/v1 \
  -e EMBEDDING_MODEL_NAME=mixedbread-ai/mxbai-embed-large-v1 \
  -e EMBEDDING_API_KEY=your-key \
  -e LUCENE_VECTOR_DIMENSION=1024 \
  -e PRJXP_CHAT_TIMEOUT_SECS=300 \
  -v /data/prjxp:/app-source/.prjxp-data \
  prjxp/hub:latest
```

> **Tip:** When running the Hub in Docker alongside a local LM Studio instance on the host, use `host.docker.internal` (Linux: add `--add-host=host.docker.internal:host-gateway`) or the host's LAN IP in `providerUrl` and `apiBaseURL`.

_This document was generated with Doc|Pipe._

