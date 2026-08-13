---
type: issue
status: open
severity: friction
tags: [issue, render, schema, web, class/n11, wave/context-fixes]
---

# Floor residue: a shipped second dial set, two cursor walks, and a marker hand list

## Problem

Four findings against the W3 floor unification, all confirmed by
reading the landed source at HEAD.

**A shipped second set of size dials survives.**
The RENDER_VALUE section of `resources/seon/schema.edn` still declares
`:seon.render.value/max-depth` (default 3), `max-collection` (8),
`max-map-visits` (32), `max-string` (80), `shape-sample` (8), `width`
(72), and `:seon.render.value/options`. Every one of those keys has
ZERO consumers in `src/` and `test/`, yet `seon.render.value` calls
`schema.edn/load!` on every load and registers them globally. They are
residue of the deleted floor, and `seon.render.block:924-926` cites
their non-existence as the wave's own justification: "a second set of
size dials would drift from the first." Delete them, along with the
dead `:seon.render.value/width` and `:seon.render.value/schemas`
optionals on `:seon.render.value/projection`, which `prepare` never
produces.

**Two implementations of one cursor walk.** `descend`
(`src/seon/render/web.clj:355-384`) is `seon.render.data/at`
(`src/seon/render/data.clj:50-73`) verbatim modulo an `open` step and
the error keyword — same `(Object.)` sentinel, same `index-step?`, same
three arms, same refusal text. `data.clj`'s docstring already claims
both `/data` and the per-agent debug route hand their value to it; only
`/data` does. Give `data/at` a per-step opener and delete `descend`.

**Two implementations of the reverse-ref window, including its
message.** `generic-entity` (`src/seon/render/web.clj:320-345`) repeats
`seon.render.walk`'s `reverse-refs` windowing — same width dial, same
`distinct/sort/reverse/take`, same elided arithmetic — and emits the
identical sentence "elided N reverse ATTR connection(s) at the
configured collection cap" while hardcoding the other namespace's
private keyword `:seon.render.walk/elided` as a literal. One producer
of that marker, called from both.

**A marker hand list.** `marker-map?`/`marker-text`
(`src/seon/render/value.cljc:212-233`) enumerate
`seon.sci.admit`'s marker grammar by hand. A map carrying an
unenumerated `:seon.sci.admit/*` key renders as ordinary agent data —
and `:seon.sci.admit/description`, which the projection-failure path
does emit, is not in the list. `seon.sci.admit` owns the closed grammar
and should publish the predicate and the display text; `value.cljc`
calls it.

**Smaller, same owner.** `render-html-data`
(`src/seon/render/value.cljc:193-198`) is the identity function whose
only caller is a test asserting it is the identity function; `prepare`
claims to produce "the finite projection both twins consume" while
`render-html` bypasses it. The namespace docstring claims raw values
are consulted "only for O(1) count/length summaries" while
`stable-entries` (`:83`) sorts the entire raw collection with a
`pr-str` per entry. `(< depth 2)` appears three times (`:276`, `:301`,
`:319`) as one presentation constant written three ways. And
`4399ce8d6`'s `#?(:cljs …)` branch in `node-id` is unreachable —
`value.cljc` requires the CLJ-only `seon.sci.admit` — and would produce
DIFFERENT ids if it ever ran, which is the identity breakage the commit
title claims to prevent.

## Acceptance

The dead schema keys are deleted; `descend` and the duplicated reverse
window each collapse into their one existing owner; the marker
predicate moves to `seon.sci.admit` and `value.cljc` calls it; the dead
`render-html-data` path and its test assertion are removed; the two
false docstring claims are corrected; the triplicated depth constant
becomes one named value; and the unreachable `:cljs` branch is either
removed or backed by a portability proof that loads.

## Evidence

`docs/prds/sci-execution-runtime/research/context-wave-audit-2026-07-31.md`

## Backlog triage 2026-08-02

**Still real, narrowed to two mechanisms.** The sealed print wave deleted the
dead option family and marker hand list, made `render-html-data` a real sink,
and replaced the old depth constants/docstrings. `9aa9bf8d1` also deleted the
duplicate debug cursor walk. Two current findings survive:

- `seon.render.web/generic-entity` still repeats
  `seon.render.walk/reverse-refs`, including its cap and prose; and
- `seon.render.value/stable-entries` still sorts the complete raw map/set with
  `pr-str` before applying the page window, while `node-id` still deliberately
  hashes differently on CLJ and CLJS without a checked dual-tier identity
  proof.

The destination is the render-floor cleanup wave: one reverse-ref window owner,
bounded cursor preparation before whole-value sorting, and one proven stable
node identity across every supported tier.
