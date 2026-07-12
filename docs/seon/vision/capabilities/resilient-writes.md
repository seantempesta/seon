---
type: capability
status: partial
tags: [vision, database]
---
# Resilient Database Writes

Database writes handle transient failures gracefully. Timeouts prevent hung writes from stalling the system, and a single retry recovers from transient connection errors. The code scanner has per-batch isolation and a circuit breaker; the DB write pipeline does not yet have these for its own operations.

## What Exists

- `transact-with-timeout!` wraps each write with a 30s future+deref timeout
- Single retry on connection error with fresh connection acquisition
- Per-batch error isolation in the code scanner (`safe-transact!` in `graph/ingest.clj`)
- Circuit breaker after 3 consecutive ingest failures in the code scanner (`system.clj`)

## Gaps

- DB writer step-fn has no circuit breaker — sustained failures produce per-request errors, not system-level degraded health signal
- "Per-batch" isolation applies only to graph ingestion, not to the core DB write pipeline itself

## Related

- Components: [[components/database]], [[components/system-lifecycle]]
- PRDs: `prds/startup-reliability/prd`
