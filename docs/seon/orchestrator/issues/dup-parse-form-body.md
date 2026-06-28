---
type: issue
status: open
severity: cleanup
tags: [issue, architecture, jvm-track, paused]
---
# Duplication: parse-form-body in Two Places

## Problem

`parse-form-body` is implemented identically in `web/handlers.clj` and `ns/routes.clj`. Same logic, two copies.

## Where

- `src/seon/web/handlers.clj:47`
- `src/seon/ns/routes.clj:649`

## Acceptance Criteria

- Single canonical implementation in a shared location (e.g., `web/util.clj` or `web/handlers.clj`)
- Both call sites use the shared version
- Tests pass

## Related

- [[components/web-layer]]

## Status (2026-06-28 audit): valid but JVM-track is paused — defer until that track resumes.
