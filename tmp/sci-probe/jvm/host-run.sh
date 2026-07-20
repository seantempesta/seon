#!/bin/zsh
# Drive the C1 JVM sci host probe through phases, vmmap-ing between each.
# Runs from the REPO ROOT on the exact :writer basis (the operator's own
# dependency closure) plus sci from reference-code and this probe's src.
set -e
cd "$(dirname "$0")/../../.."   # repo root
OUT=tmp/sci-probe/jvm/out
LOG=$OUT/host-run.log
MEM=$OUT/host-mem.log
GCLOG=$OUT/host-gc.log
FIFO=$OUT/host-ctl
mkdir -p "$OUT"
rm -f "$LOG" "$MEM" "$GCLOG" "$FIFO"
mkfifo "$FIFO"

clj -Sdeps '{:aliases {:sci-probe {:extra-paths ["tmp/sci-probe/jvm/src"]
                                   :extra-deps {org.babashka/sci {:local/root "reference-code/sci"}}
                                   :jvm-opts ["-Xlog:gc*:file=tmp/sci-probe/jvm/out/host-gc.log:time,uptime"]}}}' \
    -M:writer:sci-probe -m probe.host < "$FIFO" > "$LOG" 2>&1 &
CLJPID=$!
exec 3> "$FIFO"

phases=(baseline base-loaded transport-proof ctx-100 wave-100 interrupt-scale oome-20 footprint)
for ph in $phases; do
  until grep -q "PHASE $ph READY" "$LOG" 2>/dev/null; do
    kill -0 $CLJPID 2>/dev/null || { echo "process died; log tail:"; tail -20 "$LOG"; exit 1; }
    sleep 0.3
  done
  sleep 1
  JPID=$(pgrep -P $CLJPID java 2>/dev/null || echo $CLJPID)
  {
    echo "== $ph (pid $JPID)"
    vmmap --summary $JPID 2>/dev/null | grep -E "Physical footprint" || true
  } >> "$MEM"
  echo next >&3
done
wait $CLJPID 2>/dev/null || true
echo "--- $MEM"; cat "$MEM"
echo "--- DATA"; grep "^DATA" "$LOG"
