---
type: issue
status: open
severity: blocker
tags: [issue, runtime, flow, agent, live-drive]
---

# Settle or refuse a frozen plan's first form

## Problem

A real root-agent run completed its DeepSeek attempt and froze two ordered
forms, but never created the first evaluation receipt. The run remains held by
the live process, `seon.cluster.work/next-agent-work` keeps returning
`:resume` at ordinal zero, and every later inbound message waits behind it.

## Evidence

The 2026-08-06 default-cluster live drive observed run
`f56667dc-a2ec-4f92-af47-e37cdb06535c`, opened at
`2026-08-06T17:26:19.882Z` and held by process
`52509-1786036914863`. Attempt zero finished with `finish_reason=stop`; the
model observation recorded 89,726 ms and the attempt recorded 51,635 tokens.
The durable plan has digest
`02cffcd10d8fd42f92db01468b5411c329861fb7ae56c347a247ef7d8254f59f`
and two source rows.

At database basis `536871034`, more than six minutes after plan freeze:

- the run still had no `:seon.cluster.run/closed-at`;
- the run had zero `:seon.cluster.eval/run` receipts;
- `next-agent-work` returned `{:seon.cluster.work/situation :resume,
  :seon.cluster.run.form/ordinal 0}`; and
- the root agent could not open a run for the live-drive inbound message.

No restart, resume, refork, or direct run mutation was attempted because the
drive was explicitly required to stop at a foreign in-flight boundary.

## Cause

The independent observer narrowed the frozen seam to turn-fork construction,
not plan publication, work derivation, or wake delivery. Run eid `23687`
entered `:resume` correctly, then `seon.sci.eval/fork-for-turn` rehydrated
three atom desk rows that already carried
`:seon.def/unrestorable-reason` and no value/blob digest. The atom arm called
`seon.blob/get` with nil before the later unrestorable arm could handle those
rows. Core fault eid `23700` recorded that contract violation, but the turn
proc left the held run open with zero receipts.

The desk attribution and rehydration cause is owned separately by
[Skip unrestorable atom desk rows before blob rehydration](unrestorable-atom-desk-row-wedges-next-turn.md).
This issue owns the run-loop guarantee exposed by that fault: every failure
after an accepted plan and before receipt zero must settle a durable error and
release custody rather than silently retaining the run.

## Owner

The root agent's `seon.cluster.loop` Flow proc and its post-attempt
plan-freeze-to-first-evaluation transition.

## Acceptance

- A successful provider attempt that freezes a nonempty plan publishes the
  first observable evaluation transition without an external wake.
- Form zero either settles one terminal receipt or commits a bounded refusal;
  a live held run cannot remain indefinitely at zero receipts.
- A production-loop regression observes the transition through database
  events and proves a later inbound message is eventually claimable.
