# DocPipe Tool-Enabled LLM Concept

**Goal:** Define how DocPipe can allow an LLM to use MCP/tools while still exposing the existing `String chat(String)` generation principle to the rest of DocPipe.

**Architecture:** Keep DocPipe's external flow as “prompt in, document string out”. Add an optional internal tool-enabled chat execution path that performs model/tool iterations before returning the final answer string. Use the existing `stereotype` model selection concept and configure tool permission per document.

**Tech Stack:** Java, Spring Boot, current DocPipe model/config classes, existing PrjXP `KIChat` abstraction, LangChain4j as primary candidate for the first tool-calling implementation, optional Spring AI/MCP later.

---

## Current Context

The current code path is simple and synchronous:

1. `ContentCreationService.createContent(...)`
   - resolves the prompt via `PromptResolvingService`
   - calls `LLMService.chat(ccTask, prompt)`
   - writes the returned string to the configured output sink

2. `LLMService.chat(...)`
   - reads `DPContentCreation.stereotype`
   - gets a `KIChat` via `KIChatProvider.getByStereotype(stereotype)`
   - calls `chat.chat(prompt)`

3. `KIChat` currently exposes:
   - `String chat(String question)`
   - `String analyzeImage(BufferedImage image)`

4. `KIChatModelWrapper.chat(String)` currently delegates directly to LangChain4j `ChatModel.chat(question)`.

This means DocPipe currently performs a single model call per document. Tool calls are not represented in the public API or in the content creation model.

Important design constraints:

- The principle `String chat(String)` should remain intact.
- Tool calls may happen internally while generating the result string.
- DocPipe and Chunk-Norris remain independent.
- Tool/MCP configuration should be hybrid:
  - central definitions are tied to model/stereotype infrastructure
  - per-document config decides whether and which tools are allowed
- For the first concept, tool configuration is only per document; no job-level defaults yet.

---

## Conceptual Target Model

### Outer Contract Remains String-Based

From DocPipe's perspective, this remains valid:

```java
String content = llmService.chat(ccTask, prompt);
```

And inside a `KIChat` implementation this may happen:

```text
prompt
  -> model response asks for tool call
  -> DocPipe executes allowed tool
  -> tool result appended to conversation
  -> model asks for another tool or finalizes
  -> final answer string returned
```

So `String chat(String)` remains the visible abstraction, but no longer implies exactly one provider call.

### Hybrid Configuration

The `stereotype` continues to select the model. Tool capability is layered on top.

- Model/stereotype config answers:
  - which model is used?
  - which centrally known tool profiles are generally compatible/available?

- Document config answers:
  - are tools enabled for this document?
  - which profiles are allowed for this document?
  - how many tool iterations are allowed?
  - should tool activity be logged/stored?

---

## Proposed Configuration Shape

### `documents.json`: Per-Document Tool Allowance

Add an optional field to `DPContentCreation`, e.g. `tools`.

Example:

```json
{
  "outputFile": "Architecture.md",
  "stereotype": "architecture",
  "prompt": "ArchitectureAssessment.prompt.txt",
  "tools": {
    "enabled": true,
    "profiles": ["repo-readonly"],
    "maxIterations": 5,
    "storeToolTrace": true
  }
}
```

Recommended Java model:

```java
public class DPContentCreation {
    // existing fields ...
    private DPToolConfig tools;
}
```

```java
@Data
public class DPToolConfig {
    private boolean enabled = false;
    private List<String> profiles = List.of();
    private int maxIterations = 5;
    private boolean storeToolTrace = false;
}
```

### Central Tool/Profile Definitions

Do not put full MCP server definitions into every document. Define reusable profiles centrally.

Possible future locations:

1. Extend PrjXP model config / `models.json`.
2. Add a DocPipe-specific tool config file under `.dp`, e.g. `.dp/tools.json`.

Recommended first concept: `.dp/tools.json`, because it avoids overloading model references too early while still being central.

Example:

```json
{
  "profiles": [
    {
      "name": "repo-readonly",
      "servers": ["filesystem-project"],
      "allowedTools": ["read_file", "list_files", "search"],
      "readOnly": true
    }
  ],
  "mcpServers": [
    {
      "name": "filesystem-project",
      "transport": "stdio",
      "command": "npx",
      "args": ["-y", "@modelcontextprotocol/server-filesystem", "${projectRoot}"],
      "readOnly": true
    }
  ]
}
```

A later iteration may move or merge this into the existing PrjXP configuration if that proves cleaner.

---

## Runtime Components

### `ToolRunConfig`

A runtime object assembled from:

- `DPContentCreation.tools`
- central tool profile definitions
- `ContentCreationTask.dpJob.rootDir`
- selected model/stereotype

Responsibilities:

- contains allowed tool definitions
- contains max iteration count
- contains sandbox/root information
- contains logging/trace options

### `DocPipeToolConfigService`

Loads and resolves tool configuration.

Likely responsibilities:

```java
public Optional<ToolRunConfig> resolve(ContentCreationTask task) {
    // if task.dpContentCreation.tools is null or disabled: Optional.empty()
    // load .dp/tools.json
    // resolve profiles referenced by document
    // validate all referenced servers/tools exist
    // enforce read-only defaults
}
```

### `ToolEnabledChatService` or `ToolAwareKIChatFactory`

Creates a tool-capable `KIChat` wrapper for a single content creation request.

Possible shape:

```java
public interface ToolEnabledChatFactory {
    KIChat wrap(KIChat baseChat, ToolRunConfig toolRunConfig);
}
```

Or, if direct access to the underlying LangChain4j `ChatModel` is required:

```java
public interface ToolEnabledChatFactory {
    KIChat create(PrjXPChatModelReference modelRef, ToolRunConfig toolRunConfig);
}
```

The second shape may be more realistic because tool calling usually has to be configured at the model/request layer rather than by wrapping a completed string-only `KIChat`.

### `ToolAwareKIChat implements KIChat`

Still exposes:

```java
@Override
public String chat(String question)
```

But internally runs:

1. Build initial conversation from `question`.
2. Attach allowed tool specifications.
3. Call model.
4. If model requests tool calls:
   - validate tool is allowed
   - execute tool through MCP/tool backend
   - append tool result
   - continue
5. Stop when final assistant text is produced or max iterations is reached.
6. Return final text.

### `DocPipeMcpClientService`

Encapsulates MCP details.

Responsibilities:

- start/connect configured MCP servers
- list available tools
- filter tools by profile allowlist
- execute a selected tool call
- enforce read-only policy where possible
- map MCP results into model-consumable tool messages
- close/cleanup server processes after the document run if needed

---

## Integration Into Existing Flow

### Minimal Change to `LLMService`

Current logic:

```java
return chatProvider.getByStereotype(stereotype)
        .map(chat -> chat.chat(prompt))
        .orElseThrow(...);
```

Conceptual new logic:

```java
KIChat chat = chatProvider.getByStereotype(stereotype)
        .orElseThrow(...);

Optional<ToolRunConfig> toolRunConfig = toolConfigService.resolve(ccTask);

if (toolRunConfig.isPresent()) {
    chat = toolEnabledChatFactory.wrapOrCreate(chat, toolRunConfig.get());
}

return chat.chat(prompt);
```

The output remains a `String`.

### Alternative: Keep `LLMService` Thin

If `KIChatProvider` becomes context-aware:

```java
KIChat chat = chatProvider.getByStereotype(stereotype, toolRunConfig);
return chat.chat(prompt);
```

But this would alter more existing APIs. For a first implementation, keeping the new behavior in `LLMService` is likely less invasive.

---

## Tool Loop Semantics

### Termination Conditions

The loop should stop when one of these occurs:

- model returns final assistant text with no tool calls
- `maxIterations` reached
- tool call is disallowed
- tool execution fails in a non-recoverable way

Recommended behavior on max iterations:

- warn in logs
- ask model for best final answer using gathered information
- if that fails, return the last assistant text or an explicit failure message

### Error Handling

- Unknown/disallowed tool call:
  - WARN log
  - feed an error tool result back to the model if provider supports it
  - stop if repeated

- MCP server startup failure:
  - ERROR log
  - fail the document generation task, unless config later adds `fallbackWithoutTools`

- Tool execution failure:
  - ERROR log for infrastructure failures
  - WARN for expected domain failures such as file not found
  - return tool error to model where possible

### Traceability

For documentation generation, traceability matters. The first implementation should be able to store:

- which tools were available
- which tools the model called
- arguments, with secret redaction
- results summary or full results depending on config
- final iteration count

Possible output path:

```text
.dp/tool-traces/<outputFile>.tool-trace.jsonl
```

Only enable by document config, e.g. `storeToolTrace: true`.

---

## Security Boundaries

Recommended first policy:

- Tool profiles are read-only by default.
- MCP filesystem roots are constrained to `DPJob.rootDir` unless explicitly configured otherwise.
- No shell execution in the first implementation.
- No write-capable tools in the first implementation.
- Tool call arguments are validated before execution.
- Tool result sizes are capped to avoid blowing up the LLM context.
- Secrets and `.env`-like files are blocked or redacted.

This is especially important because DocPipe is designed to generate documentation over existing projects; the model should inspect, not mutate.

---

## LangChain4j vs Spring AI

### LangChain4j First

Pros:

- Current `KIChatModelWrapper` already uses LangChain4j `ChatModel`.
- Existing model suppliers already create LangChain4j models.
- Less disruptive to `prjxp-common` and DocPipe.

Cons:

- Need to verify exact APIs in the project’s LangChain4j version.
- MCP support may require either an extra LangChain4j module or a custom MCP-to-tool adapter.

### Spring AI Later or Alternative

Pros:

- Spring AI has explicit MCP integration paths.
- Fits Spring Boot wiring well.

Cons:

- Current chat abstraction is LangChain4j-first.
- Switching DocPipe to Spring AI ChatClient could duplicate model configuration.
- Might require parallel model supplier infrastructure.

Recommendation:

Start with a backend-neutral DocPipe abstraction (`DocPipeToolProvider`, `ToolRunConfig`, `ToolAwareKIChat`) and implement the first backend with the least invasive LangChain4j path. Keep Spring AI MCP as an implementation option, not as a design dependency.

---

## Proposed Implementation Phases

### Phase 0: Spike Only

Goal: Verify exact LangChain4j APIs for tool calling and whether an MCP adapter exists/needs to be custom.

No production changes except a throwaway spike or notes.

Questions to answer:

- Can current `dev.langchain4j.model.chat.ChatModel` support tool call request/response in this version?
- Is `ChatModel.chat(String)` sufficient, or must code use lower-level messages/requests?
- Which classes represent tool specifications and tool executions?
- Is there existing LangChain4j MCP support in the available version?

### Phase 1: Configuration Model Only

Add models and validation, no actual tool execution yet.

Likely files:

- `docpipe/src/main/java/de/spraener/prjxp/docpipe/model/DPContentCreation.java`
- new `docpipe/src/main/java/de/spraener/prjxp/docpipe/model/DPToolConfig.java`
- new tool config model classes under `docpipe/src/main/java/de/spraener/prjxp/docpipe/tools/`
- `docpipe/src/main/java/de/spraener/prjxp/docpipe/config/DotDPFilesService.java`

Tests:

- parse `documents.json` with `tools` block
- load `.dp/tools.json`
- resolve profile references
- fail clearly on unknown profile

### Phase 2: Internal Tool-Loop Interface

Add abstractions but use a fake/in-memory tool backend in tests.

Likely new interfaces/classes:

- `DocPipeToolProvider`
- `DocPipeTool`
- `ToolRunConfig`
- `ToolCallTrace`
- `ToolAwareKIChat`
- `ToolEnabledChatFactory`

Tests:

- `String chat(String)` returns final model text after one fake tool call
- max iterations enforced
- disallowed tool rejected
- trace recorded when enabled

### Phase 3: MCP Backend

Implement MCP server connection/execution behind the tool abstraction.

Likely classes:

- `DocPipeMcpClientService`
- `McpServerDefinition`
- `McpToolProvider`

Tests:

- unit tests with fake MCP client
- optional integration test behind profile flag for a real read-only filesystem MCP server

### Phase 4: Wire Into `LLMService`

Modify `LLMService` to choose normal or tool-enabled chat execution.

Expected behavior:

- no `tools` config: exact current path
- `tools.enabled=false`: exact current path
- `tools.enabled=true`: tool-aware path

Tests:

- existing no-tool behavior unchanged
- tool-enabled document uses tool-aware chat
- missing/invalid tool profile logs/fails predictably

### Phase 5: Documentation and Operational Safety

Add docs for `.dp/tools.json` and `documents.json` usage.

Document:

- read-only recommendation
- max iteration default
- trace file location
- examples for architecture documentation
- limitations and known provider support

---

## Risks and Tradeoffs

### Provider Compatibility

Not every model/provider combination supports tool calling equally. A tool-enabled document may need model compatibility validation.

Mitigation:

- validate at startup/run time
- fail fast with clear message if selected stereotype/model cannot use tools

### Context Growth

Tool results can explode context just like source dumps.

Mitigation:

- cap tool result size
- prefer search/list/read-small tools
- require model to request narrower reads

### Security

MCP tools can be powerful.

Mitigation:

- read-only first
- project-root sandbox
- no shell/write tools initially
- explicit per-document allowlist

### Complexity Hidden Behind `String chat(String)`

Keeping the string API is good for callers, but can hide a complex multi-step operation.

Mitigation:

- trace logs
- explicit tool-enabled config
- clear max iterations and timeout behavior

---

## Open Questions

1. Should `.dp/tools.json` be the central config file, or should tool profiles live in existing `models.json`/PrjXP config?

2. Should a tool-enabled document fail if selected model does not support tools, or silently fall back to normal `chat(String)`?

   Recommendation: fail clearly. Silent fallback would produce lower-quality documentation without making the missing capability obvious.

3. Should tool traces be enabled by default for tool-enabled documents?

   Recommendation: default `false`, but make it easy to enable.

4. Should MCP servers be long-lived across all document generations in one DocPipe run, or started per document?

   Recommendation: start once per DocPipe run/profile where possible, but make lifecycle explicit and safe. First implementation can start per document if simpler.

5. Should allowed tools be listed explicitly, or should a profile allow every tool exposed by a referenced MCP server?

   Recommendation: explicit allowlist for first implementation.

---

## Recommended Next Step

Before implementing, run a small spike against the currently available LangChain4j version to verify the exact API needed for:

- tool specifications
- tool-call model responses
- feeding tool results back into the conversation
- whether MCP can be adapted directly or needs a custom bridge

After the spike, update this concept into an implementation plan with concrete classes, tests, and exact code paths.
