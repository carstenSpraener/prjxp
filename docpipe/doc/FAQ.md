# Doc|Pipe Developer FAQ

This FAQ provides technical insights into the architecture, extensibility, and integration patterns of **Doc|Pipe**. It is intended for software engineers and architects implementing or extending the documentation pipeline.

## What is Doc|Pipe and what problem does it solve?

Doc|Pipe is a pipeline-driven documentation engine designed to automate the generation of technical documentation directly from project artifacts. It is specifically optimized for **legacy systems** where architectural knowledge is often trapped in source code rather than documented in maintainable formats.

By treating documentation as a build artifact, Doc|Pipe solves the "stale documentation" problem. It uses Large Language Models (LLMs) to analyze project structures, source code, and configurations to generate data-driven Markdown files, architecture assessments, and READMEs.

### Core Architectural Components
*   **Job Discovery:** Scans the project for `.dp` configuration directories.
*   **Prompt Resolution:** Compiles dynamic templates using Handlebars and custom resolvers.
*   **LLM Orchestration:** Maps documentation "stereotypes" to specific model configurations (e.g., GPT-4 for analysis, faster models for summaries).
*   **Change Detection:** Uses SHA-256 hashing to ensure LLM calls are only made when the underlying prompt or context changes.

---

## How does the template resolution mechanism work?

Doc|Pipe utilizes a context-sensitive template resolution strategy. The `PromptResolvingService` takes a raw prompt template (usually a Handlebars file) and processes it within the context of a specific working directory.

### Resolution Lifecycle
1.  **Template Loading:** The service locates the template file defined in `documents.json` within the `.dp` folder.
2.  **Context Injection:** It identifies the `baseDir` (the root of the specific documentation job).
3.  **Helper Execution:** Handlebars helpers (implemented as `TemplateResolver` beans) are invoked to inject real-world data, such as source code dumps or external file contents.
4.  **Final Prompt Generation:** A "sharp" prompt is generated, containing the actual technical context required for the LLM to produce accurate results.

### Example Template Syntax
```handlebars
Analyze the following Java classes and create a data model description:

{{java-src-dump "src/main/java/com/example/model" ending="java" scanSubs=true}}

Please output the result in Markdown format.
```

---

## How can developers extend Doc|Pipe?

Extensibility is achieved through the `TemplateResolver` interface. Developers can register custom components to introduce new template identifiers or specialized logic for prompt enrichment.

### Implementing a Custom TemplateResolver
To create a new resolver, implement the `TemplateResolver` interface and register it as a Spring `@Component`.

```java
@Component
public class MyCustomResolver implements TemplateResolver {
    @Override
    public String getID() {
        return "my-custom-tag"; // Used as {{#my-custom-tag}}...{{/my-custom-tag}}
    }

    @Override
    public String resolve(File baseDir, Object context, Options options) throws Exception {
        // Access parameters: options.param(0)
        // Access block content: options.fn.text()
        return "Processed context-sensitive data";
    }
}
```

### Scripted Extensions
Doc|Pipe includes a `GroovyResolver`, allowing developers to write complex logic directly within the prompt template without recompiling the application:

```handlebars
{{#groovy}}
    def files = new File(dir, "src/main/resources").listFiles()
    return "Found ${files.length} resource files."
{{/groovy}}
```

---

## How does Doc|Pipe handle incremental updates?

To minimize LLM tokens and execution time, Doc|Pipe uses the `ContentUpdateRequiredController`. It maintains a persistent hash map of every generated document in `.dp/content-hashes.properties`.

*   **Logic:** Before calling the LLM, Doc|Pipe generates the final prompt and calculates its **SHA-256 hash**.
*   **Comparison:** If the hash matches the stored value for the target `outputFile`, the generation is skipped.
*   **Invalidation:** If the template, the source code included via `src-dump`, or the configuration changes, the hash will differ, triggering a fresh LLM request.

---

## What are the key benefits for team onboarding and system assessments?

Doc|Pipe significantly reduces the "time-to-understanding" for complex or legacy projects:

*   **Zero-Time Assessments:** Automatically generate SWOT analyses or architectural overviews by pointing Doc|Pipe at a legacy codebase.
*   **Living Readmes:** Ensure every module has a README.md that actually reflects the current state of the code, not the state it was in three years ago.
*   **Data Model Visualization:** Use the `java-src-dump` resolver to feed entity classes to an LLM to generate Mermaid.js diagrams or documentation of database schemas.
*   **Standardized Stereotypes:** Define model configurations in `models.json` once, allowing the entire team to use consistent LLM settings (temperature, model version) across different documentation tasks.

### Example `documents.json` configuration:
```json
[
  {
    "outputFile": "ARCHITECTURE.md",
    "stereotype": "architect-expert",
    "prompt": "arch-assessment.hbs",
    "filterList": "noSurroundingCodeBlock"
  }
]
```

_This document was generated with .dp and gemini-3-flash-preview_

