# Doc|Pipe - Intelligent Document Generation Pipeline

![Doc|Pipe](doc/images/docpipe.png)

Doc|Pipe is a powerful CLI tool designed to automate the generation of documentation and content using Large Language Models (LLMs). It streamlines the process of turning templates and source data into finished documents while keeping costs low and performance high.

## Core Concept

Doc|Pipe scans your project for specific configuration folders (`.dp`). Each folder defines a "Job" consisting of what to generate, which LLM to use, and where to save the result.

### How it works

```text
[ Template File ] ----> [ Prompt Resolver ] ----> [ Prompt Hash Check ]
                               |                         |
                      (Handlebars + Groovy)        (Skip if unchanged)
                               |                         |
                               v                         v
[ LLM Mapping ] <---- [ Final Prompt ]           [ LLM Provider ]
(Stereotypes)                                    (Ollama, Gemini, etc.)
       |                                                 |
       +-------------------------------------------------+
                               |
                               v
                        [ Output File ]
```

## Top Features

*   **Smart Hashing:** Doc|Pipe calculates a SHA-256 hash of every generated prompt. If the prompt hasn't changed since the last run, the LLM call is skipped. This saves time and significant token costs.
*   **Multi-LLM Mapping:** Use "Stereotypes" to route different tasks to different models. Use a "cheap" local model (via Ollama) for simple summaries and a "smart" model (via Gemini or OpenAI) for complex architectural analysis.
*   **Handlebars Templates:** Templates use the familiar Handlebars syntax, making it easy to inject variables and structure your prompts.
*   **Groovy Scripting:** Need logic inside your prompt? You can embed Groovy code directly within your templates. It has full access to the Spring Application Context, allowing for advanced data retrieval.
*   **Easy Extensibility:** The system is built to be extended. You can easily add custom Chat Model suppliers or new Template Resolvers.

## Getting Started

### 1. Configuration

Doc|Pipe looks for a `.dp` directory in your project. Inside, you define two main files:

#### `models.json`
Define which LLMs are available and assign them a **stereotype**.

```json
[
  {
    "stereotype": "fast",
    "serverType": "ollama",
    "modelName": "llama3",
    "modelProviderURL": "http://localhost:11434"
  },
  {
    "stereotype": "expert",
    "serverType": "gemini",
    "modelName": "gemini-1.5-pro",
    "temperature": 0.1
  }
]
```

#### `documents.json`
Define what files should be generated.

```json
[
  {
    "outputFile": "docs/Architecture.md",
    "stereotype": "expert",
    "prompt": "arch-template.hbs"
  }
]
```

### 2. Templates

Create your prompt template (e.g., `arch-template.hbs`) inside the `.dp` folder. You can use specialized helpers:

*   `{{java-src-dump "src/main/java"}}`: Automatically includes all Java source code from a directory into the prompt.
*   `{{#groovy}} ... {{/groovy}}`: Execute logic to fetch data or format strings.

### 3. Running the CLI

Run Doc|Pipe from your terminal. It will automatically pick up your `.env` file for API keys.

```bash
java -jar docpipe.jar --root ./my-project
```

## Further Reading

*   [HowTo](doc/HowTo.md): Detailed guide for developers on how to use and extend Doc|Pipe.
*   [FAQs](doc/FAQ.md): Answers to the most frequently asked questions.
*   [ArchitectureAssessment](doc/ArchitectureAssessment.md): A deep dive into the internal architecture of Doc|Pipe.

***

**Fun Fact:** Doc|Pipe is self-documenting! It generates its own [Architecture Assessment](doc/ArchitectureAssessment.md) by analyzing its own source code through the pipeline.

_This document was generated with Doc|Pipe and gemini-3-flash-preview_

