---
type: issue
status: resolved
severity: friction
milestone: M2
tags: [issue, database, architecture]
---
# Duplication: get-conn for :seon.runtime in 3 Places

## Problem

`get-conn` for `:seon.runtime` is implemented identically in 3 places. Each has its own private copy doing the same thing. Bug fixes or behavior changes must be applied three times.

## Where

- `src/seon/render.clj:56` — private `get-conn` for `:seon.runtime` (confirmed present)
- `src/seon/ns/routes.clj:122` — private `get-conn` for `:seon.runtime` (confirmed present)
- `src/seon/db.clj:167` — **stale reference** (2026-03-11): line 167 is now `get-conn-manager`, not a `:seon.runtime` get-conn. Two duplicates remain (render.clj and ns/routes.clj).

## Acceptance Criteria

- Single canonical implementation in `seon.db` (or equivalent shared location)
- All three call sites use the shared version
- No private duplicates remain
- Tests pass

## Resolution (2026-06-28 audit)

Closed RESOLVED per `docs/seon/orchestrator/issues-audit-2026-06-28.md` (frontmatter
was wrongly `open`): `get-conn` was DELETED in M-1; `render.clj:55` and
`routes.clj` now carry tombstones, so no duplicate `:seon.runtime` `get-conn`
remains.

## Related

- [[components/database]]
