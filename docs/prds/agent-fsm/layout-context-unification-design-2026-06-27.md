---
type: prd
status: draft
tags: [prd, web, agent, architecture]
---

# Context + UI Unification — "one entry vector, one engine, two surfaces"

Design map from the `layout-as-fn-of-db` workflow (2026-06-27), converged with
R's `seon.agent.ctx` refactor. **Routing is intentionally held** pending the
reitit investigation + setup — see the "Routing — reitit-pending" note. Naming
settled: shell · layout · tile · component · **console** = the REPL/debug
surface · theme · router · `seon.ui.*` (web) with `seon.render.*` (engine) kept
separate. Primary-panel name (stage / main / …) still open.

## Thesis

The **prompt composer already IS the target shape.** `seon.ctx/render-context`
and `seon.render/render` build a root renderable whose children are name-keyed
entries (`:seon.ctx/name`), presence-of-attr selects the surface, override is
by-name, and an injected `:seon.render/render` handle (render.cljs:659-660) is
already the recursive "slot" primitive. The refactor makes the **page ride that
same mechanism** instead of a parallel `:seon.tile/*` composer (which is dormant
— `:seon.tile/*` was never registered). One entry vector, one twin engine, one
recursive slot, two surfaces: **prompt** = concat `:ai` by priority; **page** =
a layout fn placing `:html` into slots.

## Entity / schema shape (all under existing namespaces, minimal)

- **ctx-entry** (`:seon.ctx/section`, relaxed): `:seon.ctx/name` (kw — the upsert
  id = DOM slot id `#tile-:name` = prompt section name), `:seon.ctx/priority`,
  `:seon.render/ai {:optional true}` (present → prompt section),
  `:seon.render/html {:optional true}` (present → page tile). **No `:kind`** —
  presence picks the surface. `:ai`-only = pure prompt section; `:html`-only =
  pure tile (zero prompt bloat); both = dual-surfaced. Per-agent vector
  `:seon.agent/sections` → **`:seon.agent/ctx`**.
- **layout** — NOT a new entity. A layout is just a ctx-entry whose
  `:seon.render/html` symbol returns hiccup containing `(slot …)` placeholders.
  "Layout vs component" is never stored — it's purely whether the render output
  contains child slots.
- **route** — `:seon.web.route/*` system-level rows. **HELD for reitit** (the
  hand-rolled table below is superseded by reitit's data routes — see note).
- **DROP** `:seon.tile/span` / `:seon.tile/console` / `:seon.tile/id` (dormant);
  `:seon.ui/region` (never existed; placement is the layout fn's CSS job).

## The slot primitive (uniform, recursive, content-free)

`(slot name)` → `[:div {:id (str "tile-" (sid name)) :data-slot (sid name)}]` —
an EMPTY placeholder. Identical whether `name` is a component or a nested
layout; it does not resolve `name`, just marks a hole. Resolution happens at
**expansion** — render the entry's `:seon.render/html`, check whether the output
itself contains further `(slot …)`. Generalizes the injected
`:seon.render/render` handle into a named, DB-keyed `(slot :name)`.

## Layout = (fn db → hiccup-of-slots)

`console-shell`'s hardcoded hero/rail grid → **`default-world-layout`** (a seeded
core entry symbol): query the agent's html-bearing ctx-entries, sort by
`:seon.ctx/priority`, emit `(slot name)` into a responsive CSS grid. Owns
placement + CSS only. Override = redefine its `:seon.render/html`. Owner's chosen
default: **a primary communication panel + the rest as a priority-sorted
scrollable list.**

## Reactive mechanism (how the SSE learns a dynamic slot-set) — the hard part

Per SSE **connection**, keep `!last-tree` (id→html) in **process memory** (not a
DB row). Each coalesced tx:
1. **`render-slot-tree`** — BFS from `:root` to fixpoint: render each slot's
   `:seon.render/html` (guarded), walk the hiccup for `:data-slot` child ids (a
   data walk, not regex), enqueue, repeat → ordered tree `id→{:html :children}`.
2. **Diff vs `!last-tree`:** *shape unchanged* → leaf `patch {id, html}` only for
   changed ids (client-deduped). *Shape changed under N* → ONE
   `patch {N, fully-expanded-html(N)}` inlining N's whole current subtree
   (children rendered in place) → atomic add/remove/move. *Open* → one
   fully-expanded `:root` patch.

This keeps **packetstar.js byte-unchanged** — collapsing a structure change into
one fully-expanded subtree avoids the blank-child bug. After first paint the
live DOM *is* the tree; placeholders are first-paint only.

## Nesting (system → world → app) — one recursive rule

`:root` places `(slot :agent-<id>)` + chrome → the agent's world entry places
`(slot :my-app)` → an app layout places `(slot :status)`. Override any level =
redefine that entry's html; the subtree re-expands next tx. **Routes make this
addressable** (a route's handler IS a layout) — **reitit-pending.** Interactive
elements POST to the unchanged `/call` (route table = the PAGE door; `/call` =
the ACTION door — one sandboxed-exec surface).

## Self-heal contract

Missing symbol → self-heal line (render.cljs:600-604, exists). Throwing component
→ error-card leaf, siblings/ancestors untouched, fix → re-expands next tx.
Throwing layout → guard; if `:root`, substitute `default-world-layout` + banner,
page never blanks. A pure layout's throw is smoke-reported by a derived
`:warnings` ctx section (pure fn of db, vanishes when fixed).

## Change-list (dependency-ordered; lanes marked)

- **Phase A — schema + entry foundation [R, atomic, first]:** `:seon.render/ai`
  → optional in `:seon.ctx/section`; rename `:seon.agent/sections` →
  `:seon.agent/ctx` (fix U read-sites same patch — this is #26); `add-section!`/
  `install!` accept html-only + batch upsert-by-name; expose ONE public pure
  `(fn [db agent-id] → merged priority-sorted ctx-entries)` (core ∪ agent).
- **Phase B — unified slot/layout engine:** [R] factor `resolve-slot`'s core
  lookup into an injectable seam (prompt uses `eval/lookup-value`; web uses a
  lean core table — compiler must stay out of the bundle); [U] NEW
  `seon.ui.layout` (`slot`/`sid`, `slot-ids` hiccup walker, `resolve-render`,
  `render-slot-tree` BFS, `default-world-layout`).
- **Phase C — page transport [U]:** `console-shell` → minimal shell (one
  `#tile-root`); `console-payload` → `console-patches` (diff vs per-conn
  `!last-tree`); retire `default-tiles`/`console-tiles`/`find-tile`/`render-tile`/
  `resolve-view`; port the 9 view fns to components; packetstar.js unchanged.
- **Phase D — R's composer demotion [R]:** `render-context-html` → debug-overlay
  section view only; `render-context-ai` stays the prompt producer; the page is
  now `default-world-layout`.
- **Phase E — routes-as-data:** **HELD for reitit** (was a hand-rolled
  `seon.web.router`; reitit replaces it — see note).

## The load-bearing convergence with R

**Shared contract:** ONE per-agent `:seon.agent/ctx` vector of dual-rendered
entries `{:seon.ctx/name :seon.ctx/priority :seon.render/ai? :seon.render/html?}`,
where `:seon.ctx/name` is the upsert id + prompt section name + DOM slot id, and
presence of `:ai`/`:html` (no `:kind`) selects surfaces. Both lanes resolve
through `seon.render`.

**The one change R makes:** R stops owning "the page." `render-context-html`
(today the html composer) is demoted to the debug section view; the page becomes
`default-world-layout` (a layout fn of db reading R's merged ctx-entries). **The 5
asks to R:** (1) `:seon.render/ai` optional; (2) rename → `:seon.agent/ctx`
(atomic); (3) `install!` accepts html-only + batch; (4) expose the merged-entries
pure query; (5) **R's derived tiles (status/todos/progress/toolkit/value-explorer/
commentary) become html-only ctx-entries the agent owns/sees/edits** — the crux
that makes the prompt-section set and the page-tile set ONE set.

## Open decisions (owner)

- **Fold R's derived tiles into `:seon.agent/ctx` (agent owns/sees/edits) or keep
  page-only?** The crux of unification. (Recommend: fold; html-only = zero prompt
  bloat.)
- **`:root` scope** — `/agent/<id>` is the agent's own `:root`, or a SYSTEM `:root`
  placing `(slot :agent-<id>)` per agent? (Tied to routing → reitit.)
- **Route auth scope** — does the gate scope READS, or only which fn runs? Can
  agent B's route render agent A's data? (→ reitit middleware.)
- **Sequencing** — land my.todo + purpose→`:my.agent/purpose` + the
  `:seon.agent/ctx` rename together (atomic) → relax schema + expose query → U
  reworks layout/slot/SSE → routes (reitit) last.
- **Primary-panel name** — stage / main / other.

## Risks

1. **Dynamic-slot streaming** is the genuinely hard part — the `!last-tree` +
   "shape change → one fully-expanded subtree" rule needs explicit tests
   (move+content-change in one tx; churny subtrees; coalesced flip-flop). Measure
   before adding `(slot-id, basis-t)` memoization.
2. **Capability surface** — agent route handlers + layouts MUST go through
   `render-sci/invoke-bounded` (deadline-budgeted), never `lookup-value`-direct,
   or a runaway freezes the single-threaded pod. Keep routes read-only + own-fns
   only for v1.
3. **Web-bundle bloat** — the injectable core-lookup seam must keep the bootstrap
   compiler out of the web bundle (the thing tile.cljs:324-338 avoids).
4. **Two render backends drifting** — derive the web core table from the same
   registry the prompt uses; test both resolve core symbols identically.
5. **Atomic cross-lane rename** — `:seon.agent/sections`→`:seon.agent/ctx` +
   `:ai`-optional + my.todo/purpose all in one window across a shared tree;
   sequence as ONE unit (rename → fresh `bin/test-cljs` → atomic commit).
6. **Prompt cache prefix** — the `stable-boundary` at priority≤20 for provider
   prefix caching must survive unification (page ignores it; prompt keeps it).

## Routing — reitit-pending

Owner direction (2026-06-27): **reitit** (data-driven routing, vendored in
`reference-code/`) is the keystone for routes — it's data-based, handles
params/reverse-routing/middleware (auth-later), and likely expresses **nested
layouts** via nested route data. **Plan: set up reitit first, THEN deep-dive its
source with our specific problems** (route table from the DB, agent-authored
routes wired to `my.*` namespaces capability-gated like `/call`, nested
route→layout composition, auth-via-middleware). The hand-rolled `seon.web.router`
in the change-list above is SUPERSEDED by reitit. Investigation in flight.
