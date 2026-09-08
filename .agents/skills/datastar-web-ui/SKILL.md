---
name: datastar-web-ui
type: skill
status: active
description: "Change Seon's Datastar namespace pages, debug previews, entity blocks, route table, SSE delivery, or message form. Use before editing the web render owners; generalized canvas design has a separate contract."
---

# Datastar web UI

The target is [turn PRD §13–§15](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md).
Use [UI architecture](../../../docs/seon/architecture/ui.md) for the
render contract and the seon-flow-architecture skill for proc changes.

## Inspect the existing owners

Discover the endpoint through `bin/seon status`; this wave verifies
default at the assigned endpoint. An HTTP success proves reachability,
not layout or adoption.

The canonical route data and compiled Reitit router live in
`src/seon/render/route.clj:5` and `:33`; handler binding lives in
`src/seon/render/web.clj:3633`.
Read that table instead of carrying another route list. A route name
or retained manual context handler does not authorize preserving an
obsolete behavior from before §14.

Stable DOM ids derive through `seon.render.block/surface-id`
(`src/seon/render/block.clj:61`). Reuse that owner for morph targets.

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
through that pair. The current entry points are
`src/seon/repl.clj:315` and `:323`; `text` at `:246`
owns the REPL grammar. Do not mistake these existing entry points
for proof that §15 storage has landed.

History is chronological, oldest first, with all turns shown by
default. Do not hand-assemble entry kinds or format evaluations
outside that pair.

## Shown text and previews — target

The value renderer applies the profile once at evaluation time.
The evaluation stores shown text, out, and error. History reuses
those bytes; it never reruns a form or adds a history-wide clip.
HTML renders actual live result objects without presentation
clipping and falls back to shown text after restart.

Generated reads are stored in system turns. Before each agent turn,
the since-query diff selects changed reads from every distinct
read form's latest evaluation, including agent-written reads.
Writes and effects never rerun. Passive browser render work cannot
append evaluations.

The debug invocation cache holds previews in memory.
`?prompt=true` projects stored history plus the would-be system
turn without writing. Compaction wipes evaluations and regenerates
the opening. Do not preserve manual Add/remove/curation controls
as a second context mechanism.

## Preserve delivery and input

The render proc owns serialized revisioned packages.
`join-package` returns retained bytes without deriving or serializing
again (`src/seon/render/web.clj:1937`).
The feed writer's `write-package!` waits for drain or close while
the sliding-one tap retains the newest complete package
(`src/seon/render/web.clj:2875`).

The dependency's `write-state` returns pending bytes and a
drain-or-close completion
(`reference-code/http-kit/src/org/httpkit/server.clj:321`).
A successful send alone is not a drain event. Revision gaps use
the complete keyframe; never put delta-only values in a lossy buffer.

Keep message inputs, disclosure, and scroll stable across morphs.
Message submission commits ordinary facts, and the existing feed
renders their consequence. Do not add an action-specific repaint
channel or pass an SSE connection to agent-authored code.

Verify actual browser layout, input preservation, and feed updates.
Separately verify the exact prompt prefix and no-write preview behavior.
A cache marker or source publication marker alone proves neither.
