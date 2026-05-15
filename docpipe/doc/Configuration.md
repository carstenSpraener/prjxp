# Doc|Pipe

Doc|Pipe is a document generation pipeline that automates the creation of content based on model configurations and job definitions discovered within a project directory structure.

## How it Works

The pipeline operates in three main phases: Configuration Loading, Job Discovery, and Content Execution.

### 1. Configuration Loading
The process begins in the `DocPipeRunner`. It first attempts to load a global configuration file:
- **Global Models**: It looks for a `models.json` file located in the project directory under the `.docpipe` (defined by `DocPipeConfig.DP_DIR`) folder.
- If found, these models are loaded into the global configuration to serve as defaults for jobs that do not have their own specific model definitions.

### 2. Job Discovery (`JobCreationService`)
The `JobCreationService` scans the project directory recursively to identify "Jobs". A directory is considered a job location if it contains a `.docpipe` folder.

For every identified job directory, the service performs the following:
- **Model Resolution**: 
    - It looks for a local `models.json`.
    - If a local file exists, it uses those specific model configurations.
    - If no local file exists, it falls back to the **Global Models** loaded in the first phase.
- **Content Definition**:
    - It reads a `documents.json` file which contains a list of `DPContentCreation` objects.
    - Each object defines what needs to be created (output file, prompt, stereotype, and post-script/ps).

### 3. Content Execution
Once the jobs are read and the content lists are extracted, the `DocPipeRunner` flattens these jobs into individual `ContentCreationTask` objects.

Each task is then passed to the `ContentCreationService`, which handles the actual generation of the content based on the resolved model and the specific prompt provided in the job configuration.

## Data Models

### DPModelConfig
Defines the AI backend settings:
- **Stereotype**: The role or type of model.
- **Provider URL**: The endpoint of the model server.
- **Server Type**: Defaults to `ollama`.
- **Parameters**: Includes `temperature` (default 0.2) and `timeOutSeconds` (default 60).

### DPContentCreation
Defines the specific document to be generated:
- **outputFile**: The destination path for the generated content.
- **stereotype**: The model stereotype to be used for this specific piece of content.
- **prompt**: The instruction sent to the model.
- **ps**: Additional post-script or context.

## Summary Flow
`DocPipeRunner` $\rightarrow$ `ModelConfigLoader` (Global) $\rightarrow$ `JobCreationService` (Scan Directories $\rightarrow$ Load `models.json` $\rightarrow$ Load `documents.json`) $\rightarrow$ `ContentCreationService` (Generate Files).

_This document was generated with Doc|Pipe and gemma4:31B_
