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

## Acceptance

- Creating an agent after boot arms it without a direct `arm!` call.
- The committed bootstrap run receives its first receipt.
- A listener/armer failure is committed or reported with enough evidence to
  name the failed transition.
