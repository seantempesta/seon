#!/usr/bin/env bash
set -euo pipefail

clojure -M:dev tmp/submit-probe/submit_roundtrip.clj
clojure -M:dev tmp/submit-probe/startup_wait.clj
clojure -M:dev tmp/submit-probe/workload_split.clj
