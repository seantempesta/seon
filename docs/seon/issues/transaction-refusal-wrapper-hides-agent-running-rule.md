---
type: issue
status: open
severity: blocker
tags: [issue, database, render, runtime, wave/context-fixes]
---

# Preserve classified run refusals through transaction wrappers

## Problem

Concurrent authored-source previews correctly meet the run transaction's
one-open-run fence, but `seon.db/transact!` can return
`#:seon.cluster.run{:id nil}` instead of the classified
`:seon.cluster.run/refused` value. `seon.cluster.agent/submit-source!` then
treats that unclassified map as success and its output instrumentation raises a
core fault because the required run id is nil.

## Live evidence

The stopped-web `juniper-context` cluster recorded fault signature
`2636dc23ab33dc0ef78db5e49cc51e047bf33737aadd183429729720375988c8` at
2026-09-07 00:16:39 UTC. Its stored diagnostic says:

- function: `seon.cluster.agent/submit-source!`;
- arm: output;
- expected: `[:or :seon.cluster.agent/source-submission-result
  :seon.error/value]`;
- offending value: `#:seon.cluster.run{:id nil}`;
- cause: missing required key.

The immediately preceding writer log entry is
`:datahike/write-rejected {:kind :seon.cluster.run/refused, :cause "run
transition refused: agent-already-running"}`. The stack is
`seon.render.web/render-source-call` → `submit-source!` → instrumentation.
The render caller already classifies `::run/agent-already-running` as transient
and leaves it for a later database wake (`src/seon/render/web.clj:1602-1623`),
but it never receives that rule.

The virtual-thread-aware `tmp/juniper-render-threads.json` does not show a
source-preview deadlock. The render proc had faulted. One agent completion
backstop was parked on its ordinary completion channel, a query thread was
runnable, and prompt context acquisition was parked on its ordinary await.
The observed primary failure is refusal translation after legitimate run
contention.

## Cause boundary

`run/open-call` throws ex-info carrying `:seon.error/kind
:seon.cluster.run/refused` and `:seon.cluster.run/rule
:seon.cluster.run/agent-already-running` (`src/seon/cluster/run.clj:318-325,
438-449`). `seon.db/transact!` relies on `seon.error.refusal/refusal`, which
returns the deepest non-empty ex-data in the wrapper chain
(`src/seon/error/refusal.clj:4-17`; `src/seon/db.clj:2032-2075`). The live
return proves a deeper transaction wrapper can contain only a nil run-id map
below the classified transition cause. Selecting depth alone is therefore not
equivalent to selecting the transition refusal.

## Minimum fix

At the existing transaction boundary, prefer the deepest cause-chain ex-data
that carries `:seon.error/kind`; only when no classified entry exists should it
fall back to the deepest non-empty dependency data used for ordinary Datahike
classification. This preserves the actual `:seon.cluster.run/rule`, lets
`submit-source!` return its declared flat error, and activates the web owner's
existing transient-contention path. A submit-local nil-id fallback would lose
the rule and leave every other transaction caller exposed to the same wrapper
shape.

## Acceptance

- A transaction-function `agent-already-running` refusal returns the original
  flat error including `:seon.cluster.run/rule`.
- `submit-source!` satisfies its output contract under the same contention and
  returns the flat refusal rather than a nil run id.
- Two source candidates rendered from one older database value do not fault
  the render proc; the second remains pending until a database wake can observe
  the first run's terminal facts.
- Unknown and ordinary Datahike failures retain their present classification.

## Implemented evidence

The shared `seon.error.refusal/refusal` cause-chain reader now returns the
deepest classified ex-data when any cause carries `:seon.error/kind`, and falls
back to the previous deepest non-empty behavior when none does. This keeps the
fix at the one cause-reading seam used by `seon.db/transact!` rather than adding
a database-local or submission-local classifier.

The focused regression first constructs the observed wrapper ordering: an
outer classified `:seon.cluster.run/refused` with an inner
`#:seon.cluster.run{:id nil}` wrapper. It then opens a real run and attempts a
second open for the same agent through `db/transact!`; the result retains both
`:seon.error/kind :seon.cluster.run/refused` and
`:seon.cluster.run/rule :seon.cluster.run/agent-already-running`. The focused
`seon.db-test` gate passed 38 tests and 320 assertions with zero failures or
errors. A controlled live web/source-submission run remains the final proof
that the transient caller receives this value in the original process.


2026-09-09 scratch faults-render seed re-observed a classified
`agent-already-running` refusal and `supervision-not-committed` fault while
provider calls were disabled. This does not prove the wrapper defect recurred;
it records the remaining supervision boundary without attributing its cause.
