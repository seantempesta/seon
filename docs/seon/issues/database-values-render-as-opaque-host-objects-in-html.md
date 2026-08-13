---
type: issue
status: open
severity: friction
tags: [issue, render, database, web]
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

Still open, with a reproducible focused boundary. In
`seon.render-simplification-test/nested-values-render-their-declared-faces`,
the value handed to `seon.render.value/admitted-projection` is the full
Datahike object instead of its identity-only projection, so AI and HTML expose
`#datahike.db.DB`. The exact edit belongs at the schema-projection handoff in
`src/seon/render/value.clj` / `src/seon/sci/admit.clj`, outside this lane's
owned paths; the focused gate ended with this one failing test.
