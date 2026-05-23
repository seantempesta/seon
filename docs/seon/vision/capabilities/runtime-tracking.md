---
type: capability
status: complete
tags: [vision, flow]
---
# Runtime Instance Tracking

The system knows what is running, what has run, and what crashed. Dual storage (fast cache + durable Datahike) tracks agent runs with cost, duration, and turn counts. Crash detection identifies unclean shutdowns. Flow handles and topology snapshots provide a live map of the running system.

## What Exists

- Dual storage: in-memory cache for speed, Datahike for durability
- Crash detection via `mark-crashed!`
- Agent run tracking with cost, turns, duration
- Flow handle registry and topology snapshots

## Gaps

None.

## Related

- Components: [[components/runtime]]
- PRDs: [[prds/refinement/prd]]
