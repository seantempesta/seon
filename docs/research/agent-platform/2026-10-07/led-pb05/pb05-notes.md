---
title: led-pb05 notes
type: evidence
status: active
created: 2026-10-08
---

# led-pb05: a declaration write advances the projection by its written rows

Ledger row PB-05, task `seon.schema/a-declaration-write-reloads-the-whole-projection`. `seon.schema/load-projection`
with a complete base already recompiled only the changed declarations (`projection-with-declarations`), but before
that it joined, parsed and re-assembled every declaration row: `projection-entities` (all schema and contract rows,
group-by), `projection-admissions` (every identity), and per-row parsed maps for the whole population.

## Change (EQUIVALENT)

`advanced-projection` walks the three AEVT ranges (value, identity, admission source) once, merge-joined by entity
id, and keeps only the rows whose text or admission source differs from the base's
`:seon.schema.projection/definition-strings` and admissions, plus the set of identities the ranges hold (removals
are base identities that set lacks). The differing rows go through the same `parsed-rows` and the population goes
through the same `projection-of-population` tail as `projection-from-entities`, so both paths parse, refuse and build
identically. Preconditions for the advanced path: a native `datahike.db.DB` value (sorted AEVT, current datoms) and a
complete base carrying definition strings. Any other value (as-of, history, since), a partial base, or a duplicated
identity answers nil and the whole load runs, refusing as before.

Which step of the one path this serves: record (the writer's projection of the pass's declaration write). What it
lets go: the per-write whole-population join; no new cache, state or path beside the cold whole load, which still
exists for the first build.

Datahike pin `d9121bbbad`: `datahike/datom.cljc:16` declares `Datom` with `^long e`, so the merge reads `.-e`
unboxed; `(:e datom)` boxed every comparison (measured below).

## Measurements

Same isolated probe on parent (HEAD 542749d0e) and change: `measure.clj` in this directory (copy to
`test/seon/pb05_measure_test.clj`, run through `bin/seon check`; an intentional failure returns the data). Three
SCI MCP sequences per side through `seon.mcp/call`; the definition is a contracted `defn`.

Owner, `load-projection` of one added contract over 3,403 schemas / 2,950 contracts, median of five:

| | ms | MB |
|---|---:|---:|
| parent | 13.79 | 23.94 |
| change (first cut, boxed ids, merged admissions per call) | 10.02 | 12.21 |
| change (final) | 8.39 | 7.25 |

Final pieces: schema range walk 2.16 MB, contract range walk 2.48 MB (each re-walks the 1.35 MB admission-source
range), the replacement build itself 0.37 MB (measured on default, unchanged code).

Pass rows (wall ms / allocated MB / armed calls), all complete, no error:

| pass | parent | change |
|---|---|---|
| first defn | 275.4 / 375.9 / 61,385 | 261.2 / 315.9 / 61,572 |
| defn 2 | 138.2 / 116.8 / 30,849 | 142.2 / 98.3 / 30,938 |
| defn 3 | 143.6 / 118.7 / 27,145 | 129.7 / 102.1 / 27,974 |
| after-defn (median) | 53.5 / 65.2 | 58.3 / 62.8 |
| ordinary (median) | 30.8 / 25.0 | 33.6 / 24.6 |

## Budget: NOT met

Row budget O: defn pass interim <= 60 MB, end < 30 MB. Warm defn is ~98-102 MB, first defn ~316 MB. The projection
load is now ~7 MB of that; the rest belongs to other owners this row does not name (RP-0 measures them). The first
defn's extra ~210 MB over a warm defn is not the projection. Ordinary and after-defn moved within noise
(after-defn 3 ms slower in one median, ordinary 2.8 ms; single-sample JVM variation, calls unchanged).

## Tests

Proof `seon.schema-test/a-declaration-write-reprojects-only-its-declarations`: a write adds one contract, removes one
contract and adds one schema; each range yields exactly the written row, and the advanced projection equals the whole
load. Whole `seon.schema-test` on the change: 53 executed, 3 red vars, all red on HEAD (run 77c2ae45c1c3):
`a-refused-registration-is-an-error-value-never-a-throw` (error), `canonical-reference-values-use-the-pull-collection-grammar`,
`every-predicate-schema-declares-what-it-accepts` (identical finding list on both). HEAD also fails
`an-incremental-projection-equals-its-full-build` on its 5 s bound (9,377 ms, run 77c2ae45c1c3; existing task
`seon.test/reds-on-head-178aa24e0-in-db-program-schema-tests`). On the change it passed in a three-test run and
failed the same bound (9,414 ms) in the commit gate, so it is excluded from the commit's named tests; its equality
assertions passed in both change runs.

## Design check

Held against the code: the parse and build tail are shared, so the two loaders cannot drift. Awkward: the advanced
walk re-reads the admission-source range for each declaration range (1.35 MB twice); one three-range walk would save
it but complicates the merge. `deleted-predicate-functions` (1.7 MB) still walks the base's predicate bindings.
Default needs root's normal batched restart to load this; no reset.
