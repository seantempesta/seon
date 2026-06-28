---
type: prd
status: draft
tags: [prd, agent, flow, web, index]
---

# Root agent + the data-driven multi-agent OS — vision, decisions, phased plan

> **Owner direction, 2026-06-28.** Synthesized by Lane-U from the owner's vision +
> Core's `5ab2e46c` root work. This is the SHARED handoff for both lanes (and for a
> Lane-U session restart). Most IMPL is Core-lane (runtime, wake, capability,
> config-load, supervisor); the dashboard/UI + routing is Lane-U. Research agents to
> launch are listed at the end — **launch them AFTER reading this**, grounded.

## The milestone

A stable, **fully data-driven multi-agent OS**: independent agents running from ONE
datasource, supervised by a **root agent** that detects failures, guides / fixes /
restarts agents, and — the real point — **teaches us the failure modes so we fix them
at the root instead of papering over them.** Context, triggers, seeding, and
(eventually) authorization are all *data*.

## What's already shipped (do not rebuild)

- **Phase 8 / the converged UI (Lane-U):** ONE world UI — `/agent/{id}` = an agent's
  world (live-tile canvas + html ctx-block tiles + chat + nav), db-driven routing
  (`db->routes` over `:seon.route/*`, `3c7cfb72`), time-travel (`?t=`→`db/as-of`),
  legacy stacks deleted, operator dev tools carved to `seon.web.debug`. Suite 648/0.
- **The root agent (Core, `5ab2e46c`):** `root` is ONE ordinary agent, literal id
  `"root"`, parentless — the orchestrator + the base case of the spawn recursion.
  `seon.agent/start!` = `create!` + writes `:seon.agent/parent` = caller (mints a
  14-char child; children are never `"root"`). First boot mints `root`; `/agents/new`
  mints a child. ~27 agent-id slots now key on `:seon.agent/id` (uniformity fix).
  Deviation: leaf nses (derive/run/schedule) load before `seon.agent`, so their
  eager `register!` slots use base `:string` (the 14-char shape is enforced at
  create) — Phase-7 cleanup = relocate the `:seon.agent/id` registration to a base ns.

## Settled decisions

- **`root = /`.** The all-agents overview IS the root agent's world (root's blocks are
  SYSTEM-scoped — they query across every agent, not just root's own ctx). So `/` = the
  dashboard = the root agent's world; `/agent/{id}` is uniform for ALL agents incl
  `/agent/root` (= `/`); **`/world` + `/world/feed` are RETIRED.** No special `/root` or
  `/dashboard` route. (A `/dashboard` alias only if asked.) → Core: drop `::world`/
  `::world-feed` from `core-routes-tx`; `/`'s handler repoints from the `serve-root!`
  302 placeholder to the root-world layout Lane-U adds.
- **Restart policy:** auto-restart → **reset the agent's RUN/FSM to clean idle** (its
  persisted work — authored fns, blocks, transcript — stays in the DB; only the live
  run resets; it re-engages on the next wake). Known-good by construction.

## Owner decisions on the four forks (verbatim intent)

1. **Context config — OVERRIDE-BASED + FILE-LOADED (the big one).** The user can
   COMPLETELY set all context blocks + their render/fn symbols from config (an override
   layer over the defaults — fully replace if they want). A general **file-based load**
   for large text: markdown files are the default way to load big text — e.g. the
   **system message lives in `SYSTEM.md`**; all default blocks move to a **`config/blocks/`
   folder**. Third parties boot different configs by swapping the folder. **The EDN +
   markdown loading system must be DESIGNED to do all this** (referencing markdown files
   for content + code symbols for renders, override semantics, startup seed-into-DB).
   *(This supersedes the earlier "manifest over code vs inline" framing — the owner
   wants BOTH: edn manifest for composition/symbols AND markdown files for content,
   fully override-capable.)*
2. **Wake / engagement router — EXPLAIN CURRENT FIRST, then a general upgrade-friendly
   solution.** The owner doesn't yet know the current mechanism and (rightly) won't
   design blind. **Action: a research agent EXPLAINS the current ticker + tx-listener +
   message + resume-wake mechanism (is it general? where are the gaps?), THEN designs
   the target.** Target shape the owner already likes (their Posh insight): agents
   declare a **wake-fn symbol** (data); on each wire/tx hit, run the relevant wake-fns —
   a **Posh-style data-driven pre-filter** (`reference-code/posh/`: only re-check wakes
   whose tracked datoms changed) for speed, then the fn confirms (data-driven is fast
   but not comprehensive → the fn is the comprehensive check; simplest fallback = run
   ALL agents' wake-fns on every wire hit). Truthy → engage that agent's loop. This
   becomes the ONE wake mechanism. **Build it general now, easy to upgrade later.**
3. **Root authority — DEFER until stable.** For NOW: every agent is effectively root
   (full access); **roles are LABELS, not yet enforced.** The elegant end-state =
   **DB-level filtered views** (an agent asks for a db copy → it gets a FILTERED view of
   what its role may see — a safety mechanism by construction). Research item: how
   malli / reitit / others do authorization, applied to db-filtered-views. **Do NOT
   build enforcement until the system is stable.** (Core's `#31` roles-as-capability-sets
   is the role scaffolding; the ENFORCEMENT is the deferred authz layer.)
4. **System message (soft, pending):** likely reframed toward "here's your live REPL +
   capabilities" rather than "your job is…", and it physically lives in `SYSTEM.md`
   (per decision #1). Confirm global-vs-root-only framing later.

## The root agent's context + role (the dashboard's brain)

Root is a SUPERVISOR, not a worker. Its context (to design via the config loader):
- the base system message (`SYSTEM.md`), reframed capability-first;
- a **root block** explaining its view = the fleet + health stats to judge who's
  struggling / needs help;
- the ability to **load an agent's context** (recent transcript for now) — a
  cross-agent read (deferred-authz: fine for now since all-agents-are-root);
- the same `my.*` toolkit.
Woken by: (a) the owner's messages when on `/` (global understanding), (b) agent
ERRORS (root investigates). It acts: message an agent, fix its code, restart a crashed
one. Goal: learn failure modes, fix at the root.

## OS-like process supervision

Agents as supervised processes: **missed heartbeat past a timeout → auto-restart
(reset run to clean idle)**; **repeated crashes → a crash-loop flag** surfaced to root
on the dashboard. The run/FSM gives the bones; this adds a supervisor + the
reset-to-idle + crash-loop detection. (Mostly Core-lane; Lane-U surfaces health on the
dashboard.)

## Phased plan (sequence; refine after the research lands)

- **Phase A — Root's view (Lane-U, unblocked NOW):** the rich **mission-control
  dashboard** at `/` — a grid of agent CARDS (each = `render-agent-tile` preview +
  `derive-state` + `agent-turn-count`, clickable → `/agent/{id}`), live-morphing.
  Restore the pre-#6 richness from `git show 1eec28dc~1:src/seon/web/inspector.cljs`
  (`consumer-snapshot` ~:903 + `agents-dash-fragment`). Plus **graceful default routes**
  (missing `/agent/{id}` → 302 `/`; no-match → 302 `/`). Plus the dashboard "start
  agent" button → `/agents/new`→`start!`.
- **Phase B — The config + EDN/markdown loader (Core-lane, owner's #1):** design + build
  the override-based, file-loaded config system (`config/blocks/`, `SYSTEM.md`, the
  loader, startup seed-into-DB). Root's distinct context is the first proof.
- **Phase C — Supervisor + engagement router (Core-lane, owner's #2):** explain current
  wake → build the general wake-fn + Posh-prefilter router; the heartbeat/restart/
  crash-loop supervisor.
- **Phase D — Authz (deferred, owner's #3):** db-filtered-views + role enforcement,
  once stable.

## Research agents to launch (AFTER restart, grounded in THIS doc)

1. **Config + EDN/markdown loading system** — design a general, override-based,
   file-based loader: edn manifests (blocks, priorities, render symbols) + markdown
   content files (`SYSTEM.md`, `config/blocks/*.md`), references linked at load,
   seed-into-DB at startup, third-party config-swap. Ground in the EXISTING seed/
   code-as-data model (`client.cljs` `boot-seed!`, `seon.agent.ctx` default-ctx,
   the analyzer) so it EXTENDS rather than forks. Deliverable: a design doc.
2. **The engagement / wake router** — FIRST explain the current mechanism (the 30s
   ticker `seon.agent.loop/install-ticker!`, `db/listen!` tx-listener, message-wake,
   the resume-path wake, `open-run!`/the run FSM) and where it's not general; THEN
   design the target (per-agent `:seon.agent/wake-fn` symbol + a Posh-style data-driven
   pre-filter — read `reference-code/posh/` — over the tx-log/wire stream; the fn is the
   comprehensive check; run-all-on-wire-hit as the simple baseline). Easy-to-upgrade.
3. **OS-process supervision** — heartbeat-miss → restart-to-clean-idle; crash-loop
   detection + flag-for-root; what "timeout" + "crash loop" mean concretely; reuse the
   run/FSM. (Core-lane.)
4. **Authorization (DEFERRED, research-only for now)** — how malli/reitit/others do
   authz; applied to **db-level filtered views** (filter the db copy by role). Do NOT
   implement until stable.

## Lane split

- **Lane-U (web/ui):** the dashboard grid + page + SSE morph + routing/naming/graceful-
  routes + CSS; the `/debug`+`/data` rebuild; surfacing health/crash-loops on the
  dashboard; the `/` handler. Handle Core's `#32` (dead render-lane fn).
- **Core (runtime/data):** the root agent (✓), the config/EDN/markdown loader + seed,
  the wake/engagement router, the supervisor, the capability-roles + (later) authz,
  the `core-routes-tx` `/world`-drop + `/` repoint.

## Immediate state / next steps (for the restart)

- **In flight, needs reconciling:** the `/debug`+`/data` rebuild agent DIED on an auth
  error mid-way, leaving UNCOMMITTED partial edits in `web/datastar.cljs`,
  `web/debug.cljs`, `web/router.cljs`. **Decide: finish-or-revert** (likely revert +
  re-run cleanly) before building the dashboard (which also touches `router.cljs`/
  `datastar.cljs`) — don't build the dashboard on top of a half-edited tree.
- **Then:** Phase A (dashboard + graceful routes), which is unblocked.
- **Then:** write up + launch the 4 research agents (don't launch before the docs are
  read — the owner wants them grounded).
- Open Core tasks referenced: `#31` (roles-as-capability-sets / gate `/call` + `start!`),
  `#30` (in-process wake-trigger arming), `#32` (dead render-lane fn → Lane-U).
