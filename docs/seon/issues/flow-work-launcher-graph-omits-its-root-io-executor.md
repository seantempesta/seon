---
type: issue
status: open
severity: friction
tags: [issue, flow, runtime, class/p1, wave/seon-env-p3]
---

# The work-launcher graph does not pass its root :io executor

## Problem

`seon.flow`'s work-launcher graph definition supplies `:compute-exec` and
nothing else. flow's resolver falls back to core.async's process-global
memoized executor for any workload the graph did not name, so the launcher
proc's own run loop — and every other `:io` proc's run loop in that graph —
executes on the global `:io` executor rather than on the process root's.

The process root's executors are supposed to be the whole truth for a Seon
graph's placement. Here half of that truth is supplied and half is inherited
from a global the root does not own.

## Evidence

- `src/seon/flow.clj:528` — the graph definition's only executor key is
  `:compute-exec compute-executor`.
- `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:148`
  — `(get-exec [_ context] (or (execs context) (disp/executor-for context)))`.
  With no `:io-exec`, `:io` resolves to the global.
- `.../flow/impl.clj:262` and `:323` — the proc run loop is placed with
  `(spi/get-exec resolver :io)` and launched through `futurize`.
- `.../clojure/core/async/impl/dispatch.clj:98-111` — `executor-for` memoizes
  one process-global executor per workload tag.
- Measured, `tmp/env-probes/flow_env_carriage.clj` (`run-loop-placement`): the
  same `:io` proc lands on `{:thread-name "" :virtual? true}` with no
  `:io-exec`, and on `{:thread-name "probe-explicit-io-exec" :virtual? false}`
  when `:io-exec` is supplied. The escape is real.

**Important qualification — this is latent today, not live.**
`resources/seon/operator/runtime.clj:17-22` defines the root's `:io` as
`(async.dispatch/executor-for :io)`, i.e. the global executor itself; the
probe confirms `:root-io-is-global-io? true`. So passing `:io-exec` today
would change nothing observable. The root's `:compute` IS its own
(`:root-compute-is-global-compute? false`), which is why the graph passes
that one. The defect is that the root cannot express a distinct `:io`
executor, and the day it does, every graph's run loops silently keep the
global one.

Source grounding:
[environment-mechanism-flow-2026-08-07.md](../../prds/sci-execution-runtime/research/environment-mechanism-flow-2026-08-07.md)
(§4 item 5 — the original reading).
Live falsification:
[env-phase0-flow-carriage-2026-08-07.md](../../prds/sci-execution-runtime/research/env-phase0-flow-carriage-2026-08-07.md).
Named as a scope item in the sealed
[seon.env PRD](../../prds/sci-execution-runtime/plan/seon-env-prd-2026-08-07.md)
("Also in scope", item 4).

## Owner

`seon.flow/work-launcher-graph-definition` (`src/seon/flow.clj:496-528`), with
the root executor pair at `resources/seon/operator/runtime.clj:17-22`.

## Acceptance criteria

- Every `flow/create-flow` call in `seon.flow` names both `:compute-exec` and
  `:io-exec` from the process root's executor pair, so no graph's placement
  depends on a global the root does not own. `start-error-fanout!`'s fault
  graph (`src/seon/flow.clj:872`) names neither today and is in scope.
- The behavioral proof is placement, not configuration: an `:io` proc in a
  Seon graph reports a run-loop thread belonging to the root's `:io` executor.
  This requires the root's `:io` to be distinguishable from the global, so
  either give the root its own named virtual-thread executor or record
  explicitly why sharing core.async's is correct.
- One class regression, not a per-graph test: whatever construction path
  builds Seon flow graphs supplies both executors by construction, so a new
  graph cannot omit them.
