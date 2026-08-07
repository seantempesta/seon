---
type: issue
status: resolved
severity: blocker
tags: [issue, test, database, schema, performance]
---

# Bind test database writers to their schema projections

## Problem

`seon.test-support/with-database` bound each test body to its schema projection
only after creating the Datahike connection. The connection's serial writer
therefore captured no projection binding and fell back to reparsing packaged
schema resources during transactions that decode nested values.

## Evidence

After the curation fixture advanced the bare gate, retained run
`tmp/test-runs/run.XH3zOk` reached the 300-second backstop in
`seon.cluster.loop-test/attempt-settlement-updates-the-registered-model-gauges`.
The virtual-thread-aware dump at
`tmp/test-runs/run.XH3zOk/tmp/loop-attempt-live-threads.json` showed `main`
waiting for `config/apply!` while Datahike's `async-mixed-19` writer was
RUNNABLE in `seon.schema.edn/read-schema-resource`, reached from
`seon.db/decode-pull-entity` inside `seon.reconcile/plan`.

## Owner

The canonical database fixture in `test/seon/test_support.clj`.

## Acceptance

Fresh-store and branched fixtures derive one projection from a provisional
connection, reconnect while its projection state is bound, and keep the same
binding around the test body. The model-gauge regression and bare `bin/test`
complete without packaged-schema fallback or a liveness kill.

## Resolution

Commit `3f276226f` applies the production provisional-connection → derive
projection → reconnect-under-binding sequence to both fixture paths. The
previously stuck model-gauge test then completed twice in about 20 seconds per
fresh JVM load with no failures.
