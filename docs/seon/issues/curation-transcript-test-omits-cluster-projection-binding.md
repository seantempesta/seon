---
type: issue
status: open
severity: blocker
tags: [issue, test, render, schema, performance]
---

# Bind the curation transcript proof to its cluster projection

## Problem

The bare test gate reaches the 300-second liveness backstop in
`seon.cluster.curate-test/proof-acceptance-and-atomic-adopt-curate-one-messy-span`.
The test calls `seon.render.transcript/render-ai` directly without binding the
started cluster's acquired schema projection, so pull decoding falls back to
re-reading every packaged schema resource for each pulled attribute.

## Evidence

The bare run retained at `tmp/test-runs/run.JZ60UM` exits 124 after entering
the curation property. Its virtual-thread-aware dump at
`tmp/test-runs/run.JZ60UM/tmp/test-liveness/31990-1786065592647-threads.json`
shows `main` RUNNABLE in `seon.schema.edn/read-schema-resource`, reached through
`seon.schema/registered-schemas` →
`seon.schema.datahike/edn-encoded-attr?` → `seon.db/decode-pull-entity` →
`seon.render.transcript/render-ai` →
`seon.cluster.curate-test/transcript-ai`. Compute workers are parked, so this
is neither executor starvation nor a deadlock.

`test/seon/cluster/curate_test.clj:52-67` obtains the cluster `ctx` but invokes
the renderer outside `seon.schema/call-with-projection-state`.
`src/seon/schema.clj:615-620` is the surviving dynamic binding owner, and
`src/seon/schema/datahike.clj:331-338` falls back to
`schema/registered-schemas` when that binding is absent.

## Owner

The curation integration fixture in `test/seon/cluster/curate_test.clj`, using
the projection state carried by its started cluster's SCI context.

## Acceptance

The curation test renders its transcript through the started cluster's
acquired projection, completes below the existing liveness backstop, and bare
`bin/test` runs to completion with zero failures and zero errors. A focused
probe demonstrates that packaged schema resources are not re-read per pulled
attribute during the transcript render.
