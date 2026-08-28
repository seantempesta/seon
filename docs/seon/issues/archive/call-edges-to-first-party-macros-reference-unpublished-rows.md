---
type: issue
status: resolved
severity: blocker
tags: [issue, program-graph, indexing, wave/program-graph-indexing]
---

# Call edges to first-party macros reference unpublished rows

## Problem

Transactions carrying `:seon.fn/calls` edges to `seon.bootstrap/help`
are rejected by the writer: `seon.bootstrap/help` is a `defmacro`, and
publication mints no `:seon.fn/fn` row for it, so the lookup ref
`[:seon.fn/sym "seon.bootstrap/help"]` resolves to nothing and Datahike
rejects the WHOLE transaction — every fact riding it is lost, and the
loop retries into the same wall.

## Evidence

During `seon.cluster.cohost-boot-test` runs at `2540e6c8f` (2026-08-28),
both cluster boots logged repeatedly:

```text
:error datahike.writer :datahike/write-rejected {:kind :entity-id/missing,
  :cause "Nothing found for entity id [:seon.fn/sym \"seon.bootstrap/help\"]"}
```

The opening's `(help)` form is analyzed into a call edge; wave A's
core-call widening (`4b4d73517`, ruling 42b) mints name-only rows for
core/library vars, but first-party MACROS fall between the two
populations: not an indexed `defn`, not a core var.

## Owner

The publication seam (`src/seon/fn.clj` twin analysis sites plus
`assert-clean-analysis!`): the 42b name-only-row construction should
cover any callable var the analyzer can emit as a call target,
macros included. Determine what facts are currently being LOST with
each rejected transaction — a rejected write that only logs is the
absence-as-health class.

## Acceptance

A cohost-boot (or any fresh-cluster) run logs zero
`:datahike/write-rejected` events for `:seon.fn/sym` lookup refs, and a
regression proves a call edge to a first-party macro lands as a fact.

## Resolution (2026-08-28)

Root cause was wider than macros: ANY run-form call target without a
program row — first-party macro, unindexed core var — was settled as a
lookup ref, which resolves against the before-db and rejected the
whole settlement transaction. `seon.fn/analyze-form` now rewrites
missing targets to string tempids resolved by ruling-42b name-only
companion rows riding the same transaction (`portable-calls`); the
class regression
`seon.cluster.run-test/settlement-mints-rows-for-unindexed-call-targets`
proves the settlement commits, the row is minted, and the call edge
lands. Cohost boot re-verified with zero writer rejections.
