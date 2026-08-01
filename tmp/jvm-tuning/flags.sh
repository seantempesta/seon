#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
result_dir="$repo_root/tmp/jvm-tuning/results/flags"
mkdir -p "$result_dir"

print_flags() {
  local label="$1"
  shift
  java "$@" -XX:+PrintFlagsFinal -version > "$result_dir/$label.txt" 2>&1
}

print_flags host-current \
  -XX:+UseG1GC -XX:MaxRAMPercentage=25.0
print_flags simulated-16g-current \
  -XX:+UseG1GC -XX:MaxRAM=16g -XX:MaxRAMPercentage=25.0
print_flags host-g1-12_5 \
  -XX:+UseG1GC -XX:MaxRAMPercentage=12.5
print_flags simulated-16g-g1-12_5 \
  -XX:+UseG1GC -XX:MaxRAM=16g -XX:MaxRAMPercentage=12.5
print_flags host-zgc-12_5 \
  -XX:+UseZGC -XX:MaxRAMPercentage=12.5 -XX:SoftMaxHeapSize=2g
print_flags simulated-16g-zgc-12_5 \
  -XX:+UseZGC -XX:MaxRAM=16g -XX:MaxRAMPercentage=12.5 \
  -XX:SoftMaxHeapSize=1g

for path in "$result_dir"/*.txt; do
  rg '(InitialHeapSize|MaxHeapSize|SoftMaxHeapSize|MaxRAM |MaxRAMPercentage|UseCompressedOops|UseCompactObjectHeaders|UseG1GC|UseZGC|EnableDynamicAgentLoading|ResizeTLAB|TLABSize)' \
    "$path" > "${path%.txt}.selected.txt"
done
