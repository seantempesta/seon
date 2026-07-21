#!/usr/bin/env bash
# Run both U10 drills and print one honest summary table.
set -uo pipefail
cd "$(dirname "$0")/../../.."
mkdir -p tmp/sci-probe/jvm/out

run_drill() {
  local name="$1"
  local script="$2"
  local output="$3"
  if "$script" >"$output" 2>&1; then
    printf '%s\tPASS\t%s\n' "$name" "$output"
    return 0
  else
    local status=$?
    printf '%s\tFAIL (%s)\t%s\n' "$name" "$status" "$output"
    return "$status"
  fi
}

host_status=0
pod_status=0
host_row=$(run_drill host-kill tmp/sci-probe/jvm/drill.sh \
  tmp/sci-probe/jvm/out/host-kill-drill.out) || host_status=$?
pod_row=$(run_drill pod-restart tmp/sci-probe/jvm/pod-restart-drill.sh \
  tmp/sci-probe/jvm/out/pod-restart-drill-run-all.out) || pod_status=$?

{
  printf 'DRILL\tRESULT\tOUTPUT\n'
  printf '%s\n' "$host_row"
  printf '%s\n' "$pod_row"
} | tee tmp/sci-probe/jvm/out/run-all-drills-summary.out

if (( host_status != 0 || pod_status != 0 )); then
  exit 1
fi
