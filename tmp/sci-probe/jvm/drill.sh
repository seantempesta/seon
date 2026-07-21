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
source tmp/sci-probe/jvm/drill-lifecycle.sh
DRILL_WRITER_PID=""
cleanup() {
  drill_stop_owned_writer
}
trap cleanup EXIT

drill_clean_private_runtime
drill_start_writer tmp/host-drill/writer.log

clojure -M:writer:host -i tmp/sci-probe/jvm/drill_client.clj
