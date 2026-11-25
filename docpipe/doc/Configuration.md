# DocPipe

DocPipe is a documentation generation pipeline that discovers, configures, and executes LLM-driven content creation tasks in parallel across a project tree.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                        DocPipeRunner                                │
│  (orchestrator: job discovery → task fan-out → parallel execution)  │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  ┌───────────────────────┐    ┌──────────────────────────────────┐  │
│  │  JobCreationService   │    │   ContentCreationService         │  │
│  │  (discovers .docpipe  │───▶│   (executes a single task:       │  │
│  │   dirs, parses        │    │    prompt → LLM → filters →      │  │
│  │   documents.json,     │    │    output file)                  │  │
│  │   expands forEach)    │    └──────────────────────────────────┘  │
│  └───────────────────────┘                                         │
│           │                                                         │
│           ▼                                                         │
│  ┌───────────────────────┐    ┌──────────────────────────────────┐  │
│  │  DotDPFilesService    │    │   DocPipeManifestWriter          │  │
│  │  (locates .docpipe/   │    │   (writes output manifest for    │  │
│  │   documents.json)     │    │    hub embedding)                │  │
│  └───────────────────────┘    └──────────────────────────────────┘  │
│                                                                     │
│  ┌───────────────────────┐                                         │
│  │  PxLogService         │  (central error/severity tracking)      │
│  └───────────────────────┘                                         │
└─────────────────────────────────────────────────────────────────────┘
```

## How It Works

### 1. Job Discovery

`JobCreationService.readJobs()` walks the project's root directory tree and identifies every sub-directory that contains a `.docpipe` folder (checked via `DotDPFilesService.hasDocPipeDir`). Each such directory becomes a **`DPJob`**.

### 2. Configuration Parsing

For every discovered `.docpipe` directory, the service reads the `documents.json` file (located by `DotDPFilesService.getDocumentsJsonFrom`). The JSON is deserialised into a list of **`DPContentCreation`** objects.

### 3. `forEach` Expansion

Each `DPContentCreation` entry may declare a `forEach` glob pattern. When present, the service:

1. Walks the job's root directory.
2. Matches every regular file against the glob (relative to the job root).
3. For each match, clones the `DPContentCreation` and:
   - Sets `args["currentFile"]` to the slash-separated relative path.
   - Computes the output file name by stripping the extension from the matched file and appending `outputFile` (and optionally prefixing with `outputDir`).
   - Adjusts `storePrompt` the same way if it is set.

The result is one concrete task per matched file. Entries **without** a `forEach` pass through as a single task.

### 4. Task Execution (Parallel)

`DocPipeRunner` flattens all jobs into a flat list of `ContentCreationTask` objects and submits each to a **fixed thread pool** (size configurable via `prjxp.docpipe.maxthreads`, default **5**). Each task is handed to `ContentCreationService.createContent(task)`, which:

- Resolves the prompt template (substituting `args` such as `currentFile`).
- Invokes the LLM using the configured `stereotype`.
- Applies any `filterList` post-processing.
- Appends the optional `ps` (post-script).
- Writes the result to the resolved `outputFile` path.

### 5. Error Handling & Exit

| Mode | Trigger | Behaviour |
|------|---------|-----------|
| **Standalone** – `run(PrjXPConfig)` | Any `SEVERE` log entry after the pool drains | Logs a summary of all severe messages, calls `System.exit(1)`. |
| **Hub in-process** – `runForProject(ProjectDefinition)` | Any recorded failure in `DocPipeRunResult` | Throws `DocPipeRunException` with a joined summary. Never calls `System.exit`. |

In hub mode a **1-hour timeout** guards against hung tasks (e.g. an unresponsive LLM). On timeout a `DocPipeRunException` is thrown.

### 6. Manifest Writing (Hub Mode Only)

After the executor shuts down (or times out), `DocPipeManifestWriter.write(pd, result.outputs())` is called in a `finally` block so that **every** produced document is recorded, even on partial failure. A write failure is logged as a warning and does not mask the run's own outcome.

## Project Directory Layout

```
my-project/
├── src/
│   ├── .docpipe/
│   │   └── documents.json        ← job configuration
│   ├── module-a/
│   │   ├── Foo.java
│   │   └── Bar.java
│   └── module-b/
│       └── Baz.java
├── docs/                         ← generated output (example)
│   ├── module-a/
│   │   ├── Foo.md
│   │   └── Bar.md
│   └── module-b/
│       └── Baz.md
└── ...
```

A directory is a DocPipe job **iff** it contains a `.docpipe/` sub-directory with a valid `documents.json`.

## `documents.json` Schema

```json
[
  {
    "forEach": "src/**/*.java",
    "outputDir": "docs",
    "outputFile": ".md",
    "stereotype": "code-explainer",
    "prompt": "Explain the purpose and public API of {{currentFile}}.",
    "ps": "\n---\n_Generated by DocPipe._",
    "filterList": "remove-blank-lines,trim-trailing-spaces",
    "storePrompt": ".prompt.md",
    "args": {
      "audience": "new developers"
    }
  },
  {
    "outputFile": "README.md",
    "stereotype": "doc-writer",
    "prompt": "Write a project overview for {{currentFile}}."
  }
]
```

| Field | Required | Description |
|-------|----------|-------------|
| `forEach` | no | Glob pattern (relative to job root). When set, one task is created per matched file. |
| `outputFile` | yes | File name or suffix written to disk. With `forEach`, the matched file's base name is prepended. |
| `outputDir` | no | Sub-directory (relative to job root) for the output. |
| `stereotype` | yes | LLM model / prompt-stereotype identifier. |
| `prompt` | yes | Prompt template; `{{key}}` placeholders are resolved from `args`. |
| `ps` | no | Post-script text appended after the LLM output. |
| `filterList` | no | Comma-separated filter names applied to the generated text. |
| `storePrompt` | no | Optional path to persist the resolved prompt alongside the output. |
| `args` | no | Key-value map injected into the prompt template. `currentFile` is set automatically during `forEach` expansion. |

## Configuration Properties

| Property | Default | Description |
|----------|---------|-------------|
| `prjxp.docpipe.maxthreads` | `5` | Size of the fixed thread pool used for parallel task execution. |

## Execution Flow (Sequence)

```
DocPipeRunner.run(cfg)
│
├─▶ JobCreationService.readJobs(activeProject)
│     │
│     ├─ Files.walk(rootDir)
│     ├─ filter: has .docpipe/ ?
│     ├─ read .docpipe/documents.json
│     ├─ deserialize → List<DPContentCreation>
│     └─ expand forEach globs → N concrete tasks per entry
│
├─▶ flatMap → List<ContentCreationTask>
│
├─▶ ExecutorService (fixed pool, maxThreads)
│     │
│     ├─ task 1 → ContentCreationService.createContent(task)
│     ├─ task 2 → ContentCreationService.createContent(task)
│     ├─ ...
│     └─ task N → ContentCreationService.createContent(task)
│
├─▶ pool drains (try-with-resources)
│
└─▶ if logService.maxLevel() ≥ SEVERE
      ├─ log summary
      └─ System.exit(1)
```

## Hub In-Process Mode

When DocPipe is invoked from the PrjXP hub (via `runForProject`), the following differences apply:

- **No `System.exit`** – failures surface as a `DocPipeRunException`.
- **`DocPipeRunResult`** accumulates per-task outputs and failures in-memory.
- **Manifest is always written** (in `finally`), so the hub can embed whatever documents were produced, even on partial failure or timeout.
- **1-hour hard timeout** on `executor.awaitTermination` protects against hung LLM calls.

_This document was generated with Doc|Pipe and gemma4:31B_

