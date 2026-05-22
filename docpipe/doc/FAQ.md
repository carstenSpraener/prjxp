# Doc|Pipe Developer FAQ

This FAQ provides technical insights into the architecture, extension points, and operational mechanics of **Doc|Pipe**. It is intended for software engineers and architects integrating or extending the tool.

## What is Doc|Pipe and what problem does it solve?

**Doc|Pipe** is a pipeline-driven documentation engine designed to automate the generation of technical documentation using Large Language Models (LLMs). It specifically addresses the "documentation rot" common in fast-moving projects and the high cognitive load required to document legacy systems.

Unlike static documentation generators, Doc|Pipe:
*   **Automates Context Gathering:** It scans project structures to extract relevant context (source code, configuration, schemas).
*   **Orchestrates LLM Workflows:** It manages prompts, model configurations, and output sinks.
*   **Ensures Idempotency:** It uses a SHA-256 hashing mechanism to track prompt changes, ensuring LLM calls are only made when the underlying template or context evolves.

## How does the template resolution mechanism work?

The core of Doc|Pipe's flexibility is the `PromptResolvingService`. It transforms a raw prompt template into a data-driven final prompt by executing context-sensitive helpers.

1.  **Discovery:** The system scans for `.dp` directories.
2.  **Contextualization:** It sets the working directory to the location of the `.dp` folder.
3.  **Handlebars Processing:** It uses Handlebars as the templating engine, augmented with custom `TemplateResolver` implementations.
4.  **Data Injection:** Helpers like `java-src-dump` or `groovy` are executed to pull real-time data from the project into the prompt.

### Example Template
A prompt template (e.g., `architecture-prompt.hb`) might look like this:

```handlebars
Analyze the following Java classes and describe the component interactions:

{{java-src-dump "src/main/java/com/example/service"}}

Please provide the output in Markdown format.
```

## How can developers extend Doc|Pipe?

Developers can extend Doc|Pipe by implementing the `TemplateResolver` interface. This allows for the creation of custom identifiers that can be used within prompt templates to fetch specific data or perform complex logic.

### Implementing a Custom TemplateResolver
To create a new resolver, implement the interface and register it as a Spring `@Component`.

```java
@Component
public class MyCustomResolver implements TemplateResolver {
    @Override
    public String getID() {
        return "my-helper"; // Used as {{my-helper "param"}}
    }

    @Override
    public String resolve(File baseDir, Object context, Options options) throws Exception {
        String param = options.param(0).toString();
        // Custom logic to fetch data based on the project structure
        return "Resolved data for " + param;
    }
}
```

### Extending LLM Support
If you need to support a proprietary or non-standard LLM provider, implement the `ChatModelSupplier` interface:

```java
@Component
public class CustomLLMProvider implements ChatModelSupplier {
    @Override
    public boolean canProvide(DPModelConfig cfg) {
        return "my-provider".equals(cfg.getServerType());
    }

    @Override
    public ChatModel provide(DPModelConfig cfg) {
        return MyCustomChatModel.builder()
                .apiKey(cfg.getArgs().get("apiKey"))
                .build();
    }
}
```

## How does Doc|Pipe handle change detection?

To minimize API costs and execution time, Doc|Pipe includes a `ContentUpdateRequiredController`. Before invoking an LLM, the system generates a SHA-256 hash of the fully resolved prompt.

*   **Hash Storage:** Hashes are stored in `.dp/content-hashes.properties`.
*   **Comparison:** If the hash of the new prompt matches the stored hash for a specific output file, the generation is skipped.
*   **Force Update:** Modifying the template or the source code included via a resolver changes the hash, triggering a re-generation.

## What are the key benefits for team onboarding and system assessments?

Doc|Pipe enables "Zero-Time Documentation" by deriving insights directly from the source of truth (the code).

*   **Architecture Assessments:** By using the `java-src-dump` resolver, developers can feed entire packages into an LLM to generate high-level architectural overviews or identify design pattern violations.
*   **Automated Readmes:** Generate `README.md` files for sub-modules that stay in sync with the actual implementation.
*   **Onboarding Guides:** Create data models and service maps automatically, allowing new developers to understand legacy modules without manual documentation hand-offs.

## How is the system configured?

Doc|Pipe uses a hierarchical configuration approach:
1.  **Global Models:** Defined in a global `models.json`.
2.  **Project-Specific Jobs:** Defined in `.dp/documents.json` within the project sub-directories.
3.  **Environment Variables:** Sensitive data like API keys are resolved via `EnvResolver` from `.env` files or system environment variables.

### Example `documents.json`
```json
[
  {
    "outputFile": "docs/architecture.md",
    "stereotype": "architect",
    "prompt": "prompts/arch-analysis.hb"
  }
]
```

### Example `models.json`
```json
[
  {
    "stereotype": "architect",
    "modelName": "gpt-4",
    "serverType": "openapi",
    "temperature": 0.1
  }
]
```

_This document was generated with .dp and gemini-3-flash-preview_

