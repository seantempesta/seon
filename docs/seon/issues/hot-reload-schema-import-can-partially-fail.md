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

The exact dependency path is now grounded in
`docs/prds/runtime-reliability/research/schema-generation-lifecycle-audit-2026-07-15.md`.
Shadow's Node client catches an exception while synchronously importing the
selected JavaScript files, logs `JS reload failed`, returns from
`handle-build-complete`, and still invokes Seon's custom `:build-complete`
notification. ClojureScript `def` reloads have already assigned any namespaces
loaded before the failure. Seon can then wrap that partial live population with
the old committed database contracts and mistake wrapper coverage for a
complete implementation generation.

The ACME evidence run reproduced the dangerous consequence on 2026-07-15. A
stale hot-reloaded pod accepted `POST /agents/run`, then crashed under the
intentional core-fault policy because the transcript renderer resolved
`seon.agent.ctx.run_policy` as a non-function. The same pod had already logged
repeated `Invalid database replay page: writer returned not-ok` failures. A
target-level down/reset rebuilt the current source and database into a ready
watcher, writer, and pod; the identical request then completed normally. Keep
the crash policy: the missing mechanism is atomic generation admission and
readiness invalidation, not error suppression.

The default cluster reproduced the recovery half on 2026-07-18 without a
schema change. A transient unmatched-delimiter build correctly closed runtime
publication. The immediately following valid client and test builds completed,
but the pod logged `reload: committed publication nil` and both watcher and pod
remained not ready until `bin/seon restart`. Later valid-only reloads committed
publication and rehosted runtimes normally. The first complete generation after
a rejected generation must recover without another edit or process restart.

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
- A nominal Shadow `:build-complete` after a caught JavaScript import failure
  cannot reopen admission or rehost agents/tickers.
- A hot-reload test proves dependent namespace order, failed publication, and
  successful recovery without a process restart.
