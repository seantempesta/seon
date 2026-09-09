---
type: issue
status: open
severity: friction
tags: [issue, test, operator, class/n4, wave/test-fixture]
---

# Bound dependency-cache preparation before the test coordinator starts

## Evidence

2026-09-08, issues-sweep: the paths-only gate `run.72XcCa` waited 178,522 ms
for `target/dev-dependency-cache.lock`, then held it for 2,107 ms. Its preparation
JVM 30530 was in `FileChannel.lock` through `dev_cache/with-cache-lock`
(`dev_cache.clj:411`). No coordinator had started, so the coordinator's
liveness watchdog did not cover this wait. The wait eventually completed;
this is a measured unbounded wait, not an observed permanent deadlock.

The same build owner calls tools.build 0.10.5 `process`, whose implementation
waits for process exit before draining captured stdout/stderr. Its API has no
execution deadline. This is dependency source evidence, not a claim that a
pipe filled during this run.

## Owner and acceptance

The dependency-preparation seam in `bin/test` and `dev_cache.clj` must use the
existing bounded operator subprocess/lock authority, including cleanup after
a bound fires. A held-lock regression must produce a bounded diagnostic naming
the owner and boundary, then prove its own process is reaped. A noisy child
must drain while running. `bin/test` is concurrently owned by runner-paths;
this lane did not edit its launcher or operate another lane's process.
