---
type: issue
status: open
severity: friction
tags: [issue, adoption, schema, sci]
---

# Re-admitting the fixture schemas after adoption refuses the environment

## Problem

The already-seeded scratch cluster adopted current source successfully,
but re-running the fixture's schema source returned evaluation errors.

## Evidence

2026-09-10 01:33 UTC, `tmp/loop-live-root`, cluster `loop-live`, after
adoption `6aa20891-bbaf-538d-8d97-0e51e5ead95a`: the shared schema
submission reported both
`Schema population refused :example/order-row (unresolved-reference).`
and `seon.env/advance-projection! violated its contract (invalid-input):
must hold one immutable replacement environment`.
The loop persisted the evaluation errors and closed; the fixture then
refused installation. A fresh scratch fork of that same publication seeded
successfully. This is not evidence of the ordinary-turn stall.

## Owner

SCI schema admission and development-adoption environment reconciliation.
The owning SCI file was concurrently held by context-cookbook; the loop
lane did not edit it or operate another lane's session.

## Acceptance

Seed the shared Juniper fixture, adopt in place, then re-admit its exact
schema source through the same real graph. Verify successful evaluations
and an environment satisfying the declared `advance-projection!` contract.
