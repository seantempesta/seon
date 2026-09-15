---
type: issue
status: resolved
severity: blocker
tags: [render, schema, context]
created: 2026-09-14
---

# Derived maps lose their declared pair when a less specific entity also matches

The canonical nested-response regression in
`test/seon/render/value_test.clj`,
`declared-pairs-render-inside-response-values`, finds both
`:my.plan/component-view` → `seon.plan/format-plan-ai` and
`:seon.agent/agent` → `seon.cluster.agent/render-identity-ai` for a whole
plan result. The former requires five attributes; the latter requires
only agent identity.

`seon.render/schema-producers` applies its existing required-attribute
specificity rule only when the value has `:db/id`. The derived plan map
does not. `project-node*` then rejects the ambiguous set because one
candidate returns generated source. The result falls back to a raw map
or a structural elision, instead of its compact plan pair.

The general fix applies specificity to every matching map, counting
declared attributes actually present, including optional attributes.
Equally specific different pairs remain ambiguous. Attribute lookup also
works for Datahike database objects, whose iteration yields datoms.
Producer values override caller context fields: a nested plan keeps its
own agent identity. The canonical nested-response regression passes for
transaction reports, directory maps, plan items, and whole plans.
Fast value/print/loop verification: 53 tests, 425 assertions, no failures
or errors.
