---
type: issue
status: open
severity: friction
tags: [issue, render, performance, wave/render-producers]
---

# Exact test reach takes 7.22 seconds for one function

## Problem

The existing exact query takes seconds on default. Owner-approved P2 renders
only its exact query form and never eagerly computes counts (2026-09-16).
That keeps the entity block fast, but an agent explicitly asking for reach
still pays the measured query cost. Improve the shared query owner separately.

## Evidence

On 2026-09-15, the entity-pairs lane measured
`(seon.fn/tests-reaching database "seon.id/id")` through MCP JVM mode:
936 tests, 7217.67325 ms, no error. Database custody came from
`(seon.db/db (seon.operator/connection "default"))`. A preceding combined
AI/HTML prototype probe exceeded MCP's 30000 ms bound; its completion was
not observed, so that timeout alone does not attribute cost to one query.

The source routes `tests-reaching` directly to `gate-set`
(`src/seon/fn.clj:894`), whose recursive Datalog query is at
`src/seon/fn.clj:874`. Render producers run under the request evaluation
bound (`src/seon/render.clj:831`).

Reproduction and the three owner options are recorded in
[the entity-pairs landing note](../../prds/steward-platform/research/entity-pairs-2026-09-16.md).

## Owner

`seon.fn/gate-set` owns exact reach derivation. Entity renderers must not
introduce a second traversal or a stored count that can drift.

## Acceptance

Measure the existing query owner after improvement on the same function and
representative sparse/dense reach sets, with canonical armed regressions.
Preserve subject and pending-subject reach semantics. Keep P2's explicit-read
queries; do not introduce a second traversal or cache in renderers.
