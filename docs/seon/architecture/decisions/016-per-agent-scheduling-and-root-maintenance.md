---
type: decision
status: active
date: 2026-08-04
tags: [decision, architecture, flow, scheduling, maintenance]
---

# ADR-016: Per-agent scheduling and root-owned maintenance

## Decision

Each agent graph owns its schedule proc. Declared tasks, schedules, and fires
produce ordinary messages to the owning agent; there is no central ticker or
scheduler entity. Root owns the maintenance portfolio as ordinary declared
tasks: database and blob reclamation, footprint inspection, dead-root cleanup,
log retention, and related repair. Explicit operator maintenance invokes the
same owners.

## Consequences

- Scheduling scales with agents and keeps one graph owner per task.
- A due fire enters the existing durable message path.
- Maintenance is queryable work, not a hidden daemon.
- Manual and scheduled maintenance cannot drift into parallel mechanisms.

## Related

- [[agent-runtime]] — per-agent graphs and message wakes.
- `AGENTS.md` §2.3 and the `seon-flow-architecture` skill — event-driven
  readiness, execution bounds, and declared ownership.
