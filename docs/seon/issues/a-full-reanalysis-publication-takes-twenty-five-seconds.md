---
type: issue
status: open
severity: friction
created: 2026-09-24
tags: [issue, publication, wave/publication-velocity]
---

# A full-reanalysis publication takes about 25 seconds

## Problem

A publication classified `:all` reanalyzes every source file. It now happens
once after every change to the analyzer's reach (`seon.fn/analyze-rows` and
its callees) and on every `.clj-kondo` change. On default, one run took
24,876 ms in total.

The armed profile, by inclusive time:

- `seon.cluster/full-source-refresh!`: 23,905 ms.
- `seon.fn/analyze-rows`: 13,175 ms.
- `seon.fn/published-index-rows`: 21,987 ms across 12 calls.
- `seon.fn/file-rows`: 14,227 ms across 5 calls.
- `seon.db/pull`: 10,952 ms across 143,224 calls.
- `seon.db/with-declarations`: 24,587 ms across 866,175 calls.

The cost is proportional to the whole program, not to the change. That is
over the 10-second law.

## Evidence

The measurement was taken during lane fix-analysis-key-omits-analyzer
(2026-09-24). It ran `seon.cluster/refresh-source!` on default with
`["src/seon/id.clj"]` and the development cluster `default`, after the seal
first lacked `:seon.source/analyzer-digest`. The run produced commit
`6ab575a7-bc1b-57bd-924f-0732e7a8bfa0` and reloaded five namespaces whose
rows changed.

## Owner

Publication (`seon.cluster`) and the `seon.fn` indexer. Two leads:

- The 143,224 pulls and 866,175 `with-declarations` calls read per row.
  Batching those reads is the first lead to verify.
- Split analysis from the prior-row reads.
