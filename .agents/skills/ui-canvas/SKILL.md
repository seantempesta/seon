---
name: ui-canvas
description: "Assess whether a requested agent-authored canvas or control needs a new contract or fits Seon's existing namespace-page renderer. Use for generalized forms, buttons, inputs, and my.canvas proposals."
---

# Canvas and control boundary

Read [UI architecture](../../../docs/seon/architecture/ui.md) and
[turn PRD §13–§15](../../../docs/prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md).
Use the datastar-web-ui skill for the existing page, block, and feed
owners.

## Inspect before inventing an API

The current route authority is `src/seon/render/route.clj:5`.
It includes namespace and agent/debug routes, the message action,
context handler, feed, and data browser; it contains no generic
`/call` route. A retained context handler does not make manual
curation part of the §14 target.

Do not present proposed `my.canvas` constructors as installed APIs.
First determine whether ordinary schema-declared HTML can represent
the requested value. If new interaction is needed, declare its
data shape and effect boundary before naming executable calls.

Revisioned packages are already implemented, not a future canvas
feature: `join-package` reuses retained serialized output and the
socket writer waits for drain or close
(`join-package`, `src/seon/render/web.clj:1857`; `write-package!`, `:2777`).
Reuse that delivery path. Stable block ids come from
`src/seon/render/block.clj:61`.

## Render the concern — target

One entity schema declares one AI/HTML pair. Scalars share the
entity's own block; components and declared derived queries render
whole concerns. No pair means the attribute-map printer. Render
functions choose useful forms from data; `dir` and `doc` return
program data rather than separate teaching prose.

System turns store generated evaluations. The turn owner refreshes
changed reads across every distinct read form's latest evidence.
Passive browser rendering does not append evaluations or repeat
writes/effects. Manual context curation is superseded by compaction:
wipe evaluations and regenerate the opening.

The value renderer applies the profile once at evaluation time.
Stored shown text remains immutable; HTML can inspect actual live
objects and uses shown text after restart. A canvas must not create
another result serializer, clipping owner, history formatter, or
effectful helper that owns browser transport.

A future action returns declared data interpreted by the existing
turn/effect owner. Agent code never receives an SSE connection.
Exact generalized constructors remain a separate design contract.
