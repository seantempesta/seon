---
type: issue
status: resolved
tags: [issue, database, rendering]
severity: blocker
---

# Host sampling policy query has an invalid find shape

## Evidence

The focused writer fixture proof reached the real Unit 1G host acquisition and
Datahike rejected `sampling-policy-query` with `:parser/find`. The query declares
`:find [?path-segments ... ?items] .`, combining a tuple find expression with
the scalar-dot form. Datahike accepts the tuple form without the dot.

The host conformance tests stub `query-writer-at!` and return a prepared vector,
so they prove fail-closed policy handling but never submit the maintained query
to the real writer parser. Consequently the JVM host cannot acquire a sampling
policy from any real database, even when all seven required facts exist.

## Acceptance

- The one production query uses Datahike's tuple find form without scalar dot.
- A real writer query returns the seven values in the declared order.
- Existing missing/incomplete policy cases remain fail-closed.
- The focused host conformance and integration writer tests pass.

## Resolution

Commit `49dcc009` replaces the invalid tuple-plus-scalar find with Datahike's
tuple find form and adds a parser regression over the maintained query. Missing,
multiple, malformed, and wrong-typed policies remain fail-closed. Independent
review accepted the integrated query and fixture with no P0/P1 findings.
