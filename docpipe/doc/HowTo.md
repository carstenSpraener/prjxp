# Doc|Pipe User Guide

Doc|Pipe is a Java-based CLI tool designed to automate the generation of documentation and technical content using Large Language Models (LLMs). It allows developers to define documentation tasks, map them to specific LLM providers (Ollama, Gemini, OpenAI) via "stereotypes," and use Handlebars-based templates to inject project context—such as source code—directly into prompts.

## Prerequisites & Setup

### System Requirements
* **Java 17** or higher.
* **LLM Access**: A running instance of **Ollama**, or API keys for **Google Gemini** or **OpenAI**.

### Environment Configuration
Doc|Pipe supports a `.env` file in the execution directory to manage sensitive credentials. Create a `.env` file with the following keys as needed:

```properties
chat.gemini.apikey=your_gemini_api_key
chat.openapi.api-key=your_openai_api_key
```

### Project Structure
Doc|Pipe looks for a configuration directory named `.dp` within your project. This directory should contain your model definitions, document tasks, and prompt templates.

```text
my-project/
├── .dp/
│   ├── models.json        # LLM provider configurations
│   ├── documents.json     # Documentation tasks
│   └── Readme.prompt.txt  # Handlebars prompt template
└── src/                   # Your source code
```

## Quick Start

1. **Define Models**: Create `.dp/models.json` to specify which LLM to use for a specific "stereotype".
   ```json
   [
     {
       "stereotype": "documentation",
       "modelName": "gemma4:31b-cloud",
       "modelProviderURL": "http://localhost:11434",
       "serverType": "ollama"
     }
   ]
   ```

2. **Define Tasks**: Create `.dp/documents.json` to link a prompt to an output file.
   ```json
   [
     {
       "stereotype": "documentation",
       "outputFile": "README.md",
       "prompt": "Readme.prompt.txt"
     }
   ]
   ```

3. **Create a Prompt**: Create `.dp/Readme.prompt.txt`.
   ```text
   Generate a README for this project based on the following code:
   {{#java-src-dump . ../src/main/java}}{{/java-src-dump}}
   ```

4. **Run Doc|Pipe**:
   Execute the application via CLI, specifying the root directory of your project:
   ```bash
   java -jar docpipe.jar --root /path/to/your/project
   ```

## Core Concepts

### Stereotypes
Stereotypes act as a bridge between a **Content Creation Task** and a **Model Configuration**. This allows you to use a fast, cheap model for simple tasks (like Javadoc) and a more powerful model for complex architectural documentation.

### Supported Server Types
Doc|Pipe uses `ChatModelSupplier` implementations to connect to various backends:
* **ollama**: Local LLM execution.
* **gemini**: Google AI Gemini models (requires `apiKey`).
* **openapi**: OpenAI-compatible APIs (also used for **LM Studio**).
* **custom**: Allows providing a custom `ChatModel` implementation class via the `kiChatImpl` field in `models.json`.

### Prompt Templates & Helpers
Prompts are processed using Handlebars. Doc|Pipe provides built-in helpers to inject context:
* **`{{#java-src-dump baseDir relativeSrcPath}}`**: Recursively scans the specified directory for `.java` files and dumps their content into the prompt wrapped in Markdown code blocks.
* **`{{#URL url}}`**: Resolves and includes content from a specified URL.

### Incremental Updates
To save costs and time, Doc|Pipe tracks the state of your prompts. It generates a SHA-256 hash of the resolved prompt and stores it in `.dp/content-hashes.properties`. A document is **only re-generated** if the prompt content has changed.

## Advanced Usage

### Custom Model Implementation
If you need to use a specific LLM client not supported out-of-the-box, implement the `dev.langchain4j.model.chat.ChatModel` interface and configure it in `models.json`:

```json
{
  "stereotype": "specialized",
  "serverType": "custom",
  "kiChatImpl": "com.example.MyCustomChatModel",
  "args": {
    "customParam": "value"
  }
}
```

### Global vs. Local Models
Doc|Pipe first looks for a `models.json` inside the local `.dp` folder. If not found, it falls back to a global `models.json` located in the root project directory's `.dp` folder.

## Best Practices & Common Pitfalls

* **Prompt Stability**: Since Doc|Pipe uses hashing to determine if an update is needed, avoid putting timestamps or volatile environment information inside your prompt templates unless you want to force a re-generation every run.
* **Timeout Management**: For large source code dumps, LLMs may take significant time to respond. Increase the `timeOutSeconds` in your `models.json` (default is 60s) to prevent `java.time.Duration` related errors.
* **Context Window Limits**: When using `java-src-dump` on large projects, be mindful of the LLM's context window. Large codebases may exceed the token limit of smaller models.
* **API Key Security**: Never hardcode API keys in `models.json`. Use the `${VAR_NAME}` syntax in your configuration or rely on the `.env` file integration.

_This document was generated with .dp and gemini-3-flash-preview_

