# Docker

PRJXP Hub ships as a self-hosted Docker stack. This chapter covers the multi-stage image build, the compose services, all environment variables, volume layout, how to update the stack or the in-app documentation without a full rebuild, and the reverse-proxy / CORS configuration.

## Image Build Stages

The `Dockerfile` uses four stages to keep the final image small and the build cacheable:

| Stage | Base image | Purpose | Cache invalidation |
|-------|-----------|---------|--------------------|
| `gradle-wrapper` | `eclipse-temurin:21-jdk-jammy` | Downloads the Gradle distribution via the wrapper | Only when `gradlew` or `gradle/wrapper/` change |
| `gradle-deps` | `gradle-wrapper` | Resolves all module dependencies into the Gradle cache | Only when `settings.gradle`, `build.gradle`, or `gradle/libs.versions.toml` change |
| `app-builder` | `gradle-deps` | Compiles the three fat JARs (`shadowJar`) | Every source change |
| *(final)* | `eclipse-temurin:21-jre-jammy` | Runtime image with JRE, system deps, and the three JARs | Only when JARs, docs, or entry script change |

The final stage installs the following system packages:

```bash
libgomp1 ca-certificates curl git
```

It then copies:

- `/app/chunk-norris-all.jar`
- `/app/tibed-all.jar`
- `/app/mcp-server-all.jar`
- `mcp-server/doc` → `/app/doc` (in-app documentation browser)
- `prjxp-common/docker/entry.sh` → `/app/entry.sh`
- `docker/application.yaml.docker` → `/app/application.yaml` (fallback config)

The working directory is set to `/app-source`, and the entrypoint is:

```dockerfile
ENTRYPOINT ["/app/entry.sh"]
CMD ["hub"]
```

## Services and Networking

The `docker-compose.yml` defines three services on a shared bridge network (`prjxp-network`):

| Service | Image | Host port | Internal port | Role |
|---------|-------|-----------|---------------|------|
| `apache-proxy` | `httpd:2.4-alpine` | `${APACHE_PORT:-80}` | 80 | Reverse proxy / TLS termination |
| `frontend` | `prjxp-frontend` (built from `./frontend`) | — (exposed only) | 8080 | Web UI; proxies API calls to the hub |
| `hub` | `prjxp-hub` (built from `.`) | `${HUB_PORT:-7007}` | 7007 | MCP endpoint, import watcher, in-process pipeline |

Dependency chain: `apache-proxy` → `frontend` → `hub`.

The `hub` service exposes port 7007 on the host for direct MCP client access:

```
http://localhost:7007/mcp
```

Both `apache-proxy` and `hub` declare:

```yaml
extra_hosts:
  - "host.docker.internal:host-gateway"
```

so that container-internal references to `host.docker.internal` resolve to the Docker host (needed for an external embedding model such as LM Studio).

### Entry-point modes

`/app/entry.sh` accepts a single mode argument:

| Mode | JAR executed | Behaviour |
|------|-------------|-----------|
| `chunk` | `chunk-norris-all.jar` | One-shot chunking pipeline; exits when done |
| `embed` | `tibed-all.jar` | Starts `text-embeddings-router` (unless `SKIP_EMBEDDING_SERVER=true`), waits for `/health`, then runs the embedding pipeline |
| `hub` | `mcp-server-all.jar` | Exports `PRJXP_HUB_ENABLED=true`, runs the long-lived server (MCP endpoint, import watcher, in-process pipeline) |

The `hub` mode is the default (`CMD ["hub"]`). When `PRJXP_HUB_ENABLED` is **not** set, the same JAR runs as a CLI pipeline (chunk + embed + docpipe) and exits.

### Runtime env-file loading

Before dispatching, the entrypoint calls `load_runtime_env_file`, which sources `${PRJXP_ENV_FILE:-/data/prjxp.env}`. The file may **only add** variables; it never overrides values already present in the container environment (compose `environment` and the shell take precedence). This prevents host-oriented values (e.g. a relative `LUCENE_INDEX_PATH`) from clobbering container-specific settings.

### Spring config resolution

`resolve_config_location` checks for `/app-source/application.yml` or `/app-source/application.yaml`:

- If found → `optional:file:/app-source/,optional:file:/app/`
- Otherwise → `file:/app/` (the baked-in `application.yaml`)

## Environment Variables

### Baked into the image (Dockerfile `ENV`)

| Variable | Default | Purpose |
|----------|---------|---------|
| `SPRING_CONFIG_LOCATION` | `file:/app/` | Fallback Spring config path |
| `LUCENE_INDEX_PATH` | `.prjxp-data/lucene-index` | Relative Lucene index path (overridden at runtime) |
| `LUCENE_VECTOR_DIMENSION` | `1024` | Embedding vector dimension |
| `JAVA_OPTS` | `-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 --add-modules jdk.incubator.vector` | JVM tuning for containers |
| `SERVER_PORT` | `7007` | HTTP listen port |

### Compose-level (set in `docker-compose.yml` / `.env`)

| Variable | Default | Service | Purpose |
|----------|---------|---------|---------|
| `APACHE_PORT` | `80` | apache-proxy | Host port for the reverse proxy |
| `PRJXP_HUB_URL` | `http://hub:7007` | frontend | Upstream URL for API proxying |
| `HUB_PORT` | `7007` | hub | Host port for the MCP endpoint |
| `PROJECTS_HOME` | `./import` | hub | Host directory bind-mounted to `/import` |
| `PRJXP_ENV_FILE` | `./.env` | hub | Path to the runtime env file (mounted read-only to `/data/prjxp.env`) |
| `LUCENE_VECTOR_DIMENSION` | `1024` | hub | Vector dimension (overrides image default) |
| `PRJXP_HUB_GITHUB_TOKEN_KEY` | *(empty)* | hub | Passphrase for encrypting GitHub PATs (private repos) |
| `EMBEDDING_API_BASE_URL` | `http://host.docker.internal:1234/v1` | hub | External embedding provider base URL |
| `EMBEDDING_MODEL_NAME` | `mxbai-embed-large-v1` | hub | Embedding model name |
| `EMBEDDING_API_KEY` | `dummy` | hub | API key for the embedding provider |
| `MCP_CORS_ALLOWED_PATTERNS` | `http://10.*.*.*,http://172.16.*.*,http://192.168.*.*,http://localhost:*` | hub | Comma-separated CORS origin patterns (no spaces) |

> **Note:** The embedding variables in `application.yaml.docker` use the names `EMBEDDING_API_BASE_URL`, `EMBEDDING_MODEL_NAME`, etc. (no `PRJXP_` prefix). The compose `environment` block must match those names exactly.

### Fixed in the compose `environment` block (not overridable via `.env`)

| Variable | Value |
|----------|-------|
| `PRJXP_ENV_FILE` | `/data/prjxp.env` |
| `LUCENE_INDEX_PATH` | `/data/lucene-index` |
| `PRJXP_HUB_IMPORT_DIR` | `/import` |
| `PRJXP_HUB_PROJECTS_ROOT` | `/projects` |
| `PRJXP_HUB_SCAN_EXCLUDE_DIR_NAMES` | `.git,.svn,.hg,node_modules,branches,tags,.idea,.vs` |
| `PRJXP_HUB_SCAN_MAX_DEPTH` | `8` |
| `SKIP_EMBEDDING_SERVER` | `true` |
| `SERVER_PORT` | `7007` |
| `PRJXP_DOCS_DIR` | `/app/doc` |

## Volumes and Data Persistence

The `hub` service mounts the following volumes:

| Mount | Type | Purpose |
|-------|------|---------|
| `${PROJECTS_HOME:-./import}` → `/import` | Bind | Staging area for uploaded / imported project archives |
| `prjxp-projects` → `/projects` | Named | Persistent project working directories |
| `prjxp-github` → `/github` | Named | Cloned GitHub repositories (survive container restarts) |
| `prjxp-hub-data` → `/data` | Named | Lucene index, runtime env file, and other hub data |
| `${PRJXP_ENV_FILE:-./.env}` → `/data/prjxp.env` | Bind (read-only) | Runtime environment overrides |
| `./language-packs` → `/app-source/language-packs` | Bind | External Language Pack plugin JARs (loaded at startup, never baked into the image) |

The Dockerfile also declares `VOLUME` for `/app-source` and `/app-source/.prjxp-data/lucene-index` as a safety net, but in the compose setup the Lucene index lives on the `prjxp-hub-data` named volume at `/data/lucene-index` (set via `LUCENE_INDEX_PATH=/data/lucene-index`).

### Language Pack plugins

Plugin JARs (e.g. `lp-csharp-plugin.jar`) are placed in `./language-packs/` on the host and mounted to `/app-source/language-packs`. The default plugin directory (`prjxp.language-packs.dir`, default `./language-packs`) resolves relative to `WORKDIR /app-source`. Override with `PRJXP_LANGUAGE_PACKS_DIR` if needed.

## Updating the Stack and Documentation

### Rebuilding the image

```bash
docker compose build hub
docker compose up -d
```

Because of the multi-stage cache, a source-only change re-runs only the `app-builder` and final stages. A change to `gradle/libs.versions.toml` additionally re-runs `gradle-deps`. A change to the wrapper re-runs all stages.

### Updating the in-app documentation without a rebuild

The documentation served by the in-app docs browser is baked into the image at `/app/doc` (from `mcp-server/doc`). To update it without rebuilding, bind-mount your own directory (e.g. DocPipe output) onto that path in `docker-compose.yml`:

```yaml
volumes:
  - ./my-docs:/app/doc
```

Then restart the hub:

```bash
docker compose up -d hub
```

The `PRJXP_DOCS_DIR` environment variable (set to `/app/doc` in the compose file) tells the application where to look.

### Updating the runtime env file

Edit `./.env` (or whatever path `PRJXP_ENV_FILE` points to) on the host, then:

```bash
docker compose restart hub
```

The entrypoint re-sources the file on every container start. Remember: the file can only **add** variables; it cannot override values set in the compose `environment` block.

## Reverse Proxy and CORS

### Apache reverse proxy

The `apache-proxy` service runs `httpd:2.4-alpine` in the foreground and mounts two configuration files:

```yaml
volumes:
  - ./docker/apache-httpd.conf:/usr/local/apache2/conf/httpd.conf
  - ./docker/apache-proxy.conf:/usr/local/apache2/conf/extra/prjxp-proxy.conf
```

It listens on `${APACHE_PORT:-80}` and forwards to the `frontend` container (port 8080), which in turn proxies API calls to `http://hub:7007`.

The hub's Spring configuration sets:

```yaml
server:
  forward-headers-strategy: framework
```

so that `X-Forwarded-*` headers from the proxy are honoured for scheme/host detection.

### CORS configuration

Browsers send an `Origin` header on `POST`/`PUT`/`DELETE` requests to the MCP endpoint. Origins that do not match the allowed patterns are rejected with **403**.

The default pattern list covers private-network and localhost origins:

```
http://10.*.*.*,http://172.16.*.*,http://192.168.*.*,http://localhost:*
```

When deploying behind a reverse proxy with a public hostname, add it:

```bash
MCP_CORS_ALLOWED_PATTERNS="http://localhost:*,https://my-host.example.com"
```

Place this in your `.env` file. The value is a comma-separated list with **no spaces** after commas.

### Multipart upload limits

The Spring configuration sets:

```yaml
spring:
  servlet:
    multipart:
      max-file-size: 2GB
      max-request-size: 2GB
```

This must be ≥ the hub's import limit (`prjxp.hub.max-tar-bytes`, default 2 GiB). Spring's default of 1 MB per file would otherwise reject project archives.

_This document was generated with Doc|Pipe._

