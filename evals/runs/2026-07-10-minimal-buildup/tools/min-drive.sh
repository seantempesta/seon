#!/bin/zsh
# min-drive.sh CLUSTER TASK CONTRACT N [ORACLE] — one minimal-context drive.
#   CLUSTER=<min-a|min-b>  TASK=<dir under tmp/t4-masters/py, or "-" for none>
#   CONTRACT=<contracts base>  N=drive#
#   ORACLE=<tools/*.py transcript oracle for TASK "-"; default db-memory-oracle.py>
# Serial only. Resets the task dir from master (when TASK != "-"), POSTs the
# contract to the cluster's pod, extracts the transcript from :<CLUSTER>,
# then runs the oracle: pytest (py tasks) or the transcript ORACLE (TASK "-").
# Adapted from tmp/gram-drive.sh (2026-07-09 run), parameterized by cluster.
set -e
cd /Users/sean/src/seon
CLUSTER=$1; TASK=$2; CONTRACT=$3; N=$4; ORACLE=${5:-db-memory-oracle.py}
EV=evals/runs/2026-07-10-minimal-buildup
PORT=$(cat "tmp/seon-port-$CLUSTER")
TAG="${CLUSTER}-${CONTRACT}-d${N}"

sha_before=$(shasum -a 256 out-bench/client/main.js | awk '{print $1}')
echo "[$TAG] bundle sha before: ${sha_before:0:12} port=$PORT"

if [ "$TASK" != "-" ]; then
  rm -rf "tmp/t4-drive/py/$TASK"
  cp -R "tmp/t4-masters/py/$TASK" "tmp/t4-drive/py/$TASK"
fi

python3 - "$EV/contracts/$CONTRACT.md" > "tmp/min-req-$TAG.json" <<'EOF'
import json, sys
print(json.dumps({"input": open(sys.argv[1]).read(), "timeout_ms": 1800000}))
EOF

STARTED_UTC=$(date -u +%FT%TZ)
echo "[$TAG] dispatching to http://127.0.0.1:$PORT/agents/run ... started=$STARTED_UTC"
start=$(date +%s)
curl -s --max-time 1900 "http://127.0.0.1:$PORT/agents/run" \
  -H 'content-type: application/json' \
  -d @"tmp/min-req-$TAG.json" > "$EV/transcripts/$TAG.response.json"
end=$(date +%s)
echo "[$TAG] drive done in $((end-start))s"
echo "$((end-start))" > "$EV/transcripts/$TAG.wallclock.txt"
cat "$EV/transcripts/$TAG.response.json" | python3 -c 'import json,sys; d=json.load(sys.stdin); print({k:d.get(k) for k in ["agent_id","turns","evals","closed_reason","timed_out","elapsed_ms"]})' 2>&1

sha_after=$(shasum -a 256 out-bench/client/main.js | awk '{print $1}')
if [ "$sha_before" != "$sha_after" ]; then
  echo "[$TAG] NOTE bundle sha on disk changed (${sha_before:0:8}->${sha_after:0:8}) — running pod unaffected (in-memory)"
fi

AGENT=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("agent_id",""))' "$EV/transcripts/$TAG.response.json")
echo "[$TAG] agent_id=$AGENT"

bb "$EV/tools/min-extract.bb" "$AGENT" "$EV/transcripts/$TAG.txt" "$CLUSTER" || echo "[$TAG] extract failed"

if [ "$TASK" != "-" ]; then
  find "tmp/t4-drive/py/$TASK" -name __pycache__ -type d -exec rm -rf {} + 2>/dev/null || true
  (cd "tmp/t4-drive/py/$TASK" && /Users/sean/src/seon/tmp/t4-venv/bin/pytest -q *_test.py > "/Users/sean/src/seon/$EV/transcripts/$TAG.oracle.txt" 2>&1) && OUTCOME=GREEN || OUTCOME=RED
  diff -ru --exclude=__pycache__ \
    "tmp/t4-masters/py/$TASK" "tmp/t4-drive/py/$TASK" \
    > "$EV/transcripts/$TAG.diff.txt" 2>&1 || true
  echo "[$TAG] diff lines: $(wc -l < $EV/transcripts/$TAG.diff.txt)"
else
  python3 "$EV/tools/$ORACLE" "$EV/transcripts/$TAG.txt" > "$EV/transcripts/$TAG.oracle.txt" 2>&1 && OUTCOME=GREEN || OUTCOME=RED
fi
echo "[$TAG] outcome: $OUTCOME  ($(tail -1 $EV/transcripts/$TAG.oracle.txt 2>/dev/null | head -c 120))"

echo "$TAG agent=$AGENT outcome=$OUTCOME wall=$((end-start))s started=$STARTED_UTC sha_ok=$([ "$sha_before" = "$sha_after" ] && echo yes || echo NO)" >> "$EV/transcripts/index.txt"
rm -f "tmp/min-req-$TAG.json"
echo "[$TAG] COMPLETE"
