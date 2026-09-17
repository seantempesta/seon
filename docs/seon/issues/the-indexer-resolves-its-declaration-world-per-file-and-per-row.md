---
type: issue
status: open
severity: friction
created: 2026-09-17
tags: [issue, program-graph, schema, publication, performance, class/p1]
---

# The indexer resolves its declaration world per file and its shapes per row

## Problem

Two call-time fetches of a value the operation already holds (AGENTS.md §2.1),
measured on `default` (pid 30138, 2026-09-17, instrumented dev JVM):

- `seon.schema.edn/packaged-forms` costs **18.13 ms** and is resolved **twice
  per file** during indexing — `seon.fn/declaration-forms`
  (`src/seon/fn.clj:1044`) when the request supplies no
  `:seon.schema.projection/forms`, and `analysis-rows-by-file`'s
  `(set (keys (schema.edn/packaged-forms)))` (`src/seon/fn.clj:989`). Counted
  over 20 files with `with-redefs`: **40 calls**. At the 337 files a
  relocated-checkout publication analyses that is ~12.2 s of re-reading a
  population that cannot change during the operation.
- `seon.program/shapes-in` is derived **once per row** — 122 calls for 122
  rows in the same probe. The memo `9cc181289` introduced for exactly this
  (`seon.program/shapes`'s one-argument arity, 0.0013 ms against 0.0224 ms for
  the derivation) is bypassed by every per-row caller: `program/shape`
  (`src/seon/program.cljc:250`), `canonical-row` (`:893`) and
  `changed-attributes` (`:1018`) call `shapes-in` directly.

The regression against the pre-`9cc181289` `defonce` is real — that cache was
correctly deleted (it stripped attributes declared after JVM start), but its
replacement moved the cost onto the per-call and per-row paths instead of onto
the caller's value.

## Fix shape

Thread the derived value; delete the memo rather than route more callers
through it. Declare `:seon.program/shapes`
(`[:map-of :seon.program/identity-attribute :seon.program/shape]`) so a
population handed where shapes are expected is a typed refusal rather than a
row silently stripped of every owned attribute; make the explicit arities of
`shape` / `canonical-row` / `changed-attributes` / `exact-replacement-tx-in`
take it (their private `-in` companions already do); resolve the population
and derive its shapes ONCE per operation in `seon.fn`; and have
`seon.cluster/incremental-source-refresh!` (`src/seon/cluster.clj:1986`) supply
`:seon.schema.projection/forms` to the per-file `build-artifact` calls instead
of letting each one re-resolve.

Acceptance is a COUNT, never wall time: N `build-artifact` calls resolve the
declaration population once each and derive the shapes once each, whatever the
row count.

## Evidence

[shapes-per-row-callers-2026-09-17](../../prds/steward-platform/research/shapes-per-row-callers-2026-09-17.md)
— full table, the 20-file seam counts, and the named boundary. Related:
`program-shapes-cache-strips-attributes-declared-after-the-jvm-started`
(resolved; this is the cost its fix moved), and
`complete-publication-takes-seventy-seconds`.

## Resolved 2026-09-16

Landed as designed after the file-identity rewrite (`28f1a761e`) committed.
`:seon.program/shapes` is declared; `shape` / `canonical-row` /
`changed-attributes` / `exact-replacement-tx-in` take that value in their
explicit arities; `!supplied-shapes`, `supplied-shapes` and `shapes`'s
one-argument arity are deleted; `seon.fn` resolves the population once per
operation and carries both halves (the declared-attribute key set and the
derived shapes) through `artifact`, `build-manifest`, `normalized-index-row`,
`reconcile-tx-in` and `index!`.

Re-measured on `default` pid 53320 before and after, counts not wall time
(20 smallest `src/seon/**.clj[c]` files, one `build-artifact` each, 90 rows):

| | `packaged-forms` calls | `shapes-in` calls |
|---|---|---|
| before | 40 (2 × files) | 90 (1 × rows) |
| after | 20 (1 × files) | 20 (1 × files) |
| after, population supplied | **0** | 20 |

`seon.fn-test/indexing-resolves-its-declaration-world-once-per-operation` is
the class regression: it asserts the exact count at three seams, and counts
only the calling thread's calls because `with-redefs` replaces a Var root for
the whole JVM.

The typed refusal caught a real caller within minutes of landing: a stale
`seon.fn` in the development JVM handed `canonical-row` a declaration
population, which without `:seon.program/shapes` would have published rows
stripped of every attribute their family owns and reported success.

One part of the assignment is NOT done and stays open elsewhere:
`seon.program/identity-attributes` is still a literal vector rather than a
derivation over the attributes declaring `:seon.program/row-schema` — see
`live-resources-outrun-the-loaded-program-identity-list`. It did not fall out
of this change because the list also carries a deterministic ADMISSION ORDER
that a population's key set cannot supply, and because `row-identity` /
`row-identities` read it per row with no population in hand.

## Reopened: publication evidence resolves forms per reference

The original indexer callers remain fixed. The same class recurs at
`src/seon/cluster/source.clj:385`: `identity-ref` reads `packaged-forms` for
every absent reference while `preserved-evidence-tx` (`:483`) maps retained
`:seon.test/reach`. A complete publication on default PID 53320 was observed
inside this path by an all-thread JVM dump on 2026-09-16 (UTC), with subsequent
publications waiting for `source-refresh-monitor`. The callback has not yet
reached final-report validation. Carry the projection/derived mintable
identities once with the evidence operation; do not fetch declarations per
reference. This owner is outside the urgent two-defect write-admission repair.
Evidence: [write-admission landing note](../../prds/steward-platform/research/write-admission-2026-09-17.md).

## Adoption-margin repair — 2026-09-17

The publication evidence path now carries packaged forms once and resolves
each distinct evidence identity once at the writer's current database value.
Default had 1146092 reach references but only 3793 distinct reached functions.
The read and transaction phases measured 4735 ms and 25570 ms.

The same repeated-work pattern appeared in development reconciliation: the
reference pull memoization lived inside one row normalization. It now lives
with each immutable operation database, separately for published-row reads,
the writer transaction and each side of the comparison. A successful default
adoption measured reconciliation transaction 5373 ms and comparison 4982 ms.
See [the measured landing note](../../prds/steward-platform/research/adoption-margin-2026-09-17.md).
