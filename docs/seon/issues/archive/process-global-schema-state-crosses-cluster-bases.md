---
type: issue
status: resolved
severity: blocker
tags: [issue, schema, cluster, runtime]
---

# Scope schema projections to their cluster database values

## Problem

`seon.schema/!schema-state` combines a reloadable host-predicate cache with one
process-global candidate population and one process-global active schema
projection. A JVM may host sovereign clusters at different database bases, so
one active projection cannot be the authority for every cluster. Native callers
such as error classification read that global projection directly, while SCI
contexts correctly carry per-cluster projection state. Starting or instrumenting
one cluster can therefore change schema semantics observed by another.

The stable `seon-registry` facade compounds the problem: Malli's process-global
default dereferences the same global active forms, and `seon.instrument/apply!`
reactivates whatever candidate population module loading most recently
assembled.

## Evidence

- `src/seon/schema.clj:532-539` stores candidate forms, predicate functions, and
  the active database-derived projection in one `defonce` atom.
- `src/seon/schema.clj:589-595,623-666` makes both the candidate registry and
  Malli's stable default facade read that atom.
- `src/seon/schema.clj:2172-2185` publishes a projection process-wide;
  `:2308-2323` also commits an eval delta into the global candidate population.
- `src/seon/cluster.clj:97-98` activates a module-load snapshot, and
  `src/seon/instrument.clj:375-387` may replace it on every instrumentation
  pass.
- `src/seon/error.clj:885-904` classifies native errors through the global
  projection rather than a named cluster database value.
- The isolated `tmp/atom-census-operator` scratch cluster on 2026-08-06 showed
  one process-global atom containing 1,842 candidate forms, 32 predicate
  functions, and one active projection fingerprint while the running instance
  separately carried its own cluster SCI context.

## Owner

Per-cluster schema acquisition at a database basis owns projections. The
process may retain a reloadable qualified-symbol-to-host-function compiler
cache, but database-derived forms and compiled projections belong to the
cluster context that acquired them.

## Acceptance

- Remove candidate forms and the active projection from process-global mutable
  state; every runtime schema decision accepts or reaches an explicit
  per-cluster projection acquired from that cluster's database value.
- Keep only genuinely process-local compiler artifacts globally, keyed without
  making one cluster's database facts the default for another.
- Native error classification, instrumentation, rendering, and SCI evaluation
  agree with each addressed cluster's projection when two co-hosted clusters
  have different program commits.
- A two-cluster live proof changes or reforks one cluster's schema facts and
  proves the other cluster's validation and error classification do not change.

## Resolution

Resolved by `656cea270`, `6012df4ce`, `d9f360997`, and `61ccb7332`.
Database-derived schema forms now live in each cluster's projection state; the
only process-local schema cache maps qualified predicate symbols to reloadable
host functions. Cluster and agent Flow graphs execute on the cluster-bound IO
executor, so native and SCI consumers retain the addressed cluster projection.
The two-cluster projection regressions and the fresh isolated bootstrap proof
exercise different database bases without cross-cluster mutation.
