#!/usr/bin/env bash
set -Eeuo pipefail

MODE="${1:-hub}"

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

run_java() {
  local jar_path="$1"
  shift
  local cfg
  cfg="$(resolve_config_location)"

  java ${JAVA_OPTS:-} -Dspring.config.location="${cfg}" -jar "${jar_path}" "$@"
}

run_app() {
  "$@" &
  local app_pid=$!

  trap 'kill "${app_pid}" 2>/dev/null || true; exit 143' INT TERM

  set +e
  wait "${app_pid}"
  local status=$?
  set -e
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
    run_app run_java /app/tibed-all.jar "$@"
    ;;
  hub)
    shift
    # Hub mode is the ONLY server mode: in-process pipeline, import watcher.
    # When PRJXP_HUB_ENABLED is not set, the same mcp-server-all.jar runs as a
    # CLI pipeline (chunk + embed + docpipe) and exits. The CLI runners of
    # chunk-norris/tibed/docpipe are gated on prjxp.hub.enabled=false, so
    # enabling the hub switches them off automatically.
    export PRJXP_HUB_ENABLED=true
    run_app run_java /app/mcp-server-all.jar "$@"
    ;;
  *)
    echo "Unknown mode: ${MODE}" >&2
    echo "Supported modes: chunk, embed, hub" >&2
    exit 1
    ;;
esac

