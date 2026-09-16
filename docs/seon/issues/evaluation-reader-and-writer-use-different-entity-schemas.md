---
type: issue
status: open
severity: cleanup
tags: [issue, schema, database, evaluation]
---

# Evaluation reader and writer use different entity schemas

Schema-audit finding D3 from
[the three-grammar research](../../prds/steward-platform/research/entity-schema-vs-pulled-shape-2026-09-16.md).
`:seon.eval/entity` in `resources/seon/schemas/seon.eval.edn` declares render
functions but no `:seon.db/attributes`. Consequently `seon.db/write-entity-schemas`
does not select it, while `seon.eval/of-agent` promises that shape on return.

The broader claim that evaluation rows receive **no** whole-entity write
validation is not supported by the source. `:seon.cluster.eval/receipt` in
`resources/seon/schemas/seon.cluster.eval.edn` declares
`:seon.db/attributes true`, requires `:seon.cluster.eval/id` (an identity),
run, ordinal and at, and is selected by that same writer derivation.
The writer validates the resulting datom-derived row against that declaration.

The audit should settle one authoritative declaration and its reader projection,
and verify the different required fields and expanded-reference shapes. This
note does not authorize adding a second write validator or changing contracts
in the pulled-reference repair.
