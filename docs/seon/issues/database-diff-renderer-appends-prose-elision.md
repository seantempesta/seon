---
type: issue
status: open
severity: friction
tags: [issue, render, database, class/n1, wave/strict-repl-display]
---

# Database diff rendering still appends a prose elision

## Problem

The database diff's explicit AI renderer describes omitted detail with an
English tail instead of the shared elision value.

## Evidence

At implementation commit `563034709`, `src/seon/db.clj:2329`
`render-diff-ai` emits `Full data elided (approximately … tokens); requery by …`
at lines 2350–2354. This is source evidence; no database diff was induced
on default. The generic result renderer's separate prose tail was removed
in that commit. This note is the remaining database-owned portion of
[the archived compound finding](archive/one-elision-has-two-representations-in-one-context.md).

## Owner

`seon.db/render-diff-ai`, using the existing `seon.print/elision` constructor.
Outside n1-render-substitution's explicitly assigned production paths.

## Acceptance

A real diff's omitted detail is one elision value with counts, path, and a
working requery form. No parallel English representation survives.
