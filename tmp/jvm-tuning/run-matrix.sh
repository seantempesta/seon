#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
frozen_tree="$repo_root/tmp/jvm-tuning/frozen-tree"

if [[ ! -f "$frozen_tree/.jvm-tuning-head" ]]; then
  echo "run freeze-head.sh first" >&2
  exit 2
fi

for mode in init turn; do
  for heap in 2g 4g 8g; do
    JVM_TUNING_TREE="$frozen_tree" \
      "$repo_root/tmp/jvm-tuning/run-case.sh" \
      "frozen-$mode-g1-$heap" "$mode" "$heap" 0 -XX:+UseG1GC
    JVM_TUNING_TREE="$frozen_tree" \
      "$repo_root/tmp/jvm-tuning/run-case.sh" \
      "frozen-$mode-zgc-$heap" "$mode" "$heap" 0 -XX:+UseZGC
  done
done

python3 "$repo_root/tmp/jvm-tuning/summarize.py" \
  "$repo_root/tmp/jvm-tuning/results"/frozen-*
