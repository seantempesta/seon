---
type: issue
status: resolved
severity: blocker
tags: [issue, runtime, sci, wave/schema-codec-deletion]
---

# Make committed declaration deletion install exactly

## Problem

Successful terminal transactions which delete a function, schema, or test are
followed by an SCI installation refusal claiming the deleted declaration is
still present.

## Evidence

At HEAD on 2026-08-29, explicit `bin/test seon.cluster.turn-test` reproducibly
errored with `Deleted declaration is still present after commit.` from
`seon.sci.eval/install-row!` in four tests:
`ns-unmap-retracts-the-owned-function-after-the-terminal-commit`,
`qualified-dynamic-ns-unmap-is-durable-in-a-fresh-context`,
`runtime-schema-unregister-removes-one-unused-global-schema`, and
`runtime-tests-install-run-redefine-and-delete-exactly`. None exercises the
launcher or caps fixtures changed by the effective-config census sweep.

## Owner

The post-commit declaration installation path in `seon.sci.eval`.

## Acceptance

Committed declaration deletions disappear from the fresh SCI context and all
four named tests pass without a post-commit refusal.

## Resolution (2026-08-29)

Fixed by `d16466b1d` (graph-consequences lane): `install-row!` threw
whenever a stable identity row survived deletion — directly
contradicting ruling 47 (identity rows never retract; deletion
retracts definition facts). It now verifies definition-fact absence.
All four named turn tests green in the lane's five-namespace gate
(149 tests / 901 assertions).

## Recurrence probe — 2026-09-16

The old tombstone repair is present at `a55bdfc80`, but its comparison passed
nil to `changed-attributes` when a deletion named an identity that never
existed (for example the test sibling of a function). The turn-test-reds slice
branches on the actual pulled entity at `remaining-definition-facts`, so only
existing definition maps are compared. Four real deletion regressions pass
19 assertions both before source editing and after isolated development
adoption, with fresh canonical bases and armed contracts.

The separate schema-attribute removal observable is not claimed repaired;
it is tracked in
[the residual](../runtime-schema-unregister-retains-installed-attribute.md).
Exact commits and recorded runs are in
[the landing](../../../prds/context-generation/research/turn-test-reds-2026-09-16.md).

## Superseding deletion ruling — 2026-09-17

Program-facts PRD section 1f G1–G3 supersedes ruling 47: deletion retracts
the entity, and history retains the former definition. The old `row-tx`
identity-only replacement now correctly fails required admission validation.
The writer emits `retractEntity`; all four named regressions assert entity
absence instead of tombstones. Schema attribute removal and fresh-context
absence remain required. See [the S3 residue evidence](../../../prds/steward-platform/research/acquisition-s3-residue-2026-09-17.md).
