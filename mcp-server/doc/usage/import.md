# Import

The Import page in the PRJXP Hub web UI is where you add projects to your local index. It presents three tabs—**Upload**, **Live**, and **Embedded**—each corresponding to a different way of bringing source code into the platform. Once a project is registered, it is chunked, embedded, and made available for retrieval-augmented questions.

## Import Tabs

The Import page opens on the **Upload** tab by default. You can switch between the three tabs at any time:

| Tab | Purpose |
|-----|---------|
| **Upload** | Upload a project archive (e.g. `.tar.gz`) from your local machine. |
| **Live** | Scan the host filesystem for existing project directories and register them in place. |
| **Embedded** | Upload a pre-packaged embedded archive (e.g. `.tgz`) that the Hub processes internally. |

Each tab maintains its own independent list of projects. Projects added via one tab do not appear in the lists of the other tabs.

## Archive Upload

The **Upload** tab provides a file-upload zone. Select an archive file (for example `my-project.tar.gz`) and the Hub unpacks it, chunks the source code, and generates embeddings.

- On success the new project appears in the list below the upload zone with its name and current status.
- On failure an alert is shown with the server-provided error message.

The upload is a single round-trip: the file is sent to the Hub, processed, and the resulting `ProjectInfo` (name + status) is returned.

## Embedded Upload

The **Embedded** tab works the same way as the Upload tab but targets a different backend endpoint (`uploadEmbedded`). Use it when you have a pre-packaged embedded archive (for example `my-project.tgz`) that should be ingested through the embedded pipeline.

- On success the project is added to the embedded-projects list.
- On failure an alert displays the error returned by the server.

## Live Project Scan & Registration

The **Live** tab lets you register projects that already exist on the host filesystem (for example, mounted into the Docker container).

1. Click **Scan**. The Hub walks the configured directories and returns a list of *live project candidates*. Each candidate shows:
   - **Name** – the directory name.
   - **Directory** – the absolute path on the host.
   - **Has config file** – whether a PRJXP configuration file was detected.
   - **Languages** – the programming languages identified in the directory.

2. For each candidate you want to index, click **Register**. The Hub sends a `ProjectCreateRequest` with:

   ```json
   {
     "name": "my-proj",
     "kind": "LIVE",
     "rootDir": "/path/my-proj"
   }
   ```

   After a successful registration the candidate is removed from the list.

While a scan is in progress a scanning indicator is shown; it clears automatically when the scan completes or fails.

## Project Statuses

Every project carries a `status` field that reflects where it is in the import lifecycle. The statuses observed in the UI are:

| Status | Meaning |
|--------|---------|
| `IMPORTING` | The archive has been received and the Hub is chunking / embedding the source. |
| `READY` | Indexing is complete; the project is available for vector search and Q&A. |

The status is displayed on each project card in the list. A project that is still `IMPORTING` will transition to `READY` once the backend finishes processing.

## Removing Projects

The project list on the Import page (rendered via the *Project List Card* component) shows every project that has been added through the active tab. To remove a project, use the remove action on its card. This deletes the project's index entries (chunks, embeddings, and graph artifacts) from the Hub so it no longer appears in search results.

> **Note:** Removing a *live* project does **not** delete the source files on disk; it only deregisters the project from the PRJXP index. The directory remains on the host and can be re-registered at any time via the Live tab.

## Troubleshooting

- **Upload fails with an alert** – The alert text is the error message returned by the Hub backend. Common causes include an unsupported archive format or a file that exceeds the configured size limit. Check the Hub container logs (`docker logs <hub-container>`) for the full stack trace.
- **Scan returns no candidates** – Ensure the host directories you expect to contain projects are mounted into the container and that the Hub's scan path configuration points to them.
- **Project stuck in `IMPORTING`** – The embedding step may still be running for large codebases. Wait for the status to change to `READY`, or check the Hub logs for errors.

_This document was generated with Doc|Pipe._

