# Doc|Pipe User Guide

Doc|Pipe is a powerful Java-based CLI tool designed to automate the generation of documentation and software artifacts using Large Language Models (LLMs). By mapping specific documentation tasks to specialized LLM providers, Doc|Pipe allows developers to orchestrate complex documentation workflows while maintaining cost-efficiency and consistency.

## Overview

Doc|Pipe operates on a "Job" basis. It scans your project for configuration directories, resolves prompt templates using Handlebars and Groovy, and interacts with various LLM backends (Ollama, Gemini, OpenAI) to produce the final output.

**Key Features:**
*   **Prompt Hashing**: Prevents unnecessary API calls by only regenerating documents when the prompt content changes.
*   **Stereotype Mapping**: Assigns different LLMs to different tasks (e.g., using a fast model for Javadoc and a reasoning model for architecture assessments).
*   **Dynamic Templates**: Uses Handlebars as the root structure with the ability to execute Groovy scripts and dump source code directly into prompts.
*   **Extensibility**: Easily add custom LLM providers or template resolvers.

## Prerequisites & Setup

### System Requirements
*   **Java 17** or higher.
*   **Access to an LLM provider**: Local (Ollama, LM Studio) or Cloud (Google Gemini, OpenAI).

### Configuration
Doc|Pipe uses a `.env` file or system properties for sensitive credentials. Create a `.env` file in your execution directory:

```properties
chat.gemini.apikey=your_google_gemini_api_key
chat.openapi.api-key=your_openai_api_key
prjxp.docpipe.maxthreads=5
```

### Project Structure
Doc|Pipe looks for a `.dp` folder within your project directories. This folder must contain:
1.  `models.json`: Defines available LLM providers.
2.  `documents.json`: Defines the documents to be generated.
3.  Prompt files: Text files containing the instructions for the LLM.

## Quick Start

1.  **Initialize the configuration**: Create a folder named `.dp` in your project root.
2.  **Define your models**: Create `.dp/models.json`.
    ```json
    [
      {
        "stereotype": "documentation",
        "modelName": "gemma2:27b",
        "modelProviderURL": "http://localhost:11434",
        "serverType": "ollama"
      }
    ]
    ```
3.  **Define your document**: Create `.dp/documents.json`.
    ```json
    [
      {
        "stereotype": "documentation",
        "outputFile": "README.md",
        "prompt": "README.prompt.txt"
      }
    ]
    ```
4.  **Create the prompt**: Create `.dp/README.prompt.txt`.
    ```text
    Generate a README for the following source code:
    {{#java-src-dump this "../src/main/java"}}{{/java-src-dump}}
    ```
5.  **Run Doc|Pipe**:
    ```bash
    java -jar docpipe.jar --root /path/to/your/project
    ```

## Core Concepts & Advanced Usage

### Stereotypes
Stereotypes act as a bridge between a document requirement and a specific model configuration. In `documents.json`, you specify a `stereotype`. Doc|Pipe then looks up the corresponding configuration in `models.json`.

### Template Resolvers
Doc|Pipe provides built-in Handlebars helpers to enrich prompts:

*   **`{{#java-src-dump context path}}`**: Recursively scans the specified path and appends all `.java` files into the prompt inside Markdown code blocks.
*   **`{{#groovy}} ... {{/groovy}}`**: Executes Groovy code. The script has access to the `applicationContext` (Spring), the current `dir`, and template `options`.
*   **`{{#URL url}}`**: Fetches content from a specific URL to include in the prompt.

### Supported Server Types
Configure the `serverType` in `models.json` using one of the following:
*   `ollama`: For local Ollama instances.
*   `gemini`: For Google Gemini (requires API key).
*   `openapi`: For OpenAI or compatible APIs.
*   `lm.studio`: For local LM Studio instances (uses OpenAI protocol).
*   `custom`: For providing your own `ChatModel` implementation via the `kiChatImpl` field.

### Custom Model Implementation
To use a custom model, implement the `ChatModelSupplier` interface or provide a class that can be instantiated by the `CustomChatModelSupplier`.

```java
public class MyCustomSupplier implements ChatModelSupplier {
    @Override
    public boolean canProvide(DPModelConfig cfg) {
        return "my-type".equals(cfg.getServerType());
    }

    @Override
    public ChatModel provide(DPModelConfig cfg) {
        // Return a LangChain4j ChatModel
    }
}
```

## Best Practices & Common Pitfalls

### Performance and Threading
Doc|Pipe processes document generation in parallel. You can control the concurrency level using the property `prjxp.docpipe.maxthreads` (default is 5). If using local LLMs (like Ollama), setting this too high may overwhelm your GPU/CPU.

### Token Limits and Source Dumps
The `java-src-dump` helper is powerful but can easily exceed the context window of smaller LLMs if pointed at a large source tree. Always ensure your `modelName` supports the context size required for your codebase.

### Environment Variable Resolution
You can use environment variables inside your JSON configurations using the `${VAR_NAME}` syntax. This is highly recommended for `modelProviderURL` or sensitive parameters.

### Hashing Mechanism
Doc|Pipe generates a `content-hashes.properties` file inside the `.dp` directory. 
*   **Behavior**: If the resolved prompt (after Handlebars/Groovy processing) matches the stored hash, the LLM call is skipped.
*   **Force Update**: To force a regeneration without changing the prompt, delete the `content-hashes.properties` file or the specific entry within it.

### Timeout Configuration
For complex prompts or slow models, increase the `timeOutSeconds` in `models.json` (default is 60 seconds) to prevent `java.util.concurrent.TimeoutException`.

_This document was generated with .dp and gemini-3-flash-preview_

