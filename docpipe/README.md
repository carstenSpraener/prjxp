![Doc|Pipe](doc/images/docpipe.png)

# Doc|Pipe

Doc|Pipe is a powerful, template-driven command-line tool designed to automate the generation of project documentation using Large Language Models (LLMs). It transforms your source code and metadata into polished documentation by bridging the gap between your codebase and AI.

## How it Works

Doc|Pipe scans your project for configuration directories, resolves dynamic templates, and communicates with LLMs to generate content only when necessary.

```text
[ Project Files ]          [ .dp/ Configuration ]
       |                            |
       v                            v
+-------------------------------------------------------+
|                   Doc|Pipe Engine                     |
|                                                       |
|  1. Discovery: Finds documents.json                   |
|  2. Resolving: Processes Handlebars & Groovy          |
|  3. Hashing:   Checks if prompt changed               |
|  4. LLM Chat:  Sends prompt to mapped Model           |
|  5. Filtering: Cleans up AI response                  |
+-------------------------------------------------------+
       |
       v
[ Generated Documentation (Markdown, etc.) ]
```

## Top Features

*   **Smart Hashing:** Prevents unnecessary API calls and token usage. Doc|Pipe calculates a SHA-256 hash of every resolved prompt. If the prompt hasn't changed, the document isn't re-generated.
*   **Multi-LLM Mapping:** Optimize costs and quality by mapping "stereotypes" to specific models. Use a "fast" cheap model for simple summaries and a "smart" expensive model for complex architectural analysis.
*   **Handlebars Templates:** Use the familiar Handlebars syntax to structure your prompts. Inject variables, include external files, or dump source code directly into your prompt.
*   **Groovy Scripting:** Need complex logic? You can embed Groovy scripts directly inside your templates. These scripts have full access to the Spring Application Context and project metadata.
*   **Easy Extensibility:** Add your own content filters (e.g., to strip unwanted characters) or custom template resolvers by implementing simple Java interfaces.

## Getting Started

Doc|Pipe looks for a `.dp` directory in your project folders. This directory acts as the control center for your documentation tasks.

### 1. Define your Models (`.dp/models.json`)
Map stereotypes to specific LLM configurations:

```json
[
  {
    "stereoType": "architect",
    "provider": "openai",
    "model": "gpt-4"
  },
  {
    "stereoType": "summarizer",
    "provider": "anthropic",
    "model": "claude-3-haiku"
  }
]
```

### 2. Define your Documents (`.dp/documents.json`)
Specify what needs to be generated:

```json
[
  {
    "outputFile": "ARCHITECTURE.md",
    "stereotype": "architect",
    "prompt": "arch-template.hbs",
    "filterList": "noSurroundingCodeBlock"
  }
]
```

### 3. Create a Template (`.dp/arch-template.hbs`)
Combine text with dynamic resolvers:

```handlebars
# Project Architecture
This document describes the architecture of the project.

## Source Code Context
{{java-src-dump "src/main/java" scanSubs=true}}

## Dynamic Insights
{{#groovy}}
   // Access the project directory or Spring beans here!
   return "Generated on: " + new Date()
{{/groovy}}
```

## Usage

Simply run the application from your project root:

```bash
java -jar docpipe.jar --project.dir=.
```

Doc|Pipe will:
1. Walk through your directories.
2. Find every `.dp` folder.
3. Process the tasks in `documents.json`.
4. Output the generated files to your specified paths.

## Further Reading

For more detailed information, please refer to the following documents:

*   [HowTo](doc/HowTo.md): A guide for developers on how to use and extend the system.
*   [FAQs](doc/FAQ.md): Frequently asked questions about Doc|Pipe.
*   [ArchitectureAssessment](doc/ArchitectureAssessment.md): A deep dive into the internal architecture of Doc|Pipe.

***

**Fun Fact:** Doc|Pipe is self-documenting! It uses its own engine to generate the [Architecture Assessment](doc/ArchitectureAssessment.md) based on its own source code.

_This document was generated with Doc|Pipe and gemini-3-flash-preview_

