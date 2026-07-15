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

The isolated boot index falsifier selects the four rows directly from
`seon.client/index-core!` before any database rendering. Every stored fact is
already corrupt: `button`, `input`, `select`, and `toggle` each decode to two
top-level vectors. The first is the real destructuring arglist and the second
is the returned Hiccup body. For example, `button` stores
`([{:my.canvas/keys [label handler data]}] [:button ... label])` beside its
single `[:=> [:cat :my.canvas/button-request] :my.canvas/control]` schema.
The exact focused falsifier produced four failures with actual top-level count
two. Therefore `parsed-arglists` faithfully exposes already-corrupt indexed
data; it does not introduce the phantom vector.

## Implementation checkpoint — 2026-07-15

The shared callable projection now treats any valid persisted Malli function
schema as the authority for the number of logical callable alternatives.
Physical arglists supply labels for those alternatives, and remain the
fallback only when no callable schema exists. Thus indexed implementation
vectors cannot create an additional unspecced arity, while genuinely
unspecced functions still expose every stored physical arglist.

A table-driven focused regression covers nested Hiccup and recursive vector
output data. Together with the existing pure-variadic regressions, the focused
gate passes four tests and twenty assertions. This renderer defense is
necessary but does not make the underlying program facts truthful by itself.

## Index repair checkpoint — 2026-07-15

`seon.client/arglists-from-source` is the first corrupt owner. Its reader-free
scanner previously collected every vector directly inside a single-arity
`defn`, including a vector-valued implementation body. Candidate vectors now
retain their parenthesis depth until the scan completes. The first depth-one
vector establishes a single-arity definition and excludes all later body data;
when no depth-one vector exists, all depth-two multi-arity parameter vectors
survive.

The exact boot-index regression proves each of `my.canvas/button`, `input`,
`select`, and `toggle` now stores one physical map-destructuring arglist.
Existing real-source tests simultaneously preserve pure variadic `query`,
multi-arity `pull` and `entity`, the variadic `transact!` body, and local
auto-qualified destructuring keywords. The focused indexing gate passes four
tests and twenty-three assertions.

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

Keep this separate from the completed pure-variadic logical-arity change. The
fix and focused source proof are complete. The remaining exit is a coordinated
clean ACME rebuild proving the durable database rows and all four derived cards
from one immutable database value. Namespace policy and context prose are
outside this defect.
