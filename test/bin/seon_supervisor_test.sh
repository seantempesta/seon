#!/usr/bin/env bash

# Behavioral process-safety checks for bin/seon. The real supervisor functions
# run against an isolated registry and harmless sleep processes; no Seon
# cluster, port, socket, store, or build artifact is touched.

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SANDBOX="$(mktemp -d "${TMPDIR:-/tmp}/seon-supervisor-test.XXXXXX")"
export SEON_PROC_DIR="$SANDBOX/proc"
export SEON_LOG_DIR="$SANDBOX/logs"
export SEON_PORT_FILE="$SANDBOX/pod-port"
export SEON_WRITER_REPL_PORT_FILE="$SANDBOX/writer-port"
export SEON_REQ_SOCK="$SANDBOX/req.sock"
export SEON_PUB_SOCK="$SANDBOX/pub.sock"
export TEST_CHILD_PID_FILE="$SANDBOX/child-pid"

# Sourcing defines the production lifecycle without dispatching a command.
# shellcheck source=../../bin/seon
source "$ROOT/bin/seon"

process_command() {
  case "$1" in
    dummy|serial) echo "exec sleep 300" ;;
    tree)
      echo "sleep 300 & child=\$!; echo \$child > \"$TEST_CHILD_PID_FILE\"; wait \$child" ;;
    *) return 1 ;;
  esac
}

# These harmless test processes have no readiness protocol.
ready_bound() { echo 0; }
assert_no_unmanaged_listener() { return 0; }
maybe_prep_deps() { return 0; }
maybe_build_bootstrap() { return 0; }
clear_readiness_artifacts() { return 0; }

fail() {
  echo "FAIL: $*" >&2
  cleanup
  exit 1
}

cleanup() {
  local name pid
  for name in dummy serial tree stale; do
    pid="$(cat "$(pid_file "$name")" 2>/dev/null || true)"
    if [ -n "$pid" ]; then
      kill -KILL -- "-$pid" 2>/dev/null || kill -KILL "$pid" 2>/dev/null || true
    fi
  done
  if [ -f "$TEST_CHILD_PID_FILE" ]; then
    kill -KILL "$(cat "$TEST_CHILD_PID_FILE")" 2>/dev/null || true
  fi
  [ -n "${INNOCENT_PID:-}" ] && kill "$INNOCENT_PID" 2>/dev/null || true
  rm -rf "$SANDBOX"
}

echo "[1/5] start is idempotent and records process-instance ownership"
cmd_start dummy >/dev/null
first_pid="$(cat "$(pid_file dummy)")"
[ -s "$(pid_start_file dummy)" ] || fail "start did not record a process start stamp"
[ -s "$(session_owned_file dummy)" ] || fail "start did not record session ownership"
cmd_start dummy >/dev/null
[ "$(cat "$(pid_file dummy)")" = "$first_pid" ] || fail "idempotent start replaced the live process"

echo "[2/5] stop drains the complete owned process group"
cmd_stop dummy >/dev/null
kill -0 "$first_pid" 2>/dev/null && fail "stopped leader is still alive"

rm -f "$TEST_CHILD_PID_FILE"
cmd_start tree >/dev/null
for _ in 1 2 3 4 5 6 7 8 9 10; do
  [ -s "$TEST_CHILD_PID_FILE" ] && break
  sleep 0.1
done
[ -s "$TEST_CHILD_PID_FILE" ] || fail "tree child was never spawned"
tree_child="$(cat "$TEST_CHILD_PID_FILE")"
cmd_stop tree >/dev/null
kill -0 "$tree_child" 2>/dev/null && fail "stop orphaned a child from the owned process group"

echo "[3/5] stale PID reuse never signals the unrelated process"
sleep 300 &
INNOCENT_PID=$!
ensure_state stale
echo "$INNOCENT_PID" > "$(pid_file stale)"
echo "definitely-not-the-live-start-stamp" > "$(pid_start_file stale)"
cmd_stop stale >/dev/null 2>&1
kill -0 "$INNOCENT_PID" 2>/dev/null || fail "stale registration killed an unrelated PID"
kill "$INNOCENT_PID"
wait "$INNOCENT_PID" 2>/dev/null || true
INNOCENT_PID=""

echo "[4/5] the lifecycle lock serializes a concurrent process mutation"
hold_stack() {
  echo entered > "$SANDBOX/stack-entered"
  sleep 1
  echo released > "$SANDBOX/stack-released"
}
with_stack_lock hold_stack &
holder=$!
for _ in 1 2 3 4 5 6 7 8 9 10; do
  [ -f "$SANDBOX/stack-entered" ] && break
  sleep 0.1
done
[ -f "$SANDBOX/stack-entered" ] || fail "stack-lock holder never entered"
cmd_start serial >/dev/null
[ -f "$SANDBOX/stack-released" ] || fail "start interleaved with a held lifecycle transition"
wait "$holder"
cmd_stop serial >/dev/null

echo "[5/5] releasing an inner lock preserves outer-lock crash cleanup"
(
  acquire_lock outer
  acquire_lock inner
  release_lock inner
  # Simulate a failure after an inner critical section returned.
  exit 7
) >/dev/null 2>&1 || true
[ ! -e "$(lock_dir outer)" ] && [ ! -L "$(lock_dir outer)" ] \
  || fail "outer lock leaked after inner release + failure"
[ ! -e "$(lock_dir inner)" ] && [ ! -L "$(lock_dir inner)" ] \
  || fail "inner lock leaked"

cleanup
echo "PASS: bin/seon supervisor process-safety behaviors"
