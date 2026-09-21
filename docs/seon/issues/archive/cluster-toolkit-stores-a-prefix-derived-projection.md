---
type: issue
status: resolved
severity: friction
tags: [issue, database, render, class/n7, wave/context-derivation]
resolution: Both prohibited shapes are gone: src/seon/cluster/instruction.clj:31-57 selects namespaces by the declared :seon.ns/context-relevant? fact rather than a 'my.' name prefix, and derives the set from the supplied database value on every call; no durable toolkit roster is written (seed-rows at :59 installs only the getting-started instruction text).
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
and stores the new one. The INSTRUCTION section of `resources/seon/schema.edn` makes the
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

## Re-grounded evidence — 2026-08-13

**STILL-REAL at `06e654c76`.** The old `.cljc` and monolithic-schema anchors
were renamed, but both prohibited mechanisms remain:

- `src/seon/cluster/instruction.clj:32-57` still classifies toolkit namespaces
  with `(str/starts-with? ... "my.")`.
- `src/seon/cluster.clj:1694-1745` still derives that set, compares it with the
  cluster's stored `:seon.cluster/toolkit`, retracts the old value, and writes
  the projection back as durable refs.
- `resources/seon/schemas/seon.cluster.edn:1-15` still makes that copied set an
  attribute of every cluster entity, and
  `test/seon/cluster/instruction_test.clj:115-142` still proves convergence by
  corrupting and reconciling the stored copy.

## N7 implementation boundary — 2026-09-15

Namespace metadata declares `:seon.ns/context-relevant?`; the existing namespace
indexer records it and `toolkit-namespaces` queries it. The `my.` classifier is
deleted. Eleven agent-facing namespace declarations carry the explicit fact.
The new canonical regression adds `sample.relevant` and an unrelated
`my.impostor`, and observes only the declared namespace entering the result.

Still open: `src/seon/cluster.clj` is protected by the owner's latest instruction
and continues to reconcile a stored copy. Exact writer and renderer diffs and
the dependent schema/test changes are in the N7 landing note. No protected
file was edited.

Implemented slice: `5deb40e4e`; the live default query returned all eleven
declared namespace facts and the same eleven context namespaces in 24 ms.
Owner gate 82 tests / 418 assertions and platform gate 86 tests / 542
assertions passed. Closure still requires deleting the protected stored copy
and verifying its ordinary reconciliation; the new relevance query does not
establish that deletion. See
[the exact patches and probes](../../prds/context-generation/research/n7-query-classification-2026-09-15.md).
