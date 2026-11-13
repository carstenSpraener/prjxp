# Docker

PRJXP ships as a self-hosted Docker stack. The `docker-compose.yml` orchestrates three containers on a shared bridge network, and the multi-stage `Dockerfile` produces a single runtime image containing all three Java services. This chapter covers the build pipeline, the service topology, configuration, persistence, and the reverse-proxy layer.

## Image Build Stages

The `Dockerfile` uses four stages. Each stage is designed so that the most expensive layers (Gradle distribution download, dependency resolution) are invalidated only when their inputs actually change.

| Stage | Base image | Purpose | Invalidation trigger |
|-------|-----------|---------|---------------------|
| `gradle-wrapper` | `eclipse-temurin:21-jdk-jammy` | Downloads the Gradle distribution via the wrapper | `gradlew` or `gradle/wrapper/` files change |
| `gradle-deps` | `gradle-wrapper` | Resolves all module dependencies into the Gradle cache | `settings.gradle`, `build.gradle`, `gradle/libs.versions.toml`, or any per-module `build.gradle` changes |
| `app-builder` | `gradle-deps` | Compiles sources and produces three fat JARs (`shadowJar`) | Any source file changes |
| *(final)* | `eclipse-temurin:21-jre-jammy` | Assembles the runtime image | Any of the above, plus `mcp-server/doc`, `entry.sh`, or `application.yaml.docker` |

### Stage 1 – `gradle-wrapper`

```dockerfile
FROM eclipse-temurin:21-jdk-jammy AS gradle-wrapper
WORKDIR /app
COPY gradlew ./
COPY gradle/wrapper/ ./gradle/wrapper/
RUN ./gradlew --version --no-daemon
```

Running `--version` forces the wrapper to download the Gradle distribution into the image layer. This layer is cached until the wrapper files themselves change.

### Stage 2 – `gradle-deps`

```dockerfile
FROM gradle-wrapper AS gradle-deps
COPY settings.gradle build.gradle ./
COPY gradle/libs.versions.toml gradle/cache-warmup.init.gradle ./gradle/
COPY prjxp-common/build.gradle prjxp-common/
COPY chunk-norris/build.gradle chunk-norris/
COPY tibed/build.gradle tibed/
COPY golden-retriever/build.gradle golden-retriever/
COPY mcp-server/build.gradle mcp-server/
COPY docpipe/build.gradle docpipe/
COPY lucene-store/build.gradle lucene-store/
COPY mcp-proxy/build.gradle mcp-proxy/
RUN ./gradlew --no-daemon -I gradle/cache-warmup.init.gradle warmUpDependencyCache
```

Only build scripts are copied (no source code). The `cache-warmup.init.gradle` init script resolves every configuration of every module, pulling all JARs into the Gradle dependency cache. The `prjxp-launcher` module is a placeholder with no `build.gradle` and is intentionally omitted.

### Stage 3 – `app-builder`

```dockerfile
FROM gradle-deps AS app-builder
COPY . .
RUN ./gradlew :chunk-norris:shadowJar :tibed:shadowJar :mcp-server:shadowJar --no-daemon
```

All sources are copied. The build produces three fat JARs:

- `chunk-norris/build/libs/chunk-norris-all.jar`
- `tibed/build/libs/tibed-all.jar`
- `mcp-server/build/libs/mcp-server-all.jar`

### Stage 4 – Final runtime image

```dockerfile
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends \
    libgomp1 ca-certificates curl git \
    && rm -rf /var/lib/apt/lists/*
```

The final image is a JRE (not JDK) to keep the footprint small. The installed packages serve specific purposes:

- **`git`** – required for GitHub project import (clone/pull).
- **`libgomp1`** – OpenMP runtime for vectorised Lucene operations.
- **`curl`** – used by the entrypoint to poll the embedding-server health endpoint.

The image then copies the three fat JARs, the documentation tree, the entrypoint script, and the baked-in configuration:

```dockerfile
COPY --from=app-builder /app/chunk-norris/build/libs/chunk-norris-all.jar /app/
COPY --from=app-builder /app/tibed/build/libs/tibed-all.jar /app/
COPY --from=app-builder /app/mcp-server/build/libs/mcp-server-all.jar /app/
COPY mcp-server/doc /app/doc
COPY prjxp-common/docker/entry.sh /app/entry.sh
RUN chmod +x /app/entry.sh
COPY docker/application.yaml.docker /app/application.yaml
```

Directory and volume declarations:

```dockerfile
RUN mkdir -p /import /projects /github
VOLUME /app-source
VOLUME /app-source/.prjxp-data/lucene-index
RUN mkdir -p /app-source/language-packs
VOLUME /app-source/language-packs
```

The working directory is set to `/app-source` (where `.env` and project-specific configuration live), and the entrypoint is:

```dockerfile
ENV SPRING_CONFIG_LOCATION=file:/app/
ENV LUCENE_INDEX_PATH=.prjxp-data/lucene-index
ENV LUCENE_VECTOR_DIMENSION=1024
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 --add-modules jdk.incubator.vector"
ENV SERVER_PORT=7007
WORKDIR /app-source
ENTRYPOINT ["/app/entry.sh"]
CMD ["hub"]
```

> **Note:** The `LUCENE_INDEX_PATH` default in the Dockerfile is a *relative* path (`.prjxp-data/lucene-index`). The `docker-compose.yml` overrides it with the absolute path `/data/lucene-index` so the index lands on the persistent named volume. The entrypoint's `load_runtime_env_file` function is designed to prevent a host-oriented `.env` from clobbering this container-specific value (see [Environment Variables](#environment-variables)).

## Services

The `docker-compose.yml` defines three services on a shared `prjxp-network` (bridge driver):

| Service | Image | Host port | Internal port | Role |
|---------|-------|-----------|---------------|------|
| `apache-proxy` | `httpd:2.4-alpine` | `${APACHE_PORT:-80}` | 80 | Reverse proxy / TLS termination |
| `frontend` | `prjxp-frontend` (built from `./frontend`) | — (exposed only) | 8080 | Web UI (SPA) |
| `hub` | `prjxp-hub` (built from `.`) | `${HUB_PORT:-7007}` | 7007 | MCP endpoint, REST API, in-process pipeline |

### Dependency chain

```
apache-proxy  →  frontend  →  hub
```

The Apache proxy depends on `frontend`; `frontend` depends on `hub`. All three share the `prjxp-network` bridge.

### `apache-proxy`

```yaml
apache-proxy:
  image: httpd:2.4-alpine
  container_name: prjxp-apache-proxy
  ports:
    - "${APACHE_PORT:-80}:80"
  volumes:
    - ./docker/apache-httpd.conf:/usr/local/apache2/conf/httpd.conf
    - ./docker/apache-proxy.conf:/usr/local/apache2/conf/extra/prjxp-proxy.conf
  command: ["/usr/local/apache2/bin/apachectl", "-D", "FOREGROUND"]
  extra_hosts:
    - "host.docker.internal:host-gateway"
  depends_on:
    - frontend
```

The Apache container runs in the foreground and proxies to the `frontend` container. The two configuration files are bind-mounted so you can adjust routing or TLS without rebuilding the image.

### `frontend`

```yaml
frontend:
  image: prjxp-frontend
  container_name: prjxp-frontend
  build:
    context: ./frontend
    dockerfile: Dockerfile
  environment:
    PRJXP_HUB_URL: ${PRJXP_HUB_URL:-http://hub:7007}
  expose:
    - "8080"
  depends_on:
    - hub
```

The `PRJXP_HUB_URL` variable tells the SPA where to send API requests. The default `http://hub:7007` resolves via the Docker network. Override it in `.env` if your backend runs under a different service name or port.

### `hub`

```yaml
hub:
  image: prjxp-hub
  container_name: prjxp-hub
  build:
    context: .
    dockerfile: Dockerfile
  command: ["hub"]
  env_file:
    - ${PRJXP_ENV_FILE:-./.env}
  ports:
    - "${HUB_PORT:-7007}:7007"
  expose:
    - "7007"
  extra_hosts:
    - "host.docker.internal:host-gateway"
  depends_on:
    - frontend   # (implied by apache-proxy chain)
```

The `hub` service is the only container that exposes a port to the host by default. The MCP endpoint is reachable at `http://localhost:7007/mcp`. The `extra_hosts` entry maps `host.docker.internal` to the Docker host gateway, allowing the container to reach services running on the host (e.g., an LM Studio or TEI instance).

### Entrypoint modes

The `entry.sh` script accepts a single mode argument:

| Mode | JAR executed | Behaviour |
|------|-------------|-----------|
| `chunk` | `chunk-norris-all.jar` | Runs the chunking pipeline and exits. |
| `embed` | `tibed-all.jar` | Starts the text-embeddings-router (unless `SKIP_EMBEDDING_SERVER=true`), waits for its `/health` endpoint, then runs the embedding pipeline. |
| `hub` | `mcp-server-all.jar` | Exports `PRJXP_HUB_ENABLED=true`, starts the embedding server (if not skipped), and runs the long-lived hub server (MCP + REST + import watcher). |

The `hub` mode is the default (`CMD ["hub"]`). When `PRJXP_HUB_ENABLED` is *not* set, the same `mcp-server-all.jar` runs as a one-shot CLI pipeline (chunk → embed → docpipe) and exits. Enabling the hub automatically disables the CLI runners of `chunk-norris`/`tibed`/`docpipe` because they are gated on `prjxp.hub.enabled=false`.

## Environment Variables

Configuration is layered. The entrypoint resolves the Spring config location at startup:

1. If `/app-source/application.yml` or `/app-source/application.yaml` exists, Spring loads from `optional:file:/app-source/,optional:file:/app/`.
2. Otherwise it falls back to `file:/app/` (the baked-in `application.yaml`).

The runtime `.env` file (mounted at `/data/prjxp.env`) is sourced by `load_runtime_env_file`, but it can only **add** variables. The function snapshots the existing environment, sources the file, then restores the snapshot—so values already set by `docker-compose.yml` `environment` or the Dockerfile `ENV` directives are never overridden. This prevents a host-oriented relative path (e.g., `LUCENE_INDEX_PATH=.prjxp-data/lucene-index`) from clobbering the container-specific absolute path (`/data/lucene-index`).

### Dockerfile defaults (baked into the image)

| Variable | Default | Purpose |
|----------|---------|---------|
| `SPRING_CONFIG_LOCATION` | `file:/app/` | Fallback config location |
| `LUCENE_INDEX_PATH` | `.prjxp-data/lucene-index` | Relative; overridden by compose |
| `LUCENE_VECTOR_DIMENSION` | `1024` | Embedding vector size |
| `JAVA_OPTS` | `-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 --add-modules jdk.incubator.vector` | JVM tuning for containers |
| `SERVER_PORT` | `7007` | HTTP listen port |

### Compose-level overrides (hub service)

| Variable | Default | Purpose |
|----------|---------|---------|
| `PRJXP_ENV_FILE` | `/data/prjxp.env` | Path to the runtime env file inside the container |
| `LUCENE_INDEX_PATH` | `/data/lucene-index` | Absolute path on the persistent data volume |
| `LUCENE_VECTOR_DIMENSION` | `1024` | (from `.env`) |
| `PRJXP_HUB_IMPORT_DIR` | `/import` | Where uploaded archives are unpacked |
| `PRJXP_HUB_PROJECTS_ROOT` | `/projects` | Root for imported project checkouts |
| `PRJXP_HUB_SCAN_EXCLUDE_DIR_NAMES` | `.git,.svn,.hg,node_modules,branches,tags,.idea,.vs` | Directories skipped during source scanning |
| `PRJXP_HUB_SCAN_MAX_DEPTH` | `8` | Maximum directory depth for the scanner |
| `PRJXP_HUB_GITHUB_TOKEN_KEY` | *(empty)* | Passphrase for encrypting GitHub PATs (private repos) |
| `SERVER_PORT` | `7007` | HTTP listen port |
| `EMBEDDING_API_BASE_URL` | `http://host.docker.internal:1234/v1` | External embedding provider (e.g., LM Studio) |
| `EMBEDDING_MODEL_NAME` | `mxbai-embed-large-v1` | Embedding model identifier |
| `SKIP_EMBEDDING_SERVER` | `true` | Set to `false` to start an in-container TEI |
| `EMBEDDING_API_KEY` | `dummy` | API key for the embedding endpoint |
| `MCP_CORS_ALLOWED_PATTERNS` | `http://10.*.*.*,http://172.16.*.*,http://192.168.*.*,http://localhost:*` | Comma-separated CORS origin patterns (no spaces) |
| `PRJXP_DOCS_DIR` | `/app/doc` | Directory served by the in-app docs browser |

> **Naming note:** The embedding variables in `application.yaml.docker` use the names `EMBEDDING_API_BASE_URL`, `EMBEDDING_MODEL_NAME`, etc. (no `PRJXP_` prefix). These names must match the placeholders in the baked-in `application.yaml`—do not rename them.

### Frontend service

| Variable | Default | Purpose |
|----------|---------|---------|
| `PRJXP_HUB_URL` | `http://hub:7007` | Upstream API URL for the SPA |

### Apache proxy service

| Variable | Default | Purpose |
|----------|---------|---------|
| `APACHE_PORT` | `80` | Host port for the reverse proxy |

## Volumes

### Named volumes (Docker-managed)

| Volume | Mounted at | Contents |
|--------|-----------|----------|
| `prjxp-projects` | `/projects` | Imported project checkouts (persistent across container rebuilds) |
| `prjxp-github` | `/github` | Cloned GitHub repositories |
| `prjxp-hub-data` | `/data` | Lucene index (`/data/lucene-index`), runtime `.env` (`/data/prjxp.env`) |

### Bind mounts

| Host path | Container path | Access | Purpose |
|-----------|---------------|--------|---------|
| `${PROJECTS_HOME:-./import}` | `/import` | rw | Upload / import staging directory |
| `${PRJXP_ENV_FILE:-./.env}` | `/data/prjxp.env` | **ro** | Runtime environment file |
| `./language-packs` | `/app-source/language-packs` | rw | External Language Pack plugin JARs (loaded at startup, never baked into the image) |
| `./docker/apache-httpd.conf` | `/usr/local/apache2/conf/httpd.conf` | rw | Apache main config |
| `./docker/apache-proxy.conf` | `/usr/local/apache2/conf/extra/prjxp-proxy.conf` | rw | Apache proxy/virtual-host config |

### Optional: documentation bind mount

The docs tree is baked into the image at `/app/doc` (from `mcp-server/doc`). To update documentation without rebuilding the image, add a bind mount in `docker-compose.yml`:

```yaml
volumes:
  # ... existing volumes ...
  - ./my-docs:/app/doc
```

Point `./my-docs` at your DocPipe output or any directory containing the rendered docs. The `PRJXP_DOCS_DIR` environment variable (default `/app/doc`) tells the hub where to look.

## Updating the Stack

### Rebuilding the image

```bash
docker compose build hub
docker compose up -d
```

Because of the multi-stage cache, a source-only change re-runs only the `app-builder` and final stages. A change to `build.gradle` or `libs.versions.toml` additionally re-runs `gradle-deps`. A change to the Gradle wrapper re-runs all four stages.

### Updating the frontend

```bash
docker compose build frontend
docker compose up -d
```

### Updating documentation without a rebuild

Bind-mount your docs directory onto `/app/doc` (see [Volumes](#volumes)). Then simply replace the files in the host directory and restart the hub:

```bash
docker compose restart hub
```

No image rebuild is required.

### Updating the runtime `.env`

Edit `./.env` (or the file pointed to by `PRJXP_ENV_FILE`) on the host, then:

```bash
docker compose restart hub
```

The entrypoint re-sources the file on every container start. Remember that the file can only *add* variables; it cannot override values set in `docker-compose.yml` `environment` or the Dockerfile `ENV` directives.

### Updating Apache configuration

Edit `./docker/apache-httpd.conf` and/or `./docker/apache-proxy.conf`, then:

```bash
docker compose restart apache-proxy
```

No rebuild is needed because both files are bind-mounted.

## Reverse Proxy

The `apache-proxy` service (`httpd:2.4-alpine`) sits in front of the `frontend` container and is the single entry point for external traffic.

### Topology

```
Internet / LAN
       │
       ▼
  apache-proxy  :80  (or ${APACHE_PORT})
       │
       ▼
  frontend      :8080  (internal)
       │
       ▼
  hub           :7007  (internal)
```

The Apache container runs in the foreground:

```yaml
command: ["/usr/local/apache2/bin/apachectl", "-D", "FOREGROUND"]
```

Two configuration files are bind-mounted:

- `./docker/apache-httpd.conf` → `/usr/local/apache2/conf/httpd.conf`
- `./docker/apache-proxy.conf` → `/usr/local/apache2/conf/extra/prjxp-proxy.conf`

### Forwarded headers

The hub's `application.yaml` sets:

```yaml
server:
  forward-headers-strategy: framework
```

This tells Spring Boot to honour `X-Forwarded-*` headers injected by the Apache proxy, so the application sees the correct client IP, scheme, and host.

### CORS

The MCP endpoint enforces CORS. The allowed origins are controlled by:

```yaml
mcp:
  cors:
    allowed-patterns: ${MCP_CORS_ALLOWED_PATTERNS:http://10.*.*.*,http://172.16.*.*,http://192.16.*.*,http://localhost:*}
```

The default patterns cover private RFC-1918 ranges and `localhost`. Browsers send an `Origin` header on `POST`/`PUT`/`DELETE` requests; origins that do not match any pattern are rejected with **403**.

When you deploy behind a reverse proxy with a public hostname, add that hostname to the patterns in your `.env`:

```bash
MCP_CORS_ALLOWED_PATTERNS="http://localhost:*,https://my-host.example.com"
```

> **Important:** Separate patterns with commas and **do not include spaces** after the commas.

### TLS termination

TLS is handled by the Apache container. Configure your certificate and `SSLCertificateFile` / `SSLCertificateKeyFile` directives in `./docker/apache-httpd.conf` (or a separate `ssl.conf` included from it), then map port 443 in the compose file:

```yaml
ports:
  - "${APACHE_PORT:-80}:80"
  - "443:443"
```

After editing, restart the proxy:

```bash
docker compose restart apache-proxy
```

### Accessing the MCP endpoint directly

If you need to reach the MCP endpoint from a client on the host (bypassing the proxy), the hub port is published directly:

```
http://localhost:7007/mcp
```

(Or `http://localhost:${HUB_PORT}/mcp` if you changed `HUB_PORT` in `.env`.)

_This document was generated with Doc|Pipe._

