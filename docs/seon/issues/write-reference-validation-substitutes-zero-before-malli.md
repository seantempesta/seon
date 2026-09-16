---
type: issue
status: open
severity: cleanup
tags: [issue, schema, database, admission]
---

# Write reference validation substitutes zero before Malli

Schema-audit finding D2 from
[the three-grammar research](../../prds/steward-platform/research/entity-schema-vs-pulled-shape-2026-09-16.md).
`seon.db/write-value` replaces each map or sequential value under an installed
reference attribute with `0` before the attribute Malli validator runs.
`write-many-values` supplies the corresponding cardinality-many grammar.

Thus widening `:seon.db/ref` to accept `{:db/id n}` changes reader contracts but
does not make the attribute write pass validate the original reference syntax
against that union. `write-ref-error` separately traverses nested maps and
lookup refs; Datahike resolves them. The final `write-entity-error` validates
resolved eids from `write-entity-value`, not the submitted maps.

This is a schema-audit boundary, not proof that arbitrary nested writes evade
validation. Audit which layer owns each grammar, including the dependency's
unique-identity reference handling, before replacing the normalization. No
write-path change is part of the pulled-reference repair.
