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
