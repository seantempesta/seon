#!/usr/bin/env bash
# Measurement only: JVM heap, RSS, and platform-thread cost at agent scale.
# Active question/results:
# docs/prds/sci-execution-runtime/research/measurements-2026-07-25.md
# Correctness owner: test/seon/cluster/loop_test.clj.
set -euo pipefail

if [[ $# -ne 4 ]]; then
  echo "usage: $0 PID CLUSTER AGENT_COUNT OUTPUT_FILE" >&2
  exit 2
fi

pid=$1
cluster=$2
agent_count=$3
output_file=$4

command -v jcmd >/dev/null
mkdir -p "$(dirname "$output_file")"

{
  printf 'captured_at_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf 'cluster=%s\n' "$cluster"
  printf 'agent_count=%s\n' "$agent_count"
  printf 'pid=%s\n' "$pid"
  sw_vers
  sysctl -n hw.model hw.ncpu hw.memsize
  jcmd "$pid" VM.version
  jcmd "$pid" VM.flags
  jcmd "$pid" GC.run
  jcmd "$pid" GC.heap_info
  ps -p "$pid" -o pid=,rss=,vsz=,etime=,state=
  printf 'os_thread_rows_including_header='
  ps -M "$pid" | wc -l | tr -d ' '
} > "$output_file"

cat "$output_file"
