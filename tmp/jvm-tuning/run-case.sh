#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 5 ]]; then
  echo "usage: run-case.sh CASE MODE HEAP IDLE-SECONDS JVM-FLAG..." >&2
  exit 2
fi

case_name="$1"
mode="$2"
heap="$3"
idle_seconds="$4"
shift 4

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
measurement_tree="${JVM_TUNING_TREE:-$repo_root}"
case_dir="$repo_root/tmp/jvm-tuning/results/$case_name"
scratch_root="$repo_root/tmp/jvm-tuning/roots/$case_name"
mkdir -p "$case_dir" "$scratch_root"

if [[ -e "$case_dir/complete" ]]; then
  echo "refusing to overwrite completed case: $case_name" >&2
  exit 2
fi

classpath="$(clojure -Spath -M:dev)"
jvm_args=(
  "-Xms16m"
  "-Xmx$heap"
  "--add-modules=jdk.incubator.vector"
  "--enable-native-access=ALL-UNNAMED"
  "-Xlog:gc*,safepoint,gc+heap=debug,gc+tlab=debug,stringdedup=debug:file=$case_dir/gc.log:time,uptime,level,tags"
)

if [[ "${JVM_TUNING_OMIT_UNSAFE_ALLOW:-0}" != "1" ]]; then
  jvm_args+=("--sun-misc-unsafe-memory-access=allow")
fi

for flag in "$@"; do
  jvm_args+=("$flag")
done

if [[ "$mode" == "init" ]]; then
  workload_args=("init" "$scratch_root" "$idle_seconds")
elif [[ "$mode" == "turn" ]]; then
  workload_args=("turn" "$scratch_root" "12" "$idle_seconds")
else
  echo "unknown mode: $mode" >&2
  exit 2
fi

printf '%q ' java "${jvm_args[@]}" -cp "$classpath" clojure.main \
  "$repo_root/tmp/jvm-tuning/workload.clj" "${workload_args[@]}" \
  > "$case_dir/command.txt"
printf '\n' >> "$case_dir/command.txt"

started_epoch="$(date +%s)"
cd "$measurement_tree"
java "${jvm_args[@]}" -cp "$classpath" clojure.main \
  "$repo_root/tmp/jvm-tuning/workload.clj" "${workload_args[@]}" \
  > "$case_dir/stdout.edn" 2> "$case_dir/stderr.log" &
pid=$!

printf 'elapsed_s,rss_kib\n' > "$case_dir/rss.csv"
while true; do
  state="$(ps -o state= -p "$pid" | tr -d ' ' || true)"
  if [[ -z "$state" || "$state" == "Z" ]]; then
    break
  fi
  elapsed="$(( $(date +%s) - started_epoch ))"
  rss="$(ps -o rss= -p "$pid" | tr -d ' ' || true)"
  if [[ -n "$rss" ]]; then
    printf '%s,%s\n' "$elapsed" "$rss" >> "$case_dir/rss.csv"
  fi
  sleep 1
done

set +e
wait "$pid"
exit_code=$?
set -e

printf '%s\n' "$exit_code" > "$case_dir/exit-code.txt"
printf '%s\n' "$(( $(date +%s) - started_epoch ))" > "$case_dir/wall-seconds.txt"
if [[ "$exit_code" -eq 0 ]]; then
  touch "$case_dir/complete"
fi
exit "$exit_code"
