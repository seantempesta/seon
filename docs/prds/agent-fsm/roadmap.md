---
type: prd
status: active
tags: [prd, agent, web, architecture]
---

# Agent-FSM roadmap — we are here → the target

The single **we-are-here** doc. The design docs ([[architecture]], [[data-model]],
[[agent-runtime]], [[ui]], [[toolkit]]) describe the system in present tense — the
target as it IS when built. THIS doc holds the current code state, the gap to that
target, and the dependency-ordered, file:line path to close it.

**Comprehensive means: every current→target divergence below gets a
REPLACE-IN-PLACE or a DELETE action. No parallel systems survive the migration —
no `foo-v2`, no "new ns to house the fix", no provider kept beside the seed.** The
whole tree is on `feature/agent-fsm`; atomic in-place refactors are the cheap
option. Verified against live code (ctx/render/web/data/routing audit).

Grounded in R's settled decisions: the R→U handoff, the reconciliation P0/P1/P2 +
orchestrator-root §3, the `:kind` audit, and the 7-step dependency order.

## Where the code is now vs. the target

Per domain — the current shape, the divergence, and the design doc that owns the
target shape.

- **Context / seed / override.** NOW: `seon.ctx` (`src/seon/ctx.cljs`, not moved)
  with `:seon.ctx/section` blocks stored under `:seon.agent/sections`; the default
  set is `core-default-ctx` (ctx.cljs:1604) **render-merged** over the agent's own
  vector by `gather-sections` (ctx.cljs:1737) inside `context-root`
  (ctx.cljs:1821-1845); per-agent mutation is `add-section!`/`remove-section!`/
  `reset-ctx!`/`update-ctx!` in `seon.agent`. TARGET: ns `seon.agent.ctx`,
  `:seon.agent.ctx/block` seeded by **seed-copy** into the agent's complete
  `:seon.agent/ctx` at creation; render reads that one collection priority-sorted;
  override is the scope-aware variadic `install!`/`remove!` — **no merge, no
  provider**. → [[ui]], [[data-model]] §4.2.
- **Purpose / planning.** NOW: `:seon.agent/purpose` (`:string`, agent.cljs:76) with
  the `set-purpose!` verb (agent.cljs:689) — both WORK today (live-verified
  2026-06-27: `set-purpose!` returns `{:seon.agent/ok? true …}`, the attr installs on
  first transact; the earlier "throws, never installed" note was stale). No plan tree.
  Phase 3 is therefore a clean DESIGN move (not a bug fix). TARGET: `:my.agent/purpose`
  as the first per-agent seed
  worked-example (schema + refine fn + self-refining block); planning = `my.todo`
  as a TREE (`:my.todo/parent` + derived roll-up), not a separate `:my.plan`. →
  [[data-model]] §5, [[agent-runtime]].
- **Lifecycle / root.** NOW: `create!` (agent.cljs ~396-444) seeds an agent + opens
  no run; `:seon.agent/parent` is registered (agent.cljs:80) and READ
  (lifecycle.cljs:83) but **has no writer**; no `start!`, no orchestrator-root, the
  `/`-world and "supervisor" are separate ideas. TARGET: creation = an IDLE entity;
  `seon.agent/start!` (a core verb granted to root, alias of `create!`, through the
  same `/call` gate) writes `:seon.agent/parent`; ONE `:seon.agent/id "root"` is BOTH
  UI-owner and orchestrator; roles = capability-sets, not a `:kind`. →
  [[agent-runtime]].
- **Domain schemas.** NOW: core `:seon.agent.todo/*` (todo.cljs:41-49) and the
  `my.kb.shared` singleton (kb/shared.cljs:38-49) exist; no `:my.todo/agent` scoping,
  no `:my.agent/*`, no data-ref global-vs-per-agent rule. TARGET: `my.kb` (no agent
  ref → global), `my.todo` (tree, `:my.todo/agent` → per-agent), `my.agent`; scope is
  a property of the DATA's agent-ref, never the block. → [[data-model]] §5,
  [[toolkit]].
- **`:kind` / `:type`.** NOW: the active CLJS pod has **zero stored entity-kind
  discriminators** — kind is already presence-derived (`:seon.entity/id-attr` →
  `entity-primary-kind`). Two value-enum flavors keep the word (`:seon.error/kind`,
  `:seon.warn/kind`); one doc slip (toolkit.md:436); one truly-bad discriminator is
  JVM-track-paused (`seon.ai/::type`). TARGET: lock the entity-kind-vs-value-enum
  rule, fix the slip, leave the value-enums. → [[data-model]] §3.
- **Web / routing / UI.** NOW: two competing UI stacks (the inspector datastar view
  and the packetstar tile/console path) with hand-rolled `case`/`cond`/`re-matches`
  dispatch across `serve`/`inspector`/`tile`; whole-region SSE replace; `:seon.tile/*`
  placement entities; a `/eval` 404; a dead broadcast stub. TARGET: reitit front door
  over `:seon.route/*` datoms, the capability gate UNCHANGED, one `world-layout` with
  the all-agents overview = the root agent's world, slots/tiles over
  `:seon.agent/ctx`, a per-connection `!last-tree` diff. → [[ui]].

## The build path (dependency-ordered)

Seven R-spine phases (context-model + seed + root) plus a parallel Lane-U track
(web/reitit/UI) gated on Phase 1's naming and Phase 5's route schema. Each step is a
REPLACE-IN-PLACE or DELETE; `SILENT-FAILURE` marks a missed stored-attr read that
returns an empty query instead of erroring.

> **Before coding ANY phase, read its rows in [[library-grounding]]** — the
> concrete `reference-code/…:LINE` map (datahike CAS/components/bridge, malli
> explain/humanize/`:orn`, SCI bounded-eval, reitit routes) that grounds the
> phase's claims in real source and demonstrates idiomatic Clojure. Guessing
> library semantics produces confident, wrong code; the read flips the mindset.

### Phase 1 — Ratify the `:seon.agent.ctx/*` naming (cross-lane, atomic)

Owner-ratified: the unit is **block**, ns `seon.ctx` → `seon.agent.ctx`, keywords
`:seon.agent.ctx/block|name|priority`; the collection attr stays `:seon.agent/ctx`
(component vector, bridges to `:db.cardinality/many`, order never relied upon). Ships
as ONE patch + `bin/seon cluster reset default`. Active CLJS track only; the paused
JVM `.clj` side (`seon.ctx.clj`, `ctx/history.clj`) stays on `:seon.ctx/*` (separate
store; reconcile if/when the JVM track resumes).

- **The ns move (CLJS):** `src/seon/ctx.cljs` → `src/seon/agent/ctx.cljs`;
  `src/seon/ctx/{inventory,live_tile,namespaces,relevant,transcript,usage,warnings}.cljs`
  → `src/seon/agent/ctx/*.cljs`. ~15 requirers update `[seon.ctx …]` →
  `[seon.agent.ctx …]` (`agent`, `agent/inspect`, `agent/message`, `agent/turn`, `ai`,
  `ai/anthropic`, `client`, `web/inspector`, `web/reactive/call`, `web/tile`, + the
  internal `ctx/*` requirers).
- **24 distinct `:seon.ctx/*` keywords (~240 occurrences) → `:seon.agent.ctx/*`**,
  with `section`→`block` and `section-html`→`block-html` renamed in the same sweep.
- **Block schema rename:** `:seon.ctx/section` (ctx.cljs:108) → `:seon.agent.ctx/block`;
  `:seon.render/ai {:optional true}` (ctx.cljs:112).
- **Stored attr rename:** `:seon.agent/sections` register! (agent.cljs:163) →
  `:seon.agent/ctx`. `SILENT-FAILURE` reads to retarget: `ctx.cljs:864`
  (`ctx-entities` pull), `ctx.cljs:1818-1819` (`agent-sections`/`context-root` pull),
  `ctx.cljs:1719`, `web/tile.cljs:548`, `web/tile.cljs:590`. Write sites:
  `agent.cljs:532,546,548,644,646,679,682`. Literal code-ref:
  `client.cljs:350`.
- Per-block input key `:seon.ctx/section` → `:seon.agent.ctx/block` at the producer
  (ctx.cljs:53) and reader (warnings.cljs:21).
- `:seon.ctx/section-html` register! (ctx.cljs:1557) → `:seon.agent.ctx/block-html`;
  holder `agent/inspect.cljs:41-48,105`.

This is the cross-lane atomic part: the same rename touches U's `web/tile.cljs`
Datalog reads. Grep-verify zero old keywords (final gate) before the reset.

### Phase 2 — KEYSTONE: delete the provider seam + render-merge → seed-copy + variadic `install!`/`remove!`

One rewrite resolves the P0 data-model contradiction AND every override divergence.
**There is NO render-merge and NO provider seam in the target** — never build
`set-blocks-provider!`/`!blocks-provider`/`default-blocks`/a public `core-blocks`
catalog. ALL blocks are seed-copied into the agent's own `:seon.agent/ctx` at
creation; render reads that complete collection sorted by `:seon.agent.ctx/priority`,
deduped app-level by `:seon.agent.ctx/name` (a plain `:keyword`, NOT a datahike
identity — see [[data-model]] §4.2). Renders are fns/symbols, never stored output
(see [[ui]]). Depends on Phase 1.

**2a — Add the override/seed verbs in `seon.agent.ctx`.**

- ADD `install!` — scope-aware + VARIADIC: `(install! single-map)` OR
  `(install! [vector-of-maps])`. Idempotent **upsert-by-`:seon.agent.ctx/name`**. At
  boot / no-agent-scope it builds the default seed set; in an agent's scope it targets
  THAT agent's `:seon.agent/ctx`.
- ADD `remove!` — drops a block by name (component cascade retracts the child).
- These REPLACE — and DELETE — `seon.agent`'s four per-agent verbs:
  `add-section!`(592), `remove-section!`(653), `reset-ctx!`(520), `update-ctx!`(534),
  plus the request/response schemas `::add-section-request`(559)/
  `::remove-section-request`(567)/`::section-response`(577) and
  `default-section-priority`(586). Move every call site to `ctx/install!`/`ctx/remove!`.
  No renamed-in-place duplicate left in `seon.agent`.

**2b — Delete the merge + provider machinery.**

- DELETE `gather-sections` (ctx.cljs:1737) — no render-time merge.
- REPLACE `context-root` (ctx.cljs:1821-1845): read the agent's OWN complete
  `:seon.agent/ctx`, decode blocks, sort by `:seon.agent.ctx/priority`, stop. The
  reader `agent-sections`(1714) → `agent-blocks` over `:seon.agent/ctx`;
  `decode-section`(1701) → `decode-block`.
- `core-default-ctx` (ctx.cljs:1604) → a PRIVATE **seed-set builder**
  (`default-seed-blocks`) consumed ONLY by `install!` at boot to copy the default set
  into a new agent. It is NOT read at render and is NOT public. DELETE the stale
  re-export `(def core-default-ctx ctx/core-default-ctx)` (agent.cljs:124).
- KEEP UNTOUCHED — the byte-stable cache split: `stable-boundary`/`split-context`/
  `stable-priority-max` + `:seon.render/stable-text|volatile-text|split-response`
  (ctx.cljs:1562-1603). KEEP `render-context`(1852) as the single prompt producer and
  `render-context-ai`(1905) (concat-by-priority + bracket + cache-split).
- Rename the remaining merge/prompt helpers in place: `rendered-section-texts`(1893)
  → `rendered-block-texts`; `section-bracket-ai`(1752) → `block-bracket-ai`.
- **DELETE the per-agent char budget** (owner decision 2026-06-27): `apply-agent-budget`
  (1761), `agent-section-char-budget`(1693), and the truncation marker. After
  seed-copy every block is the agent's own and the seeded blocks (transcript 53k /
  namespaces 18k) dwarf the old 8k cap; unbounded growth is bounded at the EVAL-OUTPUT
  layer instead (2g), and seeded blocks get rolling windows later (deferred milestone).
  The byte-stable tie-break loses the `:seon.ctx/agent?` tag → use `(juxt priority name)`.

**2g — Conditional eval-output bound (replaces the char budget).**

Bound the real growth source — agent eval output — at the eval-render layer, reusing
the SHIPPED value-renderer (`seon.render.value`) + result-mechanism (`result/<id>`),
NOT a ctx char budget. Two tiers: (1) **incidental** eval output keeps the bounded
skeleton (`render.value/sample` + `result-body-render-cap` ≈ 4k tokens) + the
`result/<id>` drill handle (already shipped); (2) **intentional drill** — when the
form REFERENCES a `result/<id>` var — renders with a loosened skeleton + a high cap
(`SEON_RESULT_DRILL_CAP` ≈ 50k tokens) so the agent SEES the data it asked for instead
of a re-skeleton. Broaden `result-var-ref?` (eval.cljs:750, today only the bare symbol)
to "the form's source references a `result/<id>` token." Full design + file:lines:
[[library-grounding]] "Output bounding."

**2c — Seed-copy at creation.**

`create!` (agent.cljs ~392-428) seeds the FULL block set into the new agent's
`:seon.agent/ctx` via `(ctx/install! [...])`. The seed vector is assembled from the
owning `my.*`/`seon.agent.ctx.*` nses, NOT from a central hardcoded catalog. (Phase 4
makes this seed run as recorded bootstrap forms; Phase 2 establishes that the seed
COPIES the complete set in, so render needs no default fallback.)
- **Seed ONLY on a genuinely new entity (`fresh?`-guard).** `create!` is idempotent
  and today re-call NEVER re-seeds (it keeps the agent's own purpose/sections — the
  `(and fresh? …)` guard at agent.cljs:415-418). The block seed-copy MUST ride the
  SAME `fresh?` gate, or a resumed agent's edited/removed blocks get clobbered back to
  defaults every boot. Seed-copy is creation-only; resume reads the agent's existing
  `:seon.agent/ctx` untouched.

**2d — Rename the wired blocks + the file-block factory (no central catalog).**

- Each `*-section` block fn → `*-block`, in its OWNING ns; these become entries in the
  seed vector, NOT registered into a `core-blocks` list. Keep priorities:
  `my.kb.shared/instructions-section` (10); `…namespaces/namespaces-section` (20);
  `…live-tile/live-tile-section` (35); `…warnings/warnings-section` (40);
  `seon.agent.todo.internal/open-todos-section` (45); `…relevant/relevant-source-section`
  (48); `…inventory/inventory-section` (97); `…transcript/transcript-section` +
  `transcript-section-html` (100). File blocks: soul (5), agents (8). Default new-block
  priority = 46.
- File-block factory: `file-section`(217)→`file-block`, `file-section-ai`(199)→
  `file-block-ai`, `file-section-html`(209)→`file-block-html`. KEEP the fresh-read
  reactive mechanism + soul/agents wiring (`soul-file-path`/`agents-file-path`).
- Update the symbol-wire docstrings naming the old producer: `ctx/transcript.cljs:21`,
  `ctx/namespaces.cljs:26`, `ctx/live_tile.cljs:4`, `ctx/inventory.cljs:6`,
  `ctx/relevant.cljs:5`, `my/kb/shared.cljs:97`, `ai.cljs:336`.

**2e — Render-engine word cleanup + the `slot` primitive (R; Lane U depends on it).**

- `twin` → `render` (render.cljs:130,133,301,306,396,483,515; live_tile.cljs:5,26,29,
  36,434,451,563,566; sci.cljs:510); `surface` → `render` (render.cljs:1 ns docstring;
  `entity-render-slot` arg); `panel`/`card` → `tile` in prose.
- Free the word `slot`: `resolve-slot`(606)→`resolve-render`,
  `entity-render-slot`(299)→`entity-render`, `missing-slot-render`(595)→`missing-render`.
- ADD the `(slot :name)` primitive generalizing the injected `:seon.render/render`
  handle (render.cljs:659-660), keyed on `:seon.agent.ctx/name`.
- DELETE the planned third-surface note `:seon.render/canvas` (render.cljs:2-3) — the
  word is now the focal block.
- FOLD `:seon.render.live-tile/content` (live_tile.cljs:314) into `:seon.render/html`
  on a block; `render-agent-tile`(386) becomes the canvas/tile block.
- KEEP `invoke-bounded`(sci.cljs:335) + `agent-authored-sym?`(92); broaden the scope
  wording to "any agent-authored render/layout/handler."

**2f — Inspector per-block breakdown (cross-lane; same patch).**

`ctx-sections`(ctx.cljs:1950)→`ctx-blocks`; keys `:seon.render/section-texts`→
`:seon.render/block-texts`, `:seon.render/section-html`→`:seon.render/block-html`.
`SILENT-FAILURE` U consumers to retarget: `web/tile.cljs:1001,1040,1104,1406`.

### Phase 3 — `:seon.agent/purpose` → `:my.agent/purpose` (the first seed worked-example)

Proves the seed/`install!` path end-to-end. (`:seon.agent/purpose` + `set-purpose!`
WORK today — live-verified; this is a clean DESIGN move to a per-agent `my.*`
worked-example, NOT a bug fix.) Depends on Phase 2. The
schema + attr + verb move into `my.agent.<id>` as a seeded example: register
`:my.agent/purpose` (a markdown goal string), a `refine` fn, and a self-refining
block. DELETE the old `:seon.agent/purpose` path — REPLACE, not parallel.

- DELETE `:seon.agent/purpose` register! (agent.cljs:76) and the `[:seon.agent/purpose …]`
  slots in the agent schemas (agent.cljs:302,382,444). Migrate the read sites:
  create!/upsert (agent.cljs:418), `render.cljs:378`, `client.cljs:1994,2310,2379`,
  `web/serve.cljs:377`, `ctx.cljs:1814`, `web/tile.cljs:105`.
- DELETE `set-purpose!` (agent.cljs:689) — its behavior becomes the seeded
  `my.agent`-owned refine fn, surfaced as a block, not a `seon.agent` core verb.
- Schema shape + the per-agent home detail land in [[data-model]] §5; the loop's view
  of purpose in [[agent-runtime]].

### Phase 4 — Bootstrap-as-seeded-forms

An agent's bootstrap is a **form-vector** carried with the seed. After `create!`/
`start!` transacts the idle entity, it runs those forms SYNCHRONOUSLY in the new
agent's scope, BEFORE any trigger can open a run, recording each as a `:seon.eval` row
with `:seon.eval/origin :core` so they are QUIET (the loop's turn-counter and wake
logic ignore them; no run opens). The agent SEES its own startup in its transcript /
program graph. The bootstrap forms ARE the agreed seed commands: the batched
`(ctx/install! [...])` from Phase 2c, `(schema/register! :my.agent/purpose …)` + the
refine fn from Phase 3, and the home-ns `defn`s. Depends on Phases 2-3. Full spec:
[[agent-runtime]].

- The loop stays strictly trigger-driven — creation leaves the agent IDLE; the FIRST
  run opens only on a trigger via the existing `[:db.fn/cas … :seon.agent/run nil …]`.
- KEEP unchanged: the run/turn/derived-state model (`seon.agent.run`, `seon.derive`),
  `seon.agent.loop` `transitions`/`transition`.

### Phase 5 — Orchestrator-root (`start!`, `:seon.agent/parent`, roles-as-capabilities, root base case)

The missing half of the model: root is an ordinary agent holding capabilities others
don't, not special core machinery. Depends on Phase 4 (it runs the child's bootstrap)
+ the `/call` gate. Full design: [[agent-runtime]]; root's `/`-world derivation:
[[ui]].

- ADD `seon.agent/start!` — a system-level `:seon.fn` GRANTED to root, alias of
  `create!`, called through the SAME `/call` capability gate (not a bypass). It
  transacts an idle child and WRITES `:seon.agent/parent` = caller. That write IS the
  activation of `:seon.agent/parent` (agent.cljs:80) — no separate writer needed,
  retiring its "no writer" status. The gate check: caller must hold the spawn
  capability (root does by grant; a normal agent does not unless granted).
- **Roles = capability-SETS, not a `:kind`/`:role` enum.** ADD no `:seon.agent/role`
  attr. Orchestrator = an agent granted the spawn/terminate/system `:seon.fn`s; worker
  = without. Differentiation is Datomic presence/absence at the `/call` gate.
- **Root base case.** Cluster boot seeds `:seon.agent/id "root"` the SAME way `start!`
  seeds a child, except root has NO `:seon.agent/parent` (it is the recursion's base
  case). Boot runs root's elevated bootstrap form-vector: install the system-scoped
  blocks, seed the `/`-world layout symbol on root's `/` route, grant the
  spawn/terminate/system fns. Recursion bottoms out: boot → seed-root →
  root.`start!`(child).
- **UI-root == orchestrator-root.** ONE entity. The `/`-world is DERIVED from root's
  system-scoped blocks; the "start an agent" affordance on `/` is a UI surface over
  root's `start!` through `/call`. No second supervisor/dashboard entity.
- **ADD the `seon.route` ns + `:seon.route/*` schema** (keyword-ns = code-ns rule),
  needed here to seed root's `/` route and by Lane U's reitit adoption. Register
  `:seon.route/pattern :string`, `:seon.route/method :keyword`, `:seon.route/name
  [:keyword {:seon.db/identity true}]`, `:seon.route/owner :seon.db/ref` (reference the
  canonical ref shape), `:seon.route/handler :symbol` (dedicated native
  `:db.type/symbol`, NOT a reuse of `:seon.render/html`), `:seon.route/middleware
  {:optional true} [:vector :keyword]`, and the `:seon.route` entity `:map`. Seed the
  **CORRECTED** core route set — the UI lane (owner-approved, commit `b11da421`)
  pivoted to the **hyperlith model** (view = f(db-as-of t) datastar morph over gzip
  SSE, replacing packetstar / `!last-tree` / since-t), changing Handoff #3: seed ONLY
  `/` (owned by root, the root world-layout handler) + `/agent/{id}` — **NO
  `/agent/{id}/feed`** (the GET shim and the live stream ride the SAME path; datastar
  opens the stream from the page); `/call` is **`/agent/{id}/call`** with the fn riding
  as a route-data **descriptor** — do NOT seed per-ns / per-fn routes; namespaces are
  NOT a routing level; hierarchical reitit with route-data inheritance; `db->routes`
  stays UI's. Schema detail: [[data-model]] §4.8.
- KEEP unchanged: the agent record core attrs (`:seon.agent/id|run|terminated-at|
  default-turn-limit|default-deadline-ms|schedules`).

### Phase 6 — `my.kb` / `my.todo` (tree) / `my.agent` domain schemas + data-ref scoping

Mostly independent; depends only on Phase 1's naming, so it can run in parallel with
Phases 3-5. Global-vs-per-agent is a property of the DATA's agent-ref, NEVER the block
or a `:kind`: `:my.kb.*` rows carry no agent ref → global (one KB, all agents);
`:my.todo/*` rows carry `:my.todo/agent` → per-agent; the render fn scopes by what it
queries. Schema tables land in [[data-model]] §5; the agent-facing verbs in [[toolkit]].

- **`my.todo` as a TREE.** Fold the `seon.agent.todo` → `my.todo` migration
  (todo.cljs:41-49 today) and ADD `:my.todo/parent` (`:seon.db/ref`) + a derived
  roll-up (parent progress = its children's). The work-list IS the plan tree (top =
  plans/milestones, leaves = actions). NOT a separate `:my.plan`. Add `:my.todo/agent`
  scoping (REPLACE the core `::owner` ref framing).
- **`my.kb`** stays global — the `my.kb.shared` singleton (kb/shared.cljs:38-49) is the
  exemplar (no agent ref). Index-everything applies; `my.*` renders full.
- **`my.agent`** — the per-agent home ns; `:my.agent/purpose` (from Phase 3) is its
  first member.

### Phase 7 — `:kind` doc-lock + toolkit slip + `:seon/error` consolidation + cleanup/index

Last, once the structural model is settled. The `:kind` situation is mostly
confirm/keep (the active pod has zero stored entity-kind discriminators).

- **Lock the entity-kind-vs-value-enum rule** in [[data-model]] §3 (BANNED = a
  stored field that selects WHICH schema a row obeys → identify by attribute presence
  / `:seon.entity/id-attr` → `entity-primary-kind`, or malli `:orn`/`m/parse`; FINE =
  a value-enum flavor, a derived label, a library shape). No code change for the active
  pod.
- **Fix the doc slip** in [[toolkit]] (toolkit.md:436): annotate `:seon.code/kind` as a
  DERIVED response label (which id-attr the forgotten entity carried), enum values =
  the namespace keywords, never persisted.
- **`:seon/error` consolidation (R).** REPLACE `:seon.render/error` (render.cljs:116)
  with the ONE base shape `[:map [:seon.error/message :string] [:seon.error/where
  :keyword] [:seon.error/symbol {:optional true} :symbol] [:seon.error/hint {:optional
  true} :string] …]`; ADD `:seon.error/where|symbol|hint` in `seon.error`. Stop
  aliasing `:seon.db/error` (it stays for the transact envelope, db.cljs:144-152, a
  real divergence). Consolidate the catch sites onto the one value: `missing-render`
  (render.cljs:595), `render` catch (663-666), `render-entity-html` catch (351-357),
  `render-entity-ai` catch (516-518), `live_tile/error-response`(559); ripple the
  readers that destructure the old envelope (render.cljs:142, render/live_tile.cljs:
  564-591, ctx/live_tile.cljs:65-67). `warnings-section`→`warnings-block`; ADD
  `check-render-health` to `seon.warn/checks` (warn.cljs:949-964) aggregating current
  `:seon/error` values into fix-oriented prose (pure derive, never stored). The error
  shape spec is owned by [[data-model]] §6; the error-TILE html render is U (Phase 8).
- **system-text stays fixed + non-overridable** (ctx.cljs:982-1101): reword "render
  twins"/"tile or panel hiccup" → render/block/tile, keep byte-identical. NOTE the
  per-request LLM-system seam survives one layer up — `seon.ai/effective-system-prompt`
  (ai.cljs:360-369) honors `:seon.ai/system-prompt`; that is separate from the ctx
  block set and is NOT eliminated.
- **Index-everything / show-`my.*`-full**: index all nses' valid forms while rendering
  only `my.*` in full. Lands in [[data-model]] §4.9 (the index half).
- **Deferred value-tag renames (do NOT do now).** `:seon.error/kind`/`:seon.warn/kind`
  are correct value-enums; renaming to `:seon.error/fault`/`:seon.warn/check` is the
  one silent-failure risk (`:seon.error/kind` is READ at db/internal.cljs:1092 to retag
  user-input vs core-bug; must be atomic across ~30 sites). KEEP + documented.
- **Deferred JVM-track (paused).** `seon.ai/::type` (the one truly-bad stored
  discriminator), `seon.repl/:form/type`, `render.clj/typed`+`view-type` — fix when the
  JVM track resumes.

### Phase 8 — Lane U: reitit front door + slots/tiles + the live channel (parallel)

`seon.web.serve`, `seon.web.inspector`, `seon.web.tile`, `seon.web.reactive.*`, new
`seon.ui.*`. Gated on Phase 1 (naming) + Phase 5 (the `:seon.route/*` schema + seeded
`/`). The capability gate (`seon.web.reactive.call`) is UNCHANGED. Target design:
[[ui]].

- **Adopt reitit.** ADD `reitit-core` + `reitit-ring` + `reitit-malli` (vendored
  `reference-code/reitit` 0.10.1, `.cljc`). ADD `db->routes` (~10 lines) + a ~20-line
  Node↔Ring adapter; generalize the `createServer` var-rereading wrapper
  (serve.cljs:704-710) to a per-request router thunk.
- **REPLACE the hand-rolled dispatch** with reitit: `serve/handler` method-`case` +
  GET/POST `cond` (serve.cljs:594-634), `complete-path->agent-id`(419) +
  `handle-complete-agent!`(389); `inspector` `route?`(1585)/`handle!`(1845)/
  `parse-agent-id`(1551); `tile` `route?`(1527)/`handle!`(1532) + the 8
  `re-matches`/`re-find` blocks. Seed core routes `/`, `/agent/{id}`,
  `/agent/{id}/feed`, `/call`, `/eval`, `/chat`, `/agent/{id}/complete`,
  `/agent/{id}/app/{x}`. KEEP the gate verbatim
  (`resolve-owning-agent`/`granted-fn?`/`capability-check`/`invoke!`/the `/call`
  handler) — move ONLY `/call`'s registration (serve.cljs:620) to a seeded route datom;
  KEEP `transform-hiccup` (transform.cljs:183-197) + wiring (render.cljs:439-452);
  `same-origin?`(serve.cljs:572-592) → a reitit keyword middleware on POST route-data.
- **FIX the `/eval` 404.** `packetstar.js:125` posts to `POST /eval?agent=…` but
  `serve.cljs` has only `/chat`→`handle-chat!`(427). ADD `handle-eval!` + the `/eval`
  route (seed a `:seon.route/*` row once reitit lands).
- **Collapse the two UI stacks to ONE `world-layout`.** REPLACE — do not keep both:
  world `console-shell`(tile.cljs:1333) → `world-layout` (seon.ui), canvas = the focal
  comms block (`input-form` 1316 + commentary); RETIRE the older datastar consumer view
  (inspector.cljs:1023). The all-agents overview = the root agent's world
  (`:seon.agent/id "root"`) rendered by the SAME layout, not a separate dashboard:
  `/agents` → `/`; `list-agents-data`(103)/`agents-index-page`(1283)/
  `agent-grid-tile`(1068) → the system-scoped, query-across-all-agents variant of
  `world-layout`; `::index` SSE key → the root-world subscriber. Consolidate the two
  tx-listeners (`db/listen! ::inspector` inspector.cljs:1715; `::listener`
  tile.cljs:1170) and the two push registries (inspector 1624-1689; tile 1109-1146)
  onto one streamer + one SSE framing.
- **Slots + tiles + placement-as-blocks** (depends on Phase 2e's `slot` primitive):
  `console-region`/`region`(tile.cljs:1242-1251) → `(slot :name)` keyed on
  `:seon.agent.ctx/name`; `render-context-html`'s flat `[:section {:data-section name}]`
  dump (ctx.cljs:1936) → slot placement (`data-section` → `data-slot`). REPLACE the
  `:seon.tile/*` placement entities — `default-tiles`(700)/`console-tiles`(724)/
  `find-tile`(739), `:seon.tile/console|id|span` — with `:seon.agent/ctx` blocks sorted
  by `:seon.agent.ctx/priority` placed into layout slots; `find-tile` → lookup by
  `:seon.agent.ctx/name`; `:seon.tile/span` becomes a CSS/layout concern. KEEP the ~9
  core view fns (tile.cljs:643-652) as `:seon.render/html` symbols re-homed as blocks'
  html renders; `prebuilt-views`(661) → entries in the Phase-2 seed block set (NOT a
  `core-blocks` catalog). KEEP the lean `core-views` symbol-resolution table
  (tile.cljs:329-332,641-642) + `live-result-value`(324-338) `globalThis.result` read —
  **preserve this invariant** (no `seon.eval`/bootstrap compiler in the web bundle).
- **ADD the `!last-tree` per-connection slot-tree BFS diff** (neither UI has it today —
  both whole-region replace).
- **The error-TILE html render** (the human-facing half of Phase 7's `:seon/error`
  value): the warnings-block's html render = an error-tile list.
- **DELETE the dead A-6 broadcast stub:** `open-sse!`(serve.cljs:175-196),
  `!sse-connections`(69), `open-sse-connections`(71), and `serve-root!`'s 302→`/agents`
  (164-173). `/` becomes the root agent's world route.
- **KEEP hand-rolled** (reitit has no streaming/file primitives): static
  `serve-static!`(serve.cljs:137-158) in the adapter or a catch-all route; the raw
  SSE-open + same-origin guard (the SSE handler returns `{:seon.http/hijacked true}` so
  the adapter does not double-write).
- **FIX brand drift:** inject `SEON_BRAND_CSS` (`brand-css-style`, inspector.cljs:706)
  on the tile/console `head` too (tile.cljs:1228).
- **DECIDE the home of non-vocab pages:** the `/data` live browser (`data-scan`
  inspector.cljs:1359) and the debug overlay (inspector `/agent/<id>/debug` :848 + tile
  `/tile/debug/<id>` :1399 → one developer page, likely `/agent/{id}/app/debug`). KEEP
  the prompt-faithful derivation.

## Lane split (R vs U)

Two lanes, both must agree; Phase 1's rename is the cross-lane atomic part.

- **R — core context / schema / seed / render-engine + orchestrator-root verbs.**
  `seon.agent.ctx` (the moved ns), `seon.agent`, `seon.render`, `seon.warn`,
  `seon.error`, `seon.route` (schema/seed), the `my.*` domain schemas, the `:kind`
  generalization in `seon.db`/`seon.schema`. Owns Phases 1-7 (the `:seon/error` VALUE,
  the warnings-block AI render, `start!`/`:seon.agent/parent`/roles/bootstrap).
- **U — `seon.ui.*` / web / reitit / css.** The slot/layout/`!last-tree` diff, the
  page system, reitit consumption, the error-TILE render. Owns Phase 8.
- **Cross-lane atomic:** the `:seon.agent/sections`→`:seon.agent/ctx` +
  `:seon.ctx/section`→`:seon.agent.ctx/block` rename touches U's `web/tile.cljs` Datalog
  reads; a missed stored-attr read fails SILENTLY (empty query, not an error). Phase 2f
  (`ctx-blocks` keys) and Phase 8's tile consumers are the cross-lane reads.

## Tests to update (same units — they reference the renamed surface)

- `test/seon/ctx_test.cljs` → `agent/ctx_test.cljs` — `install!`/`remove!` (replacing
  `add-section!`/`remove-section!`) + `:seon.agent.ctx/name` (esp. 491-658, 978-1016).
- `test/seon/gym/driver.cljs:323,785-787` + `driver_test.cljs:506-507` —
  `:seon.gym.profile/sections` reads `:seon.agent.ctx/name` off `:seon.agent/ctx`.
- `src/seon/agent/inspect.cljs:41-48,105` — the `:seon.agent.ctx/block-html` holder.
- Purpose tests → `:my.agent/purpose` (Phase 3); `start!`/`:seon.agent/parent` writer
  tests (Phase 5); `my.todo` tree roll-up tests (Phase 6).

## Final gate

- **Grep-verify ZERO** `:seon.agent/sections` and `:seon.ctx/section` across `src/` +
  `my.*` + `acme/` before the reset (web Datalog reads of a missed attr fail silently
  with empty results). Also verify no surviving `core-default-ctx`, `gather-sections`,
  `set-blocks-provider!`, `!blocks-provider`, `default-blocks`, or public `core-blocks`
  — the keystone leaves no parallel override path standing.
- **One `bin/seon cluster reset default`** — the pod re-seeds the core from the indexed
  codebase; the new world boots with seed-copied `:seon.agent/ctx`, root's `/` route,
  and the bootstrap forms recorded as quiet `:core` evals.
- **Live proof:** a fresh world renders an agent's prompt from its own
  `:seon.agent/ctx` (no merge), `set`-equivalent via `(ctx/install! …)` upserts
  by name, `start!` writes `:seon.agent/parent`, and `/` derives from root.

## Cross-cutting milestones (owner directives 2026-06-27)

These run alongside / after the phases, not as a separate lane.

- **Live DeepSeek feedback ASAP.** As soon as Phases 1-2 land enough to run an agent,
  drive a live DeepSeek agent (pre-authorized) and READ its actual outputs — are the
  instructions good? Real-output feedback gates "is the context working," not just
  green tests. Every subsequent phase ships a live-drive observation.
- **Context-quality audit — no overlap, no repetition, colocate.** Review the seeded
  ctx blocks against the LIVE rendered prompt for (a) bad/stale context, (b) OVERLAPPING
  context (the same fact stated in two blocks — e.g. transcript masthead vs
  shared-instructions vs the fixed system-text), (c) colocation (relevant context
  together, not scattered). Prune duplicates; one fact, one home. Pairs with the live
  DeepSeek drives (look at what the model actually receives). Aligns with the
  show-don't-tell / align-context-with-runtime standing guidance.
- **Rolling window on unbounded blocks (DEFERRED).** The transcript (53k chars live)
  and similar grow without bound; add a rolling window (last-N turns / since-T) so
  growth is bounded by VIEW, not a char cap. Not blocking the phases; do after the
  seed-copy + eval-output-bound model is proven live.
