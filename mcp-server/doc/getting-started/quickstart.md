# Quickstart

This chapter walks you through the three actions you will perform immediately after starting the PRJXP Hub:

1. **Import** a project (archive, embedded archive, or live directory).
2. **Watch** the indexing lifecycle move the project from *IMPORTING* to *READY*.
3. **Ask** your first question in the chat and see the retrieval pipeline in action.

No additional configuration is required beyond the Hub container being up and the web UI reachable in your browser.

---

## Importing a Project

The Import page (`/import`) presents three tabs. The default tab is **Upload**.

| Tab | What you provide | Service call | Typical file |
|-----|-----------------|--------------|--------------|
| **Upload** | An archive of your source tree | `uploadArchive(file)` | `my-project.tar.gz` |
| **Embedded** | A pre-packaged embedded archive | `uploadEmbedded(file)` | `my-project-embedded.tgz` |
| **Live** | A directory already on the Hub host | `scanLiveProjects()` → `registerProject(candidate)` | — (filesystem path) |

### Upload tab (archive)

1. Click the **Upload** tab (selected by default).
2. Drag-and-drop or browse to select an archive file (e.g. `my-project.tar.gz`).
3. The UI calls `uploadArchive` with the selected `File`. On success the returned `ProjectInfo` (containing `name` and `status`) is appended to the project list card.
4. On failure an alert is shown with the server error message.

### Embedded tab

1. Switch to the **Embedded** tab.
2. Select an embedded archive (e.g. `my-project-embedded.tgz`).
3. The UI calls `uploadEmbedded`. The resulting `ProjectInfo` appears in the embedded-projects list.

> **Note:** The embedded flow is intended for projects that were previously packaged with their index artifacts. The returned status may initially be `IMPORTING` while the Hub validates the bundle.

### Live tab (host directory)

1. Switch to the **Live** tab.
2. Click **Scan**. The UI sets a scanning indicator and calls `scanLiveProjects()`.
3. The Hub returns a list of `LiveProjectCandidate` objects, each containing:

   | Field | Meaning |
   |-------|---------|
   | `name` | Project name (derived from directory) |
   | `directory` | Absolute path on the Hub host |
   | `hasConfigFile` | Whether a PRJXP config file was detected |
   | `languages` | Detected source languages (e.g. `["Java"]`, `["Kotlin"]`) |

4. For each candidate you want to index, click **Register**. The UI sends a `ProjectCreateRequest`:

   ```json
   {
     "name": "my-proj",
     "kind": "LIVE",
     "rootDir": "/path/my-proj"
   }
   ```

   On success the candidate is removed from the list and the project appears in the main project list.

---

## The Indexing Lifecycle

After any import method completes, the Hub builds (or validates) the project index in the background. The `ProjectInfo.status` field tracks progress:

```
IMPORTING  ──►  READY
```

| Status | Meaning |
|--------|---------|
| `IMPORTING` | The Hub is chunking source files, computing embeddings, and (where applicable) building the code graph. The project is **not yet searchable**. |
| `READY` | The index is complete. The project appears in `listProjects()` and all search tools accept it as a `projectName`. |

You can monitor progress on the Import page: the project card updates its status badge as the Hub transitions the project. Once the badge reads **READY**, the project is fully queryable.

> The `listProjects()` tool returns every indexed project together with the timestamp of its last index update. Use it to confirm that your project is visible to the retrieval layer.

---

## Asking Your First Question

Open the **Chat** view and select your newly indexed project (e.g. `prjxp`). Type a natural-language question, for example:

```
Which class handles the import lifecycle, and what methods does it expose?
```

Under the hood the Hub runs a **three-level search ladder** plus structural tools:

| Level | Tool | Purpose |
|-------|------|---------|
| 1 (primary) | `vectorSearch(projectName, userQuestion)` | Semantic retrieval over chunk embeddings. Always called first for discovery. |
| 2 | `grep(query, project)` | Exact full-text search across all chunks. Use the exact method signature as `query` to pull the full body. |
| 3 | `readFile(file, project)` / `readBySignature(project, method)` | Returns the **complete** source of a file or a single method (Javadoc + body; Java only for `readBySignature`). |

Orthogonal structural tools are also available:

| Tool | Purpose |
|------|---------|
| `listProjects()` | All indexed projects + last-update timestamp. |
| `listFiles(project, path)` | Browse the file tree; supports path prefixes and globs. |
| `graphFile(project, file, includeExternal, maxEdges)` | Typed code-graph neighbours of a file (imports, extends, implements, field, param, return, call edges). |
| `graphNode(project, symbol, includeExternal, maxEdges)` | Typed neighbours of a specific symbol (FQN or `fqn#name(paramFQNs)`). Lenient resolution: exact → suffix. |

### Typical retrieval flow for one question

```
userQuestion
    │
    ▼
vectorSearch("prjxp", question)          ← semantic hits (chunks)
    │
    ├──► grep("exactSignature", "prjxp") ← confirm / pull full body
    │
    └──► readFile("src/.../Foo.java", "prjxp")   ← whole file
         readBySignature("prjxp", "com.acme.Foo#bar(String)")  ← one method
```

The LLM assembles the retrieved chunks into a grounded answer. You will see the answer in the chat pane; no additional API calls are needed from your side.

> **Tip:** Pass `includeTests: true` to `vectorSearch` when your question concerns test code. The default is `false` for `vectorSearch` and `true` for `grep`.

---

## Verifying the Index

After the first question, you can sanity-check the index from the chat or any tool-capable client:

```text
listProjects()
```

Expected output (one line per project):

```
prjxp   last-indexed: 2025-07-11T09:42:17Z
```

To confirm that source files were chunked:

```text
listFiles(project: "prjxp", path: "src/main")
```

To inspect the dependency neighbourhood of a specific file:

```text
graphFile(project: "prjxp", file: "src/main/java/com/acme/ImportService.java", includeExternal: false, maxEdges: 20)
```

If the project is still `IMPORTING`, these tools will return an empty result or an error until the index is complete.

---

## Troubleshooting the First Import

| Symptom | Likely cause | Action |
|---------|-------------|--------|
| Alert: *"Upload fehlgeschlagen: …"* | Archive rejected by the Hub (bad format, size limit, or server error). | Check the Hub container logs; re-compress the archive as `.tar.gz` (upload) or `.tgz` (embedded). |
| Project stuck on `IMPORTING` | Indexing is still running or failed mid-build. | Wait, then call `listProjects()` to see if the timestamp advanced. Restart the Hub container if it does not. |
| `vectorSearch` returns no hits | Project not yet `READY`, or the question is too narrow. | Confirm status is `READY`; rephrase the question with broader terms. |
| `readBySignature` returns "Java only" | The symbol belongs to a non-Java source file. | Use `readFile` or `grep` instead. |

Error messages surfaced by the UI are rendered in German (e.g. *"Unbekannter Fehler"* = "Unknown error"). The underlying HTTP response body contains the machine-readable detail.

_This document was generated with Doc|Pipe._

