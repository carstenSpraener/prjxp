# Docker

The PRJXP Hub ships as a self-hosted Docker stack. This chapter covers the multi-stage image build, the Compose services, all environment variables, volume layout, how to update the stack or the in-app documentation without a full rebuild, and the reverse-proxy / CORS configuration for production deployments.

## Image Build Stages

The `Dockerfile` uses four build stages to keep rebuilds fast and the final image small.

| Stage | Base image | Purpose |
|-------|-----------|---------|
| `gradle-wrapper` | `eclipse-temurin:21-jdk-jammy` | Downloads the Gradle distribution via the wrapper. Invalidated only when `gradlew` or `gradle/wrapper/` change. |
| `gradle-deps` | `gradle-wrapper` | Copies every module's `build.gradle` and `libs.versions.toml`, then runs `warmUpDependencyCache` (init script `gradle/cache-warmup.init.gradle`) to resolve all JARs into the Gradle dependency cache. Invalidated only when build scripts change. |
| `app-builder` | `gradle-deps` | Copies the full source tree and builds three fat JARs: `chunk-norris-all.jar`, `tibed-all.jar`, `mcp-server-all.jar`. |
| *(final)* | `eclipse-temurin:21-jre-jammy` | Runtime image. Installs `libgomp1`, `ca-certificates`, `curl`, `git`. Copies the three JARs, the documentation tree, `entry.sh`, and the baked-in `application.yaml`. |

Key runtime paths inside the final image:

```
/app/
├── chunk-norris-all.jar
├── tibed-all.jar
├── mcp-server-all.jar
├── doc/                  ← in-app documentation (served by the hub)
├── entry.sh              ← entrypoint script
└── application.yaml      ← fallback config (from application.yaml.docker)

/app-source/              ← WORKDIR; bind-mounted at runtime
├── language-packs/       ← VOLUME; external plugin JARs
└── .prjxp-data/
    └── lucene-index/     ← VOLUME (overridden by /data/lucene-index in compose)

/import/                  ← VOLUME; project import directory
/projects/                ← VOLUME; processed project storage
/github/                  ← VOLUME; cloned GitHub repositories
```

The image is built with:

```bash
docker build -t prjxp-hub .
```

## Services

The `docker-compose.yml` defines three services on a shared bridge network `prjxp-network`:

| Service | Image | Host port | Role |
|---------|-------|-----------|------|
| `apache-proxy` | `httpd:2.4-alpine` | `${APACHE_PORT:-80}` | Reverse proxy; terminates TLS / routes to the frontend |
| `frontend` | `prjxp-frontend` (built from `./frontend`) | *(exposed 8080, not published)* | Web UI; proxies API calls to the hub |
| `hub` | `prjxp-hub` (built from `.`) | `${HUB_PORT:-7007}` | Core backend: MCP endpoint, import watcher, in-process pipeline |

Dependency chain: `apache-proxy` → `frontend` → `hub`.

The hub container runs with `command: ["hub"]`, which invokes `/app/entry.sh hub`. The entrypoint:

1. Sources `${PRJXP_ENV_FILE:-/data/prjxp.env}` (additive only — existing variables are never overridden).
2. Resolves the Spring config location: if `/app-source/application.yml` or `/app-source/application.yaml` exists, both `/app-source/` and `/app/` are searched; otherwise only `/app/`.
3. Optionally starts a local `text-embeddings-router` (TEI) process unless `SKIP_EMBEDDING_SERVER=true`.
4. Launches `mcp-server-all.jar` with `PRJXP_HUB_ENABLED=true` exported.

The frontend container receives `PRJXP_HUB_URL` (default `http://hub:7007`) to know where to proxy API requests.

## Environment Variables

### Baked into the image (`Dockerfile` `ENV`)

| Variable | Default | Purpose |
|----------|---------|---------|
| `SPRING_CONFIG_LOCATION` | `file:/app/` | Fallback Spring config path (overridden at runtime by `entry.sh`) |
| `LUCENE_INDEX_PATH` | `.prjxp-data/lucene-index` | Relative Lucene index path (overridden by compose to `/data/lucene-index`) |
| `LUCENE_VECTOR_DIMENSION` | `1024` | Embedding vector dimension |
| `JAVA_OPTS` | `-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 --add-modules jdk.incubator.vector` | JVM tuning for containers |
| `SERVER_PORT` | `7007` | HTTP port for the hub |

### Set by `docker-compose.yml` (hub service)

| Variable | Default | Purpose |
|----------|---------|---------|
| `PRJXP_ENV_FILE` | `/data/prjxp.env` | Path to the runtime env file (mounted read-only) |
| `LUCENE_INDEX_PATH` | `/data/lucene-index` | Absolute path on the persistent data volume |
| `LUCENE_VECTOR_DIMENSION` | `${LUCENE_VECTOR_DIMENSION:-1024}` | Override vector dimension |
| `PRJXP_HUB_IMPORT_DIR` | `/import` | Directory watched for new project archives |
| `PRJXP_HUB_PROJECTS_ROOT` | `/projects` | Root for processed projects |
| `PRJXP_HUB_SCAN_EXCLUDE_DIR_NAMES` | `.git,.svn,.hg,node_modules,branches,tags,.idea,.vs` | Directories skipped during source scanning |
| `PRJXP_HUB_SCAN_MAX_DEPTH` | `8` | Maximum directory depth for scanning |
| `PRJXP_HUB_GITHUB_TOKEN_KEY` | *(empty)* | Passphrase for encrypting GitHub PATs (private repos) |
| `SERVER_PORT` | `7007` | HTTP port |
| `EMBEDDING_API_BASE_URL` | `http://host.docker.internal:1234/v1` | External embedding provider (e.g. LM Studio on the host) |
| `EMBEDDING_MODEL_NAME` | `mxbai-embed-large-v1` | Embedding model identifier |
| `SKIP_EMBEDDING_SERVER` | `true` | Set to `false` to start an in-container TEI server |
| `EMBEDDING_API_KEY` | `dummy` | API key for the embedding endpoint |
| `MCP_CORS_ALLOWED_PATTERNS` | `http://10.*.*.*,http://172.16.*.*,http://192.168.*.*,http://localhost:*` | Comma-separated CORS origin patterns (no spaces) |
| `PRJXP_DOCS_DIR` | `/app/doc` | Directory served by the in-app docs browser |

### Frontend service

| Variable | Default | Purpose |
|----------|---------|---------|
| `PRJXP_HUB_URL` | `http://hub:7007` | Upstream API address for the web UI proxy |

### Apache proxy service

| Variable | Default | Purpose |
|----------|---------|---------|
| `APACHE_PORT` | `80` | Host port for the reverse proxy |

> **Note:** The embedding and chat-model variables in `application.yaml.docker` (e.g. `EMBEDDING_API_BASE_URL`, `EMBEDDING_MODEL_NAME`) must match the names used in the baked-in YAML — they carry **no** `PRJXP_` prefix.

## Volumes and Data Persistence

### Named volumes (Docker-managed)

| Volume | Mounted at | Contents |
|--------|-----------|----------|
| `prjxp-projects` | `/projects` | Processed project data (persistent across container rebuilds) |
| `prjxp-github` | `/github` | Cloned GitHub repositories |
| `prjxp-hub-data` | `/data` | Lucene index (`/data/lucene-index`) and the runtime env file (`/data/prjxp.env`) |

### Bind mounts

| Host path | Container path | Access | Purpose |
|-----------|---------------|--------|---------|
| `${PROJECTS_HOME:-./import}` | `/import` | rw | Drop zone for project archives (tar/zip) |
| `${PRJXP_ENV_FILE:-./.env}` | `/data/prjxp.env` | **ro** | Runtime environment overrides (additive) |
| `./language-packs` | `/app-source/language-packs` | rw | External language-pack plugin JARs (e.g. `lp-csharp-plugin.jar`) |

The Dockerfile also declares `VOLUME /app-source` and `VOLUME /app-source/.prjxp-data/lucene-index` as fallbacks; in the Compose stack these are superseded by the named `prjxp-hub-data` volume mounted at `/data`.

### Runtime env file semantics

`entry.sh` sources `/data/prjxp.env` **additively**: it snapshots the full environment, sources the file, then restores the snapshot. Variables already set by Compose `environment:` or the shell always take precedence. This prevents host-oriented values (e.g. a relative `LUCENE_INDEX_PATH` for local `java -jar` use) from clobbering container-specific settings and sending the Lucene index to an ephemeral anonymous volume.

## Updating the Stack and Documentation

### Rebuilding the image

```bash
docker compose build hub
docker compose up -d
```

Because of the multi-stage cache, a source-only change re-runs only the `app-builder` stage (Gradle distribution and dependency cache are reused). A change to `build.gradle` or `libs.versions.toml` additionally re-runs `gradle-deps`.

### Updating the in-app documentation without a rebuild

The documentation tree is baked into the image at `/app/doc` (copied from `mcp-server/doc`). To update it without rebuilding, bind-mount your own directory onto that path in `docker-compose.yml`:

```yaml
services:
  hub:
    volumes:
      # ... existing volumes ...
      - ./my-docs:/app/doc
```

Then restart the hub:

```bash
docker compose up -d hub
```

This is the intended way to deploy DocPipe output or any other generated documentation.

### Updating the frontend

```bash
docker compose build frontend
docker compose up -d frontend
```

## Reverse Proxy and CORS

### Apache reverse proxy

The `apache-proxy` service runs `httpd:2.4-alpine` in the foreground and mounts two configuration files:

```yaml
volumes:
  - ./docker/apache-httpd.conf:/usr/local/apache2/conf/httpd.conf
  - ./docker/apache-proxy.conf:/usr/local/apache2/conf/extra/prjxp-proxy.conf
```

It listens on `${APACHE_PORT:-80}` and routes to the `frontend` container. The `extra_hosts` entry maps `host.docker.internal` to `host-gateway` so the proxy can reach host-side services if needed.

The hub's `application.yaml` sets:

```yaml
server:
  forward-headers-strategy: framework
```

This tells Spring Boot to honour `X-Forwarded-*` headers, so the application sees the correct external scheme and host when behind the proxy.

### CORS configuration

Browsers send an `Origin` header on `POST`/`PUT`/`DELETE` requests to the MCP endpoint. Origins that do not match the allowed patterns are rejected with **403**.

The default pattern list covers private-network and localhost origins:

```
http://10.*.*.*,http://172.16.*.*,http://192.168.*.*,http://localhost:*
```

When deploying behind a reverse proxy with a public hostname, add it to `MCP_CORS_ALLOWED_PATTERNS` in your `.env` file:

```bash
# .env
MCP_CORS_ALLOWED_PATTERNS="http://localhost:*,https://my-host.example.com"
```

> **Important:** Separate patterns with commas and **do not include spaces** after the commas.

### MCP endpoint

The hub exposes the MCP (Model Context Protocol) endpoint at:

```
http://localhost:7007/mcp
```

The protocol is `STREAMABLE` (configured via `spring.ai.mcp.server.protocol`). The frontend proxies API calls to this endpoint using the `PRJXP_HUB_URL` variable.

_This document was generated with Doc|Pipe._

