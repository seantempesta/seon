---
type: issue
status: open
severity: friction
tags: [issue, agent, schema]
---

# Prevent output data from becoming a phantom callable arity

## Problem

The shared compact callable projection renders a bogus second positional arity
for `my.canvas/button`, `input`, `select`, and `toggle`. Each function has one
physical destructuring arglist and one Malli `:=>` contract, but the live card
continues with a bracketed Hiccup value from the implementation body and labels
it `OR positional ... -> <return unspecified>`.

This corrupts every consumer of `compact-fn-head`: required namespace cards,
menus, `my.ns/functions`, and autocomplete. It is a global indexed-program or
callable-projection defect, not a canvas-specific prompt problem.

## Evidence

The complete database-derived inventory and immutable coordinate are preserved
in [[../../prds/agentic-tool-refinement/research/tool-namespace-colocation-audit-2026-07-15]].
The four affected functions are ordinary one-argument functions whose source
schemas are `[:=> [:cat ::request] ::control]`. Their returned Hiccup bodies
are data, never callable input.

The current `callable-contract` implementation pairs parsed physical arglists
and logical Malli specs by position and renders up to the larger count. That
mechanism needs a falsifier proving whether the extra vector first entered the
stored `:seon.fn/arglists` fact or was introduced while parsing it; it must not
be hidden with a symbol allow-list.

## Acceptance criteria

- The stored analyzer facts and compact projection are inspected from one
  immutable database value to identify the first corrupt boundary.
- One physical arglist plus one Malli callable schema always renders exactly
  one callable alternative, regardless of recursive/vector output data.
- A table-driven regression covers a function returning nested Hiccup data and
  proves none of that output value is interpreted as an arglist.
- Cards, menus, `my.ns/functions`, and autocomplete continue to consume the
  same corrected projection.
- A clean ACME rebuild shows exactly one truthful contract for all four canvas
  controls.

## Scheduling

Keep this separate from the completed pure-variadic logical-arity change. Run
the stored-fact falsifier after the coordinated ACME rebuild, then fix the first
corrupt owner before changing namespace policy or context prose.
