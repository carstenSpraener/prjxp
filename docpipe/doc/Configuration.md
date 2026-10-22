# Doc|Pipe Technical Documentation

## Overview
Doc|Pipe is a document generation pipeline that automates the creation of content by scanning a project directory for `.dp` configuration directories, mapping document definitions to LLM configurations, and executing content creation tasks in parallel.

## Architecture & Workflow

### 1. Initialization and Global Configuration
The process begins in `DocPipeCliApp`:
- It loads a `.env` file from the working directory (dotenv-java) and exposes every variable as a system property.
- Spring Boot binds all `prjxp.*` properties from `application.yaml` to `PrjXPConfig` (prjxp-common): the active project (`prjxp.activeProject`, default `cwd`), the project list (`prjxp.projects[]`), the chat model references (`prjxp.chatModels[]`), and the MCP server references (`prjxp.mcp-servers[]`).
- `McpClientManager` (prjxp-common) connects all configured MCP servers at startup (`stdio`, `http`, or `groovy` tool scripts) and registers their tools in the `ToolRegistry`.

> **Note:** an earlier version loaded per-directory `.dp/models.json` files via `ModelConfigLoader`. That code path is no longer used by the pipeline — model configuration lives exclusively in `application.yaml` (`prjxp.chatModels[]`). `ModelConfigLoader` and the `models.json` helpers in `DotDPFilesService` remain as unused legacy.

### 2. Job Discovery (`JobCreationService`)
The system recursively scans the active project's root directory to identify "Jobs":
- **Directory Scanning**: it walks the file tree looking for directories that contain a `.dp` folder (`DotDPFilesService.hasDocPipeDir`).
- **Content Definition**: for each such directory it parses `.dp/documents.json` as a JSON array of `DPContentCreation` objects.
- **`forEach` Expansion**: entries with a `forEach` glob are cloned once per matched file; the clone receives `args.currentFile` (relative path) and a computed `outputFile`/`storePrompt` (output dir + source base name + configured suffix).
- **Fallback**: a malformed `documents.json` yields `DPJob.EMPTY_JOB` (no content creations) so the pipeline keeps running.

### 3. Task Execution
`DocPipeRunner` flattens the jobs:
- Each `DPContentCreation` within a `DPJob` is wrapped into a `ContentCreationTask`.
- **Parallel Processing**: the runner uses a `FixedThreadPool` (`prjxp.docpipe.maxthreads`, default 5) to execute tasks concurrently.
- **Content Generation** (`ContentCreationService`), per task:
  1. `PromptResolvingService` reads the template from `.dp/`, registers every `TemplateResolver` bean as a Handlebars helper, and renders the prompt with the task's `args` as context (optionally persisting it via `storePrompt`).
  2. `ContentUpdateRequiredController` hashes the resolved prompt (SHA-256) and compares it with `.dp/content-hashes.properties` (keyed by output file). If unchanged, the LLM call is skipped.
  3. `LLMService` resolves the model by `stereotype` via `KIChatProvider` (prjxp-common), decorates it with `McpClientManager` when MCP clients are active, and sends the prompt.
  4. Configured `ContentFilter`s (comma-separated `filterList`) clean the response; `ps` is appended.
  5. The result is written through an `OutputSink` (`FileOutputSink` by default).

### 4. Error Handling and Logging
- **Logging**: `PxLogService` (prjxp-common) captures all messages; individual task failures are logged and recorded without halting the pipeline.
- **CLI termination**: if any message at level `SEVERE` or higher was recorded, `DocPipeRunner.run()` logs a summary and exits with code `1`.
- **Hub mode**: `runForProject(ProjectDefinition)` never calls `System.exit`; it awaits task completion (1 h timeout), records outputs/failures in `DocPipeRunResult`, always writes a manifest (`.prjxp-data/docpipe/manifest.json` via `DocPipeManifestWriter`), and throws `DocPipeRunException` on failure.

## Data Models

### `PrjXPChatModelReference` (prjxp-common)
Defines one LLM backend configuration:
- **Stereotype**: `stereoType` — the identifier used to link a content request to this model.
- **Model details**: `serverType` (`ollama`, `gemini`, `lm-studio`, `openapi`, `custom`), `modelName`, `providerUrl`, `apiKey`.
- **Parameters**: `timeoutSecs` (default 60), `temperature` (default 0.0), optional standing `prompt`, `supportsTools`, supplier `args`, request `bodyProperties`.
- `${ENV}` placeholders are resolved at runtime by `EnvPlaceholderResolver`.

### `DPContentCreation`
Defines a single output requirement:
- `forEach` – optional glob for per-file expansion.
- `outputFile` / `outputDir` – destination path(s), relative to the job root.
- `stereotype` – reference to a `PrjXPChatModelReference`.
- `prompt` – template file name inside `.dp/`.
- `ps` – post-script appended to the generated content.
- `filterList` – comma-separated `ContentFilter` names.
- `storePrompt` – optional file for the resolved prompt.
- `args` – Handlebars context map.

### `DPJob` / `ContentCreationTask`
A `DPJob` pairs a job root directory with the shared `PrjXPConfig` and its list of `DPContentCreation`s. A `ContentCreationTask` pairs one `DPJob` with one `DPContentCreation` and is the unit of work submitted to the thread pool.
