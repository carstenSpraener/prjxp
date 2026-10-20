#!/usr/bin/env bash
set -Eeuo pipefail

MODE="${1:-serve}"
EMBEDDING_SERVER_PID=""

trap 'stop_embedding_server' EXIT

load_runtime_env_file() {
  local env_file_path="${PRJXP_ENV_FILE:-/data/prjxp.env}"
  if [[ ! -f "${env_file_path}" ]]; then
    return
  fi
  # The runtime env file may only ADD variables — it must never override values
  # that are already present (compose `environment` and the shell take
  # precedence). We snapshot the whole environment NUL-delimited (so values with
  # spaces, quotes or newlines survive), source the file, then restore the
  # snapshot. This stops host-oriented values (e.g. a relative
  # LUCENE_INDEX_PATH=.prjxp-data/lucene-index for local `java -jar` use) from
  # clobbering container-specific settings (e.g. LUCENE_INDEX_PATH=/data/lucene-
  # index on the persistent data volume) — which would otherwise send the Lucene
  # index to an ephemeral anonymous volume and force a full re-embed on every
  # container restart.
  local snapshot entry
  snapshot=$(mktemp) || return
  env -0 > "${snapshot}"
  set -a
  # shellcheck disable=SC1090
  . "${env_file_path}"
  set +a
  while IFS= read -r -d '' entry; do
    export "${entry}"
  done < "${snapshot}"
  rm -f "${snapshot}"
}

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
  if [[ "${SKIP_EMBEDDING_SERVER:-false}" != "true" ]]; then
    start_embedding_server
    wait_for_embedding_server
  fi

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

load_runtime_env_file

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

