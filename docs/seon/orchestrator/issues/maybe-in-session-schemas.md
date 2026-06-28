---
type: issue
status: open
severity: cleanup
tags: [issue, schema, jvm-track, paused]
---
# [:maybe] Convention Violation in session.clj

## Problem

`src/seon/session.clj` uses `[:maybe ...]` in 4 schema registrations. Project convention bans `[:maybe X]` — use `{:optional true} X` instead.

## File Refs

- `src/seon/session.clj` — grep for `[:maybe`

## Acceptance Criteria

- All `[:maybe ...]` replaced with `{:optional true}` on the containing map key
- Schemas validate correctly after change
- Tests pass

## Additional

Source docstring at `generate-session-id` (line ~177) says "hex" but the function generates Base62 via `runtime/generate-id`. Fix docstring to say "Base62".

## Severity

cleanup

## Milestone

[[vision/m3-convention-uniformity]]

## Status (2026-06-28 audit): valid but JVM-track is paused — defer until that track resumes.
