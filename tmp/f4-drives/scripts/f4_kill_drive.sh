#!/bin/zsh
set -euo pipefail

repo_root=/Users/sean/src/seon
evidence_root="$repo_root/tmp/f4-drives/evidence"
proxy_log="$evidence_root/model-requests.jsonl"
proxy_stdout="$evidence_root/recording-proxy.log"
phase1_log="$evidence_root/kill-phase1.log"
phase2_log="$evidence_root/kill-phase2.log"

cd "$repo_root"
mkdir -p "$evidence_root"
rm -f \
  "$proxy_log" \
  "$proxy_stdout" \
  "$phase1_log" \
  "$phase2_log" \
  "$evidence_root/kill-ready" \
  "$evidence_root/kill-ready.edn" \
  "$evidence_root/kill-recovered-ready" \
  "$evidence_root/kill-recovered.edn" \
  "$evidence_root/allow-rewake" \
  "$evidence_root/kill-phase2-complete" \
  "$evidence_root/kill-complete.edn"

python3 tmp/f4-drives/scripts/recording_proxy.py \
  --port 18090 \
  --upstream http://127.0.0.1:8090/v1/chat/completions \
  --slow-delay-seconds 30 \
  --log "$proxy_log" \
  >"$proxy_stdout" 2>&1 &
proxy_pid=$!

cleanup() {
  if kill -0 "$proxy_pid" 2>/dev/null; then
    kill -TERM "$proxy_pid"
    wait "$proxy_pid" || true
  fi
}
trap cleanup EXIT

for _ in {1..200}; do
  if curl -fsS http://127.0.0.1:18090/health >/dev/null; then
    break
  fi
  sleep 0.05
done
curl -fsS http://127.0.0.1:18090/health >/dev/null

clojure -M:dev tmp/f4-drives/scripts/f4_kill_child.clj phase1 \
  >"$phase1_log" 2>&1 &
phase1_pid=$!

for _ in {1..3600}; do
  received=$(python3 - "$proxy_log" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
rows = [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []
print(sum(row.get("event") == "received" for row in rows))
PY
)
  if [[ -f "$evidence_root/kill-ready" && "$received" -eq 6 ]]; then
    break
  fi
  sleep 0.05
done

received_before_kill=$(python3 - "$proxy_log" <<'PY'
import json, pathlib, sys
rows = [json.loads(line) for line in pathlib.Path(sys.argv[1]).read_text().splitlines()]
print(sum(row.get("event") == "received" for row in rows))
PY
)
if [[ ! -f "$evidence_root/kill-ready" || "$received_before_kill" -ne 6 ]]; then
  echo "kill readiness failed: marker=$([[ -f "$evidence_root/kill-ready" ]] && echo yes || echo no) requests=$received_before_kill" >&2
  kill -TERM "$phase1_pid" 2>/dev/null || true
  wait "$phase1_pid" || true
  exit 1
fi

kill -9 "$phase1_pid"
wait "$phase1_pid" || true

clojure -M:dev tmp/f4-drives/scripts/f4_kill_child.clj phase2 \
  >"$phase2_log" 2>&1 &
phase2_pid=$!

for _ in {1..2400}; do
  if [[ -f "$evidence_root/kill-recovered-ready" ]]; then
    break
  fi
  sleep 0.05
done
if [[ ! -f "$evidence_root/kill-recovered-ready" ]]; then
  echo "recovery readiness failed" >&2
  kill -TERM "$phase2_pid" 2>/dev/null || true
  wait "$phase2_pid" || true
  exit 1
fi

received_after_recovery=$(python3 - "$proxy_log" <<'PY'
import json, pathlib, sys
rows = [json.loads(line) for line in pathlib.Path(sys.argv[1]).read_text().splitlines()]
print(sum(row.get("event") == "received" for row in rows))
PY
)
if [[ "$received_after_recovery" -ne 6 ]]; then
  echo "recovery replayed a request: expected 6, got $received_after_recovery" >&2
  kill -TERM "$phase2_pid" 2>/dev/null || true
  wait "$phase2_pid" || true
  exit 1
fi

touch "$evidence_root/allow-rewake"
for _ in {1..3600}; do
  if [[ -f "$evidence_root/kill-phase2-complete" ]]; then
    break
  fi
  sleep 0.05
done
if [[ ! -f "$evidence_root/kill-phase2-complete" ]]; then
  echo "phase 2 did not complete" >&2
  kill -TERM "$phase2_pid" 2>/dev/null || true
  wait "$phase2_pid" || true
  exit 1
fi
wait "$phase2_pid"

received_final=$(python3 - "$proxy_log" <<'PY'
import json, pathlib, sys
rows = [json.loads(line) for line in pathlib.Path(sys.argv[1]).read_text().splitlines()]
print(sum(row.get("event") == "received" for row in rows))
PY
)
auth_final=$(python3 - "$proxy_log" <<'PY'
import json, pathlib, sys
rows = [json.loads(line) for line in pathlib.Path(sys.argv[1]).read_text().splitlines()]
print(sum(row.get("event") == "received" and row.get("authorization_present") for row in rows))
PY
)
if [[ "$received_final" -ne 12 || "$auth_final" -ne 0 ]]; then
  echo "unexpected final request census: requests=$received_final auth=$auth_final" >&2
  exit 1
fi

cat <<EOF
KILL DRIVE PASS
requests before kill: $received_before_kill
requests after recovery, before explicit re-wake: $received_after_recovery
requests after six explicit re-wakes: $received_final
requests carrying Authorization: $auth_final
EOF
