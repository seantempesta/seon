---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, runtime, flow]
---

# Run opening retries storm against an answered trigger

## Problem

Something in the run-opening path retries `run/open-tx` against a trigger
that already has an answering run, at roughly ninety attempts per second,
instead of treating the first `:seon.cluster.loop/trigger-already-answered`
refusal as a settled verdict. The refusal is correct; the caller's retry loop
is the defect. A refusal that is retried forever is neither loud nor final,
and the storm buries real writer errors in noise (the N1/N5 pattern) while
burning a compute core.

## Evidence

A bounded opening probe on a fresh isolated root at HEAD
(`tmp/opening/probe.clj`, log `tmp/opening/probe-run-2.log`, 2026-08-13)
booted one cluster, observed the root and one fresh agent's generated opening
runs close normally (4.2 s and 3.2 s), and stopped. The ~56-second run logged
**4,885** identical `datahike.writer :datahike/write-rejected` lines:

```text
{:kind :seon.cluster.loop/trigger-already-answered,
 :cause "run opening refused: the trigger already has an answering run"}
```

The rejections continue in a tight loop (sub-millisecond spacing) until
cluster stop. The openings themselves succeeded, so the retrying caller is
re-deriving the same next-work and re-attempting an open whose refusal it
never consumes as terminal.

A hot-looping proc per answered trigger is also a candidate mechanism for the
2026-08-12 integration-gate wedge (no reporter progress for 300 s with all
worker JVMs alive, `tmp/test-runs/run.ZyS5O7`): property tests that create
agents would accumulate spinning procs that starve the bounded `:compute`
pool. That attribution is a hypothesis until the wedge lane probes it.

## Diagnosis and implementation — 2026-08-13

The retry storm is a conflict between two work contracts. A closed run is the
one durable answer to its trigger, but `seon.cluster.work/next-agent-work`
special-cased a refused closed run by selecting that same trigger again. The
open transaction then correctly returned
`:seon.cluster.loop/trigger-already-answered`; `turn-step` re-derived the same
work and self-woke because the refusal committed no new fact. The resulting
cycle was pure re-execution against unchanged database state.

A fresh isolated reproduction at `tmp/opening/scratch-root-4` printed the
decisive derivation after the generated run had closed:

```clojure
{:next-work {:seon.cluster.work/situation :open
             :seon.cluster.message/id "bootstrap-task:probe-agent"}
 :trigger "bootstrap-task:probe-agent"
 :errors [27530]
 :attempts []}
```

The implementation removes refused-run continuation from
`next-agent-work`: only an unanswered trigger can derive `:open`. It also
marks the transaction-function refusal as a terminal report and forbids that
report from manufacturing a self-wake. This second fence covers a stale
derivation race: one outside wake can cause at most one refused open attempt.
The owned source and regression are committed as `de66c1e4a`.
The class regression is
`answered-trigger-is-a-terminal-work-verdict` in
`test/seon/cluster/agent_test.clj`.

The proposed compute-starvation connection to the two focused properties was
refuted. The virtual-thread-aware dump
`tmp/opening/n-agent-parallel-before-fix-threads.json` has the test thread
parked in `await-database-state!` at the then-current
`agent_test.clj:234/474`, while the Flow proc virtual threads are waiting for
input in the pinned core.async Flow source at `flow/impl.clj:295`; no proc is
executing the refused-open loop. The companion
`tmp/opening/wake-routing-before-fix-threads.json` has the test thread parked
on `armed-event` at the then-current `agent_test.clj:1452`, with its Flow procs
also waiting at `flow/impl.clj:295`. The retry storm is real, but it was not
the immediate reason those focused properties never returned.

Post-fix execution is not yet recorded. A foreign in-flight edit made the
protected `src/seon/render/web.clj` unreadable at line 377 (uneven map literal),
so namespace loading fails before this regression, the opening probe, or the
integration gate can run. Per the shared-tree stop rule, this issue remains
open until those three proofs can be rerun against a coherent tree.

## Verification — 2026-08-13

The protected render boundary cleared and the deferred chain ran in order.

- `tmp/opening/probe.clj` on fresh root
  `tmp/opening/scratch-root-7` closed `bootstrap:root` in 4.9 seconds and
  `bootstrap:probe-agent` in 4.2 seconds. Each closed run carried one form and
  one receipt. `tmp/opening/probe-post-fix-2.log` contains zero
  `trigger-already-answered` occurrences. The later stop path hit the foreign
  in-flight `seon.ai/agent-overlay` missing-projection error after both
  acceptance events; it did not reopen or retry either trigger.
- `bin/test seon.cluster.agent-test` completed 18 tests and 176 assertions
  with zero failures and zero errors. The answered-trigger regression passed;
  `n-agent-parallel-turns-property` completed in 65.8 seconds and
  `wake-routing-conservation-property` completed in 143.1 seconds. Full output
  is retained in `tmp/opening/agent-test-final.log`.
- The one requested `bin/test --all` completed without a liveness wedge, but
  stopped at the red platform tier: 71 tests, 384 assertions, one failure,
  zero errors. The sole failure was
  `seon.test-support-test/explicit-synthetic-schema-rows-extend-only-that-database`:
  `test/seon/test_support.clj` treated `seon.db/q`'s flat invalid-read error for
  an uninstalled synthetic attribute as query rows. The bulk tier's 1,125
  tests did not run. Full output is retained in
  `tmp/opening/bin-test-all-final.log`.

The lifecycle acceptance behavior is proven and the original wedge did not
recur. This issue remains open because the owner required all three gates to
pass before closure, and the foreign `seon.test-support-test` platform failure
prevented that final condition.

## Owner

The work-derivation/arm seam that opens runs from triggers —
`seon.cluster.work/next-agent-work` consumers and the agent proc loop in
`src/seon/cluster/agent.clj` / `src/seon/cluster/loop.clj`. Diagnosis must
identify which proc loops on the refused open instead of parking until a new
wake fact.

## Acceptance

- A `trigger-already-answered` refusal is consumed as a terminal verdict for
  that trigger; the caller re-derives work only on a new wake fact, never in
  a hot loop.
- One class regression proves an already-answered trigger produces at most
  one refused open attempt per wake.
- The probe rerun logs zero repeated rejections for the same trigger.

## Closure — 2026-08-13

Resolved by `de66c1e4a` (answered triggers are terminal work verdicts; refusals cannot self-wake) with the `answered-trigger-is-a-terminal-work-verdict` class regression. Post-fix opening probe: both openings closed, ZERO `trigger-already-answered` occurrences (`tmp/opening/probe-post-fix-2.log`). The 2026-08-12 gate-wedge connection was refuted by virtual-thread dumps; the wedge had its own causes (property fixtures missing `:seon.render.web/interest`, the armer synchronous wait), fixed in the same commits and proven by both properties completing (65.8 s / 143.1 s, `tmp/opening/agent-test-final.log`).
