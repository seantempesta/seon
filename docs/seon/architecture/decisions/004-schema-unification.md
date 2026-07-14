---
type: decision
status: implemented
date: 2026-03-06
tags: [decision, architecture, schema, database]
---

# ADR-004: Malli is the schema authority

## Context

Validation shapes and persistence schemas describe the same attributes. Keeping
them as separately authored registries permits accepted values and Datahike
storage semantics to diverge.

## Decision

`seon.schema/register!` is the single authored schema surface. Attribute Malli
forms carry identity and component metadata; the bridge derives Datahike value
type, cardinality, uniqueness, and component ownership. Application namespaces
never hand-write native Datahike schema.

```clojure
(schema/register! :seon.foo/id
                  [:string {:seon.db/identity true}])
(schema/register! :seon.foo/tags [:vector :keyword])
(schema/register! :seon.foo/parent :seon.db/ref)

(db/transact! {:seon.db/tx-data
               [{:seon.foo/id "abc" :seon.foo/tags [:one :two]}]})
```

A complete declaration candidate resolves every schema reference and derives
compatible native attributes before publication. Failed validation leaves the
previous immutable projection authoritative. Runtime instrumentation consumes
that accepted projection; it does not create a second schema registry.

## Consequences

- Persistence metadata lives on registered attribute shapes.
- Persisted data forbids `:any`, `:some`, `:nil`, and `[:maybe X]`; optional
  attributes are absent.
- Shared shapes are registered once and referenced.
- A bridge limitation is fixed in the bridge rather than bypassed with native
  schema maps.

## Related

- [[architecture/decisions/002-absence-over-nil]] — absence semantics.
- [[architecture/decisions/003-ref-type]] — the shared reference shape.
- [[architecture/decisions/007-runtime-instrumentation]] — accepted program
  publication and wrappers.
- [[data-model]] — complete attribute ownership.
