---
type: issue
status: open
severity: friction
created: 2026-09-16
tags: [turn, test, class/p1]
---

# `ordered-evaluation-retains-one-explicit-basis-without-publication` refuses `agent-already-running`

## Observation

In-process on live `default` (PID 95853, branch `steward-platform`),
`seon.cluster.evaluate-sources-test/ordered-evaluation-retains-one-explicit-basis-without-publication`
runs **24 pass / 9 fail / 0 error**. Every one of the nine failures is
downstream of the first:

```
(nil? (:seon.error/kind committed))
  actual: {:seon.error/kind :seon.turn/refused,
           :seon.error/message "run transition refused: agent-already-running",
           :seon.turn/refused :seon.turn/agent-already-running}
```

The test opens run `active-during-add` for `preview-batch-agent`, closes it in
`_close`, then commits the `saved-preview` transaction data — which the writer
refuses because the agent still has an open run. `turn/open-for-agent` then
answers `"active-during-add"`, so the close did not take effect before the
commit. The remaining eight failures are the empty `receipts` and absent
`saved-preview` row that follow.

## It is not the render-profile change

Observed on the `request-profile` lane and probed: re-running the same test with
`seon.render/request-profile` forced to ignore any carried profile — the exact
pre-change per-form derivation behaviour — fails identically (24 pass, 9 fail,
same refusal). The run-transition path was not touched by that lane.

## Next step

Reproduce under the orchestrator's gate to establish whether this is a HEAD
failure or an artefact of running this deftest in the shared `default` JVM, then
find why the `active-during-add` close is not visible to the `committed`
transaction. The failing seam is the close/open ordering in the test's final
`let`, or the writer's own view of it.
