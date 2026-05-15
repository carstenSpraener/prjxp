# DocPipe

![DocPipe](doc/images/docpipe.png)

DocPipe is a command-line tool designed to automate the generation of documentation and content using Large Language Models (LLMs). It allows users to define "Jobs" that map specific content requirements to specialized AI models via a structured configuration.

## 🚀 Getting Started

### Prerequisites
- Java Runtime Environment (JRE)
- A configured LLM provider (e.g., Ollama)

### Configuration
DocPipe supports environment variables via a `.env` file located in the root directory. This is used to manage sensitive information or system-wide settings.

**Example `.env`:**
```env
LLM_API_KEY=your_key_here
OLLAMA_HOST=http://localhost:11434
```

## 🛠 Usage

### Running the Application
DocPipe is executed as a Spring Boot CLI application. It parses arguments provided at runtime to determine the project directory and then executes the defined jobs.

```bash
java -jar docpipe.jar [arguments]
```

### Project Structure
The application expects a project directory containing configuration files that define the documentation pipeline.

#### 1. Model Configuration (`DPModelConfig`)
You must define the AI models to be used. This is typically done in a JSON file that maps a **stereotype** to a specific model provider.

**Example Model Config:**
```json
[
  {
    "stereotype": "technical-writer",
    "modelName": "llama3",
    "modelProviderURL": "http://localhost:11434",
    "serverType": "ollama"
  },
  {
    "stereotype": "creative-assistant",
    "modelName": "mistral",
    "modelProviderURL": "http://localhost:11434",
    "serverType": "ollama"
  }
]
```

#### 2. Content Creation Definitions (`DPContentCreation`)
Define what content needs to be generated, which model stereotype to use, and the prompt.

**Example Content Definition:**
```json
[
  {
    "outputFile": "docs/architecture.md",
    "stereotype": "technical-writer",
    "prompt": "Generate a high-level architecture document based on the source code.",
    "ps": "Ensure you use Mermaid.js for diagrams."
  }
]
```

## ⚙️ How it Works

1. **Initialization**: The app loads `.env` variables into the system properties.
2. **Argument Parsing**: The `DocPipeArgsParser` processes the command line input to locate the project directory.
3. **Job Loading**: The `JobCreationService` reads the project directory to build `DPJob` objects, which combine:
   - The root directory.
   - A list of available model configurations.
   - A list of content creation tasks.
4. **Execution**: The `DocPipeRunner` iterates through all content creation tasks. For each task:
   - It matches the required `stereotype` to the corresponding `DPModelConfig`.
   - The `ContentCreationService` invokes the `LLMService` to generate the text based on the prompt.
   - The resulting content is written to the specified `outputFile`.

_This document was generated with DocPipe and gemma4:31B_
