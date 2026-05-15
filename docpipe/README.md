# DocPipe

![DocPipe](doc/images/docpipe.png)

DocPipe is a command-line tool designed to automate content creation by piping project data through Large Language Models (LLMs). It allows users to define specific "jobs" that map content requirements to specific model configurations.

## Getting Started

### Configuration
DocPipe supports environment variable configuration via a `.env` file located in the root directory. This is primarily used for sensitive information such as API keys or server URLs.

**Example `.env`:**
```env
LLM_API_KEY=your_key_here
OLLAMA_HOST=http://localhost:11434
```

### Usage
The application is executed as a Spring Boot CLI application. It parses arguments provided at runtime to locate the project directory and then executes the defined jobs.

```bash
java -jar docpipe.jar [project-directory]
```

## How it Works

### 1. Job Definition
DocPipe operates based on a project structure. A **Job** (`DPJob`) consists of:
- **Project Directory**: The root folder containing the source materials.
- **Model Configurations**: A list of available LLM providers and their settings.
- **Content Creation Tasks**: A list of specific outputs to be generated.

### 2. Model Mapping
The system uses a "Stereotype" mechanism to decouple the content request from the specific model implementation. 

- **`DPModelConfig`**: Defines a `stereotype` (e.g., "creative-writer" or "technical-expert"), the `modelName`, and the `modelProviderURL`.
- **`DPContentCreation`**: Requests a specific `stereotype` to handle the prompt.

When the `DocPipeRunner` processes a task, it matches the requested stereotype from the content creation definition to the corresponding model configuration.

### 3. Execution Pipeline
The processing flow follows these steps:
1. **Initialization**: Loads `.env` variables and parses CLI arguments.
2. **Job Discovery**: The `JobCreationService` reads the jobs from the specified project directory.
3. **Task Expansion**: Each job is broken down into individual `ContentCreation` units.
4. **Content Generation**: The `ContentCreationService` utilizes the `LLMService` and `PromptResolvingService` to generate text based on the provided prompt and output it to the specified `outputFile`.

## Data Models

### Content Creation Definition
The following fields are used to define what needs to be generated:
- `outputFile`: The path where the resulting content will be saved.
- `stereotype`: The identifier for the LLM model to be used.
- `prompt`: The instruction for the LLM.
- `ps`: Additional post-script or context for the generation.

### Model Configuration
The system supports flexible model providers (defaulting to `ollama`):
- `stereotype`: Unique identifier for the model role.
- `modelName`: The specific model version (e.g., `llama3`).
- `modelProviderURL`: The endpoint of the LLM server.
- `serverType`: The type of server implementation.
```
_This file was generated with gemma4:31b_
