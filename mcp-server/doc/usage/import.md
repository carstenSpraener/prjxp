# Import

The Import page in the PRJXP Hub web UI provides three tabs for adding projects to the index. The default tab is **Upload**.

| Tab | Purpose |
|-----|---------|
| **Upload** | Upload a project archive (e.g. `.tar.gz`) |
| **Embedded** | Upload a pre-packaged embedded project file (e.g. `.tgz`) |
| **Live** | Scan the host filesystem for existing project directories and register them |

Each tab renders a file-upload zone (for Upload and Embedded) or a scan-and-register workflow (for Live). Imported projects are displayed as project cards in a list below the active tab's controls.

## Archive Upload

The **Upload** tab (the default) accepts a project archive via the file-upload zone.

1. Select or drag an archive file (for example `test.tar.gz`) into the upload zone.
2. The UI calls the `uploadArchive` service method with the selected `File`.
3. On success the returned `ProjectInfo` (name, status) is appended to the uploaded-projects list and rendered as a card.
4. On failure an alert dialog is shown with the server error message.

The upload is a single round-trip: the archive is sent to the Hub, which unpacks, chunks, and indexes it. The resulting project card reflects the current import status (see [Import Statuses](#import-statuses)).

## Embedded Upload

The **Embedded** tab works identically to Archive Upload but targets a different service method (`uploadEmbedded`). It is intended for pre-packaged embedded project files (for example `test-embedded.tgz`).

- Select or drag the embedded file into the upload zone.
- On success the project is added to the embedded-projects list.
- On failure an alert dialog displays the error.

Embedded projects are tracked in a separate list from archive uploads, so both can coexist on the same page.

## Live Projects

The **Live** tab registers projects that already exist as directories on the Hub host (for example a checked-out repository). No file upload is involved.

### Scanning

Click **Scan** to trigger a filesystem scan:

- The UI sets an internal *scanning* flag (the button is disabled while the scan runs).
- The Hub returns a list of **candidates**, each containing:

| Field | Description |
|-------|-------------|
| `name` | Project name (derived from the directory) |
| `directory` | Absolute path on the host |
| `hasConfigFile` | Whether a PRJXP config file was detected |
| `languages` | Detected source languages (e.g. `["Java"]`, `["Kotlin"]`) |

If the scan fails, the scanning flag is reset and no candidates are shown.

### Registering a candidate

For each candidate you can choose to register it:

- The UI sends a `ProjectCreateRequest` with:
  - `name` – the candidate's name
  - `kind` – fixed to `"LIVE"`
  - `rootDir` – the candidate's `directory` path
- On success the candidate is removed from the list; the project now appears in the main project list and will be indexed.

You can register multiple candidates in sequence; each registration removes only that candidate from the list.

## Import Statuses

Every project carries a `status` field that reflects where it is in the import pipeline. The statuses observed in the UI are:

| Status | Meaning |
|--------|---------|
| `IMPORTING` | The Hub is still chunking, embedding, or indexing the project. The project card is visible but the project is not yet searchable. |
| `READY` | Indexing is complete. The project can be queried via the search tools. |

The status is displayed on each project card. A project transitions from `IMPORTING` to `READY` once the Hub finishes processing.

## Error Handling

All three import paths share the same failure behaviour:

- **Upload / Embedded:** If the HTTP request fails, the UI logs the error to the browser console and shows a modal alert with the message `Upload fehlgeschlagen: <error>`. The project is **not** added to the list.
- **Live scan:** If the scan request fails, the scanning indicator is cleared and no candidates are populated. No alert is shown for scan failures.
- **Live registration:** The registration call is fire-and-forget from the UI's perspective; on success the candidate is simply removed from the list.

No retry mechanism is provided in the UI. If an upload fails, select the file again and retry.

_This document was generated with Doc|Pipe._

