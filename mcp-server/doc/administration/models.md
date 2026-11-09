# Models

PRJXP uses two categories of AI models:

- **Embedding models** – convert source-code chunks into fixed-size vectors for the Lucene-based embedding store.
- **Chat models** – generate natural-language answers (and documentation) via retrieval-augmented generation.

Both are configured in the Hub's `application.yml` (or `application.yaml`) under the `prjxp` root key. All values can be overridden with environment variables, which is the recommended approach for Docker deployments.

## Embedding Models

The embedding model is responsible for turning each code chunk into a vector. PRJXP supports two provider types:

| `type` value | Provider | Typical use-case |
|---|---|---|
| `OLLAMA` | Ollama server | Local GPU/CPU inference |
| `OPEN_AI` | Any OpenAI-compatible API (LM Studio, vLLM, hosted) | Local or cloud inference |

### Configuration

```yaml
prjxp:
  embedding:
    type: "OPEN_AI"
    apiBaseURL: ${EMBEDDING_API_BASE_URL:http://host.docker.internal:1234/v1}
    modelName: ${EMBEDDING_MODEL_NAME:mixedbread-ai/mxbai-embed-large-v1}
    timeout: ${EMBEDDING_TIMEOUT:180}
    apiKey: ${EMBEDDING_API_KEY:dummy}
```

| Field | Description |
|---|---|
| `type` | `OLLAMA` or `OPEN_AI`. Selects the client implementation. |
| `apiBaseURL` | Base URL of the embedding endpoint. For Docker, use `host.docker.internal` to reach a host-local server. |
| `modelName` | Model identifier as expected by the provider. |
| `timeout` | Request timeout in seconds (default `180`). |
| `apiKey` | API key. Set to `dummy` for local servers that ignore it. |

### Lucene Embedding Store

The vector index is backed by Apache Lucene. Configure it under `prjxp.embeddingStoreLucene`:

```yaml
prjxp:
  embeddingStoreLucene:
    indexPath: ${LUCENE_INDEX_PATH:/app-source/.prjxp-data/lucene-index}
    vectorDimension: ${LUCENE_VECTOR_DIMENSION:1024}
```

| Field | Description |
|---|---|
| `indexPath` | Filesystem path for the Lucene index. Must be writable by the Hub process. |
| `vectorDimension` | Dimensionality of the vectors. **Must match** the output dimension of the chosen embedding model (e.g. `1024` for `mxbai-embed-large`). |

### Alternative: `embedding-model` block

A second, provider-agnostic block exists for environments that use a different configuration schema:

```yaml
prjxp:
  embedding-model:
    server-type: "openai"
    provider-url: "${PRJXP_EMBEDDING_API_BASE_URL:http://localhost:1234/v1}"
    model-name: "${PRJXP_EMBEDDING_MODEL_NAME:mxbai-embed-large-v1}"
    api-key: "${EMBEDDING_API_KEY}"
    timeout-secs: 20
```

Use either `prjxp.embedding` **or** `prjxp.embedding-model`; do not configure both simultaneously.

## Chat Models

Chat models are defined as a **list** under `prjxp.chatModels` (or `prjxp.chat-models` in kebab-case YAML). Each entry describes one model endpoint and its behaviour.

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
```

### Example: multiple stereotypes

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

> **Note:** The `timeoutSecs` default is **60 seconds**. Large local models (e.g. a 27 B parameter model) can easily exceed that for document generation. Raise it via the `PRJXP_CHAT_TIMEOUT_SECS` environment variable or set `timeoutSecs` explicitly per entry.

## Stereotypes

A **stereotype** is a logical role that a chat model fulfils within PRJXP. The platform selects the appropriate model based on the stereotype of the task at hand.

| Stereotype | Typical use |
|---|---|
| `default` | General Q&A / interactive chat in the web UI |
| `detailed` | In-depth code explanation, architecture analysis |
| `documentation` | Automated document generation (FAQ, architecture docs) |
| `javadoc` | Javadoc / API-comment generation (DocPipe) |

You may define multiple entries with the **same** stereotype (e.g. two `detailed` models pointing at different providers) or a single entry that covers several roles. The Hub matches by the `stereotype` / `stereo-type` string.

## Configuration Fields

The following table lists every field available on a chat-model entry (class `PrjXPChatModelReference`):

| Field | Type | Default | Description |
|---|---|---|---|
| `stereotype` / `stereo-type` | `String` | – | Logical role (see table above). |
| `serverType` / `server-type` | `String` | – | Provider identifier, e.g. `lm-studio`, `openai`. Selects the `ChatModelSupplier` implementation. |
| `modelName` / `model-name` | `String` | – | Model name as the provider expects it. |
| `apiKey` / `api-key` | `String` | – | API key. Excluded from log output (`@ToString.Exclude`). |
| `providerUrl` / `provider-url` | `String` | – | Base URL of the provider's OpenAI-compatible endpoint. |
| `timeoutSecs` / `timeout-secs` | `int` | `60` | Per-request timeout in seconds. |
| `temperature` | `double` | `0.0` | Sampling temperature. |
| `prompt` | `String` | – | Optional standing instructions prepended to every conversation. Supports `${ENV_VAR}` placeholders. |
| `supportsTools` | `Boolean` | `null` | Explicitly enable/disable native function calling. `null` = auto-detect (tools are attempted; the chat falls back to the text protocol if the provider rejects them). |
| `args` | `Map<String,String>` | `{}` | Client/connection-level parameters consumed by the supplier (e.g. Azure endpoint, `api-version`, `trust-all-certs`). |
| `bodyProperties` | `Map<String,Object>` | `{}` | Request-level properties merged into every chat-completion request body as top-level JSON fields. |

## Provider-Specific Options

### `bodyProperties`

`bodyProperties` lets you inject arbitrary top-level JSON fields into the chat-completion request body. This is the mechanism for provider-specific knobs that have no dedicated configuration field.

```yaml
bodyProperties:
  enable_thinking: false
```

Rules:

- Values may be booleans, numbers, strings, or nested objects.
- String values that look like booleans (`"true"`, `"false"`) or numbers are **coerced** to their native JSON type by the supplier.
- A common use-case: Qwen3's "thinking" mode leaks the tool-call into the response content as XML, breaking native function calling. Setting `enable_thinking: false` disables it.

### `args`

`args` carries **connection-level** (not per-request) parameters. The individual `ChatModelSupplier` implementations read these to configure the HTTP client or provider SDK.

```yaml
args:
  api-version: "2024-02-01"
  trust-all-certs: "true"
```

Typical keys include Azure-specific settings (`api-version`, custom endpoint) or TLS options (`trust-all-certs`). The exact keys depend on the `serverType` supplier in use.

### `supportsTools`

Controls whether the model is offered native function-calling (tool) schemas:

| Value | Behaviour |
|---|---|
| `true` | Tools are always sent; the provider **must** support them. |
| `false` | Tools are never sent; the model uses the text protocol only. |
| `null` (default) | Auto-detect: tools are attempted first; if the provider rejects them, the chat transparently falls back to the text protocol. |

Set this explicitly when you know the model's capabilities to avoid a failed first request during auto-detection.

### `prompt`

A standing system-level instruction prepended to every conversation with that model. Useful for enforcing output format, language, or project-specific conventions.

```yaml
prompt: "You are documenting the PRJXP codebase. Always reference file paths."
```

`${ENV_VAR}` placeholders in the value are resolved at startup, allowing per-deployment customisation without editing the YAML.

_This document was generated with Doc|Pipe._

