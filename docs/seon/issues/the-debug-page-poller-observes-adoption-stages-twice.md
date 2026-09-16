---
type: issue
status: open
severity: friction
created: 2026-09-17
tags: [adoption, boot-test, debug-page, cohosted]
---

# The debug-page poller observes adoption stages twice

## Problem

`seon.cluster.boot-test/development-adoption-targets-one-of-two-cohosted-clusters`
(boot_test.clj:~1073, "the real debug page remains served during every
adoption stage") expects one page response per adoption stage,
`[200 200 200 200]`, and observes eight — first visible on 2026-09-17 in
batch 79 once the test could run to completion under its declared long
allowance (`8c2f62701`, `29b6e1707`; retained root `tmp/test-runs/run.XCm4Xm`,
block in `tmp/orchestrator/gate-results/batch-79/named.log`). Either
development adoption now runs its stage sequence twice (the documented
single retry at `src/seon/cluster.clj:~2042` re-running every stage on a
source change, or the two cohosted clusters each announcing the shared
stages), or the expectation was written against a shorter sequence. Which
one is the finding: the poller should count the stages the adoption
DECLARES (derive the expected count from the stage sequence the operator
reports), not a literal four.

## Fix shape

Derive the expected count from the adoption's own stage announcements for
the cluster under test; if the stages genuinely run twice for one cluster,
that is the defect (each stage pays reload/acquisition/instrumentation).

Related: `a-committed-storable-declaration-is-dropped-from-the-clusters-live-projection`
(the same test's other red).
