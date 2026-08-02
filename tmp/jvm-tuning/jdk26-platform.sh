#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
result_dir="$repo_root/tmp/jvm-tuning/results/jdk26-platform"
mkdir -p "$result_dir"

{
  java -version
  sysctl vm.pagesize hw.pagesize
} > "$result_dir/platform.txt" 2>&1

probe() {
  local label="$1"
  shift
  set +e
  java "$@" -version > "$result_dir/$label.txt" 2>&1
  local status=$?
  set -e
  printf '%s\n' "$status" > "$result_dir/$label.exit"
}

probe pages -Xlog:pagesize=info
probe large-pages -Xlog:pagesize=info -XX:+UseLargePages
probe transparent-huge-pages -XX:+UseTransparentHugePages
probe trim-native -XX:TrimNativeHeapInterval=30000
probe pinned-property -Djdk.tracePinnedThreads=full
probe container -Xlog:os+container=trace -XshowSettings:vm
