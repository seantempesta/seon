---
name: datastar-web-ui
description: "Change Seon's Datastar namespace pages, debug previews, entity blocks, route table, SSE delivery, or message form. Use before editing the web render owners; generalized canvas design has a separate contract."
---

# Datastar web UI

The target is [agent-loop.md](../../../docs/seon/specs/agent-loop.md), especially
§3.4's current-state block and single debug view.
Use [UI architecture](../../../docs/seon/architecture/ui.md) for the
render contract and the seon-flow-architecture skill for proc changes.

## Inspect the existing owners

Discover the endpoint through `bin/seon status`; this wave verifies
default at the assigned endpoint. An HTTP success proves reachability,
not layout or reload.

The canonical route data and compiled Reitit router live in
`seon.render.route/routes` and `seon.render.route/router`; handler binding lives in
`bind-handlers` and `handler` (`seon.render.web/handler`).
Read that table instead of carrying another route list. A route name
or retained manual context handler does not authorize preserving an
obsolete behavior.

Stable DOM ids derive through `seon.render.block/surface-id`
(`seon.render.block/surface-id`). Reuse that owner for morph targets.

## Entity rendering — target

One entity schema declares one AI/HTML pair. Render scalars together
in its own block, components as their concerns, and derived query
blocks from functions declared once on the agent schema.
No render pair means the default attribute-map printer.
Do not add attribute-level pairs or a second renderer-selection path.

A render function chooses source from its data. An empty plan emits
useful `dir`/`doc` forms; a populated plan emits current, ready,
and blocked queries. Those documentation functions return data.
There is no separate teaching-prose mechanism.

The evaluation schema declares `seon.repl/render-ai` and
`seon.repl/render-html`; the walk renders evaluations in order
through that pair (`resources/seon/schemas/seon.cluster.eval.edn`). The current
entry points are `seon.repl/render-ai` and `seon.repl/render-html`; `seon.repl/text` owns the REPL grammar.

History is chronological, oldest first, with all turns shown by
default. Do not hand-assemble entry kinds or format evaluations
outside that pair.

## Printed values and previews — target

The value renderer applies the profile once at evaluation time.
The evaluation stores the printed value, out, and error. History reuses
those bytes; it never reruns a form or adds a history-wide clip.
HTML renders actual live result objects without presentation
clipping and falls back to the printed value after restart.

The agent debug page shows the agent's context through the turn's own context
function, on demand, via the value printer's HTML face (`sent-context-html`). Debug text wraps in CSS; AI bytes do not
acquire display-width breaks (`seon.render.value/prepare`).
Do not preserve manual Add/remove/curation controls
as a second context mechanism.

## Preserve delivery and input

The render proc owns serialized revisioned packages.
`join-package` returns retained bytes without deriving or serializing
again (`seon.render.web/join-package`).
The feed writer's `write-package!` waits for drain or close while
the sliding-one tap retains the newest complete package
(`seon.render.web/write-package!`).

The dependency's `write-state` returns pending bytes and a
drain-or-close completion
(`org.httpkit.server/write-state`).
A successful send alone is not a drain event. Revision gaps use
the complete keyframe; never put delta-only values in a lossy buffer.

Keep message inputs, disclosure, and scroll stable across morphs.
Message submission commits ordinary facts, and the existing feed
renders their consequence. Do not add an action-specific repaint
channel or pass an SSE connection to agent-authored code.

Verify actual browser layout, input preservation, and feed updates.
Separately verify the exact prompt prefix and no-write preview behavior.
A cache marker or source publication marker alone proves neither.
