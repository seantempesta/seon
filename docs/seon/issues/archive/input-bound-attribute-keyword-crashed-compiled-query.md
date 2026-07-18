---
type: issue
status: resolved
severity: blocker
tags: [issue, database]
---

# Input-bound attribute keyword crashed compiled query

## Problem

With Datahike attribute refs enabled, a keyword supplied through a collection
binding and used in a pattern's attribute position is not an attribute
resolution site. An uninstalled keyword therefore became a nil attribute in
the compiled executor and reached an EAVT probe, which threw instead of
returning the empty relation. Seon's declarative-state reconciliation also
incorrectly relied on attribute-position input variables resolving installed
keywords.

## Resolution

Maintained Datahike commit `4c55791b` treats a nil attribute as an empty
positive merge, a successful anti-merge, or an optional merge default. Seon
now joins each supplied identity attribute through the schema datom
`[?attribute :db/ident ?identity-attr]` before using its numeric attribute ref.
That is Datahike's existing schema representation and naturally ignores
uninstalled identity attributes without a second lookup path.

## Evidence

- The focused Datahike regression passes 2 assertions on the compiled and
  legacy engines.
- The complete Datahike query-planner namespace passes 30 tests and 126
  assertions.
- Seon's affected changed-test selection passes 57 tests and 331 assertions,
  including `seon.state-test` and client initialization.
