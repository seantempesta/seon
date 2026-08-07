---
type: issue
status: resolved
severity: blocker
tags: [issue, test, flow, render, schema, performance]
---

# Bind the turn render-context graph to its cluster projection executor

## Problem

The turn-test fixture created its render-context Flow graph without the
cluster projection executor. Its `:io` render proc therefore ran without the
acquired schema projection and repeatedly reread packaged schema resources.

## Evidence

Bare run `tmp/test-runs/run.9OZMxE` reached the 300-second liveness backstop in
`seon.cluster.turn-test/generated-model-attempt-traces-preserve-presence-and-episode-laws`.
The virtual-thread-aware dump at
`tmp/test-runs/run.9OZMxE/tmp/test-liveness/35225-1786070442079-threads.json`
showed `main` waiting in `seon.render/acquire-context!` while the render proc
was RUNNABLE in `seon.schema.edn/read-schema-resource`, reached through render
profile resolution. This was the same missing graph binding class repaired by
commit `61ccb7332` in the agent fixtures.

## Owner

The render-context Flow fixture in `test/seon/cluster/turn_test.clj`.

## Acceptance

The fixture supplies `cluster/projection-executor` as its graph `:io-exec`,
and both the opening-database-value prompt regression and generated attempt
property complete without failures or a liveness kill.

## Resolution

The fixture now derives the graph executor from the projection state carried
by its cluster SCI context. Focused verification ran both affected test vars
to completion in about 94 seconds with no failure or error output under the
unchanged liveness backstop.
