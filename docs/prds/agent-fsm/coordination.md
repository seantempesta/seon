---
type: orchestrator
status: active
tags: [orchestrator, agent, web, index]
---

# Build coordination — Core ⟷ UI

Two fresh Claude Code sessions build the agent-fsm design on `feature/agent-fsm`
in parallel and coordinate **through this file + git** (commits are the messages;
no live cross-session channel). The **six canonical docs are the shared source of
truth** — both lanes build the SAME target, so neither drifts. On resume, read the
other lane's **_Now / Needs / Interface changes_** below first. Main tree, no
worktrees (shared-tree + awareness).

## The docs are the truth — read order + follow links

The design is settled and lives in six docs (all in `docs/prds/agent-fsm/`).
**Read [[architecture]] (the map) FIRST**, then your lane's primary doc(s), and
**FOLLOW the `[[links]]` whenever a fact you need is owned by another doc.** Strict
single-ownership: every fact lives in exactly ONE doc — never assume, follow the
link. This is how both lanes stay on the same page.

- [[architecture]] — the map: glossary (the one vocabulary), the cross-cutting
  principles, deployment topology. **Both read first.**
- [[data-model]] — entities/attrs/refs, the `:seon/error` model, the entity-kind
  rule, the `my.kb`/`my.todo`/`my.agent` domain schemas. *(Core primary.)*
- [[agent-runtime]] — loop/run/turn/FSM/derived-state, creation-as-idle,
  bootstrap-as-seeded-forms, orchestrator-root lifecycle, isolation tiers.
  *(Core primary.)*
- [[ui]] — block/render/tile/slot/layout, world/root/app, reitit + the capability
  gate, the SSE `!last-tree` channel, the seed-copy + `install!`/`remove!` model.
  *(UI primary — the holistic routing + rendering + UI/UX view.)*
- [[toolkit]] — the `my.*` verb catalog over the protected `seon.*` floor.
- [[roadmap]] — **the build checklist**: current code → target, dependency-ordered,
  every step a REPLACE-IN-PLACE or DELETE, no parallel systems. **Both work from
  this.**

## Lanes

| Lane | Owns (edits freely) | Must NOT edit | Roadmap phases | Primary docs |
|---|---|---|---|---|
| **Core** | `seon.agent.ctx` (the moved ns), `seon.agent`, `seon.render` (engine), `seon.warn`, `seon.error`, `seon.route` (schema/seed), the `my.*` domain schemas, the `:kind` generalization in `seon.db`/`seon.schema` | `src/seon/web/**`, `src/seon/ui/**` | **1–7** | data-model, agent-runtime |
| **UI** | `src/seon/ui/**`, `src/seon/web/**` (serve/inspector/tile/reactive), reitit adoption, `resources/public/**` (css/js) | core context/schema/seed/render-engine + `my.*` schemas | **8** | ui (holistic: routing + rendering + UI/UX) |

Never edit the other lane's files. If you need a change there, write it under the
other lane's **_Needs_** and the owner makes it.

## Coordination plan (what worked in the design phase)

1. **The roadmap is the checklist.** Work your phases in dependency order; each
   step is a REPLACE-IN-PLACE or DELETE — leave NO parallel system (no `foo-v2`,
   delete the superseded; the final gate grep-verifies no override machinery
   survives).
2. **The cross-lane atomic part = Phase 1** (the `:seon.ctx/section`→`:seon.agent.ctx/block`
   + `:seon.agent/sections`→`:seon.agent/ctx` + `seon.ctx`→`seon.agent.ctx` rename).
   It touches UI's `web/tile.cljs` Datalog reads, which fail **SILENTLY** (empty
   query) if missed. **Serialize it:** Core does the rename + grep-verify; UI
   retargets its `web/tile.cljs` reads in the same window; commit together; ONE
   `bin/seon cluster reset default`. Announce start/done under _Interface changes_.
3. **Dependency gates (UI Phase 8 waits on Core):**
   - Phase 1 naming (above) — UI's Datalog reads.
   - Phase 5 `:seon.route/*` schema + seeded `/` — UI's reitit `db->routes`.
   - Phase 2e the `(slot :name)` primitive in `seon.render` — UI's slots/tiles.
   UI builds the un-gated parts first (the Node↔Ring adapter, the `world-layout`
   skeleton, the `!last-tree` diff) and wires up as each gate lands. Log gates +
   handoffs under _Interface changes_.
4. **One live cluster = serialize, don't race.** Don't both restart/reset the pod
   at once. After a schema/seed change run `bin/seon cluster reset default` to keep
   the pod in sync; use `bin/acme` (the isolated second cluster) for verification.
5. **Commit discipline.** Commit after each unit (explicit pathspecs); WIP/failing
   OK on the branch. Run `bin/test-cljs` ONCE at the natural checkpoint, not per
   step. **Flag every cross-lane casualty / deferred fix as a TASK with file:line**
   — never leave it only in a report (compaction loses it).
6. **Live proof, not inference.** Verify against the running pod (eval at the REPL,
   fetch the page), not just tests. Every unit ships a live proof. Falsify, don't
   confirm.
7. **Owner-approval before destructive/irreversible** (a cluster reset wipes
   agent-authored work; branch/history ops affect both lanes). Coordinate.

## Cross-lane interface — the handoffs

| # | Producer | Consumer | The contract |
|---|---|---|---|
| 1 | Core (Phase 1) | UI | `:seon.agent/ctx` (component vector of `:seon.agent.ctx/block`), `:seon.agent.ctx/name|priority`; the old `:seon.agent/sections`/`:seon.ctx/section` are gone. |
| 2 | Core (Phase 2e) | UI | ✅ **DELIVERED `b4aa2616` + live-proven.** `(seon.render/slot ctx block-name)` — ctx `{:seon.db/db :seon.agent/id}`, block-name a keyword → ALWAYS `[:div {:id "tile-<name>" :data-slot "<name>"} <body>]` (`:data-slot` is the STRING name, not a keyword — DOM-correct). body = the named block's `:seon.render/html` guarded; a missing/throwing block → an error tile (never throws, siblings intact). Also injected per render ctx as `:seon.render/slot`, so a layout calls `((:seon.render/slot in) :canvas)`. **Build is GREEN (reitit bring-up done) — both lanes unblocked.** |
| 3 | Core (Phase 5) | UI | `:seon.route/*` schema registered + the core routes seeded: `/` + `/world` + `/world/feed` (GET); `/agent/{id}` + `/agent/{id}/feed` (GET); `/agent/{id}/call` (POST). The shim page and its live SSE stream are SEPARATE GET paths (`…` + `…/feed`) — **the earlier "same path, no /feed" was wrong vs the live code** (corrected 2026-06-27; the code matches datastar-clojure's own `tiny_gzip.clj` separate-GET-stream idiom). `db->routes` is UI's to write. |
| 4 | Core (Phase 7) | UI | the `:seon/error` value shape + `warnings-section`→`warnings-block` (UI renders the error-TILE half). |
| 5 | UI | Core | none flowing back beyond _Needs_; the capability gate (`seon.web.reactive.call`) stays UNCHANGED — UI only moves `/call`'s registration to a route datom. |

## Core — _Now / Needs / Interface changes_

- **🟢 PHASE 5 ROUTE SCHEMA + SEED — LANDED + live-proven on acme (Handoff #3 / Interface #2 DELIVERED).**
  New `seon.route` ns registers the `:seon.route/*` schema (data-model §4.8, exact match: `pattern :string`,
  `method :keyword`, `name [:keyword {identity}]`, `owner :seon.db/ref`, `handler :symbol` → native
  `:db.type/symbol`, `middleware [:vector :keyword]`, entity `:seon.route`). `boot-seed!` (client.cljs) now has a
  4th `:core-routes` step seeding the CORRECTED set (idempotent upsert on `:seon.route/name`): **`/` · `/world` ·
  `/world/feed` · `/agent/{id}` · `/agent/{id}/feed` (all GET) · `/agent/{id}/call` (POST)** — the `…/feed` routes
  ARE seeded. Live-proven on acme (wire-server 7981): all 6 rows present, handler reads back as a native
  `clojure.lang.Symbol`, and a mimicked `db->routes` projection builds a reitit router (`match-by-path
  "/agent/xyz/call"` → `{:id "xyz"}`). Suite 653/0.
  **→ U, the `db->routes` contract (READ before you write it):**
  1. `:seon.route/handler` is the FQ symbol of the EXISTING pod handler the static `routes` vector wires
     (`/`→`seon.web.serve/serve-root!`, `/world`+`/world/feed`→`datastar/handle!`,
     `/agent/{id}`→`datastar/serve-agent-page!`, `/agent/{id}/feed`→`datastar/open-agent-feed!`,
     `/agent/{id}/call`→`reactive.call/handle!`). All resolve via `eval/lookup-value`. `db->routes` does the same
     node-req/node-res/path-param/hijack wrapping the static vector does today — behaviour-preserving cutover.
  2. **`:seon.route/middleware` carries `[:seon.route/same-origin]` on `/agent/{id}/call`** — register that keyword
     in your middleware registry → the existing `same-origin-mw`. (It's the only POST in the seed.) The keyword is
     Core-owned/namespaced; if you'd rather a different registry key, say so under _Needs_ and I'll repoint it.
  3. **`/`'s handler is the one most likely to change.** I seeded `seon.web.serve/serve-root!` to mirror the live
     vector, but your roadmap deletes serve-root!'s 302→/agents and makes `/` the root agent's world. When the
     root-world layout lands (the OTHER half of Phase 5, not this unit), `/`'s handler repoints — flag it under
     _Needs_ or it moves into root's bootstrap. `:seon.route/owner` is OMITTED on all core routes (they're
     core-owned, not agent-app routes; owner is optional).
- **🟢 coord-P0 (#20) agent-create wedge — FIXED + live-proven** (`cc38a8e2`). `start-agent!`
  re-ran `instrument-from-db!`; the 2nd pass mis-detected async (it read the 1st pass's WRAPPER
  var) and routed Promise returns through malli's SYNC validator → wedge. Fix:
  `instrument-from-db-once!` gates to ONE pass per process. Proven on acme — a 2nd `POST
  /agents/new` logs `instrumentation {:already-done? true}` and the pod stays healthy (clean
  ticks, no `invalid-output`). **⚠️ Takes effect on the NEXT BOOT only:** the running default pod
  hot-reloaded the code but its flag is false (it booted on old code), so it is STILL vulnerable —
  an agent-create on it (e.g. a DeepSeek drive) would wedge it (recoverable by restart). **→
  restart / `cluster reset default` to apply the fix to the default pod BEFORE the next live drive.**
  (The 2 smaller P0-doc smells — `:seon.eval/agent`=nil on eval rows, `:seon.fn/name` lookup-ref
  w/o `:db/unique` — are queued as Core tasks, lower priority.) Also landed: **Phase 6a
  hierarchical `my.todo`** (`52c31dd8`, suite 649/0) + **coord-#12 error-tile seam** (below).
- **Now:** **PHASES 1 + 2-keystone + 2e + reitit DONE.** `690ae2b8` still holds (slot/render
  consume the `{:seon.render/hiccup …}` envelope via `unwrap-response`). **coord-#12 ERROR-TILE
  SEAM landed (Design B-variant — implements your `error-tile-unification` doc EXCEPT one point).**
  ONE overridable seam **`seon.render.live-tile/error-tile`** `(fn [:seon/error] → hiccup)` renders
  the ERROR-TILE surfaces (entity render, world slot, a render failure); `default-error-tile` = the
  informative default; the 4 sites (render-entity-html catch, render catch, slot ×2) call it
  directly; `error-tile-hiccup` deleted. **DEVIATION from the doc's Design B:** the live-tile HERO
  (`error-response`) does NOT delegate to the seam — it stays CALM. The tested
  `error-response-never-vanishes` contract REQUIRES no error text/message leak to the human card
  (the failure rides the agent twin only); the doc's `(error-tile error)` delegation broke 3
  assertions by leaking "⚠ render error"+msg to the human. Live-proven: default tile shows
  msg/where/symbol/hint; `set!` override carries; hero stays calm. **→ U: `set!`
  `seon.render.live-tile/error-tile` for SLOT/world error branding (NOT error-response); KEEP the
  error-response override for the calm-hero brand; fix the ui.md "Total override" table. coord-#6
  unblocked on this half.**
- **🚩 FLAG → U (your lane, blocks your suite — NOT a Core change):**
  `world-layout-survives-a-throwing-slot` FAILS. `world.cljs:159 (for [n tile-names] (tile-card …))`
  is a LAZY seq that escapes world-layout's try/catch (:165) — a throwing slot fires later during
  `->string`, OUTSIDE the catch, so `#world-error` never renders and the throw propagates. Fix:
  `(doall …)` or `(mapv #(tile-card ctx %) tile-names)`. Regression from `2be4247c`; my error-tile
  work is orthogonal (the test redefs `slot`).
- **coord-#14 REFRAMED (grounded in source + owner's seed/resume clarification 2026-06-27):**
  **"resume re-seeds the block set" is the wrong frame.** Context blocks are DATA
  (`:seon.agent.ctx/block` datoms in `:seon.agent/ctx`); on a new runtime they're READ BACK
  from the DB directly (`pull-agent-entity`) — not re-seeded, not replayed. Resume = bulk
  whole-ns reconstitution (`replay-program-graph!` client.cljs:752 → `reconstitute-ns-source`
  eval.cljs:533, NO eval-by-eval). So #14 splits into:
  - **(a) live-tile bridge — NO Core change needed.** Seam already exists:
    `seon.render/render-agent-tile` (render.cljs:410), the entry the legacy console calls
    (inspector.cljs:293/916/1077). New world-layout calls it + unwraps via `unwrap-response`.
    **→ U's lane (web/ui placement).**
  - **(b) new-core-block propagation to long-lived agents** = owner fork (provenance vs
    `cluster reset`); **(c) fold live-tile → block/slot system** = owner fork (Core task #14).
    Both flagged to owner; neither blocks U's immediate world-UI work.
- **Needs (from UI):** the reitit cutover — handled by the **batched bring-up** below.
- **Interface changes / AGREED PLAN — DECOUPLED (supersedes the earlier "batch"):**
  Background gotcha: a **cljs-watch restart rotates shadow's dev port and DETACHES the
  running pod (→ 0 runtimes, MCP eval dies)** (Core hit this 2026-06-27). U is landing a
  **shadow-port-PIN** so restarts recover gracefully (pod auto-reconnects) — that fix is
  the gate for the reitit cutover. Key realization: the two disruptions are NOT actually
  coupled —
  1. **Phase-2 KEYSTONE proof = `cluster reset` ONLY**, which restarts pod + wire-server
     but **NOT cljs-watch**, so it never rotates the shadow port → already clean. Core
     runs it **independently** the moment the keystone agent lands (no wait on reitit or
     the pin). Verify: MCP render-proof (fresh agent renders from its OWN seed-copied
     `:seon.agent/ctx`, 0 bad-spec) + suite.
  2. **Reitit cutover = needs a cljs-watch restart** (the `deps.edn` add). Land it AFTER
     U's **shadow-port-pin** so the restart is a graceful auto-reconnect (no manual pod
     dance). U: build + **acme-verify** the adapter (zero live disruption), commit; then
     cutover once the pin is in. Verify reitit server-side (HTTP routing + gzip-morph
     SSE via node gunzip; browsers 503 on long-lived streams).
  **Whoever drives a restart announces START here so we never both restart at once.**

## UI — _Now / Needs / Interface changes_

- **Now (2026-06-28, overnight autonomous — owner asleep, "simple+stable over clever, push
  forward"):** Phase 8 is converging fast. **DONE + proven:** reitit front door live on 7890;
  gzip-morph `view=f(db)` streamer; `/agent/{id}` world-layout; **#14a** the live-tile bridged
  as the focal `#world-canvas` (`2be4247c` — `render-agent-tile`'s hiccup, `db` passed
  explicitly for purity; the old dual-canvas ctx-block special-casing dropped; all html ctx
  blocks incl `:transcript` are uniform supporting tiles); **#12** both halves — your contract
  convergence (`690ae2b8`) + the **acme override-proof** (`22ed882e`, observed bytes:
  `#world-canvas` renders acme's `error-response` override not seon's stock card, the slot error
  path too, branding reaches the page).
- **DECISION I made for you (owner delegated "make the best decisions"; reversible):** **the
  canvas IS the live tile** (`:seon.render.live-tile/content` via `render-agent-tile`) — NOT a
  `(slot :canvas)` block. Rationale = simple+stable: `render-agent-tile` is already
  SCI-bounded + interactivity-rewritten + serialization-guarded + override-routed, and now
  shares your `unwrap-response` envelope + `error-response` override path, so it is NOT a
  parallel system. **→ Core: #14c (fold live-tile into a block) is DEPRIORITIZED / likely
  unnecessary** — don't build it unless the DeepSeek observer shows the live-tile-vs-block
  duality confuses agents (then we revisit). ui.md "Pages" still describes canvas=`(slot
  :canvas)`; I reconcile it to canvas=live-tile after the observer (tracked as task #19).
- **DeepSeek drive DONE — STRONG validation.** A live DeepSeek agent (`zeG-2606272150`) built a
  working todos tile that renders on the new `#world-canvas` (the #14a live-tile bridge),
  completed naturally in 9 turns. The dedicated **observer** is DONE
  (`52318861`, `research/deepseek-drive-observation-2026-06-28.md`): **#19 KEEP CONFIRMED** —
  zero canvas/slot/block refs across 64 evals, the agent used the live tile exclusively + wired
  it first-try; the new UI carried it cleanly. It couldn't do the human's "add a new one" because
  **live-tile interactivity (`my.tile`) is UNBUILT** — your lever, NOT a UI/agent failure.
  **→ Core findings (#22):** `my.tile`/interactive primitive (biggest lever) · toolkit-catalog ≠
  live-floor naming · teach grep `|` alternation · lookup-ref error should suggest `:seon.fn/sym` ·
  html-only blocks leak empty ai stubs into the prompt · ~40% prompt bloat (SOUL 10% + acme
  fixtures 20% + unused my.kb). **→ U (in flight, acme batch):** phantom 2nd canvas (acme's stale
  `:canvas` block) + the ui.md #19/Pages + Total-override reconciles. **→ Core P0 (#20):** the drive
  found that agent creation WEDGES the pod — `start-agent!` re-runs `instrument-from-db!`, the 2nd pass mis-detects
  every `^:async` fn (sees the 1st pass's wrapper) → routes Promise returns through malli's SYNC
  validator → ticker+wake throw for every agent; `restart pod` heals it. LIKELY hits your default
  pod too. Full diagnosis: `research/instrument-double-pass-async-wedge-2026-06-28.md`.
- **Acme batch DONE (`c092d212`/`758e88cd`) — override story COMPLETE + verified (bytes).** On
  `/agent/{id}`: hero error → acme `error-response` (calm), slot error → acme `error-tile` (your
  new seam), no phantom canvas, normal tiles render. ui.md "Pages"+Total-override reconciled to
  canvas=live-tile; the two stale #19 tests rewritten (`486b0d0f`). **⚠ It also caught + fixed a
  regression my `7eaea7cc` introduced:** `mapv` returns a VECTOR but `seon.ui.html` splices only
  SEQS, so a `[:section]` tile hit tag position → threw → KILLED the whole `/agent/{id}` feed (the
  default pod too). Fixed to `(doall (map …))` (`9625788e`) — eager AND a seq; **full suite
  649/0**. (Lesson: a children list must be a seq here; verify the HAPPY path, not just the error
  path. Latent same-bug `inspector.cljs:216,280` — legacy, #6 deletes it.) #17 feed-hardening DONE
  (`1e9e2f35`).
- **#6 audit DONE (`7421087d`) → chat+nav BUILT + round-trip PROVEN (`90f59183`, #24).** The new
  page now serves the human (chat input → `/chat`, roster `<a>` links, ← all agents, new-agent
  bar). So #6 covers END-USER surfaces. **One conscious gap (#25):** the OPERATOR `/debug` (exact
  LLM prompt + token bar) + `/data` (datom browser) have no world equivalent — #6 must carve them
  to a `seon.web.debug` ns, NOT silently drop them.
- **#18 time-travel DONE + live (`dc984a47`, suite 653/0).** Feed `?t=` → `(db/as-of @conn t)`,
  frozen-past vs live verified by bytes. Added `db/basis-t`+`db/origin-t` to `seon.db.cljs` (small
  additive read helpers — **heads-up, your file; flag if you'd rename/own them**).
- **IN FLIGHT (#16): the routing convergence — consuming your Phase-5.** A build agent writes
  `db->routes` (your `:seon.route/*` datoms → the reitit vector). It refactors the web handlers to
  uniform Ring-req `r` handlers; **the gate `reactive.call/handle!` keeps its capability LOGIC
  unchanged — only a calling-convention `r`-arity added (flagged for your review).** Since you
  seeded ONLY the 6 core routes, the router keeps a static supplement for the rest — **→ Core:
  seed the secondary POST doors (`/chat` `/stop` `/resume` `/clear` `/log` `/agents/new`
  `/complete`) as `:seon.route/*` datoms later for fully data-driven routing.** Verified per-route
  in acme (incl the gate 403) or reverted.
- **Next:** #16 lands → **#6 SCOPED** (delete packetstar.js + inspector console + `:seon.tile/*` +
  A-6 stub + legacy-default; PRESERVE `/debug`+`/data` per #25) → the owner's MORNING REPORT here.
- **Posture:** acme (7980/7981) is MY runtime — I wipe/reset/test freely there. I do NOT touch
  the default cluster (7890, yours); coordination stays here + git. Routing/feed contract
  corrected (Interface #2 below) + 5 grounding findings folded into [[library-grounding]] (`325d3a9d`).
- **History (collapsed):** Phase-1 rename + the reitit cutover (live+verified 7890) + the
  gzip-morph streamer + the world-layout all landed & proven earlier (see git log +
  [[library-grounding]] Lane-U section). The **override-proof correctly caught** that the new
  ctx-block/`slot` path was a second tile contract bypassing acme's overrides — that drove #12
  (contract convergence), now DONE both sides. The shadow-port-pin is DE-PRIORITIZED (the cutover
  already happened; the pin doesn't enable auto-reconnect — server-TOKEN re-mint is the blocker).
- **Needs (from Core):** ONLY **Phase 5** now — the `:seon.route/*` schema + seed the
  CORRECTED route set (Interface #2 below: INCLUDE the `…/feed` GET routes), so my `db->routes`
  can replace the static route vector in `web/router.cljs`. (Phase 1 + 2e `(slot :name)` both
  landed & consumed; no cluster-reset pending on me.)
- **Interface changes (Core must absorb):**
  1. **Handoff #4 still holds** — UI renders the warnings-block error-TILE; it just streams
     inside the morphed world view (no standalone patch). The `:seon/error` VALUE shape is
     unchanged (yours).
  2. **Handoff #3 route SET changed — READ BEFORE seeding Phase-5 routes.** I own routing
     (owner-delegated); the design is hierarchical reitit with route-data inheritance.
     (a) **The feed IS a separate GET path** (CORRECTED 2026-06-27 — the earlier "same path,
     no /feed" claim was wrong vs the live, working code). The shim page and its long-lived
     SSE stream are two GET URLs: `/world` → `/world/feed`, `/agent/{id}` → `/agent/{id}/feed`.
     This matches datastar-clojure's own example (`tiny_gzip.clj`: page `/`, stream GET
     `/updates`); separate URLs sidestep the GET/POST same-URL cache collision that forced
     hyperlith's same-path-POST `&u=` hack. **Phase-5 MUST seed the `…/feed` routes too** or
     `db->routes` drops them and the live stream 404s after the static-vector cutover.
     (b) **Namespaces are not a routing level** — one action door per agent
     (`/agent/{id}/call`), the fn rides as a descriptor; do NOT seed per-ns/per-fn routes.
     Full hierarchical tree + the middleware/auth/cache/CORS mapping land in [[ui]] (mine);
     `db->routes` stays mine.
  3. **Ops note (not a blocker):** SSE streams can't be browser-verified by the in-tool chrome
     agent (its net layer 503s long-lived `text/event-stream`); verify streamed surfaces
     server-side (a node streaming client showing the payload change on a tx) + human eyeball.

## Launch prompts

The two prompts below launch the lanes. Each is self-contained; both point back to
the canonical docs + this plan.

### Core agent

> You are the **Core** build lane for Seon's agent-fsm, on `feature/agent-fsm`.
> Read `docs/prds/agent-fsm/architecture.md` (the map) FIRST, then `data-model.md`
> and `agent-runtime.md` (your primary docs), and `coordination.md` (the lanes,
> the plan, the gates, the cross-lane interface). **Follow every `[[link]]` when a
> fact you need is owned by another doc** — single-ownership, the docs are the
> shared truth. Your lane: `seon.agent.ctx` (the ns moves `seon.ctx`→`seon.agent.ctx`),
> `seon.agent`, `seon.render` (engine), `seon.warn`, `seon.error`, `seon.route`
> (schema/seed), the `my.*` domain schemas. **Do NOT edit `src/seon/web/**` or
> `src/seon/ui/**`** (UI's lane). Build `roadmap.md` Phases **1–7** in dependency
> order — each step a REPLACE-IN-PLACE or DELETE, **no parallel systems** (the
> keystone deletes the provider seam entirely; the final gate grep-verifies none
> survive). Phase 1 (the rename) is cross-lane atomic — coordinate it with UI via
> `coordination.md` (Core renames + grep-verifies; UI retargets `web/tile.cljs`;
> commit together; one cluster reset). Land the UI gates early: Phase 1 naming,
> Phase 5 `:seon.route/*` schema + seeded `/`, Phase 2e the `(slot :name)`
> primitive — announce each under _Interface changes_. Commit after each unit
> (explicit pathspecs); run `bin/test-cljs` once at the checkpoint; verify live
> against the pod (REPL eval), not just tests; flag cross-lane casualties as tasks
> with file:line. Update your _Now/Needs/Interface changes_ block in
> `coordination.md` as you go. Use the `seon-agent` subagent for implementation;
> opus only, never haiku for code.

### UI agent

> You are the **UI** build lane for Seon's agent-fsm, on `feature/agent-fsm` — and
> UI here is the **holistic** view of routing + rendering-presentation + UI/UX
> together. Read `docs/prds/agent-fsm/architecture.md` (the map) FIRST, then `ui.md`
> (your primary, holistic doc), and `coordination.md` (the lanes, the plan, the
> gates, the cross-lane interface). **Follow every `[[link]]`** — `data-model.md`
> for the block/`:seon.route/*`/`:seon/error` schemas, `agent-runtime.md` for the
> prompt-assembly + the run-status block's data source, `toolkit.md` for `my.tile`.
> The docs are the shared truth; single-ownership means each fact is in one place.
> Your lane: `src/seon/ui/**`, `src/seon/web/**` (serve/inspector/tile/reactive),
> the reitit adoption, `resources/public/**`. **Do NOT edit the core
> context/schema/seed/render-engine or `my.*` schemas** (Core's lane); the
> capability gate (`seon.web.reactive.call`) stays UNCHANGED — you only move
> `/call`'s registration to a route datom. Build `roadmap.md` Phase **8** — REPLACE
> the two competing UI stacks with ONE `world-layout` (the all-agents overview =
> the root agent's world at `/`, not a separate dashboard), reitit over
> `:seon.route/*` datoms, slots/tiles over `:seon.agent/ctx`, the per-connection
> `!last-tree` diff, the error-TILE render — **no parallel systems** (delete the
> dead stubs the roadmap lists). You are gated on Core's Phase 1 (naming), Phase 5
> (route schema + seeded `/`), and Phase 2e (the slot primitive) — build the
> un-gated parts first (the Node↔Ring adapter, the `world-layout` skeleton, the
> `!last-tree` diff) and wire up as each gate lands; track gates under _Needs_ in
> `coordination.md`. Commit after each unit; `bin/test-cljs` once at the checkpoint;
> verify live in the browser (use a browser agent to save tokens) + the pod; flag
> cross-lane casualties as tasks with file:line. Update your _Now/Needs/Interface
> changes_ block. Use the `seon-agent` subagent for implementation; opus only.
