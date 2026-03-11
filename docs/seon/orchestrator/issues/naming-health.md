---
type: issue
status: open
severity: friction
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

## Related

- [[components/system-lifecycle]]
