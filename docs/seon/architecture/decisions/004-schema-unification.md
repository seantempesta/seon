---
type: decision
status: superseded
date: 2026-03-06
tags: [decision, architecture, archive, schema, database]
---

# ADR-004: Malli is the schema authority

This decision is superseded as an authored-surface decision. It selected one
load-time `seon.schema/register!` surface for both Malli validation and
Datahike schema derivation. That publication mechanism was deleted.

The durable part survives: Malli forms remain the validation authority and
`seon.schema.datahike` derives Datahike attributes from them. The replacing
decisions are the 2026-07-27 ruling that schemas are one global program-graph
population and ruling 2026-08-01 #33, which makes function contracts parsed,
queryable program facts.

[[data-model]] describes the current target. Shipped declarations enter
through `resources/seon/schemas/` and `src/seon/schema/edn.clj`; runtime
registrations enter through the admitted `src/seon/program.cljc` and
`src/seon/sci/eval.clj` path.

## Related

- [[architecture/decisions/002-absence-over-nil]] — absence semantics.
- [[architecture/decisions/003-ref-type]] — the shared reference shape.
- [[data-model]] — current schema and database ownership.
