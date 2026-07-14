---
type: issue
status: open
severity: friction
tags: [issue, agent, architecture, flow]
---

# Transcript decay does not bound total context

## Problem

The original transcript decayed each eval result body to 200 estimated tokens
at turn offset five, but an empty tier schedule retained every eval event
forever. The transcript therefore grew linearly with eval count despite every
old result reaching a stable per-item size. Tier accounting also charged only
`:seon.eval/result-edn`; form source, narration, and error rendering bypassed
the claimed token budget. The mechanical bound is fixed; Inspect schedule
comparison remains before this issue can close.

## Evidence

The isolated ACME REPL on 2026-07-14 returned the stored decay levels
`0→16384`, `2→1500`, `5→200` and `:tiers []`. `clip-events-by-tiers` returns
all events unchanged when tiers are empty. When tiers are present it estimates
only the result EDN while retaining or evicting the whole event. The
`transcript-block` docstring still says the sliding window "lands later" even
though decay/tier code now runs, which obscures the active behavior.

The repair derives a 50-turn window from turn facts and evicts complete 25-turn
prefixes. The retained settled chunk shares an 8,192-token cap charged against
the complete rendered event; the append-only current chunk remains intact.
Result decay is `0→4096`, `2→1024`, `5→512`. Pure tests pin the 49/50/74/75
rotation points and prove that source plus result text, not result EDN alone,
determines retention. The rebuilt ACME database reports the exact policy.

## Owner

`seon.agent.ctx.transcript` and the database-owned transcript block policy.
Agent-facing functions must bound their own values first; transcript policy is
the final prompt budget, not a substitute for bounded tool/error envelopes.

## Acceptance

- Recent raw events decay through measured byte-stable age bands; the old
  plateau retains enough identity, outcome, diagnostic detail, and a drill path.
- Older eval events share a fixed total rendered-token budget, so a long run
  approaches a bounded transcript size instead of accumulating permanent
  per-eval stubs.
- Budget accounting covers the actual rendered event: source, narration,
  result, and error envelope.
- Eviction omits whole events without rewriting retained bytes or synthesizing
  a compacted conversation. Complete facts remain queryable in the database or
  blob archive.
- Inspect and live prompt exports compare candidate schedules, including the
  current schedule and a 512-token old-result plateau, for outcome, repeated
  retrieval, prompt size, and cache-prefix stability.
