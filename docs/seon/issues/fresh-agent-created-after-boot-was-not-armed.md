---
type: issue
status: open
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

## Acceptance

- Creating an agent after boot arms it without a direct `arm!` call.
- The committed bootstrap run receives its first receipt.
- A listener/armer failure is committed or reported with enough evidence to
  name the failed transition.
