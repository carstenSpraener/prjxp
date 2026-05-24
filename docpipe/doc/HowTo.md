## Overview

**Doc|Pipe** is a Java library that automates the generation of documentation using one or more Large Language Models (LLMs). It maps *stereotypes* to model configurations, hashes prompts to avoid redundant generation, and uses Handlebars/Groovy templates for flexible output. Typical use‑cases:

* Create README files, API docs, or FAQs from a single template definition.
* Switch between multiple LLM providers (Gemini, Ollama, OpenAI, etc.) to optimise token costs.
* Extend the pipeline with custom Spring beans and Groovy scripts inside Handlebars templates.

---

## Prerequisites & Setup

| Requirement | Details |
|------------|---------|
| **Java**   | JDK 17 or newer |
| **Build tool** | Maven 3.8+ or Gradle 7+ |
| **Dependencies** | Add the `docpipe` artifact to your project (replace version with the latest release) |
| **Optional** | Spring Boot 3.x if you want DI support for Groovy scripts inside templates |

### Maven

```xml
<dependency>
    <groupId>io.github.docpipe</groupId>
    <artifactId>docpipe-core</artifactId>
    <version>1.3.0</version>
</dependency>
```

### Gradle (Groovy DSL)

```groovy
implementation 'io.github.docpipe:docpipe-core:1.3.0'
```

### Configuration files

* `models.json` – describes available LLM endpoints and per‑stereotype parameters.
* `documents.json` – maps a *document definition* (output file, prompt template) to a stereotype.

Place both files in `src/main/resources/docpipe/` or any location you pass to the API via `DocPipeConfig`.

---

## Quick Start

1. **Create a model configuration** (`models.json`)

```json
[
  {
    "stereotype": "documentation",
    "serverType": "gemini",
    "modelName": "gemini-3-flash-preview",
    "timeOutSeconds": "240"
  },
  {
    "stereotype": "javadoc",
    "modelName": "deepseek-coder-v2:16b",
    "modelProviderURL": "http://localhost:11434",
    "serverType": "ollama"
  },
  {
    "stereotype": "faq",
    "modelName": "qwen3.6-31b",
    "modelProviderURL": "http://192.168.1.228:1234",
    "serverType": "openapi"
  }
]
```

2. **Define a document generation job** (`documents.json`)

```json
[
  {
    "stereotype": "detailed",
    "outputFile": "README.md",
    "prompt": "Readme.prompt.txt",
    "ps": "\n\n_This document was generated with Doc|Pipe and gemini-3-flash-preview_"
  }
]
```

3. **Create the prompt template** (`Readme.prompt.txt`)

```text
Create a Readme as MarkDown to explain the usage of Doc|Pipe. Explain the cli application from a users perspective as easy as possible to understand. Enhance if possible with examples and ASCII-Diagrams.

IMPORTANT: As a title image use the image that will be provided under the path doc/images/docpipe.png.
```

4. **Run the generator**

```java
import io.github.docpipe.DocPipe;
import java.nio.file.Path;

public class GenerateDocs {
    public static void main(String[] args) throws Exception {
        Path models = Path.of("src/main/resources/docpipe/models.json");
        Path docs   = Path.of("src/main/resources/docpipe/documents.json");
        DocPipe job = new DocPipe(models, docs);
        job.execute(); // Generates README.md in the current working directory
    }
}
```

The library automatically:

* Hashes the prompt content; if a file with the same hash already exists, generation is skipped.
* Selects the appropriate LLM based on `stereotype`.
* Renders the Handlebars template, allowing Groovy snippets to access Spring beans.

---

## Core Concepts / Advanced Usage

### 1. Model Mapping (`models.json`)

| Field                | Meaning |
|----------------------|---------|
| `stereotype`        | Logical name used by a document definition to pick an LLM configuration. |
| `serverType`        | `"gemini"`, `"ollama"`, or `"openapi"` – determines the client implementation. |
| `modelName`         | Identifier of the model on the target provider (e.g., `"gemini-3-flash-preview"`). |
| `modelProviderURL`  | Optional base URL for self‑hosted providers (required for Ollama / OpenAI local). |
| `timeOutSeconds`    | Request timeout – useful when the model response may be large. |

**Tip:** Use a single entry per stereotype; you can later add extra fields (e.g., temperature, maxTokens) as the library evolves.

### 2. Document Definition (`documents.json`)

| Property   | Description |
|-----------|-------------|
| `stereotype` | Must match a key from *models.json* – determines which LLM to use. |
| `outputFile` | Relative or absolute path of the generated file. |
| `prompt`     | Path to a text file containing the Handlebars template or raw prompt. |
| `ps`        | Optional post‑script text appended to the generated output (useful for licensing notes). |

### 3. Handlebars + Groovy Templates

```handlebars
{{!-- docpipe/template/Readme.handlebars --}}
<h1>Welcome to {{project.name}}</h1>

{{#*inline "toc"}}
## Table of Contents
- [Features]({{link "features"}})
{{/inline}}

{{> toc }}

{{! Groovy script to expose Spring bean }}
<#assign version = @SpringContext.getBean("appVersion")>
Current version: **{{version}}**

```

* The `{{#...}}` blocks work exactly like standard Handlebars.
* Inside a template you can embed Groovy code using `<#...>` tags, giving full access to the Spring context.
* The generated prompt is then sent to the selected LLM; its response populates the output file.

### 4. Prompt Hashing & Incremental Generation

The library computes an SHA‑256 hash of the *prompt text + model identifier*. If a file with this hash already exists in `$HOME/.docpipe/cache`, the generation step is skipped, saving tokens and latency.

```java
String hash = DocPipeUtil.sha256(promptContent + modelId);
if (Cache.exists(hash)) {
    log.info("Cached result found – skipping LLM call.");
}
```

### 5. Multi‑LLM Mapping for Cost Optimisation

Define multiple stereotypes that point to cheaper models for bulk tasks (e.g., `documentation` → Gemini flash, `javadoc` → Ollama local). Switch stereotypes in `documents.json` without touching code.

```json
{
  "stereotype": "javadoc",
  "outputFile": "API.md",
  "prompt": "Javadoc.prompt.txt"
}
```

### 6. Extending the Pipeline

* **Custom Template Loader** – implement `TemplateResolver` and register via Spring configuration.
* **Additional LLM Client** – extend `AbstractLlmClient` to plug in new providers (e.g., Anthropic).

```java
@Bean
public LlmClient customClient() {
    return new MySpecialLlmClient("http://my-host:8080");
}
```

---

## Best Practices / Common Pitfalls

| Practice | Reason |
|---------|--------|
| **Keep prompts deterministic** – minor whitespace changes change the hash and trigger a re‑run. |
| **Store `models.json` under version control** – accidental removal of a provider URL will break generation. |
| **Limit template complexity** – heavy Groovy logic can slow rendering; keep business logic in Java and use templates for presentation only. |
| **Cache directory cleanup** – the default cache lives in `${user.home}/.docpipe`. Periodically prune old entries if disk space is a concern. |
| **Thread safety** – `DocPipe` instances are **not** thread‑safe by default. Create a new instance per generation job or synchronize external calls. |
| **Model time‑outs** – set `timeOutSeconds` generously for large outputs; otherwise the client may abort prematurely. |
| **Secure provider URLs** – when using self‑hosted endpoints (Ollama, OpenAI), ensure they are reachable from the build environment. |

---

## Further Reading

* **[HowTo – detailed developer guide](/doc/HowTo.md)**
* **[FAQ – common questions](/doc/FAQ.md)**
* **[ArchitectureAssessment – internal design review](/doc/ArchitectureAssessment.md)**

---

*Fun fact:* Doc|Pipe automatically generates its own architectural assessment under `doc/ArchitectureAssessment.md`. See the link above for a deep dive.

_This document was generated with .dp and gemini-3-flash-preview_

