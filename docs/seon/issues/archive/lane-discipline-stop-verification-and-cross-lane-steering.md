---
type: issue
status: resolved
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

## Owner

Tooling lane; `bin/codex-agent`.

## Evidence

`bin/codex-agent status` 2026-07-29 ~08:38 showing pids 6521 and 9359 on one
session; `tmp/orchestrator/compute-door-fix-stdout.log` tail showing the lane
tailing `value-renderer-port-stdout.log`; the value-renderer session
(`019fadd7…`) transcript carrying an orchestrator-style "INTEGRATION
BLOCKER" user message the orchestrator never sent.

## Resolution

Resolved 2026-07-29 by commit `74a65d53d` in `bin/codex-agent`:

- atomic per-name ownership records refuse a second lane instance and retain
  the wrapper, Codex, transcript, and session ownership PIDs;
- atomic per-session ownership plus exact live-process discovery refuses a
  resume before launch and reports the holder PID;
- `stop` terminates the recorded process tree, polls its observable exit for
  up to three seconds, and returns nonzero with surviving PIDs if verification
  fails;
- each run and resume atomically replaces the canonical summary with a UTC
  launch marker, writes Codex's last message to a launch-private path, and
  atomically promotes that completed output; and
- every initial spec gains the acceptance sentence forbidding cross-lane
  steering.

## Proof

`sh -n bin/codex-agent` passed. A deterministic fake-Codex smoke run proved:

- second `run tooling-fake-smoke` refused with holder pid 54279;
- concurrent resume of session
  `11111111-1111-1111-1111-111111111111` refused with holder pid 54300;
- the resumed canonical summary immediately showed
  `command: resume`, its UTC `launched-at`, and the session ID; and
- `stop tooling-fake-smoke` returned only after all recorded PIDs were absent.

A real Codex 0.144.6 smoke run then proved the production boundary:

- concurrent resume of live session
  `019fae7e-e704-7cf1-9682-9bcd2705868e` exited 1 and reported holder pid
  57367;
- the interrupted run's `stop` verified the wrapper, Codex processes, and
  active shell-command descendants had exited;
- resume atomically replaced the prior run marker with
  `launch-id: 2026-07-29T15:29:53Z-58142`, `command: resume`, and the same
  session ID;
- a concurrent same-name run exited 1 with holder pid 58142; and
- the resumed lane's `stop` again returned only after every recorded PID had
  exited. A subsequent `bin/codex-agent status` showed no smoke lane.
