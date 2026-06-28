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

- **Now:** **PHASES 1 + 2-keystone + 2e + reitit ALL DONE + live-proven; full suite
  646 tests / 0 fail (200s).** **coord-#12 (slot/render convergence) LANDED `690ae2b8`**
  — the override-proof gap is closed. SEAM for U: `slot`/`render` now consume the
  `{:seon.render/hiccup …}` envelope via one `unwrap-response` (render.cljs); the slot/world
  ERROR path routes through the **overridable** `seon.render.live-tile/error-response` (acme's
  existing `set!` override flows through unchanged). **→ U: swap acme's error `set!` to that
  surface + fix the ui.md "Total override" table; coord-#6 (delete legacy) unblocked on this
  half.**
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
- **In flight (acme, my isolated runtime):** a live **DeepSeek drive** building an interactive
  todo tile on the new UI, captured for a **dedicated observer** (next). Then the observer's
  findings route: prompt/context/toolkit → you, UI/render/routing → me.
- **Next (autonomous, gated):** observer → **#6 delete the legacy stack** (packetstar.js +
  inspector datastar-view + `:seon.tile/*` + the A-6 stub + the `legacy-default` delegation) —
  GATED on the observer confirming the new world UI is sufficient (the legacy console is the only
  remaining fallback); I verify parity in acme before+after, revert via git if it breaks → #17
  feed reconnect-hardening → #18 time-travel. Full Phase-1 report lands in this file + a research
  doc for the owner's morning.
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
