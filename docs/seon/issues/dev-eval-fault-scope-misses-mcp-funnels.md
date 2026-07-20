---
type: issue
status: open
tags: [issue, cljs, pod, health]
severity: blocker
---

# Dev-eval fault scope misses MCP funnels; a REPL typo crashes the pod

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

Two adjacent recording defects observed on the same datoms (14/14):

- No persisted Proximum branch head (`::store-id`/`::branch-name`/
  `::commit-id`/`::basis-t` all absent) — `branch-head-now` never yields
  a valid head in the live pod, so `recorded-branch-head` returns
  `:missing-branch-head` for every fault and `cluster fork <t>` has no
  anchor.
- `:seon.error/frames` on every fault are ExceptionInfo-constructor noise
  (`{:index 0, :file "new"}` + `cljs.core.js` coords), so the
  Datalog-queryable frame design answers nothing.

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
