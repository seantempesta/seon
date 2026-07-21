#!/usr/bin/env bash
# Run both U10 drills and print one honest summary table.
set -uo pipefail
cd "$(dirname "$0")/../../.."
mkdir -p tmp/sci-probe/jvm/out
source tmp/sci-probe/jvm/drill-lifecycle.sh
lock_dir="tmp/sci-probe/jvm/.run-all-drills.lock"

acquire_lock() {
  local owner_pid=""
  if mkdir "$lock_dir" 2>/dev/null; then
    printf '%s\n' "$$" > "$lock_dir/pid"
    return 0
  fi

  [[ -f "$lock_dir/pid" ]] && read -r owner_pid < "$lock_dir/pid"
  if [[ "$owner_pid" =~ ^[0-9]+$ ]] && kill -0 "$owner_pid" 2>/dev/null; then
    echo "another combined drill runner (pid $owner_pid) owns $lock_dir" >&2
    return 2
  fi

  rm -f "$lock_dir/pid"
  rmdir "$lock_dir" 2>/dev/null || {
    echo "cannot recover stale combined drill lock $lock_dir" >&2
    return 2
  }
  mkdir "$lock_dir"
  printf '%s\n' "$$" > "$lock_dir/pid"
}

acquire_lock || exit $?

cleanup() {
  drill_clean_private_runtime
  rm -f "$lock_dir/pid"
  rmdir "$lock_dir" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

# One owner cleans dirty starts and the boundary between private drill runs.
drill_clean_private_runtime

run_drill() {
  local name="$1"
  local script="$2"
  local output="$3"
  drill_clean_private_runtime
  if "$script" >"$output" 2>&1; then
    drill_clean_private_runtime
    printf '%s\tPASS\t%s\n' "$name" "$output"
    return 0
  else
    local status=$?
    drill_clean_private_runtime
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
} | tee tmp/sci-probe/jvm/out/run-all-drills.out

if (( host_status != 0 || pod_status != 0 )); then
  exit 1
fi
