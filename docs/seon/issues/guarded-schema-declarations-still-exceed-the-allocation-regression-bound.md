---
type: issue
status: open
severity: friction
tags: [issue, sci, performance, wave/schema-projection-performance]
---

# Guarded schema declarations still exceed their allocation regression bound

## Evidence — 2026-09-15

The original full-projection rebuild was removed by `fba6bc4c1`.
On default through MCP JVM mode, a private acquired SCI context with explicit
read custody successfully evaluated the original score and label registration
forms. The guarded kernel records measured 108,685,472 bytes / 45 ms and
107,849,320 bytes / 44 ms. Neither schema was transacted into default; a
follow-up query found no schema rows. Pure candidate construction measured
819,984 and 806,288 bytes respectively.

These successful complete evaluations exceed the 64 MiB assertion in
`test/seon/sci/eval_test.clj:740`,
`schema-and-contract-declarations-have-bounded-allocation`. That test was not
rerun as part of the P1 database gate. This is a live cost observation, not an
attribution of the remaining allocation to a particular evaluator stage.

Exact forms, historical comparison, and boundaries are in the
[P1 landing note](../../prds/context-generation/research/p1-ambient-state-2026-09-15.md).

## Owner and acceptance

Decompose the existing `seon.sci.eval/evaluate` path with its carried
projection and ThreadMXBean, then remove the expensive stage at its owner.
Keep the existing real-SCI allocation regression and its wanted behavior;
do not raise the bound to hide this observation.

## S3 query-seam observation — 2026-09-16

The HEAD-plus-owned-paths fast run for the S3 override query at `e481946f6`
re-observed the same assertion: **221,730,944 bytes**, limit **67,108,864**.
The run snapshot contained only `src/seon/program.cljc` and
`test/seon/sci/eval_test.clj`; its namespace sequence was `seon.sci.eval-test`,
`seon.cluster.agent-test`, `seon.cluster.turn-test`, and
`seon.sci.documentation-test`. This is another measurement, not a new
attribution. The allocation assertion was left unchanged.
See [the S3 note](../../prds/steward-platform/research/acquisition-by-provenance-s3-2026-09-16.md)
for the complete verification boundary.

## Still open, and worse — 2026-09-17

Measured on `bin/test-fast` (`seon.sci.eval-test`, HEAD plus
`src/seon/sci/eval.clj` and `test/seon/sci/eval_test.clj`):
`schema-and-contract-declarations-have-bounded-allocation` recorded
**223,519,720 bytes** and, on a second run, **223,408,128 bytes** against the
same 67,108,864 (64 MiB) assertion — roughly double the 2026-09-15 figures
above. Observed in passing by the lane fixing
[the evaluation contract fault](the-over-bound-evaluation-path-returns-a-lookup-ref-where-its-contract-promises-a-string.md);
not that slice's, and unchanged by it.

