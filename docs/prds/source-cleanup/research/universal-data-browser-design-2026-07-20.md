---
type: research
status: complete
tags: [research, schema, web, architecture]
---

# Universal data browser — design

Owner brief (2026-07-20): unify the data-browser concept so ANY value can be
fingerprinted for its schema(s); validation status renders green/red with
hover-explanation and click-drill; custom renders attach per schema without
redoing machinery; and everything sits on ONE render contract — functions
taking spec'ed data in, returning views keyed `:seon.render/ai` /
`:seon.render/html`.

Builds directly on
[[schema-aware-inspector-2026-07-20]] (the prior report). Its conclusion
stands: **the owners are `seon.render.value` + `seon.render/block` +
`seon.schema/build-projection`; strengthen them in place.** This report adds
the four capabilities the owner asked for on top of that spine and settles
the contract shape. Disagreements with the prior report: §8.

## 1. Dependency ledger

| Dependency | Grounding |
|---|---|
| `m/explain` compiles an explainer per call — memoize `m/explainer` for hot paths | `reference-code/malli/src/malli/core.cljc:2660-2666` (docstring: "Creates the `explainer` for every call. When performance matters, (re-)use `explainer`") |
| `m/validator`/`m/parser` cached ON the schema object (`-cached`) | `malli/core.cljc:353-361`, `:2674-2679` |
| Schema `:properties` — arbitrary EDN travels with the registered form | `malli/core.cljc:2582-2588` (`properties`); Seon already reads them in `build-projection` (`src/seon/schema.cljc:339-360`) |
| Explanation → human text: `me/humanize` (`:errors` with `:path`/`:in`/`:message`), `me/error-value` (the failing sub-value), spell-checking | `reference-code/malli/src/malli/error.cljc:374-390` (humanize), `:392-404` (error-value), `:344-372` (`with-spell-checking` / likely-misspelling-of) |
| Malli's own explanation rendering (prior art) | `reference-code/malli/src/malli/dev/pretty.cljc:41-46` (format ::m/explain = schema + value + `me/humanize` of spell-checked explanation), `:186-190` (`pretty/explain` wraps `me/with-error-messages`) |
| `m/walk` + ref-walk options | already used: `src/seon/schema.cljc:27-45` (`direct-references*`) |
| datafy/nav idiom (adopt pattern, not protocols) | `reference-code/clojurescript/src/main/cljs/clojure/datafy.cljs`; prior report §5 — unchanged |
| Reveal predicate→action registry (prior art for guarded selection) | `reference-code/reveal/src/vlaaad/reveal/action.clj:14-50` |
| Orchard inspector — pagination/path mechanics | `reference-code/orchard/src/orchard/inspect.clj:44` (page-size 32 = Clojure chunk size), `:96-135` (`bounded-count (inc page-size)` lazy-safe overflow probe; `paginate?` when `count+1 > page-size` or page > 0), `:153-192` (`next-page`/`prev-page` mutate only `:current-page`; `down` recomputes page from the child index; a pages-stack restores position on `up`) |
| Seon render owners | `src/seon/render/value.cljs` (`sample` :348-399, `render-html-data` :716-741, marker vocabulary :40-59), `src/seon/render.cljs` (`entity-primary-schema` :199-224, `entity-render` :226-241, `unwrap-response` :256-278, `block` :555-605, `data-panel` :522-533) |
| Seon registry | `src/seon/schema.cljc` — `register!` EDN round-trip gate :254-278, `build-projection` catalog :339-360, `fingerprint` :361-365, `candidate-validator`/`candidate-explainer` :536-550 |
| Interaction path | `src/seon/web/reactive/call.cljs` — capability gate :63-98 refuses CORE fns (agent-authored `:seon.fn` facts only); Datastar feed pushes whole-element morphs (`src/seon/web/CLAUDE.md`) |
| Existing property-registered renderers (the call sites that prove the surface) | `src/seon/agent.cljs`, `src/seon/agent/message.cljs`, `src/seon/handlers/message.cljs`, `src/seon/handlers/eval.cljs`, `src/seon/test/runner.cljs` (`:seon.render/html '…`), `src/seon/agent/ctx/transcript.cljs:514,569` (`:seon.render/ai '…`) |

## 2. The universal render contract

### 2.1 One core idea, already in the source

The owner's asked-for contract **already exists in embryo** and needs
generalization, not invention:

- Registration: schema `:properties` already carry `:seon.render/ai` /
  `:seon.render/html` qualified symbols (`register!` docstring example,
  `schema.cljc:242-246`; six first-party call sites above). `register!`'s
  EDN round-trip gate (`schema.cljc:254-278`) admits symbols and refuses
  function objects — exactly right: the property is durable data, the fn is
  resolved at render time.
- Envelope: a render fn may return bare content OR the
  `:seon.render/html-response` map carrying `:seon.render/hiccup` and
  `:seon.render/ai`; `unwrap-response` (`render.cljs:256-278`) is the ONE
  unwrap. A single fn computing both views once and returning the envelope
  is how ai + html share computation.
- Input: render fns already receive the value under `:seon.render/node`
  (`render-entity-ai` docstring, `render.cljs:607-619`).

What is missing: (a) the catalog only admits `{:seon.db/entity true}` maps
with an id-attr (`build-projection:339-360`), so a NON-entity registered
shape's renderer property is currently invisible to dispatch; (b) the
value→schema reverse lookup is entity-only and single-winner
(`entity-primary-schema`); (c) `block`'s data fallback never consults
either.

### 2.2 Contract, exactly

**Registration** (what a schema author writes — nothing else):

```clojure
(schema/register! :my.thing/report
  [:map {:seon.render/html 'my.thing/report-html   ; optional, per view
         :seon.render/ai   'my.thing/report-ai}    ; optional, per view
   [:my.thing/title :string]
   [:my.thing/rows [:vector :my.thing/row]]])

```

**Render fn** (one namespaced map in, envelope or bare content out):

```clojure
(schema/register! :seon.render/render-request
  [:map
   [:seon.render/node :any]                        ; the matched value
   [:seon.render/schema-key :keyword]              ; which schema matched
   [:seon.config/configuration {:optional true} :seon.config/singleton]])

;; return: bare hiccup (:html) | bare String (:ai) | the
;; :seon.render/html-response envelope {:seon.render/hiccup h
;;                                      :seon.render/ai s}

```

`:seon.render/schema-key` is new but additive: existing converters take the
map and ignore keys they don't read. Passing it lets one fn serve several
registered shapes without re-fingerprinting.

**Resolution** (per node, per view) — one ordered rule, no new mechanism:

1. per-entity attr override (`:seon.render/html` / `:seon.render/ai` value
   on the entity — `entity-render` step 1, unchanged);
2. most-specific matched schema (via `schema/matching-shapes`, §4) that
   carries a registered renderer symbol for the requested view;
3. the generic schema-aware tree (`render-html-data` → `data-panel` for
   html; `value/render-ai` for ai) — annotated with the matches it did find.

Guarding is unchanged: dispatch through `block`/the walker keeps the
strict-dial guard (`render.cljs:594-605`) — a throwing custom renderer
becomes an error card, siblings intact, and the generic tree remains the
person's escape hatch (the drill-down header offers "as data" for any node
whose custom render is active — the portal switch-viewer move, one link, no
second machinery).

### 2.3 Properties vs side registry — settled: properties

Weighed per the brief:

- Properties travel with the form through the database (`:seon.schema/form`
  is the durable fact), survive projection rebuilds, resume, and pod
  restart; a side registry is process-local mutable state that must be
  re-populated — a second model of the same fact (violates derive-don't-
  store and the no-hand-maintained-lists rule).
- "Hot-swappable without re-registering" is not a real advantage here:
  re-`register!` IS the live update path (an eval away), and the projection
  rebuild it triggers is the same one any schema edit takes. The symbol
  indirection already gives hot code: redefining `my.thing/report-html`
  changes rendering with no re-registration at all.
- Six first-party call sites already use properties; a side registry would
  strand them or force a dual path.

The catalog derivation in `build-projection` is the side "registry" — but
DERIVED from the one authority, per projection, never stored.

## 3. Validation-status pipeline

### 3.1 When explain runs (cost-settled)

Grounded costs: `m/validate`/`m/explain` recompile per call
(`core.cljc:2635`, `:2660`); compiled fns are cheap to reuse. The prior
report's memo design stands: process-local cache atom keyed
`[projection-fingerprint schema-key]` (fingerprint: `schema.cljc:361-365`)
holding `{::validator f ::explainer f}` built lazily from
`candidate-validator`/`candidate-explainer` (`schema.cljc:536-550`).

Pipeline, per rendered value (top level + drilled nodes only, never every
node of the walk):

1. **Presence match** (free set ops, §4) → matched schema keys.
2. **Validate** the primary match with the memoized validator → status enum
   `:seon.render.value/status` ∈ `#{:valid :invalid}`; when the confirm
   dial is off (prior report open-Q1 default), status is `:shape-only`.
3. **Explain only on invalid**: run the memoized explainer, then
   `me/humanize` + `me/error-value` (`error.cljc:374,392`), following
   malli's own `dev.pretty` recipe (`pretty.cljc:41-46`) including
   `with-spell-checking` — misspelled-key detection is exactly the class of
   agent mistake this surface exists to show. Bounded: explain runs on the
   raw node the person is looking at, which the drill contract already
   bounds (top value, or one `get-in` path slice).

Because explain is invalid-only and the invalid case is the rare case, the
steady-state cost of the green path is one cached-validator call per
rendered top value.

### 3.2 What ships in the projection

`render-html-data` gains (all derived, never stored — renders are never
stored):

```clojure
{:seon.render.value/schemas    [{:seon.schema/key k
                                 :seon.schema/entity? bool
                                 :seon.render.value/status :valid|:invalid|:shape-only}]
 :seon.render.value/explanation {:seon.render.value/humanized <me/humanize data>
                                 :seon.render.value/error-value <me/error-value data>}
 ;; explanation PRESENT ONLY when primary status = :invalid (absence = valid;
 ;; no stored nil, no [:maybe]).
 …existing keys…}

```

### 3.3 Interaction contract (hover / click) over Datastar

- **Indicator**: `data-panel`'s header (`render.cljs:522-533`) gains the
  primary schema key + a status dot (green `:valid`, red `:invalid`, hollow
  `:shape-only`) + badges for the remaining matches. Dot+text status is the
  house UI idiom (`src/seon/web/CLAUDE.md` rules).
- **Hover = zero round-trips.** The humanized explanation ships IN the
  morph (it exists only when invalid, and it is small — humanize output
  mirrors the value's failing paths, and the value itself is already
  bounded). Reveal is a pure CSS affordance (the same class of client-side
  behavior as the existing `<details>` expand — no Datastar signal, no
  server state). This deliberately does NOT use `/call`: the capability
  gate (`call.cljs:63-98`) refuses core functions by design, and widening
  it for hover would trade a security invariant for a latency loss.
- **Click = the existing drill.** Expand/collapse stays client-side
  `<details>`; deeper-than-bound expansion stays the path-based re-sample
  of `result/<id>` the value contract already specifies
  (`value.cljs:704-714`). The expansion response for a drilled node runs
  the SAME §3.1 pipeline on that node, so status/explanation follow the
  drill down. Orchard confirms the mechanics: overflow detection by
  `bounded-count (inc page-size)` (`inspect.clj:101` — the same head+1
  probe `sample-seqish` already does, `value.cljs:330-346`) and drill
  state as a path + page index, recomputed not stored per node
  (`inspect.clj:189-192`). Adopt orchard's one addition Seon lacks:
  **elided-tail paging** — the `… +129 more` marker becomes a control that
  re-samples the same path with an offset (`:seon.render.value/offset` in
  the re-sample request), giving next/prev-page over a long collection
  instead of only "the first N".
- **Transport for the re-sample**: the prior report routed expansion
  "through the existing `/call` door". That cannot hold as written — the
  gate refuses core fns (disagreement §8.1). Two lawful options:
  (a) register the re-sample fn as an agent-authored shared fn (wrong: it
  is core machinery, not agent code); (b) one core GET route
  `/agent/{id}/value?id=<eval-id>&path=…&offset=…` returning the
  `render-html-data` projection of that slice — an ordinary read-only page
  route beside `/agent/{id}/debug`, same admission gate, no eval, no
  capability question because nothing is invoked (the eval-id names a value
  the agent's page already displays). Recommend (b).

Nav-style laziness (brief item 2 of the vendored list): re-examined and the
prior report's call stands — `nav`'s re-contextualization is exactly what
the path re-sample does (`(get-in result/<id> path)` against the LIVE
value), but as an explicit server round-trip on wire-safe data rather than
a process-local protocol. Click-to-dive needs no more laziness than that.

## 4. Fingerprinting unification — one matcher for everything

`schema/matching-shapes` (prior report §6.1) is confirmed as the ONE
matcher; this section extends its coverage beyond maps so "any value" holds:

- **Maps** (incl. entities): inverted required-key index
  `{required-attr #{schema-key}}` derived in `build-projection` over ALL
  registered map forms with ≥1 required key (not only entity-catalog rows);
  prefilter = key-set intersection, full-match = every required attr
  present; specificity order = required-count desc, key asc — the verbatim
  `entity-primary-schema` rule (`render.cljs:199-224`), which becomes
  `(first (filter :seon.schema/entity? (matching-shapes v)))`.
- **Vectors/sets/seqs**: matched through their ELEMENTS — when the
  homogeneous-shape probe (`shared-keys`, `value.cljs:240-247`, already
  bounded by `shape-sample`) yields a key set, run the map prefilter on
  that key set once; the result annotates the collection as
  `[:vector-of :seon.agent.turn]`-style ("96 items each :seon.agent.turn").
  No per-element validation; the confirm dial validates only the sampled
  head elements.
- **Scalars**: NOT fingerprinted standalone (running every registered
  scalar validator against every leaf is O(schemas×leaves), the rejected
  cost class). A scalar's schema identity comes from CONTEXT: when its
  parent map matched a schema, the entry schema for its key IS its schema
  (`m/children` on the matched form names it, `core.cljc:2598`). The
  drill-down shows it as the leaf's label. A top-level bare scalar gets
  `"scalar"` as today (`value.cljs:739`).
- **Composition**: `matching-shapes` returns ALL full matches, ordered;
  the first is primary (drives renderer resolution §2.2 step 2), the rest
  render as badges. Multiple-schemas-satisfied is thus first-class, not a
  discarded intermediate.

One function, one index, derived per projection; entity dispatch, the value
tree annotation, the `/data` browser, and custom-renderer resolution all
consume it. No second matcher anywhere.

## 5. Extensibility proof — the acceptance example

Finding (prior report §2.8): `src/my/plan/internal.cljs` hand-builds its
own plan tree with per-step detail panels (`:1831,1940,1970`) — a parallel
value-rendering path for one shape. Under this design it becomes a
registration. The COMPLETE code a schema author writes:

```clojure
;; 1. the shape already registered gains two properties — a one-line diff:
(schema/register! :my.plan/plan
  [:map {:seon.db/entity true
         :seon.render/html 'my.plan.internal/plan-html
         :seon.render/ai   'my.plan.internal/plan-ai}
   [:my.plan/id …] [:my.plan/steps …] …])

;; 2. the renderers are ordinary fns over spec'ed data — the existing tree
;;    builders, re-headed to the one contract:
(defn plan-html
  {:malli/schema [:=> [:cat :seon.render/render-request] :any]}
  [{:seon.render/keys [node]}]
  {:seon.render/hiccup (steps->tree-hiccup (:my.plan/steps node))
   :seon.render/ai     (steps->outline-text (:my.plan/steps node))})

```

Acceptance evidence, all mechanical:

1. an eval returning a plan map renders the plan tree in the transcript's
   html view WITHOUT any code in `block`/`data-panel` naming plans;
2. the drill-down header shows `:my.plan/plan` with a green dot; deleting a
   required key from a probe copy flips it red, hover shows
   `me/humanize`'s ":my.plan/steps missing" text;
3. the "as data" switch still shows the generic bounded skeleton of the
   same value;
4. `grep -c "detail-panel" src/my/plan/internal.cljs` drops to the
   renderer fns only — the parallel path is deleted in the same change
   (one mechanism).

Same few-line shape for every future author: register properties, write
one fn taking `:seon.render/render-request`, done. No walker edits, no
dispatch edits, no new namespace.

## 6. Migration plan

Extends the prior report's 7 steps (its 1–3 remain the contract base);
each step is a small path-limited commit with its named gate.

1. **`schema.cljc`** — shape index over ALL registered map forms +
   `matching-shapes` + validator/explainer memo keyed
   `[fingerprint schema-key]` (prior step 1, unchanged).
2. **`schema.cljc`** — widen the catalog derivation: rows for ANY map form
   carrying a render property (entity rows keep `entity?`+id-attr extras).
   Gate: existing entity dispatch behavior identical.
3. **`render.cljs`** — `entity-primary-schema` re-derived from
   `matching-shapes` (prior step 2); `entity-render` step-2 lookup consults
   the widened catalog; add `:seon.render/schema-key` to the render-request
   map (additive).
4. **`value.cljs`** — `::schemas`/`::status`/`::explanation` on
   `render-html-data` per §3.2; schema-aware `summary`; `render-ai` top-
   match hint (prior step 3 + validation additions). Gate: token-cost check
   on transcript rows.
5. **`render.cljs`** — `data-panel` header: primary key + status dot +
   badges + hover explanation reveal; `block`'s `:else` branches resolve a
   registered renderer via §2.2 before falling to the generic tree;
   `generic-default-renderer` (`render.cljs:691`) delegates to `block`
   (kills the unbounded pprint — prior step 4).
6. **web** — the core `/agent/{id}/value` re-sample route (path + offset →
   `render-html-data` slice, §3.3) with elided-tail paging.
7. **Leak closures** (prior steps 5–6): `/data`
   (`web/debug.cljs:157-168` pr-str) → `block`; eval result card
   (`handlers/eval.cljs:210-225`) → data projection when the live value
   exists.
8. **Registrations replace parallel paths** — every call site that becomes
   a registration:
   - `src/my/plan/internal.cljs` plan tree → §5 registration (deletes the
     bespoke detail panels);
   - already-registered property sites confirm unchanged behavior under
     the widened catalog: `src/seon/agent.cljs`,
     `src/seon/agent/message.cljs`, `src/seon/handlers/message.cljs`,
     `src/seon/handlers/eval.cljs`, `src/seon/test/runner.cljs`,
     `src/seon/agent/ctx/transcript.cljs:514,569`;
   - "panel" vocabulary rename ride-along (prior step 7).

Steps 1–4 are the contract; 5–8 are independent consumers.

## 7. What ships where (summary of the four owner asks)

| Ask | Mechanism | New code lives in |
|---|---|---|
| Fingerprint any value | `schema/matching-shapes` + shape index | `schema.cljc` |
| Green/red + hover explain + click dive | status enum + invalid-only humanized explanation in the projection; CSS hover; existing drill + one core value route | `value.cljs`, `render.cljs`, one route |
| Attachable custom renders | schema `:properties` render symbols → widened catalog → resolution step 2 | `schema.cljc` (derive), `render.cljs` (resolve) |
| One render contract | `:seon.render/render-request` in, bare-or-envelope out, `unwrap-response` unchanged | already exists; formalized schema only |

## 8. Disagreements with the prior report

1. **The `/call` door cannot carry expansion/hover** (prior §6.3 "expansion
   is a fresh server `/call`", and badge-click "via the existing `/call`
   door"). The capability gate (`call.cljs:63-98`) admits only agent-
   authored non-private `:seon.fn` facts and refuses core functions — the
   re-sample and `schema-definition` are core. Fix: hover ships its data in
   the morph (no round-trip); expansion/badge-click use one core read-only
   route (§3.3). This is a correction, not a widening of the gate.
2. **Scalars and collections are in scope** (prior §4 limited detection to
   map shapes). Resolved without new cost: collections via the existing
   homogeneous-shape probe, scalars via parent-entry context (§4). The
   prior report's "scalars/enums are attribute-level" intuition is kept —
   they are labeled from context, never independently matched.
3. Everything else — owners, matcher home, properties-as-registration,
   presence-default/confirm-dial, marker vocabulary, no datafy/nav
   protocols, migration skeleton — is confirmed and extended, not changed.

## 9. Open questions for the owner

Prior report's Q1 (confirm dial default), Q3 (eval result card in
transcript), Q5 (per-node annotation breadth) remain open and unchanged.
New:

1. **Status default**: with validation now a first-class ask, should the
   confirm dial default ON for the TOP value (one cached-validator call per
   render; `:shape-only` reserved for the dial-off/prod-cost case), or stay
   presence-only until measured? Recommendation: ON at top level, measure,
   per-node stays presence-only.
2. **Hover payload cap**: humanized explanations of a badly-invalid large
   value can be sizeable. Cap by the same token budget as the value body
   (estimate via `seon.ai.tokens/estimate`) with a "full explanation" link
   through the §3.3 route? Recommendation: yes, one shared cap.
3. **The value route's scope**: `/agent/{id}/value` reads `result/<id>`
   vars of that agent. Should it also serve entity drill (`/data`'s rows)
   by eid, unifying the two browsers' expansion transport? Recommendation:
   yes — same projection, same route, one transport.
