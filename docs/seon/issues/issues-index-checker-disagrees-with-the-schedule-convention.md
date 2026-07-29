---
type: issue
status: open
severity: friction
tags: [issue, tooling, docs]
---

# The issues-index checker disagrees with the schedule convention

## Problem

The triage rebuild (23a08423b) made index.md the ranked owner SCHEDULE and
updated conventions so the legacy severity-index generator must not
overwrite it. `bin/issues-index --check` still validates against the
generated shape and now reports the hand-built schedule as stale
(observed by the lane-tooling-fix lane, 2026-07-29 evening).

## Owner

`bin/issues-index` — align the checker with the schedule convention
(validate coverage: every open note has a schedule row; stop demanding
the generated severity shape).

## Acceptance

`bin/issues-index --check` passes on the schedule-form index and fails
when an open note lacks a schedule row.

## Update 2026-07-29 evening

A lane regenerated `index.md`, overwriting the hand-built SCHEDULE (the
triage lane's ranked lane/wave assignments). The generator is now
authoritative again and `--check` is clean at 12 open / 750 archived,
but the schedule's per-issue destinations survive only in
`plan/unsettled.md` ("Next, in order") and the lane dispatch record.
Decide one home: either the generator learns to emit a destination
column (preferred — one artifact, derived), or the schedule lives in a
separate hand-owned file that the generator never touches.
