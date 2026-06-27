---
type: prd
status: draft
tags: [prd, agent, web, architecture]
---

# Layout-Context Unification — migration plan

The grounded, file:line migration to the unified block / render / tile / slot /
layout / route system (vocabulary + design: [[architecture]] §Glossary +
[[layout-context-unification-design-2026-06-27]]). Produced by a six-subsystem
code audit (ctx, render, web, data, routing, drift) verified against live code.

Grouped by lane — **R** = core context / schema / seed / render-engine; **U** =
`seon.ui.*` / web / css / reitit — and dependency-ordered. `SILENT-FAILURE` marks
a missed stored-attr read that returns an empty query instead of erroring.

**Item 0 (the atomic rename) ships as ONE patch + `bin/seon cluster reset
default`.** Grep-verify **zero** `:seon.agent/sections` and `:seon.ctx/section`
across `src/` + `my.*` + `acme/` before the reset.

## Lane R — core context / schema / seed / render-engine

`seon.agent.ctx` (was `seon.ctx`), `seon.agent`, `seon.render`, `seon.warn`,
`seon.error`, new `seon.route`.

### 0. The atomic `section`→`block` rename + `seon.ctx`→`seon.agent.ctx` ns move (one patch)

Both the `section`→`block` rename AND the `seon.ctx`→`seon.agent.ctx` ns move ship
in the SAME atomic patch + ONE `bin/seon cluster reset default`. Scope = the ACTIVE
CLJS track only; the PAUSED JVM `.clj` side (`seon.ctx.clj`, `ctx/history.clj` + its
JVM requirers) STAYS on `:seon.ctx/*` (separate store; reconcile if the JVM track
resumes). Naming coherence stays clean: `:seon.agent/ctx` (the agent's block VECTOR
attr) vs `seon.agent.ctx` (the NS defining blocks) vs `:seon.agent.ctx/block` (the
block schema) — the agent OWNS a `ctx` of blocks defined in `seon.agent.ctx`.

**0a. The ns move — measured blast radius (CLJS track):**

- **8 CLJS files MOVE:** `src/seon/ctx.cljs` → `src/seon/agent/ctx.cljs`;
  `src/seon/ctx/{inventory,live_tile,namespaces,relevant,transcript,usage,warnings}.cljs`
  → `src/seon/agent/ctx/*.cljs`.
- **~15 CLJS requirers** update `[seon.ctx …]` → `[seon.agent.ctx …]`: `agent`,
  `agent/inspect`, `agent/message`, `agent/turn`, `ai`, `ai/anthropic`, `client`,
  `web/inspector`, `web/reactive/call`, `web/tile` (+ the internal `ctx/*`
  requirers: `live_tile`, `namespaces`, `transcript`, `warnings`).
- **24 distinct `:seon.ctx/*` keywords (~240 occurrences) → `:seon.agent.ctx/*`:**
  `agent`, `agent-attrs`, `children`, `core-authored`, `data`, `file-path`, `fn`,
  `get-ctx-request`, `get-ctx-response`, `instance-id`, `keys`, `live-tile-test`,
  `messages`, `name`, `namespace`, `priority`, `render-namespace-response`,
  `retrieval-query-request`, `section`(→`block`), `section-html`(→`block-html`),
  `strip-markers`, `updated-at`, `uploads`, `user-input`. (The `section`/`section-html`
  pair ALSO renames to `block`/`block-html` in the same sweep.)
- **~9 CLJS test files:** `ctx_test.cljs`, `agent_loop_test`, `gym/driver`,
  `gym/driver_test`, `instrument_smoke_test`, `render/live_tile_test`,
  `web/reactive/transform_test`, `internal_boundary_test`,
  `agent_context_test.cljs.disabled`.

**0b. The `section`→`block` rename (within the moved files):**

- Schema: `:seon.ctx/section` → `:seon.agent.ctx/block` (ctx.cljs:108 →
  agent/ctx.cljs); make `:seon.render/ai {:optional true}` (ctx.cljs:112).
- Stored attr: `:seon.agent/sections` → `:seon.agent/ctx` register! (agent.cljs:163).
- **SILENT-FAILURE** read sites of `:seon.agent/sections`: `ctx.cljs:864`
  (`ctx-entities` pull `{:seon.agent/sections [*]}`); `ctx.cljs:1818-1819`
  (`agent-sections`/`context-root` pull); `ctx.cljs:1719`
  (`(:seon.agent/sections entity)`); `web/tile.cljs:548` (context Datalog);
  `web/tile.cljs:590` (narration Datalog).
- Write sites (same patch): `agent.cljs:532` (reset-ctx! retract); `:546,548`
  (update-ctx!); `:644,646` (add-section! → add-block!); `:679,682`
  (remove-section! → remove-block!).
- Code-ref (literal keyword in the home-ns whitelist, not a query):
  `client.cljs:350` `:seon.agent/sections`.
- Verbs in `seon.agent`: `add-section!`(592)→`add-block!`, `remove-section!`(653)→
  `remove-block!`; `::add-section-request`(559)/`::remove-section-request`(567)/
  `::section-response`(577) → `::add-block-request`/…; `default-section-priority`(586)
  → `default-block-priority`. `add-block!` accepts html-only + optional ai.
- `reset-ctx!`(520)/`update-ctx!`(534): KEEP names, retarget to `:seon.agent/ctx`.
- Per-block input key `:seon.ctx/section` → `:seon.agent.ctx/block` at the producer
  (ctx.cljs:53) and the reader (`warnings.cljs:21`).

### 1. The override seam (mirror `seon.schema/set-tee-fn!`, schema.cljc:183)

- `core-default-ctx`(ctx.cljs:1604) → **`core-blocks`** (PUBLIC); **DELETE** the
  stale re-export `(def core-default-ctx ctx/core-default-ctx)` (agent.cljs:124).
- ADD `!blocks-provider` (`defonce ^:private` atom), `set-blocks-provider!`
  (`[:=> [:cat fn?] :nil]`; throwing provider → `core-blocks` + a loud error
  block), `default-blocks` (guarded read).
- `context-root`(ctx.cljs:1821-1845) reads `default-blocks`; expose one pure
  `(db, agent-id) → merged-priority-sorted-blocks` fn.
- Update symbol-wire docstrings naming the old producer: `ctx/transcript.cljs:21`,
  `ctx/namespaces.cljs:26`, `ctx/live_tile.cljs:4`, `ctx/inventory.cljs:6`,
  `ctx/relevant.cljs:5`, `my/kb/shared.cljs:97`, `ai.cljs:336`.

### 2. Merge + prompt-assembly helpers (cache split MUST survive untouched)

- `gather-sections`(1737)→`gather-blocks`; `agent-sections`(1714)→`agent-blocks`;
  `decode-section`(1701)→`decode-block`.
- `rendered-section-texts`(1893)→`rendered-block-texts`; `section-bracket-ai`(1752)
  →`block-bracket-ai`; `agent-section-char-budget`(1693)→`agent-block-char-budget`.
- KEEP UNTOUCHED: `stable-boundary`/`split-context`/`stable-priority-max` +
  `:seon.render/stable-text|volatile-text|split-response` (ctx.cljs:1562-1603).
- KEEP `render-context`(1852) the single prompt producer; `render-context-ai`(1905)
  keeps concat-by-priority + bracket + cache-split.

### 3. The wired default blocks — rename each `*-section` → `*-block`

In its owning ns AND in `core-blocks` (keep priorities): `my.kb.shared/instructions-section`
(10); `seon.ctx.namespaces/namespaces-section` (20); `seon.ctx.live-tile/live-tile-section`
(35); `seon.ctx.warnings/warnings-section` (40); `seon.agent.todo.internal/open-todos-section`
(45); `seon.ctx.relevant/relevant-source-section` (48); `seon.ctx.inventory/inventory-section`
(97); `seon.ctx.transcript/transcript-section` + `transcript-section-html` (100). File blocks:
soul (5), agents (8). `default-block-priority` = 46.

### 4. The markdown-file block factory

`file-section`(217)→`file-block`, `file-section-ai`(199)→`file-block-ai`,
`file-section-html`(209)→`file-block-html`. KEEP the fresh-read reactive mechanism
+ soul/agents wiring (`soul-file-path`/`agents-file-path`).

### 5. Inspector per-block breakdown + the html-render shape (touches U — same patch)

- `ctx-sections`(1950)→`ctx-blocks`; keys `:seon.render/section-texts`→
  `:seon.render/block-texts`, `:seon.render/section-html`→`:seon.render/block-html`.
  **SILENT-FAILURE** U consumers: `web/tile.cljs:1001,1040,1104,1406`.
- `:seon.ctx/section-html` register!(1557)→`:seon.agent.ctx/block-html`; holder in
  `agent/inspect.cljs:41-48,105`.

### 6. Render engine word-cleanup + the slot primitive

- `twin` → `render` everywhere (render.cljs:130,133,301,306,396,483,515;
  live_tile.cljs:5,26,29,36,434,451,563,566; sci.cljs:510).
- `surface` → `render` (render.cljs:1 ns docstring; `entity-render-slot` arg);
  `panel`/`card` → `tile` in prose.
- Free the word `slot`: `resolve-slot`(606)→`resolve-render`, `entity-render-slot`(299)
  →`entity-render`, `missing-slot-render`(595)→`missing-render`.
- ADD the `(slot :name)` primitive generalizing the injected `:seon.render/render`
  handle (render.cljs:659-660).
- **DELETE** the planned third-surface note `:seon.render/canvas` (render.cljs:2-3)
  — the word is now the focal block.
- KEEP `invoke-bounded`(sci.cljs:335) + `agent-authored-sym?`(92); broaden scope
  wording to "any agent-authored render/layout/handler."
- FOLD `:seon.render.live-tile/content` (live_tile.cljs:314) into `:seon.render/html`
  on a `:seon.agent.ctx/block`; `render-agent-tile`(386) becomes the canvas/tile block.

### 7. The friendly error value + warnings/render-health

- REPLACE `:seon.render/error` (render.cljs:116) — re-register as
  `[:map [:seon.error/message :string] [:seon.error/where :keyword]
  [:seon.error/symbol {:optional true} :symbol] [:seon.error/hint {:optional true}
  :string]]`; stop aliasing `:seon.db/error` (which stays for the transact
  envelope, db.cljs:144-152, a separate concern).
- ADD `:seon.error/where|symbol|hint` in `seon.error`.
- Consolidate the catch sites onto the one value: `missing-render`(595),
  `render` catch(663-666), `render-entity-html` catch(351-357), `render-entity-ai`
  catch(516-518), `live_tile/error-response`(559). RIPPLE readers that destructure
  the old envelope: `render.cljs:142`, `render/live_tile.cljs:564-591`,
  `ctx/live_tile.cljs:65-67`.
- `warnings-section`→`warnings-block`; ADD `check-render-health` to
  `seon.warn/checks` (warn.cljs:949-964), aggregating current `:seon.render/error`
  values into fix-oriented prose; pure derive, never stored. ADD the warnings-block
  html render (an error-tile list).

### 8. New `seon.route` ns (keyword-ns = code-ns rule)

`:seon.route/*` REQUIRES a `seon.route` code ns. Register `:seon.route/pattern
:string`, `:seon.route/method :keyword`, `:seon.route/name [:keyword
{:seon.db/identity true}]`, `:seon.route/owner :seon.db/ref` (reference the
canonical ref, never inline), `:seon.route/handler :symbol` (dedicated, native
`:db.type/symbol` — NOT a reuse of `:seon.render/html`), `:seon.route/middleware
{:optional true} [:vector :keyword]`; a `:seon.route` entity `:map`. Seed core route
rows — incl. seeding the **root agent** (`:seon.agent/id "root"`) and its `/` route
(`:seon.route/owner` = root, `:seon.route/handler` = the root world-layout symbol).
Agent app routes (`/agent/{id}/app/{x}`) are agent-transacted, capability-gated,
handler in the agent's own `my.agent.<id>` ns, route name per-agent namespaced
(e.g. `:agent.abc/app-x`).

### 9. system-text stays a fixed, non-overridable const (ctx.cljs:982-1101)

Reword "render twins" / "tile or panel hiccup" → render/block/tile; keep
byte-identical. NOTE: a per-request LLM-system override still exists one layer up
— `seon.ai/effective-system-prompt` (ai.cljs:360-369) honors `:seon.ai/system-prompt`;
that LLM-system seam is separate from the ctx block set and is NOT eliminated here.

**KEEP, unchanged:** the agent record (`:seon.agent/id|purpose|run|parent|
terminated-at|default-turn-limit|default-deadline-ms|schedules`); the
run/turn/derived-state model (`seon.agent.run`, `seon.derive`); `seon.agent.loop`
`transitions`/`transition`.

## Lane U — `seon.ui.*` / web / css / reitit

`seon.web.serve`, `seon.web.inspector`, `seon.web.tile`, `seon.web.reactive.*`,
new `seon.ui.*`.

### 10. Adopt reitit (depends on R-8 schema)

ADD `reitit-core` + `reitit-ring` + `reitit-malli` (vendored
`reference-code/reitit` 0.10.1, `.cljc`; only file/resource handlers are
`#?(:clj)`-gated). ADD `db->routes` (~10 lines) + a ~20-line Node↔Ring adapter;
the `createServer` var-rereading wrapper (serve.cljs:704-710) generalizes to a
per-request router thunk (`reloading-ring-handler`, ring.cljc:420).

### 11. REPLACE the hand-rolled dispatch with reitit (depends on 10)

- `seon.web.serve/handler` method-`case` + GET/POST `cond` (serve.cljs:594-634);
  `complete-path->agent-id`(419) + `handle-complete-agent!`(389).
- `seon.web.inspector` `route?`(1585)/`handle!`(1845)/`parse-agent-id`(1551).
- `seon.web.tile` `route?`(1527)/`handle!`(1532) + the 8 `re-matches`/`re-find` blocks.
- Seed core routes `/`, `/agent/{id}`, `/agent/{id}/feed`, `/call`, `/eval`,
  `/chat`, `/agent/{id}/complete`, `/agent/{id}/app/{x}`.
- KEEP the capability gate verbatim (`seon.web.reactive.call`:
  `resolve-owning-agent`(63-80), `granted-fn?`(82-96), `capability-check`(98-115),
  `invoke!`(126-167), `/call` handler 203-268); move ONLY `/call`'s registration
  (serve.cljs:620) to a seeded route datom — body unchanged. KEEP `transform-hiccup`
  (transform.cljs:183-197) + its wiring (render.cljs:439-452).
- `same-origin?`(serve.cljs:572-592) → a reitit keyword middleware on POST route-data.

### 12. FIX the `/eval` 404 (task #4)

`packetstar.js:125` posts Clojure forms to `POST /eval?agent=…` but `serve.cljs`
has only `/chat`→`handle-chat!` (:427), no `/eval`. ADD `handle-eval!` +
the `/eval` route (or seed a `:seon.route/*` `/eval` row once reitit lands).

### 13. RESOLVE the two-UI-stacks drift — pick ONE world layout (root world + per-agent worlds)

The packetstar tile/console path is closer to target. There is ONE `world-layout`;
the all-agents overview is the ROOT AGENT's world (`:seon.agent/id "root"`) rendered
by that same layout, NOT a separate dashboard stack.

- world: `console-shell`(tile.cljs:1333) → `world-layout` (seon.ui); canvas = the
  focal comms block (`input-form` 1316 + commentary); retire the older datastar
  consumer view (`/agent/<id>`, inspector.cljs:1023).
- root agent's world: `/agents` → `/`; `list-agents-data`(103)/`agents-index-page`(1283)/
  `agent-grid-tile`(1068) → the root agent's world-layout (the system-scoped, query-
  across-all-agents variant of `world-layout`); `::index` SSE key → the root world
  subscriber. Seed the `root` agent + the `/` route owned by it.
- Consolidate the two tx-listeners into one streamer (`db/listen! ::inspector`
  inspector.cljs:1715; `db/listen! ::listener` tile.cljs:1170). Consolidate the
  push registries (`push-agent!/index!/data!` 1624-1689; `push-tile!/console!/debug!`
  1109-1146). Converge SSE framing onto one.

### 14. Slots + tiles + placement-as-blocks (depends on R-0)

- `console-region`/`region`(tile.cljs:1242-1251) → the `(slot :name)` primitive
  keyed on `:seon.agent.ctx/name`; `render-context-html`'s flat `[:section {:data-section
  name}]` dump (ctx.cljs:1936) → slot placement (`data-section` → `data-slot`).
- REPLACE the `:seon.tile/*` placement entities — `default-tiles`(700)/
  `console-tiles`(724)/`find-tile`(739), `:seon.tile/console|id|span` — with
  `:seon.agent/ctx` blocks sorted by `:seon.agent.ctx/priority` placed into layout slots;
  `find-tile` → lookup by `:seon.agent.ctx/name`. KEEP `:seon.agent.ctx/priority`;
  `:seon.tile/span` becomes a layout/CSS concern.
- KEEP the ~9 core view fns (tile.cljs:643-652) as `:seon.render/html` symbols
  re-homed as blocks' html renders; `prebuilt-views`(661) → the `core-blocks` catalog.
- KEEP the lean `core-views` symbol-resolution table (no `seon.eval` in the web
  bundle — tile.cljs:329-332,641-642) and `live-result-value`(324-338) direct
  `globalThis.result` read. **Preserve this invariant.**

### 15. ADD the `!last-tree` per-connection slot-tree BFS diff

Neither UI has it today (both whole-region replace).

### 16. DELETE the dead A-6 broadcast stub

`open-sse!`(serve.cljs:175-196), `!sse-connections`(69), `open-sse-connections`(71),
and `serve-root!`'s 302→`/agents`(164-173). `/` becomes the root agent's world route.

### 17. KEEP hand-rolled (reitit has no streaming/file primitives)

Static `serve-static!`(serve.cljs:137-158, `node:fs`) in the adapter (or a
catch-all route); the raw SSE-open + same-origin guard (the SSE handler returns
`{:seon.http/hijacked true}` so the adapter does not double-write).

### 18. FIX brand drift

Inject `SEON_BRAND_CSS` (`brand-css-style`, inspector.cljs:706) on the tile/console
`head` too (tile.cljs:1228) — every page head branded.

### 19. DECIDE the home of non-vocab pages

The `/data` live browser (`data-scan` inspector.cljs:1359 — admin vs app) and the
debug overlay (inspector `/agent/<id>/debug` :848 + tile `/tile/debug/<id>` :1399 →
one developer page, likely `/agent/{id}/app/debug`). KEEP the prompt-faithful derivation.

## Tests to update (same unit — these reference the renamed surface)

- `test/seon/ctx_test.cljs` — `add-section!`/`remove-section!` + `:seon.agent.ctx/name`
  (esp. 491-658, 978-1016).
- `test/seon/gym/driver.cljs:323,785-787` + `driver_test.cljs:506-507` —
  `:seon.gym.profile/sections` reads `:seon.agent.ctx/name` off `:seon.agent/sections`.
- `src/seon/agent/inspect.cljs:41-48,105` — the `:seon.ctx/section-html` holder.

## Final gate

One atomic commit for item 0 (section→block, `:seon.agent/sections`→`:seon.agent/ctx`,
the twin→render and card→tile word renames); **grep-verify ZERO `:seon.agent/sections`
and `:seon.ctx/section` across `src/` + `my.*` + `acme/`** before `bin/seon cluster
reset default` (web Datalog reads of a missed attr fail silently with empty results).
