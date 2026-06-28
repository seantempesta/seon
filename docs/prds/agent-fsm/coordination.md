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
| 3 | Core (Phase 5) | UI | `:seon.route/*` schema registered + the core routes seeded: `/` (root world) + `/agent/{id}` (GET shim + same-path live stream — NO `/agent/{id}/feed`), `/agent/{id}/call`, `/eval`; `db->routes` is UI's to write. |
| 4 | Core (Phase 7) | UI | the `:seon/error` value shape + `warnings-section`→`warnings-block` (UI renders the error-TILE half). |
| 5 | UI | Core | none flowing back beyond _Needs_; the capability gate (`seon.web.reactive.call`) stays UNCHANGED — UI only moves `/call`'s registration to a route datom. |

## Core — _Now / Needs / Interface changes_

- **Now:** **PHASE 1 DONE + `cluster reset default` ALREADY RUN (by Core) + live-proven
  — UI: do NOT re-run the reset.** Reset boot clean (replay 6/6, 410 fns instrumented
  0 bad-spec, fresh agent `PGh-2606271755`); render-proof = 40k-char prompt with new
  `;;; ┌─` brackets, write→commit→read→retract green, **full suite 635 tests / 0 fail**.
  **PHASE 2 KEYSTONE now in flight** (a Core agent: seed-copy + variadic
  `install!`/`remove!`, DELETE the merge/provider seam AND the char budget). Then 2e
  (render words + the `slot` UI gate) → 2f → 2g → Phase 3 ∥ Phase 6.
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

- **Now:** **📍 LATEST (both lanes near compaction):** #13 branding DONE (`9d87dffe` — new world
  shim routes through the brand seams; acme brand reaches `/agent/{id}`). Error-tile unification
  DESIGNED + handed to you: `docs/prds/agent-fsm/research/error-tile-unification-2026-06-27.md`
  (#12 — the seam MUST be `seon.render.live-tile/error-tile` per the require cycle, FOLD into your
  active render.cljs WIP, the 4th holdout site is `render-entity-html` catch :375). **U is now
  GATED on your #12 (error-tile seam) + #14 (live-tile bridge + resume re-seed + provenance warn);**
  when they land: U does the acme `set!` swap + the ui.md error-row, then #6 (delete legacy —
  blocked on #12+#14), then the DeepSeek drive vs the complete UI. (History below.)
- **Now (override-proof detail):** **🔴 OVERRIDE-PROOF caught a real robustness gap (owner's directive — verify the
  consumer overrides, not just the default UI).** The new world-layout/slot UI BROKE the
  total-override thesis on the PRIMARY surface (`/agent/{id}`, what `/agents` links to): acme's
  overrides (error-response, branding, the live-tile map-widgets) BYPASS the new path — they
  survive only on the now-unlinked legacy console. Root cause: the new ctx-block/`slot` contract
  (BARE HICCUP) is a SECOND, PARALLEL tile contract that doesn't consume the established
  live-tile one (`:seon.render/html-response` MAP) — a no-parallel-systems violation; a
  map-returning fn renders EMPTY on the new path. **→ CORE (render engine, owner-aware): task
  #12 CRITICAL — converge the two contracts (min: make `slot`/`render` tolerate the
  `{:seon.render/hiccup …}` envelope as `render-entity-html` already does, render.cljs:339) +
  make the slot/world ERROR path overridable (render.cljs:657,692 are hardcoded — route through
  `live-tile/error-response` or one overridable `seon.render/error-tile`); task #14 — the
  live-tile bridge into world-layout, resume re-seeds the block set, a provenance warn on clean
  boot.** **→ U: task #13 — wire branding into the new world shim (doing now, independent).**
  **#6 convergence is BLOCKED on #12+#13+#14** — deleting the legacy stack before the new path
  adopts the overrides loses every consumer override. Acme override exercise committed
  `eb04736b`; ui.md "Total override" table currently OVERSELLS — fix when #12 lands.
- **Now (prior):** **🟢 REITIT LIVE + VERIFIED on 7890** (your cutover is done, build green). Server-side
  proof: GET `/world`/`/js/*`/`/agents` (inspector deleg) → 200; POST `/call` refused-fn → 403
  (gate, via reitit); cross-origin POST → 403 (same-origin mw); `/world/feed` gzip morph streams
  live (agent `dgS-2606271925`). Both `/call` doors + the hijack-SSE work through the Ring router.
  **WORLD-LAYOUT now STARTING** — Phase 2e's `(seon.render/slot …)` un-gates it; building
  `/agent/{id}` = the agent's `:seon.agent/ctx` blocks rendered as tiles via slots (ADDITIVE —
  the inspector/tile per-agent view stays until #6 deletes it). **Pin reconciliation:** the cutover
  already happened, so the shadow-port-pin no longer gates anything — and per my research
  (`92d26fc7`) it does NOT enable auto-reconnect (the build-time-baked server-TOKEN re-mint is
  the blocker, not the port), so it's a determinism-only nice-to-have, DE-PRIORITIZED (fold
  `:http {:port 9630 :strict true}` into a future restart if you want deterministic ports). U
  fixed `transform_test`'s exact-`/call`-path assertion → behavior-based (`4ee15438`).
  **✅ CORE — SAFE TO CONTINUE Phase 2+.** All U rename/disruptive src work is
  COMMITTED (`9801142d` web/** half + `c6c8d0ff` streamer), build GREEN at HEAD (zero
  `seon.ctx` in any src `.cljs`, tree-wide), nothing of mine uncommitted in `src/`. **Phase-1 COMPLETE +
  runtime-proven:** Core's `cluster reset default` is DONE (pod pid 1692, agent
  `PGh-2606271755`, 410 fns / 0 bad-spec — schema valid). My `/world` streamer is **live on the
  reseeded world** — the gzip morph stream on 7890 renders `PGh` (just verified). Tests: U green
  + committed (`aaa4a8c7`: `datastar_test` 8/23 + `call_test` 4/21, run one-ns-at-a-time in the
  live pod); Core-lane test files fixed → `bin/test-cljs` compiles (Core runs the full suite).
  (The earlier `derive-state` bare-entity smell was a FALSE alarm — it derives `:idle` cleanly,
  live-verified; no Core action.) — Validated the Phase-8
  stack against vendored source (reitit core/ring/trie/malli
  are CLJS-clean — the Java trie + ring's classpath static-handlers are `:clj`-only and
  unused; build delta = Maven `metosin/reitit-ring`+`reitit-malli` 0.10.1 in `deps.edn :cljs`,
  NOT source-paths). **Owner-approved architecture pivot** (simplest/most-robust; reuse over
  roll-our-own): the live UI is hyperlith's model ported to Node — `view = f(db-as-of t)`
  whole-element **datastar morph** over a **gzip-compressed SSE stream** (+ drop-latest
  throttle). This REPLACES packetstar per-tile `{id,html}` + the `!last-tree` BFS diff + the
  UI-side `since-t` replay. **Server half PROVEN in Node** (gzip SSE + byte-exact
  `datastar-patch-elements` framing, decode-verified). Real-pod streamer **LIVE-PROVEN in
  acme** (commit `c6c8d0ff`, `seon.web.datastar`): a real datahike tx → whole-`#world` datastar
  morph over gzip SSE (roster `1→2→1` on ADD/RETRACT, ~300ms post-commit, store clean). [[ui]] +
  roadmap Phase 8 get rewritten to match next. **Phase-1 web/** retarget LANDED** (commit
  `9801142d`, completing your `07f3c4ff`) — grep-clean tree-wide (zero `seon.ctx` in ANY
  `.cljs`, both lanes), build green at HEAD; **ready for the ONE `bin/seon cluster reset
  default`** (Core/owner triggers — destructive). Heads-up: I also retargeted the
  `seon.ctx.usage` SUB-ns (`inspector.cljs` L48/243) your Phase-1 grep would miss — add
  `seon[.]ctx` (no delimiter) to the final-gate grep.
- **Needs (from Core):** (1) ✓ Phase-1 DONE (both halves committed) — trigger the cluster
  reset when ready. (2) Phase 2e `(slot :name)`. (3) Phase 5 `:seon.route/*` schema —
  **seed the CORRECTED route set (Interface #2 below), not the old one.**
- **Interface changes (Core must absorb):**
  1. **Handoff #4 still holds** — UI renders the warnings-block error-TILE; it just streams
     inside the morphed world view (no standalone patch). The `:seon/error` VALUE shape is
     unchanged (yours).
  2. **Handoff #3 route SET changed — READ BEFORE seeding Phase-5 routes.** I own routing
     (owner-delegated); the design is hierarchical reitit with route-data inheritance.
     (a) **No `/agent/{id}/feed`** — GET=shim and the live stream ride the SAME path (datastar
     opens the stream from the page, per hyperlith), so seed `/` + `/agent/{id}` only.
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
