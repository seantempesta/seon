---
type: issue
status: open
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

The walk bound the current run id while acquiring prompt context, but dropped
that identity before invoking the schema-declared agent transcript renderer.
The transcript therefore preserved the correct arrival order while rendering
the recorded trigger and every newer unclaimed inbound message with the same
unqualified sentence shape. The run ref remained correct in the database; the
AI projection erased its custody meaning.

## Resolution

The walk now carries the current run id through renderer-call evidence.
`seon.context/message-custody` derives each inbound message's relationship to
that run exclusively from database refs:

- the run's recorded trigger renders as the current run instruction;
- an inbound message with no run trigger ref renders as pending, explicitly
  not this run's instruction; and
- claimed or non-inbound messages remain ordinary history.

The transcript still sorts every entry by its durable arrival facts, so a
mid-turn message interleaves honestly without acquiring the wrong custody.
The event-controlled regression opens a run on message A, commits message B
between open and call, and asserts both the arrival-visible pending sentence
and the unchanged trigger ref.

## Acceptance

- A run's prompt clearly identifies and answers its recorded trigger.
- A later unclaimed inbound message cannot affect that run's plan.
- Two messages committed around run opening produce two causally correct
  outcomes under an event-controlled regression, with no message lost or
  charged to the wrong run.
