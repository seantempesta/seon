---
type: issue
status: resolved
severity: blocker
tags: [issue, adoption, schema]
---

# Development adoption refuses a local plan registry reference

On 2026-09-08, adoption after page-feed commit `1338b67b4` was refused at
`:seon.boot/population :seon.schema/rows` with `:seon.db/rejected`:
`Nothing found for entity id [:seon.schema/key :my.plan/component-input-node]`.
The schema name is declared inside the local `:registry` of
`:my.plan/component-input` in `resources/seon/schemas/my.plan.edn:105`.
This records the observed boundary, not a confirmed indexing cause.

The same render changes passed their HEAD-plus-owned-paths gate and adopted
on page-feed's isolated scratch root. Default was not stopped or restarted.
Complete publication subsequently converged at
`6aa0e1ee-81a0-5819-b0b6-a68ab1c420d3`, but the browser still showed eight
pre-filter blocks. Its log did not name a `seon.render.web` reload. This is
negative evidence against treating the adoption marker alone as proof of loaded
behavior; it does not establish the schema/indexing cause. The lane preserved
the concurrently edited adoption owner. Outcomes are recorded in the
[page-feed landing note](../../prds/context-generation/research/page-feed-landing-2026-09-08.md).

## Resolution (2026-09-15 triage)

At audited HEAD `7e35df213`, `src/seon/schema.clj:33-51` derives references through Malli's walker, only emitting keys in the canonical population and following local registry references. `resources/seon/schemas/my.plan.edn:119-148` still contains the local recursive key, so this is positive inspection of the original shape, not absence of its subject. The local key cannot become a canonical ref through this constructor. Verified with `git show 7e35df213:src/seon/schema.clj` and the schema resource. The historical subsequent stale browser paint is not evidence against this local-reference fix; adoption/loaded-state concerns remain in `class-loaded-artifacts-lack-source-identity.md`.

surface: adoption-publication
