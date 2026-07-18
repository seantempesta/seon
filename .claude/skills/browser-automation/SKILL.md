---
name: browser-automation
description: "Verify Seon's own web UI in a real browser. Use for the root view, an /agent/{id} page, /data, an agent debug page, Datastar morphs, canvas controls, layout, or console errors on http://127.0.0.1:7890. Keep browser tabs agent-owned, and verify long-lived gzip SSE feeds with a server-side client because the browser bridge may return 503."
---

# Browser Automation — verifying the Seon pod UI

You have Chrome MCP tools (`mcp__claude-in-chrome__*`) to look at the **pod's own
web UI** on `http://127.0.0.1:7890` — root `/`, an `/agent/{id}` page,
the `/data` database browser, and `/agent/{id}/debug`. This skill is coordination (don't clobber peer tabs)
+ the one hard limit (SSE doesn't verify in-browser).

**Tool names:** shorthand here (`navigate`, `computer screenshot`) maps to
`mcp__claude-in-chrome__*` (e.g. `mcp__claude-in-chrome__navigate`). Load them
via ToolSearch first (one batched `select:` call — see the MCP server note).

## The KEY limit: SSE streams DON'T verify in the browser agent

The Datastar UI is `view = f(db)` morphed over a long-lived
`text/event-stream` (`/agent/root/feed`, `/agent/{id}/feed`, `/data/feed`,
and `/agent/{id}/debug/feed`). The in-tool Chrome
agent's network layer **503s long-lived event-streams**, so the page may load
the shim but never receive a morph in the agent's view — that is a tooling
artifact, NOT a broken feed.

So: confirm a feed actually pushes **server-side**, not in the browser.

- A server-side client that GETs the `/feed` URL and prints the
  `datastar-patch-elements` frames. Loopback defaults to identity encoding;
  explicitly configured remote mode negotiates `Content-Encoding: gzip`.
- `bin/seon logs pod --follow` — the `FEED OPEN` / `broadcast` lines prove the
  tx-listener fired and pushed.
- A human eyeball on the real page.

The browser agent is for the STATIC render (did the shim load, is the layout/
theme right, console errors, a `@post` button firing) — not for proving liveness.
SSE mechanics → the **`datastar-web-ui`** skill.

## Tab ownership (don't step on peers)

Agents share the browser. Make your own tab; never reuse one you didn't create.

```
tabs_context_mcp     # see existing tabs
tabs_create_mcp      # YOUR tab — remember its id for the session
navigate <url>       # load your page
# leave tabs open — the orchestrator/human cleans up
```

If a tab id goes invalid, `tabs_context_mcp` for fresh ids. Element refs
invalidate on navigation — re-`find` after navigating.

## Pod URLs (active, port 7890)

| Page | Path |
|---|---|
| Root agent view | `/` |
| Agent page | `/agent/{id}` |
| Datom browser | `/data` |
| Agent debug (exact LLM bytes) | `/agent/{id}/debug` |

## Verify a static UI change

```
1. tabs_create_mcp                  → your tab
2. navigate http://127.0.0.1:7890/
3. computer screenshot              → layout + Phosphor theme correct?
4. read_console_messages            → JS errors? (pattern "error|Datastar")
5. find / computer left_click       → exercise a @post button (e.g. + new agent)
6. read_network_requests            → POST fired? (call it BEFORE the click)
```

## Common issues

| Problem | Fix |
|---|---|
| Page loads but never updates live | EXPECTED — browser agent 503s SSE; verify the feed server-side |
| Tab id invalid | `tabs_context_mcp` for fresh ids |
| Element ref stale | re-`find` after any navigation |
| No network captured | call `read_network_requests` BEFORE the action |
| `data-on:click` does nothing | it's `data-on:click` with a COLON, not a hyphen — see `datastar-web-ui` |

## Key files

| File | Purpose |
|---|---|
| `src/seon/web/serve.cljs` | the pod HTTP server (port 7890) + POST handlers |
| `src/seon/web/datastar.cljs` | root + agent pages and their shared gzip SSE mechanism |
| `src/seon/web/router.cljs` | reitit routes from `:seon.route/*` datoms |
