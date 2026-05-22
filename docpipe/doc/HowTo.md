# Doc|Pipe User Guide

Doc|Pipe is a professional documentation generation tool designed for Java developers. It automates the creation of project documentation by piping source code and metadata through Large Language Models (LLMs). By using a "stereotype" mapping system, it allows you to route different documentation tasks (e.g., READMEs, Javadocs, Architecture Assessments) to the most cost-effective or capable LLM provider.

## Overview

Doc|Pipe operates as a CLI application that scans a project directory for configuration files, resolves complex prompt templates, and interacts with LLM providers to generate text files. 

**Key Features:**
*   **Prompt Hashing**: Prevents unnecessary API calls by only regenerating documents when the underlying prompt or source code changes.
*   **Multi-LLM Mapping**: Assigns specific models to specific tasks via "stereotypes."
*   **Handlebars Templating**: Uses Handlebars as the root structure for prompts.
*   **Extensible Resolvers**: Includes built-in support for dumping Java source code and executing Groovy scripts directly within templates.
*   **Provider Support**: Native integration for Ollama, Google Gemini, OpenAI, and LM Studio.

## Prerequisites & Setup

### System Requirements
*   **Java 17** or higher.
*   **API Keys**: Required for cloud providers (Gemini/OpenAI).

### Configuration Files
Doc|Pipe looks for a `.dp` directory in your project root. This directory must contain:
1.  `models.json`: Defines the LLM providers and their configurations.
2.  `documents.json`: Defines what documents to generate and which prompts to use.

### Environment Variables
You can provide API keys via a `.env` file in the working directory or via system properties:
*   `chat.gemini.apikey`: Your Google AI Gemini key.
*   `chat.openapi.api-key`: Your OpenAI or compatible provider key.
*   `prjxp.docpipe.maxthreads`: (Optional) Number of concurrent generation tasks (Default: 5).

## Quick Start

1.  **Initialize the `.dp` directory** in your project root.
2.  **Configure your models** in `.dp/models.json`:
    ```json
    [
      {
        "stereotype": "documentation",
        "modelName": "gemini-1.5-flash",
        "serverType": "gemini"
      }
    ]
    ```
3.  **Define a document task** in `.dp/documents.json`:
    ```json
    [
      {
        "stereotype": "documentation",
        "outputFile": "README.md",
        "prompt": "README.prompt.txt"
      }
    ]
    ```
4.  **Create your prompt template** in `.dp/README.prompt.txt`:
    ```text
    Generate a README for this project based on the following source code:
    {{#java-src-dump this "../src/main/java"}}{{/java-src-dump}}
    ```
5.  **Run Doc|Pipe**:
    ```bash
    java -jar docpipe.jar --root /path/to/your/project
    ```

## Core Concepts & Advanced Usage

### Stereotypes
Stereotypes act as an abstraction layer between the document task and the LLM. In `documents.json`, you assign a `stereotype` to a task. In `models.json`, you define which model handles that stereotype. This allows you to switch from an expensive model to a local Ollama instance by changing a single configuration line.

### Template Resolvers
Doc|Pipe uses Handlebars helpers to inject dynamic content into prompts:

*   **`{{#java-src-dump this "path"}}`**: Recursively scans the specified path and appends all `.java` files into the prompt inside Markdown code blocks.
*   **`{{#groovy}} ... {{/groovy}}`**: Executes Groovy code. The script has access to the `applicationContext` (Spring), the `baseDir`, and template `options`.
*   **`{{#URL "url"}}`**: Fetches content from a local or remote URL to include in the prompt.

### Server Types
The `serverType` property in `models.json` supports:
*   `ollama`: For local models running via Ollama. Requires `modelProviderURL`.
*   `gemini`: For Google Gemini models.
*   `openapi` / `lm.studio`: For OpenAI-compatible APIs.
*   `custom`: Allows you to provide your own `ChatModel` implementation via the `kiChatImpl` property.

### Custom Chat Models
To use a custom provider, implement the `dev.langchain4j.model.chat.ChatModel` interface and reference your class in the config:
```json
{
  "stereotype": "special",
  "serverType": "custom",
  "kiChatImpl": "com.example.MyCustomSupplier"
}
```

## Best Practices & Common Pitfalls

### Prompt Hashing
Doc|Pipe stores SHA-256 hashes of resolved prompts in `.dp/content-hashes.properties`. 
*   **Behavior**: If the generated prompt (after resolving all helpers) is identical to the previous run, the LLM call is skipped.
*   **Tip**: If you want to force regeneration without changing the prompt, delete the corresponding entry in `content-hashes.properties`.

### Path Resolution
Paths in `documents.json` and prompt templates are generally relative to the `.dp` directory. Use `../` to navigate to the project root.

### Threading and Rate Limits
Doc|Pipe processes documents in parallel. If you are using a cloud provider with strict rate limits (like free-tier Gemini), reduce the concurrency by setting the system property:
`-Dprjxp.docpipe.maxthreads=1`

### Error Handling
If a run fails, check the console output. Doc|Pipe aggregates errors and will provide a summary at the end of the execution. Common errors include missing `models.json` or incorrect API keys in the environment.

_This document was generated with .dp and gemini-3-flash-preview_

