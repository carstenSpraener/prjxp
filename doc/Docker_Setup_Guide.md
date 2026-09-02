# prjxp Docker Setup Guide

This guide explains how to set up **prjxp** as a project-expert MCP server using Docker. After setup, you can query your codebase through tools like **GitHub Copilot** or **opencode**.

---

## Table of Contents

1. [Prerequisites](#prerequisites)
2. [Clone and Build the Docker Image](#clone-and-build-the-docker-image)
3. [Prepare Your Project Directory](#prepare-your-project-directory)
4. [Adapt application.yaml](#adapt-applicationyaml)
5. [Run the Setup Pipeline](#run-the-setup-pipeline)
6. [Configure Your MCP Client](#configure-your-mcp-client)
7. [Start the MCP Server](#start-the-mcp-server)
8. [Troubleshooting](#troubleshooting)

---

## Prerequisites

- **Docker** installed and running
- **Git** for cloning the repository
- A source code project you want to index (e.g., your own Java/TypeScript project)

---

## Clone and Build the Docker Image

```bash
git clone https://github.com/spraener/prjxp.git
cd prjxp

# Build the Docker image (one-time)
docker build -t prjxp .
```

This creates the `prjxp` image containing all three tools: `chunk-norris`, `tibed`, and the MCP server.

---

## Prepare Your Project Directory

Navigate to your project directory (the code you want to index):

```bash
cd /path/to/your-project
```

Copy the template files from prjxp:

```bash
cp /path/to/prjxp/application.yaml .
cp /path/to/prjxp/.env.example .env
```

---

## Adapt application.yaml

Edit `application.yaml` in your project directory. The key settings:

```yaml
prjxp:
  activeProject: "my-project"          # Name of your project

  projects:
    - name: my-project
      rootDir: /app-source             # MUST be /app-source in Docker
      jsonlFile: px-chunks.jsonl       # Output file from chunking
      chunoWhiteList: java,ts          # File types to chunk (comma-separated)
      tibedBatchSize: 50               # Chunks per embedding batch
      tibedResetStore: true            # Reset index on re-embed

  # Embedding configuration
  embeddingModelType: onnx_local       # Use built-in ONNX model (no external server)
  embeddingStoreType: lucene           # Use Lucene vector store

  mcp-servers:
    - name: "filesystem-mcp"           # Optional: add MCP tools you need
      type: "stdio"
      command: "npx"
      args: ["-y", "@modelcontextprotocol/server-filesystem", "/path/to/files"]
```

**Important notes:**
- `rootDir` **must** be `/app-source` — this is the Docker mount point
- `chunoWhiteList` determines which file types are chunked: `java`, `ts`, `js`, `py`, etc.
- `tibedResetStore: true` clears the old index before re-embedding (useful for updates)

### Adapt .env

Edit `.env` in your project directory:

```bash
PRJXP_ROOT_DIR=/app-source
EMBEDDING_STORE_TYPE=lucene
EMBEDDING_MODEL_TYPE=onnx_local
LUCENE_INDEX_PATH=.prjxp-data/lucene-index
LUCENE_VECTOR_DIMENSION=1024
```

---

## Run the Setup Pipeline

From your **project directory**, run:

```bash
/path/to/prjxp/setup.sh .
```

This does everything in one go:
1. Checks if the Docker image exists (builds it automatically if not)
2. Copies config files if missing
3. **Chunks** your source code → `px-chunks.jsonl`
4. **Embeds** the chunks into a Lucene index
5. Starts the MCP server on port 7007

### Optional: Custom Port

```bash
/path/to/prjxp/setup.sh . --port 8090
```

### Stop the Server

```bash
/path/to/prjxp/setup.sh stop .
```

---

## Configure Your MCP Client

After the setup pipeline runs, your MCP server is available at `http://localhost:7007`. Configure your IDE to connect.

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

### opencode

Add an entry to `~/.config/opencode/opencode.jsonc`:

```jsonc
{
  // ... your existing config ...
  "mcp": {
    "prjxp-project-expert": {
      "type": "streamable-http",
      "url": "http://localhost:7007/mcp",
      "enabled": true
    }
  }
}
```

> **Note:** If you used a custom port (e.g., `--port 8090`), adjust the URL accordingly:
> `http://localhost:8090/mcp`

---

## Start the MCP Server (Standalone)

If you only want to start the server without re-running chunk/embed:

```bash
docker run -d \
  --name mcp-my-project \
  -v /path/to/your-project:/app-source \
  -v prjxp-data:.prjxp-data/lucene-index \
  -p 7007:7007 \
  prjxp serve
```

Or use `docker compose`:

```bash
cd /path/to/your-project
docker compose -f /path/to/prjxp/docker-compose.yml up -d
```

---

## Verify the Server is Running

Test with curl:

```bash
curl http://localhost:7007/prjxp/tools/ping
# Expected response: "pong!"

curl "http://localhost:7007/prjxp/tools/context?userQuestion=How+does+the+embedding+server+work"
# Expected: relevant code snippets from your project
```

---

## Re-embedding After Code Changes

When you update your source code, re-run the pipeline:

```bash
/path/to/prjxp/setup.sh . --skip-chunk   # If chunks are still valid
# Or full re-run:
/path/to/prjxp/setup.sh .                  # Chunk + embed + serve
```

---

## Troubleshooting

| Problem | Solution |
|---|---|
| `px-chunks.jsonl` is empty | Check `chunoWhiteList` — does it match your file types? |
| Embedding fails with dimension error | Ensure `LUCENE_VECTOR_DIMENSION=1024` in `.env` |
| MCP server won't start on port 7007 | Another process is using the port. Use `--port 8090` instead. |
| "No active project" error | Check `activeProject` in `application.yaml` matches your project name |
| Docker image not found | Run `docker build -t prjxp .` in the prjxp directory first |

---

## Architecture Overview

```
Your Project (mounted at /app-source)
├── application.yaml          ← Your project config
├── .env                      ← Environment variables
├── px-chunks.jsonl          ← Generated by chunk-norris
└── [your source code]       ← Java, TypeScript, etc.

Docker Container (prjxp image)
├── chunk-norris-all.jar     ← Chunks source code → JSONL
├── tibed-all.jar            ← Embeds chunks → Lucene index
├── mcp-server-all.jar       ← Serves retrieval API on :7007
└── prjxp-common/embedding-server/
    ├── embedding-server.py  ← Python ONNX server (auto-started)
    └── models/model.onnx   ← mxbai-embed-large (1024-dim)

Docker Volume: prjxp-data
└── .prjxp-data/lucene-index/  ← Persistent Lucene index
```

---

## Quick Reference Commands

| Action | Command |
|---|---|
| Build image (once) | `cd prjxp && docker build -t prjxp .` |
| Full setup (chunk+embed+serve) | `/path/to/prjxp/setup.sh /your/project` |
| Setup with custom port | `/path/to/prjxp/setup.sh /your/project --port 8090` |
| Stop server | `/path/to/prjxp/setup.sh stop /your/project` |
| Test ping | `curl http://localhost:7007/prjxp/tools/ping` |
| Query context | `curl "http://localhost:7007/prjxp/tools/context?userQuestion=..."` |
