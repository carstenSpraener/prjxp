![Doc|Pipe](doc/images/docpipe.png)

# Doc|Pipe

Doc|Pipe is a powerful, CLI-driven documentation pipeline designed to automate the generation of project documentation using Large Language Models (LLMs). It intelligently scans your project, resolves complex templates, and interacts with various LLMs to keep your documentation in sync with your code.

## How it Works

Doc|Pipe operates on a simple "Discovery and Execution" model. It traverses your project directory looking for configuration folders named `.dp`. Each folder tells Doc|Pipe what documents to generate, which templates to use, and which AI models to consult.

```text
[ Project Root ]
├── src/ ... (Your Code)
├── .dp/
│   ├── documents.json      <-- Task Definitions
│   ├── models.json         <-- Model Mappings
│   └── readme-template.hb  <-- Handlebars Prompt Template
└── README.md               <-- Generated Output
```

### The Workflow

1.  **Scan**: Doc|Pipe finds all `.dp` directories in your project.
2.  **Resolve**: It processes prompt templates using **Handlebars**, injecting code context or running **Groovy** scripts.
3.  **Hash Check**: It calculates a SHA-256 hash of the final prompt. If the prompt hasn't changed since the last run, the LLM call is skipped to save time and money.
4.  **Generate**: If changes are detected, it sends the prompt to the LLM mapped to that specific task's "stereotype".
5.  **Filter & Write**: The AI response is cleaned (e.g., removing markdown code blocks) and written to the specified output file.

## Top Features

### 🚀 Smart Hashing
Doc|Pipe remembers. By hashing the generated prompts, the system knows exactly when a document actually needs an update. If your code and templates haven't changed, Doc|Pipe won't waste tokens.

### 🤖 Multi-LLM Mapping (Stereotypes)
Not every task requires the most expensive model. You can define "stereotypes" (e.g., `fast`, `creative`, `architect`) and map them to different LLMs (e.g., GPT-3.5 for simple summaries, GPT-4 for complex architecture analysis).

### 📝 Handlebars Root Structure
Templates are written in Handlebars, making them easy to read and maintain. You can use helpers like `{{java-src-dump "path"}}` to automatically pull source code into your prompts.

### 💡 Groovy Integration
For ultimate power, you can embed Groovy scripts directly inside your templates. These scripts have full access to the **Spring Application Context**, allowing you to query project metadata or perform complex logic during prompt generation.

### 🧩 Easy Extensibility
Doc|Pipe is built to grow. You can easily add new `ContentFilters` to post-process AI output or new `TemplateResolvers` to introduce custom logic into your prompt templates.

## Usage Example

### 1. Define your tasks (`.dp/documents.json`)
```json
[
  {
    "outputFile": "docs/Architecture.md",
    "stereotype": "architect",
    "prompt": "arch-template.hb",
    "filterList": "noSurroundingCodeBlock"
  }
]
```

### 2. Create a template (`.dp/arch-template.hb`)
```handlebars
Analyze the following Java architecture:

{{java-src-dump "../src/main/java" scanSubs=true}}

{{#groovy}}
   return "Please focus specifically on the " + applicationContext.getBean('projectInfo').getName() + " module."
{{/groovy}}
```

### 3. Run the CLI
```bash
java -jar docpipe.jar --projectDir .
```

## Architecture Overview

The following diagram illustrates how Doc|Pipe processes a single documentation task:

```text
+----------------+      +-----------------------+      +----------------+
|  .dp Config    | ---> | PromptResolvingService| ---> | Hashing Check  |
| (JSON + Temp)  |      | (Handlebars + Groovy) |      | (SHA-256)      |
+----------------+      +-----------------------+      +-------|--------+
                                                               |
                                                       Prompt Changed?
                                                               |
        +------------------------------------------------------+--- Yes ----+
        |                                                                   |
+-------v--------+      +-----------------------+      +--------------------+
|   LLM Service  | <--- | Stereotype Mapping    |      | Content Generation |
| (Chat Request) |      | (models.json)         |      | (LLM Interaction)  |
+-------|--------+      +-----------------------+      +---------|----------+
        |                                                        |
        +--------------> +-----------------------+      +--------v----------+
                         |    Content Filters    | ---> |   Output File     |
                         | (Clean up Markdown)   |      | (README.md, etc.) |
                         +-----------------------+      +-------------------+
```

## Further Reading

*   [HowTo](doc/HowTo.md): Detailed explanations for developers on how to use and extend Doc|Pipe.
*   [FAQs](doc/FAQ.md): Answers to the most important questions about Doc|Pipe.
*   [ArchitectureAssessment](doc/ArchitectureAssessment.md): An in-depth assessment of the Doc|Pipe architecture based on its source code.

***

**Fun Fact:** Doc|Pipe is self-documenting! It uses its own logic to generate its [Architecture Assessment](doc/ArchitectureAssessment.md).

_This document was generated with Doc|Pipe and gemini-3-flash-preview_

