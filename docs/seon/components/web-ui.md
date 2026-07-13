---
type: component
status: active
tags: [component, web]
---

# Web UI (CLJS pod web lane)

> The pod's own browser UI: `seon.web.serve` (`src/seon/web/serve.cljs`) hosts a loopback HTTP+SSE server (default port 7890, `SEON_PORT`/`SEON_PORT_FILE` overrides); `seon.web.router` owns the route vector, and `seon.web.datastar` + `seon.web.debug` render the pages. Distinct from the JVM [[components/web-layer]] — this lane is Node-side, has no `.clj` sibling, and is what the demo browser actually talks to.

## Page shells

| Route | Render path | What it is |
|-------|-------------|------------|
| `/` | `serve-root!` → root `agent-view` | Root's ordinary agent view; its pinned `seon.render.system/system-view` canvas is the cluster dashboard. |
| `/agents` | `agents-page-html` → `roster-view` | The live roster: status header plus one agent row with the agent's compact canvas preview. The new-agent purpose bar is outside the morph target. |
| `/agent/<id>` | `agent-page-html` → `seon.ui.agent-view/agent-view` | A large primary surface and a scrollable rail of every context block that has an HTML twin. The most recently agent-updated surface is selected initially; selecting a rail card is browser-local state. |
| `/agent/<id>/debug` | `debug-page!` → `debug-shell` | A separate operator page: exact AI prompt blocks on the left, HTML twins on the right, token breakdown, activity, and chat. |
| `/data` | `data-page!` → `data-browser-fragment` | The live database browser. |

Routing: `seon.web.serve` hosts the HTTP front door and supplies handlers to `seon.web.router`. Seeded `:seon.route/*` datoms own the main agent and roster routes; static assets, operator pages, and POST actions are the router's static supplement. Handler symbols resolve late, so a redefinition takes effect without a parallel route path.

## SSE morphing

- `seon.web.datastar/install!` owns the gzip feed listener for `/`, `/agents`, and `/agent/<id>`. A 50 ms trailing window coalesces transaction bursts. Equivalent tabs share one derived render; each gzip stream has latest-wins backpressure.
- Agent feeds keep renderer read-sets. Ordinary transactions send only complete, ID-addressed surfaces whose read attributes changed; structural context/program changes send the full `#app-view`.
- `seon.web.debug/install!` owns the operator listener. It only schedules agents with an open debug SSE connection, so a normal agent page does not build prompt previews or HTML debug cards. `/data` likewise renders only for its own open connections.
- The debug left pane comes from `seon.agent.debug/ctx-preview`: the same system-message and context producers used by the LLM call, rendered from one unfiltered database snapshot.

## Brand consumption

Every shell reads `brand/info` from [[components/web-brand]] for `<title>` (`brand/page-title`), `data-theme` on `[:html]`, and the optional downstream stylesheet after `output.css` so its token overrides win. `seon.web.debug/install!` kicks `brand/sync!` at boot.

## Debug is pay-for-use

The ordinary agent header links to `/agent/<id>/debug`; it does not embed an iframe or open a second stream. The debug page computes its initial snapshot only when that route is requested, then opens `/agent/<id>/debug/sse`. Leaving the page closes the connection, so later transactions do no debug rendering for that agent. Escape returns to the ordinary agent view.

## Canvas and context surfaces

- `seon.render/render-agent-canvas` is the one canvas entry point. The pin is `:seon.render.canvas/content`; without a pin, the latest deliberately updated HTML surface wins, then `seon.render.canvas/welcome`.
- `seon.ui.agent-view` resolves context blocks from database facts, omits AI-only or empty HTML renders, and projects compact/expanded faces without invoking a renderer twice.
- The focused primary surface is not duplicated in the rail. Transcript faces follow the latest reply at the bottom; the rail itself is independently scrollable.

## Dependencies

- Uses: `seon.db`, `seon.agent.debug`, `seon.render`, `seon.ui.agent-view`, `seon.ui.header`, `seon.ui.html`, `seon.web.brand`, `seon.web.router`, Node HTTP, and Node zlib.
- Used by: `seon.client/start-runtime!` starts `seon.web.serve` and installs the Datastar and debug listeners after the database is ready.
