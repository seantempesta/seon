---
type: issue
status: open
severity: blocker
created: 2026-09-17
tags: [issue, test, publication, write-admission]
---

# Publication issue indexing refuses in the runner's real-store fixtures

The test-system-stage2 fast snapshot at HEAD `ec350ece0` plus its selected
paths reached two errors while preparing real published roots:

- `seon.test-runner-test/consecutive-cache-invocations-reuse-the-published-base`
- `seon.test-runner-test/concurrent-bin-test-invocations-both-reach-their-tallies`

Both reported `clojure.lang.ExceptionInfo: Issue indexing was refused.` at
`seon.cluster.source/refuse!`, `source.clj:81`, through `require-committed!`
and `index-issues!` (`source.clj:566`). Immediately preceding writer events
at `2026-09-17T03:30:09.567Z` and `2026-09-17T03:30:57.309630Z` reported
`:transaction/validation-rejected`. The test reporter printed the stack but
not the refused row or exception data, so the offending attribute and causal
change are not established.

Evidence: `tmp/test-system-stage2-final-fast.log`, lines 314–379; snapshot
`tmp/test-runs/run.KjgTxV`. The snapshot launcher reported a concurrent
delete/add of the same issue-document path while preparing the snapshot;
that is an observation, not an attribution of the refusal. The snapshot is
automatically removed when the fast JVM exits.

All three recorder regressions named in
[the recorder issue](the-recorder-creates-a-test-row-the-write-grammar-validator-refuses.md)
completed without failure in this run. The publication errors precede test
execution in the two fixtures above; they are not a failure to record those
test results. The aggregate was 98 tests, 637 assertions, six failures from
two separately corrected fixture assumptions, and these two errors.

Owner: publication issue admission (`seon.cluster.source/index-issues!`,
`seon.issue`, and final transaction validation). No implementation change is
made here. The next diagnostic must retain the writer's refusal data and
identify its exact rejected row; absence of that evidence is not permission
to guess which schema or document caused it.

Acceptance: both fixtures publish their real roots and complete their
declared observations through the canonical harness. See
[the stage-2 note](../../prds/steward-platform/research/test-system-stage2-2026-09-17.md).
