---
type: issue
status: open
severity: friction
tags: [issue, schema, render, wave/schema-audit]
---

# Follow transparent Malli schema wrappers when checking renderer inputs

During schema-audit on 2026-09-15, an armed canonical fixture rejected
`:my.edit/ambiguous-match-error` because `seon.error/edit-prose` declared input
`[:schema properties :seon.schema/value]`. Malli accepts that transparent wrapper
and the referenced value schema is `:any`, so every declaring map fits it.

`src/seon/schema.clj:1454` dereferences the input once and then compares its type.
The explicit wrapper plus named reference requires following another transparent
step. The equivalent `[:any properties]` passes the same fixture. The audit uses
that equivalent form and does not change this out-of-scope implementation owner.

## Acceptance

Renderer coherence follows Malli's transparent wrappers/references with cycle
handling. One canonical fixture proves that bare `:any`, the named alias, and
the property-bearing transparent wrapper make the same acceptance decision.
