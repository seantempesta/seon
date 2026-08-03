---
type: issue
status: resolved
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

## Root cause

The completion channel was not missing. A virtual-thread-aware dump from the
recurring failure at `bd4494239` showed the agent turn still active in
`seon.render/acquire-context!`, while the main thread waited in
`await-turn-completion!`. Cluster teardown had stopped the combined armer and
render graph before disarming agent graphs. The fault-generated follow-up turn
therefore requested context after the only render proc had stopped, so that
turn could never finish and publish completion.

The earlier platform-thread-only attribution omitted the active virtual turn
and was incorrect. Neither the guarded-kernel merge nor the work launcher
caused this failure.

## Acceptance

- Disarm never waits unboundedly on a turn: completion is awaited as an
  observable event with a stated terminal outcome for "this turn cannot
  complete", not a bare promise deref.
- The backstop, if one survives, is loud — its firing is a reported bug, never
  the ordinary path (`plan/README.md` timeout law).
- `seon.cluster.armed-test` runs to completion in the recurring gate, and one
  regression covers stopping a cluster whose turn is guaranteed not to settle.

## Resolution

Cluster teardown now unregisters database wakes, sends one quiescence request
through the armer's existing input, closes that input, and waits for the
observable acknowledgement. That establishes that every earlier arm wake has
settled while the render proc remains live. Agent graphs then disarm and join
their active turns; only afterward does the combined cluster graph stop.

The original focused command first reproduced the failure and exited 124 at
the suite's 300-second liveness backstop. After the repair, the same
`bin/test seon.cluster.armed-test` command completed 6 tests and 53 assertions
with 0 failures and 0 errors. The formerly wedged
`an-escaped-throwable-becomes-a-fact-and-a-message` case completed in about 28
seconds and the namespace continued through all later recovery cases.

## Provenance

Found by the guarded-kernel-merge lane while widening its gate to the
consumers of the evaluation error face; resolved by the wave-close gate.
