#!/usr/bin/env bash
# Driver for the multi-reader spike. Run from the libdatahike-cljs dir
# (cd pod-host/libdatahike-cljs && ./spikes/multi-reader/run.sh).
#
# Step 1: writer creates fresh store.
# Step 2: two readers, in parallel, against the static store.
# Step 3: 1 rw-writer + 2 ro-pollers, all concurrent for ~6s.
set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
LIB_DIR="$(cd "$HERE/../.." && pwd)"
cd "$LIB_DIR"

STORE="$LIB_DIR/../../tmp/multi-reader/store.sqlite"
SCRIPT="$LIB_DIR/out/spike-multireader.js"
mkdir -p "$(dirname "$STORE")"

if [[ ! -f "$SCRIPT" ]]; then
  echo "[run.sh] compiling spike-multireader build..."
  npx shadow-cljs compile spike-multireader
fi

banner() { echo; echo "=== $1 ==="; }

banner "STEP 1 — writer (fresh store)"
node "$SCRIPT" writer "$STORE"

banner "STEP 2 — two concurrent readers"
node "$SCRIPT" reader "$STORE" &  R1=$!
node "$SCRIPT" reader "$STORE" &  R2=$!
wait $R1; S1=$?
wait $R2; S2=$?
echo "[run.sh] reader exits: $S1 $S2"
if [[ $S1 -ne 0 || $S2 -ne 0 ]]; then
  echo "[run.sh] Step 2 FAILED — skipping Step 3"
  exit 1
fi

banner "STEP 3 — 1 rw-writer + 2 ro-pollers concurrent"
node "$SCRIPT" rwwriter "$STORE" >tmp/multi-reader/step3-rwwriter.log 2>&1 &  W=$!
sleep 0.2
node "$SCRIPT" ropoll "$STORE" >tmp/multi-reader/step3-ropoll1.log 2>&1 &     P1=$!
node "$SCRIPT" ropoll "$STORE" >tmp/multi-reader/step3-ropoll2.log 2>&1 &     P2=$!
wait $W;  SW=$?
wait $P1; SP1=$?
wait $P2; SP2=$?
echo "[run.sh] step3 exits: writer=$SW poll1=$SP1 poll2=$SP2"
echo
echo "--- step3 rwwriter ---"
cat tmp/multi-reader/step3-rwwriter.log
echo
echo "--- step3 ropoll1 ---"
cat tmp/multi-reader/step3-ropoll1.log
echo
echo "--- step3 ropoll2 ---"
cat tmp/multi-reader/step3-ropoll2.log
