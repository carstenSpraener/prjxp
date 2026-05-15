```markdown
# Doc|Pipe Technical Documentation

## Overview
Doc|Pipe is a document generation pipeline that automates the creation of content based on predefined model configurations and job definitions. It scans a project directory for specific configuration files, resolves the appropriate AI models, and executes content creation tasks.

## Workflow Architecture

### 1. Initialization and Global Configuration
The process begins in the `DocPipeRunner`. Before processing jobs, the system checks for a global configuration file:
- It uses `DotDPFilesService` to locate the global `models.json`.
- If found, `ModelConfigLoader` loads these global model definitions into the `DocPipeConfig`.
- These global models serve as a fallback if local directory configurations are missing.

### 2. Job Discovery (`JobCreationService`)
The system performs a recursive scan of the provided project directory:
- **Directory Walking**: It walks through the file system searching for directories that qualify as "DocPipe directories" (via `dpFilesService.hasDocPipeDir`).
- **Job Creation**: For every valid directory found, a `DPJob` object is instantiated.

### 3. Configuration Resolution
For each discovered job, the system resolves two primary components:

#### A. Model Configuration (`DPModelConfig`)
The system determines which AI model to use in the following order of priority:
1. **Local**: Looks for a `models.json` within the specific job directory.
2. **Global**: If no local file exists, it falls back to the global models loaded during initialization.
3. **Failure**: If neither is available, a warning is logged.

The `DPModelConfig` defines the technical parameters for the AI provider, including:
- `serverType` (e.g., "ollama")
- `modelProviderURL` and `modelName`
- `temperature` and `timeOutSeconds`
- `stereotype` (used to map specific model roles to tasks)

#### B. Content Definitions (`DPContentCreation`)
The system looks for a `documents.json` file in the job directory. This file contains a list of content creation requirements, specifying:
- `outputFile`: The destination path for the generated content.
- `stereotype`: The required model type/role.
- `prompt`: The instruction for the AI.
- `ps`: Additional post-script or context.

### 4. Execution Pipeline
Once the jobs are read and configurations are resolved, the `DocPipeRunner` flattens the structure:
1. **Task Mapping**: Each `DPContentCreation` entry within a `DPJob` is wrapped into a `ContentCreationTask`.
2. **Processing**: The `ContentCreationService` iterates through these tasks and executes the `createContent` method to generate the final documents.

## Data Model Summary

| Class | Responsibility | Key Fields |
| :--- | :--- | :--- |
| `DPJob` | Represents a processing unit tied to a directory | `rootDir`, `modelConfigs`, `contentCreationList` |
| `DPModelConfig` | Technical AI model settings | `modelName`, `serverType`, `temperature`, `stereotype` |
| `DPContentCreation` | Definition of a single document to be created | `outputFile`, `prompt`, `stereotype` |
| `DocPipeRunner` | Orchestrates the entire flow | `run()` |
```

_This document was generated with Doc|Pipe and gemma4:31B_
