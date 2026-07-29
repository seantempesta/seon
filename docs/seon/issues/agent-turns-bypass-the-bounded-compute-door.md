---
type: issue
status: open
severity: blocker
tags: [issue, flow, agent, sci, architecture]
---

# Route agent evals through the bounded compute owner

## Problem

The per-agent turn proc executes SCI evaluation inline on its `:io` transform.
The bounded compute launcher, process-root executors, and their backpressure are
not on the production agent path even though the agent namespace says they are.

## Evidence

`src/seon/cluster/agent.clj:164-224` calls
`seon.cluster.loop/turn` inline, and `graph-definition` pins that proc to
`:io` at lines 261-265. The resume branch in
`src/seon/cluster/loop.cljc:718-756` invokes `seon.sci.eval/evaluate`
inline.

`rg 'submit!!' src` returns only the definition in `src/seon/flow.clj` and the
namespace-docstring claim in `src/seon/cluster/agent.clj`; there is no
production caller. Similarly, `seon.cluster/root-executors` creates and
publishes a process-root pair, but no cluster or agent graph supplies those
executors to `create-flow`.

A `clojure -M:dev` probe datafying the turn launcher returned
`{:step seon.cluster.agent/turn-step, :desc {... :workload :io ...}}`.
The dependency contract at
`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:200-202`
says `:io` must not do extended computation.

The tests cover the pieces separately: `test/seon/flow_test.clj` drives the
unreachable launcher; `test/seon/cluster/boot_test.clj` proves only executor
object identity; agent tests drive the turn graph without proving a compute
handoff or concurrency bound.

The existing `flow-submit-waits-forever-before-time-limit.md` and
`submit-awaits-started-with-no-bound.md` notes concern a defect inside the
orphaned launcher. They do not cover this production-bypass root cause.

## Owner

The workload seam between `seon.cluster.agent/turn-step`,
`seon.cluster.loop/turn`, `seon.sci.eval/evaluate`, and the single
process-root compute owner.

## Acceptance

A production-composed agent-graph test uses latches to start more agent evals
than configured compute capacity and proves:

- provider/database blocking remains on `:io`;
- SCI evaluation runs through exactly one bounded `:compute` owner;
- no more than configured compute concurrency evaluates at once;
- completion remains a flat value at the turn boundary; and
- the process-root executor data is either consumed by every relevant graph or
  deleted in favor of the one actual owner.

The dead launcher path is deleted or becomes the production owner, its
duplicate issue notes are reconciled, and the agent namespace docstring
describes the observed call path.

## Triage 2026-07-29

**PRESSING — confirmed spine blocker.** The bounded-compute fix wave also owns
[[flow-submit-waits-forever-before-time-limit]] and
[[submit-awaits-started-with-no-bound]]: both are startup-boundary failures in
the currently unused launcher that must be corrected as it becomes the
production agent-eval path.
