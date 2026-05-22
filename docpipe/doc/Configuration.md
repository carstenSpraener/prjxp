# Doc|Pipe

Doc|Pipe is a document generation pipeline designed to automate the creation of content based on model configurations and job definitions. It scans project directories for specific configuration files and processes content creation tasks in parallel.

## How it Works

### 1. Initialization and Global Configuration
The process starts in the `DocPipeRunner`. Before processing jobs, the system checks for a global `models.json` file via the `DotDPFilesService`. If found, these global model configurations are loaded into the system to serve as defaults for jobs that do not define their own models.

### 2. Job Discovery
The `JobCreationService` scans the provided project directory recursively. It identifies "DocPipe directories" (folders containing specific markers) and converts each valid directory into a `DPJob`.

For each discovered directory, the service looks for two primary JSON files:
- **`models.json`**: Defines the AI models to be used. If this file is missing, the system falls back to the global models loaded during initialization.
- **`documents.json`**: Defines the specific content creation tasks (output files, prompts, and stereotypes) to be executed.

### 3. Task Mapping
Once the jobs are read, the `DocPipeRunner` flattens the structure. It maps every `DPContentCreation` entry within every `DPJob` into a `ContentCreationTask`. This decouples the file-system job structure from the actual execution units.

### 4. Parallel Execution
To optimize performance, Doc|Pipe uses a fixed thread pool (configurable via `prjxp.docpipe.maxthreads`, defaulting to 5). Each `ContentCreationTask` is submitted to the `ExecutorService`, where the `ContentCreationService` handles the actual generation of the content.

## Data Models

### DPModelConfig
Defines the AI backend and parameters:
- **Provider Details**: `modelName`, `modelProviderURL`, `kiChatImpl`, and `serverType` (default: "ollama").
- **Hyperparameters**: `temperature` (default: 0.2) and `timeOutSeconds` (default: 60).
- **Additional Args**: A map of flexible arguments for the model.

### DPContentCreation
Defines what needs to be generated:
- **`outputFile`**: The destination path for the generated content.
- **`stereotype`**: Links the task to a specific `DPModelConfig`.
- **`prompt`**: The instruction sent to the AI.
- **`ps`**: Additional post-script or context.

## Workflow Summary
`DocPipeRunner` $\rightarrow$ `JobCreationService` (Scan Folders) $\rightarrow$ `DPJob` (Load JSONs) $\rightarrow$ `ContentCreationTask` (Flatten) $\rightarrow$ `ExecutorService` (Parallel Run) $\rightarrow$ `ContentCreationService` (Generate)

_This document was generated with Doc|Pipe and gemma4:31B_

