# Projects

The **Projects** page is the central management view in the PRJXP Hub web UI. It lists every project that has been imported into your Hub instance, shows its current pipeline status, and provides the actions you need to keep the index current: reindexing, reimporting, embedding dependencies, and syncing from GitHub.

The page loads the project list on open and refreshes it every **10 seconds** so that counts, statuses, and live-update indicators stay current without manual reloading.

---

## Project List & Status

When the page loads, the component calls `getProjects()` and renders one row per project. Each row displays:

| Field | Description |
|---|---|
| **Name** | The project identifier (e.g. `project-a`). |
| **Status badge** | `READY` (green), `FAILED` (red), or a neutral badge for in-progress states such as `IMPORTING`. |
| **Chunk count** | Number of code chunks in the index. |
| **Embedding count** | Number of vectors stored for the project. |
| **Reindex progress bar** | Shown only while a reindex is actively running (see [Reindexing](#reindexing)). |

### Filtering

A search box at the top of the list filters projects by **name**. Matching is **case-insensitive** and uses substring inclusion:

```text
project-a   →  matches "project-a", "PROJECT-A", "my-project-a"
(empty)     →  shows all projects
```

### Status badges

| Status | Badge class | Meaning |
|---|---|---|
| `READY` | `status-ready` | Index is complete and queryable. |
| `FAILED` | `status-failed` | The last pipeline run did not finish successfully. |
| Any other (e.g. `IMPORTING`) | `status-default` | Pipeline is in progress. |

---

## Creating & Editing Projects

Both creation and editing open the **YAML editor dialog**. The dialog is pre-populated with the project's current configuration when editing; it starts blank when creating.

### Configuration fields

The YAML editor exposes the following fields (model `PrjxpYamlConfig`):

| Field | Type | Notes |
|---|---|---|
| `name` | string | Unique project identifier. |
| `rootDir` | string | Path to the source tree on the Hub host. |
| `chunoWhiteList` | string | Comma-separated list of file extensions to include (e.g. `java,ts`). |
| `tibedBatchSize` | number | Batch size for the embedding step. |
| `prompt` | string \| null | Optional custom system prompt for the RAG pipeline. |
| `fulltext` | boolean | Whether to include full-text search alongside vector search. |
| `embedDependencies` | boolean | Whether to also embed third-party library sources. |

### Save behaviour

- **New project** (`name` does not match any existing project): the component calls `createProject(config)`.
- **Existing project** (`name` matches a project already in the list): the component calls `updateProject(name, { rootDir, chunoWhiteList, tibedBatchSize, prompt, fulltext, embedDependencies })`.

After a successful save the project list is reloaded and the editor closes.

### Delete

Selecting a project and choosing **Delete** calls `deleteProject(name)`. On success the list is reloaded and the selection is cleared.

---

## Reindexing

Reindexing re-runs the full chunk → embed pipeline for a project. It is the primary way to pick up source-code changes after the initial import.

### Confirmation dialog

Clicking **Reindex** on a project row opens a confirmation dialog that displays the project name. You can:

- **Confirm** – triggers the reindex.
- **Cancel** – closes the dialog; no action is taken.

### Progress tracking

Once confirmed, the component:

1. Calls `reindexProject(name)`.
2. Starts a **2-second polling interval** on `getReindexStatus(name)`.
3. Updates the project row in the list with the returned `reindexStatus` and `reindexProgress` (0–100).
4. Renders a **progress bar** in the row while the status is one of the active values: `CHUNKING`, `EMBEDDING`, `IMPORTING`, or `DOCPIPE`.
5. **Stops polling** when the status reaches `COMPLETE` or `FAILED`.
6. Reloads the full project list to refresh counts.
7. After **5 seconds**, resets the row's `reindexStatus` to `IDLE` and `reindexProgress` to `0` so the UI returns to a clean state.

If you navigate away and return while a reindex is still running, `loadProjects()` detects the active status and **resumes polling** automatically.

### Reindex status values

| Status | Active? | Meaning |
|---|---|---|
| `IDLE` | No | No reindex in progress. |
| `IMPORTING` | Yes | Source files are being read. |
| `CHUNKING` | Yes | Code is being split into chunks. |
| `EMBEDDING` | Yes | Chunks are being vectorised. |
| `DOCPIPE` | Yes | Documentation pipeline is running. |
| `COMPLETE` | No | Pipeline finished successfully. |
| `FAILED` | No | Pipeline encountered an error. |

---

## GitHub Import & Update

### Import a new project from GitHub

1. Click **Import from GitHub** to open the import dialog.
2. Enter the **repository URL** (e.g. `https://github.com/foo/bar.git`) and, optionally, a **GitHub token** for private repositories.
3. Submit. The dialog **stays open** and shows a status indicator:

| Phase | What you see |
|---|---|
| `running` | The import request was accepted; the dialog shows a spinner. |
| `success` | A success message (e.g. "Imported bar") is displayed. The dialog **auto-closes after 1.5 s**. |
| `error` | An error message (e.g. "git clone exited with code 128") is displayed. The dialog **stays open** so you can retry. |

Behind the scenes the component polls `getGithubOperations()` every **2 seconds**, matching on the repository URL as the operation key. Polling stops when the operation status is no longer `PENDING`, or after a **300-second deadline** (matching the server-side `githubTimeoutSeconds` default). Transient network errors on the poll do not abort the loop.

If the initial `importGithub` request itself fails (e.g. a network error), the dialog immediately shows the error and remains open for retry.

### Update an existing GitHub-backed project

Select a project and choose **Update from GitHub**. The component calls `updateGithub(name)`, then polls `getGithubOperations()` (keyed by project name) every 2 seconds with the same 300-second deadline. The result is surfaced as a **toast notification**:

- **Success** toast: e.g. "Updated project-a".
- **Error** toast: e.g. "git pull exited with code 1".

The project list is reloaded after the request is accepted.

---

## Additional Actions

These actions operate on the **currently selected** project. If no project is selected, the action is a no-op.

| Action | Service call | Post-action behaviour |
|---|---|---|
| **Embed Dependencies** | `embedDependencies(name)` | Reloads the project list (the async materialisation registers new library chunks). |
| **Reimport** | `reimportProject(name)` | Reloads the project list. |
| **DocPipe** | `docpipeProject(name)` | Starts reindex-status polling (same 2 s interval as reindex). |

---

## Project Lifecycle at a Glance

```
┌─────────────────────────────────────────────────────────────────────┐
│                        IMPORT (initial)                            │
│  GitHub import / local import                                       │
│  status: IMPORTING  →  READY  |  FAILED                            │
└──────────────────────────────┬──────────────────────────────────────┘
                               │
                               ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        READY (steady state)                        │
│  chunkCount / embeddingCount populated, queryable                  │
│                                                                    │
│  Actions available:                                                │
│    • Reindex        →  CHUNKING → EMBEDDING → COMPLETE/FAILED      │
│    • Reimport       →  re-runs the import pipeline                 │
│    • Embed Deps     →  materialises & embeds library sources       │
│    • DocPipe        →  runs the documentation pipeline             │
│    • Update (GitHub)→  git pull + reindex                          │
│    • Edit YAML      →  update config, reindex on next run          │
│    • Delete         →  removes the project and its index           │
└─────────────────────────────────────────────────────────────────────┘
```

The **backend is the source of truth** for all statuses and counts. The UI polls at fixed intervals (10 s for the list, 2 s for reindex and GitHub operations) and never mutates state locally beyond the progress bar and status badge.

_This document was generated with Doc|Pipe._

