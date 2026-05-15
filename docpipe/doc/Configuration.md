# DocPipe

DocPipe is a documentation pipeline designed to automate the creation of content using Large Language Models (LLMs). It scans a project directory for specific configuration files and processes them to generate documents based on defined prompts and model settings.

## How it Works

The execution flow is managed by the `DocPipeRunner`, which orchestrates the process through three main phases: **Job Discovery**, **Job Parsing**, and **Content Creation**.

### 1. Job Discovery & Parsing
The `JobCreationService` is responsible for identifying "Jobs" within a project directory.

- **Directory Scanning**: The service walks through the provided project root directory.
- **Trigger**: A directory is recognized as a DocPipe job if it contains a special subdirectory (defined by `DocPipeConfig.DP_DIR`).
- **Configuration Loading**: For every identified job directory, the service looks for two JSON files:
    - `models.json`: Defines the LLM configurations (`DPModelConfig`). If this file is missing, the system falls back to global model configurations.
    - `documents.json`: Defines a list of content creation tasks (`DPContentCreation`).

### 2. Data Model
The pipeline relies on two primary data structures:

- **`DPModelConfig`**: Specifies how to connect to an LLM, including the `modelName`, `modelProviderURL`, `serverType` (defaulting to "ollama"), and a `stereotype` to categorize the model.
- **`DPContentCreation`**: Defines what needs to be generated, including:
    - `outputFile`: The destination path for the generated content.
    - `prompt`: The instruction for the LLM.
    - `stereotype`: The type of model required for this specific task.
    - `ps`: Additional parameters or context.

### 3. Execution Pipeline
The `DocPipeRunner` executes the following logic:

1. **Read Jobs**: Calls `jobCreationService.readJobs()` to get a stream of all valid `DPJob` objects found in the file system.
2. **Flattening**: Since one `DPJob` can contain multiple `DPContentCreation` entries, the runner flattens these into a single stream of `ContentCreation` objects.
3. **Processing**: Each `ContentCreation` object is passed to the `ContentCreationService`, which handles the actual interaction with the LLM and writes the resulting content to the specified output file.

## Summary Flow
`Project Root` $\rightarrow$ `Scan for DP_DIR` $\rightarrow$ `Parse JSON Configs` $\rightarrow$ `Map Prompt to Model` $\rightarrow$ `Generate Content` $\rightarrow$ `Write to File`

_This document was generated with DocPipe and gemma4:31B_
