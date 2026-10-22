## Overview

**Doc|Pipe** is a Spring Boot CLI application that automates the generation of documentation using one or more Large Language Models (LLMs). It discovers `.dp` configuration directories in a project, maps *stereotypes* to model configurations, hashes resolved prompts to avoid redundant generation, and uses Handlebars/Groovy templates for flexible prompts. Typical use cases:

* Create README files, architecture overviews, or FAQs from a single template definition.
* Switch between multiple LLM providers (Gemini, Ollama, LM Studio, OpenAI-compatible, …) to optimise token costs.
* Extend the pipeline with custom Spring beans and Groovy scripts inside Handlebars templates.
* Let the LLM research the codebase autonomously via MCP tools (see [Tool-Enabled LLM](concepts/tool-enabled-llm.md)).

---

## Prerequisites & Setup

| Requirement | Details |
|------------|---------|
| **Java**   | JDK 17 or newer (21 recommended) |
| **Build tool** | Gradle 8.x (the project uses a version catalog) |
| **Configuration** | `application.yaml` with `prjxp.*` properties (projects, chat models, MCP servers) |
| **Secrets** | Optional `.env` file in the working directory (loaded into system properties at startup) |

Doc|Pipe is part of the prjxp multi-module build:

```bash
./gradlew :docpipe:run --args="--project my-project"
```

or build the fat JAR:

```bash
./gradlew :docpipe:shadowJar
java -jar docpipe/build/libs/docpipe-<version>.jar --project my-project
```

> **Note:** the shadow JAR excludes `application.yaml` — provide your own `application.yaml` (or `application.yml`) next to the JAR or in `./config/`.

---

## Quick Start

### 1. Define the project and models (`application.yaml`)

```yaml
prjxp:
  projects:
    - name: my-project
      rootDir: /path/to/my-project

  chatModels:
    - stereoType: documentation
      serverType: gemini
      modelName: gemini-3-flash-preview
      timeoutSecs: 240
    - stereoType: javadoc
      serverType: ollama
      modelName: deepseek-coder-v2:16b
      providerUrl: http://localhost:11434
    - stereoType: faq
      serverType: openapi
      modelName: qwen3.6-31b
      providerUrl: http://192.168.1.228:1234
      apiKey: ${FAQ_API_KEY}
```

If no project is selected, Doc|Pipe falls back to a `cwd` project whose root is the current working directory.

### 2. Create the `.dp` directory

In the project root (or any subdirectory that should be documented):

```
my-project/
└── .dp/
    ├── documents.json
    └── Readme.prompt.txt
```

### 3. Define a document generation job (`.dp/documents.json`)

`documents.json` is a **JSON array** of document definitions:

```json
[
  {
    "stereotype": "documentation",
    "outputFile": "README.md",
    "prompt": "Readme.prompt.txt",
    "ps": "\n\n_This document was generated with Doc|Pipe and gemini-3-flash-preview_"
  }
]
```

### 4. Create the prompt template (`.dp/Readme.prompt.txt`)

```text
Create a Readme as MarkDown to explain the usage of my-project. Explain the CLI application from a user's perspective as easy as possible to understand. Enhance if possible with examples and ASCII diagrams.

## Source Code Context
{{src-dump "src/main/java" ending="java" scanSubs=true}}
```

### 5. Run the generator

```bash
./gradlew :docpipe:run --args="--project my-project"
```

Doc|Pipe then:

* walks the project root and finds every `.dp` directory,
* resolves each prompt template (Handlebars + Groovy),
* hashes the resolved prompt (SHA-256) and skips the LLM call if the hash in `.dp/content-hashes.properties` is unchanged,
* selects the LLM by `stereotype`, applies `filterList`/`ps`, and writes the result to `outputFile`.

---

## Core Concepts / Advanced Usage

### 1. Model Mapping (`prjxp.chatModels[]`)

| Field | Meaning |
|-------|---------|
| `stereoType` | Logical name used by a document definition to pick an LLM configuration. |
| `serverType` | `"ollama"`, `"gemini"`, `"lm-studio"`, `"openapi"`, or `"custom"` — selects the client implementation. |
| `modelName` | Identifier of the model on the target provider. |
| `providerUrl` | Base URL for self-hosted / OpenAI-compatible providers. |
| `apiKey` | API key; `${ENV_VAR}` placeholders are resolved at runtime. |
| `timeoutSecs` | Request timeout in seconds (default 60). |
| `temperature` | Sampling temperature (default 0.0). |
| `prompt` | Optional standing instructions prepended to every conversation. |
| `supportsTools` | Explicitly enable/disable native function calling (`null` = auto-detect). |
| `args` | Supplier-specific client parameters (e.g. Azure endpoint, api-version). |
| `bodyProperties` | Extra request-body fields merged into every chat completion. |

All string values support `${ENV_VAR}` (and `${ENV_VAR:default}`) placeholders, resolved from environment variables and system properties.

**Tip:** use one entry per stereotype; switch stereotypes in `documents.json` to move work between cheap and expensive models without touching code.

### 2. Document Definition (`.dp/documents.json`)

| Property | Description |
|----------|-------------|
| `stereotype` | Must match a `stereoType` from `chatModels[]` — determines which LLM is used. |
| `outputFile` | Path of the generated file, relative to the job root (the directory containing `.dp/`). |
| `outputDir` | Output directory used together with `forEach` (combined with the source file name). |
| `prompt` | Name of the prompt template file inside `.dp/` (Handlebars). |
| `forEach` | Glob pattern (relative to the job root); the entry is cloned once per matched file. |
| `ps` | Optional post-script text appended to the generated output. |
| `filterList` | Comma-separated `ContentFilter` names applied to the LLM response (e.g. `noSurroundingCodeBlock`). |
| `storePrompt` | Optional file name to persist the fully resolved prompt for auditing. |
| `args` | Map passed as the Handlebars context (for `forEach` tasks, `currentFile` is added automatically). |

### 3. Handlebars + Groovy Templates

```handlebars
{{!-- .dp/Readme.prompt.txt --}}
<h1>Welcome to {{args.projectName}}</h1>

{{#groovy}}
    // Bindings: dir (.dp dir), options, applicationContext, dpContext, currentFile
    return "Generated on: " + new Date()
{{/groovy}}
```

* `{{...}}` blocks work like standard Handlebars; the `args` map is the template context.
* `{{#groovy}} … {{/groovy}}` executes an inline Groovy script with access to the Spring `ApplicationContext` — powerful, but untrusted templates can do anything (see [ArchitectureAssessment](ArchitectureAssessment.md), risk H1).
* Built-in helpers: `src-dump`/`java-src-dump`, `src-skeleton`, `URL`, `groovy`, `currentFile`, `mcp-project`/`mcpProject`, `gr` (see [FAQ](FAQ.md) for details).

### 4. Prompt Hashing & Incremental Generation

Doc|Pipe computes a SHA-256 hash of the **resolved prompt** and stores it in `.dp/content-hashes.properties`, keyed by the output file path. If the stored hash matches, the LLM call is skipped and the existing output file is left as-is. The hash file is a flat Java `Properties` file — inspectable and diffable (it is git-ignored by the prjxp `.gitignore`).

### 5. Multi-LLM Mapping for Cost Optimisation

Define multiple stereotypes pointing at cheaper models for bulk tasks (e.g. `documentation` → Gemini flash, `javadoc` → local Ollama). Switch stereotypes in `documents.json` without touching code.

### 6. Tool-Enabled LLMs (MCP)

Configure MCP servers in `application.yaml` and the LLM can research the codebase autonomously while generating a document:

```yaml
prjxp:
  mcp-servers:
    - name: prjxp-search
      type: http
      url: http://localhost:7007/mcp
      defaultProject: my-project
```

`LLMService` decorates every chat with the active MCP tools; use `{{mcp-project project="my-project"}}` in a template to tell the model which project to search. See [Tool-Enabled LLM](concepts/tool-enabled-llm.md) and [EXAMPLE](EXAMPLE.md).

### 7. Extending the Pipeline

* **Custom template resolver** – implement `TemplateResolver` (`getID()`, optional `getAliases()`, `resolve(baseDir, context, options)`) and annotate with `@Component`; it is registered as a Handlebars helper automatically.
* **Content filter** – implement `ContentFilter` (`name()`, `filter(String)`) and reference it in `filterList`.
* **Source skeletonizer** – implement `SourceSkeletonizer` (`supports(File, ending)`, `skeletonize(File)`) for the `src-skeleton` helper.
* **Output sink** – `OutputSinkFactory` (a Spring `@Service`) creates `FileOutputSink`; replace the bean (e.g. with a `@Primary` subclass) for in-memory or remote targets.
* **LLM provider** – add a `ChatModelSupplier` in `prjxp-common` for new `serverType`s; Doc|Pipe itself is unaffected.

---

## Best Practices / Common Pitfalls

| Practice | Reason |
|---------|--------|
| **Keep prompts deterministic** – minor whitespace changes change the hash and trigger a re-run. |
| **Store `application.yaml` under version control** (keep secrets in `.env`) – a missing model entry breaks generation with "No Model found for Stereotype …". |
| **Limit template complexity** – heavy Groovy logic slows rendering; keep business logic in Java and use templates for presentation only. |
| **Prune `content-hashes.properties`** – it lives in `.dp/` next to the sources; stale entries are harmless but the file grows. |
| **Parallelism** – tasks run on a fixed pool (`prjxp.docpipe.maxthreads`, default 5); raise it for large projects, but note that LLM rate limits apply. |
| **Model time-outs** – set `timeoutSecs` generously for large outputs; otherwise the client aborts prematurely. |
| **Secure provider URLs** – when using self-hosted endpoints (Ollama, LM Studio), ensure they are reachable from the build environment. |

---

## Further Reading

* **[FAQ](FAQ.md)** – common questions, built-in resolvers, extension examples.
* **[Configuration](Configuration.md)** – technical walkthrough of the pipeline internals.
* **[ArchitectureAssessment](ArchitectureAssessment.md)** – internal design review, risks and technical debt.
* **[Tool-Enabled LLM](concepts/tool-enabled-llm.md)** – concept and implementation of MCP tool calling.
* **[EXAMPLE](EXAMPLE.md)** – end-to-end example: MCP-powered architecture documentation.
