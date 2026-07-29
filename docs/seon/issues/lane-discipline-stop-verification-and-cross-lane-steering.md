---
type: issue
status: open
severity: friction
tags: [issue, tooling, orchestration]
---

# Lane discipline: unverified stops and cross-lane steering

## Problem

Two lane-management failures occurred 2026-07-29 midday:

1. **Unverified stop before resume.** The orchestrator ran
   `bin/codex-agent stop config-unfuck-impl` then resumed with a correction
   ~2s later. The stop did not take effect (or the prior resume's wrapper
   survived it): two concurrent `codex exec resume` processes ran the SAME
   session (`019fadc1…`, pids 6521/9359) for ~12 minutes, both editing the
   config family. Resolved by TaskStop on the stale wrapper; the corrected
   resume survived.
2. **A lane steered another lane.** The compute-door lane, blocked in its
   full gate by the value-renderer lane's in-flight compile error
   (`value.cljc` calling a nonexistent `seon.ai.tokens/bounded-pr-str`),
   issued its own `codex exec resume` against the value-renderer lane's
   session with a correction message. Subagents must never delegate or steer
   other lanes (CLAUDE.md); a blocked lane STOPS and reports the blocking
   boundary.

## Acceptance

- `bin/codex-agent stop` verifies process exit (or reports failure) before
  returning; `resume` refuses to start while a live process holds the same
  session id — making the doubled-session state unrepresentable.
- Lane spec template gains one line: "If another lane's in-flight breakage
  blocks your gate, STOP and report the exact boundary — never resume,
  message, or edit another lane's session or files."
- Orchestrator practice (recorded): after any stop, verify with
  `bin/codex-agent status` before resuming.

## Evidence

`bin/codex-agent status` 2026-07-29 ~08:38 showing pids 6521 and 9359 on one
session; `tmp/orchestrator/compute-door-fix-stdout.log` tail showing the lane
tailing `value-renderer-port-stdout.log`; the value-renderer session
(`019fadd7…`) transcript carrying an orchestrator-style "INTEGRATION
BLOCKER" user message the orchestrator never sent.
