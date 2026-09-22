---
type: issue
status: open
severity: blocker
tags: [issue, schema, test-system, wave/publication-velocity]
---

# A call-preparation facet requires unstorable candidates

## Problem

`:seon.call-preparation/ambiguous-call-error` requires
`:seon.call-preparation/candidates`, whose declared shape is a vector of
vectors of argument indices. Canonical declaration admission refuses it
as an unstorable required error member. Fast snapshot admission therefore
cannot record, even when that suite does not reach call preparation.

## Evidence

After `fd93f709a`, publication's export regression executed zero tests:
`tmp/publication-dissolution/morning-export-fast.log` names
`:seon.schema/path [:seon.call-preparation/candidates]` and
`:seon.schema/identity :seon.call-preparation/ambiguous-call-error`.
Declaration: `resources/seon/schemas/seon.call-preparation.edn:245`;
member shape at line 121. The publisher did not execute in that attempt.

## Owner

SCI call-preparation sweep and schema declaration/storage owners. The
publication lane did not edit either owner.

## Acceptance

Canonical schema acquisition accepts every required member of that declared
error schema, and
`bin/test-fast --paths test/seon/cluster/publication_export_test.clj -- seon.cluster.publication-export-test`
reaches its test body and records its result. Preserve the ruled distinction
between typed durable members and optional in-memory observations.
