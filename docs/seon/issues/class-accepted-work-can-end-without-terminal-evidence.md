---
type: issue
status: open
severity: blocker
tags: [issue, agent, runtime, effect, class/n10, class-kill, wave/class-kill-queue]
---

# Make accepted work require terminal evidence

## Problem

A request, form, stream, or binary operation can be accepted and later end
without one durable terminal receipt/fact. Recovery and callers then cannot
distinguish completion, refusal, interruption, partial response, or lost work.

## Evidence

Current open members carry `class/n10` and are derived with
`bin/issues-index --class class/n10`.

## Owner

The accepted-work constructors and their one terminal transaction/state
transition, including child and stream settlement.

## Acceptance

- The accepted-work value contains its durable identity and terminal
  publisher; no close/interrupt transition exists without a receipt/fact.
- The terminal transaction records exactly one of completion, refusal,
  interruption, or partial failure and settles owned child cancellation.
- Recovery consumes the same states and never re-executes effects to infer the
  result.
