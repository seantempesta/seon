---
type: issue
status: open
severity: friction
tags: [issue, test, publication]
---

# Platform tier rejects publication fixture observations

Run `75801c3ccaed`, 2026-09-23:
`seon.test.runner-test/the-canonical-platform-tier-preserves-file-local-uncertainty`
refuses at `runner/selected-tiers` because the indexed platform contains
publication tests whose observed fixture operations delete filesystem paths.
The refusal includes `seon.cluster.source-test/flat-scratch-write-refusal-retires-the-candidate`
and `seon.cluster.source-lineage-test/publication-advances-one-branch-and-retires-scratch`.
The complete list and exception are in `tmp/test-system-indexed-final.log`.

The same refusal predates the scoped reach change. The publication fixtures
are assigned to the redesign lane and were not edited. Acceptance: reconcile
the indexed platform eligibility with the actual isolated fixture operations,
then run this platform-selection regression. A blanket long allowance is not
an explanation of those operations.
