---
type: issue
status: resolved
severity: blocker
tags: [issue, context, messaging, runtime, honesty, live-drive]
---

# Keep an unclaimed message out of an unrelated run's prompt

## Problem

A new human message can enter the context of an already-opening run whose
recorded trigger is a different system message. DeepSeek then answers the new
message under the wrong run identity while the new message itself remains
unclaimed.

## Evidence

The public HTTP boundary committed message `inbound-536871002-0` at
`2026-08-06T17:25:50.238Z`. It asked root to complete with a tagged live-drive
value. Run `f56667dc-a2ec-4f92-af47-e37cdb06535c` opened 29.6 seconds later,
but its recorded trigger is
`d05eafc5-35f3-4555-b131-0a80b5d06cc4-your-run`, the schedule-settlement
error message.

The exact durable capture for that run, at basis `536871009`, contains both
the schedule error and the later `LIVE-DRIVE-0806-A` message. DeepSeek froze
forms whose comments and completion value explicitly answer the live-drive
message. At basis `536871034`, the inbound message still had no run connected
through `:seon.cluster.run/trigger`.

This is not merely a display mismatch: the wrong causal run paid the provider
call and owns the resulting plan.

## Owner

`seon.cluster.prompt` and the run-opening context selection that turns one
recorded trigger into the exact prompt for that run.

## Cause

`call-turn` passed a fresh `@connection` database value to `prompt/prompt`
after the run had already opened. The walk therefore included facts committed
after the opening transaction, including a newer message awaiting its own run.
The stored trigger remained correct; the prompt violated the run's opening
database value.

## Resolution

The ruled R3 mechanism is `seon.cluster.run/opening-db`. It derives the run's
opening transaction from the `:seon.cluster.run/opened-at` datom and returns
Datahike's `as-of` database value at that transaction. The one prompt call site
uses that value. A message committed after the run opened is absent by
construction, with no post-opening visibility filter.

For messages already present in the opening database value,
`seon.context/message-custody` still derives each inbound message's
relationship to that run exclusively from database refs:

- the run's recorded trigger renders as the current run instruction;
- an inbound message with no run trigger ref renders as pending, explicitly
  not this run's instruction; and
- claimed or non-inbound messages remain ordinary history.

The transcript retains durable arrival order. A message already present at
opening but not claimed by its own run renders at that arrival position as
pending; a message arriving after opening belongs only to later work.

## Acceptance evidence

The event-controlled class regression opens run A, commits message B after
that opening, and asserts B is absent from A's paid-call prompt. After A
settles, it asserts `next-agent-work` derives `:open` for B and B's own
paid-call prompt contains B as the current run instruction. A direct fresh
database probe observed opening ids `["A"]` while the current database held
`["B" "A"]`. The existing pre-evaluation settlement regression remains
unchanged.

## Acceptance

- A run's prompt clearly identifies and answers its recorded trigger.
- A later unclaimed inbound message cannot affect that run's plan.
- Two messages committed around run opening produce two causally correct
  outcomes under an event-controlled regression, with no message lost or
  charged to the wrong run.

## REOPENED — 2026-08-08 live drive (recurrence, second half)

The first half holds: the run's prompt no longer contains a message committed
after opening. The SECOND half does not. Live on cluster `default` (pid 79576)
the drive committed two human messages through `POST /agent/root/message`,
both HTTP 204, both admitted and queryable:

- `inbound-536870994-0` at `2026-08-08T04:35:02Z`
- `inbound-536870997-0` at `2026-08-08T04:38:02Z`

Neither ever became a run's trigger. Every run on the cluster at
2026-08-08T04:42Z is triggered by a system error message or by nothing:

| run | opened | closed | trigger |
|---|---|---|---|
| `bootstrap:root` | 04:31:05 | 04:31:10 | (none) |
| `a7e24a23-…` | 04:31:13 | 04:39:47 | `maintenance-error/…/compact…-your-run` |
| `cf7cc2f1-…` | 04:39:47 | 04:39:47 | `maintenance-error/…/process-census…-your-run` |
| `20768b1f-…` | 04:41:32 | (open) | `db9b5b2a-…-your-run` |

Root is instead working through its own error backlog, and that backlog feeds
itself: the turn's contract violation committed error message
`db9b5b2a-…-your-run`, which opened run `20768b1f`. A human message queued
behind an error queue is never selected.

The acceptance clause "after A settles, `next-agent-work` derives `:open` for
B and B's own paid-call prompt contains B as the current run instruction" is
therefore NOT satisfied on the live path. A settles (`a7e24a23` closed at
04:39:47) and the next run selected another error message, not the waiting
human message.

Note the `opening-db` mechanism that closed the first half is also the direct
cause of two new blockers, because an as-of database value is not a total
input to the code that reads it:
[the walk refuses it](walk-refuses-an-as-of-database-value-and-empties-the-agent-context.md)
and the capture basis read it through the wrong reader (fixed in the same
drive, `src/seon/context.clj`).

### Added acceptance

- With an error-message backlog present, a newly committed human message is
  selected by `next-agent-work` before or fairly among the system messages;
  the live drive's exact sequence is the regression.
- A run's own failure message cannot starve the human queue.

## The recurrence was misattributed — 2026-08-08 re-drive

The reopening above is withdrawn on its own evidence. Selection was never
starving human messages.

The independent observer lane had already refuted the claim from the same
cluster: at all 84 samples every claimed message was claimed by exactly one
run, in strict eid order, with no fan-out, and `LIVE-DRIVE-0808-A` (eid 25372)
**was** claimed by a run of its own. The reopening read "the human message
never reached a prompt" as "the human message was never selected". Those are
different failures with different owners, and the true one was that no message
reached any prompt because the walk had collapsed the entire context to a
509-character error.

Confirmed directly after that defect was fixed. Two human messages submitted
through `POST /agent/root/message`, with an error backlog present on the
cluster:

| Message | Run | Opened |
|---|---|---|
| `inbound-536871134-0` (`LIVE-DRIVE-0808-C`) | `c9c653a5-…` | 05:36:37Z |
| `inbound-536871139-0` (`LIVE-DRIVE-0808-D`) | `d95c5c42-…` | 05:39:16Z |

Each opened its own run, with itself as the recorded trigger, and each reached
a real context (78,836 and 80,834 characters) and a settled reply. No fairness
change was made to `next-agent-work`, `wake`, or `message` — nothing in the
selection path was touched.

The second added acceptance clause — "a run's own failure message cannot
starve the human queue" — is owned by
[Stop a failed turn from waking itself through its own fault message](a-failed-turn-wakes-itself-through-its-own-fault-message.md),
where the self-feeding escalation was fixed at cause. It does not belong here.

This note therefore returns to its archived state: the original defect (a
message committed after a run opened entering that run's prompt) remains
fixed by `seon.cluster.run/opening-db`, and the first half of that mechanism
is now exercised by real turns rather than by an empty walk.
