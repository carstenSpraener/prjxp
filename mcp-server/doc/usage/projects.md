# Projects

The **Projects** page is the central hub for managing every codebase you have imported into PRJXP. It shows a list of projects with their current status, lets you create, edit, and delete them, and exposes the full reindexing and GitHub workflows.

The page loads the project list on open and refreshes it every **10 seconds** so that counts, statuses, and live-update indicators stay current without manual reloading.

## Viewing Projects

When you open the Projects page, the component calls `getProjects()` and renders every project as a row. Each row displays:

| Field | Description |
|---|---|
| **Name** | The project identifier (e.g. `project-a`). |
| **Status badge** | `READY` (green), `FAILED` (red), or a neutral badge for in-progress states such as `IMPORTING`. |
| **Chunk count** | Number of code chunks in the index. |
| **Embedding count** | Number of vectors stored for the project. |
| **Reindex progress bar** | Shown only while a reindex is actively running (see [Reindexing](#reindexing)). |

Click a row to **select** the project. The selected project drives the action buttons in the detail card (reindex, embed dependencies, reimport, update from GitHub, docpipe).

### Filtering

A search box at the top of the list filters projects by name. The match is **case-insensitive** and uses substring matching:

- Typing `project-a` matches `project-a`.
- Typing `PROJECT-A` also matches `project-a`.
- An empty filter shows all projects.

## Project Status and Lifecycle

Each project carries two independent status dimensions:

### Overall status (`status`)

| Value | Badge class | Meaning |
|---|---|---|
| `READY` | `status-ready` | The project is fully indexed and queryable. |
| `FAILED` | `status-failed` | The import or indexing pipeline failed. |
| `IMPORTING` | `status-default` | The project is still being cloned / imported. |
| *(any other)* | `status-default` | In-progress or transitional state. |

### Reindex status (`reindexStatus`)

| Value | Active? | Meaning |
|---|---|---|
| `IDLE` | No | No reindex in progress. |
| `IMPORTING` | Yes | Source files are being re-read. |
| `CHUNKING` | Yes | Code is being split into chunks. |
| `EMBEDDING` | Yes | Chunks are being vectorised. |
| `DOCPIPE` | Yes | Documentation pipeline is running. |
| `COMPLETE` | No | Reindex finished successfully. |
| `FAILED` | No | Reindex failed. |

When a reindex is **active** (any of the four "Yes" values above), the project row renders a progress bar whose width tracks `reindexProgress` (0–100 %). Idle, complete, and failed projects show no bar.

## Creating and Editing Projects

Both creation and editing use the **YAML editor dialog**.

### Creating a new project

1. Click **Create Project**. The editor opens with a blank form.
2. Fill in the configuration fields (see table below).
3. Click **Save**. The component calls `createProject(config)`, then reloads the list and closes the dialog.

### Editing an existing project

1. Select a project in the list.
2. Click **Edit**. The editor opens pre-populated with the project's current values.
3. Modify any field and click **Save**. The component calls `updateProject(name, …)` with the changed fields, then reloads the list and closes the dialog.

### YAML configuration fields

| Field | Type | Purpose |
|---|---|---|
| `name` | string | Unique project identifier. |
| `rootDir` | string | Filesystem path to the project root. |
| `chunoWhiteList` | string | Comma-separated list of languages to index (e.g. `java,ts`). |
| `tibedBatchSize` | number | Batch size for the embedding pipeline. |
| `prompt` | string \| null | Optional custom system prompt for the RAG model. |
| `fulltext` | boolean | Whether to include full-text search alongside vector search. |
| `embedDependencies` | boolean | Whether to also embed third-party library sources. |

When saving an **existing** project, the component sends `rootDir`, `chunoWhiteList`, `tibedBatchSize`, `prompt` (or `null`), `fulltext`, and `embedDependencies`. When creating a **new** project, the full `PrjxpYamlConfig` object is sent.

### Deleting a project

Select a project and click **Delete**. The component calls `deleteProject(name)`, reloads the list, and clears the selection if the deleted project was the one you had selected.

## Reindexing

Reindexing re-runs the full pipeline (import → chunk → embed) for an existing project. It is a **two-step confirmation** flow:

1. Click **Reindex** on the selected project. A confirmation dialog appears showing the project name.
2. **Confirm** – the dialog closes, `reindexProject(name)` is called, and the UI begins **polling** `getReindexStatus(name)` every **2 seconds**.
   - Each poll updates the project's `reindexStatus` and `reindexProgress` in the list, so you see the progress bar advance.
   - Polling stops automatically when the status becomes `COMPLETE` or `FAILED`.
   - After a 5-second delay following completion, the status resets to `IDLE` and progress to 0, returning the row to a clean state.
   - The project list is reloaded once after the pipeline finishes so that chunk and embedding counts are refreshed.
3. **Cancel** – the dialog closes and the project name is cleared. No reindex is started.

If you navigate away and return to the Projects page while a reindex is still running, `loadProjects()` detects the active status and **resumes polling** automatically.

## GitHub Import and Update

### Importing a repository

1. Click **Import from GitHub**. A dialog opens with two fields:
   - **URL** – the repository URL (e.g. `https://github.com/foo/bar.git`).
   - **Token** – optional; supply a GitHub personal access token for private repositories.
2. Click **Import**. The dialog stays open and shows a **running** indicator.
3. The component calls `importGithub(url, token)`, then begins **polling** `getGithubOperations()` every **2 seconds** (up to a **300-second** deadline).
   - **Success** – the dialog shows a success message (e.g. "Imported bar") and **auto-closes after 1.5 seconds**. The project list is reloaded.
   - **Failure** – the dialog shows the error message (e.g. "git clone exited with code 128") and **stays open** so you can retry.
   - **Request error** – if the initial `importGithub` call itself fails, the dialog shows the error and stays open.
4. You can also **close** the dialog at any time; this stops polling and resets the status to idle.

### Updating a GitHub-backed project

Select a project that was imported from GitHub and click **Update from GitHub**. The component calls `updateGithub(name)`, then polls the GitHub operations endpoint (same 2-second / 300-second mechanism). The result is shown as a **toast notification**:

- **Success** – a green toast with the operation message (e.g. "Updated project-a").
- **Failure** – a red toast with the error message (e.g. "git pull exited with code 1").

The project list is reloaded after the update request is accepted.

## Additional Actions

These actions operate on the **currently selected** project. If no project is selected, the button is a no-op.

| Action | Service call | What happens after |
|---|---|---|
| **Embed Dependencies** | `embedDependencies(name)` | The project list is reloaded so newly materialised library chunks appear. |
| **Reimport** | `reimportProject(name)` | The project list is reloaded. |
| **DocPipe** | `docpipeProject(name)` | Polling for the doc-phase status begins (same 2-second mechanism as reindex). |

All three actions require a project to be selected first. The component checks `selectedProject()` and returns early if it is `null`.

## Cleanup and Polling Lifecycle

The component manages three independent timers, all of which are cleaned up when you leave the page (`ngOnDestroy`):

| Timer | Interval | Stopped when |
|---|---|---|
| **List refresh** | 10 s | Component destroyed. |
| **Reindex poll** | 2 s | Status reaches `COMPLETE` or `FAILED`; component destroyed. |
| **GitHub operation poll** | 2 s | Operation resolves (success or failure); 300 s deadline elapses; dialog closed; component destroyed. |

Transient network errors during a GitHub poll do **not** stop the poller; the next tick simply retries. The 300-second deadline covers the server-side `githubTimeoutSeconds` default and prevents indefinite polling if the operation never resolves.

_This document was generated with Doc|Pipe._

