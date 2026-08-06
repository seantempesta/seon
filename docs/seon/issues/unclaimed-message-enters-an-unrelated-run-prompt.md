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

## Acceptance

- A run's prompt clearly identifies and answers its recorded trigger.
- A later unclaimed inbound message cannot affect that run's plan.
- Two messages committed around run opening produce two causally correct
  outcomes under an event-controlled regression, with no message lost or
  charged to the wrong run.
