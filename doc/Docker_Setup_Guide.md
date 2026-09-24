# prjxp Docker Setup Guide

This guide explains how to set up **prjxp** as a project-expert MCP server using Docker. After setup, you can query your codebase through tools like **opencode**.

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Clone the Repository](#clone-the-repository)
3. [Prepare Your Project Directory](#prepare-your-project-directory)
4. [Configure application.yaml](#configure-applicationyaml)
5. [Configure .env](#configure-env)
6. [Run the Pipeline with the prjxp Control Script](#run-the-pipeline-with-the-prjxp-control-script)
7. [Configure Your MCP Client](#configure-your-mcp-client)
8. [Verify the Server is Running](#verify-the-server-is-running)
9. [Re-embedding After Code Changes](#re-embedding-after-code-changes)
10. [Troubleshooting](#troubleshooting)
11. [Multi-Project Hub (One Container, Many Projects)](#multi-project-hub-one-container-many-projects)

---

## Prerequisites

- **Docker** installed and running
- **Git** for cloning the repository
- A source code project you want to index (e.g., your own Java/TypeScript project)

---

## Clone the Repository

```bash
git clone https://github.com/spraener/prjxp.git
cd prjxp
```

The `prjxp` repository contains a **control script** (`./prjxp`) that manages the entire Docker pipeline — building the image, chunking, embedding, and starting the MCP server.

---

## Prepare Your Project Directory

Navigate to your project directory (the code you want to index):

```bash
cd /path/to/your-project
```

You don't need to manually copy config files — the `mcp` command below does it automatically.

---

## Configure application.yaml

The control script copies `application.yaml` from the prjxp repo into your project directory on first run. Edit it to match your setup:

```yaml
prjxp:
  activeProject: "my-project"          # Name of your project

  projects:
    - name: my-project
      rootDir: ${PRJXP_ROOT_DIR:/app-source}   # Docker mount point (don't change)
      jsonlFile: "px-chunks.jsonl"              # Output from chunking
      chunoWhiteList: "java,ts"                 # File types to chunk (comma-separated)
      tibedBatchSize: 50                        # Chunks per embedding batch
      tibedResetStore: true                     # Reset index on re-embed

  # Embedding: OpenAI-compatible endpoint (e.g. TEI)
  embedding:
    type: "OPEN_AI"
    apiBaseURL: ${EMBEDDING_API_BASE_URL:http://localhost:80/v1}
    modelName: ${EMBEDDING_MODEL_NAME:mixedbread-ai/mxbai-embed-large-v1}

  # Vector store: Lucene (local, no external DB)
  embeddingStoreType: ${EMBEDDING_STORE_TYPE:lucene}
  embeddingStoreLucene:
    indexPath: ${LUCENE_INDEX_PATH:.prjxp-data/lucene-index}
    vectorDimension: ${LUCENE_VECTOR_DIMENSION:1024}

server:
  port: ${SERVER_PORT:7007}
```

**Important notes:**
- `rootDir` **must** be `/app-source` — this is the Docker mount point
- `chunoWhiteList` determines which file types are chunked: `java`, `ts`, `js`, `py`, etc.
- `tibedResetStore: true` clears the old index before re-embedding (useful for updates)
- `OPEN_AI` embedding nutzt einen OpenAI-kompatiblen Endpoint (z. B. TEI auf Port 80)

---

## Configure .env

The control script also copies `.env.example` to `.env` on first run. Verify the values:

```bash
PRJXP_ROOT_DIR=/app-source
EMBEDDING_STORE_TYPE=lucene
EMBEDDING_API_BASE_URL=http://localhost:80/v1
EMBEDDING_MODEL_NAME=mixedbread-ai/mxbai-embed-large-v1
LUCENE_INDEX_PATH=.prjxp-data/lucene-index
LUCENE_VECTOR_DIMENSION=1024
SERVER_PORT=7007
```

---

## Run the Pipeline with the prjxp Control Script

All pipeline operations are handled by the `./prjxp` control script located in the prjxp repository root.

### Full Setup: Chunk + Embed + Start MCP Server (Recommended)

From the **prjxp directory**, run:

```bash
cd /path/to/prjxp
./prjxp /path/to/your-project mcp
```

This does everything in one go:
1. Copies `application.yaml` and `.env` to your project if they don't exist
2. Builds the Docker image automatically if it doesn't exist yet
3. **Chunks** your source code → `px-chunks.jsonl`
4. **Embeds** the chunks into a Lucene index
5. Starts the MCP server on port 7007

### Custom Port

```bash
./prjxp /path/to/your-project mcp --port 8090
```

### Individual Steps

If you want to run steps separately:

```bash
# Only chunk (skips if px-chunks.jsonl already exists)
./prjxp /path/to/your-project chunk

# Only embed (skips if .prjxp-data already has content)
./prjxp /path/to/your-project embed
```

### Stop the Server

```bash
./prjxp /path/to/your-project stop
```

### Clean Everything

Delete chunks, index data, Docker image, and stop the server:

```bash
./prjxp /path/to/your-project clean
```

### Rebuild the Docker Image

```bash
./prjxp rebuild
```

---

## Configure Your MCP Client

After the server is running, it's available at `http://localhost:7007/mcp`. Configure your IDE to connect.

### opencode (Recommended)

Add an entry to `~/.config/opencode/opencode.jsonc`:

```jsonc
{
  "mcpServers": {
    "prjxp": {
      "type": "remote",
      "url": "http://localhost:7007/mcp",
      "enabled": true
    }
  }
}
```

> **Note:** If you used a custom port (e.g., `--port 8090`), adjust the URL accordingly:
> `http://localhost:8090/mcp`

### GitHub Copilot (VS Code)

Create `.vscode/mcp.json` in your project:

```json
{
  "mcpServers": {
    "prjxp-project-expert": {
      "type": "streamable-http",
      "url": "http://localhost:7007/mcp",
      "enabled": true
    }
  }
}
```

### GitHub Copilot (JetBrains)

In **Settings → Tools → MCP Servers**, add:
- **Name:** `prjxp-project-expert`
- **Type:** `streamable-http`
- **URL:** `http://localhost:7007/mcp`

---

## Verify the Server is Running

Test with curl:

```bash
curl "http://localhost:7007/prjxp/tools/ping"
# Expected response: "pong!"

curl "http://localhost:7007/prjxp/tools/context?userQuestion=How+does+the+embedding+server+work"
# Expected: relevant code snippets from your project
```

---

## Re-embedding After Code Changes

When you update your source code, re-run the pipeline:

```bash
cd /path/to/prjxp
./prjxp /path/to/your-project mcp   # Full re-run: chunk + embed + serve
```

The control script skips chunking if `px-chunks.jsonl` exists and skips embedding if `.prjxp-data` has content. To force a full re-index, clean first:

```bash
./prjxp /path/to/your-project clean
./prjxp /path/to/your-project mcp
```

---

## Troubleshooting

| Problem | Solution |
|---|---|
| `px-chunks.jsonl` is empty | Check `chunoWhiteList` — does it match your file types? |
| Embedding fails with dimension error | Ensure `LUCENE_VECTOR_DIMENSION=1024` in `.env` |
| MCP server won't start on port 7007 | Use `--port 8090` instead |
| "No active project" error | Check `activeProject` in `application.yaml` matches your project name |
| Docker image build fails | Check Docker is running: `docker info` |
| Server started but ping failed | Check container logs: `docker logs mcp-<project-name>` |

---

## Multi-Project Hub (One Container, Many Projects)

Instead of one container per project, a single long-running **hub** serves any
number of projects added at runtime. The hub runs the same Docker image in a
fourth mode (`hub`) and executes chunking + embedding **in-process** — no
separate pipeline containers.

### Start the Hub

```bash
docker compose up -d hub
```

The hub listens on port **7008** (so it can run alongside a single-project
server on 7007) and mounts:

| Mount | Purpose |
|---|---|
| `./import` → `/import` | Drop tar archives here to import projects |
| named volume `prjxp-projects` → `/projects` | Extracted project directories (persistent) |
| named volume `prjxp-hub-data` → `/data` | Shared Lucene index (persistent) |

The hub requires the Lucene store type — already set in `docker-compose.yml`
(`EMBEDDING_STORE_TYPE=lucene`). All projects share one index; chunks are
stamped with their project name.

### Import a Project

Package your project as a tar archive and drop it into `./import`:

```bash
cd /path/to/your-project
tar czf /path/to/prjxp/import/my-project.tar .        # files at archive root
# or from the parent directory (a single top-level dir is stripped automatically):
tar czf /path/to/prjxp/import/my-project.tar my-project/
```

Accepted formats: `.tar`, `.tgz`, `.tar.gz`. The import poller picks up new
archives within ~5 seconds and runs:

1. **Extract** → `/projects/my-project` — zip-slip paths, symlinks and hardlinks are rejected; limits: 2 GB archive / 4 GB extracted / 100k entries
2. **Register** → the project appears in the registry (status `importing`)
3. **Chunk + Embed** → runs in-process; status walks `chunking` → `embedding`
4. **Ready** → searchable via MCP and the web UI

Notes:

- **Re-import:** dropping an archive with an existing project name replaces it — old index data is wiped and the new archive is processed from scratch.
- **Failed archives** are renamed to `*.tar.failed` (not retried); delete them once fixed.
- **Restart:** on hub startup, projects whose chunks are already in the index come up `ready` immediately; others re-run the pipeline.
- **Optional config:** a `prjxp.yaml` at the archive root can override defaults — `name`, `rootDir`, `jsonlFile`, `chunoWhiteList` (default `java,ts`), `tibedBatchSize` (default 32).

### Check Status & Manage Projects

```bash
# All projects with lifecycle status (importing/chunking/embedding/ready/failed)
curl http://localhost:7008/prjxp/projects

# Delete a project (scoped index wipe + directory removal)
curl -X DELETE http://localhost:7008/prjxp/projects/my-project
```

The web UI at `http://localhost:7008/` lists all projects in the dropdown with
their status; non-ready projects are disabled. Point your MCP client at
`http://localhost:7008/mcp` — only `ready` projects are listed.

### Hub vs Single Project

| | Single project (`./prjxp ... mcp`) | Hub (`docker compose up hub`) |
|---|---|---|
| Containers | one per project | one for all projects |
| Adding a project | new container + full pipeline run | drop a tar into `./import` |
| Port | 7007 (configurable) | 7008 |
| Index | per-project volume | shared Lucene index, project-stamped |
| Pipeline | separate chunk/embed containers | in-process (single FIFO worker) |

---

## Architecture Overview

```
Your Project (mounted at /app-source)
├── application.yaml          ← Copied by prjxp control script
├── .env                      ← Copied from .env.example
├── px-chunks.jsonl          ← Generated by chunk-norris
└── [your source code]       ← Java, TypeScript, etc.

Docker Container (prjxp image)
├── chunk-norris-all.jar     ← Chunks source code → JSONL
├── tibed-all.jar            ← Embeds chunks → Lucene index
├── mcp-server-all.jar       ← Serves retrieval API on :7007
└── text-embeddings-router    ← TEI CPU embedding server (HF model)

Docker Volume: prjxp-data
└── .prjxp-data/lucene-index/  ← Persistent Lucene index

Control Script (./prjxp)
├── chunk   → Run chunker, produce JSONL
├── embed   → Embed chunks into Lucene
├── mcp     → chunk + embed + start server (default)
├── stop    → Stop the MCP container
├── clean   → Remove all artifacts and Docker image
└── rebuild → Rebuild the Docker image from scratch
```

---

## Quick Reference Commands

| Action | Command |
|---|---|
| Full setup (chunk+embed+serve) | `./prjxp /your/project mcp` |
| Setup with custom port | `./prjxp /your/project mcp --port 8090` |
| Stop server | `./prjxp /your/project stop` |
| Clean all artifacts | `./prjxp /your/project clean` |
| Rebuild Docker image | `./prjxp rebuild` |
| Test ping | `curl "http://localhost:7007/prjxp/tools/ping"` |
| Query context | `curl "http://localhost:7007/prjxp/tools/context?userQuestion=..."` |
