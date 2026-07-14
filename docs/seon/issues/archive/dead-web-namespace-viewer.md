---
type: issue
status: superseded
severity: cleanup
milestone: M4
tags: [issue, web, architecture]
---
# Dead Code: web/namespace.clj and ui/viewer.clj

## Problem

`web/namespace.clj` and `ui/viewer.clj` have no callers and no tests. They were fully replaced by `ns/routes`. Two dead files remain in `src/`, adding confusion and maintenance burden.

## Where

- `src/seon/web/namespace.clj` — no callers
- `src/seon/ui/viewer.clj` — no callers

## Acceptance Criteria

- Both files deleted
- No remaining references to either namespace in the codebase
- No test failures after removal

## Related

- [[components/namespace-lifecycle]]

## Status (2026-06-28 audit): valid but JVM-track is paused — defer until that track resumes.
