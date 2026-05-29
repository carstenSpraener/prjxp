# Doc|Pipe Developer FAQ

This FAQ provides technical insights into the architecture, extensibility, and operational logic of **Doc|Pipe**. It is intended for software engineers and architects integrating or extending the documentation pipeline.

## What is Doc|Pipe and what problem does it solve?

**Doc|Pipe** is a pipeline-driven documentation engine designed to automate the generation of technical documentation using Large Language Models (LLMs). It is specifically built to address the challenges of documenting legacy systems and rapidly evolving architectures.

Instead of manual writing, Doc|Pipe treats documentation as a build artifact. It scans project structures, extracts relevant context (source code, configurations, file structures), and pipes this data through prompt templates to an LLM.

### Key Problems Solved:
*   **Stale Documentation:** By tying documentation generation to the actual state of the source code, the "documentation lag" is eliminated.
*   **Context Fragmentation:** Doc|Pipe gathers disparate project information (e.g., Java source, SQL schemas, directory structures) into a unified context for the LLM.
*   **Legacy Knowledge Extraction:** It automates the assessment of poorly documented legacy systems by generating summaries and data models directly from the code.

## How does the template resolution mechanism work?

The core of Doc|Pipe's prompt generation is the `PromptResolvingService`. It utilizes the **Handlebars** templating engine to transform raw prompt templates into sharp, data-driven instructions for the LLM.

### The Resolution Workflow:
1.  **Discovery:** The `JobCreationService` identifies directories containing a `.dp` configuration folder.
2.  **Context Mapping:** For every task defined in `documents.json`, the service resolves the working directory and any arguments (`args`) provided.
3.  **Handlebars Processing:** The `PromptResolvingService` compiles the template and executes registered `TemplateResolver` helpers. These helpers inject real-time system data into the prompt.

### Example Template Syntax:
A prompt template can combine static instructions with dynamic system introspection:

```handlebars
Analyze the following Java classes and create a sequence diagram:

{{java-src-dump "src/main/java/com/service" scanSubs=true}}

Focus on the interaction between the Controller and the Repository.
```

## How can developers extend Doc|Pipe?

Doc|Pipe is designed for high extensibility through the `TemplateResolver` interface. Developers can register custom resolvers to introduce new prompt logic or data sources.

### Implementing a Custom TemplateResolver
To create a new helper, implement the `TemplateResolver` interface and register it as a Spring `@Component`.

```java
@Component
public class MyCustomResolver implements TemplateResolver {
    @Override
    public String getID() {
        return "my-helper"; // Used as {{my-helper}} in templates
    }

    @Override
    public String resolve(File baseDir, Object context, Options options) throws Exception {
        // baseDir: The .dp configuration directory
        // options: Access to parameters and hash values from the template
        String param = options.param(0).toString();
        return "Processed data for: " + param;
    }
}
```

### Scripted Extensions via Groovy
For complex logic that doesn't warrant a compiled Java class, Doc|Pipe includes a `GroovyResolver`. This allows developers to write logic directly within the prompt template:

```handlebars
{{#groovy}}
    def dir = new File(dir, "../src/main/resources")
    StringBuilder sb = new StringBuilder()
    dir.eachFileMatch(~/.*\.xml/) { file ->
        sb.append("Config file: ${file.name}\n")
    }
    return sb.toString()
{{/groovy}}
```

## What are the key benefits for team onboarding and system assessments?

Doc|Pipe significantly reduces the "Time-to-Productivity" for new developers and provides immediate clarity during architectural audits.

### Automated System Assessments
*   **Data Model Extraction:** Automatically generate Markdown tables or Mermaid.js Entity-Relationship diagrams by pointing the `SourceDumpResolver` at JPA entities or DDL files.
*   **Architecture Overviews:** Use the `java-src-dump` helper to feed entire package structures to an LLM to generate high-level component descriptions.

### Zero-Time Onboarding
*   **Contextual READMEs:** Generate localized `README.md` files for sub-modules that explain the specific purpose of that directory based on the files it contains.
*   **Dependency Mapping:** Use custom resolvers to parse build files (like `pom.xml` or `build.gradle`) and generate visual dependency graphs or security surface area reports.

## How does Doc|Pipe handle performance and LLM costs?

To avoid redundant API calls and minimize latency, Doc|Pipe implements a hash-based change detection mechanism via the `ContentUpdateRequiredController`.

### Change Detection Logic:
1.  **Prompt Hashing:** Before calling the LLM, Doc|Pipe generates a **SHA-256 hash** of the *fully resolved* prompt.
2.  **State Persistence:** This hash is stored in `.dp/content-hashes.properties` alongside the output file path.
3.  **Conditional Execution:** The LLM is only invoked if the prompt hash has changed or the output file is missing.

```java
// Internal logic snippet
String currentHash = toHash(resolvedPrompt);
String storedHash = readEntry(cct, outputFile);

if (storedHash == null || !storedHash.equals(currentHash)) {
    // Trigger LLM Chat and update hash
}
```

## How are LLM models configured for different tasks?

Doc|Pipe uses "Stereotypes" to map specific documentation tasks to different LLM configurations (e.g., GPT-4 for complex analysis, GPT-3.5 for simple summaries).

Configurations are managed in a `models.json` file:

```json
[
  {
    "stereoType": "architect",
    "provider": "openai",
    "modelName": "gpt-4-turbo"
  },
  {
    "stereoType": "summarizer",
    "provider": "ollama",
    "modelName": "llama3"
  }
]
```

The `LLMService` resolves these at runtime based on the `stereotype` field in the `documents.json` task definition.

_This document was generated with .dp and gemini-3-flash-preview_

