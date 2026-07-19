---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, database, schema]
---

# Publish the namespaces block schema on cold and reopen paths

## Problem

The namespace render controls correctly live on the component
`:namespaces` block, but no stored block entity schema declares their four
attributes. `schema/register!` validates the values while the writer's
Malli-to-Datahike publication only discovers attributes from maps marked
`{:seon.db/entity true}`. A fresh browser-created agent therefore fails its
namespaces render when Datahike rejects the first pull of
`:seon.agent.ctx.namespaces/full-source` as an unknown attribute.

This regresses one acceptance claim in
[[archive/live-data-and-debug-views-exposed-schema-boundary-drift]] after
commit `e205dc9e` moved the controls off `:seon.agent` without replacing the
stored declaration at their component owner.

## Evidence

The `bumpy-brooms-pay` debug feed reported `[namespaces] render failed` with
`Bad entity attribute :seon.agent.ctx.namespaces/full-source ... not defined
in current schema` for its namespaces block entity. Source inspection shows
the four leaf schemas in `seon.agent.ctx.namespaces` but no entity map that
names them.

## Acceptance

- One namespaces-specific stored block schema declares the established name,
  full-source, with-tests, current-full?, and current-tests? attributes.
- The generic block remains the shared validation surface; the controls are
  not duplicated back onto `:seon.agent`.
- A fresh database installs every selected attribute before the first agent
  prompt render.
- Reopening the database remains converged and the namespaces block renders
  without an unknown-attribute error.
- Focused index/schema tests and live fresh/reopen evidence cover the repair.

## Resolution

Resolved by `49bebaf5`. `seon.agent.ctx.namespaces` now declares the stored
specialization of the generic context block, so cold program publication
installs all four namespace render attributes before agent admission. The JVM
Malli-to-Datahike bridge now takes only the value type from a collection child
schema; identity, uniqueness, indexing, and component properties come only
from the outer stored attribute. This matches the existing CLJS bridge and
allows multiple blocks to select the same namespace symbol.

The complete CLJS gate passed 1,239 tests/5,552 assertions. The changed writer
gate passed 125 tests/791 assertions, including two distinct stored block
entities sharing `my.shared` without a uniqueness conflict.

The approved destructive default reset proved the fixed cold artifact at basis
transaction 536870918: all four attributes were installed before readiness;
full-source and with-tests were cardinality-many `db.type/symbol` with no
`db/unique`; both boolean controls were cardinality one; and the compiled root
context contained a healthy namespaces block. A config-free supervised restart
reopened at basis transaction 536870919 with identical schema and compiled
context names, and current writer/pod logs contained no namespace or core
errors. The contaminated pre-fix database was deliberately not treated as
reopen evidence because Datahike cannot remove its installed uniqueness facet
in place.
