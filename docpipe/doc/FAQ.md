# Doc|Pipe Developer FAQ

This FAQ provides technical insights into the architecture, extension points, and operational mechanics of **Doc|Pipe**. It is intended for software engineers and architects integrating or extending the system.

## What is Doc|Pipe and what problem does it solve?

**Doc|Pipe** is a pipeline-oriented documentation engine designed to automate the generation of technical documentation using Large Language Models (LLMs). 

In many software projects—particularly legacy systems—documentation is either non-existent, outdated, or disconnected from the actual source code. Doc|Pipe solves this by:
*   **Context-Aware Generation:** It scans project structures to identify documentation "jobs" defined in local `.dp` directories.
*   **Source-Truth Integration:** It extracts actual code structures (e.g., Java source dumps) and feeds them into LLM prompts.
*   **Incremental Updates:** It uses a SHA-256 hashing mechanism to ensure LLM calls are only made when the underlying prompt or context has changed, preventing unnecessary API costs and latency.

## How does the template resolution mechanism work?

The core of Doc|Pipe's prompt engineering is the `PromptResolvingService`. It transforms raw templates into high-fidelity LLM prompts by resolving context-sensitive data.

1.  **Template Discovery:** The system identifies a `.dp/documents.json` file which points to a prompt template.
2.  **Handlebars Integration:** Templates are processed using the Handlebars templating engine.
3.  **Context Injection:** The `TemplateResolver` interface allows the engine to inject dynamic content based on the project's working directory.
4.  **Final Compilation:** The engine executes helpers (like Groovy scripts or source code crawlers) to produce the final string sent to the LLM.

### Example: Prompt Template with Source Injection
```handlebars
Analyze the following Java classes and generate a Mermaid class diagram:

{{java-src-dump "src/main/java/com/example/model"}}

Focus on the relationships between entities.
```

## How can developers extend Doc|Pipe?

Doc|Pipe is built on a modular Spring Boot architecture, allowing developers to extend it in two primary ways:

### 1. Custom Template Resolvers
To add new logic for prompt generation (e.g., querying a database or parsing a specific file format), implement the `TemplateResolver` interface and register it as a Spring `@Component`.

```java
@Component
public class MyCustomResolver implements TemplateResolver {
    @Override
    public String getID() {
        return "my-helper";
    }

    @Override
    public String resolve(File baseDir, Object context, Options options) {
        // Custom logic to return data into the prompt
        return "Dynamic data from " + baseDir.getName();
    }
}
```

### 2. Custom Chat Model Suppliers
If you need to integrate an LLM provider not supported out-of-the-box, implement the `ChatModelSupplier` or `CustomChatModel` interface. This allows the `ChatModelFactory` to resolve your custom implementation based on the `serverType` defined in `models.json`.

```java
@Component
public class MyPrivateLLMSupplier implements ChatModelSupplier {
    @Override
    public boolean canProvide(DPModelConfig cfg) {
        return "my-private-llm".equals(cfg.getServerType());
    }

    @Override
    public ChatModel provide(DPModelConfig cfg) {
        return MyPrivateChatModel.builder()
                .url(cfg.getModelProviderURL())
                .build();
    }
}
```

## How does Doc|Pipe handle incremental builds?

To avoid redundant LLM processing, Doc|Pipe implements a `ContentUpdateRequiredController`. 

*   **Hashing:** For every task, it generates a SHA-256 hash of the *fully resolved* prompt.
*   **Persistence:** These hashes are stored in `.dp/content-hashes.properties`.
*   **Comparison:** Before execution, the system compares the current prompt hash against the stored hash. If they match, the LLM call is skipped.

## What are the key benefits for team onboarding and system assessments?

Doc|Pipe is particularly effective for rapid system discovery:

*   **Zero-Time Assessments:** By using the `SourceDumpResolver`, architects can generate comprehensive system overviews, security assessments, or data models from a codebase they have never seen before.
*   **Automated READMEs:** Teams can maintain high-quality README files in sub-modules that automatically update as the code evolves.
*   **Scriptable Context:** Using the `GroovyResolver`, developers can write logic to filter specific parts of the codebase to be included in the prompt, ensuring the LLM receives only the most relevant context.

### Example: Groovy-based Context Filtering
```handlebars
{{#groovy}}
// Logic to only include classes annotated with @Entity
def files = new File(dir, "src/main/java").collectMany { it.listFiles() }
return files.findAll { it.text.contains("@Entity") }.join("\n")
{{/groovy}}
```

## How is the configuration structured?

Doc|Pipe looks for a `.dp` directory at the project root or sub-module levels.

*   **`models.json`**: Defines the LLM configurations (model name, temperature, API keys/endpoints).
*   **`documents.json`**: Defines the documentation artifacts to be created, their output paths, and the prompt templates to use.

```json
[
  {
    "outputFile": "docs/architecture.md",
    "stereotype": "architect",
    "prompt": "prompts/arch-assessment.hbs"
  }
]
```

_This document was generated with .dp and gemini-3-flash-preview_

