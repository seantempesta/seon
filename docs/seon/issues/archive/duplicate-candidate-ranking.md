---
type: issue
status: resolved
severity: cleanup
tags: [issue, agent, database]
---

# Candidate ranking mechanics may be duplicated

## Problem

Retrieval and repair appear to maintain separate candidate-ranking mechanics,
which risks inconsistent ordering and repeated computation.

## Evidence

The archived dual-path audit's C53 row identifies distinct recall bands but
does not prove whether their distance and ranking mechanics are genuinely
different policies.

## Owner

The database-derived candidate ranking function used by retrieval and repair.

## Acceptance

Source and live-result comparison either consolidate distance/ranking into one
pure function with policy data as input, or document and test the two genuinely
different semantics without duplicated mechanics.

## Resolution

Resolved by `seon.repair.candidates`, the one ranking, tier, and winner owner
used by eval, worker eval, and `my.plan`. `seon.repair-candidates-test` passed
in the focused four-namespace checkpoint on 2026-07-14 (29 tests/169
assertions total).
