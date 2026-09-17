---
type: issue
status: open
severity: friction
tags: [issue, test, operator, wave/schema-audit]
---

# Successful isolated test results can fail to persist in the shared operator

Observed 2026-09-15 in the schema-audit path-limited gate: 152 tests,
1,373 assertions, zero failures/errors, exit 0. The runner then printed:

```
! operator event silence backstop fired: prepl response was silent for 30000 ms; config=:seon.config.operator/event-silence-backstop-ms
bin/test: persistent results NOT recorded: :seon.fresh-operator/prepl-response-silent The prepl response went silent for 30000 ms.
```

Development adoption was running concurrently. This is timing evidence,
not a proven cause. The isolated successful root `run.QYAZCA` was removed
by the runner. The gate's assertions passed, but its shared durable result
facts were explicitly unavailable. The test-provenance lane owns the
currently edited runner and source publication paths; schema-audit did not
modify them.

## Acceptance

A successful gate either confirms its durable result facts or retains
replayable result evidence with a typed persistence failure. Verify this
while development adoption is active; elapsed silence is not confirmation
that a write succeeded or failed.

## Batch 122 B review — 2026-09-17

`tmp/orchestrator/gate-results/batch-122.log` repeats the 30,000 ms
`:seon.fresh-operator/prepl-response-silent` refusal, phase `:prepl-response`.
`eca2d87a7` changes only the source callback's Malli schema plus documentation;
it changes no transport or reply function. The actual recording transport,
`seon.fresh-operator/live-root-value!`, returned a read-only map from PID
94566 in a 119 ms BB invocation during this review. MCP independently
returned the same PID with a 1 ms evaluation. These probes establish current
reply delivery, not the duration or cause of the historical recording wait.

The recording owner is `seon.test.runner/persistent-results-form` →
`commit-staged-completion!` → `seon.cluster.source/record-results!` →
`record-results-at-head!`. It requires the runner, derives projections and
commits a complete result population without emitting prepl progress. The
source recorder can retry stale-head conflicts under the declared test
allowance; the transport independently refuses a 30 s silence. The exact
phase that consumed batch 122's window remains unmeasured. No speculative
transport change or bound widening was made. The runner remains the stage-2
lane's live boundary and was not edited.
