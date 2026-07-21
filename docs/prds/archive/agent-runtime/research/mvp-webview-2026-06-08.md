---
type: research
status: active
tags: [research, web, agent, dashboard]
---
# MVP webview recon — the live two-pane (AI + HTML) view for one agent

## TL;DR

**The MVP two-pane reactive webview already exists and is wired at agent
boot.** It is `seon.web.inspector` (CLJS pod, `src/seon/web/inspector.cljs`),
served by `seon.web.serve` (the pod's loopback HTTP+SSE server). Route
`GET /agent/<id>` renders a full-page two-column view — LEFT pane is
`:seon.render/ai` (the exact bytes the LLM sees), RIGHT pane is
`:seon.render/html` (per-entity hiccup). `GET /agent/<id>/sse` opens the
reactive stream. A single datahike `d/listen` tx-listener
(`inspector/install!`) re-renders both panes (coalesced on a 100ms trailing
timer) and writes `datastar-patch-elements` SSE frames on every commit
scoped to that agent. `start-agent!` calls both `web.serve/start!` and
`inspector/install!`.

This is **not** the V1/JVM `seon.web.sse` / `seon.web.reactive.*` stack and
**not** the platform `seon.server.broadcast`. Those exist but are a
different lane. The MVP lives entirely in the CLJS pod.

The gap to MVP is therefore small: it is operational/verification, not
new architecture. The biggest real gap is that the agent's
**per-turn `:seon.render/ai` / `:seon.render/html` summary on the agent's
own entity** is assembled from the tx-log window (`assemble-ai-context`),
not yet a single live "agent thought stream" summary fn on the agent entity
— but the panes already render the per-entity stream, which is functionally
the live REPL/agent-thought view the MVP asks for.

## What exists

| Capability | Status | Anchor |
|---|---|---|
| Pod loopback HTTP+SSE server | EXISTS | `src/seon/web/serve.cljs` — `start!` binds `127.0.0.1:7890` (env `SEON_PORT`, `0`=ephemeral), writes `tmp/seon-port` |
| Main `GET /sse` stream + connection registry | EXISTS | `serve.cljs:130 open-sse!`, `!sse-connections` atom |
| Root shell page `GET /` | EXISTS | `src/seon/web/page.cljc` — grid shell, `#agent-seon` tile, opens `/sse` via `data-init="@get('/sse')"` |
| **Two-pane per-agent inspector** (AI text + HTML) | **EXISTS** | `src/seon/web/inspector.cljs` — `ai-pane-fragment` (LEFT, `:seon.render/ai`), `html-pane-fragment` (RIGHT, `:seon.render/html`) |
| Inspector routes `/agents`, `/agent/<id>`, `/agent/<id>/sse` | EXISTS | `inspector.cljs:361 route?`, `:491 handle!`; delegated from `serve.cljs:325` |
| **Reactive DB→browser bridge** (tx-listener → SSE patch) | **EXISTS** | `inspector.cljs:443 install!` → `db/listen!` → `on-tx` → `schedule-push!` (100ms coalesce) → `push-agent!` writes `datastar-patch-elements` |
| Per-agent tx scoping for pushes | EXISTS | `on-tx` reads `:seon.db/agent-id` off the tx eid; substrate tx (nil) fan out to all watchers, per-agent tx only to that agent's conns |
| Render resolution (symbol → fn, fallback to pretty-print) | EXISTS | `src/seon/render.cljs` — `render-entity-html`, `render-entity-ai`, `assemble-ai-context` (tx-log-as-context window, default 64) |
| Per-agent filtered DB view | EXISTS | `src/seon/agent_view.cljs` — `agent-view` returns `d/filter`ed db value scoped to agent-id + substrate |
| `db/listen!` (datahike `d/listen` tx-listener) | EXISTS | `src/seon/db.cljs:1310` — safe-by-default, keyed, idempotent |
| Datastar JS + Tailwind output CSS served | EXISTS | `serve.cljs` static roots `/js/`, `/css/`; `page.cljc`/`inspector.cljs` load them; highlight.js + marked.js from CDN in inspector |
| Chat bar (user → agent message) | EXISTS | `inspector.cljs:182 chat-bar-fragment` → `POST /chat?agent=<id>` → `agent/chat` → triggers loop; SSE re-renders panes |
| LLM agent loop + deepseek adapter | EXISTS | `src/seon/ai/deepseek.cljs` — `agent-adapter`, `default-system-prompt`, `DEEPSEEK_API_KEY`; `start-agent-with-deepseek!` in `client.cljs:844` |
| Agent assigned to a single home namespace | EXISTS | `agent.cljs:368 home-ns` → `seon.agent.<id>` |
| Shared read-only folder (fs allowlist) | EXISTS | `src/seon/fs.cljs` — default-deny allowlist; `SEON_FS_ROOT` + `SEON_FS_READ_ONLY=1` for the read-only dig folder |
| Boot wires it all | EXISTS | `src/seon/client.cljs:712 start-agent!` calls `web.serve/start!` (`:783`) and `seon.web.inspector/install!` (`:832`) |
| `seon.web.reactive.*` (demo/actions/transform) | EXISTS but **JVM/V1 lane** | `src/seon/web/reactive/{demo,actions,transform}.clj` — central-store reactive demo, instance-per-tab, `:on:click`→`@post` transform. NOT used by the pod MVP. |
| `seon.web.sse` (`render-handler`, `refresh-all!`) | EXISTS but **JVM/V1 lane** | `src/seon/web/sse.clj` — hash-diff full-view push. Documented in datastar-quick-reference. Not the pod path. |
| `seon.server.broadcast` "changed-summaries" | EXISTS but **platform/V2 lane** | `src/seon/server/broadcast.clj` — JVM-side per-DB broadcast for Rust host/guests (Unix socket/CBOR). NOT a browser transport. |
| `seon.web.broadcast` (A-6) referenced by `page.cljc` | **MISSING** | `page.cljc` docstring + `repl.cljs:73` mention a "broadcast watcher" that never shipped — its job (DB tx → SSE patch on the main `/sse`) was instead implemented inside `inspector.cljs` for the per-agent path. The main `#agent-seon` tile on `GET /` therefore stays "loading…"; only the inspector route is live. |

### Render path, concretely (DB entity → browser)

1. Any `db/transact!` commits → datahike fires the `::inspector` listener
   (`inspector/on-tx`).
2. `on-tx` reads tx-meta `:seon.db/agent-id`, picks watching agents,
   `schedule-push!` (100ms trailing coalesce).
3. `push-agent!` calls `snapshot` → `agent-view/agent-view` (filtered db)
   → `render/assemble-ai-context` (AI text + ordered entity list) and
   `render/render-entity-html` per entity (RIGHT pane cards).
4. Three fragments (header, AI pane, HTML pane) wrapped as
   `event: datastar-patch-elements` and written to each open SSE conn.
   Datastar morphs by stable ids `#inspect-ai-<id>` / `#inspect-html-<id>`.

The right pane additionally runs highlight.js on `language-clojure` blocks
and marked.js on `[data-markdown]` (eval narration) via a MutationObserver
that re-runs after each morph.

## What's missing for MVP

1. **Nothing architectural is missing** for the two-pane live view of one
   agent. The path exists end-to-end and is wired at boot.
2. **Operational verification not done in this recon** (no live pod was
   booted): confirm `node out/client/main.js` boots, `/agent/<id>` loads,
   and panes morph as the deepseek loop runs against a real
   `SEON_FS_ROOT` folder. This is the actual remaining work — run it and
   watch it.
3. **Discoverability of the agent id / landing route.** `GET /` shows the
   grid shell with a permanently-"loading…" `#agent-seon` tile (the A-6
   main-stream broadcast was never built). The live view is at `/agents`
   → `/agent/<id>`. MVP polish: redirect `/` to `/agents`, or have
   `start-agent!` log the `/agent/<id>` URL, or fill the main tile by
   reusing `inspector/push-agent!` on the main `/sse` registry.
4. **"Agent's own summary" on its entity vs. tx-log window.** The MVP
   framing ("agent's `:seon.render/ai`/`html` lives on its own entity")
   is partially met: `assemble-ai-context` composes the *window of
   renderable entities*, which is the live thought/REPL stream. If a
   single roll-up summary fn pinned to the `:seon.agent/id` entity is
   wanted, register `:seon.render/ai` + `:seon.render/html` symbols on the
   agent entity and let `render-entity-{ai,html}` resolve them — the
   resolution code already honors per-entity overrides.
5. **Single-agent assumption is fine**; multi-agent grid on `/` is the
   only thing explicitly deferred (page.cljc V0.6 note).

## Recommended quickest path to the live two-pane view

Do NOT build a new reactive system, do NOT point `seon.web.reactive.demo`
at an agent entity (that's the JVM/V1 lane and would duplicate working
pod code — "don't be a dumbass"). Instead:

1. **Boot the pod against a real folder + key.** Set
   `SEON_FS_ROOT=<shared-readonly-folder>`, `SEON_FS_READ_ONLY=1`,
   `DEEPSEEK_API_KEY=…`; start the agent via
   `seon.client/start-agent-with-deepseek!` (or wire the main entry to it).
   `start-agent!` already starts the server + installs the inspector.
2. **Open `/agent/<id>`** (id is the freshly-minted `db/new-id!`, logged
   by `start-agent!` at `client.cljs:833`). Two panes populate from the
   initial render in `open-agent-sse!` and then morph reactively.
3. **One small UX fix:** make the landing discoverable — redirect `GET /`
   to `/agents`, or fill the `#agent-seon` tile. ~1 route change in
   `serve.cljs`.
4. **Optional roll-up:** if a single per-agent summary (not the entity
   window) is desired, attach `:seon.render/ai` / `:seon.render/html`
   symbols to the agent's own entity; the resolver already prefers them.

Net: the MVP is a **boot + verify + one redirect**, not a build. The
two-pane reactive inspector is the work product that already shipped.

## Key files

- `src/seon/web/inspector.cljs` — the two-pane reactive view (THE MVP)
- `src/seon/web/serve.cljs` — pod loopback HTTP+SSE server + router
- `src/seon/web/page.cljc` — root shell (`GET /`); references missing A-6 broadcast
- `src/seon/render.cljs` — render-symbol resolution + tx-log-as-context
- `src/seon/agent_view.cljs` — per-agent filtered db view
- `src/seon/db.cljs` — `listen!` tx-listener (the reactive engine)
- `src/seon/client.cljs` — `start-agent!` boot wiring (serve + inspector)
- `src/seon/ai/deepseek.cljs` — LLM adapter
- `src/seon/fs.cljs` — read-only shared-folder allowlist

### Adjacent lanes (do not confuse with the MVP)

- `src/seon/web/reactive/{demo,actions,transform}.clj` — JVM/V1 reactive demo
- `src/seon/web/sse.clj` — JVM/V1 SSE `render-handler`/`refresh-all!`
- `src/seon/server/broadcast.clj` — V2 platform host/guest broadcast (not browser)
