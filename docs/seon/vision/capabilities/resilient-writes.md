---
type: capability
status: complete
tags: [vision, database]
---
# Resilient Database Writes

Database writes handle transient failures gracefully. Per-batch error isolation prevents one bad transaction from blocking others. Timeouts prevent hung writes from stalling the system. Circuit breakers signal degraded health after repeated failures rather than retrying forever.

## What Exists

- `safe-transact!` catches per-batch failures independently
- `transact-with-timeout!` wraps with 30s future+deref
- Circuit breaker after 3 consecutive failures signals degraded health

## Gaps

None.

## Related

- Components: [[components/database]], [[components/system-lifecycle]]
- PRDs: [[prds/startup-reliability/prd]]
