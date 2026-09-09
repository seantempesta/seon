---
type: issue
status: resolved
severity: blocker
tags: [issue, custody, cluster, db, class/absence-as-health]
---

# A write from one cluster into another cluster's branch is not refused

**RESOLVED 2026-09-08 — the premise was false.** The write *was* already
refused at HEAD. `seon.custody-stability-test` was red for an unrelated
reason: the pooled test worker died inside `seon.test.runner/arm-contracts!`
(runner.clj:897) during a mid-run re-arm, taking the namespace down before any
custody assertion ran. The same run's confirmation phase printed
`parallel-only` for `cross-cluster-write-isolation` — red in the pool, green in
isolation.

Evidence, all three in
[the landing note](../../prds/context-generation/research/custody-isolation-landing-2026-09-08.md):

- live, two clusters (`cluster-alpha`, `cluster-beta`) on one store in one
  JVM: the foreign write returns `:seon.db/foreign-connection` and beta's
  datom count is unchanged, while the explicit-own and elided arities commit;
- the same through alpha's live SCI evaluation path;
- `bin/test seon.custody-stability-test` — 5 tests / 26 assertions / 0 / 0;
  `bin/test --platform` — 73 / 398 / 0.

## What changed

`seon.db/transact!` is the only write in `seon.db`, and its foreign-branch
decision is taken at that seam from the two connections the call holds — never
a pre-read. The refusal now **names both branches**
(`:seon.db/ambient-branch`, `:seon.db/explicit-branch`, and a message saying
which branch the write tried to reach and what to do instead) in addition to
the two connection ids it already carried.

The class regression is
`seon.db-test/a-write-naming-another-clusters-branch-is-refused-naming-both`.

## What is still open, elsewhere

The runner's re-arm fault is a real defect and it is **not** custody:
`reassert-contracts!` re-derives `(config/result-caps (config/defaults))`
through its own armed `:panic` contract, so the repair path can kill the
worker it was repairing. The recommended hunk is in the landing note §4.
