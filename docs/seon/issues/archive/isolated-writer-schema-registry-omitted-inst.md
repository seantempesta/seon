---
type: issue
status: resolved
tags:
  - database
  - schema
  - initialization
---

# Isolated writer schema registry omitted `:inst`

## Failure

Fresh initialization reached a stored entity attribute whose canonical schema
was `:inst`. Seon's CLJS registry defines `:inst` as the portable data form of
Malli's `inst?` predicate, but the JVM writer's explicit database-local Malli
registry contained only Malli defaults and the database program. Malli does not
provide `:inst` itself, so compilation failed before Datahike saw a declaration.

## Resolution

The existing pure schema internals now own Seon's primitive schema forms. Both
the normal Seon registry and the isolated JVM Datahike compiler consume that
same map. Database-authored schema references remain isolated; only Seon's
language-level primitive alias is shared.

## Evidence

- `seon.db.datahike.schema-test/canonical-primitive-alias-compiles-in-an-isolated-registry`
- Clean Bun database initialization proceeds past `:inst` attributes.
