---
type: issue
status: resolved
severity: friction
tags: [issue, database, schema, config]
---

# Reconcile empty cardinality-many values without metadata-only transactions

## Problem

The exact desired-state compiler treats a desired map containing an empty
cardinality-many collection as attribute presence. Datahike cannot store an
empty many-valued datom, so the domain state remains absent and every later
reconcile emits the same ineffective map addition plus transaction metadata.

## Evidence

The first live-config convergence test repeatedly compiled
`{:seon.config/id "cluster" :seon.agent.web/allowed-domains []}`. Database
listener output contained only `:db/txInstant`, `:seon.db/user`, and
`:seon.db/process`; nevertheless `seon.state/reconcile!` returned
`changed: true` and one operation on every identical apply.

## Owner

The database-form canonicalization in `seon.state` before its strict
presence-sensitive exact comparison.

## Acceptance

- Empty cardinality-many desired values canonicalize to database attribute
  absence before compilation.
- Presence remains significant for every value Datahike can actually store.
- A second identical reconcile submits no transaction and leaves the basis
  unchanged.

## Resolution

Resolved by `2f348806`. The compiler removes only impossible empty
cardinality-many presences before exact comparison, retaining attribute
presence as signal for every storable value. `seon.state-test` passed 7
tests/35 assertions, including a basis-stable empty-many convergence proof;
the live config apply then returned zero operations on the next identical
manifest.
