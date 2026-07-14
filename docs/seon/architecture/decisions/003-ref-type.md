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

`:seon.db/ref`, registered in `seon.schema`, is the one reference shape. Every
plain or component reference attribute names it. The bridge in
`seon.db.internal` derives `:db.type/ref`; a vector wrapper derives cardinality
many, and `{:seon.db/component true}` derives component ownership.

```clojure
(schema/register! :seon.agent.run/agent :seon.db/ref)
(schema/register! :seon.agent.turn/evals
                  [:vector {:seon.db/component true} :seon.db/ref])

(db/transact! {:seon.db/tx-data
               [{:seon.agent.turn/run [:seon.agent.run/id "run-id"]}]})
```

## Consequences

- Application schemas never hand-write `:db/valueType`.
- Lookup refs use an identity attribute and its stored value type.
- Component ownership belongs to the reference attribute, not an entity kind.
- If the shared shape or bridge cannot express a required reference, fix that
  owner rather than inlining another union.

## Owners

- `src/seon/schema.cljc` — registered reference grammar.
- `src/seon/db/internal.cljs` — Malli-to-Datahike derivation.
- [[data-model]] — reference, identity, and component semantics.
