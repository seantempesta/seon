#!/bin/sh
# Per namespace: tally line, red members, and red members whose report names a
# namespace row refused for a missing definition digest.
# Usage: tmp/sweep-ns-rows/count.sh DIR
for f in "$1"/*.txt; do
  ns=$(basename "$f" .txt)
  awk -v ns="$ns" '
    NR==1 {tally=$0}
    /^red / {if (cur!="" ) {reds++; if (hit) digest++}; cur=$2; hit=0}
    /missing :seon.program\/definition-digest/ && /#:seon.ns\{/ {hit=1}
    END {if (cur!="") {reds++; if (hit) digest++}
         sub(/ \/ [0-9]+ ms.*/, "", tally)
         printf "%-45s reds=%-3d ns-digest=%-3d %s\n", ns, reds, digest, substr(tally,1,110)}' "$f"
done
