---
type: issue
status: open
severity: friction
tags: [issue, context, database, architecture]
---

# Derive namespace context without a stored `my.*` roster

## Problem

Cluster namespace context has two prohibited shapes at once: namespace
spelling classifies relevance, then the resulting projection is copied into
durable cluster facts and reconciled as a second truth path.

## Evidence

`src/seon/cluster/instruction.cljc:36-61` queries public contracted functions
but keeps a namespace only when its printed name starts with `my.`. The query
result is sufficient to derive the projection from any database value.

Instead, `src/seon/cluster.clj:831-881` computes that result during cluster
reconciliation, compares it with `:seon.cluster/toolkit`, retracts the old set,
and stores the new one. `resources/seon/schema/instruction.edn:31-42` makes the
copied set part of every cluster entity. The regression at
`test/seon/cluster/instruction_test.clj:117-144` proves convergence by first
corrupting the stored copy and invoking reconciliation; it does not remove the
second authority.

## Owner

The database-derived namespace-context query used by the render walk.

## Acceptance

- Namespace context is queried from current program-graph facts when rendered;
  no `:seon.cluster/toolkit` projection is stored or reconciled.
- Relevance is derived from graph relationships and the requested context,
  never a `my.` prefix or another namespace-name roster.
- A regression adds a relevant namespace with an unrelated name and an
  irrelevant `my.*` namespace, proving classification without changing code.
- Every agent remains able to call every function; this projection never
  becomes a grant surface.
