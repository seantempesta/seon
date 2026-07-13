---
type: component
status: active
tags: [component, web]
---

# Web UI (CLJS pod web lane)

> `seon.web.serve` (`src/seon/web/serve.cljs`) hosts the one loopback HTTP+SSE
> web UI (default port 7890, with environment overrides). `seon.web.router`
> owns the route vector; `seon.web.datastar` and `seon.web.debug` render pages.
> The archived JVM web application is not a second supported renderer.

## Page shells

| Route | Render path | What it is |
|-------|-------------|------------|
| `/` | `serve-root!` → root `agent-view` | Root's ordinary agent view; its pinned `seon.render.system/system-view` canvas is the cluster dashboard. |
| `/agents` | `agents-page-html` → `agents-view` | The live agents view: status header plus one row per `:seon.agent/id` fact, with the agent's compact canvas preview. The new-agent purpose bar is outside the morph target. |
| `/agent/<id>` | `agent-page-html` → `seon.ui.agent-view/agent-view` | A large primary surface and a scrollable rail of every context block that has an HTML twin. The most recently agent-updated surface is selected initially; selecting a rail card is browser-local state. |
| `/agent/<id>/debug` | `debug-page!` → `/agent/<id>/debug/feed` | A cheap operator shell: lazy exact AI text and HTML twins, token breakdown, activity, and chat. |
| `/data` | `data-page!` → `data-browser-fragment` | The live database browser. |

Routing: `seon.web.serve` hosts the HTTP front door and supplies handlers to `seon.web.router`. Seeded `:seon.route/*` datoms own the agents, unit, and per-agent debug page/feed routes. Static assets, `/data`, and POST actions are the router's static supplement. Handler symbols resolve late, so a redefinition takes effect without a parallel route path.

## SSE morphing

- `seon.web.datastar/install!` owns the one stable-keyed Datahike listener for `/`, `/agents`, and `/agent/<id>`. One lifecycle-owned coalescer retains the complete effective change window, settles ordinary work at 16 ms and structural work at 300 ms, and caps continuous bursts at 500 ms. Equivalent tabs share one derived render; each gzip stream has latest-wins backpressure.
- Agent feeds keep renderer read-sets. Ordinary transactions send only complete, ID-addressed surfaces whose read attributes changed; structural context/program changes send the full `#app-view`.
- Per-agent debug uses the same `seon.web.datastar` gzip registry, listener, unit activation door, reconnect fencing, and backpressure as ordinary pages. The final feed close removes the listener; no open feed means no transaction callback work.
- The debug left pane comes from one AI-only `seon.agent.debug/ctx-preview`: the same system-message and context producers used by the LLM call, rendered from one unfiltered database snapshot. One lazy unit exposes the exact assembled prompt; the other sections break down the retained source-block bodies without rerendering them.
- `/data` retains its own temporary plain-SSE path, but installs that listener only on first open and removes it on final close.

## Brand consumption

Every shell reads `brand/info` from [[components/web-brand]] for `<title>` (`brand/page-title`), `data-theme` on `[:html]`, and the optional downstream stylesheet after `output.css` so its token overrides win. `seon.client` awaits `brand/sync!` directly at boot; debug owns no boot hook.

## Debug is pay-for-use

The ordinary agent header links to `/agent/<id>/debug`; it does not embed an iframe or open a second stream. The page GET returns only a shell and view id. Its `/debug/feed` performs one exact AI projection, publishes closed raw bodies and every HTML twin as inactive stubs, and materializes only units the operator expands through `GET /view/unit`. HTML discovery reads metadata rather than block bodies. Leaving the page closes the feed and, when it was the final live page, removes the shared listener. Escape returns to the ordinary agent view.

## Canvas and context surfaces

- `seon.render/render-agent-canvas` is the one canvas entry point. The pin is `:seon.render.canvas/content`; without a pin, the latest deliberately updated HTML surface wins, then `seon.render.canvas/welcome`.
- `seon.ui.agent-view` resolves context blocks from database facts, omits AI-only or empty HTML renders, and projects compact/expanded faces without invoking a renderer twice.
- The focused primary surface is not duplicated in the rail. Transcript faces follow the latest reply at the bottom; the rail itself is independently scrollable.

## Dependencies

- Uses: `seon.db`, `seon.agent.debug`, `seon.render`, `seon.ui.agent-view`, `seon.ui.header`, `seon.ui.html`, `seon.web.brand`, `seon.web.router`, Node HTTP, and Node zlib.
- Used by: `seon.client/start-runtime!` starts `seon.web.serve`; page feeds install their shared listener lazily after the database is ready.
