---
type: research
status: complete
tags: [research, schema, web, architecture]
---

# Schema-aware data inspector — design research

> Superseded detail: the later adversarial correction in
> [[universal-data-browser-design-2026-07-20]] replaces this report's single
> presence matcher with one derived index serving `candidate-shapes` for
> diagnostics and validated `matching-shapes` for custom-render dispatch. In
> particular, a missing-required-key value remains a diagnostic candidate but
> never selects that schema's renderer.

Owner ask (2026-07-20, vocabulary-unification §Owner rulings item 1): ONE
component that detects which registered Malli schemas are present in a
structure (a structure may satisfy several), renders the value with that
understanding, and is used everywhere a value is shown to a person — replacing
the ad-hoc "panel" drill-down and preventing future parallel value-rendering
paths.

Conclusion up front: **the mechanism already exists and is
`seon.render.value` + `seon.render/block`; the missing piece is only the
value→registered-schema reverse lookup and its surfacing.** Strengthen those
two owners in place. Entity-side reverse lookup already exists
(`seon.render/entity-primary-schema`); it must be generalized from "entity
catalog rows" to "all registered closed map shapes" and its result attached to
the one `render-html-data`/`render-ai` projection both views consume. No new
namespace, no "inspector" noun, no second walker.

## 1. Dependency ledger

| Dependency | Where grounded |
|---|---|
| Malli validators/explainers/parsers + per-schema cache | `reference-code/malli/src/malli/core.cljc:2627-2682` (validator/validate/explainer/explain/parser/parse), `:353-361` (`-cached` — compiled fns cached on the schema object under `:validator`/`:explainer`/`:parser`) |
| Malli walker for refs | `reference-code/malli/src/malli/core.cljc` `m/walk`; already used by `seon.schema/direct-references*` (`src/seon/schema.cljc:27-45`) |
| Seon schema registry + projection | `src/seon/schema.cljc` — candidate forms atom (`:94-130`), `build-projection` (`:292-374`, incl. the entity **catalog** with `:seon.schema.catalog/required-attrs`/`id-attr`/`render-ai`/`render-html`), `candidate-validator`/`candidate-explainer` (`:536-550`, uses `m/deref-recursive`) |
| The one render walker + typed-block renderer | `src/seon/render.cljs` — `render` (`:754`), `block` (`:555-605`), `entity-primary-schema` (`:199-224`), `data-panel` (`:522-533`) |
| The value sampler/skeleton | `src/seon/render/value.cljs` — `sample`, `render-ai`, `render-html-data` (`:716-741`), marker vocabulary (ns docstring `:40-59`) |
| datafy/nav (CLJS) | `reference-code/clojurescript/src/main/cljs/clojure/datafy.cljs`, `clojure/core/protocols.cljs` |
| Reveal predicate→action registry (prior art) | `reference-code/reveal/src/vlaaad/reveal/action.clj:14-50` (`*registry` = `{id check-fn}`; `collect` runs every check over `[value annotation]`, guarded) |
| Portal viewer registry | **NOT vendored** — comparison below is from memory, FLAGGED UNGROUNDED |

## 2. Current value-rendering inventory (every place a person sees a value)

1. **`src/seon/render/value.cljs`** — the owner. `sample` builds a depth/
   breadth-bounded skeleton with real keys/indices; `render-ai` emits agent
   text; `render-html-data` emits the plain-data contract
   (`:seon.render.value/tree`/`summary`/`truncated?`/`eval-id`). Marker
   vocabulary (`::kind`/`::shown`/`::elided`/`::pruned`/`::string-len`,
   `:seon.eval/opaque|datom`) is shared by emitter and HTML view. No schema
   awareness anywhere in this file — `summary` is only "map 12 keys".
2. **`src/seon/render.cljs` `block`** (`:555`) — THE typed-block dispatch
   (markdown / source / data-projection / error / hiccup / else→data). Its
   `data-panel` (`:522`) + `value-node`/`map-node`/`seqish-node` render the
   drill-down as `<details>`. This is the thing informally called "the
   panel". Callers: `src/seon/handlers/eval.cljs:210,213,222`,
   `src/seon/handlers/message.cljs:122`,
   `src/seon/agent/ctx/transcript.cljs:1315`.
3. **`src/seon/render.cljs` `render`/`resolve-render`** (`:732-794`) — the
   one guarded walker; step 4 is the schema-default renderer via
   `entity-primary-schema`: attribute-presence subset test against the
   entity catalog, most-required-attrs wins, alphabetical tie-break. **This
   IS a working value→schema reverse lookup — but only for catalog entities
   (`:seon.db/entity` maps with an id-attr), and it returns only the single
   primary row, discarding the other matches.**
4. **`generic-default-renderer`** (`render.cljs:691-707`) — the no-match
   fallback: `pprint` dump (ai: `;; <id>` header; html: `[:pre]`). Does NOT
   route through `render.value`'s bounded sampler — an unbounded pprint of a
   huge node is a latent cost/blow-up and a second (degenerate) value path.
5. **`src/seon/web/debug.cljs` `/data`** (`data-element:157-168`) — raw
   `(pr-str (:datahike.index-page/datoms page))` into a `[:pre]`. The live
   database browser bypasses the sampler, the drill-down, and token sizing
   entirely. Clearest migration target.
6. **`src/seon/web/debug.cljs` `/agent/{id}/debug`** — renders the exact
   prompt TEXT (`:seon.render/text preview`) — a string surface, correctly
   not a value drill-down; out of scope.
7. **`src/seon/handlers/eval.cljs` result card** (`:210-225`) — the stored
   `:seon.eval/result-edn` STRING rendered as a highlighted source block,
   not as a data drill-down. The interactive tree only appears when a caller
   hands `block` a live `render-html-data` projection; the transcript's eval
   rows show a string. A person inspecting a result gets syntax-highlighted
   EDN, not the collapsible skeleton — a soft fork of the value view.
8. **`src/my/plan/internal.cljs`** — a bespoke plan tree with per-step
   "detail panels" (`:1831,1940,1970`) — a legitimate *specialized* renderer
   for one shape (exactly what schema-dispatch should select), plus
   `pr-str` inside error strings (fine — messages, not value views).
9. **`src/seon/ui/clojure.cljs`** — source highlighting, not value
   rendering; a leaf renderer `block` dispatches to. No change.
10. **`src/seon/ui/agent_view.cljs` `primary-panel`** (`:53`) — layout slot
    naming only ("panel" as CSS/layout, allowed per vocabulary table "card
    for CSS only"; rename ride-along candidate).

So: one real owner pair (value.cljs + block), plus three leaks —
`generic-default-renderer`'s pprint, `/data`'s pr-str, and eval-result-as-
source-string.

## 3. Registry / reverse-lookup findings

- `schema/registered-schemas` returns the full `{k form}` map
  (`schema.cljc:492-496`); `entity-catalog` (`:409-418`) is the derived
  subset for `:seon.db/entity` maps and already carries
  `:seon.schema.catalog/required-attrs` — the exact ingredient the presence
  prefilter needs.
- **No value→schema reverse lookup exists for non-entity shapes.** Nothing
  in the tree asks "which registered VALUE schemas does this map satisfy" —
  `entity-primary-schema` is the only matcher and it is catalog-scoped and
  single-winner.
- The projection has a `fingerprint` (`schema.cljc:361-365`) — the natural
  cache key for compiled validators (derive-don't-store: a memo keyed on the
  immutable projection's fingerprint is a measured-expensive derived cache,
  invalidated for free when a new projection is activated).
- For DATABASE entities the "schemas present" question is already answered
  the right way — attributes + connections, `:seon.entity/id-attr` marks
  identity, and required-attr subset testing is the match. The generalized
  matcher must keep this exact semantic and merely stop throwing away the
  non-primary matches.

## 4. Malli matching design + cost

Grounded facts (`malli/core.cljc`):

- `m/validate` compiles a validator **every call** (`:2635-2641` docstring
  says so explicitly); `m/validator` caches the compiled fn on the schema
  object (`-cached`, `:353-361`). Correct usage: compile once per
  (projection, schema-key), reuse. Seon already has the right entry point:
  `schema/candidate-validator` (`schema.cljc:536`) — but it recompiles via
  `deref-recursive` per call; the memo layer goes around it.
- Malli has **no multi-schema matcher**: `:orn`/`m/parse` tag which branch
  of ONE union matched, but there is no "which of these 400 registry keys
  does this value satisfy" API. Detection = run candidate validators.
- Cost control is therefore structural prefilter + bounded confirm:
  1. **Candidate set**: only registered `:map` shapes with at least one
     required key are detectable ("schemas present in a structure" is a
     map-shape question; scalars/enums are attribute-level, not
     value-level). Derive once per projection: `{required-key → #{schema-k}}`
     inverted index.
  2. **Prefilter**: for a map node, `(set (keys m))` ∩ index → candidate
     schemas whose required keys are ALL present (the existing
     `entity-primary-schema` subset test, generalized). Pure set ops, no
     validation. For a fully-namespaced-keys codebase this prunes ~everything.
  3. **Confirm** (optional, config-dialed): run the memoized validator for
     each surviving candidate to distinguish "keys present" from "keys
     present AND values valid". Cost is O(size of value) per survivor —
     but the inspector runs it on the **already-bounded sample skeleton's
     source node**, and only at the TOP level plus nodes the person drills,
     never across the whole walk. Default: presence-match only (matches
     today's entity dispatch, zero validation cost); validity shown as a
     badge state when confirm is on (`✓ valid` / `~ shape` per matched key).
  4. **Never on the hot ai path per node**: `render-ai` gets one top-level
     detection (it already computes `top-type+size` there); the per-node
     annotation is html-view-only.

Rejected: validating every registered schema against every rendered node
(O(schemas × nodes) — exactly the render-cost bug class ui.md warns about),
and storing detection results as datoms (renders are never stored).

## 5. Prior art

- **Reveal (vendored)** — `vlaaad.reveal.action/collect`
  (`action.clj:37-50`): a registry of `{id check-fn}`; every check runs
  guarded (`try`) over `[value annotation]`; non-nil ⇒ the action applies.
  Same shape as portal's viewer `:predicate`. Lesson: selection = run
  predicates over the value, guarded, cheap predicates first. Seon's
  improvement over both: the predicates need not be opaque fns — registered
  Malli shapes ARE the predicates, so selection is derivable from the
  registry with no second hand-maintained viewer list (house rule: no
  hand-maintained lists).
- **datafy/nav (vendored, CLJS)** — `clojure/datafy.cljs`: `datafy`
  projects an object to data (recording the original under
  `::datafy/obj` meta); `nav coll k v` re-contextualizes a child before
  drilling. Maps exactly onto what value.cljs already does: `project-plain`/
  `opaque-marker` IS Seon's datafy (opaque handles → data), and the
  skeleton's preserved keys/indices + `(get-in result/<id> path)` re-sample
  IS Seon's nav. Recommendation: do NOT adopt the protocols themselves (a
  second projection authority next to the marker vocabulary); keep the
  existing marker contract, which is already errors-as-values and
  wire-serializable — datafy/nav protocols are process-local object
  machinery, wrong for a projection that ships over SSE.
- **Portal — UNGROUNDED (not in reference-code/)**: from memory, portal
  registers viewers with `:predicate` + `:component` and picks the default
  viewer as the last-registered matching predicate, with the person able to
  switch among all matching viewers. The "show all matches, one primary"
  composition below borrows that; treat details as unverified.

## 6. Recommended design — strengthen the existing owners in place

No new mechanism. Three in-place changes, one shared matcher.

### 6.1 The matcher lives in `seon.schema` (it is a registry projection)

Add to `build-projection` (alongside the existing catalog derivation,
`schema.cljc:339-360`): a **shape index** over ALL registered closed `:map`
forms — `{:seon.schema.projection/shape-index {required-attr #{schema-key}}}`
plus per-key required-attr sets. Entity catalog rows are a subset of this
index (they additionally carry id-attr + renderer symbols). One public fn:

```clojure
(schema/matching-shapes value)   ; → [{:seon.schema/key k
                                 ;     :seon.schema/required-attrs #{…}
                                 ;     :seon.schema/entity? bool
                                 ;     :seon.schema/valid? bool-or-absent}]

```

sorted by specificity (required-attr count desc, key alphabetical — the
`entity-primary-schema` rule, verbatim). `entity-primary-schema` in
render.cljs then becomes `(first (filter entity? (schema/matching-shapes …)))`
— the existing single-winner dispatch is unchanged behavior, now derived from
the one matcher instead of a private copy. Validator memo: an ordinary
process-local cache atom keyed `[fingerprint schema-key]` (compiler-state-
class atom, allowed), populated lazily via `m/validator` against the
projection registry.

### 6.2 `seon.render.value` carries the detection in its one projection

`render-html-data` (value.cljs:716) gains one key:

```clojure
{:seon.render.value/schemas [<matching-shapes rows for the TOP value>]
 …existing keys…}

```

and, per **map node in the skeleton** (computed during `sample*`'s map
branch, presence-prefilter only — set ops on keys already in hand, no
validation), an annotation key `:seon.render.value/node-schemas [k …]` on
nodes with ≥1 match. `summary` becomes schema-aware: `":seon.eval — map 12
keys"` instead of `"map 12 keys"`. `render-ai`'s drill hint (value.cljs:663)
includes the top match: `‹partial view of map 34 keys — :seon.agent.turn›` —
so **agents get the same schema awareness** at ~5 tokens, and can jump
straight to `(schema/schema-definition :seon.agent.turn)`.

### 6.3 `seon.render/block`'s data view renders the matches

`data-panel` (render.cljs:522) header row: primary schema key as the
leading label, remaining matches as small badges (a structure satisfying
several schemas shows all of them; click a badge → that schema's definition
via the existing `/call` door + `schema/schema-definition`). When a matched
schema is an entity-catalog row with a registered `:seon.render/html`
symbol, offer it as the alternate rendering of that node (the portal
"switch viewer" move) — but the DEFAULT stays the structural drill-down;
the walker's entity path already uses the specialized renderer when the
value arrives as a walked node.

Drill-down/nav state: **keep the current model** — the whole bounded tree
ships in one morph, expand/collapse is client-side `<details>` (no state to
own), and deeper-than-the-bound expansion is a path-based re-sample of
`result/<id>` through the existing `/call` door (value.cljs:704-714 already
specifies this). No URL state, no Datastar signal registry for tree state —
signals would be a second stored-state path for what CSS already does.

### 6.4 Close the three leaks (this is what makes it "everywhere")

- `generic-default-renderer` (render.cljs:691) → delegate to
  `block`/`render-html-data` instead of raw pprint (its ai twin keeps the
  `;; <id>` header, body via `value/render-ai`). Kills the unbounded dump.
- `/data` (web/debug.cljs:168) → render each datom row / pulled entity
  through `render/block :html` (datoms already have a marker form,
  `:seon.eval/datom`). The database browser becomes schema-aware for free —
  an `:aevt` page filtered to an attr shows which registered shapes those
  entities satisfy.
- eval result card (handlers/eval.cljs:210-225) → when the live
  `result/<id>` value is available, hand `block` the `render-html-data`
  projection instead of the `result-edn` string-as-source; keep the string
  form as the fallback for historical rows whose live var is gone. (Open
  question 3.)

### 6.5 Vocabulary

"panel" disappears as a value-rendering noun: the mechanism is
**`seon.render.value` (the value render) + `seon.render/block` (the typed
dispatch)** — code names, per the vocabulary table. `data-panel` renames to
`value-drill-down` or simply inlines under `block`; layout/CSS uses of
"panel" (agent_view `primary-panel`) stay, per "card for CSS only".

### Migration order

1. `schema.cljc`: shape index in `build-projection` + `matching-shapes` +
   validator memo (pure additive; tests: presence match, specificity order,
   multi-match).
2. `render.cljs`: re-derive `entity-primary-schema` from `matching-shapes`
   (behavior-identical; existing render tests are the gate).
3. `value.cljs`: `::schemas`/`::node-schemas` on `render-html-data`,
   schema-aware `summary`, `render-ai` hint annotation (transcript
   token-cost check: +1 short token run per truncated value).
4. `render.cljs`: badges in the drill-down header; `generic-default-renderer`
   → block.
5. `web/debug.cljs` `/data` → block. 6. eval result card → data projection.
7. "panel" rename ride-along in the vocabulary stage-2 sweep.

Steps 1–3 are the contract; 4–6 are independent consumers; each is a small
path-limited commit.

## 7. Open questions for the owner

1. **Validation depth default**: presence-match only (free, matches today's
   entity dispatch) vs confirm-with-validators at top level (honest `valid?`
   badge, small per-render cost). Recommendation: presence by default, one
   config dial for confirm, tokens/latency measured before flipping.
2. **Which schemas are "detectable"**: proposed = registered closed `:map`
   forms with ≥1 required key (computed structural rule, no hand list).
   Should open maps with required keys count too? Recommendation: yes —
   required-key presence is the test, `{:closed true}` is irrelevant to it.
3. **Eval result cards**: switch the transcript's result view from
   highlighted `result-edn` string to the interactive drill-down, or keep
   the string for the transcript and reserve the tree for deliberate
   inspection surfaces? (Token/DOM cost per transcript row is the concern.)
4. **Badge → alternate renderer**: when a value matches an entity schema
   with a registered `:seon.render/html`, should the drill-down offer a
   "render as <schema>" switch (portal-style), or is the walker's automatic
   entity path sufficient? Recommendation: defer; ship badges first.
5. **`node-schemas` breadth**: annotate every matching map node in the
   skeleton, or only top level + homogeneous-collection element shape (the
   existing `::shape` slot's natural upgrade)? Recommendation: top level +
   `::shape` upgrade first; per-node only if drilling shows the need.
