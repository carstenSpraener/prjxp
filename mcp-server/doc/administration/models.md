# Models

PRJXP uses two categories of AI models:

- **Embedding models** – convert source-code chunks into fixed-size vectors for similarity search.
- **Chat models** – generate natural-language answers, documentation, and Javadoc via retrieval-augmented generation.

Both are configured in the Hub's `application.yml` (or via environment variables) and are resolved at startup. This chapter covers every field you need to set when deploying or re-pointing the Hub to a different inference backend.

## Embedding Models

The embedding model is responsible for turning each code chunk into a vector. PRJXP supports two provider types:

| `type` value | Backend | Typical use |
|---|---|---|
| `OLLAMA` | Ollama HTTP API | Local GPU/CPU inference |
| `OPEN_AI` | Any OpenAI-compatible `/v1/embeddings` endpoint | LM Studio, vLLM, hosted APIs |

### Configuration block

```yaml
prjxp:
  embedding:
    type: OPEN_AI                          # or OLLAMA
    apiBaseURL: ${EMBEDDING_API_BASE_URL:http://host.docker.internal:1234/v1}
    modelName: ${EMBEDDING_MODEL_NAME:mixedbread-ai/mxbai-embed-large-v1}
    timeout: ${EMBEDDING_TIMEOUT:180}      # seconds
    apiKey: ${EMBEDDING_API_KEY:dummy}
    maxSegmentsPerBatch: 250               # max texts per HTTP request
```

| Field | Description |
|---|---|
| `type` | Provider protocol: `OLLAMA` or `OPEN_AI`. |
| `apiBaseURL` | Base URL of the embedding service. For Ollama this is the Ollama root (e.g. `http://192.168.188.92:11434`). For OpenAI-compatible servers it is the `/v1` prefix. |
| `modelName` | Model identifier as the provider expects it. |
| `timeout` | Per-request timeout in seconds. |
| `apiKey` | Bearer token. Ollama ignores it; OpenAI-compatible servers may require it. |
| `maxSegmentsPerBatch` | Upper bound on the number of texts sent in a single embedding HTTP call. The per-project `tibedBatchSize` is split at this size. |

### Alternative `embedding-model` block

A second, flatter configuration section exists for deployments that separate the embedding endpoint from the main `embedding` block:

```yaml
prjxp:
  embedding-model:
    server-type: "openai"
    provider-url: "${PRJXP_EMBEDDING_API_BASE_URL:http://localhost:1234/v1}"
    model-name: "${PRJXP_EMBEDDING_MODEL_NAME:mxbai-embed-large-v1}"
    api-key: "${EMBEDDING_API_KEY}"
    timeout-secs: 20
```

Use whichever block your deployment profile requires; both are read by the Hub at startup.

## Embedding Stores

Each project gets its own vector store (Lucene-based). The store path and vector dimension are configured globally:

```yaml
prjxp:
  embeddingStoreLucene:
    indexPath: ${LUCENE_INDEX_PATH:/app-source/.prjxp-data/lucene-index}
    vectorDimension: ${LUCENE_VECTOR_DIMENSION:1024}
```

| Field | Description |
|---|---|
| `indexPath` | Filesystem path where Lucene index segments are written. Must be writable by the Hub container. |
| `vectorDimension` | Dimensionality of the embedding vectors. **Must match the output dimension of the configured embedding model** (e.g. `mxbai-embed-large` → 1024). A mismatch causes retrieval failures. |

You can also declare named stores per project:

```yaml
prjxp:
  embedding-stores:
    - project-name: "prjxp"
      default: true
    - project-name: "kigbau"
```

The entry with `default: true` is used when a project does not specify its own store.

## Chat Models and Stereotypes

Chat models are declared as a **list**. Each entry carries a **stereotype** that tells the Hub which pipeline step should use that model:

| Stereotype | Used for |
|---|---|
| `default` | General Q&A / interactive chat in the web UI. |
| `detailed` | Long-form document generation (FAQ, architecture overviews). |
| `documentation` | Automated documentation pages. |
| `javadoc` | Javadoc / API-comment generation. |

If a stereotype is not defined, the Hub falls back to the entry marked `default`.

### Example configuration

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

> **Note:** The kebab-case form (`chat-models`, `stereo-type`, `server-type`, `provider-url`, `timeout-secs`) is also accepted by Spring Boot's relaxed binding. Both forms map to the same `PrjXPChatModelReference` fields.

## Chat Model Configuration Fields

Every entry in the `chatModels` list maps to the `PrjXPChatModelReference` class. The full field set:

| Field | Type | Default | Description |
|---|---|---|---|
| `stereotype` | `String` | – | Pipeline role (see table above). |
| `serverType` | `String` | – | Provider identifier, e.g. `lm-studio`, `openai`, `azure`. Determines which `ChatModelSupplier` is instantiated. |
| `modelName` | `String` | – | Model name as the provider expects it. |
| `apiKey` | `String` | – | Authentication token. Excluded from log output. |
| `providerUrl` | `String` | – | Base URL of the chat-completions endpoint. |
| `timeoutSecs` | `int` | `60` | Per-request timeout in seconds. |
| `temperature` | `double` | `0.0` | Sampling temperature. |
| `prompt` | `String` | – | Optional standing instructions prepended to every conversation. Supports `${ENV_VAR}` placeholders resolved at startup. |
| `supportsTools` | `Boolean` | `null` | `true`/`false` to force native function calling on or off. `null` (default) = auto-detect: tools are attempted; if the provider rejects them the chat falls back to the text protocol. |
| `args` | `Map<String,String>` | `{}` | Client/connection-level parameters consumed by the specific `ChatModelSupplier` (e.g. Azure `api-version`, `trust-all-certs`). |
| `bodyProperties` | `Map<String,Object>` | `{}` | Request-level properties merged into every chat-completion request body as top-level JSON fields. See next section. |

### Environment-variable overrides

Common overrides you will set in the Docker `docker-compose.yml` or `.env`:

```bash
PRJXP_CHAT_TIMEOUT_SECS=300
EMBEDDING_API_BASE_URL=http://host.docker.internal:1234/v1
EMBEDDING_MODEL_NAME=mixedbread-ai/mxbai-embed-large-v1
EMBEDDING_API_KEY=your-key-here
```

## Provider-Specific Request Properties

The `bodyProperties` map lets you inject arbitrary top-level JSON fields into every `/chat/completions` request body without modifying code. Values may be booleans, numbers, strings, or nested objects. String values that look like booleans or numbers are coerced to their native JSON type by the supplier.

### Disabling Qwen3 thinking mode

Qwen3 models expose a "thinking" mode that, when active, leaks the tool-call XML into the `content` field and breaks native function calling. PRJXP's DocPipe relies on function calling, so you must disable it:

```yaml
bodyProperties:
  enable_thinking: false
```

### Forcing or disabling tool support

If your provider does not support the OpenAI tools schema, set `supportsTools: false` to skip the tool-calling path entirely:

```yaml
- stereotype: default
  serverType: openai
  providerUrl: https://api.openai.com/v1
  modelName: gpt-4o-mini
  apiKey: ${OPENAI_API_KEY}
  supportsTools: false
```

Conversely, set `supportsTools: true` to assert that the provider handles tools, bypassing the auto-detect fallback.

### Connection-level arguments

The `args` map is consumed by the supplier at client-creation time (not per-request). Example for an Azure OpenAI endpoint:

```yaml
- stereotype: default
  serverType: azure
  providerUrl: https://my-resource.openai.azure.com
  modelName: gpt-4o
  apiKey: ${AZURE_OPENAI_KEY}
  args:
    api-version: "2024-06-01"
    deployment-id: "gpt-4o"
```

## MCP Server Registration

The Hub exposes its own MCP (Model Context Protocol) endpoint so that external chat clients can query the project index. Register it in the consuming application's config:

```yaml
prjxp:
  mcp-servers:
    - name: prjxp
      type: http
      url: ${PRJXP_HUB_URL:http://localhost:7007/mcp}
```

The Hub's MCP server itself is configured under the Spring AI section:

```yaml
spring:
  ai:
    mcp:
      server:
        protocol: STREAMABLE
        name: "prjxp-mcp"
        version: "1.0.0"
```

CORS for the MCP endpoint is controlled by:

```yaml
mcp:
  cors:
    allowed-patterns: ${MCP_CORS_ALLOWED_PATTERNS:http://10.*.*.*,http://172.16.*.*.*,http://192.168.*.*.*,http://localhost:*}
```

> **Important:** The pattern list is comma-separated with **no spaces** after commas. Browsers send an `Origin` header on POST/PUT/DELETE; origins not matching a pattern are rejected with HTTP 403. Add your public hostname(s) when deploying behind a reverse proxy with HTTPS.

_This document was generated with Doc|Pipe._

