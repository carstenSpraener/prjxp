#!/usr/bin/env bash
# =============================================================================
# setup.sh — Setup a project for prjxp Docker pipeline
# =============================================================================
# Usage:
#   ./setup.sh <project-path> [--port 8090]
#     Setup + chunk + embed + serve (auto-builds image if needed)
#   ./setup.sh stop <project-path>
#     Stop the MCP server for a project
# =============================================================================

set -euo pipefail

IMAGE_NAME="prjxp"
DEFAULT_PORT=7007
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

log_info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# =============================================================================
# Parse arguments
# =============================================================================
PROJECT_PATH=""
PORT=$DEFAULT_PORT
COMMAND="setup"

while [[ $# -gt 0 ]]; do
    case "$1" in
        stop)
            COMMAND="stop"
            shift
            if [[ $# -gt 0 && ! "$1" =~ ^-- ]]; then
                PROJECT_PATH="$1"
                shift
            fi
            ;;
        --port)
            PORT="$2"
            shift 2
            ;;
        -*)
            log_error "Unknown option: $1"
            exit 1
            ;;
        *)
            if [[ -z "$PROJECT_PATH" ]]; then
                PROJECT_PATH="$1"
            fi
            shift
            ;;
    esac
done

# =============================================================================
# Validation
# =============================================================================
if [[ -z "$PROJECT_PATH" ]]; then
    echo "Usage: $0 <project-path> [--port 8090]"
    echo "       $0 stop <project-path>"
    exit 1
fi

PROJECT_PATH="$(cd "$PROJECT_PATH" && pwd)"
PROJECT_NAME=$(basename "$PROJECT_PATH")

if [[ ! -d "$PROJECT_PATH" ]]; then
    log_error "Project directory does not exist: $PROJECT_PATH"
    exit 1
fi

# =============================================================================
# Stop command
# =============================================================================
if [[ "$COMMAND" == "stop" ]]; then
    log_info "Stopping MCP server for project '$PROJECT_NAME'..."
    if docker ps --format '{{.Names}}' | grep -q "mcp-${PROJECT_NAME}"; then
        docker stop "mcp-${PROJECT_NAME}" && docker rm "mcp-${PROJECT_NAME}"
        log_info "MCP server stopped."
    else
        log_warn "No running MCP server found for project '$PROJECT_NAME'."
    fi
    exit 0
fi

# =============================================================================
# Step 1: Ensure Docker image exists (auto-build if needed)
# =============================================================================
log_info "Checking for Docker image '${IMAGE_NAME}'..."
if ! docker image inspect "$IMAGE_NAME" >/dev/null 2>&1; then
    log_warn "Image not found. Building from ${SCRIPT_DIR}..."
    (cd "$SCRIPT_DIR" && docker build -t "$IMAGE_NAME" .)
    log_info "Image built successfully."
else
    log_info "Image '${IMAGE_NAME}' already exists."
fi

# =============================================================================
# Step 2: Copy config files if not present
# =============================================================================
if [[ ! -f "$PROJECT_PATH/application.yaml" ]]; then
    log_info "Copying application.yaml to project directory..."
    cp "$SCRIPT_DIR/application.yaml" "$PROJECT_PATH/application.yaml"
fi

if [[ ! -f "$PROJECT_PATH/.env" ]]; then
    log_info "Copying .env.example to project directory as .env..."
    cp "$SCRIPT_DIR/.env.example" "$PROJECT_PATH/.env"
fi

# =============================================================================
# Step 3: Chunking
# =============================================================================
log_info "=== Step 1/3: Chunking source code ==="
docker run --rm \
    -v "$PROJECT_PATH:/app-source" \
    -e PRJXP_ROOT_DIR=/app-source \
    "$IMAGE_NAME" chunk

if [[ ! -f "$PROJECT_PATH/px-chunks.jsonl" ]]; then
    log_error "Chunking failed — px-chunks.jsonl not created."
    exit 1
fi

CHUNK_COUNT=$(wc -l < "$PROJECT_PATH/px-chunks.jsonl")
log_info "Created $CHUNK_COUNT chunks in px-chunks.jsonl"

# =============================================================================
# Step 4: Embedding
# =============================================================================
log_info "=== Step 2/3: Embedding chunks into Lucene index ==="
docker run --rm \
    -v "$PROJECT_PATH:/app-source" \
    -v prjxp-data:.prjxp-data/lucene-index \
    -e PRJXP_ROOT_DIR=/app-source \
    "$IMAGE_NAME" embed

log_info "Embedding complete."

# =============================================================================
# Step 5: Start MCP server
# =============================================================================
log_info "=== Step 3/3: Starting MCP server on port $PORT ==="

# Stop existing container if running
if docker ps --format '{{.Names}}' | grep -q "mcp-${PROJECT_NAME}"; then
    log_warn "Existing MCP server found for '$PROJECT_NAME'. Stopping..."
    docker stop "mcp-${PROJECT_NAME}" && docker rm "mcp-${PROJECT_NAME}"
fi

docker run -d \
    --name "mcp-${PROJECT_NAME}" \
    -v "$PROJECT_PATH:/app-source" \
    -v prjxp-data:.prjxp-data/lucene-index \
    -p "${PORT}:7007" \
    -e PRJXP_ROOT_DIR=/app-source \
    -e SERVER_PORT=7007 \
    "$IMAGE_NAME" serve

# Wait for server to be ready
log_info "Waiting for MCP server to start..."
for i in $(seq 1 30); do
    if curl -s "http://localhost:${PORT}/prjxp/tools/ping" >/dev/null 2>&1; then
        break
    fi
    sleep 1
done

PING_RESULT=$(curl -s "http://localhost:${PORT}/prjxp/tools/ping" 2>/dev/null || echo "failed")

if [[ "$PING_RESULT" == *"pong"* ]]; then
    log_info "=== MCP server is running! ==="
    log_info "  Project: $PROJECT_NAME"
    log_info "  URL:     http://localhost:${PORT}"
    log_info "  Ping:    $PING_RESULT"
else
    log_warn "Server started but ping failed. Check logs: docker logs mcp-${PROJECT_NAME}"
fi

log_info "To stop the server later: $0 stop $PROJECT_PATH"
