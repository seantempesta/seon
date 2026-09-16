---
type: issue
status: open
severity: friction
created: 2026-09-16
tags: [issue, testing, performance]
---

# Test base publication waits for idle agent threads after completion

## Evidence

During the preparation-cost slice, the real `bin/test --paths
src/seon/schedule.clj src/seon/test/runner.clj -- seon.db-test` cold
publication printed its completed-base line while PID 64773 remained alive
at 0.0% CPU. `jcmd 64773 Thread.print` observed `DestroyJavaVM` elapsed
39.81 seconds and two non-daemon `clojure-agent-send-off-pool` threads,
each parked in `SynchronousQueue.poll` / `ThreadPoolExecutor.getTask`.
This is idle exit delay, not program analysis. Raw evidence is retained at
`tmp/test-preparation-costs/publication-threads.txt`; the durable excerpt and
gate measurements belong to
[the landing note](../../prds/steward-platform/research/test-preparation-costs-2026-09-16.md).

## Owner and acceptance

`seon.test.runner/-main`'s `--prepare-base` branch returns after publication
without shutting down Clojure's agent executors. The process owner should
finish its owned work and terminate those executors before returning.
Verify on a real cold gate: publication completion to child exit no longer
waits for idle executor expiry, and the resulting store, manifest, and
provenance remain usable. No execution bound should change.

Deferred at the assignment's step-2 design review boundary; worker readiness
priming does not alter publication process shutdown.
