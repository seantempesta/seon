---
type: issue
status: open
severity: cleanup
tags: [issue, render, schema, wave/transcript-deletion]
---

# The retired form projection is still declared and selected at HEAD

## Problem

Ruling 44 retired `:seon.render/form`: `:seon.render/ai` is source (comments
then forms) executed by the ordinary reply reader, so a separate authored form
projection has no role. The owner confirmed on 2026-09-06 that the projection
is removed as a concept and the docs now say so, but the code still declares
and selects it.

## Evidence

`rg -c "seon.render/form" src resources/seon/schemas` on 2026-09-07:
`src/seon/render.clj` 14, `src/seon/render/ns.clj` 3, `src/seon/bootstrap.clj`
5, `src/seon/render/walk.clj` 2, `src/seon/render/transcript.clj` 2,
`src/seon/cluster/agent.clj` 2, `src/my/note.clj` 2, `src/my/run.clj` 1,
`src/seon/render/web.clj` 1, `src/seon/cluster/loop.clj` 1,
`src/seon/schema.clj` 1; schema declarations in `seon.render.edn`,
`seon.cluster.agent.edn`, `seon.cluster.message.edn`, `my.note.edn`,
`my.run.edn`, `seon.ns.edn`, `seon.fn.edn`, `seon.schema.edn`,
`seon.render.walk.edn`. The selection chain in `seon.render/schema-stage` and
`floor-producer` still has a `:seon.render/form` branch and floor.

## Owner

`src/seon/render.clj` (selection, floor, `render-form`), the walk and
transcript consumers, and every schema declaring a `:seon.render/form`
producer. A cross-cutting deletion: orchestrator-owned, one commit after the
2026-09-07 debug-unit lanes land, because it touches files those lanes own.

## Acceptance

No `:seon.render/form` key in `src/` or `resources/seon/schemas/`; the
selection has exactly two outputs; `bin/test` platform tier green; the debug
page and context walk render unchanged for AI and HTML.
