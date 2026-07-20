---
type: issue
status: open
tags: [issue, cljs, pod, health]
severity: friction
---

# Dev-eval fault scope misses MCP funnels; a REPL typo crashes the pod

## Resolution 2026-07-20 (scope/classification — the blocker part)

Root cause proven live: Bun (1.4.0) does NOT carry AsyncLocalStorage into
the process `unhandledRejection` listener (probe: `in-dev-eval?` true in
the spawning fiber, after `await`, and in a `.then` continuation, but
FALSE inside a prepended `unhandledRejection` listener). Any dev-eval
rejection that only surfaced through the process net lost the dev-eval
scope and classified `:core`; the wrapper `.catch` funnel (fiber context
intact) classified the identical mistake `:agent` — the observed
path-dependence.

Fix (one owner): `seon.error/dev-eval!` now settles the form's own
returned Promise — an unrecorded rejection records `:agent` in-fiber
(scope intact) with a steering console line, and never reaches the
process net. A wrapper-arm-recorded fault (a core fn's own output
breach) is deduped via `recorded?` and keeps its `:core` datom.

Live proof (default cluster): datom 4287 `:agent` "No protocol method
ICounted…[object Promise]" — the exact detector probe, pod stayed up;
datom 4293 `:agent` `db/pull`-given-a-Promise through the previously
diverging plain-Shadow funnel. Tests:
`seon.error-record-test/dev-eval-settlement-records-agent-fault` and
`…-defers-to-a-wrapper-recorded-fault`.

Crash attribution CORRECTED: datom 3857 (ICounted) recorded `:core` but
did NOT exit the pod — the process net has no configuration scope, so the
dial read its `:gate` default. The 13:41 EDT exit was datom 3863 "Shadow
build failed while runtime publication was closed" (a genuine `:core`
fault with scope intact under `:crash` — the deliberate fail-loud path,
provoked by a broken `my/skills.cljs` await form).

Remaining (this issue stays open, severity downgraded):

- A TRULY DETACHED fiber a dev eval spawned without returning its
  Promise still classifies `:core` at the process net (live datom 4304);
  it cannot crash the pod (no configuration scope → `:gate`), but the
  fault census is polluted and the crash dial is funnel-dependent — see
  `bun-rejection-net-loses-async-scope.md`.
- The two recording defects below (branch head, frames) are untouched.

## Evidence

On 2026-07-20 the live-system detector audit
(`docs/prds/source-cleanup/research/live-system-detectors-2026-07-20.md`)
recorded, in the live default cluster:

- Fault datom 3857 (13:40:24 EDT): an `eval_cljs` MCP probe evaluated
  `((fn ^:async [] (let [d (await (db/db))] (count (db/installed-schema d)))))`
  — a caller mistake (counting a Promise) inside a detached async fiber.
  The rejection was recorded `:seon.error/fault :core`, and with
  `config/system.edn:165` `:seon.config/on-core-error :crash` the pod
  exited. `bin/seon restart` confirmed `pod: forced
  reason=unexpected-exit`.
- Fault datoms 3689/3700 (11:38) and 3767/3778 (12:21): identical
  `seon.db/pull`-given-a-Promise dev-eval mistakes recorded `:core`,
  while 3711–3755 (11:38–11:40) were correctly `:agent`. Classification
  of the same mistake is path-dependent.

`seon.error/dev-eval!` (`src/seon/error.cljs:474-495`) documents that a
dev/MCP REPL caller mistake is `:agent` population — "dev probing must
not crash the pod" — including Promise settlement through
AsyncLocalStorage propagation. The live behavior contradicts that for at
least the detached-fiber rejection funnel and some instrument-wrapper
paths reached from Shadow-nREPL MCP evals.

Two adjacent recording defects observed on the same datoms (14/14) —
both FIXED 2026-07-20 (source-cleanup register R3):

- No persisted Proximum branch head — root cause: `seon.db` installed
  only the `:seon.error/transact!` hook and never a
  `:seon.error/branch-head` hook, so `branch-head-now` had nothing to
  call. The hook now derives the head from the session's cached current
  database value (`branch/head-from-database-value`). Live-proven: fault
  datom 6248 carries the complete head and `recorded-branch-head`
  returns a valid `::branch/head` anchor for `cluster fork <t>`.
- `:seon.error/frames` were ExceptionInfo-constructor noise — fixed at
  the recording site: frames now parse the DEEPEST cause's stack, the
  `at new Ctor (…)`/`at async …` and Bun `undefined.` shapes are
  repaired before parsing, and the leading error-construction frames are
  dropped so the top frame is the throw site.

## Expected owner

`seon.error` owns the fault scope and `record!` projection;
`seon.client`'s `js/SHADOW_NODE_EVAL` patch is the documented one
dev-eval choke point. Either the MCP/Shadow entry is not funneled through
`dev-eval!`, or the ALS scope is lost before `seon.instrument/wrapper-fault`
and the unhandled-rejection funnel classify the fault.

## Acceptance

- A deliberately bad `eval_cljs` form (sync throw, and a detached
  `^:async` rejection) records `:agent`, never `:core`, and the pod stays
  up under `:crash`.
- A persisted fault carries the complete catch-site branch head when a
  database session is live.
- Frames on a new fault name the throw site, not the ExceptionInfo
  constructor.
