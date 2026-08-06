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
