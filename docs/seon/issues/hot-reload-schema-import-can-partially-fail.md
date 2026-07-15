---
type: issue
status: open
severity: friction
tags: [issue, agent, schema]
---

# Make schema hot reload atomic

## Problem

A watched client reload can fail while importing a dependent namespace with
`:malli.core/invalid-schema`, then continue into exact instrumentation and leave
the pod reporting ready. The operator cannot tell whether the runtime still
represents the prior complete generation or a partially replaced one.

## Evidence

After a clean public restart on 2026-07-14, the default pod booted with 792
instrumented functions and zero unresolved schemas. A later watcher reload
logged `SHADOW import error .../seon.agent.ctx.js` and
`JS reload failed ... :malli.core/invalid-schema`, then logged a successful
exact-instrumentation pass over 39 namespaces without failing readiness. No
`seon.agent.ctx` source was part of the active database-coordinate edit. The
full trace is retained in the operator pod log beginning at
`2026-07-15T00:18:09.918Z`.

Historical research and the archived dual-path audit already identify stale
schema projections across hot reload, but there is no open issue owning this
observed partial-import/readiness behavior.

The ACME evidence run reproduced the dangerous consequence on 2026-07-15. A
stale hot-reloaded pod accepted `POST /agents/run`, then crashed under the
intentional core-fault policy because the transcript renderer resolved
`seon.agent.ctx.run_policy` as a non-function. The same pod had already logged
repeated `Invalid database replay page: writer returned not-ok` failures. A
target-level down/reset rebuilt the current source and database into a ready
watcher, writer, and pod; the identical request then completed normally. Keep
the crash policy: the missing mechanism is atomic generation admission and
readiness invalidation, not error suppression.

## Owner

The one core program/schema publication generation and
`seon.client/after-reload` transition. Shadow namespace loading,
database-indexed schemas, and instrumentation must publish or retain one
complete generation atomically.

## Acceptance

- A deliberate schema dependency edit either publishes one complete new
  generation or retains the complete prior generation.
- An invalid schema import cannot proceed to a misleading successful
  instrumentation/readiness state.
- Logs and readiness name the retained or published generation and the exact
  rejected schema/symbol.
- A hot-reload test proves dependent namespace order, failed publication, and
  successful recovery without a process restart.
