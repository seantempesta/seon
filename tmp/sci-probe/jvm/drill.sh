#!/usr/bin/env bash
# U1 kill drill runner (sci-execution-runtime design §7).
#
# Starts a PRIVATE drill writer (own store + socket under tmp/host-drill/;
# the shared default cluster is never touched), then runs drill_client.clj,
# which spawns the JVM host, admits 20 agent contexts, kill -9s the host
# mid-eval-wave, restarts it, and asserts fleet restore + honest in-flight
# error values + zero fact loss. Results print as `DRILL <edn>` lines.
set -euo pipefail
cd "$(dirname "$0")/../../.."

mkdir -p tmp/host-drill

# A previous failed run may have left an orphan host owning the socket —
# the drill must kill ITS host, not talk to a stale one.
pkill -f '\-m seon.host' 2>/dev/null || true
rm -f tmp/host-drill/host.sock

writer_pid=""
cleanup() {
  if [[ -n "$writer_pid" ]] && kill -0 "$writer_pid" 2>/dev/null; then
    kill "$writer_pid" 2>/dev/null || true
    wait "$writer_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT

if [[ ! -S tmp/host-drill/writer.sock ]]; then
  clojure -M:writer -m seon.db.server \
    --db-name u1-drill --backend file \
    --path tmp/host-drill/store \
    --req-sock tmp/host-drill/writer.sock \
    > tmp/host-drill/writer.log 2>&1 &
  writer_pid=$!
  for _ in $(seq 1 120); do
    [[ -S tmp/host-drill/writer.sock ]] && break
    sleep 0.5
  done
fi

clojure -M:writer:host -i tmp/sci-probe/jvm/drill_client.clj
