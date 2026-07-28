#!/usr/bin/env bash
# Measurement only: concurrent per-agent turn latency and completion shape.
# Active question/results:
# docs/prds/sci-execution-runtime/research/measurements-2026-07-25.md
# Correctness owner: test/seon/cluster/loop_test.clj.
# The 120-second curl limit guards a foreign HTTP request; it is not proof.
set -euo pipefail

usage() {
  echo "usage: $0 URL LABEL OUTPUT_DIR AGENT_ID..." >&2
  exit 2
}

[[ $# -ge 4 ]] || usage

url=$1
label=$2
output_dir=$3
shift 3
agent_ids=("$@")

[[ $label =~ ^[A-Za-z0-9_:-]+$ ]] || {
  echo "LABEL must contain only letters, digits, underscore, colon, or dash." >&2
  exit 2
}
for agent_id in "${agent_ids[@]}"; do
  [[ $agent_id =~ ^[a-z0-9-]+$ ]] || {
    echo "invalid agent id: $agent_id" >&2
    exit 2
  }
done

command -v curl >/dev/null
command -v jq >/dev/null
mkdir -p "$output_dir"

prompt="Return exactly one Clojure form and no prose: (seon.agent.lifecycle/complete \\\"${label}\\\")"
started_at_file="$output_dir/started-at.tsv"
: > "$started_at_file"

pids=()
for agent_id in "${agent_ids[@]}"; do
  (
    started_at_ms=$(perl -MTime::HiRes=time -e 'printf "%.3f\n", time * 1000')
    printf '%s\t%s\n' "$agent_id" "$started_at_ms" >> "$started_at_file"
    request=$(jq -cn \
      --arg agent_id "$agent_id" \
      --arg input "$prompt" \
      '{agent_id:$agent_id,input:$input,timeout_ms:120000}')
    curl --fail-with-body --silent --show-error \
      --output "$output_dir/$agent_id.json" \
      --write-out '%{http_code}\t%{time_total}\n' \
      --header 'content-type: application/json' \
      --data "$request" \
      "$url/agents/run" > "$output_dir/$agent_id.http.tsv"
  ) &
  pids+=("$!")
done

failed=0
for pid in "${pids[@]}"; do
  if ! wait "$pid"; then
    failed=$((failed + 1))
  fi
done

jq -s \
  --arg label "$label" \
  '{
     expected_reply:$label,
     count:length,
     replies:(map(.reply)|unique),
     eval_counts:(map(.evals)|unique),
     turn_counts:(map(.turns)|unique),
     elapsed_ms:(map(.elapsed_ms)|sort)
   }' \
  "$output_dir"/*.json > "$output_dir/summary.json"

if [[ $failed -ne 0 ]]; then
  echo "$failed request(s) failed; inspect $output_dir" >&2
  exit 1
fi

jq . "$output_dir/summary.json"
