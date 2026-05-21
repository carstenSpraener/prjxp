# Doc|Pipe Documentation

## Overview
Doc|Pipe is a document generation pipeline that automates the creation of content based on predefined model configurations and job definitions. It scans a project directory for specific configuration files, resolves the necessary AI model settings, and executes content creation tasks.

## How it Works

### 1. Initialization and Global Configuration
The process starts in the `DocPipeRunner`. Before processing jobs, the system checks for a global `models.json` file via the `DotDPFilesService`. If found, these global model configurations are loaded into the main configuration to serve as defaults for any job that lacks its own specific model definitions.

### 2. Job Discovery
The `JobCreationService` performs a recursive walk through the provided project root directory. It identifies "DocPipe directories" based on specific criteria:
- It filters for directories that are recognized as DocPipe-enabled.
- For every valid directory found, it attempts to create a `DPJob`.

### 3. Job Configuration Loading
For each identified directory, the system looks for two primary JSON configuration files:

#### A. Model Configuration (`models.json`)
- The system looks for a local `models.json` within the job directory.
- **Priority:** Local `models.json` $\rightarrow$ Global Models $\rightarrow$ Warning (if neither exists).
- The `DPModelConfig` object stores details such as:
    - `modelProviderURL` and `serverType` (e.g., "ollama").
    - `kiChatImpl` (the implementation of the AI chat).
    - Hyperparameters like `temperature` and `timeOutSeconds`.

#### B. Content Definition (`documents.json`)
- The system reads `documents.json` to determine what needs to be created.
- This file is mapped to a list of `DPContentCreation` objects, which define:
    - `outputFile`: Where the result should be saved.
    - `stereotype`: The type of content/model to use.
    - `prompt`: The instruction for the AI.
    - `ps`: Additional parameters or post-scripts.

### 4. Execution Pipeline
Once the jobs are loaded, the `DocPipeRunner` flattens the structure:
1. **Mapping:** Each `DPContentCreation` entry within a `DPJob` is wrapped into a `ContentCreationTask`.
2. **Processing:** The `ContentCreationService` iterates through these tasks and executes the `createContent` method to generate the final documents.

## Data Model Summary

| Class | Responsibility |
| :--- | :--- |
| `DocPipeRunner` | Orchestrates the overall flow from config loading to execution. |
| `JobCreationService` | Scans the filesystem and parses JSON files into Job objects. |
| `DPJob` | Represents a specific directory containing model and content definitions. |
| `DPModelConfig` | Defines the AI backend settings (URL, Server Type, Temperature). |
| `DPContentCreation` | Defines the specific output file and the prompt to be used. |

_This document was generated with Doc|Pipe and gemma4:31B_

