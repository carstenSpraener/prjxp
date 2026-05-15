# Doc|Pipe

![Doc|Pipe](doc/images/docpipe.png)

Doc|Pipe is a command-line tool designed to automate content creation by piping structured job definitions through configured AI models. It allows users to define specific "stereotypes" for AI models and map them to content creation tasks.

## Getting Started

### Configuration
Doc|Pipe supports environment variable configuration via a `.env` file located in the root directory. This file is automatically loaded at startup and integrated into the system properties.

### Execution
The application is a Spring Boot CLI application. It parses command-line arguments to determine the project directory and then executes the defined jobs.

## How it Works

### 1. Project Structure
Doc|Pipe looks for a project directory containing:
- A `.dp` directory (defined as `DP_DIR` in config).
- A `models.json` file within the `.dp` directory for global model configurations.
- Job definition files that specify which content to create.

### 2. Model Configuration (`DPModelConfig`)
Models are defined by a **stereotype**, which acts as a unique identifier. This allows you to switch between different AI providers or models without changing the individual content tasks.

**Configurable parameters include:**
- `stereotype`: Unique identifier for the model.
- `modelName`: The name of the AI model.
- `modelProviderURL`: The endpoint of the AI provider.
- `serverType`: The type of server (defaults to `ollama`).
- `temperature`: Controls randomness (default: `0.2`).
- `timeOutSeconds`: Request timeout (default: `60`).
- `metadata`: Additional key-value pairs for model-specific settings.

### 3. Job Definitions (`DPJob`)
A job consists of:
- **Model Configs**: A list of models available for this specific job.
- **Content Creation List**: A series of tasks to be executed.

### 4. Content Creation Tasks (`DPContentCreation`)
Each task defines how a specific piece of content should be generated:
- `outputFile`: The destination path for the generated content.
- `stereotype`: The model stereotype to be used for this task.
- `prompt`: The main instruction for the AI.
- `ps`: Additional post-script or supplementary instructions.

## Workflow Summary
1. **Initialization**: Loads `.env` and parses CLI arguments.
2. **Global Config**: Loads global model definitions from `models.json`.
3. **Job Discovery**: Scans the project directory for job definitions.
4. **Task Mapping**: For every `DPContentCreation` entry in a job, Doc|Pipe matches the required `stereotype` with the available `DPModelConfig`.
5. **Execution**: The `ContentCreationService` processes each task and writes the result to the specified `outputFile`.

_This document was generated with Doc|Pipe and gemma4:31B_
