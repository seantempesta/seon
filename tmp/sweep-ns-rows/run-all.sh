#!/bin/sh
# One bin/test-check request per namespace, sequentially; output per namespace.
# Usage: tmp/sweep-ns-rows/run-all.sh OUT-DIR [NAMESPACE-FILE]
out=$1; list=${2:-tmp/sweep-ns-rows/run-namespaces.txt}
mkdir -p "$out"
for ns in $(cat "$list"); do
  start=$(date +%s)
  bin/test-check default --ns "$ns" > "$out/$ns.txt" 2>&1
  echo "$ns exit=$? secs=$(( $(date +%s) - start )) $(head -1 "$out/$ns.txt")" >> "$out/SUMMARY"
done
echo DONE >> "$out/SUMMARY"
