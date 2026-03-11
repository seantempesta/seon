---
type: issue
status: open
severity: friction
---
# Duplication: get-conn for :seon.runtime in 3 Places

## Problem

`get-conn` for `:seon.runtime` is implemented identically in 3 places. Each has its own private copy doing the same thing. Bug fixes or behavior changes must be applied three times.

## Where

- `src/seon/render.clj:56`
- `src/seon/ns/routes.clj:122`
- `src/seon/db.clj:167`

## Acceptance Criteria

- Single canonical implementation in `seon.db` (or equivalent shared location)
- All three call sites use the shared version
- No private duplicates remain
- Tests pass

## Related

- [[components/database]]
