![Doc|Pipe](doc/images/docpipe.png)

# Doc|Pipe

Doc|Pipe is a powerful CLI tool designed to automate the generation of documentation and content using Large Language Models (LLMs). It acts as a "pipeline" that connects your project structure, custom templates, and various AI providers to produce consistent, high-quality output.

## How it Works

Doc|Pipe scans your project for special configuration directories. It resolves templates, injects context (like your source code), and communicates with LLMs to generate files only when changes are detected.

### The Workflow

```text
[ Project Directory ]
       │
       ▼
[ .dp/ Configuration ] ───► [ Template Engine ] ───► [ LLM Provider ]
       │                         │                        │
       │ (models.json)           │ (Handlebars/Groovy)    │ (Ollama/Gemini/OpenAI)
       │ (documents.json)        │                        │
       ▼                         ▼                        ▼
[ Content Hashing ] ◄──── [ Prompt Generation ] ──── [ AI Response ]
       │                                                  │
       └─────────── If changed, write to file ────────────┘
```

## Top Features

*   **Smart Hashing:** Doc|Pipe calculates a SHA-256 hash of your resolved prompts. If the prompt hasn't changed, it skips the LLM call, saving you significant time and token costs.
*   **Multi-LLM Mapping:** Use "Stereotypes" to map different tasks to different models. Use a "cheap" model for simple summaries and a "smart" model for complex architecture analysis.
*   **Handlebars Templates:** Prompts are built using Handlebars, making them dynamic and reusable.
*   **Groovy Scripting:** Need logic inside your prompt? You can embed Groovy code directly in your templates with full access to the Spring application context.
*   **Easy Extensibility:** Add custom LLM providers or template resolvers with minimal effort.

## Getting Started

### 1. Project Structure
Doc|Pipe looks for a `.dp` folder in your project directories. This folder contains your configuration and templates.

```text
my-project/
├── src/
├── .dp/
│   ├── models.json       # Define your LLM providers
│   ├── documents.json    # Define what files to generate
│   └── readme-gen.hbs    # Your prompt template
└── .env                  # API Keys and environment variables
```

### 2. Configure Models (`models.json`)
Define which AI services you want to use. Doc|Pipe supports **Ollama, Gemini, OpenAI, LM Studio**, and **Custom** implementations.

```json
[
  {
    "stereotype": "smart",
    "serverType": "gemini",
    "modelName": "gemini-1.5-pro",
    "temperature": 0.1
  },
  {
    "stereotype": "local",
    "serverType": "ollama",
    "modelProviderURL": "http://localhost:11434",
    "modelName": "llama3"
  }
]
```

### 3. Define Documents (`documents.json`)
Tell Doc|Pipe which files to create and which prompt template to use.

```json
[
  {
    "outputFile": "README.md",
    "stereotype": "smart",
    "prompt": "readme-gen.hbs"
  }
]
```

### 4. Create a Template (`readme-gen.hbs`)
Templates use Handlebars. You can use built-in helpers like `java-src-dump` to automatically include your source code in the prompt.

```handlebars
Write a README for the following Java project:

\{{java-src-dump "src/main/java"}}

Focus on the main features and usage.
```

## Usage

Run Doc|Pipe from your terminal. It will automatically load variables from your `.env` file.

```bash
# Run in the current directory
java -jar docpipe.jar

# Run on a specific root directory
java -jar docpipe.jar --root /path/to/your/project
```

### Environment Variables
You can use `${VARIABLE_NAME}` syntax in your `models.json` or `.env` file to keep your API keys secure.

```text
# .env file
CHAT_GEMINI_APIKEY=your_api_key_here
PRJXP_DOCPIPE_MAXTHREADS=10
```

## Advanced Templating

Doc|Pipe provides powerful helpers to build complex prompts:

*   **`\{{java-src-dump "path"}}`**: Recursively finds all `.java` files in the path and wraps them in markdown code blocks.
*   **`\{{URL "http..."}}`**: Fetches content from a URL to include in your prompt.
*   **`\{{groovy}} ... \{{/groovy}}`**: Executes Groovy code. You have access to the `applicationContext` to fetch any Spring bean or service.

## Further Reading

For more detailed information, please refer to the following documents:

*   [HowTo](doc/HowTo.md) - A guide for developers on how to use and extend Doc|Pipe.
*   [FAQs](doc/FAQ.md) - Answers to the most frequently asked questions.
*   [ArchitectureAssessment](doc/ArchitectureAssessment.md) - A deep dive into the internal architecture of Doc|Pipe.

***

**Fun Fact:** Doc|Pipe is self-documenting! It generates its own [Architecture Assessment](doc/ArchitectureAssessment.md) by analyzing its own source code.

_This document was generated with Doc|Pipe and gemini-3-flash-preview_

