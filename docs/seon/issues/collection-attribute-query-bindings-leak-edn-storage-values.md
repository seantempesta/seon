---
type: issue
status: open
severity: friction
created: 2026-09-17
tags: [issue, database, query, wave/database-codec]
---

# Collection attribute bindings leak EDN storage values

## Problem

`seon.db/q` decodes an EDN-backed attribute when the attribute is a literal
or scalar input, but returns its storage string when the same attribute is
supplied through a collection binding. A caller comparing that result with
the logical symbol finds nothing, even though the datom exists.

## Evidence

The canonical, armed probe is
`test/my/program_query_test.clj`,
`scalar-attribute-bindings-preserve-the-renderer-value-type`. On the
`run.zWaZQU` fast snapshot, at 2026-09-17 02:04:05Z, it printed:

```clojure
#:seon.program.probe
{:pulled #:seon.render{:ai seon.render.value/render-ai}
 :scalar seon.render.value/render-ai
 :literal seon.render.value/render-ai
 :collection #{[:seon.render/ai "seon.render.value/render-ai"]}}
```

All reads select `:seon.program/breakage` from the same database value.
The collection query is ordinary Datalog:

```clojure
[:find ?property ?renderer
 :in $ ?key [?property ...]
 :where [?schema :seon.schema/key ?key]
        [?schema ?property ?renderer]]
```

`src/seon/db.clj`, `query-variable-attributes` and `query-find-attributes`,
infer a column's attribute from a literal or scalar input. The collection
binding supplies neither to that inference, so `decode-query-field` leaves
the storage string unchanged. The render properties themselves are present:
`seon.schema/canonical-schema-rows` merges their storable properties into the
schema row, and the pull above observes the symbol.

The affected read now uses one scalar property binding per query through
the same `seon.db/q` owner. Its regression and both probes passed together:
6 tests, 63 assertions, no failures or errors. The probe does not assert
that leaking the string is correct; its assertions protect the supported
scalar/literal/pull path, and its printed comparison records the defect.

This is separate from the refactoring spec's original nested `str` predicate:
that clause was a wrong query, proven by the preceding canonical probe.

## Owner

`seon.db` query-result decoding. The db file is held by the integrator;
the program-read lane does not edit it or introduce its own EDN decoder.

## Acceptance

Literal, scalar-bound and collection-bound attribute queries return the same
logical values as pull on the canonical fixture. Cover mixed attributes
with different storage codecs; if the decoder cannot identify a field's
attribute, report that explicitly rather than silently exposing storage.
