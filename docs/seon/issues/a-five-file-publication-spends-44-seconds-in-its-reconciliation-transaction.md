---
type: issue
status: open
severity: high
created: 2026-09-23
tags: [issue, publication, performance, seconds-not-minutes]
---

# A five-file publication spends 44 seconds in its reconciliation transaction

Seen 2026-09-23 by lane publication-work, on a private operator root
(`tmp/publication-work/snap/tmp/leaf-root`, a `git archive` of `72fa85fc3` with
this lane's `src/seon/cluster.clj`, `src/seon/cluster/source.clj` and test file
copied in). Measured by
`docs/prds/agent-platform/landing/lane-publication-work-leaf-2026-09-23.clj`,
run 3 (`tmp/publication-work/leaf3.log`). Machine load average was about 11.

The first publication after the JVM started named no paths. It found 5 changed
inputs ("branch publication started: 5 inputs"). Inferred from the files changed
since that root's last publication, not listed by the run: `deps.edn`,
`src/seon/eval.clj`, `src/seon/cluster.clj`, `src/seon/cluster/source.clj` and
`test/seon/cluster/publication_inputs_test.clj`. It took **56,893 ms**:

| phase (progress marks, ms from request) | ms |
|---|---|
| source build, up to "findings in analyzed files: 35" | 8,542 |
| program rows, up to "development reconciliation transaction" | 694 |
| reconciliation transaction, up to "analysis callers: 55 files" | **43,845** |
| caller analysis, up to "program rows complete" | 3,575 |
| seal and branch head | 118 |

Inclusive wrapper totals: `seon.fn/index!` 48,003 ms; `seon.db/transact!` 2 calls,
43,629 ms; `seon.db/carried-projection` **5,634 calls, 41,046 ms**;
`seon.fn/analyzed-artifacts` 6 calls, 12,332 ms. A one-file leaf publication on the
same root makes 173–178 `carried-projection` calls (1.3–1.6 s total).

Assumption to verify: the reconciliation transaction derives the projection once
per checked row or entity instead of once per database value (wave-1.4
projection sweep, `lane-projection-as-a-read-sweep`). The reach grows with the
changed files' callers: `cluster.clj` has many.

Wanted: a publication's reconciliation cost scales with the rows it changes. The
projection is derived once per database value (README §7 memoized projection).
