#!/bin/zsh
# Drive the JVM probe through phases, vmmap-ing between each.
set -e
cd "$(dirname "$0")"
LOG=out/run.log
MEM=out/mem.log
FIFO=out/ctl
mkdir -p out
rm -f "$LOG" "$MEM" "$FIFO"
mkfifo "$FIFO"

XMX=${XMX:-512m}
clj -J-Xmx$XMX -M -m probe.jvm < "$FIFO" > "$LOG" 2>&1 &
CLJPID=$!
exec 3> "$FIFO"

phases=(baseline shared-loaded ctx-1 ctx-20 workload interrupt memory-bomb)
for ph in $phases; do
  until grep -q "PHASE $ph READY" "$LOG" 2>/dev/null; do
    kill -0 $CLJPID 2>/dev/null || { echo "process died; log tail:"; tail -5 "$LOG"; exit 1; }
    sleep 0.3
  done
  sleep 1
  # vmmap the actual java child, not the clj wrapper
  JPID=$(pgrep -P $CLJPID java 2>/dev/null || echo $CLJPID)
  {
    echo "== $ph (pid $JPID)"
    vmmap --summary $JPID 2>/dev/null | grep -E "Physical footprint:" || true
  } >> "$MEM"
  echo next >&3
done
wait $CLJPID 2>/dev/null || true
echo "--- $MEM"; cat "$MEM"
echo "--- DATA"; grep "^DATA" "$LOG"
