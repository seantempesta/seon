#!/usr/bin/env bash
# U10-prep pod-restart drill (sci-execution-runtime design §7).
# Uses the same PRIVATE writer directory as drill.sh; no live cluster is used.
set -euo pipefail
cd "$(dirname "$0")/../../.."

mkdir -p tmp/host-drill tmp/sci-probe/jvm/out
source tmp/sci-probe/jvm/drill-lifecycle.sh
output_path="tmp/sci-probe/jvm/out/pod-restart-drill.out"
: > "$output_path"

DRILL_WRITER_PID=""
cleanup() {
  drill_stop_owned_writer
}
trap cleanup EXIT

drill_clean_private_runtime
if ! drill_start_writer tmp/host-drill/pod-restart-writer.log 2>> "$output_path"; then
  exit 1
fi

clojure -M:writer:host -i tmp/sci-probe/jvm/pod_restart_drill_client.clj \
  | tee -a "$output_path"
