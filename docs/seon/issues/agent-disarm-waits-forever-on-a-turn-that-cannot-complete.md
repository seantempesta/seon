---
type: issue
status: open
severity: blocker
tags: [issue, flow, runtime, testing]
---

# `disarm!` waits forever when a turn cannot complete, wedging `bin/test`

## Problem

`seon.cluster/stop!` -> `disarm-agents!` -> `seon.cluster.agent/disarm!` ->
`await-turn-completion!` blocks on an unbounded promise deref. When the turn it
waits for can never settle, the whole suite process wedges until the liveness
backstop kills it at 300 s.

This is the open edge ruling #51 already names — "agent graph stop can wait
forever on turn completion" (`plan/README.md:1894-1896`) — now observed
failing a recurring gate rather than only reasoned about.

## Evidence

`seon.cluster.armed-test/an-escaped-throwable-becomes-a-fact-and-a-message`
wedges reproducibly, measured 2026-08-03 at `6920320e3`, both inside an
eight-namespace selection and running alone:

```text
bin/test: forcibly stopping suite descendants and exiting 124
"main" id=3 state=WAITING waiting-on=java.util.concurrent.CountDownLatch$Sync
  at [clojure.core$promise$reify__8625 deref core.clj 7261]
  at [seon.cluster.agent$await_turn_completion_BANG_ agent.clj 460]
  at [seon.cluster.agent$disarm_BANG_ agent.clj 527]
  at [seon.cluster$disarm_agents_BANG_ cluster.clj 1539]
  at [seon.cluster$stop_BANG_ cluster.clj 1890]
  at [seon.cluster.armed_test$with_cluster armed_test.clj 84]
```

The test redefines `work/next-agent-work` to throw for the whole body, so once
the fault is committed the turn can never reach completion and `with-cluster`'s
`finally` never returns. The test's own assertions pass first: the fault fact,
its agent tagging, its signature, and root's message are all committed before
the wedge, so the fault path is healthy — only teardown is not.

## Not caused by the guarded-kernel merge

Checked while landing that merge (`04fe5f247`, `db0d78368`): the injected throw
replaces `work/next-agent-work`, which runs BEFORE any guarded evaluation, and
no `seon.sci.*` frame appears anywhere in the wedged dump. The kernel merge
changed only how the two guarded entrances arm and classify failures.

The likelier neighbour is the same day's work-launcher change
(`79700db1c`, "Add bounded background IO launcher arm", +249 lines in
`src/seon/flow.clj`), which owns exactly the control alts ruling #51 names as
this edge's other half. That is a lead, not a finding — it was not bisected,
because a shared checkout cannot be checked out to an older commit safely.

## Acceptance

- Disarm never waits unboundedly on a turn: completion is awaited as an
  observable event with a stated terminal outcome for "this turn cannot
  complete", not a bare promise deref.
- The backstop, if one survives, is loud — its firing is a reported bug, never
  the ordinary path (`plan/README.md` timeout law).
- `seon.cluster.armed-test` runs to completion in the recurring gate, and one
  regression covers stopping a cluster whose turn is guaranteed not to settle.

## Provenance

Found by the guarded-kernel-merge lane while widening its gate to the
consumers of the evaluation error face.
