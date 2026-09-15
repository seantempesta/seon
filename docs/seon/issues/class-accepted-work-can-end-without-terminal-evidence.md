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

## Re-verified at HEAD (2026-09-15)

UNVERIFIABLE-WITHOUT-GATE (`seon.effect-test`, `seon.background-blob-test`, `seon.turn-test`). Audited HEAD `7e35df213` retains accepted background work at `src/seon/effect.clj:647-693`, with terminal callback `settle-background-terminal!` at `:488-503`, and source/outcome in one evaluation entity (`src/seon/turn.clj:498`). The two-family and stream-truncation members in this slice are resolved; neither proves the universal terminal-work claim. The attempted background regression instead failed at setup before submitting work (see `background-binary-settlement-does-not-publish-required-event.md` for exact command and output). Need actual cancellation, failure, and background completion assertions on the canonical fixture. The aggregate note is not a new confirmed reproduction; keep blocker pending terminal-boundary evidence.

surface: turn-loop
