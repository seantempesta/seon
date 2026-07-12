---
type: capability
status: complete
tags: [vision, flow]
---
# System Self-Monitoring

The system monitors its own health and reports degradation before it becomes failure. Readiness gates prevent premature operation, post-start observation catches delayed issues, and circuit breakers isolate failing subsystems.

## What Exists

- Readiness gate with 3 operational checks before accepting work
- Two-phase startup (nREPL+HTTP first, DB second)
- Post-start observation at 30s and 60s intervals
- Circuit breaker after 3 consecutive failures
- Health endpoint for external monitoring

## Gaps

None.

## Related

- Components: [[components/system-lifecycle]]
- PRDs: `prds/stability-improvements/prd`, `prds/startup-reliability/prd`
