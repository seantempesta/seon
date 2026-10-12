---
name: ui-canvas
description: "Assess whether a requested agent-authored canvas or control needs a new contract or fits Seon's existing namespace-page renderer. Use for generalized forms, buttons, inputs, and my.canvas proposals."
---

# Canvas and control boundary

Read [UI architecture](../../../docs/seon/architecture/ui.md) and
[agent-loop.md](../../../docs/seon/specs/agent-loop.md).
Use the datastar-web-ui skill for the existing page, block, and feed
owners.

## Inspect before inventing an API

The current route authority is `seon.render.route/routes`.
It includes namespace and agent/debug routes, the message action,
context handler, feed, and data browser; it contains no generic
`/call` route. A retained context handler does not make manual
curation part of the target.

Do not present proposed `my.canvas` constructors as installed APIs.
First determine whether ordinary schema-declared HTML can represent
the requested value. If new interaction is needed, declare its
data shape and effect boundary before naming executable calls.

Revisioned packages are already implemented, not a future canvas
feature: `join-package` reuses retained serialized output and the
socket writer waits for drain or close
(`seon.render.web/join-package`; `seon.render.web/write-package!`).
Reuse that delivery path. Stable block ids come from
`seon.render.block/surface-id`.

## Render the concern — target

One entity schema declares one AI/HTML pair. Scalars share the
entity's own block; components and declared derived queries render
whole concerns. No pair means the attribute-map printer. Render
functions choose useful forms from data; `dir` and `doc` return
program data rather than separate teaching prose.

The target context has a static prefix, the agent's own forms and results,
and one fresh current-state block. Rendering the block or its debug view
never appends evaluations or repeats effects. There is no opening or input
eval request and no compaction path that regenerates one (agent-loop.md §3.4).

The value renderer applies the profile once at evaluation time.
The stored printed value remains immutable; HTML can inspect actual live
objects and uses the printed value after restart. A canvas must not create
another result serializer, clipping owner, history formatter, or
effectful helper that owns browser transport.

A future action returns declared data interpreted by the existing
turn/effect owner. Agent code never receives an SSE connection.
Exact generalized constructors remain a separate design contract.
