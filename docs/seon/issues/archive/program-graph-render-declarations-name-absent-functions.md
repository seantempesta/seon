---
type: issue
status: resolved
severity: blocker
tags: [issue, architecture, render, schema]
---

# Make program graph render declarations resolvable

## Problem

Function, schema, and namespace entity declarations advertised AI and HTML
render projections whose namespaces did not exist in the fresh tree. These
families therefore looked renderable in the schema catalog but the one router
could only return `:seon.render/unresolvable` for them.

## Evidence

`resources/seon/schema/program.edn` declared six projection symbols under
`seon.render.handlers.fn`, `seon.render.handlers.schema`, and
`seon.render.handlers.ns`. There was no `src/seon/render/handlers/` path and no
namespace matching any of the six symbols in `src/`.

A live catalog probe stopped on the first declaration with
`FileNotFoundException`: Clojure could not locate
`seon/render/handlers/fn.clj`. `seon.render/render` would classify that same
resolution failure as `:seon.render/unresolvable`.

## Owner

The program graph schema declarations, composed with the one `seon.render`
router and the generic render floor.

## Acceptance

- Every projection symbol published in the entity catalog resolves in a clean
  fresh-tree JVM, or the declaration is absent.
- Function, schema, and namespace entities do not claim unbuilt specialists;
  the generic floor owns their presentation.
- A catalog-wide regression checks every remaining AI and HTML declaration.

## Resolution

The six unbuilt program-specialist declarations were removed. Function,
schema, and namespace entities remain catalogued by their identity attributes,
but no longer claim projections they cannot provide. No compatibility handler
or second dispatch path was introduced.

`seon.schema.program-test/catalog-render-declarations-resolve` proves both
rails in a fresh JVM: the three program families have no specialist
declarations, and every AI or HTML symbol still advertised anywhere in the
entity catalog resolves to a Var. The focused gate passed 2 tests and 24
assertions with zero failures or errors.
