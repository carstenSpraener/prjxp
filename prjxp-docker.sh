#!/usr/bin/env bash
# =============================================================================
# prjxp-docker.sh — Chunk, Embed, and Serve prjxp in Docker
# =============================================================================
# Usage:
#   ./prjxp-docker.sh build      # Build the Docker image
#   ./prjxp-docker.sh chunk       # Chunk source code → px-chunks.jsonl
#   ./prjxp-docker.sh embed       # Embed chunks → Lucene index
#   ./prjxp-docker.sh serve       # Start MCP server (persistent)
#   ./prjxp-docker.sh stop        # Stop the MCP server
#   ./prjxp-docker.sh pipeline     # Build + chunk + embed + serve (all-in-one)
# =============================================================================

set -euo pipefail

IMAGE_NAME="prjxp"
COMPOSE_FILE="docker-compose.yml"

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }

cmd_build() {
    log_info "Building Docker image '${IMAGE_NAME}'..."
    docker build -t "${IMAGE_NAME}" .
    log_info "Build complete."
}

cmd_chunk() {
    log_info "Running chunk-norris (generating px-chunks.jsonl)..."
    docker run --rm \
        -v "$(pwd):/app-source" \
        -e PRJXP_ROOT_DIR=/app-source \
        "${IMAGE_NAME}" chunk
    log_info "Chunking complete. Check px-chunks.jsonl."
}

cmd_embed() {
    if [ ! -f "px-chunks.jsonl" ]; then
        log_warn "px-chunks.jsonl not found. Run 'chunk' first."
        exit 1
    fi

    log_info "Running tibed (embedding chunks into Lucene index)..."
    docker run --rm \
        -v "$(pwd):/app-source" \
        -v prjxp-data:.prjxp-data/lucene-index \
        -e PRJXP_ROOT_DIR=/app-source \
        "${IMAGE_NAME}" embed
    log_info "Embedding complete. Index stored in Docker volume 'prjxp-data'."
}

cmd_serve() {
    log_info "Starting MCP server on port 8080..."
    docker compose -f "${COMPOSE_FILE}" up -d
    log_info "MCP server is running. Visit http://localhost:8080"
}

cmd_stop() {
    log_info "Stopping MCP server..."
    docker compose -f "${COMPOSE_FILE}" down
    log_info "MCP server stopped."
}

cmd_pipeline() {
    log_info "=== Full Pipeline: Build → Chunk → Embed → Serve ==="
    cmd_build
    echo ""
    cmd_chunk
    echo ""
    cmd_embed
    echo ""
    cmd_serve
    log_info "=== Pipeline complete ==="
}

# =============================================================================
# Main
# =============================================================================
case "${1:-}" in
    build)   cmd_build ;;
    chunk)   cmd_chunk ;;
    embed)   cmd_embed ;;
    serve)   cmd_serve ;;
    stop)    cmd_stop ;;
    pipeline) cmd_pipeline ;;
    *)
        echo "Usage: $0 {build|chunk|embed|serve|stop|pipeline}"
        echo ""
        echo "Commands:"
        echo "  build    Build the Docker image"
        echo "  chunk    Chunk source code → px-chunks.jsonl"
        echo "  embed    Embed chunks into Lucene index"
        echo "  serve    Start MCP server (persistent, port 8080)"
        echo "  stop     Stop the MCP server"
        echo "  pipeline Build + chunk + embed + serve (all-in-one)"
        exit 1
        ;;
esac
