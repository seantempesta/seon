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

Four open blockers/frictions span 2026-08-05 through 2026-08-10:
[[a-mid-stream-provider-disconnect-discards-the-whole-turn]],
[[a-runs-last-form-can-close-without-a-receipt]],
[[background-binary-settlement-does-not-publish-required-event]], and
[[interrupted-blob-staging-leaves-no-observable-artifact]].

The archive repeats terminal loss on 2026-08-08 and 2026-08-10 in
[[archive/recovery-closes-an-interrupted-run-without-marking-it]],
[[archive/an-interrupted-my-shell-run-orphans-its-child-and-its-receipt]],
[[archive/an-unreadable-reply-closes-a-run-with-no-forms-and-no-trace]], and
[[archive/dropped-core-fault-count-is-not-durable]], with the 2026-08-11
reconstruction recurrence [[archive/agent-definition-restore-reexecutes-authored-source]].

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
