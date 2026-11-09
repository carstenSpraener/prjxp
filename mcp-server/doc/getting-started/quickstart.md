# Quickstart

This chapter walks you through importing your first project into the PRJXP Hub and explains the project status lifecycle you will observe in the web UI.

## The Import Screen

After the Hub container is running, open the web UI in your browser. The **Import** page is the entry point for adding projects. It presents three tabs:

| Tab | Purpose |
|-----|---------|
| **Upload** (default) | Upload a project archive (e.g. `.tar.gz`) from your local machine |
| **Embedded** | Upload a pre-packaged embedded project file (e.g. `.tgz`) |
| **Live** | Scan the host filesystem for existing project directories and register them in place |

The UI defaults to the **Upload** tab. Switch between tabs to choose the import method that fits your workflow.

## Upload Tab – Archive Import

1. Click the file-upload zone on the **Upload** tab.
2. Select an archive file (for example `my-project.tar.gz`).
3. The Hub receives the archive via the `uploadArchive` endpoint and begins processing it.

On success the new project appears in the project list with a status of **READY**.
On failure an alert is shown with the server-provided error message.

## Embedded Tab – Embedded Import

The **Embedded** tab works the same way as Upload but targets the `uploadEmbedded` endpoint. Use it when you have a pre-packaged embedded project archive (for example `my-project.tgz`).

- The file is uploaded and the project is created with an initial status of **IMPORTING**.
- Once the Hub finishes processing, the status transitions to **READY**.

## Live Tab – Register a Host Directory

Use the **Live** tab when your project already exists on the machine where the Hub runs (or on a mounted volume).

1. Click **Scan**. The Hub walks the filesystem and returns a list of *live project candidates*. Each candidate reports:

   | Field | Meaning |
   |-------|---------|
   | `name` | Project name (derived from the directory) |
   | `directory` | Absolute path on the host |
   | `hasConfigFile` | Whether a PRJXP config file was detected |
   | `languages` | Detected source-code languages (e.g. `["Java"]`, `["Kotlin"]`) |

2. Select the candidate you want to register. The Hub sends a `ProjectCreateRequest`:

   ```json
   {
     "name": "my-proj",
     "kind": "LIVE",
     "rootDir": "/path/my-proj"
   }
   ```

3. The candidate is removed from the scan list and the project appears in the project list.

> **Note:** A *live* project is indexed in place. The Hub reads source files directly from `rootDir`; no copy is made.

## Project Status Lifecycle

Every project carries a `status` field that you can see on its card in the project list. The two values the UI handles are:

| Status | Meaning |
|--------|---------|
| `IMPORTING` | The Hub is still chunking, embedding, or otherwise preparing the project. The project is not yet queryable. |
| `READY` | Indexing is complete. The project's chunks are available for retrieval. |

Typical transition:

```
IMPORTING  ──►  READY
```

- **Upload** and **Live** imports that complete quickly may appear directly as `READY`.
- **Embedded** imports start as `IMPORTING` and transition to `READY` once background processing finishes.

Watch the project card in the UI; the status badge updates when the transition occurs.

## Asking Your First Question

Once a project's status reads **READY**, its code chunks are embedded and indexed. You can now switch to the **Chat** view in the web UI, select the project, and type a question about the codebase. The Hub retrieves the most relevant chunks and generates an answer grounded in your project's source.

> **Tip:** If a project is still `IMPORTING`, questions about it will not return results. Wait for the status to change to `READY` before asking.

_This document was generated with Doc|Pipe._

