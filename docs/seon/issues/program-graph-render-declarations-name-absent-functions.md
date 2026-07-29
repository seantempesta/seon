---
type: issue
status: open
severity: blocker
tags: [issue, architecture, render, schema]
---

# Make program graph render declarations resolvable

## Problem

Function, schema, and namespace entity declarations advertise AI and HTML
render projections whose namespaces do not exist in the fresh tree. These
families therefore look renderable in the schema catalog but the one router can
only return `:seon.render/unresolvable` for them.

## Evidence

`src/seon/schema.cljc:538-575` declares six projection symbols under
`seon.render.handlers.fn`, `seon.render.handlers.schema`, and
`seon.render.handlers.ns`. `src/seon/schema.cljc:1121-1190` copies those
declarations into the derived entity catalog.

There is no `src/seon/render/handlers/` path and no namespace matching any of
the six symbols in `src/`. `seon.render/render` resolves every declaration with
`requiring-resolve` and returns an unresolvable flat error when resolution
fails (`src/seon/render.clj:120-147`).

## Owner

The N5 program-graph projection builders, composed with the one
`seon.render` router and N4's generic HTML default.

## Acceptance

- Every projection symbol published in the entity catalog resolves in a clean
  fresh-tree JVM, or the declaration is absent.
- Function, schema, and namespace values have one derived unit-build point that
  selects generic defaults or specialists from their own attributes.
- AI and HTML consumers invoke those projections only through
  `seon.render/render`.
- A catalog-wide regression constructs each advertised family, renders every
  declared kind, and observes no unresolvable projection.

## Triage 2026-07-29

**PRESSING — current N5/program-graph render blocker.** `src/seon/schema.cljc`
still publishes all six `seon.render.handlers.{fn,schema,ns}` symbols, no such
namespace exists, and the router still returns `:seon.render/unresolvable`.
