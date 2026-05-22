![Doc|Pipe](doc/images/docpipe.png)

# Doc|Pipe

Doc|Pipe is a powerful CLI-driven pipeline designed to automate the generation of documentation and content using Large Language Models (LLMs). It transforms your project structure into a documentation engine by mapping specific content requirements to specialized AI models.

## Core Concept

Doc|Pipe scans your project for configuration directories named `.dp`. Each directory defines a "Job" consisting of what needs to be generated, which prompt to use, and which LLM should handle the task.

### Key Features

*   **Smart Hashing:** Doc|Pipe calculates a SHA-256 hash of your resolved prompts. If the prompt hasn't changed, it skips the LLM call, saving you time and API credits.
*   **Multi-LLM Mapping:** Assign different "stereotypes" to models. Use a cheap, fast model (like Ollama) for simple summaries and a high-end model (like Gemini or GPT-4) for complex architectural analysis.
*   **Handlebars Templating:** Prompts are not just static text. They are dynamic templates powered by Handlebars.
*   **Groovy Integration:** Need logic inside your prompt? You can embed Groovy scripts directly in your templates with full access to the Spring application context.
*   **Easy Extensibility:** Add custom Chat Model suppliers or new Template Resolvers (like the built-in Java source code dumper) with minimal effort.

---

## How it Works

```text
[ Project Root ]
      │
      ├── .dp/                      <-- Configuration Folder
      │    ├── models.json          <-- Which LLMs to use
      │    ├── documents.json       <-- What to generate
      │    └── my-prompt.hbs        <-- The instructions
      │
      ├── src/                      <-- Your Source Code
      │
      └── generated-doc.md          <-- The Output
```

1.  **Scan:** Doc|Pipe finds all `.dp` folders.
2.  **Resolve:** It processes the Handlebars templates (fetching URLs, dumping source code, or running Groovy).
3.  **Hash Check:** It compares the new prompt hash against `content-hashes.properties`.
4.  **Generate:** If changed, it sends the prompt to the LLM assigned to that specific "stereotype".
5.  **Output:** The result is written to your specified output file.

---

## Usage

### 1. Define your Models (`.dp/models.json`)
Map stereotypes to specific providers. Doc|Pipe supports **Ollama, Gemini, OpenAI, LM Studio**, and **Custom** implementations.

```json
[
  {
    "stereotype": "architect",
    "serverType": "gemini",
    "modelName": "gemini-1.5-pro",
    "temperature": 0.1
  },
  {
    "stereotype": "summarizer",
    "serverType": "ollama",
    "modelProviderURL": "http://localhost:11434",
    "modelName": "llama3",
    "temperature": 0.7
  }
]
```

### 2. Define your Documents (`.dp/documents.json`)
Specify which files to create and which prompt/model to use.

```json
[
  {
    "outputFile": "../README.md",
    "stereotype": "summarizer",
    "prompt": "readme-template.hbs"
  }
]
```

### 3. Create a Template (`.dp/readme-template.hbs`)
Use powerful helpers to build your prompt.

```handlebars
Write a README for the following Java project:

{{java-src-dump "../src/main/java"}}

{{#groovy}}
  // You can use Groovy logic here!
  return "The current directory is: " + dir.getName();
{{/groovy}}
```

### 4. Run the CLI
Run Doc|Pipe from your terminal. It will automatically look for a `.env` file for API keys.

```bash
java -jar docpipe.jar --root ./my-project
```

---

## Advanced Templating

Doc|Pipe comes with built-in resolvers to make prompts context-aware:

*   `{{java-src-dump "path"}}`: Recursively finds all `.java` files in a directory and wraps them in Markdown code blocks.
*   `{{URL "http://..."}}`: Fetches content from a URL to include in your prompt.
*   `{{#groovy}} ... {{/groovy}}`: Executes Groovy code. It has access to the `applicationContext`, allowing you to interact with the internal state of the application.

---

## Further Reading

*   [HowTo](doc/HowTo.md): Detailed guide for developers on how to use and extend Doc|Pipe.
*   [FAQs](doc/FAQ.md): Answers to the most frequently asked questions.
*   [ArchitectureAssessment](doc/ArchitectureAssessment.md): An in-depth look at the internal structure and design decisions of Doc|Pipe.

---
**Fun Fact:** Doc|Pipe is self-documenting! It uses its own engine to generate the [Architecture Assessment](doc/ArchitectureAssessment.md) based on its own source code.

_This document was generated with Doc|Pipe and gemini-3-flash-preview_

