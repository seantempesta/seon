---
type: research
status: active
tags: [research, web, ui]
---

# World page vs legacy console — human-surface audit (gate before #6)

## TL;DR

The new world page (`/world` + `/agent/{id}`) is **NOT a sufficient human
replacement today**. It gives the human a live read-only view (status chip,
the agent's live-tile canvas, the agent's html ctx-block tiles) plus whatever
`@post('/agent/{id}/call')` buttons the AGENT itself authors into a tile — but
it has **no way for the human to message the agent**, no navigation, no
new-agent affordance, and no lifecycle controls. Deleting the legacy stack (#6)
before adding a chat input would strand the user: they could watch the agent
but never talk to it.

The fix is small and uses only endpoints that already exist (`/chat`,
`/agents/new`, plus plain `<a href>` links) and the exact datastar / inline-fetch
patterns the legacy inspector already ships on this same pod. **No Core change is
required** — every door the human needs is already routed and same-origin-gated.

**Verdict: build P0 (chat input) + P1 (roster→world links, back-nav, new-agent)
into the world shim/layout FIRST, then #6 can delete legacy.**

## Live proofs (default pod, 127.0.0.1:7890, read-only)

- `GET /agent/dgS-2606271925` → the **NEW** world shim
  (`<main id="world" data-init="@get('/agent/dgS-2606271925/feed', …)">loading…</main>`).
  The bare `/agent/{id}` is now a reitit route → `datastar/serve-agent-page!`,
  so the legacy inspector consumer view is already SHADOWED at that path.
- `GET /agent/<id>/feed` (decompressed) renders only `#world-header`,
  `#world-canvas`, `#world-tiles` → `world-tile-soul`, `world-tile-transcript`.
  **No chat input, no nav, no controls** in the streamed `#world`.
- `GET /agent/<id>/debug` → 200 (legacy two-pane debug + chat-bar still reachable
  via the no-match `legacy-default` delegation).
- `GET /tile/console/<id>` → 200 (legacy packetstar console + its REPL input still
  reachable).
- The world feed includes a live `:transcript` tile — so a human message + the
  agent's reply WOULD surface there over the broadcast morph, once an input
  exists to send it.

## 1. Legacy human surfaces — inventory

Two legacy stacks ride `legacy-default` in `seon.web.router`:
`seon.web.inspector` (`/agents`, `/data`, `/agent/<id>/debug`, the now-shadowed
`/agent/<id>` consumer view) and `seon.web.tile` (the older packetstar console
at `/tile/console/<id>`). All POST endpoints live in `seon.web.serve` and are
same-origin-gated by `seon.web.router/same-origin-mw`.

| # | Human surface | What it does | Endpoint / mechanism | On the new world page? |
|---|---------------|--------------|----------------------|------------------------|
| 1 | **Chat input** (inspector `chat-bar-fragment`; packetstar `input-form`) | Human → agent message; wakes a turn | `<form>` inline-`fetch` POST `/chat?agent=<id>`, form-urlencoded `text=` | **NO** |
| 2 | Eval input (packetstar `input-form`, a `(form)` text) | Quiet introspective eval | POST `/eval?agent=<id>` — **no `/eval` route registered in the pod → 404; already dead** | NO (and dead in legacy too) |
| 3 | New-agent button + purpose (inspector mission control) | Mint + boot a live agent | inline-`fetch` POST `/agents/new`, optional `purpose=` | **NO** |
| 4 | Agent grid → open agent (inspector tiles; `agent-grid-tile`) | Navigate to a specific agent | `<a href="/agent/<id>">` | **NO** (roster `<li>` is not a link) |
| 5 | "← all agents" nav (inspector header) | Back to the roster | `<a href="/agents">` | **NO** (no link off the world page) |
| 6 | "✓ complete" (inspector `agent-grid-tile`) | Close the open run `:completed` → idle | inline-`fetch` POST `/agent/<id>/complete` | NO |
| 7 | "⚙ debug" overlay + backtick (inspector / packetstar) | Open the raw-prompt + context-bar debug view | iframe → `/agent/<id>/debug` | NO |
| 8 | "⛁ data browser" link (inspector) | Browse stored datoms by kind | `<a href="/data">` | NO |
| 9 | system / completed toggles (inspector) | Show machinery counts / finished agents | `?system=1` / `?completed=1` query params | NO |
| 10 | stop / resume / clear | Pause / re-drive / wipe conversation | POST `/stop`,`/resume`,`/clear?agent=<id>` — **endpoints exist but NO legacy UI button wires them (curl-only today)** | NO |
| 11 | Agent-authored tile buttons | Agent's own interactive controls | `@post('/agent/{id}/call?fn=…')` via `transform-hiccup` (applied in `render.cljs` `render-agent-tile`) | **YES** (the one interactive path the world page already has) |

Notes that change the priority math:
- **The eval input (#2) is already non-functional** — `/eval` is registered in
  neither `router.cljs` nor `serve.cljs`; a POST `/eval` falls through
  `legacy-default` to 404. So "lose the eval console" is not a real regression.
- **stop / resume / clear (#10) have no legacy button** either — they are
  endpoints reachable only by curl/external tooling, not human-clickable
  surfaces. Their absence on the world page is not a regression from a working
  surface.
- **Agent-authored interactivity (#11) already works on the world page**: the
  canvas + every tile route through `render-agent-tile`, which postwalks agent
  hiccup through `seon.web.reactive.transform/transform-hiccup`, so an agent can
  put a `@post('/agent/{id}/call')` button on its own tile and it works on
  `/agent/{id}` today. The gap is HUMAN-owned controls, not agent-owned ones.

## 2. The GAP — human surfaces missing from the world page, ranked

- **P0 — chat input (message the agent).** The single blocking gap. Without it
  the human can watch but cannot interact at all. The `/chat?agent=<id>` endpoint
  exists, is same-origin-gated, and the reply already surfaces in the world's
  `:transcript` tile over the broadcast morph. This is the strand-the-user risk.
- **P1 — navigation.** (a) the `/world` roster tiles are `<li>`, not links — the
  human can't click through to a per-agent world; (b) a per-agent world has no
  "← all agents" link back to `/world`. (c) **new-agent** affordance (POST
  `/agents/new`) is absent. Together these make the world pages a set of
  dead-ends.
- **P2 — lifecycle controls (complete / stop / resume / clear).** Useful, but
  even legacy only ever wired `complete`; stop/resume/clear were curl-only. Low
  urgency, cheap to add (endpoints exist).
- **P3 — developer/operator introspection (the debug view + `/data`).** The
  two-pane debug is the ONLY human view of the exact LLM prompt + the per-section
  token/cache-line audit bar; the world page renders the agent's *rendered* tiles
  but never the raw prompt. `/data` is the datom browser. These serve a
  developer-operator, not an end consumer — they can be a later rebuild, but #6
  should note that deleting `/agent/<id>/debug` removes the only raw-prompt /
  cache-line surface.

## 3. Build plan (→ U) — reuse existing doors, no new mechanism

The world page streams a whole-`#world` morph on every tx. Human-owned input
must live **outside** the `#world` morph target (focus/typing would otherwise be
clobbered on every commit) — exactly how the legacy inspector put its
`chat-bar-fragment` OUTSIDE the morphed panes. The natural home is the shim page
(`seon.web.datastar/shim-html` / `agent-page-html` / `world-page-html`), which is
static raw HTML the feed never touches.

- **P0 chat input** — in `agent-page-html`'s shim body, below `<main id="world">`,
  add a static `<form>` posting to `/chat?agent=<id>`. Simplest + proven: copy
  the legacy `inspector/chat-bar-fragment` inline-`fetch` (form-urlencoded `text=`,
  clears on success, errors into a span, Cmd/Ctrl+Enter). Datastar-native
  alternative: a `data-bind` input + a button
  `@post('/chat?agent=<id>', {contentType:'form'})` — our shipped datastar.js
  supports `contentType` + `application/x-www-form-urlencoded`. Either way:
  endpoint unchanged, same-origin gate already allows the same-origin loopback
  POST, the reply morphs back into `#world`'s transcript tile. **No Core change.**
- **P1a roster links** — in `seon.web.datastar/agent-tile`, wrap each roster tile
  as `[:a {:href (str "/agent/" id)} …]` (it is a plain `<li>` today). Pure
  hiccup.
- **P1b back-nav** — in `seon.ui.world/world-layout`'s `#world-header`, add a
  `[:a {:href "/world"} "← all agents"]`. Pure hiccup. (Lives inside the morph,
  which is fine — it's a static link, not an input.)
- **P1c new-agent** — on the `/world` roster shim (outside the morph), a button
  whose inline-`fetch` POSTs `/agents/new` then navigates to `/agent/<id>` (copy
  the inspector mission-control button verbatim; the optional `purpose=` field is
  free). Endpoint exists.
- **P2 controls** — optional small button row in the per-agent shim footer next to
  chat: inline-`fetch` `@post`/POST to `/agent/<id>/complete`, `/stop?agent=<id>`,
  `/resume?agent=<id>`. All endpoints exist + are same-origin-gated.
- **P3 introspection** — until a raw-prompt tile is rebuilt on the world page,
  keep a header link to `/agent/<id>/debug` and `/data`. **Flag for #6:** these
  are exactly what #6 deletes, so either rebuild the raw-prompt/cache-line surface
  as a world tile first, or accept that operators lose the exact-LLM-bytes view.

Everything above reuses already-routed endpoints and the datastar / inline-fetch
patterns already live on this pod. **No `:seon.route/*` Core change, no new
endpoint, no new client mechanism is needed for P0–P2.**

## 4. #6 verdict — NOT ready; build the chat input first

**Do not delete the legacy stack yet.** The new world page is a sufficient
*observation* surface but not a sufficient *operation* surface: a human on
`/agent/{id}` or `/world` has no way to message the agent, navigate between
agents, or create one. The blocking item is P0 (chat input); P1 (roster→world
links + back-nav + new-agent) is needed to make the world pages navigable rather
than dead-ends.

The work is small and Core-free — one static chat form in the shim, three hiccup
link/button additions, all against endpoints that already exist. Land P0 + P1 on
the world page, verify a live human round-trip (type a message → reply appears in
the transcript tile), THEN #6 can delete `seon.web.inspector` + `seon.web.tile`.
Note in #6 that the raw-prompt / token-cache-line debug surface (P3) has no world
equivalent — rebuild it as a tile or consciously accept the loss.
