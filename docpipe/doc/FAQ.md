# Doc|Pipe Developer FAQ

This FAQ provides technical insights into the architecture, extension points, and operational mechanics of **Doc|Pipe**.

## What is Doc|Pipe and what problem does it solve?

**Doc|Pipe** is a pipeline-driven documentation engine designed to automate the creation and maintenance of technical documentation. It specifically addresses the "stale documentation" problem in software engineering, which is often most acute in legacy systems where architectural knowledge has eroded.

By treating documentation as a build artifact, Doc|Pipe allows developers to:
*   **Automate Context Extraction:** Automatically gather source code, directory structures, and external metadata.
*   **Pipeline Documentation:** Use LLMs to transform raw project context into structured Markdown, architecture assessments, or README files.
*   **Ensure Idempotency:** Use a hash-based change detection mechanism to only regenerate documentation when the underlying prompt or context has changed.

## How does the template resolution mechanism work?

Doc|Pipe utilizes **Handlebars** as its primary templating engine for prompts. The resolution process follows a specific lifecycle to transform a raw template into a data-driven prompt:

1.  **Context Discovery:** The system identifies a `.dp` directory within the project structure.
2.  **Template Loading:** It reads the prompt template specified in the `documents.json` configuration.
3.  **Helper Execution:** The `PromptResolvingService` scans the template for custom helpers (e.g., `{{#groovy}}` or `{{java-src-dump}}`).
4.  **Data Injection:** These helpers are executed with access to the current working directory (`baseDir`) and the Spring `ApplicationContext`.
5.  **Final Prompt Generation:** The resolved strings are concatenated into a final, sharp prompt sent to the configured LLM.

### Example: Context-Sensitive Source Dump
A template can use the `java-src-dump` resolver to inject actual source code into the prompt:

```handlebars
Analyze the following Java classes and describe the design patterns used:

{{java-src-dump "src/main/java/de/example/service"}}

Please provide the output in Markdown format.
```

## How can developers extend Doc|Pipe?

Developers can extend Doc|Pipe by implementing the `TemplateResolver` interface. This allows for the creation of custom identifiers that can be used within Handlebars templates to fetch data from proprietary APIs, databases, or specific file formats.

### 1. Implement the TemplateResolver
Create a new component that defines a unique ID and the logic to resolve the content.

```java
@Component
public class MyCustomResolver implements TemplateResolver {
    @Override
    public String getID() {
        return "my-data-fetcher";
    }

    @Override
    public String resolve(File baseDir, Object context, Options options) throws Exception {
        // Custom logic: e.g., reading a specific config file
        String param = options.param(0).toString();
        return "Resolved data for: " + param;
    }
}
```

### 2. Use in Templates
Once registered as a Spring Bean, the resolver is automatically available in your prompt templates:

```handlebars
Here is the custom metadata:
{{my-data-fetcher "some-parameter"}}
```

## What are the key benefits for team onboarding and system assessments?

Doc|Pipe significantly reduces the "Time-to-Productivity" for new engineers and provides architects with instant visibility into legacy codebases:

*   **Zero-Time Onboarding:** Generate comprehensive `README.md` files and "Getting Started" guides that are always in sync with the actual project structure.
*   **Automated Architecture Assessments:** By piping source code dumps into LLMs with specific architectural prompts, teams can generate "as-is" documentation, identifying technical debt or architectural violations automatically.
*   **Data Model Visualization:** Use resolvers to extract schema definitions or DTO structures to generate up-to-date data dictionaries.

## How does Doc|Pipe handle different LLM providers?

The system uses a factory pattern (`ChatModelFactory`) and a supplier interface (`ChatModelSupplier`) to abstract the underlying LLM provider. It currently supports:

*   **Ollama:** For local execution.
*   **Gemini:** Via Google AI.
*   **OpenAI / LM Studio:** Via the OpenAI-compatible API.
*   **Custom:** By providing a specific implementation class in the configuration.

### Configuration Example (`models.json`)
```json
[
  {
    "stereotype": "architect",
    "modelName": "gpt-4",
    "serverType": "openapi",
    "modelProviderURL": "https://api.openai.com/v1",
    "temperature": 0.1
  }
]
```

## How is documentation regeneration optimized?

To avoid unnecessary LLM API calls and costs, Doc|Pipe implements a `ContentUpdateRequiredController`. 

*   **Hashing:** It generates a SHA-256 hash of the fully resolved prompt.
*   **Persistence:** This hash is stored in `.dp/content-hashes.properties`.
*   **Comparison:** Before calling the LLM, the system compares the current prompt hash with the stored hash. If they match, the generation is skipped.

```java
private boolean updateRequired(ContentCreationTask cct, String prompt, String outputFile) {
    String currentHash = toHash(prompt);
    String storedHash = readEntry(cct, outputFile);
    return storedHash == null || !storedHash.equals(currentHash);
}
```

## Can I use scripts within my prompt templates?

Yes. Doc|Pipe includes a `GroovyResolver` that allows you to execute logic directly within the template. This is useful for complex filtering or conditional context gathering.

### Example: Groovy Scripting in Prompt
```handlebars
{{#groovy}}
StringBuilder sb = new StringBuilder();
sb.append("Project Directory: ").append(dir.getAbsolutePath());
// Access Spring beans if necessary
// var myService = applicationContext.getBean(MyService.class);
return sb.toString();
{{/groovy}}
```

_This document was generated with .dp and gemini-3-flash-preview_

