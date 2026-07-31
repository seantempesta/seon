---
type: issue
status: resolved
severity: blocker
tags: [issue, render, context, architecture]
---

# The value floor truncates in band without a placeholder, so the shape is a lie

## Problem

`seon.render.block/data-prose` and `data-panel` promise that
"collections beyond the configured width render as an explicit elision
marker rather than a wall" (`src/seon/render/block.clj:922-926`). What
the one floor actually emits is a single PANEL-LEVEL sentence appended
after the whole value; the truncated structure itself carries no
placeholder, so a shortened collection is indistinguishable from a real
one of that size.

Independently falsified at HEAD (probe retained at
`tmp/audit-0731/probe8.clj`), agent-facing `ai` kind:

```text
;; a 5x5 matrix of :x under max-nodes 8
[[:x :x :x :x :x] []] (elided — this value is larger than the configured caps)

;; a six-key map under max-nodes 4
{:a 1} (elided — this value is larger than the configured caps)

;; a forty-item vector under max-collection 3
[0 1 2] (elided — this value is larger than the configured caps)
```

The second row of the matrix is a FIVE-element vector printed as `[]`,
and rows three through five vanish. `[]` and `{:a 1}` are themselves
legitimate values. An agent that reads `(get-in v [1 0])` reasons from
an empty collection that is not empty. This is precisely what
`seon.sci.admit`'s own docstring forbids: "a reader must never have to
guess whether an elision marker was the agent's own data."

The trailing sentence does tell the reader that SOMETHING was cut, so
this is not total silence — it is worse in kind: the reader is told the
value was capped and simultaneously shown a shape that is false, with
no way to locate the cut.

Two further honesty defects in the same owner, found by the same sweep
and reproduced at HEAD:

- `raw-child` (`src/seon/render/value.cljc:235-242`) resolves a raw
  child for map, vector, and set only; every other sequential falls to
  `nil`, so `summary` prints the literal `nil` as the summary of a
  non-nil node. `clipped?` (`:255-264`) then requires `(string? raw)`,
  so a string truncated inside a list renders with NO clip marker and
  no drill link, while the same string at top level correctly emits one.
  Marker presence therefore depends on the CONTAINER TYPE rather than
  on the bound.
- `display-value` (`:130-143`) windows for BOTH kinds, but only the
  html twin has a pager and breadcrumbs; `render-ai-data` (`:200-206`)
  discards the `:seon.render.value/summary` that `prepare` computed. A
  forty-key map read past the end renders to an agent as `{}` with no
  marker at all, and the pager prints "showing 0–500 of 40". Latent
  today — only `seon.render.web` builds cursor-carrying units and both
  routes render html — but nothing in the types or the call graph
  prevents it, and `data-prose` passes such a unit straight through.

## Acceptance

Every active bound emits an IN-BAND placeholder at the point of the
cut, in both twins, and the placeholder is unforgeable — a marker map
from `seon.sci.admit`'s own grammar, never a bare `[]`, `{}`, or a
short string. Concretely: `seon.sci.admit`'s `take-node!`/`afford!`
append the elision scalar to the partially built collection rather than
returning it silently short, the way the depth cap already does
correctly. `raw-child` is total over `sequential?` or, better, child
summaries and `clipped?` derive from the ADMITTED projection rather
than from a re-navigation of the raw value. The ai twin either reports
the window it applied or windowing becomes an html-only property, and
`truncated?` is true whenever `offset > 0` or `shown < total`.

One recurring generative property per bound: for depth, collection
width, string length, and node budget, in both kinds, the rendered
output must contain a marker AT the cut, and a value that was NOT
capped must contain none.

## Evidence

`docs/prds/sci-execution-runtime/research/context-wave-audit-2026-07-31.md`

## Resolution

Resolved by `fe4e23f2d`. `seon.sci.admit` now reserves the last affordable
collection node or map entry for its in-band elision marker instead of
returning a silently shortened shape. A string clip becomes admit-owned
marker data containing the retained prefix, so both the AI and HTML twins
show the cut at that string. When even the containing structure cannot fit,
the structure becomes the scalar elision marker rather than a lying empty
collection.

`seon.render.value` now resolves raw children across every sequential value,
applies cursor windowing only to the HTML projection, inserts an in-band
window marker, and treats either a positive offset or fewer shown entries
than the known total as truncation.

Recurring fixed-seed properties cover depth, collection width, string length,
and node budget for both twins. Each property also supplies an uncapped
counterexample and asserts that its admitted tree contains no marker. The
audit's exact falsifiers now project as:

```clojure
;; max-nodes 8
[[:x :x :x :x :x] :seon.sci.admit/elided]

;; max-nodes 4
#:seon.sci.admit{:elided true}

;; max-string 3 inside a list
[#:seon.sci.admit{:truncated-string "abc", :elided true}]
```

Focused recurring proof after the commit:

```text
bin/test seon.sci.admit-test seon.render.value-test seon.render.block-test
Ran 46 tests containing 126 assertions.
0 failures, 0 errors.
```

The load-only cursor falsifier now renders the complete 40-key map to the AI
twin rather than `{}`. Its HTML-only cursor at offset 500 contains
`:seon.sci.admit/elided`, reports `showing 0 of 40`, and sets truncation loud.
