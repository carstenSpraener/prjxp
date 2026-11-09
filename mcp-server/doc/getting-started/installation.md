# Installation

This chapter covers how to deploy the PRJXP Hub as a self-hosted Docker Compose stack. The Hub provides a web UI for importing projects, chunking source code, embedding chunks, and answering questions via retrieval-augmented generation.

## Prerequisites

Before starting the stack, ensure the following are available on the host:

- **Docker Engine** with the Compose plugin (`docker compose` command).
- **An external embedding model** reachable from the host. The Hub calls an OpenAI-compatible embeddings API by default:

  | Variable | Default | Purpose |
  |---|---|---|
  | `EMBEDDING_API_BASE_URL` | `http://host.docker.internal:1234/v1` | Base URL of the embedding provider |
  | `EMBEDDING_MODEL_NAME` | `mxbai-embed-large-v1` | Model identifier sent to the provider |
  | `EMBEDDING_API_KEY` | `dummy` | API key (set a real key if your provider requires one) |

  The default assumes a [text-embeddings-inference](https://github.com/huggingface/text-embeddings-inference) (TEI) server running on the host at port 1234. If you use a different provider (e.g. LM Studio, Ollama, a cloud API), override these variables in your `.env` file.

- **A GitHub Personal Access Token** (optional, only for private repositories). The token is encrypted at rest using the passphrase in `PRJXP_HUB_GITHUB_TOKEN_KEY`.

- **Sufficient RAM and CPU** for the JVM. The container is tuned with `-XX:MaxRAMPercentage=75.0` and the G1 garbage collector.

## Architecture and Services

The Compose file defines three containers on a shared bridge network (`prjxp-network`):

| Service | Image | Host Port | Internal Port | Role |
|---|---|---|---|---|
| `apache-proxy` | `httpd:2.4-alpine` | `${APACHE_PORT:-80}` | 80 | Reverse proxy; routes external traffic to the frontend |
| `frontend` | `prjxp-frontend` (built from `./frontend`) | — (exposed only) | 8080 | Web UI; proxies API calls to the Hub |
| `hub` | `prjxp-hub` (built from the repo root) | `${HUB_PORT:-7007}` | 7007 | Core backend: MCP endpoint, import watcher, in-process chunk → embed → retrieve pipeline |

Dependency chain: `apache-proxy` → `frontend` → `hub`.

The Hub exposes its MCP endpoint at `http://localhost:7007/mcp` (or `http://<host>:${HUB_PORT}/mcp` when `HUB_PORT` is overridden). The frontend communicates with the Hub over the internal network at `http://hub:7007` (configurable via `PRJXP_HUB_URL`).

All three containers resolve `host.docker.internal` to the Docker host gateway, which is how the Hub reaches an embedding server running on the host.

## Configuration

All runtime configuration is supplied through a single `.env` file in the project root (override the path with `PRJXP_ENV_FILE`). The file is mounted read-only into the Hub container at `/data/prjxp.env`.

### Key variables

| Variable | Default | Description |
|---|---|---|
| `APACHE_PORT` | `80` | Host port for the Apache reverse proxy |
| `HUB_PORT` | `7007` | Host port for the Hub (MCP + REST) |
| `PRJXP_HUB_URL` | `http://hub:7007` | Upstream URL the frontend uses to reach the Hub |
| `LUCENE_VECTOR_DIMENSION` | `1024` | Dimensionality of embedding vectors in the Lucene index |
| `EMBEDDING_API_BASE_URL` | `http://host.docker.internal:1234/v1` | Embedding provider base URL |
| `EMBEDDING_MODEL_NAME` | `mxbai-embed-large-v1` | Embedding model name |
| `EMBEDDING_API_KEY` | `dummy` | Embedding provider API key |
| `SKIP_EMBEDDING_SERVER` | `true` | Set to `false` to run an in-container TEI server instead of an external one |
| `PRJXP_HUB_GITHUB_TOKEN_KEY` | *(empty)* | Passphrase for encrypting GitHub PATs (private repos) |
| `MCP_CORS_ALLOWED_PATTERNS` | `http://10.*.*.*,http://172.16.*.*,http://192.168.*.*,http://localhost:*` | Comma-separated CORS origins (no spaces). Add your public hostname when behind a reverse proxy |
| `PROJECTS_HOME` | `./import` | Host directory for project imports |

### Optional: project-level Spring configuration

If you place an `application.yml` or `application.yaml` in the directory mounted at `/app-source` (the container working directory), the entrypoint script detects it and appends it to the Spring config search path:

```
optional:file:/app-source/,optional:file:/app/
```

Without that file, only the baked-in `/app/application.yaml` (derived from `docker/application.yaml.docker` at build time) is used.

### Optional: Language Pack plugins

Place external language-pack JARs (e.g. `lp-csharp-plugin.jar`) in `./language-packs/` on the host. They are mounted at `/app-source/language-packs` and loaded at startup. They are never baked into the image. Override the directory with `PRJXP_LANGUAGE_PACKS_DIR`.

### Optional: in-app documentation

The Hub serves documentation from `/app/doc` (baked in from `mcp-server/doc` at build time). To update docs without rebuilding, bind-mount your own directory:

```yaml
volumes:
  - ./my-docs:/app/doc
```

## Starting the Stack

From the repository root (where `docker-compose.yml` and `.env` reside):

```bash
# 1. Create or edit the .env file with your settings
cp .env.example .env   # if an example is provided; otherwise create manually
$EDITOR .env

# 2. Build and start all services
docker compose up -d --build

# 3. (Optional) Follow logs to watch startup
docker compose logs -f hub
```

The `--build` flag builds the `prjxp-hub` and `prjxp-frontend` images from their respective `Dockerfile`s. The Hub image is a four-stage build:

1. **gradle-wrapper** – downloads the Gradle distribution (cached; invalidated only when wrapper files change).
2. **gradle-deps** – resolves all module dependencies into the Gradle cache (invalidated only when `build.gradle` / `libs.versions.toml` change).
3. **app-builder** – compiles the full source tree and produces three fat JARs: `chunk-norris-all.jar`, `tibed-all.jar`, `mcp-server-all.jar`.
4. **runtime** – a slim `eclipse-temurin:21-jre-jammy` image containing the three JARs, the entrypoint script, the baked-in `application.yaml`, and system packages (`git`, `curl`, `libgomp1`, `ca-certificates`).

To stop the stack:

```bash
docker compose down
```

To stop and remove named volumes (destroys all indexed data):

```bash
docker compose down -v
```

## Data Volumes and Persistence

The Hub container declares three named volumes and several bind mounts. All persistent state lives in the named volumes so that container recreation does not lose data.

| Volume / Mount | Container Path | Contents |
|---|---|---|
| `prjxp-projects` (named) | `/projects` | Imported and processed project files |
| `prjxp-github` (named) | `/github` | Cloned GitHub repositories (persistent across restarts) |
| `prjxp-hub-data` (named) | `/data` | Lucene index (`/data/lucene-index`), runtime `.env` copy |
| `${PROJECTS_HOME:-./import}` (bind) | `/import` | Staging area for user-supplied project archives |
| `./language-packs` (bind) | `/app-source/language-packs` | External language-pack plugin JARs |
| `${PRJXP_ENV_FILE:-./.env}` (bind, read-only) | `/data/prjxp.env` | Runtime environment variables |

The Lucene index path inside the container is `/data/lucene-index` (set by `LUCENE_INDEX_PATH` in the Compose `environment` block). This overrides the Dockerfile default of `.prjxp-data/lucene-index`, ensuring the index lands on the persistent `prjxp-hub-data` volume rather than an ephemeral anonymous volume.

The entrypoint script enforces a strict precedence rule when sourcing `/data/prjxp.env`: variables already set by the Compose `environment` block or the shell **take precedence**. The `.env` file can only *add* new variables; it cannot override container-specific settings. This prevents a host-oriented relative path (e.g. `LUCENE_INDEX_PATH=.prjxp-data/lucene-index` for local `java -jar` use) from clobbering the container path and forcing a full re-embed on every restart.

## Verifying a Healthy Startup

After `docker compose up -d`, confirm each service is running:

```bash
docker compose ps
```

Expected output (all three services in `Up` state):

```
NAME                STATUS
prjxp-apache-proxy  Up
prjxp-frontend      Up
prjxp-hub           Up
```

Check the Hub logs for a successful Spring Boot startup and MCP endpoint registration:

```bash
docker compose logs hub | tail -40
```

You should see the Spring Boot banner, the MCP server binding to port 7007, and the import watcher initialising. If `SKIP_EMBEDDING_SERVER` is set to `false`, the logs will also show the in-container `text-embeddings-router` starting and passing its `/health` check before the main application launches.

Test the MCP endpoint from the host:

```bash
curl -s http://localhost:${HUB_PORT:-7007}/mcp
```

A successful response (HTTP 200 with a JSON body) confirms the Hub is reachable.

Test the web UI through the Apache proxy:

```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:${APACHE_PORT:-80}/
```

An HTTP `200` (or a redirect to the SPA) confirms the full proxy → frontend → hub chain is operational.

If the Hub fails to start, the most common causes are:

- The embedding provider is unreachable. Verify `EMBEDDING_API_BASE_URL` points to a running service and that `host.docker.internal` resolves (the `extra_hosts` entry handles this on Linux; on Docker Desktop for Mac/Windows it is built in).
- The Lucene index dimension does not match the embedding model. Ensure `LUCENE_VECTOR_DIMENSION` equals the output dimensionality of the model named in `EMBEDDING_MODEL_NAME`.
- A stale or corrupt index on the `prjxp-hub-data` volume. As a last resort, remove the volume and re-embed: `docker compose down -v && docker compose up -d`.

_This document was generated with Doc|Pipe._

