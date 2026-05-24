# Doc|Pipe

![Doc|Pipe](doc/images/docpipe.png)

Doc|Pipe is a powerful CLI-based documentation pipeline designed to automate the generation of project documentation using Large Language Models (LLMs). It streamlines the process of turning source code, templates, and structured data into high-quality documents while keeping costs and execution time to a minimum.

## Top Features

*   **Smart Prompt Hashing**: Doc|Pipe calculates a SHA-256 hash of your resolved prompts. If the prompt hasn't changed, it skips the LLM call, preventing unnecessary document generation and saving you money and time.
*   **Multi-LLM Mapping**: Assign different "stereotypes" to different LLMs. Use a local Ollama instance for simple tasks and switch to high-end models like Gemini or OpenAI only when needed.
*   **Handlebars Root Structure**: Templates are built using Handlebars, making it easy to structure your prompts with variables and logic.
*   **Groovy Scripting Power**: Need complex logic? You can embed Groovy scripts directly inside your templates with full access to the Spring application context.
*   **Easy Extensibility**: The architecture is plugin-friendly. You can easily add custom ChatModel suppliers or new template resolvers to fit your specific workflow.

## How it Works

Doc|Pipe scans your project for special configuration directories and processes them in a parallel pipeline.

```text
[ Project Root ]
       │
       ├── src/ (Your Source Code)
       │
       └── .dp/  <-- Doc|Pipe Configuration Folder
            ├── models.json      (Which LLMs to use)
            ├── documents.json   (What to generate)
            └── prompts/         (Your Handlebars templates)
```

### The Workflow Diagram

```text
1. Scan Directories ──▶ 2. Load Config (.dp/) ──▶ 3. Resolve Templates
                                                         │ (Handlebars/Groovy)
                                                         ▼
6. Write Output     ◀── 5. Call LLM (if changed) ◀── 4. Check Hash
   (Markdown/Docs)          (Ollama/Gemini/etc.)        (Skip if identical)
```

## Configuration

To use Doc|Pipe, you place a `.dp` folder in any directory where you want documentation generated.

### 1. Define your Models (`models.json`)
Map "stereotypes" to specific LLM configurations.

```json
[
  {
    "stereotype": "fast",
    "serverType": "ollama",
    "modelName": "llama3",
    "modelProviderURL": "http://localhost:11434"
  },
  {
    "stereotype": "smart",
    "serverType": "gemini",
    "modelName": "gemini-1.5-pro",
    "temperature": 0.1
  }
]
```

### 2. Define your Documents (`documents.json`)
Tell Doc|Pipe which template to use for which output file.

```json
[
  {
    "outputFile": "README.md",
    "stereotype": "smart",
    "prompt": "prompts/readme-gen.hbs"
  }
]
```

## Template Power

Doc|Pipe templates use Handlebars but are supercharged with custom resolvers.

### Source Code Dumps
Automatically include your Java source code into a prompt:
`{{java-src-dump "../src/main/java"}}`

### Groovy Logic
Execute logic during prompt generation:
```handlebars
{{#groovy}}
  def beans = applicationContext.getBeanDefinitionNames()
  return "This project has ${beans.size()} spring beans."
{{/groovy}}
```

## Usage

Run Doc|Pipe from your terminal. It will recursively look for `.dp` folders starting from the root.

```bash
java -jar docpipe.jar --root ./my-project
```

### Environment Variables
You can provide API keys via a `.env` file in your working directory:
```env
CHAT_GEMINI_APIKEY=your_api_key_here
CHAT_OPENAPI_API_KEY=your_openai_key
```

## Further Reading

For more detailed information, please refer to the following documents:

*   [HowTo](doc/HowTo.md): A guide for developers on how to use and extend Doc|Pipe.
*   [FAQs](doc/FAQ.md): Answers to the most frequently asked questions.
*   [ArchitectureAssessment](doc/ArchitectureAssessment.md): A deep dive into the internal architecture of Doc|Pipe.

***

**Fun Fact:** Doc|Pipe is self-aware enough to generate its own [Architecture Assessment](doc/ArchitectureAssessment.md) by analyzing its own source code!

_This document was generated with Doc|Pipe and gemini-3-flash-preview_

