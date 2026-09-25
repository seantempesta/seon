---
type: issue
status: open
severity: friction
tags: [issue, schema, contracts]
created: 2026-09-25
---

# `:seon.schema/projection` admits a projection without a registry

## Evidence

Default pid 67823: `(seon.schema/valid-candidate-value? p :seon.schema/projection
{:seon.schema.projection/forms {:my/plain :string}})` answers `true`. Every
reader of `(:seon.schema.projection/registry projection)` — e.g.
`seon.render.web/attribute-description` and `declared-entity-units`
(src/seon/render/web.clj) — then passes nil to `malli.registry/schema` and fails
with a bare NullPointerException instead of a named refusal. This was the
render/web_test `an-attribute-description-comes-from-the-projection-form` red
(fixed at the producer, the hand-built test projection, in 18192143c).

## Fix direction (schema resource owner)

Require `:seon.schema.projection/registry` in the `:seon.schema/projection`
declaration so an armed contract refuses the incomplete value by name at entry.
