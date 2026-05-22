# Doc|Pipe Technical Documentation

## Overview
**Doc|Pipe** is a multi-threaded content generation pipeline designed to automate the creation of documents based on model configurations and job definitions. It scans a project directory for specific configuration files, resolves the required LLM (Large Language Model) settings, and executes content creation tasks in parallel.

## Architecture & Workflow

### 1. Initialization and Global Configuration
The process starts in the `DocPipeRunner`. Before processing individual jobs, the system checks for a global models configuration file via the `DotDPFilesService`. If found, these global models are loaded into the `DocPipeConfig` to serve as defaults for jobs that lack their own specific model definitions.

### 2. Job Discovery (`JobCreationService`)
The system recursively walks through the provided project directory to identify "DocPipe directories." A directory is considered a valid job location if it contains the required configuration files.

For every valid directory found, a `DPJob` is created:
- **Model Resolution**: The service looks for a `models.json` file.
    - If `models.json` exists: It loads the local model configurations and validates them.
    - If `models.json` is missing: It falls back to the global models loaded during initialization.
- **Content Definition**: The service looks for a `documents.json` file, which contains a list of `DPContentCreation` objects defining what needs to be generated.

### 3. Task Execution (`DocPipeRunner`)
Once all jobs are read, the `DocPipeRunner` flattens the hierarchy:
- It maps every `DPContentCreation` entry within every `DPJob` into a `ContentCreationTask`.
- These tasks are submitted to a fixed thread pool (`ExecutorService`) with a configurable number of threads (default: 5).
- The `ContentCreationService` then processes each task to generate the actual content.

### 4. Error Handling and Logging
The system utilizes a `DPLogService` to capture issues during the pipeline execution. If any errors with a level of `SEVERE` or higher are encountered during the run, the system logs a summary of all critical errors and terminates the process with an exit code of `1`.

## Data Models

### DPModelConfig
Defines the connection and behavior of the AI model:
- **Stereotype**: A unique identifier used to reference the model.
- **Model Name**: The specific LLM name to be used.
- **Server Type**: The provider (e.g., `ollama`, `gemini`, `openai`, or `custom`).
- **Parameters**: Includes `temperature`, `timeOutSeconds`, and a map of additional `args`.

### DPContentCreation
Defines the specific output requirements:
- **outputFile**: The destination path for the generated content.
- **stereotype**: The model stereotype to be used for this specific piece of content.
- **prompt**: The instruction sent to the LLM.
- **ps**: Additional post-script or supplementary information.

## Summary Flow
`DocPipeRunner` $\rightarrow$ `JobCreationService` (Scan Files) $\rightarrow$ `DPJob` (Resolve Models/Docs) $\rightarrow$ `ContentCreationTask` $\rightarrow$ `ExecutorService` (Parallel Execution) $\rightarrow$ `ContentCreationService`.

_This document was generated with Doc|Pipe and gemma4:31B_

