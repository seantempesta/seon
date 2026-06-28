---
type: issue
status: resolved
severity: friction
tags: [issue, architecture]
---
# Naming Conflict: "health" Means 2 Different Things

## Problem

"health" refers to both system health checks (is Datalevin up?) and the health domain (workouts, body metrics). The system namespace and the domain namespace collide, causing confusion.

## Where

- `src/seon/health.clj` — system health checks
- `src/seon/domains/health/` — health domain (workouts, body metrics)

## Acceptance Criteria

- System health and domain health have distinct, unambiguous names
- No namespace collision between infrastructure and domain concepts
- All references updated consistently

## Resolution (2026-06-28 audit)

Closed RESOLVED/STALE per `docs/seon/orchestrator/issues-audit-2026-06-28.md`:
`src/seon/domains/health/` was deleted; only the JVM `health.clj` (system health
checks) remains, so there is no longer a namespace collision.

## Related

- [[components/system-lifecycle]]
