# Projects

The **Projects** page is the central management view in the PRJXP Hub web UI. It lists every project imported into your Hub, lets you create and edit project configurations, triggers reindexing and other maintenance operations, and tracks the progress of long-running pipelines in real time.

The page loads the full project list on open and refreshes it every **10 seconds** so that counts, statuses, and live-update indicators stay current without manual reloading.

---

## Viewing Projects

When you open the Projects page, the component calls `getProjects()` and renders a list of `ProjectInfo` entries. Each entry displays:

| Field | Description |
|---|---|
| **Name** | The project identifier (e.g. `project-a`). |
| **Status badge** | A colour-coded pill: green for `READY`, red for `FAILED`, neutral for all other statuses (e.g. `IMPORTING`). |
| **Chunk count** | Number of code chunks produced by the last successful index. |
| **Embedding count** | Number of vectors stored for the project. |
| **Reindex progress bar** | Shown only while a reindex is actively running (see [Reindexing](#reindexing)). |

### Search filter

A text box at the top of the list filters projects by name. The match is **case-insensitive** and uses substring matching:

- Typing `project-a` matches `project-a`.
- Typing `PROJECT-A` also matches `project-a`.
- An empty filter shows all projects.

### Selecting a project

Clicking a project row sets it as the **selected project** and opens the **Project Detail Card** panel, which exposes the action buttons described in the sections below.

---

## Creating and Editing Projects

Both creation and editing use the same **YAML Editor Dialog**.

### Create

1. Click **Create Project**.
2. The editor opens with a blank configuration.
3. Fill in the fields and save.

The component calls `createProject(config)` with the full `PrjxpYamlConfig` object, then reloads the project list and closes the editor.

### Edit

1. Select a project in the list.
2. Click **Edit**.
3. The editor opens pre-populated with the project's current configuration.
4. Modify the fields and save.

The component calls `updateProject(name, { rootDir, chunoWhiteList, tibedBatchSize, prompt, fulltext, embedDependencies })`, then reloads the list and closes the editor.

### Configuration fields

| Field | Type | Example | Purpose |
|---|---|---|---|
| `name` | string | `my-service` | Unique project identifier. |
| `rootDir` | string | `/opt/src/my-service` | Filesystem path to the project source. |
| `chunoWhiteList` | string | `java,ts` | Comma-separated list of file extensions to include when chunking. |
| `tibedBatchSize` | number | `32` | Batch size for the embedding (Tibed) pipeline. |
| `prompt` | string \| null | *(custom RAG prompt)* | Optional system prompt override for the RAG model. |
| `fulltext` | boolean | `true` | Whether to include full-text search alongside vector search. |
| `embedDependencies` | boolean | `true` | Whether to also embed third-party library dependencies. |

---

## Reindexing

Reindexing re-runs the full chunk → embed pipeline for a project. Because it can take minutes, the UI tracks progress with a confirmation dialog and a live progress bar.

### Triggering a reindex

1. Select a project.
2. Click **Reindex**.
3. A **confirmation dialog** appears showing the project name.
   - **Confirm** – closes the dialog, calls `reindexProject(name)`, and starts a 2-second polling loop on `getReindexStatus(name)`.
   - **Cancel** – closes the dialog and clears the pending name; no API call is made.

### Progress tracking

While the reindex is running, the component polls `getReindexStatus(name)` every **2 seconds** and updates the project's `reindexStatus` and `reindexProgress` fields in the list.

| `reindexStatus` value | Meaning | Progress bar shown? |
|---|---|---|
| `IDLE` | No reindex in progress. | No |
| `IMPORTING` | Source files are being imported. | Yes |
| `CHUNKING` | Source files are being split into chunks. | Yes |
| `EMBEDDING` | Chunks are being embedded. | Yes |
| `DOCPIPE` | Documentation pipeline is running. | Yes |
| `COMPLETE` | Pipeline finished successfully. | No (bar removed) |
| `FAILED` | Pipeline encountered an error. | No (bar removed) |

The progress bar's width is set to `reindexProgress` percent (e.g. `42%`).

### Completion and reset

- When the polled status becomes `COMPLETE` or `FAILED`, polling stops and the project list is reloaded to pick up the final counts.
- After **5 seconds**, the UI resets `reindexStatus` to `IDLE` and `reindexProgress` to `0` so the list returns to a clean state.

### Resuming after a page reload

If you navigate away and come back while a reindex is still running, `loadProjects()` detects any project whose `reindexStatus` is one of the active values (`IMPORTING`, `CHUNKING`, `EMBEDDING`, `DOCPIPE`) and automatically resumes the 2-second polling loop.

---

## GitHub Import and Update

PRJXP can clone a GitHub repository directly into the Hub and later pull updates.

### Import a new repository

1. Click **Import from GitHub**.
2. The **GitHub Import Dialog** opens with two fields:
   - **URL** – the repository URL (e.g. `https://github.com/foo/bar.git`).
   - **Token** – optional; supply a personal access token for private repositories.
3. Click **Import**.

The dialog **stays open** and transitions through the following states:

| `dialogStatus` | What you see |
|---|---|
| `running` | A spinner / "in progress" indicator. The component polls `getGithubOperations()` every 2 s. |
| `success` | A success message (e.g. *"Imported bar"*). The dialog **auto-closes after 1.5 s** and the project list reloads. |
| `error` | An error message (e.g. *"git clone exited with code 128"*). The dialog **stays open** so you can retry. |

If the initial `importGithub` request itself fails (network error, bad URL), the dialog immediately shows the error and remains open.

You can close the dialog at any time with the **Close** button; this stops the poll, resets the status to `idle`, and clears the message.

### Update an existing GitHub-backed project

1. Select a project that was imported from GitHub.
2. Click **Update from GitHub**.

The component calls `updateGithub(name)`, then polls `getGithubOperations()` every 2 s (same 300-second deadline as import). When the operation resolves:

- **Success** – a green toast appears (e.g. *"Updated project-a"*).
- **Failure** – a red toast appears with the error message (e.g. *"git pull exited with code 1"*).

The project list reloads after the request is accepted.

### Polling details

The GitHub operation poller:

- Polls every **2 seconds**.
- Stops when the operation's status is no longer `PENDING` (i.e. `SUCCESS` or `FAILED`).
- Enforces a **300-second deadline** (matching the server-side `githubTimeoutSeconds` default). If the deadline passes without a resolution, the callback fires with the message *"Timed out waiting for the GitHub operation"*.
- Tolerates transient poll failures (network blips) by simply continuing to poll.

---

## Additional Actions

With a project selected, the detail card exposes further maintenance actions:

| Action | What it does |
|---|---|
| **Embed Dependencies** | Calls `embedDependencies(name)` to (re-)materialise and embed third-party library code. The list reloads when the call returns. Does nothing if no project is selected. |
| **Reimport** | Calls `reimportProject(name)` to re-import the source files from `rootDir`. The list reloads when the call returns. Does nothing if no project is selected. |
| **DocPipe** | Calls `docpipeProject(name)` to run the documentation pipeline, then starts the same 2-second reindex-status poller used for reindexing. |
| **Delete** | Calls `deleteProject(name)`, reloads the list, and clears the selection if the deleted project was the one you had open. |

---

## Project Lifecycle at a Glance

The following table summarises the observable states a project can be in, as reflected in the `status` and `reindexStatus` fields:

| `status` | Badge colour | Typical meaning |
|---|---|---|
| `READY` | Green | The project is fully indexed and available for RAG queries. |
| `FAILED` | Red | The last import or index attempt failed. |
| `IMPORTING` | Neutral | Source files are being cloned / copied into the Hub. |

| `reindexStatus` | Active? | Notes |
|---|---|---|
| `IDLE` | No | Default; no pipeline running. |
| `IMPORTING` | Yes | File import phase. |
| `CHUNKING` | Yes | Chunking phase. |
| `EMBEDDING` | Yes | Embedding phase. |
| `DOCPIPE` | Yes | Documentation pipeline phase. |
| `COMPLETE` | No | Pipeline finished; resets to `IDLE` after 5 s. |
| `FAILED` | No | Pipeline errored; resets to `IDLE` after 5 s. |

All long-running operations (reindex, DocPipe, GitHub import/update) are **asynchronous on the server side**. The UI never blocks; it polls at a fixed interval and updates the list in place, so you can continue working with other projects while a pipeline runs.

_This document was generated with Doc|Pipe._

