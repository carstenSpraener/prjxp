# DocPipe

![DocPipe](doc/images/docpipe.png)

DocPipe is a command-line tool designed to automate the generation of documentation and content by piping project data through Large Language Models (LLMs). It allows users to define specific "Jobs" that map content requirements to specific model configurations.

## 🚀 Getting Started

### Prerequisites
- Java Runtime Environment (JRE)
- A configured LLM server (e.g., Ollama)

### Configuration
DocPipe supports environment variables via a `.env` file located in the root directory. This is used to configure system properties and API keys required for the LLM services.

Create a `.env` file in the project root:
```env
# Example environment variables
LLM_API_KEY=your_key_here
LLM_SERVER_URL=http://localhost:11434
```

## 🛠 Usage

DocPipe operates as a CLI application. It parses arguments to locate a project directory, reads the job definitions, and executes the content creation process.

### How it Works
1. **Job Discovery**: The application scans the specified project directory for job definitions.
2. **Model Mapping**: Each job contains a list of `DPModelConfig` (defining the provider URL and server type, e.g., Ollama) and `DPContentCreation` requirements.
3. **Content Generation**: For every content item requested, DocPipe:
    - Identifies the correct LLM model based on the assigned `stereotype`.
    - Resolves the prompt and associated parameters.
    - Generates the content and writes it to the specified `outputFile`.

## 📋 Data Model

### Job Structure
A **Job** (`DPJob`) consists of:
- **Root Directory**: The base path for the project.
- **Model Configurations**: A list of available models, their providers, and their stereotypes.
- **Content Creation List**: A list of specific tasks to be performed.

### Content Creation (`DPContentCreation`)
Each content task is defined by:
- `outputFile`: The destination path for the generated text.
- `stereotype`: The category of the content (used to select the appropriate LLM model).
- `prompt`: The instruction for the LLM.
- `ps`: Additional post-script or supplementary instructions.

### Model Configuration (`DPModelConfig`)
- `stereotype`: The unique identifier for the model's role.
- `modelName`: The name of the LLM (e.g., `llama3`).
- `modelProviderURL`: The endpoint of the LLM server.
- `serverType`: The type of server (defaults to `ollama`).

## 💻 Execution Flow
`DocPipeCliApp` $\rightarrow$ `DocPipeArgsParser` $\rightarrow$ `DocPipeRunner` $\rightarrow$ `JobCreationService` $\rightarrow$ `ContentCreationService`
```
_This file was generated with gemma4:31b_
