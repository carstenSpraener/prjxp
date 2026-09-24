#!/usr/bin/env bash
set -Eeuo pipefail

MODE="${1:-serve}"
EMBEDDING_SERVER_PID=""

trap 'stop_embedding_server' EXIT

resolve_config_location() {
  if [[ -f /app-source/application.yml || -f /app-source/application.yaml ]]; then
    echo "optional:file:/app-source/,optional:file:/app/"
  else
    echo "file:/app/"
  fi
}

start_embedding_server() {
  text-embeddings-router --model-id "${MODEL_ID}" --port "${TEI_PORT}" &
  EMBEDDING_SERVER_PID=$!
}

wait_for_embedding_server() {
  while true; do
    if curl -fsS "http://localhost:${TEI_PORT}/health" >/dev/null 2>&1; then
      break
    fi

    if ! kill -0 "${EMBEDDING_SERVER_PID}" 2>/dev/null; then
      echo "Embedding server process exited unexpectedly." >&2
      return 1
    fi

    sleep 1
  done
}

stop_embedding_server() {
  if [[ -n "${EMBEDDING_SERVER_PID}" ]] && kill -0 "${EMBEDDING_SERVER_PID}" 2>/dev/null; then
    kill "${EMBEDDING_SERVER_PID}" 2>/dev/null || true
    wait "${EMBEDDING_SERVER_PID}" 2>/dev/null || true
  fi
}

run_java() {
  local jar_path="$1"
  shift
  local cfg
  cfg="$(resolve_config_location)"

  java ${JAVA_OPTS:-} -Dspring.config.location="${cfg}" -jar "${jar_path}" "$@"
}

run_with_embedding_server() {
  start_embedding_server
  wait_for_embedding_server

  "$@" &
  local app_pid=$!

  trap 'kill "${app_pid}" 2>/dev/null || true; stop_embedding_server; exit 143' INT TERM

  set +e
  wait "${app_pid}"
  local status=$?
  set -e
  stop_embedding_server
  return "${status}"
}

case "${MODE}" in
  chunk)
    shift
    run_java /app/chunk-norris-all.jar "$@"
    ;;
  embed)
    shift
    run_with_embedding_server run_java /app/tibed-all.jar "$@"
    ;;
  serve)
    shift
    run_with_embedding_server run_java /app/mcp-server-all.jar "$@"
    ;;
  hub)
    shift
    # Hub mode: in-process pipeline, import watcher; CLI runners stay off.
    export PRJXP_HUB_ENABLED=true
    export PRJXP_CLI_ENABLED=false
    run_with_embedding_server run_java /app/mcp-server-all.jar "$@"
    ;;
  *)
    echo "Unknown mode: ${MODE}" >&2
    echo "Supported modes: chunk, embed, serve, hub" >&2
    exit 1
    ;;
esac

