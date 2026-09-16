---
type: issue
status: open
severity: friction
tags: [issue, render, performance, wave/render-producers]
---

# Function entity rendering would synchronously recompute test reach

## Problem

Steward-platform P2 requests an eager reaching-test count for every function
block. The existing exact query takes seconds on default. Calling it for
each AI and HTML block adds that work to the render path. No production pair
was added by this observation.

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

Choose eager or explicit-read counts for P2. If eager counts remain required,
measure the existing query owner after improvement and prove the function
pair through selection and the walk under the normal render bound, using
the canonical fixture and armed contracts. Preserve subject and
pending-subject reach semantics.
