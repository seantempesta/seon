---
type: research
status: draft
tags: [research, agent]
---

# `my.tile` — best out-of-the-box implementation (research)

Agent-facing tool: **`my.tile`** — "set your live tile to a prebuilt VIEW
rendered with DATA" (a card / pros-cons / recommendation the human watches).
Catalog entry: `docs/prds/agent-fsm/toolkit-catalog.md` (NEW lean facade, floor
= `seon.render.live-tile` + `seon.ui.components`, `:toolkit-seed`/editable, budget
~1k tok). Sibling design: `docs/prds/agent-fsm/ux-toolkit-proposal.md` (the
Layer-1/Layer-2 split). DESIGN-ONLY: this note specifies the agent-facing call
shape; it changes no source.

## TL;DR

- **The hard part is already free.** "Cross-lane with the UI session" needs NO
  new transport. The agent writes ONE datom — `:seon.render.live-tile/content`
  on its own agent entity — via the protected `seon.db` floor (forwards to
  `wire-server`); the UI process (`seon.web.tile` / `seon.render`) reads that
  attr and renders it. That is the DB-as-bus, fully built and tested. `my.tile`
  invents no cross-lane mechanism — it reuses the live-tile write path that
  `seon.render.live-tile/wiring-source` already exercises at agent creation.
- **No npm module is warranted.** The capability is "named view + data → hiccup,
  validated per view." In a CLJS/Node, render-to-string, non-React system the
  idiomatic implementation is plain Clojure core + Malli (both already present) —
  not a JS dependency.
- **One external library is genuinely on-point and validating, not adoptable:
  Replicant** (`cjohansen/replicant`). Its **aliases** feature (`[:ui/btn attrs
  children]` → hiccup resolved at render time, data-not-functions, works under
  `replicant.string` SSR on CLJS, zero deps) is the canonical CLJS expression of
  exactly the "prebuilt view key + data" idea. But seon already has (a) its own
  XSS-safe hiccup→string renderer (`seon.ui.html`, written deliberately because
  the JVM hiccup libs are JVM-only and `teropa/hiccups` is unmaintained/unsafe),
  and (b) an alias-equivalent already: `:seon.render.live-tile/content` may be a
  **symbol late-resolved every render via `seon.eval/lookup-value`** — the same
  late-bound, data-driven view resolution Replicant aliases provide. Adopting
  Replicant would mean either swapping a load-bearing tested renderer or running
  two hiccup renderers (a "don't be a dumbass" violation). **Verdict: let
  Replicant aliases INSPIRE the API (`:seon.tile/view` keyword = seon's alias
  tag), do not take the dependency.**
- **Recommendation: HYBRID.** thin-wrap-existing-seon for the write path +
  cross-lane + the hiccup factories (`seon.render.live-tile`, `seon.db`,
  `seon.ui.components`); build-fresh ONLY a tiny dispatch+validation layer — a
  data registry `view → component-symbol` resolved through the existing
  `seon.eval/lookup-value`, plus a **Malli `:multi`** schema dispatched on
  `:seon.tile/view` that validates `:seon.tile/data` per view (errors-as-values).

## What the capability actually is (and isn't)

Decomposed, `my.tile` is four concerns. Three are already solved by the
substrate; only the fourth is new code:

| Concern | Status | Where |
|---|---|---|
| Cross-lane delivery (agent → UI session) | BUILT | `seon.db` write → `:seon.render.live-tile/content` → `seon.web.tile`/`seon.render` read (DB-as-bus) |
| Hiccup → HTML string (the render boundary) | BUILT | `seon.ui.html/->string` (XSS-safe, zero-dep, `.cljc`) |
| Late-bound dynamic view (symbol re-derives each render) | BUILT | `:seon.render.live-tile/content` symbol arm, resolved via `seon.eval/lookup-value` |
| Named-view registry + per-view DATA validation + the lean verb | **NEW** | `my.tile` (this design) over `seon.ui.components` (Layer-1, partly unbuilt) |

The agent-facing surface writes nothing new to schema: it reuses
`:seon.render.live-tile/content [:or :symbol ::hiccup]`. A keyword view builds
**literal hiccup eagerly** (a snapshot; bypasses SCI) and transacts it; a symbol
view transacts the **symbol** (late-resolved each render — the agent's own
dynamic fn). Both arms already exist in `::content`.

## Options compared (the "named view + data → hiccup" layer)

### Option A — Replicant (`replicant.string` + aliases) — wrap-lib

- **Fit (concept):** excellent. Aliases ARE "view-key + data → hiccup, late, as
  data." `replicant.string/render` runs on CLJS (JS-array string builder), zero
  deps, no React/DOM. `(defalias pros-cons [attrs children] …)` registers a
  namespaced tag; `[:ui/pros-cons {…}]` resolves at render via the registry.
- **Fit (this codebase):** poor as a dependency. seon already renders hiccup
  (`seon.ui.html`) and already has alias-equivalent late symbol resolution.
  Taking Replicant means a second renderer OR ripping out a tested one — net
  negative for a ~1k-tok facade. Its IDEAS are already independently in the
  design, which is the real signal here.
- **Verdict:** inspire, don't adopt. Treat `:seon.tile/view` as seon's alias tag.

### Option B — Clojure core multimethod (`defmulti`/`defmethod`) — build-fresh

- Classic open dispatch table: `(defmulti render-view :seon.tile/view)`,
  `(defmethod render-view :pros-cons [m] …)`. Open for extension, idiomatic.
- **Against, here:** the dispatch table is a hidden mutable global — awkward
  against seon's code-as-data loop (agents upsert fns; a multimethod's method
  table isn't a `:seon.fn`/`:seon.schema` datom and isn't introspectable for a
  discovery verb). It also duplicates resolution: seon already resolves
  view-symbols through ONE path (`lookup-value`); a multimethod adds a second.

### Option C — data registry map `{view → component-symbol}` resolved via `lookup-value` — build-fresh (RECOMMENDED core)

- A registered `def` map `:card → 'seon.ui.components/md-card`, `:pros-cons →
  'seon.ui.components/pros-cons`, `:recommend → 'seon.ui.components/decision-summary`.
  Resolution reuses `seon.eval/lookup-value` (the SAME resolver the live-tile
  symbol arm uses — one resolution path, code-as-data).
- **For:** the registry is DATA (introspectable → powers a `views` discovery
  verb the agent can read); it lives in the editable `my.tile` ns, so an agent
  adds a curated view by upserting the map + defining the component fn
  (build-your-environment); ad-hoc views need no registry edit at all — pass a
  bare symbol (`:seon.tile/view 'my.x/my-view`), already a valid `::content` arm.
- This is Option B's openness without the hidden mutable table, and reuses the
  one resolver. It is the seon-native equivalent of Replicant's alias registry.

### Validation layer — Malli `:multi` on `:seon.tile/view` (with all three)

`:seon.tile/data` is validated per view by a `[:multi {:dispatch :seon.tile/view}
[:card ::card-data] [:pros-cons ::pros-cons-data] …]`. Malli is already the
system's validator + already instruments every public fn. On a bad data map the
verb returns the standard `:seon.error/*` envelope with `:seon.error/kind
:user-input` so the agent fixes its args rather than the system throwing. This is
the right validation layer and needs no new dependency.

## Recommendation — HYBRID (thin-wrap-existing-seon + a small build-fresh core)

- **thin-wrap-existing-seon:** the effectful write + cross-lane is a transact onto
  `:seon.render.live-tile/content` (the `seon.render.live-tile` wiring move), the
  hiccup comes from `seon.ui.components` Layer-1 factories, the render/string +
  text-twin + the per-turn awareness section are all the existing live-tile path
  (`seon.render/render-agent-tile`, `seon.ctx.live-tile`). `my.tile` adds ZERO
  transport, ZERO renderer.
- **build-fresh (small):** the `view → component-symbol` registry (Option C),
  the Malli `:multi` data schema, and the four lean verbs. ~1k tok, editable
  (`:toolkit-seed`).
- **Convergence flag (don't-be-a-dumbass):** `ux-toolkit-proposal.md` sketches the
  SAME effectful verbs under `seon.agent.ui` (`show-card!`/`explain-pros-cons!`/
  `recommend!`). The toolkit-catalog's `my.tile` (`show!`/`card!`/`pros-cons!`/
  `recommend!`) is the newer agent-owned framing and SHOULD be the one built; do
  not ship both a `seon.agent.ui` and a `my.tile` doing the same thing. The
  Layer-1 `seon.ui.components` (shared cljc) stays the hiccup floor for either.

## Agent-facing API (map-in / map-out, threaded)

All verbs `^:async` (the transact forwards to `wire-server`). Default target is
`(seon.db/current-agent-id)`'s own entity (a `:seon.db/ref` lookup-ref); an
optional `:seon.db/ref` could retarget, but self is the default and the only
shape the catalog needs.

```clojure
;; --- shared shapes (register once; :seon.ui/* is the Layer-1 vocabulary) ---
(schema/register! :seon.tile/view [:or :keyword :symbol]) ; prebuilt key OR your own fn sym
(schema/register! :seon.tile/data :map)                   ; the view's :seon.ui/* payload
;; per-view data shapes (the Malli :multi arms) — owned with the components:
;;   ::card-data      {:seon.ui/title :seon.ui/body [:seon.ui/tone {:optional true}]}
;;   ::pros-cons-data {:seon.ui/title :seon.ui/pros [..] :seon.ui/cons [..]}
;;   ::recommend-data {:seon.ui/recommendation :seon.ui/rationale :seon.ui/options [..]}

;; --- the ONE build path (pure, testable, no effect) ---
(defn preview
  "Build (don't show) — resolve the view, validate :seon.tile/data, return the
   tile content + text twin WITHOUT transacting. The pure core show! calls."
  ;; {:seon.tile/view :pros-cons :seon.tile/data {…}}
  ;; -> {:seon.tile/ok? true
  ;;     :seon.render.live-tile/content [hiccup…]   ; literal (kw) or the symbol
  ;;     :seon.render/ai "what your human would see"}
  ;;  | {:seon.tile/ok? false :seon.error/* {…:kind :user-input}}
  )

;; --- the generic effectful verb ---
(defn ^:async show!
  "Set your live tile to VIEW rendered with DATA. Keyword view → eager literal
   hiccup (snapshot, bypasses SCI); symbol view → store the symbol (re-derives
   every render). Transacts onto :seon.render.live-tile/content on your entity."
  ;; {:seon.tile/view <kw|sym> :seon.tile/data {…}?}
  ;; -> {:seon.tile/ok? true
  ;;     :seon.render.live-tile/content <hiccup|sym>   ; what got wired
  ;;     :seon.render/ai "what your human now sees"}
  ;;  | {:seon.tile/ok? false :seon.error/* {…}}
  )

;; --- sugar: each is show! with the view pre-filled (ONE mechanism) ---
(defn ^:async card!      [m] #_"(show! {:seon.tile/view :card      :seon.tile/data m})")
(defn ^:async pros-cons! [m] #_"(show! {:seon.tile/view :pros-cons :seon.tile/data m})")
(defn ^:async recommend! [m] #_"(show! {:seon.tile/view :recommend :seon.tile/data m})")

;; --- discovery (pure read; ITEMS mixin) ---
(defn views
  "List the prebuilt views and their data shapes — so the agent can pick one
   without reading source."
  ;; {} -> {:seon.tile/ok? true
  ;;        :seon.items/items [{:seon.tile/view :card :seon.tile/data-schema …} …]
  ;;        :seon.items/count 3}
  )
```

### Why the outputs thread into the inputs

- **map-in:** `:seon.tile/data` is "plain namespaced data the agent already has"
  — a `db/query` row, a `db/store-inventory` row, a `my.search/grep` located map.
  The verb maps over that with no reshape at the arrow (the register-once
  `:seon.ui/*` shapes are what a query projects into).
- **map-out (RESULT):** every verb returns the never-throw envelope keyed
  `:seon.tile/ok?` (→ shared `:seon.result/ok?`), `:seon.error/*` on failure.
- **out re-enters the read contract:** `show!`/`preview` return
  `:seon.render.live-tile/content` (the exact attr the live-tile reader consumes)
  and `:seon.render/ai` (the exact twin the `:live-tile` context section shows).
  So the verb's output is a valid live-tile value AND tells the agent what the
  human now sees this turn — no waiting for next turn's context to confirm.

### The worked chain (no rekey at any arrow)

```clojure
;; query the store → show the top recommendation as a pros/cons tile
(->> (db/query {:seon.db/db (db/db) :seon.db/query '[…]})  ; rows of :seon.ui/* data
     first                                                  ; one {:seon.ui/title …}
     (hash-map :seon.tile/data)                             ; wrap as the data slot
     (merge {:seon.tile/view :pros-cons})
     show!)                                                 ; -> {ok? content ai}
;; the result's :seon.render/ai is the twin; the human's panel already updated.
```

## Gotchas / dependencies

- **Layer-1 components are partly unbuilt.** `seon.ui.components` today has
  `card`, `status-dot`, tables, `log-line`, `empty-state`, buttons — but NOT
  `md-card`/`pros-cons`/`decision-summary` (the three the catalog/`ux-toolkit`
  name). And **no `seon.ui` schema ns exists yet** — `:seon.ui/*` (title/body/
  tone/pros/cons/recommendation/rationale/options) is unregistered. `my.tile` is
  the Layer-2 facade; it is BLOCKED on the Layer-1 components + the `:seon.ui/*`
  vocabulary (the U/R lane split in `ux-toolkit-proposal.md`). Build order:
  shapes → components → `my.tile`.
- **Eager vs late is a real semantic split the agent must understand.** A keyword
  view snapshots data at call time (literal hiccup, goes stale, but bypasses SCI
  and survives pod restart as a durable datom); a symbol view re-derives every
  render (live, but the agent's fn must exist + is SCI-bounded). The docstring
  must say "for changing data, point at a fn (symbol); for a one-shot snapshot,
  use a keyword view." This mirrors the live-tile ns's "tile updates should be
  RENDERED DATABASE QUERIES" guidance — prefer symbol/fn views for anything that
  changes.
- **Literal-hiccup splice rule.** `seon.render.live-tile/valid-hiccup?` rejects a
  bare lazy `for` child; Layer-1 components must `into` their seq children (the
  same constraint `ux-toolkit-proposal.md` §1 calls out for `md/inline`). The
  serializer-faithful `hiccup-structure-error` will already degrade a broken tile
  to a legible error, but the components should emit clean hiccup.
- **Validation kind matters.** Bad `:seon.tile/data` must come back as
  `:seon.error/kind :user-input` (fix-my-args), distinct from a `:core-bug`, per
  the RESULT shape — a plain error string can't carry that signal.
- **Markdown bodies.** Per `ux-toolkit-proposal.md`, card/rationale bodies render
  agent free-text through `seon.ui.markdown/md->hiccup` by default — `my.tile`
  inherits that path; it should not re-implement text rendering.
- **Don't fork the renderer or the transport.** No second hiccup renderer (we have
  `seon.ui.html`), no new cross-lane channel (we have the DB-as-bus). If a JVM
  hot path ever needs SSR hiccup, `seon.ui.html` is `.cljc` and Replicant's
  `replicant.string` is the documented fallback — but that's a JVM-perf decision,
  not a `my.tile` one.

## Composability alignment (PATH / REF / ITEMS / RESULT / map-in-out)

- **RESULT:** every verb → `{:seon.tile/ok? … | :seon.error/* …}` (never throws;
  references shared `:seon.result/ok?` + `:seon.error/*`). ✓
- **ITEMS:** `views` → the `:seon.items/*` mixin (self-describing view rows). ✓
- **REF:** the tile targets the agent's own entity by `:seon.agent/id` lookup-ref
  (the REF shape); default self, optional `:seon.db/ref` retarget. ✓
- **PATH:** not file-addressed; threads in as `:seon.tile/data` from `my.search`
  located items / `db/query` rows (no rekey). ✓
- **map-in/map-out:** one namespaced map in, one envelope out; `show!`'s out is a
  valid live-tile value + the human-facing twin, closing the loop with the
  read contract. ✓

## Sources

- Replicant aliases — named-tag-as-data, SSR string support:
  <https://replicant.fun/alias/>
- Replicant string rendering (CLJS + JVM, zero deps, no React/DOM):
  <https://github.com/cjohansen/replicant>
- Replicant overview / hiccup-to-string philosophy: <https://replicant.fun/>
- Clojure multimethods (open dispatch) reference:
  <https://clojure.org/reference/multimethods>
- Plumatic eng-practices, data representation / `:type` map dispatch vs
  multimethods:
  <https://github.com/plumatic/eng-practices/blob/master/clojure/20130926-data-representation.md>
- re-frame view/handler registration (view-key precedent):
  <https://cljdoc.org/d/re-frame/re-frame/0.10.7/doc/introduction/first-code-walk-through>
- In-repo: `src/seon/render/live_tile.cljs` (the write path + `::content` shape +
  `valid-hiccup?`), `src/seon/ctx/live_tile.cljs` (the per-turn text twin),
  `src/seon/ui/components.cljc` (Layer-1 hiccup floor), `src/seon/ui/html.cljc`
  (the renderer + the "why we don't use a library" rationale),
  `src/seon/web/tile.cljs` (the UI-lane reader / DB-as-bus consumer),
  `docs/prds/agent-fsm/toolkit-catalog.md` + `ux-toolkit-proposal.md` (specs).
