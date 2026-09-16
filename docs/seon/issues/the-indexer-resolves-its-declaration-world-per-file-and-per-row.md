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

## Not landed, and why

The fix's owning files (`src/seon/fn.clj`, `src/seon/program.cljc`) were taken
by a concurrent lane rewriting program-row FILE IDENTITY, which re-signatures
the same functions. Sequence this after that lane commits.
