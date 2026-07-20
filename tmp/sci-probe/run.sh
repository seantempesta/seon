#!/bin/zsh
# Drive the probe through its phases, vmmap-ing between each.
# Usage: ./run.sh <tag>            (tag: jit | nojit)
#        SCI_DISABLE_JIT=1 ./run.sh nojit
set -e
cd "$(dirname "$0")"
TAG=${1:-jit}
BUN=${BUN:-/Users/sean/src/seon/reference-code/bun/build/release/bun}
LOG=out/run-$TAG.log
MEM=out/mem-$TAG.log
FIFO=out/ctl-$TAG
rm -f "$LOG" "$MEM" "$FIFO"
mkfifo "$FIFO"

# MIMALLOC_OS_TAG=240 => honest "Memory Tag 240" labels (bisect finding 5).
MIMALLOC_OS_TAG=240 "$BUN" loader.js < "$FIFO" > "$LOG" 2>&1 &
PID=$!
exec 3> "$FIFO"   # hold the fifo open for writes

phases=(ctx-created bindings-warm workloads-done burst-done post-gc-retention)
for ph in $phases; do
  until grep -q "PHASE $ph READY" "$LOG" 2>/dev/null; do
    kill -0 $PID 2>/dev/null || { echo "process died; log tail:"; tail -5 "$LOG"; exit 1; }
    sleep 0.3
  done
  sleep 1  # settle
  {
    echo "== $ph (pid $PID)"
    vmmap --summary $PID | grep -E "Physical footprint:|Memory Tag 240" || true
  } >> "$MEM"
  echo next >&3
done
wait $PID 2>/dev/null || true
echo "--- $MEM"
cat "$MEM"
echo "--- DATA lines"
grep "^DATA" "$LOG"
