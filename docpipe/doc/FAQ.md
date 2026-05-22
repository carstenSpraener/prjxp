# Doc|Pipe Developer FAQ

This FAQ provides technical insights into the architecture, extension points, and core mechanics of **Doc|Pipe**. It is intended for software engineers and architects integrating or extending the system.

## What is Doc|Pipe and what problem does it solve?

**Doc|Pipe** is a pipeline-driven documentation engine designed to automate the generation of technical documentation using Large Language Models (LLMs). It is specifically built to address the "documentation debt" often found in legacy systems or rapidly evolving microservices.

Instead of manual authoring, Doc|Pipe scans your project structure for configuration markers and generates context-aware documentation (like READMEs, architecture assessments, or data models) by feeding project artifacts directly into LLM prompts.

### Key Problems Solved:
*   **Outdated Documentation:** By tying documentation generation to the actual source code and project state.
*   **Onboarding Friction:** Automatically generating system overviews and component descriptions for new developers.
*   **Legacy Analysis:** Using LLMs to "dump" and analyze source code to create high-level architectural summaries.

---

## How does the template resolution mechanism work?

Doc|Pipe uses a context-sensitive resolution process powered by **Handlebars**. The goal is to transform a high-level prompt template into a "sharp," data-driven final prompt.

1.  **Discovery:** The `JobCreationService` identifies directories containing a `.dp` folder.
2.  **Loading:** It reads the `prompt` file defined in `documents.json`.
3.  **Resolution:** The `PromptResolvingService` processes the template. It injects project-specific context using `TemplateResolver` implementations.
4.  **Execution:** Helpers like `{{java-src-dump "src/main/java"}}` or `{{#groovy}}...{{/groovy}}` are executed to pull real-time data from the working directory into the prompt.

### Example Template Syntax:
```handlebars
Analyze the following Java classes and create a technical summary:

{{java-src-dump "src/main/java/de/example/api"}}

Focus on the interaction between the controllers and services.
```

---

## How can developers extend Doc|Pipe?

Doc|Pipe is designed with a modular architecture, allowing developers to extend both the prompt logic and the LLM integration.

### 1. Custom Template Resolvers
To add new capabilities to the prompt engine (e.g., querying a database, reading specific file types), implement the `TemplateResolver` interface and register it as a Spring `@Component`.

```java
@Component
public class MyCustomResolver implements TemplateResolver {
    @Override
    public String getID() {
        return "my-helper";
    }

    @Override
    public String resolve(File baseDir, Object context, Options options) throws Exception {
        String param = options.param(0).toString();
        // Custom logic to return data into the prompt
        return "Data processed from " + param;
    }
}
```

### 2. Custom Chat Models
If you need to support a proprietary LLM provider not covered by the default suppliers (Ollama, Gemini, OpenAI), implement the `CustomChatModel` interface.

```java
@Component
public class MyPrivateLLMSupplier implements ChatModelSupplier {
    @Override
    public boolean canProvide(DPModelConfig cfg) {
        return "my-private-llm".equals(cfg.getServerType());
    }

    @Override
    public ChatModel provide(DPModelConfig cfg) {
        return MyPrivateLLMClient.builder()
                .endpoint(cfg.getModelProviderURL())
                .build();
    }
}
```

---

## What are the key benefits for team onboarding and system assessments?

Doc|Pipe enables "zero-time" documentation, which is critical during the initial phases of a project or when a new member joins a team.

*   **Automated Architecture Assessments:** By using the `SourceDumpResolver`, Doc|Pipe can feed entire packages into an LLM to generate ADRs (Architecture Decision Records) or identify architectural violations.
*   **Dynamic READMEs:** Generate README files that stay in sync with the actual code structure, exported API endpoints, or database schemas.
*   **Contextual Intelligence:** Because the `GroovyResolver` has access to the `ApplicationContext`, you can write scripts that inspect the running environment or build-time metadata to enrich the documentation.

---

## How does Doc|Pipe manage LLM configurations?

Configurations are decoupled from the code using a `models.json` file, typically located in the `.dp` directory. This allows different projects or sub-modules to use different LLMs (e.g., a local Ollama instance for sensitive code and Gemini for general summaries).

### Example `models.json`:
```json
[
  {
    "stereotype": "architect",
    "modelName": "gemini-1.5-pro",
    "serverType": "gemini",
    "temperature": 0.1,
    "timeOutSeconds": 120
  },
  {
    "stereotype": "coder",
    "modelName": "codellama",
    "serverType": "ollama",
    "modelProviderURL": "http://localhost:11434"
  }
]
```

The `LLMService` uses the `stereotype` defined in `documents.json` to select the correct model configuration at runtime.

---

## Does Doc|Pipe regenerate documentation on every run?

No. Doc|Pipe includes a `ContentUpdateRequiredController` that implements a hashing mechanism to optimize LLM usage and reduce costs/latency.

*   **Hashing:** It calculates a SHA-256 hash of the fully resolved prompt.
*   **Persistence:** These hashes are stored in `.dp/content-hashes.properties`.
*   **Change Detection:** The system only triggers a call to the LLM if the prompt content has changed or if the output file is missing.

---

## How is the project structured for local execution?

Doc|Pipe is a Spring Boot CLI application. It expects a root directory (via the `-r` flag) and looks for `.dp` configurations recursively.

### Directory Structure Example:
```text
my-project/
├── .dp/
│   ├── models.json        # LLM Provider configs
│   └── global-readme.hbs  # Shared prompt templates
├── module-a/
│   ├── .dp/
│   │   └── documents.json # Defines what to generate
│   └── src/
└── module-b/
    ...
```

To run the CLI:
```bash
java -jar docpipe.jar -r /path/to/your/project
```

_This document was generated with .dp and gemini-3-flash-preview_

