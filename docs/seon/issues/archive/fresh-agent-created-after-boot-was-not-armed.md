---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, flow, runtime]
---

# Fresh agent created after boot was not armed

## Problem

A fresh agent created in a running cluster remained absent from the armed
routing set and never started its already-open generated bootstrap run until
`seon.cluster.agent/arm!` was called explicitly.

## Evidence

In isolated cluster `evolving-session` at commit `16f022fc9`,
`seon.cluster/ensure-entity!` created `explorer2` and its bootstrap run. After
more than seven seconds the run still had one form and zero receipts. The
process-local routing value contained only `root` and `explorer`; `explorer2`
was absent. Calling the ordinary `arm!` function with the cluster handle and
routing value added `explorer2`, after which form zero settled.

The observation occurred after process-local changes to two evaluation Vars,
but neither changed agent creation, the wake listener, the armer proc, or the
routing atom. Cause remains unattributed.

## Owner

The cluster armer path owns `(agents in facts) - (armed ids)` and the
listen-before-derive conservation law. Diagnosis must inspect its Flow report
and error channels rather than infer a missed listener event.

## Scheduling note — 2026-08-12

Skipped by the evolving-session defect-clear wave because the filed
reproduction does not yet settle an isolated armer fix. Current
`seon.cluster.agent/armer-step` synchronously waits for the first non-root
agent's generated bootstrap run to close before it can consume later arm
wakes. Moving that supervision transition without losing its durable trigger
crosses the held `loop.clj`/`run.clj` generated-call-transition owner and needs
a design ruling; a second listener or background thread is not an admissible
workaround.

## Diagnosis and implementation — 2026-08-13

The focused properties exposed two independent defects at the same lifecycle
seam.

First, both test fixtures called `seon.cluster.wake/route!` without its
required `:seon.render.web/interest` value. The listener catches that nil
dereference at `src/seon/cluster/wake.clj:203-251` and sends a fault instead of
routing an agent wake. The virtual-thread-aware dump
`tmp/opening/n-agent-parallel-before-fix-threads.json` shows the main test
thread parked in `await-database-state!` at the then-current
`agent_test.clj:234/474`; `tmp/opening/wake-routing-before-fix-threads.json`
shows the routing property parked on `armed-event` at the then-current
`agent_test.clj:1452`. In both dumps the Flow procs are waiting for input at
the pinned core.async `flow/impl.clj:295`, not consuming compute in a retry
loop. The fixtures now supply the required interest value.

Second, the production armer really did synchronously dereference a promise
until the first non-root agent's generated opening closed. A bare agent-row
fixture could never satisfy that transition, and in production the blocked
transform could not consume later creation wakes. `armer-step` now arms every
missing graph, derives the supervision transition once from the current
database value, and returns. Agent creation already wakes the armer; every run
closure now also offers a coalescing armer wake, so supervision is retried only
when a relevant fact may have changed. No listener, background thread,
timeout, or polling loop was added.
The owned source and property changes are committed as `de66c1e4a`.

`wake-routing-conservation-property` now creates agents through
`cluster/ensure-entity-call`, including the generated bootstrap run, rather
than inserting a shape the running system cannot create. Its generated entry
source is stubbed to a completed generation so the property measures routing
and arming rather than model behavior.

Post-fix execution is not yet recorded. A foreign in-flight edit made the
protected `src/seon/render/web.clj` unreadable at line 377 (uneven map literal),
so namespace loading fails before the focused properties or integration gate
can run. Per the shared-tree stop rule, this issue remains open until the
generated-agent properties and live creation proof can be rerun against a
coherent tree.

## Verification — 2026-08-13

The first focused rerun proved the original N-agent property completed, then
retained a new worker dump when the routing property still waited. The dump at
`tmp/opening/wake-routing-post-fix-worker-71261.json` showed the worker parked
at the then-current `agent_test.clj:1533` while every Flow proc waited for
input. A one-operation reduction named two stale fixture assumptions:

- generated-agent creation requires the production process and cluster facts;
  without them Datahike loudly refused the transaction before an agent could
  exist; and
- every generated agent adds one answered bootstrap trigger, so the old
  message-only answer count could never reach quiescence.

The property fixture now declares those facts and counts exactly one generated
bootstrap answer per created agent. Those changes are commits `3227436ea` and
`f68735799`. A direct one-agent trial returned all four conservation
invariants true.

The final `bin/test seon.cluster.agent-test` run completed 18 tests and 176
assertions with zero failures and zero errors. In particular,
`n-agent-parallel-turns-property` completed in 65.8 seconds and
`wake-routing-conservation-property` completed in 143.1 seconds. The opening
probe independently observed both root and fresh-agent bootstrap runs closed
with one form and one receipt.

The subsequent `bin/test --all` did not wedge, but its platform tier stopped
with one foreign failure in
`seon.test-support-test/explicit-synthetic-schema-rows-extend-only-that-database`:
71 tests, 384 assertions, one failure, zero errors; the 1,125-test bulk tier
did not run. The failure owner is `test/seon/test_support.clj`, whose
`file-store-markers` helper treats `seon.db/q`'s flat invalid-read error for an
uninstalled synthetic attribute as query rows. This issue remains open because
the owner required all three gates to pass before closure.

## Acceptance

- Creating an agent after boot arms it without a direct `arm!` call.
- The committed bootstrap run receives its first receipt.
- A listener/armer failure is committed or reported with enough evidence to
  name the failed transition.

## Closure — 2026-08-13

Resolved by `de66c1e4a`: `armer-step` now performs one fact-derived transition per wake and returns, with run closure supplying a coalescing armer wake (the Flow event-driven model) — the synchronous wait on the first generated opening is gone. Proven by the post-fix opening probe (fresh `probe-agent` created after boot, generated opening closed in seconds) and the green focused agent suite (18/176/0).
