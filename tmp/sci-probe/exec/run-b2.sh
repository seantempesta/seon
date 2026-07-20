#!/bin/zsh
# B2-EXPERIMENTAL A/B drive (sci-execution-runtime PRD, stage B2).
# Usage: run-b2.sh <label> <execution-output> <execution-build-id> [turns]
# Reads the branch lifecycle record for the b2 branch database coordinates.
set -euo pipefail
cd /Users/sean/src/seon

LABEL=$1
EXEC_OUT=$2
EXEC_BUILD=$3
TURNS=${4:-20}
RECORD=tmp/seon-operator/branches/default-b2.edn
OUT_DIR=tmp/sci-probe/exec/out

[ -f "$RECORD" ] || { echo "branch record missing: $RECORD" >&2; exit 1; }
DIGEST=$(shasum -a 256 "$EXEC_OUT" | cut -d' ' -f1)

# Extract branch database coordinates from the lifecycle record with bb.
OPTS=$(bb -e '
(let [record (clojure.edn/read-string {:default (fn [_ v] v)} (slurp "'"$RECORD"'"))
      descriptor (:seon.dev.branch/launch-descriptor record)
      database (:seon.launch/database descriptor)
      writer (:seon.launch/writer-owner descriptor)]
  (print
   (pr-str
   {:b2/socket-path (:seon.launch/request-socket-path writer)
    :b2/database-name (:seon.db.protocol/database-name database)
    :b2/database-path (:seon.db.protocol/database-path database)
    :b2/connection-id (:seon.db.branch/connection-id database)
    :b2/execution-output "'"$EXEC_OUT"'"
    :b2/execution-build-id "'"$EXEC_BUILD"'"
    :b2/execution-digest "'"$DIGEST"'"
    :b2/label "'"$LABEL"'"
    :b2/turns '"$TURNS"'
    :b2/out-dir "'"$OUT_DIR"'"})))
')

echo "driver options: $OPTS"
mkdir -p "$OUT_DIR"
export MIMALLOC_OS_TAG=240
exec bun out-b2/driver/main.js "$OPTS" 2>&1 | tee "$OUT_DIR/$LABEL-drive.log"
