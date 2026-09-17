---
type: issue
status: open
severity: friction
created: 2026-09-17
tags: [issue, render, repl, wave/render-producers]
---

# Prospective issue refs render as unnamed checks

## Problem

The issue renderer accepts an unpersisted `:seon.issue/issue`, whose tests
are lookup refs, but `seon.issue/status-text` reads every test as a pulled
map. It prints an empty name followed by `not verified`. A computed
refactoring plan therefore loses the names the agent needs to inspect.
`status-view` falls back to the supplied row when the issue is not stored;
this is a supported input, not a failed database read to ignore.

## Evidence

On default PID 33583 after the owner's reset, the ordinary SCI evaluation
`(my.program/breaks {:seon.program/subject 'seon.turn/open?})` returned five
callers, 1,506 gating tests, and five prospective issues. Its shown text
contained repeated `: not verified` lines inside issue strings instead of
test identities. The evaluation reported outcome `ok`, duration 21,616 ms,
and 4,026,713,664 allocated bytes. Those measurements cover the complete
evaluation, not an attribution of allocation to the renderer alone.

The source boundary is `src/seon/issue.clj`, `status-text` and `status-view`.
The plan carries ordinary lookup refs such as
`[:seon.test/sym "seon.turn-test/open-is-derived"]`; keyword lookup on that
vector cannot retrieve `:seon.test/sym`.

## Owner

The issue AI/HTML render owner. The read checkpoint records this existing
renderer limitation; it does not change the plan's required issue grammar
or introduce a second presentation clipping site.

## Acceptance

Use a canonical fixture and the real value renderer to render a prospective
issue with test lookup refs and no stored issue row. Preserve identifiable
tests and the plan's done condition; bounded AI rendering must retain its
normal elision/requery data. Existing stored status views must still show
their verified/red/unrun state. No unnamed check lines may remain.
