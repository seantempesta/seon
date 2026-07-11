---
type: component
status: active
tags: [component, web]
---

# Web Inspector (CLJS pod web lane)

> The pod's own browser UI: `seon.web.serve` (`src/seon/web/serve.cljs`) hosts a loopback HTTP+SSE server (default port 7890, `SEON_PORT`/`SEON_PORT_FILE` overrides) and `seon.web.inspector` (`src/seon/web/inspector.cljs`) renders every page. Distinct from the JVM [[components/web-layer]] — this lane is Node-side, has no `.clj` sibling, and is what the demo browser actually talks to.

## The four page shells

| Route | Shell fn | What it is |
|-------|----------|------------|
| `/agents` | `agents-index-page` → `agents-dash-fragment` | Mission control: brand h1 + tagline, live stat strip (agents/turns/fns/findings/datoms via `cluster-stats`), active-agent tile grid (each tile = the agent's own `render-agent-tile` surface in a fixed `h-44` card with a stretched overlay link), the cross-agent knowledge section, collapsed completed-agents history, and the `+ new agent` button (optional purpose input → `POST /agents/new`) |
| `/agent/<id>` | `consumer-shell` | The CONSUMER view (live-tiles PRD §1 Surface 2): chat bubbles + input left (`seon.render.chat/bubble-stream`), the SAME live tile expanded right (container-query breakpoint 480px selects the expanded blocks). Carries the debug overlay |
| `/agent/<id>/debug` | `inspector-shell` | The two-pane DEBUG inspector: left = `:seon.render/ai` per context section as collapsible `<details>` (only `:transcript`/`:prompt`/`:context` open by default), right = per-entity rendered cards with turn separators, eval-time sparkline header, thinking pulse, shared chat bar |
| (404) | `agent-not-found-page` | Stale-tab landing for ids from a prior store — the `agent-exists?` guard 404s pages AND refuses SSE registration for dead ids |

Routing: `seon.web.serve/handler` owns `/`, static `/css/` + `/js/`, `/sse`, and the POSTs (`/chat`, `/agents/new`, `/clear`, `/log`), and delegates to `inspector/route?` + `inspector/handle!` for the `/agents` + `/agent/<id>` family. `start!` is idempotent (a second boot reuses the listening server) and wraps the handler late-binding so hot-reloaded routes take effect without a pod restart.

## SSE morphing

- ONE `db/listen!` tx-listener (`install!`, key `::inspector`) serves every view. Fan-out scope per commit: core tx (no `:seon.db/agent-id` stamp) and `:substrate-seed`-origin tx go to ALL watching agents; agent-stamped tx only to that agent; the `::index` pseudo-agent (the `/agents` dashboard) watches every commit.
- Pushes coalesce on a 100ms trailing timer per agent-id (`schedule-push!`) — a burst of tx within one turn produces one render.
- Per-agent registry `!sse-by-agent`; each connection is tagged `:view` (`:consumer` | `:debug`) and `push-agent!` writes its view's fragment set — `consumer-snapshot` is deliberately cheaper than the debug `snapshot` (no `ctx-preview`).
- Payloads are hand-built `datastar-patch-elements` events (`patch-fragment` — inlined to keep the require graph acyclic); idiomorph morphs by stable fragment ids (header + both panes per view, `#agents-dash` for the index).
- The debug left pane is `inspect/ctx-preview` — the EXACT bytes `seon.agent/assemble-context` would hand the LLM, so the webview can never diverge from the prompt.
- Morph-survival guards live in the shared inline `page-script-js`, all keyed on JS properties idiomorph can't clobber: `__hlSrc` (highlight.js re-runs), `__mdSrc` (marked.js markdown bodies), `window.seonOpen` keyed by `data-seon-key` (user `<details>` toggles), and `__seonAtBottom` on `[data-seon-scroll]` panes (bottom-pinned autoscroll that never yanks a user reading history; sending a chat message explicitly re-pins).

## The findings pane

`findings-data` derives the per-kind knowledge summary from `seon.agent.findings/user-domain-kinds` (as of 43c5145) — the SAME derivation the agent's `:findings` context rung renders from, so the dashboard and the prompt can never disagree (the legacy bare-ns `:finding/*` query read "0 findings" while agents SAW findings in context). Cross-agent BY DESIGN: it reads the unfiltered conn, never an agent-view FilteredDB. Surfaces:

- `/agents` — the "◆ what this cluster has learned" section (`knowledge-cards`: one dot+text card per kind with row count + a claim/title-ish sample string, clipped at 140 chars display-only) and the `findings` stat cell.
- `/agent/<id>/debug` — `knowledge-group`: the same cards in a collapsible group at the top of the right pane, open by default, rendering nothing when no findings exist (derived, self-healing).

## Brand consumption

All four shells read `brand/info` from [[components/web-brand]] for `<title>` (`brand/page-title`), `data-theme` on `[:html]`, and the optional downstream stylesheet (`brand-css-style` inlines `SEON_BRAND_CSS` AFTER the `output.css` link so its token overrides win). The dashboard additionally renders the brand h1 + `::tagline`, re-read on every SSE re-render. `install!` kicks `brand/sync!` at boot (fire-and-forget).

## The debug overlay

On the consumer view, the debug inspector opens as a full-viewport OVERLAY with no URL change (live-tiles PRD §1 Surface 3 / U6): an iframe lazily pointed at `/agent/<id>/debug` — the real debug page with its own SSE stream inside, zero duplication. Open via the `⚙ debug` header button (document-delegated click, morph-proof) or backtick (focus-guarded — never fires while an input/textarea/contentEditable has focus); close via Esc or the button. Closing resets the iframe to `about:blank` so its SSE stream tears down.

## Other rendering details

- Right-pane cards: `render/render-entity-html` per entity, falling back to a styled key/value card (`unknown-entity-card`) — never a raw EDN dump. STATIC cards (schema rows; `:substrate-seed`-origin fn/ns/test index rows) collapse to a `<details>` summary; DYNAMIC cards (messages, evals, agent-authored entities) render expanded, with messages full-width and evals indented as "work shown".
- Turn grouping: cards are keyed to turns via the `:seon.agent.session/turns` → `:seon.agent.turn/messages|evals` component refs; separators carry turn number, wall time, and Σ eval duration; the header sparkline shows the last 12 turns' eval time.
- A render-cap overflow note ("… N older entities elided") surfaces `seon.render/renderable-entities` elision instead of hiding it.

## Dependencies

- Uses: `seon.db` (query/entity/listen!/`*conn*`), `seon.agent` (`message!` from `/chat`), `seon.agent.findings`, `seon.agent.inspect` (`ctx-preview`, `handlers`), `seon.agent-view`, `seon.render` + `seon.render.chat` + `seon.render.default`, `seon.web.brand`, `seon.ui.html`/`seon.ui.components`, `seon.platform` (artifact paths), Node `http`/`fs`/`path`.
- Used by: `seon.client` injects its `start-agent!` closure via `serve/set-create-agent-fn!` (the `/agents/new` boot path — no parallel creation mechanism) and calls `serve/start!` + `inspector/install!` at pod boot.
