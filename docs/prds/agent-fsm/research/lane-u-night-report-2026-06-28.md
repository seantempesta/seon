---
type: research
status: active
tags: [research, web, ui, agent]
---

# Lane-U overnight report (2026-06-28) — the new world UI + routing convergence

> For the owner's morning. Lane-U ran autonomously overnight on `feature/agent-fsm`.
> TL;DR: **Phase 8 is COMPLETE and green (suite 648/0)** — the new world UI (canvas +
> tiles + chat + nav + overrides + time-travel) over db-driven routing is now the SOLE
> surface: **#6 deleted the parallel legacy stacks** (`1eec28dc`, −4189 lines), preserving
> the operator dev tools in `seon.web.debug`. One UI, no parallel systems. **Two things
> still need YOU** (the cluster reset + #25 — both below), but the build is done.
> (Update appended below the original report — #6 landed after the report was first
> written; Core went quiet for the session so I completed it under a no-red-build rule.)

## ⚠️ Two actions that need you (or Core)

1. **`bin/seon cluster reset default`** (or a pod restart that re-runs `boot-seed!`).
   The default pod (7890) has **0 `:seon.route/*` rows** — it booted before Core's
   Phase-5 route seed. Now that the router derives core routes from those datoms
   (#16), the default pod's core GET routes (`/`, `/world`, `/agent/{id}`) **404
   until a reset seeds them**. acme is fine (it was rebuilt + reseeded). This is the
   protocol-correct action after a schema/seed change; I did NOT touch the default
   cluster. (The same reset also applies Core's P0 instrument-wedge fix `cc38a8e2`,
   which is next-boot-only — the running default pod is still wedge-vulnerable on
   agent-create until restarted.)
2. **Decide #25** — the operator `/debug` (exact LLM prompt + token/cache-line bar) +
   `/data` (datom browser) have no world equivalent. #6 must NOT silently drop them.
   My recommendation: carve them into a small `seon.web.debug` ns (preserve the dev
   tools, delete only the parallel WORLD renderers). Alternatives: rebuild as world
   tiles, or accept the loss (REPL gives `render-context` + token estimate).

## What shipped tonight (all committed, all live-proven in acme)

| Unit | Commit | Proof |
|---|---|---|
| #14a live tile = focal `#world-canvas` | `2be4247c` (+ `9625788e` doall-map fix) | a real DeepSeek agent built a todos tile that rendered there |
| #12 two error seams (calm hero `error-response`, slot `error-tile`) + #13 branding | `947d7b51`/`c092d212`/`9d87dffe` | acme overrides render on `/agent/{id}` (observed bytes) |
| #17 feed reconnect-hardening | `1e9e2f35` | `@get` retryMaxCount Infinity served |
| #19 canvas=live-tile (decision + docs + tests) | `758e88cd`/`486b0d0f` | observer confirmed agents don't confuse it |
| #24 P0 chat input + P1 nav | `90f59183` | POST /chat → 204 → message in transcript tile, durable across restart |
| #18 historical time-travel (`view = f(db-as-of t)`) | `dc984a47` | `?t=` past feed 770B vs live 88KB; frozen under txs |
| #16 db-driven routing (`db->routes` over `:seon.route/*`) | `3c7cfb72` | every route incl the **gate 403** + cross-origin 403 proven; 654/0 |

**Methodology used throughout:** every UI unit got an acme override-proof + (for the
context) a live DeepSeek drive with a dedicated observer; the observer confirmed the
new UI carries a real agent and surfaced Core findings (routed below). Server-side
verification only for SSE (browser agents 503 on long-lived streams).

## #6 — the legacy delete: ✅ DONE (`1eec28dc`, suite 648/0)

I deferred this while Core was active (a multi-file shared-tree delete has transient
red-build windows that could disrupt Core's live hot-reload). Once **Core went quiet
for the session** (~80 min, no uncommitted work), I completed it under a hard
**no-red-build** rule (end green + full suite or revert):
- **Deleted −4189 lines** — the parallel WORLD renderers: `inspector.cljs`
  (agent-world console/datastar-view), `tile.cljs` (packetstar tile console +
  `:seon.tile/*`), `packetstar.js`, `page.cljc` (the dead A-6 stub), the
  `legacy-default` delegation in `router.cljs` (no-match now 404s), + 2 dead test ns.
- **Preserved (#25 = carve)** — the operator dev tools live in a new **`seon.web.debug`**
  ns: `GET /data` (datom browser) + `GET /agent/{id}/debug` (the two-pane exact-LLM-bytes
  inspector + token/cache-line bar), with their SSE, wired as reitit routes in the
  static-supplement. `/` now 302s → `/world`.
- **Verified in acme** (build 0 warnings): new page + feeds + chat + the gate 403 all
  work; `/debug`+`/data` → 200; deleted paths (`/agents`, `/tile/console/<id>`,
  `/agent/<id>/sse`) → 404. **Full suite 648/0**; grep-CLEAN (no dangling refs).

**One P3 follow-up (your call):** the world page doesn't yet cross-link `/debug`+`/data`
— they're reachable by direct URL only. A header link is a small additive change; I left
it for you since whether dev tools belong on the main page is a UX preference.

**Phase 8 is now structurally complete — one UI, no parallel systems.** The only thing
between you and a fully-live converged default pod is the `cluster reset` above (to seed
the routes the new router derives from).

## Flags routed to Core (their lane)

- **#20 P0 wedge** — FIXED (`cc38a8e2`, instrument-once) but next-boot-only → default
  pod needs the restart above.
- **#22 observer findings** — the biggest lever is `my.tile` interactivity (the live
  tile is read-only hiccup; an agent couldn't fulfil "let me add one"); plus ~40%
  prompt bloat (SOUL + acme fixtures + unused `my.kb`) and toolkit-catalog ≠ live-floor
  naming. Full report: `research/deepseek-drive-observation-2026-06-28.md`.
- **Seed the secondary POST doors** (`/chat /stop /resume /clear /log /agents/new
  /agent/{id}/complete`, `/sse`, flat `/call`) as `:seon.route/*` datoms for fully
  data-driven routing — they're in `router.cljs`'s `static-supplement` for now.
- **Wire `(seon.web.router/rebuild!)` into a route tx-listener** so the router
  re-derives on every route tx (it's public + 0-arg; today it's called post-seed by
  `serve/start!`).
- **Review the gate's calling-convention arity** — `reactive.call/handle!` gained a
  thin `([r] …)` arity that extracts node-req/node-res and delegates to the unchanged
  `(req res)` capability logic. The security (resolve-owning-agent → granted-fn? →
  refuse-before-invoke) is byte-for-byte unchanged; the 403 is acme-proven.
- **`seon.db/basis-t` + `seon.db/origin-t`** — I added these read helpers to
  `seon.db.cljs` for time-travel's slider domain. Rename/own as you see fit.

## Open UX polish (owner, low priority)

Time-travel's control is intentionally a raw tx-id slider — refine with human-readable
timestamps, tick marks, or a diff view. The client-side slider drag is the only thing
not server-verified (browser agents can't drive SSE); the final eyeball is yours.
