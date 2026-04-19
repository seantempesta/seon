---
type: capability
status: complete
tags: [vision, agent]
---
# Agent Pool Self-Healing

The agent JVM pool maintains itself. Unhealthy processes are detected and replaced, spawn rates are limited to prevent resource exhaustion, and OOM failures from misconfigured JVM options are eliminated. The pool converges to its target size without human intervention.

## What Exists

- JVM opts fixed (no more OOM from MaxMetaspaceSize)
- stderr reader thread, `.isAlive()` checks, grace period for startup
- Spawn rate limiting prevents resource storms
- Auto-replenishment maintains target pool size
- Instrumentation deferred to claim time (not pre-warm time)

## Gaps

None.

## Related

- Components: [[components/harness]]
- PRDs: [[prds/startup-reliability/prd]], [[prds/stability-improvements/prd]]
