# Import

The Import page in the PRJXP Hub web UI is where you add projects to your Hub. It provides three tabs for different import methods:

| Tab | Method | Typical use case |
|-----|--------|-----------------|
| **Upload** | Archive file upload | Import a project from a `.tar.gz` / `.tgz` archive |
| **Embedded** | Embedded file upload | Import a project from an embedded archive |
| **Live** | Directory scan & register | Register a project that already exists on the Hub host filesystem |

The page defaults to the **Upload** tab.

## Archive Upload

The **Upload** tab lets you import a project by uploading an archive file.

1. Click the file-upload zone to select an archive (e.g. `my-project.tar.gz`).
2. The Hub receives the archive via the `uploadArchive` endpoint.
3. On success the new project appears in the project list below the upload zone with its current status.
4. On failure an alert is shown with the error message returned by the server.

Each successfully uploaded project is tracked in the **Uploaded projects** list on the page.

## Embedded Upload

The **Embedded** tab works the same way as the Upload tab but targets the `uploadEmbedded` endpoint. Use it when your archive is in the embedded format (e.g. `my-project.tgz`).

- Select a file in the upload zone.
- The Hub processes it and returns a `ProjectInfo` object.
- The project is added to the **Embedded projects** list.
- Errors are surfaced via an alert dialog.

## Live Project Import

The **Live** tab is for projects that already exist as directories on the machine running the Hub container.

### Scanning

Click **Scan** to trigger a filesystem scan (`scanLiveProjects`). While the scan runs, a scanning indicator is displayed. When the scan completes, the results appear as a list of **candidates**, each showing:

| Field | Description |
|-------|-------------|
| `name` | Project name (derived from the directory) |
| `directory` | Absolute path on the host |
| `hasConfigFile` | Whether a PRJXP config file was detected |
| `languages` | Detected source-code languages (e.g. `Java`, `Kotlin`) |

### Registering a candidate

For each candidate you want to import, click **Register**. The Hub sends a `ProjectCreateRequest`:

```json
{
  "name": "my-proj",
  "kind": "LIVE",
  "rootDir": "/path/my-proj"
}
```

After a successful registration the candidate is removed from the candidate list and the project becomes available in the Hub.

## Project Statuses

Every imported project carries a `status` field that reflects its lifecycle:

| Status | Meaning |
|--------|---------|
| `IMPORTING` | The archive has been received and the Hub is still processing it (chunking, embedding). |
| `READY` | Processing is complete; the project's chunks are embedded and available for retrieval. |

The status is displayed on each project card in the list. A project that is still `IMPORTING` will transition to `READY` once the Hub finishes indexing.

## Project List

After any import method completes, the resulting project appears as a card in the corresponding list on the Import page:

- **Uploaded projects** – projects added via the Upload tab.
- **Embedded projects** – projects added via the Embedded tab.
- **Live candidates** – unregistered directories found during a scan (removed once registered).

Each card shows the project name and its current status. Use the card to navigate to the project's detail view for further management.

_This document was generated with Doc|Pipe._

