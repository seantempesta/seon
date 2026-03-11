---
type: issue
status: open
tags: [issue, schema]
---
# routes.clj passes conn where db-name expected

## Problem

`src/seon/ns/routes.clj` line ~486 passes a Datalevin conn object to `resolve-renderer`, which expects a `db-name` keyword. This violates the function's contract. May work accidentally if downstream code tolerates a conn where it expects a keyword, but it's a type mismatch at the call site.

## File Refs

- `src/seon/ns/routes.clj:486` — call site
- `src/seon/render.clj` — `resolve-renderer` signature takes `[db-name available-keys target-ns]`

## Acceptance Criteria

- Call site passes a db-name keyword, not a conn object
- `resolve-renderer` behavior unchanged
- Tests pass

## Severity

friction

## Milestone

[[vision/m3-convention-uniformity]]
