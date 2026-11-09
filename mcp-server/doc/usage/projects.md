# Projects

The **Projects** page is the central hub for managing every codebase you have imported into PRJXP. It displays a list of projects with their current status, lets you create and edit project configurations, and exposes the full reindexing and import pipeline.

The page loads automatically when you open it and refreshes the project list every **10 seconds** so that counts and live-update indicators stay current.

## Project Status & Lifecycle

Each project carries two independent status fields:

| Field | Values | Meaning |
|---|---|---|
| `status` | `READY`, `FAILED`, `IMPORTING`, … | Overall project health. `READY` means the project is fully indexed and queryable. `FAILED` means the last pipeline run did not complete. |
| `reindexStatus` | `IDLE`, `IMPORTING`, `CHUNKING`, `EMBEDDING`, `DOCPIPE`, `COMPLETE`, `FAILED` | Tracks the current reindex pipeline phase. `IDLE` means no reindex is in progress. |

The UI renders a **status badge** next to each project name:

- `READY` → green badge (`status-ready`)
- `FAILED` → red badge (`status-failed`)
- Any other value → neutral badge (`status-default`)

Each project also reports two counts:

- **`chunkCount`** – number of code chunks produced by the chunker.
- **`embeddingCount`** – number of vectors stored in the embedding index.

When a reindex is actively running (status is `IMPORTING`, `CHUNKING`, `EMBEDDING`, or `DOCPIPE`), a **progress bar** appears in the project row, filled to the current `reindexProgress` percentage (0–100).

## Managing Projects

### Creating a project

1. Click **Create Project** in the project list.
2. The YAML editor dialog opens with an empty form.
3. Fill in the configuration fields (see below) and click **Save**.
4. The new project appears in the list and the pipeline begins.

### Editing a project

1. Select a project in the list (click its row).
2. Click **Edit**.
3. The YAML editor opens pre-populated with the project's current configuration.
4. Modify any field and click **Save**. The project is updated in place.

### YAML configuration fields

| Field | Type | Description |
|---|---|---|
| `name` | string | Unique project identifier. |
| `rootDir` | string | Filesystem path to the project's source root. |
| `chunoWhiteList` | string | Comma-separated list of file extensions or languages to include (e.g. `java,ts`). |
| `tibedBatchSize` | number | Batch size used by the embedding step. |
| `prompt` | string \| null | Optional custom prompt override for the RAG pipeline. |
| `fulltext` | boolean | Whether to include full-text search alongside vector search. |
| `embedDependencies` | boolean | Whether to also embed third-party dependency sources. |

### Deleting a project

1. Select the project.
2. Click **Delete**.
3. The project is removed from the list. If it was the currently selected project, the selection is cleared.

## Reindexing

Reindexing re-runs the full pipeline (import → chunk → embed) for an existing project. It is the primary way to pick up source-code changes.

### Triggering a reindex

1. Select a project.
2. Click **Reindex**.
3. A **confirmation dialog** appears showing the project name.
4. Click **Confirm** to start, or **Cancel** to dismiss.

### Progress tracking

After confirmation the UI begins **polling the reindex status every 2 seconds**. The project row in the list updates in real time:

- The `reindexStatus` field cycles through `IMPORTING` → `CHUNKING` → `EMBEDDING` (and `DOCPIPE` if applicable).
- The `reindexProgress` bar fills from 0 % to 100 %.
- When the status reaches `COMPLETE` or `FAILED`, polling stops and the project list is refreshed to pick up the final counts.
- Five seconds after completion the status resets to `IDLE` and the progress bar disappears, returning the row to a clean state.

If you navigate away and come back (or reload the page), the component detects any project whose `reindexStatus` is still active and **resumes polling** automatically.

## GitHub Import & Update

PRJXP can clone a repository directly from GitHub and keep it up to date.

### Importing a new repository

1. Click **Import from GitHub**.
2. Enter the repository **URL** (e.g. `https://github.com/foo/bar.git`) and, optionally, a **personal access token** for private repositories.
3. Click **Import**.

The dialog stays open and shows the operation status:

| Phase | What you see |
|---|---|
| `running` | The request has been sent; the UI polls the operation tracker every 2 s. |
| `success` | A success message (e.g. "Imported bar") is displayed. The dialog **auto-closes after 1.5 s**. |
| `error` | An error message (e.g. "git clone exited with code 128") is displayed. The dialog **stays open** so you can retry. |

If the initial HTTP request itself fails (network error, bad URL), the dialog immediately shows the error and remains open for retry.

The operation tracker has a **300-second deadline**. If the operation has not resolved by then, the UI reports "Timed out waiting for the GitHub operation" and the dialog stays open.

### Updating an existing GitHub-backed project

1. Select the project.
2. Click **Update from GitHub**.

The UI polls the operation tracker every 2 s. When the operation resolves:

- **Success** → a green toast appears (e.g. "Updated project-a").
- **Failure** → a red toast appears with the error message (e.g. "git pull exited with code 1").

## Additional Operations

Beyond reindexing, the project detail card exposes two further pipeline actions. Both operate on the currently selected project and do nothing if no project is selected.

| Action | What it does |
|---|---|
| **Embed Dependencies** | Triggers the embedding step for third-party dependency sources (when `embedDependencies` is enabled in the project config). The project list refreshes once the materialization registers the libraries. |
| **Reimport** | Re-runs the import phase for the project (re-clones or re-scans the source directory) and refreshes the list. |
| **DocPipe** | Runs the documentation-pipeline phase. After the request is accepted the UI starts polling the reindex status, the same way it does for a full reindex. |

## Search & Filtering

A **search box** at the top of the project list filters projects by name.

- Matching is **case-insensitive** (`PROJECT-A` matches `project-a`).
- The filter is a substring match: typing `proj` matches any project whose name contains `proj`.
- Clearing the filter restores the full list.

The filtered list is a derived view; the underlying project data is unchanged.

_This document was generated with Doc|Pipe._

