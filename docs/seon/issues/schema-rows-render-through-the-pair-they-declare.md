---
type: issue
status: open
severity: render
created: 2026-09-25
tags: [issue, render, selection, schema]
---

# A schema row renders through the pair it declares, not the schema pair

Seen 2026-09-25 on default (pid 39484) by lane impl-render-pairs-opus, after `:seon.schema/schema` gained its
pair (`seon.render.ns/schema-ai` / `schema-html`).

`/data?entity=[:seon.schema/key :seon.task/task]` renders through `seon.render.ns/task-html`, which refuses
(`seon.issue/task-edges` gets a nil handle). `[:seon.schema/key :seon.fn/fn]` renders through
`seon.render.ns/function-ai` / `function-html` and fails the same way. A schema without a pair, such as
`:seon.task/intent`, renders correctly through the schema pair.

## Cause

1. `:seon.schema/schema` declares `:seon.program/projected-properties true`, so a schema's Malli properties are
   stored as datoms on its row. There are 334 schema rows carrying `:seon.render/ai` on default.
2. The pulled row therefore carries `:seon.render/ai seon.render.ns/task-ai`.
3. The render ladder treats a value's own `:seon.render/ai` / `:seon.render/html` key as the master override
   (AGENTS.md "Render ordinary values totally"). `seon.render/entity-owner` also exempts those two keys
   explicitly (`src/seon/render.clj:336-338`), so the schema row is rendered by the renderer it merely describes.

The same key means two things: "this value renders with X" (the override) and "this schema declares X for its
values" (a stored projected property). Every schema that declares a pair, including every error schema, shows its
row through the wrong renderer, and in a task graph those schema nodes fail.

## Fix direction (owner of the selection ladder or of projected properties)

Give the stored declaration its own meaning instead of reusing the override key. Either project the pair
properties onto the row under the schema's own attribute names, or do not project the render keys at all, since the
projection registry already carries them for `declared-producers`. Do not add a special case for
`:seon.schema/schema` in selection.

Regression: `/data` and `walk/graph` render the rows `[:seon.schema/key :seon.task/task]` and
`[:seon.schema/key :seon.fn/fn]` through `seon.render.ns/schema-ai` / `schema-html`.
