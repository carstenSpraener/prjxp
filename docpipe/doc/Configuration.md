# Doc|Pipe Technical Documentation

## Overview
Doc|Pipe is a document generation pipeline that automates the creation of content by scanning a project directory for configuration files, mapping them to LLM (Large Language Model) configurations, and executing content creation tasks in parallel.

## Architecture & Workflow

### 1. Initialization and Global Configuration
The process begins in the `DocPipeRunner`. It first attempts to load global model configurations:
- It checks for a global `models.json` file via the `DotDPFilesService`.
- If found, these models are loaded into the `DocPipeConfig` using the `ModelConfigLoader`. These serve as defaults if local configurations are missing.

### 2. Job Discovery (`JobCreationService`)
The system recursively scans the provided project directory to identify "Jobs":
- **Directory Scanning**: It walks the file tree looking for directories that contain a specific Doc|Pipe marker (via `hasDocPipeDir`).
- **Model Mapping**: For each identified directory, it looks for a `models.json`.
    - If a local `models.json` exists, it is parsed into a list of `DPModelConfig` and validated.
    - If no local file exists, the system falls back to the global models loaded during initialization.
- **Content Definition**: It looks for a `documents.json` file in the same directory. This file contains a list of `DPContentCreation` objects, which define what needs to be generated.

### 3. Task Execution
Once the jobs are read, the `DocPipeRunner` flattens the structure:
- Each `DPContentCreation` entry within a `DPJob` is wrapped into a `ContentCreationTask`.
- **Parallel Processing**: The runner utilizes a `FixedThreadPool` (defaulting to 5 threads) to execute these tasks concurrently.
- **Content Generation**: The `ContentCreationService` is invoked for each task to produce the actual output.

### 4. Error Handling and Logging
- **Validation**: The `DPModelConfig` is validated using Jakarta constraints (e.g., `@NotBlank` for `stereotype`, `modelName`, and `serverType`).
- **Logging**: A dedicated `DPLogService` captures messages.
- **Termination**: If any errors with a level of `SEVERE` or higher are recorded during the run, the system logs a summary of all severe errors and terminates with exit code `1`.

## Data Models

### DPModelConfig
Defines the LLM backend settings:
- **Stereotype**: The identifier used to link a content request to a specific model.
- **Model Details**: `modelName`, `modelProviderURL`, and `serverType` (e.g., ollama, gemini, openai).
- **Parameters**: `temperature`, `timeOutSeconds`, and a map of additional `args`.

### DPContentCreation
Defines the specific output requirement:
- **outputFile**: The destination path for the generated content.
- **stereotype**: The reference to the required `DPModelConfig`.
- **prompt**: The instruction sent to the LLM.
- **ps**: Additional post-script or supplementary information.

_This document was generated with Doc|Pipe and gemma4:31B_

