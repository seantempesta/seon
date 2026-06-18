---
type: issue
status: active
tags: [issue, agent, flow]
---

# Context budget — fresh-reset at 55k, lean the fn-heads to land under 50k

## Problem

A freshly-reset cluster's turn-0 agent context measures **~55,362 tokens**
(221,451 chars; system estimator = 4.0 chars/token). The target is **≤50k
tokens** with headroom for the agent's OWN growing code on top. So the
baseline is already over budget before the agent writes anything.

The `<namespaces>` section is **85% of the context (47,290 tokens)** — and
that is BY DESIGN (namespaces are supposed to be the majority). The issue
is the absolute size, not the proportion.

## What's already done (committed, green)

Commit `3b86859` on `feature/overridable-substrate`:

- `namespaces-section` (`src/seon/ctx.cljs`) now compact-renders EVERY
  included (non-internal) namespace. Removed the `(when-not seed? …)` guard
  that omitted the compiled core entirely — `seon.db`/`seon.eval`/`seon.ctx`
  were invisible on a fresh world. Agent's own current ns stays full;
  everything else compact; `*.internal` excluded.
- Removed the `full-source-roots` exemplar relic (residue of the dead
  `<exemplars>` context section) so `seon.agent.search`/`todo` render
  uniform, not forced-full.
- 533 tests / 2364 assertions / 0 failures.

This made the core VISIBLE (the bug) but surfaced the budget overage (this
issue).

## Measurements (live, fresh reset, agent `rZj-2606172110`)

Per-section (tokens):

| section | tokens |
|---|---|
| namespaces | 47,290 |
| transcript | 5,618 |
| system | 1,373 |
| live-tile | 405 |
| inventory | 252 |
| warnings | 185 |
| your-entity | 163 |
| prompt | 41 |
| **total** | **55,362** |

Namespaces section composition (47,290 tok, 56 tags, 0 stubs):

- **fn defn-heads: ~30,637 tok (65%)** — 199 elided heads, each =
  signature + docstring + the full `:malli/schema` attr-map.
- **schema `register!` forms: ~11,318 tok (24%)** — 401 schemas, rendered
  full.
- remainder (~5k tok): ns forms, blank lines, tags.

Biggest namespaces (tokens): `seon.ctx` 5,354 · `seon.db` 4,619 ·
`seon.test.runner` 3,329 · `seon.agent.fs` 2,564 · `seon.render.live-tile`
2,547 · `seon.eval` 2,081 · `seon.ai` 1,975 · `seon.warn` 1,869.

## Root finding

The cost is NOT the schemas (only 24%). It's the **fn-heads (65%)**, and
there is **contract duplication**: every elided fn-head inlines its
`:malli/schema` attr-map, AND the registered attrs render AGAIN as
`(seon.schema/register! …)` schema rows. The same contract is in the prompt
twice.

## Recommendations (pick a lever — fn-head first)

1. **Drop the inline `:malli/schema` attr-map from the compact fn-head**
   (recommended). Keep `(sym arglists) — docstring`; let the `:seon.schema`
   rows be the single source of the attribute contracts. Kills the
   duplication and most of the bulk. Projection: namespaces ~47k → low-30s,
   total well under 50k. Trade-off: the fn's `:=>` signature schema is no
   longer inline (map-in/map-out request/response shapes ARE still visible
   as schema rows; named-positional `:catn` slots may not be).
2. **Also truncate docstrings to first line/sentence** — maximal cut, most
   headroom. Trade-off: loses the fuller why/how prose on core fns (still
   readable on demand via `render-namespace`).
3. **Lean schemas only** (lowest value) — collapse the `register!` forms
   (~11k). Lands ~44k (still tight), keeps full per-fn contracts inline.

Could also combine 1+2.

## Where to make the change

- `src/seon/ctx.cljs`:
  - `elide-defn-body` (~line 1000) — the fn-head renderer; this is where
    the `:malli/schema` attr-map is included. Dropping/trimming the
    attr-map here is the primary lever.
  - `compact-ns-source` (~line 1059) — assembles ns form + schemas (full) +
    elided fns. The schema-leaning lever lives here.
  - `namespaces-section` (~line 1110) — the per-ns render `cond` (already
    uniform-compact after `3b86859`).
- `fn-source-inline-threshold` (~line 1252) and `render-namespace`
  (~line 1297) — the on-demand full-render path (signature + doc by
  default, full source for small fns) is the fallback the agent uses to
  read a fn's complete source when the compact head isn't enough.

## How to re-measure

After a `bin/seon cluster reset default` and pod boot (note the minted
agent id from `logs/pod.log`), eval against the live pod:

```clojure
(require '[seon.db :as db] '[seon.db.internal :as internal] '[seon.ctx :as ctx])
(def _db @(internal/resolve-conn db/*conn*))
(let [r (ctx/assemble-context {:seon.db/db _db :seon.agent/id "<minted-id>"})]
  {:total (count (:seon.render/text r))
   :tok   (:seon.render/token-estimate r)
   :secs  (->> (:seon.render/section-texts r)
               (map (fn [s] [(name (:seon.ctx/name s)) (count (:seon.render/text s))]))
               (sort-by (comp - second)) vec)})
```

## Related / deferred

- The `get-else`-on-uninstalled-attr trap recurred in `compact-ns-source`
  (fixed in commit `a86c139`). Worth a `warn.cljs` lint rule: ban
  `get-else` in `seon.db/query`, use a separate join + code-side lookup.
  (Third sighting of this trap.)
- The new `:inventory` context section (cheap data-discovery surface,
  priority 97) landed in `a86c139` and renders fine (~250 tok on a fresh
  reset).
