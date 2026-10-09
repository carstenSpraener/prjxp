# prjxp Docker Compose Installation Guide

This guide walks you through installing **prjxp** as a self-hosted, multi-project
code-expert service using Docker Compose. After setup you get:

- **Web UI** — project dashboard at `http://localhost/prjxp/`
- **MCP server** — code-expert tools for AI clients (opencode, GitHub Copilot, …) at `http://localhost:7007/mcp`
- **Multi-project hub** — one long-running container that serves any number of projects, added at runtime (tar import or live directories)

> The single-project flow (`./prjxp /path/to/project mcp` control script) is documented
> separately in [Docker_Setup_Guide.md](./Docker_Setup_Guide.md). This guide covers the
> `docker compose` deployment only.

---

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Prerequisites](#prerequisites)
3. [Installation (Step by Step)](#installation-step-by-step)
4. [Post-Install Verification Checklist](#post-install-verification-checklist)
5. [Environment Variables Reference](#environment-variables-reference)
6. [Using prjxp](#using-prjxp)
7. [Day-2 Operations](#day-2-operations)
8. [Troubleshooting](#troubleshooting)

---

## Architecture Overview

Three containers on a shared bridge network (`prjxp-network`):

```
 Host (your machine)
 ┌───────────────┐        ┌──────────────────────── Docker: prjxp-network ────────────────────────┐
 │ Browser       │ :80    │                                                                        │
 │ (Web UI)      ├───────►│  apache-proxy ──/prjxp/*──► frontend (nginx + Angular SPA)            │
 └───────────────┘        │  httpd:2.4-alpine              │ API paths                             │
 ┌───────────────┐        │                                ▼ (tools|chat|globals|system|projects)  │
 │ MCP client    │ :7007  │                                hub (mcp-server, Spring Boot)           │
 │ (opencode…)   ├───────►│  direct host port              │ embeddings                            │
 └───────────────┘        │                                ▼                                        │
 ┌───────────────┐        │  LM Studio :1234 ◄── host.docker.internal:1234 ──────────────┐        │
 │ (embedding    │        │  (or any OpenAI-compatible endpoint)                          │        │
 │  provider)    │        └───────────────────────────────────────────────────────────────┴────────┘
 └───────────────┘   Volumes: prjxp-projects → /projects · prjxp-hub-data → /data
                     Bind mount: $PROJECTS_HOME → /import (live projects + tar drop zone)
```

| Service | Image | Role | Host port |
|---|---|---|---|
| `apache-proxy` | `httpd:2.4-alpine` (prebuilt) | Reverse proxy, single entry point for the Web UI | `${APACHE_PORT:-80}` |
| `frontend` | `prjxp-frontend` (built from `./frontend`) | nginx serving the Angular SPA + API proxy to the backend (`PRJXP_HUB_URL`, default `hub:7007`) | internal only (8080) |
| `hub` | `prjxp-hub` (built from repo root) | mcp-server in **hub mode**: project registry, import poller, in-process chunk + embed pipeline, MCP endpoint | `${HUB_PORT:-7007}` (MCP) |

The `hub` image is a multi-stage build: Gradle compiles three fat JARs
(`chunk-norris`, `tibed`, `mcp-server`) in stage 1; the runtime image
(`eclipse-temurin:21-jre`) runs them via `entry.sh` in mode `hub`.
Base images are multi-arch, so on Apple Silicon the build runs natively (arm64) —
no Rosetta required.

---

## Prerequisites

- [ ] **Docker** with the Compose plugin, running
      (`docker compose version` must work)
- [ ] **An OpenAI-compatible embedding endpoint** reachable from the containers:
      - Recommended: **LM Studio** on the host — load `mxbai-embed-large-v1`
        (1024 dimensions) and enable the local server on port **1234**
      - Any other OpenAI-compatible endpoint works (OpenAI, Ollama `/v1`, TEI, …)
- [ ] **A host directory** containing the project(s) you want to index
      (this becomes `PROJECTS_HOME`)
- [ ] Ports **80** and **7007** free on the host (or choose alternatives via
      `APACHE_PORT` / `HUB_PORT`)

> **Linux note:** `host.docker.internal` is not defined by default on Linux.
> The compose file already maps it via `extra_hosts: host-gateway`, so the
> defaults work out of the box on Linux, macOS and Windows.

---

## Installation (Step by Step)

### Step 1 — Clone the repository

```bash
git clone https://github.com/spraener/prjxp.git
cd prjxp
```

### Step 2 — Create and configure `.env`

Docker Compose reads a `.env` file from the project directory for variable
substitution. Start from the provided template:

```bash
cp .env.docker .env
$EDITOR .env        # or: nano .env
```

You **must** set at least these two values (everything else has working defaults):

| Variable | What to put |
|---|---|
| `PROJECTS_HOME` | Absolute path to the directory containing your project(s), e.g. `/Users/me/Projekte` |
| `EMBEDDING_API_KEY` | Your embedding provider's API key. LM Studio accepts any non-empty value, e.g. `lm-studio` |

The full set of `.env` variables is documented in
[Environment Variables Reference](#a-env-file-read-by-docker-compose).

### Step 3 — Start your embedding provider (LM Studio example)

1. Open LM Studio → **My Models** → load `mxbai-embed-large-v1`
   (MixedBrew AI, 1024 dimensions).
2. **Developer** tab → enable the local server on port `1234`.
3. Verify from your shell:

   ```bash
   curl -s http://localhost:1234/v1/models | grep mxbai
   # expect: "id":"mxbai-embed-large-v1"
   ```

> ⚠️ The model name you use here **must** match `EMBEDDING_MODEL_NAME` in `.env`,
> and its vector dimension **must** match `LUCENE_VECTOR_DIMENSION`.
> Changing the model later invalidates all existing index data.

### Step 4 — Build and start

```bash
docker compose up -d --build
```

- **First build takes several minutes** — Gradle runs inside the container and
  downloads all dependencies. Subsequent builds are much faster (Docker layer cache).
- The frontend image is built from `./frontend` (`npm ci` + Angular production build).

Watch the startup:

```bash
docker compose logs -f hub
# expect: Spring Boot banner, "Hub import poller active: importDir=/import, …"
```

### Step 5 — Verify

See the [Post-Install Verification Checklist](#post-install-verification-checklist) below.

---

## Post-Install Verification Checklist

Work through these in order; each one should pass before you move on:

- [ ] **All containers running**
      ```bash
      docker compose ps
      # 3 services: prjxp-apache-proxy, prjxp-frontend, prjxp-hub — all "running"
      ```
- [ ] **Web UI loads**
      Open `http://localhost/prjxp/` (or `http://localhost:${APACHE_PORT}/prjxp/`)
      → the Angular dashboard renders, project list is empty.
- [ ] **Hub health endpoint** (via the proxy)
      ```bash
      curl -s http://localhost/prjxp/system/health
      # expect: JSON health payload, HTTP 200
      ```
- [ ] **MCP endpoint reachable** (direct host port)
      ```bash
      curl -s -o /dev/null -w '%{http_code}' http://localhost:7007/mcp
      # expect: 200 or 400/405 (endpoint exists; a bare GET without an MCP
      # session is rejected — that still proves the port is open)
      ```
- [ ] **Embedding provider reachable from inside the container**
      ```bash
      docker compose exec hub curl -s http://host.docker.internal:1234/v1/models | grep mxbai
      ```
- [ ] **A test project becomes `ready`** — add a small project (see
      [Using prjxp → Adding Projects](#adding-projects)) and watch it walk through
      `importing → chunking → embedding → ready`:
      ```bash
      curl -s http://localhost/prjxp/projects | python3 -m json.tool
      ```

---

## Environment Variables Reference

prjxp's configuration is layered. From most to least specific:

1. **`.env` file** (repo root) — read by *docker compose* for `${VAR}` substitution
   in `docker-compose.yml`. This is your primary configuration surface.
2. **Hub container environment** — set in the `environment:` block of
   `docker-compose.yml` (some values interpolated from `.env`).
3. **Baked-in config** — `docker/application.yaml.docker` is copied into the image
   as `/app/application.yaml`; its `${VAR:default}` placeholders fall back to the
   defaults listed below when no container env var is set.
4. **Image-level `ENV`** — defaults in the `Dockerfile`, overridable at runtime.

> ⚠️ **Naming gotcha:** the baked-in config reads `EMBEDDING_API_BASE_URL` /
> `EMBEDDING_MODEL_NAME` **without** a `PRJXP_` prefix. Spring placeholder
> resolution is exact-name, so variables with a different name are silently
> ignored. The compose file and this guide use the non-prefixed names on purpose.

### A. `.env` file (read by docker compose)

| Variable | Default | Required | Description | Example |
|---|---|---|---|---|
| `APACHE_PORT` | `80` | No | Host port of the Apache reverse proxy — the Web UI entry point. | `APACHE_PORT=8080` → Web UI at `http://localhost:8080/prjxp/` |
| `PROJECTS_HOME` | `./import` | **Yes** (set a real path) | Host directory bind-mounted to `/import`. The hub scans it for live projects (`prjxp.yaml` markers) and picks up tar archives dropped in. Must be a directory that exists (compose creates it if missing). | `PROJECTS_HOME=/Users/me/Projekte` |
| `EMBEDDING_API_KEY` | `dummy` | **Yes** (for real providers) | API key sent to the embedding endpoint. LM Studio accepts any non-empty value; OpenAI needs a real `sk-…` key. | `EMBEDDING_API_KEY=lm-studio` |
| `EMBEDDING_API_BASE_URL` | `http://host.docker.internal:1234/v1` | No | OpenAI-compatible base URL of the embedding endpoint, **as seen from inside the containers**. For a provider on the host use `host.docker.internal`; for one in another container use its service name. | `http://host.docker.internal:1234/v1` (LM Studio) · `https://api.openai.com/v1` |
| `EMBEDDING_MODEL_NAME` | `mxbai-embed-large-v1` | No | Model name/ID of the embedding model. Must match what your provider serves, and its dimension must equal `LUCENE_VECTOR_DIMENSION`. | `mxbai-embed-large-v1` (1024) · `text-embedding-3-small` (OpenAI, 1536 — then set the dimension too) |
| `LUCENE_VECTOR_DIMENSION` | `1024` | No | Vector dimension of the Lucene index. **Must equal your model's output dimension.** Changing it invalidates all existing index data (re-embed everything). | `1024` for mxbai-embed-large-v1 · `1536` for text-embedding-3-small |
| `HUB_PORT` | `7007` | No | Host port of the MCP endpoint: `http://localhost:${HUB_PORT}/mcp`. | `7007` (default) · `8123` if 7007 is taken |
| `PRJXP_HUB_URL` | `http://hub:7007` | No | Base URL of the backend **as seen from inside the Docker network** (scheme + service name + port). The frontend's nginx proxies all API calls here at container start. Change it if your backend runs under a different service name or port. | `PRJXP_HUB_URL=http://osb-mcp:8080` |
| `MCP_CORS_ALLOWED_PATTERNS` | `http://10.*.*.*,http://172.16.*.*,http://192.168.*.*,http://localhost:*` | No | Comma-separated CORS origin patterns for the REST API (**no spaces after commas**). Browsers send an `Origin` header on POST/PUT/DELETE; origins not matching these patterns are rejected with **403**. Add your public hostname when the Web UI is served under a different name or scheme (e.g. behind an https reverse proxy). | `MCP_CORS_ALLOWED_PATTERNS=http://localhost:*,https://prjxp.example.com` |

### B. Hub container environment (set in `docker-compose.yml`)

These are set on the `hub` service. The first column shows the value from the
compose file; values marked *from `.env`* are interpolated.

| Variable | Value in compose | Description |
|---|---|---|
| `LUCENE_INDEX_PATH` | `/data/lucene-index` | Lucene index location inside the `prjxp-hub-data` volume (persistent). All projects share this one index; chunks are stamped with their project name. |
| `LUCENE_VECTOR_DIMENSION` | *from `.env`, default `1024`* | See table A. |
| `PRJXP_HUB_IMPORT_DIR` | `/import` | Container path of the import root (bind-mounted from `PROJECTS_HOME`). The poller lists this directory every ~5 s for new tar archives and live-project markers. |
| `PRJXP_HUB_PROJECTS_ROOT` | `/projects` | Container path where imported archives are extracted (named volume `prjxp-projects`, persistent). |
| `PRJXP_HUB_SCAN_EXCLUDE_DIR_NAMES` | `.git,.svn,.hg,node_modules,branches,tags,.idea,.vs` | Comma-separated, case-insensitive list of directory names the live-project scan never enters. Extend it for your own noise directories (e.g. add `dist,target`). |
| `PRJXP_HUB_SCAN_MAX_DEPTH` | `8` | Maximum recursion depth (directory levels below `/import`) of the live-project marker scan. Raise only if a real project's `prjxp.yaml` sits deeper than 8 levels below the import root. |
| `SERVER_PORT` | `7007` | Port the Spring Boot app listens on **inside** the container. Keep in sync with `HUB_PORT`'s target and the nginx proxy config; no reason to change. |
| `EMBEDDING_API_BASE_URL` | *from `.env`, default `http://host.docker.internal:1234/v1`* | See table A. |
| `EMBEDDING_MODEL_NAME` | *from `.env`, default `mxbai-embed-large-v1`* | See table A. |
| `SKIP_EMBEDDING_SERVER` | `"true"` | Skip starting an in-container embedding server. **Keep `true`:** the current image does not ship a TEI binary, and you use an external provider. (If set to anything else, `entry.sh` tries to run `text-embeddings-router`, which is not installed and will crash the container.) |
| `EMBEDDING_API_KEY` | *from `.env`, default `dummy`* | See table A. |
| `MCP_CORS_ALLOWED_PATTERNS` | *from `.env`, default `http://10.*.*.*,…localhost:*`* | See table A. |

### C. Image-level defaults (`Dockerfile` / `entry.sh`)

Baked into the image; you normally never touch these. Listed for completeness —
all can be overridden by setting a container env var of the same name.

| Variable | Default | Description |
|---|---|---|
| `SPRING_CONFIG_LOCATION` | `file:/app/` | Fallback config location. `entry.sh` overrides this at runtime: if you mount a volume containing `application.yml`/`.yaml` at `/app-source`, that file wins over the baked-in one. |
| `JAVA_OPTS` | `-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 --add-modules jdk.incubator.vector` | JVM tuning for the container (75 % of the container memory limit). Adjust if you set a `mem_limit`/`deploy.resources` on the hub service. |
| `SERVER_PORT` | `7007` | Same as in table B (compose overrides it). |
| `LUCENE_INDEX_PATH` | `.prjxp-data/lucene-index` | Relative default for non-hub modes; compose overrides to `/data/lucene-index`. |
| `LUCENE_VECTOR_DIMENSION` | `1024` | Same as in table B. |
| `MODEL_ID`, `TEI_PORT` | *(unset)* | Only consumed when `SKIP_EMBEDDING_SERVER != true`, to start the in-container TEI server. The current image does not install TEI — keep `SKIP_EMBEDDING_SERVER=true`. |

---

## Using prjxp

### Web UI

Open `http://localhost/prjxp/` (or your `APACHE_PORT`). The dashboard lists all
registered projects with their lifecycle status; non-ready projects are disabled.

### MCP client configuration

The MCP endpoint is `http://localhost:7007/mcp` (STREAMABLE HTTP protocol).

**opencode (V2)** — easiest via CLI:

```bash
opencode mcp add prjxp --global --url http://localhost:7007/mcp
opencode mcp list        # expect: "✓ prjxp  connected"
```

…or by hand in `~/.config/opencode/opencode.jsonc`:

```jsonc
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "servers": {
      "prjxp": {
        "type": "remote",
        "url": "http://localhost:7007/mcp",
        "oauth": false
      }
    }
  }
}
```

(`oauth: false` because the prjxp endpoint is unauthenticated; without it,
opencode would try an OAuth sign-in flow that the server does not offer.)

**GitHub Copilot (VS Code)** — `.vscode/mcp.json`:

```json
{
  "mcpServers": {
    "prjxp": {
      "type": "streamable-http",
      "url": "http://localhost:7007/mcp"
    }
  }
}
```

**GitHub Copilot (JetBrains):** Settings → Tools → MCP Servers → add name
`prjxp`, type `streamable-http`, URL `http://localhost:7007/mcp`.

> If you changed `HUB_PORT`, adjust the URL accordingly.
> Browser-based MCP clients need a CORS-allowed origin; the default allowed
> patterns cover `http://localhost:*` and all RFC 1918 ranges.

### Adding projects

Two ways — both detected within ~5 seconds by the import poller:

**1. Tar archive (imported, copied into the hub)**

```bash
cd /path/to/your-project
tar czf "$PROJECTS_HOME/your-project.tar" .        # files at archive root
# or from the parent dir (a single top-level directory is stripped automatically):
tar czf "$PROJECTS_HOME/your-project.tar" your-project/
```

Accepted: `.tar`, `.tgz`, `.tar.gz`. Limits: 2 GB archive / 4 GB extracted /
100 k entries. Zip-slip paths, symlinks and hardlinks are rejected.
Re-dropping an archive with an existing project name **replaces** it (old index
data wiped). Failed archives are renamed to `*.tar.failed` in `$PROJECTS_HOME` —
delete them once fixed.

**2. Live project (in place, via marker file)**

```bash
cd /path/to/your-project && touch prjxp.yaml      # or: prjxp.yml
```

The hub registers the project and runs chunk + embed from the live tree. It never
writes into your project (JSONL output goes to `/data/chunks/<name>.jsonl`).
The marker file doubles as per-project config (`name`, `rootDir`,
`chunoWhiteList` — default `java,ts` — `tibedBatchSize` — default 32).
**Remove:** delete the marker file or the directory → deregistered on next poll,
index data wiped.

> **Tip:** mount `PROJECTS_HOME` as narrowly as possible (a folder containing
> only the projects you want, or a folder of symlinks to them). A broad mount
> (an entire drive) forces the ~5 s poller to walk every unrelated subtree —
> use `PRJXP_HUB_SCAN_EXCLUDE_DIR_NAMES`, `.prjxp-exclude` marker files, or
> `PRJXP_HUB_SCAN_MAX_DEPTH` to prune noise.

### Managing projects via REST (through the proxy)

```bash
# List all projects with lifecycle status (importing/chunking/embedding/ready/failed)
curl -s http://localhost/prjxp/projects | python3 -m json.tool

# Re-run the full pipeline (chunk + embed) — e.g. after code changes in a live project
curl -X POST http://localhost/prjxp/projects/your-project/reindex

# Delete a project (scoped index wipe + directory removal)
curl -X DELETE http://localhost/prjxp/projects/your-project
```

---

## Day-2 Operations

| Task | Command |
|---|---|
| Logs (all services) | `docker compose logs -f` |
| Logs (hub only, follow) | `docker compose logs -f hub` |
| Restart everything | `docker compose restart` |
| Stop (keep data) | `docker compose down` |
| Update after `git pull` | `docker compose up -d --build` (rebuilds changed images, recreates containers) |
| Full teardown **including data** | `docker compose down -v` (⚠️ deletes the Lucene index and extracted projects) |
| Rebuild images from scratch | `docker compose build --no-cache && docker compose up -d` |

**Data persistence:** all state lives in two named volumes —
`prjxp-hub-data` (`/data`: Lucene index + chunk JSONL) and `prjxp-projects`
(`/projects`: extracted imports). Your live projects in `PROJECTS_HOME` are never
modified. Back up by exporting the volumes:

```bash
docker run --rm -v prjxp-hub-data:/data -v "$PWD/backup":/backup alpine \
  tar czf /backup/prjxp-hub-data.tar.gz -C /data .
```

**Embedding provider must be running** whenever the hub embeds (initial import,
reindex). If it is down, projects stay in `embedding`/`failed` state; start the
provider and trigger a reindex.

---

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| No projects found, `./import` next to the compose file is empty | You skipped `.env`, so `PROJECTS_HOME` fell back to its default (`./import`). Create it (`cp .env.docker .env`) and point `PROJECTS_HOME` at your real projects directory, then `docker compose up -d`. |
| Web UI loads but project list stays empty | The hub scans `PROJECTS_HOME` (mounted at `/import`). Check the directory exists and contains a `prjxp.yaml` marker or tar archive; check `docker compose logs hub` for poller warnings. |
| Project stuck in `embedding`, errors mention the model | Model name/dimension mismatch: `EMBEDDING_MODEL_NAME` must be a model actually loaded in your provider, and `LUCENE_VECTOR_DIMENSION` must equal its output dimension. |
| Embedding errors: `Connection refused` / `No route to host` | The provider is not running on the expected address. Verify with `docker compose exec hub curl -s http://host.docker.internal:1234/v1/models`. On a non-Desktop Linux setup, check that `host.docker.internal` resolves (`docker compose exec hub getent hosts host.docker.internal`). |
| MCP client can't connect to `http://localhost:7007/mcp` | Port conflict or firewall. Check `docker compose ps` shows the published port; try another `HUB_PORT`. |
| Web UI loads but API calls fail with 502 | `PRJXP_HUB_URL` points at a service that is not running (or wrong port). Check the rendered config: `docker compose exec frontend cat /etc/nginx/conf.d/default.conf` and verify the target resolves: `docker compose exec frontend getent hosts <name>`. |
| Hub REST endpoints (`/prjxp/projects`, `/prjxp/system/health`) return 404, but `ping` works | The backend is not running in hub mode: all hub beans are gated on `prjxp.hub.enabled`. In the compose deployment `entry.sh` sets `PRJXP_HUB_ENABLED=true` automatically (command `hub`). If you run the image with another mode or build your own image from the JARs, set `PRJXP_HUB_ENABLED=true` **and** `PRJXP_CLI_ENABLED=false` in the container environment. |
| GET works, but POST/PUT/DELETE fail with **403** (CORS) | The browser sends an `Origin` header on mutating requests; the backend rejects origins not in `mcp.cors.allowed-patterns` (default: private LAN ranges + `http://localhost:*`). This bites when the Web UI is served under a public hostname or https. Add your origin to `MCP_CORS_ALLOWED_PATTERNS` (comma-separated, no spaces) and recreate the hub. Verify: `curl -X DELETE http://localhost:7007/prjxp/projects/nope -H "Origin: https://your-host"` should return 404, not 403. |
| First scan takes minutes / nothing discovered | Broad `PROJECTS_HOME` mount with huge unrelated subtrees. Narrow the mount, or extend `PRJXP_HUB_SCAN_EXCLUDE_DIR_NAMES` / drop `.prjxp-exclude` markers / lower `PRJXP_HUB_SCAN_MAX_DEPTH`. |
| Container crashes on start with `MODEL_ID: unbound variable` | You set `SKIP_EMBEDDING_SERVER` to something other than `"true"`. The image ships no TEI binary — set it back to `"true"` and use an external provider. |
| Dimension error when re-embedding after a model change | The Lucene index was built with the old dimension. Wipe it: `docker compose down -v` (or delete `/data/lucene-index` in the volume) and re-import. |
| `*.tar.failed` files accumulating in `PROJECTS_HOME` | Import validation rejected the archive (size/entry limits, symlinks, zip-slip). Inspect, fix, delete the `.failed` file and re-drop. |
| Stale frontend after a code change | The SPA is baked into the image at build time: `docker compose up -d --build frontend`. |

---

## Quick Reference

```bash
# Install
cp .env.docker .env && $EDITOR .env     # set PROJECTS_HOME + EMBEDDING_API_KEY
docker compose up -d --build

# Verify
curl -s http://localhost/prjxp/system/health
open  http://localhost/prjxp/

# Add a live project
touch /path/to/project/prjxp.yaml

# Manage
curl -s http://localhost/prjxp/projects
curl -X POST   http://localhost/prjxp/projects/<name>/reindex
curl -X DELETE http://localhost/prjxp/projects/<name>

# MCP endpoint for AI clients
http://localhost:7007/mcp
```
