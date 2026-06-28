---
type: research
status: active
tags: [research, web, ui, agent]
---

# Lane-U overnight report (2026-06-28) — the new world UI + routing convergence

> For the owner's morning. Lane-U ran autonomously overnight on `feature/agent-fsm`.
> TL;DR: **Phase 8's new world UI is essentially complete and green (suite 654/0)** —
> canvas + tiles + chat + nav + overrides + time-travel + db-driven routing all
> shipped and live-proven in acme. **Two things need YOU**, and **one risky finale
> (#6 legacy delete) is prepped but deferred** for a coordinated moment.

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

## #6 — the legacy delete, PREPPED + DEFERRED (recommended next, coordinated)

`db->routes` (#16) now owns the core routes, so #6 is un-gated. The plan:
1. Carve `/debug` + `/data` out of `inspector.cljs` into `seon.web.debug` (per #25).
2. Delete the **parallel WORLD renderers**: `packetstar.js`, the inspector
   agent-world console/datastar-view, `:seon.tile/*` placement + `tile.cljs`, the
   dead A-6 broadcast stub, and the `legacy-default` delegation in `router.cljs`.
3. Verify in acme the new page fully replaces legacy; `git revert` if anything breaks.

**Why deferred, not done overnight:** it's a big shared-tree deletion — a dangling
reference would transiently red the default pod's `cljs-watch` build and could
disrupt Core's live hot-reload while Core is actively committing. Better done when
Core can pause / you're awake to confirm #25. It's revertable and low-risk *when
coordinated*; rushing it at 1am is not "simple+stable".

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
