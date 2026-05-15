# Doc|Pipe

![Doc|Pipe](doc/images/docpipe.png)

Doc|Pipe is a CLI-based content generation pipeline that leverages Large Language Models (LLMs) to automate the creation of documentation and content based on predefined job configurations.

## 🚀 Getting Started

### Prerequisites
- Java Runtime Environment (JRE)
- A configured LLM provider (e.g., Ollama)

### Configuration
Doc|Pipe uses a `.env` file located in the root directory to manage environment variables. The application automatically loads these properties at startup to configure the system.

**Example `.env`:**
```env
SOME_API_KEY=your_key_here
CUSTOM_CONFIG_PATH=/path/to/config
```

## 🛠 Usage

### Execution
The application is run as a Spring Boot CLI application. It parses command-line arguments to determine the project directory and then executes the defined jobs.

```bash
java -jar docpipe.jar [arguments]
```

### How it Works
1. **Initialization**: The app reads the `.env` file and initializes the Spring context.
2. **Global Models**: It checks for a global `models.json` file (defined in the configuration) to load shared model settings.
3. **Job Processing**: 
   - The `JobCreationService` scans the project directory for job definitions.
   - For each job found, it identifies the required content creation tasks.
   - The `ContentCreationService` executes these tasks by matching the required **Stereotype** with the corresponding **Model Configuration**.

## 📋 Configuration Models

### Model Configuration (`DPModelConfig`)
Defines how the application connects to an AI provider.
- `stereotype`: A unique identifier (e.g., "technical-writer", "summarizer") used to map tasks to models.
- `modelName`: The name of the model to use.
- `modelProviderURL`: The endpoint of the AI server.
- `serverType`: The type of server (defaults to `ollama`).
- `temperature`: Controls randomness (default: `0.2`).
- `timeOutSeconds`: Request timeout (default: `60`).
- `metadata`: Additional key-value pairs for model-specific settings.

### Content Creation Task (`DPContentCreation`)
Defines what content needs to be generated.
- `outputFile`: The destination path for the generated content.
- `stereotype`: The identifier of the model configuration to be used for this task.
- `prompt`: The primary instruction for the AI.
- `ps`: Post-script or additional context for the prompt.

### Job Definition (`DPJob`)
A job acts as a container for a specific project scope, containing:
- `rootDir`: The base directory for the job.
- `modelConfigs`: A list of model configurations available for this job.
- `contentCreationList`: A list of specific content tasks to be executed.

_This document was generated with Doc|Pipe and gemma4:31B_
