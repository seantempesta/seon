#!/usr/bin/env bash
# U10-prep pod-restart drill (sci-execution-runtime design §7).
# Uses the same PRIVATE writer directory as drill.sh; no live cluster is used.
set -euo pipefail
cd "$(dirname "$0")/../../.."

mkdir -p tmp/host-drill tmp/sci-probe/jvm/out
output_path="tmp/sci-probe/jvm/out/pod-restart-drill.out"
: > "$output_path"

writer_pid=""
cleanup() {
  if [[ -n "$writer_pid" ]] && kill -0 "$writer_pid" 2>/dev/null; then
    kill "$writer_pid" 2>/dev/null || true
    wait "$writer_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT

if [[ -S tmp/host-drill/writer.sock ]] \
   && ! pgrep -f 'seon.db.server.*host-drill' >/dev/null 2>&1; then
  rm -f tmp/host-drill/writer.sock
fi

if [[ ! -S tmp/host-drill/writer.sock ]]; then
  clojure -M:writer -m seon.db.server \
    --db-name u1-drill --backend file \
    --path tmp/host-drill/store \
    --req-sock tmp/host-drill/writer.sock \
    > tmp/host-drill/pod-restart-writer.log 2>&1 &
  writer_pid=$!
  for _ in $(seq 1 120); do
    [[ -S tmp/host-drill/writer.sock ]] && break
    if ! kill -0 "$writer_pid" 2>/dev/null; then
      echo "POD-RESTART-DRILL writer failed before its socket became ready" | tee -a "$output_path"
      tail -20 tmp/host-drill/pod-restart-writer.log | tee -a "$output_path"
      exit 1
    fi
    sleep 0.5
  done
  if [[ ! -S tmp/host-drill/writer.sock ]]; then
    echo "POD-RESTART-DRILL writer readiness timed out" | tee -a "$output_path"
    exit 1
  fi
fi

clojure -M:writer:host -i tmp/sci-probe/jvm/pod_restart_drill_client.clj \
  | tee -a "$output_path"
