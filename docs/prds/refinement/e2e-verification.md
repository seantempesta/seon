# E2E Agent Pool + Observatory Verification

Date: 2026-02-22

## Summary

The agent launch pipeline works end-to-end after two bug fixes to `resume-key` implementations in `src/seon/system.clj`.

## What Works

1. **Pool status** -- 3 idle JVMs at startup, auto-scales to 4 after first agent use
2. **Agent launch** -- `launch-agent!!` claims a pool JVM, runs the agent, returns structured result with status, cost, duration, and turns
3. **Pool recovery** -- JVM released back to idle after agent completes (4 idle, 0 active)
4. **Observatory** -- `/agents` page loads and renders (SSE-driven, skeleton UI on initial load)
5. **Runtime registry** -- `agent-runs` returns completed run with all metadata (namespace, cost, duration, turns, timestamps)
6. **Session lifecycle** -- `start-agent-session!` creates ctx, claims pool JVM, registers in runtime; `stop-agent-session!` releases JVM, destroys ctx, unregisters
7. **Test suite** -- 535 tests, 2672 assertions, 0 failures

## Bugs Found and Fixed

### 1. `resume-key :seon/runtime-db` did not re-wire runtime atom

**Symptom:** After `(reset)`, `seon.runtime/register!` threw `LMDB env is closed` because the `defonce` conn atom held a stale Datalevin connection reference.

**Root cause:** `resume-key` returned `old-state` without re-calling `runtime/init!`. While `suspend-key!` does not close the conn, the atom could hold a stale ref from a previous halt cycle (e.g., hard restart followed by resume).

**Fix:** `resume-key` now always calls `(runtime/init! {::runtime/conn (:conn old-state)})` and re-wires `seon.render/set-conn!` even when config is unchanged.

### 2. `resume-key :seon.orchestrator/sessions` did not re-wire pool atom

**Symptom:** After `(reset)`, `start-agent-session!` returned `nrepl-port nil` because `@agent-pool` was nil -- the pool atom was never set.

**Root cause:** Same pattern -- `resume-key` returned `old-state` without re-calling `session/init!`, so the `defonce` pool atom stayed nil.

**Fix:** `resume-key` now always calls `(session/init! connection-manager :pool pool)` even when config is unchanged.

### General Pattern

Any Integrant component that uses `defonce` atoms for internal state AND has a `resume-key` that short-circuits on unchanged config needs to re-wire those atoms on resume. The atoms survive code reload (that is what `defonce` does), but if `init!` was the only code path that set them, and resume skips init, the atoms stay nil or stale.

## Test Agent Run

- **Namespace:** `seon.health`
- **Task:** "Compute BMI for height 1.80m weight 75kg"
- **Result:** BMI = 23.15 (Normal weight), status `:completed`
- **Cost:** $0.25, 5 turns, 27s duration
- **Pool:** Claimed port 7900, released back to idle after completion

## Files Changed

- `src/seon/system.clj` -- Fixed `resume-key` for `:seon/runtime-db` and `:seon.orchestrator/sessions`
