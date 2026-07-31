---
type: research
status: active
tags: [research, render, ui]
---

# Render — current state against the one-walk/two-projection framing

/ 2026-07-31. Read-only audit of the fresh `src/seon/render*` tree against
the owner's stated target: ONE recursive walk over the entity graph, every
value projected through `:seon.render/ai` and `:seon.render/html` by the same
traversal, ref-following with configurable depth, defaults attached to Malli
schema definitions, overridable by a same-signature defn in the agent's (or a
required) namespace.

**Headline: the owner's framing is largely BUILT, not aspirational.** The one
router, the one recursive entity walk, distance-per-connection, schema-attached
family defaults, and the primary-panel + right-rail layout all exist in fresh
`src/`. What is missing is narrower and more specific than "the render system":
the namespace-override discovery step is built but **unwired**, there are **two
resolution chains and two floors** rather than one, rail ordering is commit
order rather than recency, and there is **no pin fact anywhere**.

## Built vs target inventory

| Capability | State | Evidence |
|---|---|---|
| One projection router, open kind set | **BUILT** | `src/seon/render.clj:173-222` (`render`), `:100-122` (`kinds`, computed) |
| `:seon.render/ai` + `:seon.render/html` as the two shipped kinds | **BUILT** | `resources/seon/schema/render.edn:83-84`; `resources/seon/schema/block.edn:76-77,96` |
| Late var resolution (hot reload changes next render) | **BUILT** | `src/seon/render.clj:204-206`; prohibition on memoizing at `:30-39` |
| Router is total (flat `:seon.error/value`, never throws) | **BUILT** | `src/seon/render.clj:210-222` |
| Literals as declarations (string / hiccup vector) | **BUILT** | `src/seon/render.clj:85-98,197-199` |
| Recursive walk over the entity graph | **BUILT** | `src/seon/render/walk.clj:328-423` (`neighborhood`) |
| Ref-following BOTH directions (forward + reverse refs) | **BUILT** | `src/seon/render/walk.clj:185-235,289-315` |
| Distance spent one hop per connection | **BUILT** | `src/seon/render/walk.clj:403-415`; `src/seon/render/block.clj:655-737` (`follow`) |
| Distance default = 1, written in exactly one place | **BUILT** | `src/seon/render/block.clj:168-178` (`distance`) |
| Distance as a **config fact** (owner: "configurable depth") | **GAP** | no `:seon.config.render/*` depth dial; only the request key + literal `1` |
| Schema-attached family default renderers | **BUILT** | `resources/seon/schema/run.edn:17-18,59,66-67,86-87`; `message.edn:38-39`; `error.edn:77-78`; lifted by `src/seon/render/walk.clj:132-148` (`family`) |
| Schema-attached defaults honoured by the top-level router too | **BUILT** | `src/seon/render.clj:147-158` (`schema-declaration`) |
| Namespace override by `render-<kind>` defn convention | **BUILT but UNWIRED** | `src/seon/render.clj:135-145`; **no production caller sets `:seon.render/namespace`** — grep hits only `render.clj` + `test/seon/render/value_test.clj:312,324` |
| Viewer override map (schema-key → projection) | **BUILT, always empty** | `src/seon/render/walk.clj:172-179`; every caller passes `{}` (`src/seon/render/agent.clj:177,426`) |
| Per-hop redirect (steer one hop to another lens) | **BUILT** | `src/seon/render/block.clj:127-166` (`entity-slot` 2-arity), consumed at `:698-701` |
| AI floor (any value → text) | **BUILT ×2** | `src/seon/render/block.clj:1008-1053` (`data-prose`) **and** `src/seon/render/value.cljc:871-875` (`render-ai`) |
| HTML floor (any value → hiccup) | **BUILT ×2** | `src/seon/render/block.clj:913-1006` (`data-panel`) **and** `src/seon/render/value.cljc:877-888` (`render-html`) |
| Bounded universal value renderer (depth/width/opaque tokens) | **BUILT** | `src/seon/render/value.cljc:479-530,790-808` |
| Blocks as the one page+prompt mechanism | **BUILT** | `src/seon/render/block.clj:444-484` (`surfaces`), `:774-858` (`page`) |
| Per-block stable DOM id / morph target | **BUILT** | `src/seon/render/block.clj:74-125` (`surface-id`, `slot`) |
| Slot expansion to fixpoint, node-budgeted, cycle-refusing | **BUILT** | `src/seon/render/block.clj:536-772` (`expand`) |
| Layout: large primary panel + right rail | **BUILT** | `src/seon/render/agent.clj:328-360` (`focus-html`); CSS `resources/public/css/input.css:1243-1313` |
| Responsive collapse to single column | **BUILT** | `resources/public/css/input.css:1315-1322` (`@media (max-width: 52rem)`) |
| Rail ordered by **most-recently-changed** | **GAP** | rail is transcript commit order, `src/seon/render/agent.clj:131-168,294-297` |
| Rail limited to ~last 3 with scrollback | **GAP** | rail renders every unit up to `max-collection`, `src/seon/render/agent.clj:335-339` |
| User **pinning** of the main block | **NOT BUILT** | `$selected` is a transient Datastar signal (`src/seon/render/agent.clj:306,319-323,348`); no pin attribute in any schema EDN, no `pin` in `src/` |
| Live delivery: complete snapshot + per-tab byte diff | **BUILT** | `src/seon/render/web.clj:229-259` (`page-of`), `:261-285` (`changed`), `:530-612` (`feed`) |
| Revisioned **packages / keyframes / deltas** | **TARGET ONLY** | none of those words appear in `src/seon/render/web.clj` as mechanisms; `:1-45` explicitly says "Complete snapshots, never incremental patches" |
| Render proc is **per agent** (ruling 21's third proc) | **GAP** | today one cluster-wide render proc, `src/seon/render/web.clj:360-400`; ruling 21 (README:1071-1099) wants one beside each agent's mailbox and turn |
| Renders call `seon.db`, never `datahike.api` (ruling 22a) | **GAP** | every render namespace requires `datahike.api` directly — `walk.clj:72`, `block.clj:57`, `agent.clj:54`, `root.clj:27`, `value.cljc:33`, `web.clj:52`. The router itself is clean (`render.clj` requires no database) |
| Boot discovery pass registering all renders (ruling 22b) | **GAP** | block sets are hand-written seed vectors, `agent.clj:440-499`, `root.clj:221-260` |
| Prompt assembly routed through the router | **BUILT** (audit claim now stale) | `src/seon/cluster/prompt.cljc:183-196` filters on `render/declaration?` and calls `render/render`. `render-universality-audit-2026-07-28.md:81` calls this "the largest second rendering system" — **verified fixed since**; do not act on that line |
| Program-graph catalog render symbols resolve | **N/A** (audit claim now stale) | `render-universality-audit-2026-07-28.md:93` reports six symbols under absent `seon.render.handlers.*` namespaces; `rg 'seon\.render\.handlers' src/ resources/` returns **zero hits** today |

## The ruled target — where each piece of the framing comes from

All line numbers are `docs/prds/sci-execution-runtime/plan/README.md`.

| Ruling | Lines | What it says |
|---|---|---|
| **(12) UI TABLED** | 1239-1249 | No proper UI until context rendering is understood; sequence is code-graph mining → old-context mining → an owner design session. **The owner's current request IS that design session.** |
| **(14) UNIVERSAL RENDERER + PRECEDENCE** | 1215-1238 | Any value renders via a default; the quarry's structural skeleton renderer is ported as the floor; precedence **most→least specific: (1) render keys on the value itself, (2) namespace override defns, (3) schema-attached default, (4) the ported structural floor**; unified with the entity/distance walker as one discipline. **This is verbatim the owner's framing, already ruled.** |
| (15) CHURN ORDERING | 1207-1214 | Context is never stored; churn = byte-identical comparison since last turn; order descends by stability. This is the *prompt* ordering mechanism, not rail display order. |
| (19) BLOCK DEFINITION | 1113-1133 | A block is one named independently-rendered region with a stable element id; a schema carrying `:seon.db/db` is reactive, one without it is static/memoizable. |
| (20) RENDERING NORTH STAR | 1100-1112 | Each namespace renders as an agent-owned world; the agent contract stays three sentences — write a fn of the db, point a display fact at it, only db changes re-render. |
| **(21) RENDER MOVES INTO THE AGENT'S FLOW** | 1071-1099 | A **third proc per agent** beside mailbox and turn owns every derived view — html/canvas/transcript **and** the ai context pieces — waking on fact interest, memoizing, byte-digesting for churn. Survived falsification (`agent-flow-render-falsification-2026-07-29.md:33-48`). |
| (22) READ SEAM | 1052-1070 | Renders call `seon.db`, never `datahike.api`; a boot discovery pass registers all renders; the third proc lands **before** the read seam; memory bound = dedupe by digest. |
| **(13c) "THE DREAM"** | 819-838 | *Sanctioned if buildable*: skip the block system entirely — everything derived from **default render functions attached to schemas as metadata**, with even the transcript built by walking the agent's entity. (13b): the old namespace block becomes THE WALK, requires followed at distance 2. |
| Post-midnight distance rulings | 536-621 | `:seon.render/distance` is the one new unit key (optional, default 1, 0 = name only), an argument TO the renderer never a property of it, decremented per hop. **Override chain: (1) explicit slot redirect, (2) viewer's local override held CONSTANT through the walk, (3) owning namespace's default, (4) floor = code/data panels.** Guardrail: all machinery confined to `seon.render.walk`, `seon.data`, schema EDN, the N5 indexer. |

**The two implemented chains are two owner rulings, not an implementation
accident.** Ruling 14 (README:1215-1238) is chain A exactly; the post-midnight
ruling (README:572-586) is chain B exactly. They were made on different days
for different callers and neither retracted the other. Reconciling them is a
decision only the owner can make, and it is the single highest-value question
in this audit.

Notably **absent from every plan document**: "sidebar", "responsive", "mobile",
and user-facing "pinning" — grepped across `plan/README.md`,
`plan/unsettled.md`, and `plan/ui-conversion-plan-2026-07-29.md`, zero hits.
The mobile collapse that exists in `input.css:1315-1325` is unruled,
undocumented work; the owner's responsive requirements are **new** and have no
prior ruling to conflict with.

`plan/ui-conversion-plan-2026-07-29.md:387-402` is the closest prior statement
of the owner's layout ask and it resolves it the same way the code does:
"Focus and rail are blocks at distance, not a surface registry" — the focal
panel renders one unit at the page's distance, the rail renders the same units
at distance 0-1. "The old system stored two projections of one unit; the new
one spends a number." It also states explicitly that a **durable pin is out of
scope** because it needs web-session facts that do not exist in fresh `src/`
(`:820`), and that order is ascending entity id within one basis (`:404-409`).

## The router precedence, as actually implemented

There are **two** chains. This is the single most important finding.

### Chain A — `seon.render/projection-declaration` (`src/seon/render.clj:166-171`)

1. `value-declaration` — a `declaration?` under the kind key on
   `:seon.render/value`, else on the unit itself (`:124-133`);
2. `namespace-declaration` — `<:seon.render/namespace>/render-<kind>`,
   probed with `requiring-resolve` (`:135-145`);
3. `schema-declaration` — the kind key on the first of
   `schema/matching-shapes` (`:147-158`);
4. `floor-declaration` — hardcoded `seon.render.value/render-html` or
   `seon.render.value/render-ai` (`:160-164`).

### Chain B — `seon.render.walk/projection` (`src/seon/render/walk.clj:172-179`)

1. `:seon.render/redirect` — the delegating renderer steered this hop;
2. `:seon.render/overrides` — the **viewer's** map, keyed by registered schema
   key, constant across the whole walk;
3. the unit's own declared qualified symbol under the kind key;
4. the first family row's kind key (schema-attached default);
5. `:seon.render/floor` — supplied by the caller, in practice
   `seon.render.block/data-panel` / `data-prose`
   (`src/seon/render/agent.clj:177,402,426`).

### How they interact — chain A's step 2 is dead on the walk path

`neighborhood` resolves through chain B, then **assoc's the winner onto the
unit** before calling the router (`src/seon/render/walk.clj:386-394`):

```clojure
rendered (render/render
          {:seon.render/unit (assoc unit kind chosen)
           :seon.render/kind kind})
```

So chain A's `value-declaration` always fires first and steps 2–4 never run
inside a walk. Chain A runs in full only for the direct callers —
`seon.render.block/surface` (`:404-405`), `seon.error` (`:461`),
`seon.problems` (`:464`), `seon.oversight` (`:275`),
`seon.cluster.prompt` (`:192`), `seon.cluster.loop` (`:926`) — and none of
those sets `:seon.render/namespace` either.

**Net effect: the owner's "override by defining a same-signature function in
the agent's namespace" has a complete implementation with zero live callers.**

## What the package/keyframe/delta design specifies vs what exists

`render-pipeline-design-2026-07-29.md` is the delivery target, and it is
**design plus measured evidence only — "No production source changed"**
(`:60`). Its exact concepts:

- **revision** `:seon.render.package/revision`, strictly increasing per
  agent-page registration, plus **base-revision** naming the previous produced
  package (`:406-410`);
- **package** — one immutable composite carrying BOTH `keyframe-bytes` and
  `delta-bytes` with their sizes, published together so the two can never be
  observed torn (`:14-21,420-428`);
- **delta fragment** `:seon.render.package/delta-bytes` — one Datastar event of
  only the changed complete stable-id fragments (`:354-357`);
- **keyframe** `:seon.render.package/keyframe-bytes` — one Datastar event of
  every current fragment, built by **concatenating already-serialized bytes,
  never a re-render** (`:356-363`);
- **mult** — the render proc publishes the one package through one mult; a raw
  mult has no replay, so a **single-writer latest-package snapshot** is
  required alongside it for late joiners (`:137-159`);
- **snap-to-keyframe** — send a delta only when
  `(= delivered-revision base-revision)` AND `(< delta-size keyframe-size)`;
  otherwise snap. No time-based "force keyframe after N" (`:430-456`);
- **new-tab-serve-from-keyframe** — a per-tab JOINING→STREAMING/PARKED/SNAP
  state machine; a joining tab taps the mult **before** reading the shared
  latest package, for race safety (`:458-509`);
- per-tab buffer stays `(sliding-buffer 1)`; fixed, dropping, and
  second-channel designs are explicitly rejected (`:512-531`).

The doc names today's pipeline as generation 5 and "the surviving owner"
(commit `2e372027d`, `:710-724`), itself as generation 6, and it **recommends
against** the client-pull variant (`:876-905`). Seven decisions remain open for
the owner (`:643-674`), and it warns that `docs/seon/architecture/ui.md` — which
currently describes one sliding-1 tap per render *unit* — must change with the
implementation, since the package design uses one tap per *tab* (`:636-641`).

What ships today is the earlier design:

- **one render proc** on `:io` consuming transaction reports plus the streamed
  `:seon.ai/partial`, deriving every watched agent's page
  (`src/seon/render/web.clj:315-322,360-400`);
- **complete snapshots** `{agent-id → {surface-id → html}}` published on one
  `mult` over a `(sliding-buffer 1)` channel (`:99-104,318-320,390`);
- **per-tab sliding-1 taps**, each tab diffing the newest complete snapshot
  against what IT last delivered and emitting one Datastar patch per changed
  block (`:530-612`, diff at `:261-285`);
- suppression is **byte** equality, made sound by a deterministic
  attribute-sorting serializer (`:264-272`).

The mapping to the target vocabulary is clean and the conversion is additive,
not a rewrite: today's complete snapshot *is* a keyframe with no revision
number; `changed`'s patch vector *is* a delta fragment with no revision; the
`mult` is already the fan-out the keyframe design wants; and "snap to keyframe
on a revision gap" is currently unnecessary because sliding-1 loss of a
*complete* snapshot is safe by construction (`:1-45`). The genuinely missing
pieces are the **revision counter**, **serialize-the-keyframe-once** (each tab
re-serializes today via its own `changed` call over shared bytes — the bytes
are shared, the diff is not), and **new-tab-serve-from-latest-keyframe**
(today a new tab paints from a fresh `page-of` derivation at the current
database value, `:534-539`).

## Seams: where "one walk, two projections" meets the code

### Already serves it

- **The walk is genuinely kind-agnostic.** `neighborhood` takes
  `:seon.render/kind` and a `:seon.render/floor` and does the identical
  traversal for both (`src/seon/render/walk.clj:353-423`). `namespace-ai`
  (`agent.clj:362-407`) and `namespace-html` (`:409-434`) are the same call
  with two kinds and two floors — this IS the owner's framing, running.
- **Family defaults live on the Malli entity map**, exactly as asked:
  `:seon.cluster.run/run` declares `:seon.render/ai seon.cluster.run/render-ai`
  and `:seon.render/html seon.cluster.run/render-html` in
  `resources/seon/schema/run.edn:59`. Adding a family lens is one EDN property
  plus one defn — no registry.
- **The `render-<kind>` naming convention already matches** the shipped family
  renderers: `seon.error/render-ai|render-html` (`src/seon/error.clj:837,869`),
  `seon.cluster.run/render-ai|render-html` (`:983,1065`),
  `seon.cluster.message/render-ai|render-html` (`:427,463`). The convention the
  namespace-override step probes for is the convention the codebase already
  writes.
- **Omission is nil-punning throughout**, so an html-only block costs the
  prompt zero tokens and an ai-only block occupies no pixels
  (`src/seon/render/block.clj:472-475`).
- **The layout the owner remembers exists.** `focus-html` emits
  `.seon-focus-layout` = `.seon-focus-primary` + `<aside class="seon-rail">`
  (`agent.clj:352-358`), CSS `grid-template-columns: minmax(0,2fr)
  minmax(15rem,1fr)` (`input.css:1250`), collapsing to `1fr` under 52rem
  (`:1315-1322`). Rail cards render the SAME `unit-html` at distance 0 while
  the primary renders at distance 1 (`agent.clj:308,326`) — one renderer, two
  distances, which is the framing applied to layout.

### Conflicts

1. **Two resolution chains** (above). Chain B is strictly richer (redirect +
   viewer overrides) but omits the namespace convention; chain A has the
   namespace convention but no redirect and no viewer overrides. One mechanism
   is required by the house rule; today a reader must know which caller they
   are in to answer "why did it render that way?".
2. **Two floors per kind.** `seon.render.value/render-html` (`render.clj:163`)
   vs `seon.render.block/data-panel` (`agent.clj:177`). Both are "any value as
   hiccup", written independently, with different elision markers
   (`seon-value-*` vs `seon-data-*` class families). A value rendered through
   the walk and the same value rendered through `seon.render/render` directly
   produce different HTML.
3. **Two bounding codecs.** `seon.render.value/sample` and `seon.sci.admit/admit`
   both walk nested data with depth/width caps; the overlap is self-declared at
   `src/seon/render/value.cljc:14-23` and filed as
   `docs/seon/issues/value-admission-render-walk-overlap.md`. `data-panel` uses
   `admit` (`block.clj:969-974`); `render-html` uses `sample` (`value.cljc:798`).
4. **Rail ordering is not recency.** `ui.md:417-419` specifies "ordered by
   database transaction recency"; `focus-entity-ids` returns the agent entity
   followed by transcript ids in ascending-eid commit order
   (`agent.clj:294-297,164-168`). Ascending eid *is* commit order, so this is
   oldest-first, i.e. the inverse of what the target asks for.
5. **`derived` is a stub returning `[]`.** `src/seon/render/block.clj:245-260`
   — render-capable discovery over `:seon.fn`/`:seon.ns` facts is the named N5
   edge. Until it lands, "any required namespace can supply a renderer" has no
   discovery path at all on the block side, matching the unwired namespace step
   on the router side. **These are the same gap seen from two directions.**

### Missing

- **A durable pin.** `ui.md:425-431` specifies a tab-scoped session pin plus an
  agent-derived focus fallback. `$selected` (`agent.clj:306,319-323`) is a
  Datastar client signal that dies with the tab. No schema EDN declares a pin
  attribute; `rg 'pin' src/` returns no render hit.
- **A "last N modified" bound on the rail.** Today it is
  `max-collection` (`agent.clj:335-339`), the eval-result dial, reused. The
  owner wants ~3 visible with scrollback — that is a presentation dial, and
  reusing the eval cap here is the same magic-number-avoidance instinct
  producing the wrong number.
- **A configurable depth dial.** `distance` defaults to `1` at
  `block.clj:178` and there is no config fact. The one render config dial that
  does exist is `:seon.config.render/coalesce-ms` (`web.clj:291-298`), which
  proves the pattern is available.
- **`:seon.render/namespace` wiring.** One line in
  `seon.render.block/unit` (`block.clj:354-364`) — putting the agent's
  `:seon.cluster.agent/namespace` symbol on the unit — would activate the whole
  step. That is likely the smallest change with the largest alignment payoff.

## The quarry (`src-old`) — what the owner remembers, confirmed

The old agent page had exactly the layout the owner describes, and its
failures are instructive because the fresh tree repeats two of them.

- **2/3 primary + 1/3 right rail, confirmed.**
  `src-old/seon/ui/agent_view.cljs:84` —
  `[:div {:id "agent-view-layout" :class "grid grid-cols-3 gap-2 …"}]`;
  primary `col-span-2` at `:85-86`; right rail
  `[:aside {:id "agent-view-context" :class "agent-view-rail col-span-1 … overflow-y-auto"}]`
  at `:89-91`. The scrollback the owner remembers is that `overflow-y-auto`.
- **Every surface emitted twice**, primary and rail card, with Datastar
  `data-show` picking one (`agent_view.cljs:53-70`) — so switching focus was a
  pure client signal flip with no server round-trip. `seon.render.agent`
  reproduces this exactly (`agent.clj:299-326`, same `$selected` idiom).
- **Recency ordering was INTENDED AND DEAD.** The sort is
  `(sort-by (juxt (comp - ::surface/touch) ::surface/label) …)`
  (`agent_view.cljs:76-77`), `touch` defaults to `0`
  (`src-old/seon/render/surface.cljc:69`), and **no producer ever wrote
  `:seon.render.surface/touch`** — `driver.cljs:249-266` sets only
  `selection`/`label`. The rail was therefore alphabetical, with the recency
  machinery vestigial. Real ordering came from context-block priority
  (`src-old/seon/agent/ctx.cljc:1631`). **The fresh tree has the same shape of
  gap from the other direction: ordering is real (commit order) but is not
  recency.** Recency did work for *selection* — max transaction id over the
  agent's REPL-authored attributes, `src-old/seon/agent/ctx/canvas.cljc:37-45,116-124`.
- **No rail pinning, no "last N" cap** in the old UI either
  (`agent_view.cljs`, `driver.cljs:249-266` — every renderable block became a
  surface). The only `pinned` concepts are unrelated: pinned canvas *content*
  (`src-old/my/canvas.cljc:141-146`) and `pinned-syms` *suppressing* derived
  blocks (`src-old/seon/agent/ctx/render_fns.cljc:58,69-71`). **So the owner's
  pin is a genuinely new requirement, not a restoration.**
- **No responsive collapse in the old UI.** `grid-cols-3`/`col-span-2` carry no
  Tailwind breakpoint prefix and `output.css:711` emits `.grid-cols-3`
  unconditionally. Adaptation was container-query only
  (`input.css:346-374`). **The fresh tree is already ahead of the quarry here**
  (`input.css:1315-1325`).
- **Dual `ai`/`html` projection existed** (`src-old/seon/render/schema.cljc:29-30`,
  resolution at `src-old/seon/render.cljc:273-288`), and **schema-attached
  defaults existed** as a five-step ladder — literal string → literal hiccup →
  symbol → **schema default** → generic (`src-old/seon/render.cljc:1058-1086`,
  catalog lookup at `:1035-1045`). Fresh `seon.render/projection-declaration`
  is that ladder, tightened.
- **Namespace-override discovery existed and was COMPUTED, not conventional.**
  Any public agent-authored fn whose Malli *output schema* declares
  `:seon.render/ai` and/or `:seon.render/hiccup` was automatically discovered
  and became a block — `src-old/seon/agent/ctx/render_fns.cljc:23-46,67-81`.
  **This is precisely what `seon.render.block/derived` (`block.clj:245-260`)
  stubs to `[]`, and it is a better answer than the fresh
  `render-<kind>` name convention:** it is a computed rule over the program
  graph rather than a hand-shaped naming law, which is the house rule. The
  agent's home namespace also supplied lexical scope for bare handler symbols
  (`src-old/seon/agent/ctx/driver.cljs:47-68`,
  `src-old/seon/agent/home.cljc:31-33`).
- **Ref-following with depth did NOT exist.** `depth` in `src-old` bounded
  structural nesting only; Datahike entities were terminal opaque markers that
  deliberately stopped the walk (`src-old/seon/render/value.cljc:292-298,459-464`).
  **`seon.render.walk`'s distance-over-connections is genuinely new work, not a
  port** — which is why it is the part of the current tree that best matches the
  owner's framing.

## Open design questions

1. **Merge the two chains, or subordinate one?** Chain B's viewer-override map
   and chain A's namespace convention answer the *same* owner requirement
   ("override by defining a function in the agent's namespace") by different
   means — one data, one convention. Evidence that they were designed
   separately: `walk.clj:22-41` narrates a four-step chain that never mentions
   `:seon.render/namespace`, while `render.clj:5-16` narrates a four-step chain
   that never mentions redirect or overrides. **Recommendation to the owner:
   one chain, in `seon.render`, with redirect and overrides accreted onto it;
   `walk/projection` then deletes.**
2. **Which floor survives?** `data-panel`/`data-prose` are caps-required and
   use the one admission codec; `render-html`/`render-ai` are richer
   (schema-status reporting, `:seon.eval/opaque` tokens, width-aware pretty
   printing) and are the *quarry-proven* renderer. They cannot both be "the
   floor" — `render.clj:66` calls the floor "universal capability", which
   admits exactly one.
3. **Is rail recency a query or a derived read plan?** `ui.md:433-439`
   specifies renderer recency via "a bounded indexed history lookup over scoped
   inputs captured by the renderer's current runtime-observed database reads",
   which is a substantial mechanism. The cheap version — order rail cards by
   descending eid — is one `reverse` in `focus-entity-ids` and gets ~90% of the
   owner's stated want ("~last 3 modified"). Worth an explicit ruling before
   anyone builds the read-plan machinery.
4. **Does the pin belong in the database or the URL?** `ui.md:430` says
   "scoped to the tab's database-backed web-session location", which presumes a
   web-session entity that does not exist in fresh `src/`. A query parameter is
   the zero-schema option and is already the idiom for `/data`
   (`src/seon/render/data.clj:40-55`, `parse-cursor`).
5. **Should the override be a NAME CONVENTION or a COMPUTED rule?** The fresh
   router probes `<ns>/render-<kind>` by name (`render.clj:135-145`). The
   quarry instead *derived* render-capability from each function's Malli output
   schema (`src-old/seon/agent/ctx/render_fns.cljc:23-46`) — no naming law, and
   it works for a function called anything. The house rule ("no hand-maintained
   lists"; classification rules are COMPUTED, never name-based) favours the
   quarry's answer. Since `block/derived` is already the named N5 slot for
   exactly this, the question is whether the name convention should survive at
   all once the code graph can answer it.
6. **Does the package/keyframe conversion need doing at all right now?** The
   complete-snapshot design is correct-by-construction and measured; the
   keyframe design's wins are serialize-once fan-out and new-tab cost. Neither
   is currently a measured problem. This is a candidate for staying TARGET.

## Stale claims found in the existing research corpus

Two lines a future lane would reasonably act on are no longer true. Recorded
here rather than left to be rediscovered:

- `render-universality-audit-2026-07-28.md:81` names `seon.cluster.prompt` as
  "the largest second rendering system". It routes through the router today
  (`src/seon/cluster/prompt.cljc:183-196`).
- `render-universality-audit-2026-07-28.md:93` reports six catalog symbols
  under absent `seon.render.handlers.*` namespaces. `rg` finds zero occurrences
  of that namespace prefix in `src/` or `resources/`.

Both were true when written; the concurrent conversion lane the audit itself
notes at `:16-46` landed them. The audit is otherwise accurate.

## Skill drift

Both loaded skills are accurate on the points this audit touched. Two
refinements worth landing:

- **`datastar-web-ui`** lists the current routes and correctly marks packages/
  keyframes/deltas as TARGET. It does **not** mention `seon.render.walk`,
  `seon.render.agent/focus-html`, the primary+rail layout, or the two-chain
  precedence — an agent loading it to work on the render router would not learn
  that a second resolution chain exists. Its "Mark target UI explicitly" list
  says to treat "generalized surfaces/canvas controls" as design input, which
  is right, but the primary-panel + right-rail layout is **built** and is not
  distinguished from the tabled items. Recommend adding a short "the walk and
  the two chains" section with the `walk.clj:172-179` / `render.clj:166-171`
  citations.
- **`data-oriented-clojure`** cites `src/seon/schema/edn.clj` paths for schema
  registration; schema EDN itself lives at `resources/seon/schema/*.edn`, which
  the skill does state correctly at its "Current schema path" note. No drift
  found. Its claim that `:seon.render` work should reach for the router rather
  than a dispatch map is borne out by `render.clj:14-16`.

No factual error found in either skill. Both under-describe the walk.

## Pointers

- Router: `src/seon/render.clj`
- Walk: `src/seon/render/walk.clj`
- Blocks, expansion, floors: `src/seon/render/block.clj`
- Universal value renderer: `src/seon/render/value.cljc`
- Agent page + focus/rail: `src/seon/render/agent.clj`
- Root page: `src/seon/render/root.clj`
- Delivery: `src/seon/render/web.clj`
- Drill: `src/seon/render/data.clj`; grammar: `src/seon/render/hiccup.clj`
- Schema-attached defaults: `resources/seon/schema/{run,message,error}.edn`
- Contract schemas: `resources/seon/schema/{render,walk,block,render_value}.edn`
- Layout CSS: `resources/public/css/input.css:1243-1322`
- Target: `docs/seon/architecture/ui.md:411-451`
