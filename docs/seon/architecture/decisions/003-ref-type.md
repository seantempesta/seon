---
type: decision
status: implemented
date: 2026-03-05
tags: [decision, architecture, database, schema]
---

# ADR-003: Unified reference shape (`:seon.db/ref`)

## Context

Datahike reference attributes accept resolved entity IDs, transaction tempids,
and lookup refs. Repeating that union in every attribute would duplicate the
shape and let Malli validation drift from Datahike schema derivation.

## Decision

`:seon.db/ref` is the one reference shape. Every plain or component reference
attribute names it. The bridge in `seon.schema.datahike` derives
`:db.type/ref`; a collection wrapper derives cardinality many, and
`{:seon.db/component true}` derives component ownership.

The portable schema authority defines the shared shape once. Shipped
attributes and entity maps refer to it from `resources/seon/schema.edn`:

```clojure
:seon.cluster.run/agent :seon.db/ref
:seon.cluster.run/forms [:set {:seon.db/component true} :seon.db/ref]
```

## Consequences

- Application schemas never hand-write `:db/valueType`.
- Lookup refs use an identity attribute and its stored value type.
- Component ownership belongs to the reference attribute, not an entity kind.
- If the shared shape or bridge cannot express a required reference, fix that
  owner rather than inlining another union.

## Owners

- `src/seon/schema.clj` — reference grammar.
- `src/seon/schema/datahike.cljc` — Malli-to-Datahike derivation.
- `resources/seon/schema.edn` — shipped attribute and entity declarations.
- [[data-model]] — reference, identity, and component semantics.
