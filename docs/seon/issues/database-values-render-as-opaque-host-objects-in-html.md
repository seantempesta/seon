---
type: issue
status: open
severity: friction
tags: [issue, render, database, web, class/n1, wave/render-producers]
---

# Render database identities in HTML instead of opaque host objects

## Problem

A database value nested in an HTML render has no declared HTML producer and
falls to an opaque JVM representation such as
`#object[datahike.db.DB 0x...]`. The corresponding AI projection already uses
the declared database identity face, so the two targets disagree and the web
UI exposes a dependency class plus process-local identity hash.

## Evidence

While strengthening
`seon.render-simplification-test/nested-values-render-their-declared-faces`,
the same nested request rendered `database` and `basis transaction` in AI but
the opaque host object in HTML. In
`resources/seon/schemas/seon.db.edn`,
`:seon.db/database-value-identity` declares `:seon.render/ai` and no
`:seon.render/html` producer.

## Owner

The database-value identity declaration and its paired render producers.
Selection must continue through the ruled explicit-or-declared-schema nested
path, not contract fit.

## Acceptance

- A database value nested at depth renders its identity in both AI and HTML.
- Neither target exposes `datahike.db.DB` or a JVM identity hash.
- One regression proves the class through the ordinary nested render walk.

## N1 disposition — 2026-08-12

Partially resolved by `e8e37eb50`. The exact probe admitted the test's map with
and without the database-derived schema projection. Without it, the nested
database was a structural record containing Datahike internals; with it, the
same node was an identity-only object containing only `:db-name`, `:t`, and
`:datahike/commit-id`. Nested producer consultation was already correct.

The selected invocation now hands its one database-derived projection around
the operation, and `seon.sci.admit` consumes either the request's explicit
projection or that operation-local handoff before walking any node. The
focused `seon.render-simplification-test` is green: 11 tests, 134 assertions.
Nested database values now use the declared AI identity face, and nested
transaction reports use their declared AI and HTML faces without exposing
`datahike.db.TxReport`.

This issue remains open because `:seon.db/database-value-identity` still
declares no HTML producer. A direct nested render after the fix therefore
still emits `#object[datahike.db.DB ...]` in HTML. Closure belongs to the one
database-value identity declaration and its paired render producers; it must
not add another selection or admission path.
