---
type: issue
status: open
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
