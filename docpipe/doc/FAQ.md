# Doc|Pipe Developer FAQ

This FAQ provides technical insights into the architecture, extension points, and operational mechanics of **Doc|Pipe**. It is intended for software engineers and architects integrating or extending the tool.

## What is Doc|Pipe and what problem does it solve?

**Doc|Pipe** is a pipeline-driven documentation engine designed to automate the generation of technical documentation directly from source code and project artifacts. 

It specifically addresses the "stale documentation" problem in rapidly evolving projects and legacy systems. Instead of manual updates, Doc|Pipe treats documentation as a build artifact. It scans project structures, identifies context via a `.dp` configuration directory, and uses Large Language Models (LLMs) to generate precise, data-driven content such as architecture assessments, data models, and READMEs.

## How does the template resolution mechanism work?

The core of Doc|Pipe's prompt generation is the `PromptResolvingService`. It utilizes **Handlebars** as a templating engine to transform raw prompt templates into final, context-aware instructions for the LLM.

1.  **Context Discovery:** The system locates a `.dp` directory within the project.
2.  **Template Loading:** It reads a template file (defined in `documents.json`).
3.  **Dynamic Processing:** The template is processed context-sensitively. Doc|Pipe registers custom Handlebars helpers (via the `TemplateResolver` interface) that allow the template to pull in external data, execute scripts, or dump source code.
4.  **Final Prompt Generation:** The result is a "sharp" prompt where all variables and helpers are expanded into the actual data the LLM needs to process.

### Example Template Syntax
```handlebars
Analyze the following Java classes and create a component diagram description:

{{java-src-dump "src/main/java/com/example/service"}}

Focus on the interaction between the controllers and the persistence layer.
```

## How can developers extend Doc|Pipe?

Developers can extend the capabilities of the prompt engine by implementing the `TemplateResolver` interface. This allows for the creation of custom template identifiers that can perform complex logic during the prompt resolution phase.

### The TemplateResolver Interface
To create a custom resolver, implement this interface and register it as a Spring `@Component`:

```java
public interface TemplateResolver {
    String getID(); // The Handlebars helper name (e.g., "my-custom-helper")
    String resolve(File baseDir, Object context, Options options) throws Exception;
}
```

### Example: Custom Groovy Resolver
Doc|Pipe includes a `GroovyResolver` that allows executing logic directly within a template:

```java
@Component
public class GroovyResolver implements TemplateResolver {
    @Override
    public String getID() { return "groovy"; }

    @Override
    public String resolve(File baseDir, Object context, Options options) throws Exception {
        String script = options.fn.text();
        // Logic to execute script and return string result...
        return result.toString();
    }
}
```

## How are LLM providers configured?

Doc|Pipe uses a factory pattern (`ChatModelFactory`) and a supplier-based architecture (`ChatModelSupplier`) to support multiple LLM backends.

Supported providers are defined in the `ServerTypes` enum:
*   **Ollama:** For local execution.
*   **Gemini:** Google AI integration.
*   **OpenAI / LM Studio:** Compatible API endpoints.
*   **Custom:** Allows developers to provide their own `ChatModel` implementation.

### Configuration Example (`models.json`)
```json
[
  {
    "stereotype": "architecture-expert",
    "modelName": "gpt-4",
    "serverType": "openapi",
    "temperature": 0.1,
    "timeOutSeconds": 120
  }
]
```

## How does Doc|Pipe handle performance and redundant LLM calls?

To minimize costs and execution time, Doc|Pipe implements a hashing mechanism via the `ContentUpdateRequiredController`. 

Before calling an LLM, the system generates a **SHA-256 hash** of the fully resolved prompt. It compares this hash against a stored value in `.dp/content-hashes.properties`. The LLM is only invoked if:
1.  No previous hash exists for the output file.
2.  The prompt content has changed (e.g., source code was updated, or the template was modified).

## What are the key benefits for team onboarding and system assessments?

Doc|Pipe enables "zero-time" documentation for complex systems:

*   **Architecture Assessments:** By using the `java-src-dump` resolver, the LLM receives the actual implementation details, allowing it to generate factual architectural overviews rather than generic descriptions.
*   **Automated Onboarding:** New developers can run the pipeline to generate up-to-date "Getting Started" guides that reflect the current state of the `main` branch.
*   **Legacy Discovery:** For undocumented legacy systems, Doc|Pipe can crawl the source tree and generate initial data models and service maps, significantly reducing the manual effort of reverse engineering.

## Can I use custom logic to fetch data for prompts?

Yes. Beyond simple file dumping, you can use the `GroovyResolver` to interact with the Spring `ApplicationContext` or perform complex file system operations to gather metadata for your prompt.

```handlebars
{{#groovy}}
// Access the project directory and find all configuration files
def configFiles = new File(dir, "src/main/resources").listFiles()
return "The project uses the following configs: " + configFiles.join(", ")
{{/groovy}}
```

_This document was generated with .dp and gemini-3-flash-preview_

