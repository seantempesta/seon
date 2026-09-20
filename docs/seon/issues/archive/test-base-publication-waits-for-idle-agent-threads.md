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
[the landing note](../../../prds/steward-platform/research/test-preparation-costs-2026-09-16.md).

## Owner and acceptance

`seon.test.runner/-main`'s `--prepare-base` branch returns after publication
without shutting down Clojure's agent executors. The process owner should
finish its owned work and terminate those executors before returning.
Verify on a real cold gate: publication completion to child exit no longer
waits for idle executor expiry, and the resulting store, manifest, and
provenance remain usable. No execution bound should change.

## Resolution (2026-09-16)

Owner approved the one-line `shutdown-agents` call after successful publication,
before the publication branch returns. The real cold database gate published
in **91,575 ms** (prior measured **130,698 ms**, including at least 39.81 s of
idle exit waiting), then ran **48 tests / 357 assertions / 0 failures / 0 errors**.
The completed store and manifest were used by all three real worker JVMs.
No bound changed. Exact command, phases and verification limits are in the
landing note; the before/after wall difference is not a controlled CPU benchmark.

## Recurrence in the cold operator (slice 2, 2026-09-22)

The test runner fix remains. The separate cold `bin/seon --root
tmp/one-jvm-redesign-root init` path still returns without terminating its
agent executors. On the slice-2 tree based on `2b7819320`, PID 66427
finished publication but stayed at 0% CPU. `jcmd 66427 Thread.print` showed
`DestroyJavaVM` elapsed **44.20 s** and non-daemon
`clojure-agent-send-off-pool-2` and `-3` parked in
`SynchronousQueue.poll` → `ThreadPoolExecutor.getTask`. The command exited
successfully at **178.826 s**, including idle executor expiry. This is
process shutdown waiting, not an O(program) analysis algorithm.

The operator owns termination of its cold child; it must not shut down
agent executors in the running host. The existing acceptance above applies
to the cold operator too. Live requests already use the advertised prepl.
This recurrence is outside slice 2's analysis-cache deletion.
