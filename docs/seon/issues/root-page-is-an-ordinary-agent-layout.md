---
type: issue
status: open
severity: friction
tags: [issue, web, agent, flow, architecture]
---

# Give root a dedicated system layout

## Problem

The root system workspace is implemented as the ordinary root-agent page. It
inherits the ordinary heading, focus/pin state, primary panel, context rail,
and composer, while its fleet dashboard is a large canvas surface that also
contains a recursive root card.

## Evidence

`seon.web.datastar/serve-root!` calls `write-agent-page!` with `"root"` and
opens `/agent/root/feed`. `open-agent-feed!` invokes
`seon.ui.agent-view/render-agent-view` for every id. `seon.render.system` includes
root in `all-agent-ids`, special-cases a root card, and returns the complete
fleet dashboard as root's canvas HTML.

A read-only live request on 2026-07-14 returned title `seon · agent root`, the
composer placeholder `message agent root`, and the `/agent/root/feed` opener.
The detailed source and deletion map are in
[[root-workspace-session-source-audit-2026-07-14]].

## Owner

One dedicated root page layout over the shared `seon.web.view-unit`, reitit,
`seon.render.surface`, `seon.ui.header`, and `seon.web.datastar` mechanisms.
`seon.render.system` retains the AI fleet projection but must stop owning a
second human page inside root's canvas.

## Acceptance

- `/` has no ordinary agent heading, context rail, or canvas pin and contains no
  recursive root card.
- Root renders one bounded card per ordinary agent through the same shared
  fleet/focus/plan projections and general observed render-unit engine.
- `/agent/root` still canonicalizes to `/`; no second listener, feed registry,
  route tree, or surface materializer is introduced.
- Focused structural tests and a real root browser journey pass with no console
  errors and server-side gzip morph evidence.
