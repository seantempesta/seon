#!/usr/bin/env bash
set -euo pipefail

clojure \
  -J-Djdk.virtualThreadScheduler.parallelism=1 \
  -J-Djdk.virtualThreadScheduler.maxPoolSize=1 \
  -M:dev \
  tmp/submit-probe/workload_split.clj
