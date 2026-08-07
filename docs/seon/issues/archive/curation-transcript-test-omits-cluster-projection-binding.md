---
type: issue
status: resolved
severity: blocker
tags: [issue, test, render, schema, performance]
---

# Bind the curation transcript proof to its cluster projection

## Problem

The bare test gate reached the 300-second liveness backstop in
`seon.cluster.curate-test/proof-acceptance-and-atomic-adopt-curate-one-messy-span`.
The test called the transcript producer directly without binding the started
cluster's acquired schema projection, so pull decoding fell back to re-reading
every packaged schema resource for each pulled attribute.

## Evidence

The bare run retained at `tmp/test-runs/run.JZ60UM` exited 124 after entering
the curation property. Its virtual-thread-aware dump at
`tmp/test-runs/run.JZ60UM/tmp/test-liveness/31990-1786065592647-threads.json`
showed `main` RUNNABLE in `seon.schema.edn/read-schema-resource`, reached through
`seon.schema/registered-schemas` →
`seon.schema.datahike/edn-encoded-attr?` → `seon.db/decode-pull-entity` →
`seon.render.transcript/render-ai` →
`seon.cluster.curate-test/transcript-ai`. Compute workers were parked, so this
was neither executor starvation nor a deadlock.

`test/seon/cluster/curate_test.clj` obtained the cluster `ctx` but invoked the
renderer outside the projection bindings owned by `src/seon/schema.clj`.

## Owner

The curation integration fixture in `test/seon/cluster/curate_test.clj`, using
the projection state carried by its started cluster's SCI context.

## Acceptance

The curation test renders its transcript through the started cluster's
acquired projection, completes below the existing liveness backstop, and bare
`bin/test` runs to completion with zero failures and zero errors.

## Resolution

Commit `d1781801d` binds both the cluster projection state and its immutable
acquired projection around the synchronous transcript call, matching the
executor/acquisition bindings used by live graphs. The fixture now invokes the
schema-declared session producer so its budget is database-derived rather than
a synthetic million-token request. Focused verification completed 1 test with
15 assertions, 0 failures, and 0 errors under the unchanged backstop.
