# DocPipe

DocPipe is a pipeline designed to automate the creation of documentation content using Large Language Models (LLMs). It scans a project directory for specific configuration files and executes content generation jobs based on defined prompts and model settings.

## How it Works

The DocPipe process follows a three-step execution flow: **Discovery**, **Job Mapping**, and **Content Creation**.

### 1. Discovery (JobCreationService)
The system recursively scans the provided project root directory to find "Job" directories. A directory is recognized as a DocPipe job if it contains a special configuration folder (defined by `DocPipeConfig.DP_DIR`).

For every valid directory found, the `JobCreationService` looks for two primary JSON configuration files:
- **`models.json`**: Defines the LLM configurations (`DPModelConfig`). If this file is missing, the system attempts to fall back to global model configurations.
- **`documents.json`**: Defines a list of content creation tasks (`DPContentCreation`).

### 2. Job Mapping (DocPipeRunner)
The `DocPipeRunner` acts as the orchestrator. It triggers the discovery process and flattens the hierarchy:
- It reads all available `DPJob` objects.
- For each job, it iterates through the associated `DPContentCreation` list.
- It transforms these definitions into `ContentCreation` execution objects, linking the specific job context (like the root directory and model configs) with the content requirements.

### 3. Content Creation (ContentCreationService)
The final stage passes each `ContentCreation` object to the `ContentCreationService`. While the internal logic of the service is abstracted in the provided code, the model indicates that it utilizes:
- **PromptResolvingService**: To resolve the prompt templates.
- **LLMService**: To communicate with the configured LLM provider (e.g., Ollama).

## Configuration Models

### DPModelConfig
Defines which AI model to use for a specific task:
- `stereotype`: A category or role for the model.
- `modelName`: The specific name of the LLM.
- `modelProviderURL`: The endpoint of the LLM server.
- `serverType`: The type of server (defaults to `"ollama"`).

### DPContentCreation
Defines what needs to be generated:
- `outputFile`: The destination path for the generated content.
- `stereotype`: The model stereotype required for this specific document.
- `prompt`: The instruction/template for the LLM.
- `ps`: Additional parameters or context.

## Summary Flow
`Project Root` $\rightarrow$ `Scan for .dp folders` $\rightarrow$ `Parse models.json & documents.json` $\rightarrow$ `Map to ContentCreation tasks` $\rightarrow$ `LLM Generation` $\rightarrow$ `Output File`
_This file was generated with gemma4:31b_
