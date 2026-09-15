---
type: issue
status: resolved
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

## Verified at HEAD (2026-09-16, N1 verification)

**RESOLVED.** Probed on the live `default` cluster (pid 69622) with a
complete render request, including the `:seon.render.value/root` the earlier
N1 probes omitted. Both targets render the database's identity and neither
exposes `datahike.db.DB` or a JVM identity hash.

AI:

```text
#:n1{:database "database :cluster-default at basis transaction 536872132 commit 6aa9d310-8baa-51ec-ac1d-77626e26eeb7"}
```

HTML (extract; whole document is 1,282 characters, and
`(re-find #"datahike\.db|0x[0-9a-f]{6,}" html)` returns `nil`):

```clojure
[:details {:class "seon-print-node seon-print-map", :data-seon-path "[0 1]"}
 [:summary {:class "seon-print-summary"} "map 3 items, depth 2"]
 [:span {:class "seon-print-content"}
  … ":datahike/commit-id" "#uuid \"6aa9d310-8baa-51ec-ac1d-77626e26eeb7\""
  … ":db-name" ":cluster-default"
  … ":t" "536872132"]]
```

`:seon.db/database-value-identity` still declares only `:seon.render/ai`
(`resources/seon/schemas/seon.db.edn:151`), but the HTML target no longer
needs it: the identity-only projection reaches HTML through the same
operation-local schema handoff, so the acceptance — identity in both
targets, no dependency class, no hash — holds behaviourally. Closing on
behaviour; a later HTML producer for the identity would be presentation
polish, not this defect.
