#!/usr/bin/env bash
# Shared lifecycle for the private U10 drill writer and host.

drill_writer_pattern='seon\.db\.server.*tmp/host-drill'
drill_host_pattern='seon\.host.*tmp/host-drill'

drill_matching_pids() {
  pgrep -f "$1" 2>/dev/null || true
}

drill_stop_pids() {
  local pattern="$1"
  local pid
  local pids
  pids="$(drill_matching_pids "$pattern")"
  [[ -z "$pids" ]] && return 0

  while read -r pid; do
    [[ -n "$pid" ]] && kill "$pid" 2>/dev/null || true
  done <<< "$pids"

  for _ in $(seq 1 20); do
    [[ -z "$(drill_matching_pids "$pattern")" ]] && return 0
    sleep 0.1
  done

  pids="$(drill_matching_pids "$pattern")"
  while read -r pid; do
    [[ -n "$pid" ]] && kill -9 "$pid" 2>/dev/null || true
  done <<< "$pids"
}

drill_clean_private_runtime() {
  drill_stop_pids "$drill_host_pattern"
  drill_stop_pids "$drill_writer_pattern"
  rm -f tmp/host-drill/host.sock tmp/host-drill/writer.sock
}

drill_start_writer() {
  local log_path="$1"
  mkdir -p tmp/host-drill
  rm -f tmp/host-drill/writer.sock
  clojure -M:writer -m seon.db.server \
    --db-name u1-drill --backend file \
    --path tmp/host-drill/store \
    --req-sock tmp/host-drill/writer.sock \
    > "$log_path" 2>&1 &
  DRILL_WRITER_PID=$!

  for _ in $(seq 1 120); do
    [[ -S tmp/host-drill/writer.sock ]] && return 0
    if ! kill -0 "$DRILL_WRITER_PID" 2>/dev/null; then
      echo "drill writer failed before its socket became ready" >&2
      tail -20 "$log_path" >&2
      return 1
    fi
    sleep 0.5
  done
  echo "drill writer readiness timed out" >&2
  return 1
}

drill_stop_owned_writer() {
  if [[ -n "${DRILL_WRITER_PID:-}" ]] && kill -0 "$DRILL_WRITER_PID" 2>/dev/null; then
    kill "$DRILL_WRITER_PID" 2>/dev/null || true
    wait "$DRILL_WRITER_PID" 2>/dev/null || true
  fi
  DRILL_WRITER_PID=""
  rm -f tmp/host-drill/writer.sock
}
