# Quickstart

This chapter walks you through the three things you need to do after starting the PRJXP Hub for the first time:

1. **Import a project** so the Hub can index its source code.
2. **Watch the indexing lifecycle** until the project is ready.
3. **Ask your first question** in the chat and follow the results into the code graph.

No additional configuration is required beyond the Hub container being up and the web UI reachable in your browser.

---

## Importing a Project

The Import page (the first screen you see in the web UI) offers three tabs. The default tab is **Upload**.

| Tab | What you do | Typical file | Resulting status |
|-----|-------------|--------------|-----------------|
| **Upload** | Drop or select an archive of a project | `.tar.gz` | `READY` (synchronous) |
| **Embedded** | Drop or select an embedded archive | `.tgz` | `IMPORTING` (asynchronous) |
| **Live** | Scan the host filesystem for existing projects | — (no file) | `READY` after registration |

### Upload (archive)

1. Click the **Upload** tab (it is selected by default).
2. Drag a project archive (e.g. `my-project.tar.gz`) into the upload zone, or click to browse.
3. The Hub unpacks the archive, chunks the source files, and embeds them.
4. On success the project card appears in the **Uploaded projects** list with status `READY`.
5. On failure an alert is shown with the server error message.

### Embedded

1. Switch to the **Embedded** tab.
2. Select an embedded archive (e.g. `my-project-embedded.tgz`).
3. The project card appears with status `IMPORTING`. Indexing continues in the background; the card updates to `READY` when the Hub finishes.

### Live (host scan)

Use this when the project already exists on the machine that runs the Hub container and you want to index it in place.

1. Switch to the **Live** tab.
2. Click **Scan**. The Hub walks the host filesystem and returns a list of *candidates*. Each candidate shows:

   | Field | Meaning |
   |-------|---------|
   | `name` | Project name (derived from the directory) |
   | `directory` | Absolute path on the host |
   | `hasConfigFile` | Whether a PRJXP config file was detected |
   | `languages` | Detected source languages (e.g. `["Java"]`, `["Kotlin"]`) |

3. For each candidate you want to index, click **Register**. The Hub sends a `ProjectCreateRequest` with `kind: "LIVE"` and the candidate's `rootDir`, then removes it from the candidate list.
4. The registered project appears in the project list and begins indexing.

> **Tip:** If the scan returns no candidates, verify that the Hub container has the correct host-path volume mounted so it can see your source trees.

---

## The Indexing Lifecycle

Every project moves through a small state machine. The status is visible on the project card and is also returned by the `listProjects` tool.

```
IMPORTING  ──►  READY
   │
   └──►  (error – card shows the failure reason)
```

| Status | Meaning |
|--------|---------|
| `IMPORTING` | The Hub is chunking source files and computing embeddings. The project is **not yet searchable**. |
| `READY` | All chunks are embedded and the project is fully queryable. |

- **Upload** projects typically reach `READY` before the HTTP response returns (synchronous path).
- **Embedded** and **Live** projects start at `IMPORTING` and transition to `READY` asynchronously. Poll the project list or wait for the card to update.

While a project is `IMPORTING`, chat queries against it will not return results. Wait for `READY` before asking questions.

---

## Asking Your First Question

Once a project shows `READY`, open the **Chat** view. The chat is backed by a set of retrieval tools that the assistant calls on your behalf. You simply type a natural-language question; the assistant picks the right tool(s).

### Available tools

| Tool | Purpose |
|------|---------|
| `vectorSearch` | Semantic search over embedded chunks. The primary entry point for *"where is X?"*, *"how does Y work?"*, *"which classes are involved?"* |
| `grep` | Exact full-text search. Use for exact signatures, annotation lookups, and call-site enumeration. |
| `readFile` | Returns the complete source of one file (reconstructed from the index). |
| `readBySignature` | Returns the full implementation (Javadoc + body) of a method or class by name / FQN. |
| `listProjects` | Lists all searchable projects and their last index timestamp. |
| `listFiles` | Lists file paths in the index (structure only, no content). |
| `graphFile` | Typed code-graph neighbours of a file (import, extends, implements, field, param, return, call edges). |
| `graphNode` | Typed code-graph neighbours of a single symbol (FQN or `FQN#name(params)`). |
| `graphCluster` | Top members of a cluster, ranked by degree centrality. |
| `graphClusterOverview` | The N largest clusters in the project – a quick architecture map. |

### Typical flows

The assistant composes tools in these patterns:

- **Discover** – `vectorSearch` → `readFile(path)` for a whole file, or `readBySignature(fqn)` for a single method.
- **Enumerate implementations** – `grep("@Annotation", contentLevel=POINTERS)`. Runtime-discovered implementations have no edge in the code graph, so `vectorSearch` cannot find them.
- **Call sites** – `grep("symbolName", contentLevel=POINTERS)`. Cheaper than `graphNode`.
- **Architecture** – `vectorSearch` + `graphNode` / `graphClusterOverview`.

### `contentLevel` and what you get back

| `contentLevel` | Output |
|----------------|--------|
| `POINTERS` *(default)* | File path plus `readFile` / `readBySignature` / `graphNode` hints per hit (~50 bytes each). Follow the hints to drill in. |
| `SKELETON` | Class frames with method signatures. |
| `FULL` | Complete method / file bodies. |

Defaults for every search tool: `contentLevel=POINTERS`, `sourceType=code`, `maxResults=10`.

### Example: your first question

```
You:  How does the import service handle archive uploads?

Assistant:  [vectorSearch → 3 chunks in ImportService.ts]
            [readFile → ImportService.ts, lines 40–112]
            The uploadArchive method unpacks the archive, walks the file tree,
            chunks each source file, and submits the chunks to the embedding
            pipeline. On completion it returns a ProjectInfo with status READY.
```

The assistant will cite file paths and line ranges. Click a path to open the file in the viewer, or ask a follow-up such as *"Show me the `registerProject` method"* to trigger `readBySignature`.

---

## Exploring the Code Graph

Beyond text search, the Hub builds a typed code graph (imports, extends, implements, field, param, return, call edges). Use the graph tools when you need structural context:

| Tool | When to use it |
|------|---------------|
| `graphFile(file)` | "What does this file import / call / extend?" |
| `graphNode(symbol)` | "What calls / is called by this method?" (expensive for hot symbols – prefer `grep(symbol, POINTERS)`) |
| `graphClusterOverview(topN)` | "Give me the top-level architecture." Returns the N largest clusters with size and top members. |
| `graphCluster(clusterId, topN)` | "Show me the most important members of cluster c42." |

Both `graphFile` and `graphNode` accept:

- `includeExternal` – set `true` to reveal JDK / library edges (hidden by default to reduce noise).
- `maxEdges` – cap on neighbours per direction.

A common exploration sequence:

```
graphClusterOverview(project="my-project", topN=5)
  →  [cluster:c3]  142 nodes  top: com.acme.core.Engine, com.acme.core.Pipeline
  →  [cluster:c7]   89 nodes  top: com.acme.api.RestController

graphCluster(project="my-project", clusterId="c3", topN=10)
  →  ranked members by degree centrality

graphNode(project="my-project", symbol="com.acme.core.Engine#run(java.lang.String)")
  →  call edges, param types, return type
```

---

## What's Next

- **Indexing options** – language-specific chunking rules and embedding model selection.
- **Multi-project queries** – the `project` parameter on every tool lets you target a specific project; `listProjects` shows all available names.
- **API access** – the same tools are exposed over the Hub's HTTP API for scripting and CI integration.

_This document was generated with Doc|Pipe._

