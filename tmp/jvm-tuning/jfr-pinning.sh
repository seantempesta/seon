#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "usage: jfr-pinning.sh CASE FROZEN-TREE-NAME" >&2
  exit 2
fi

case_name="$1"
tree_name="$2"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
measurement_tree="$repo_root/tmp/jvm-tuning/$tree_name"
recording="$repo_root/tmp/jvm-tuning/results/$case_name/recording.jfr"

if [[ ! -f "$measurement_tree/.jvm-tuning-head" ]]; then
  echo "not a frozen JVM-tuning tree: $measurement_tree" >&2
  exit 2
fi

JVM_TUNING_TREE="$measurement_tree" \
JVM_TUNING_OMIT_UNSAFE_ALLOW=1 \
  "$repo_root/tmp/jvm-tuning/run-case.sh" \
  "$case_name" turn 2g 0 \
  -XX:+UseG1GC \
  -XX:StartFlightRecording="filename=$recording,settings=default,dumponexit=true"

jfr summary "$recording"
jfr print \
  --events jdk.VirtualThreadPinned,jdk.VirtualThreadSubmitFailed,jdk.FinalFieldMutation \
  "$recording"
