---
type: evidence
status: 8 commits landed (55ddec16c..431821bfa); #24a/#24d/R-PRED regressions unarmed until next publication
---
# Lane writer-cost — 2026-09-23

Schedule #24a (report validation per purged entity) and #24d (4-row declaration
transaction), plus the coordinator's in-lane additions: the projection-memo P0
regression, its memory risk, M3 (fault write), read-currency order, the index-page
plan, the R-PRED site and the anonymous-fn contract P0. Owned paths:
`src/seon/db.clj`, `test/seon/db_test.clj`, `test/seon/owned_value_test.clj`;
granted `test/seon/schema/projection_writer_test.clj`.

## Commits

| commit | subject |
|---|---|
| `55ddec16c` | projection memo keyed by what it reads (revisions, commit, value, sorted declaration content) |
| `d32a6b2d7` | value tier weak in its own 4-entry LRU; projection_writer_test retired single-key assumptions converted |
| `fa39b67b3` | value-key contract names `seon.db/projection-value-key?` (anonymous fn refused every fresh publication) |
| `51af11aec` | read currency: equal revisions answer before any history scan; replay throw keeps its cause |
| `bfcce39ad` | index-page depends on its prefix attribute, not `:all` |
| `da151b516` | agent-provenance? uses Datahike's entid error code (R-PRED, `datahike/db/utils.cljc:109-139`) |
| `f4dd51d68` | #24a: owners/retention/render-target validation proportional to the report; owned_value_test fixture declares rows |
| `431821bfa` | #24d: arity gate reads only callers/callees the report touched; base memoized by its read datoms |

## Method and proof boundary

- Most before/after comparisons ran in default (pid 43581, then 70720, then 9104),
  on a probe branch `:writer-cost-probe` that I created and later retired. I loaded the HEAD file
  and the working file as namespace copies (`tmp/writer-cost/copy_ns.clj`) and
  called both on the same `d/with` reports. Default's own branch was never
  written, and nothing was adopted or reset.
- Harness caveat, found late: the write-report validator is cached on the
  projection under `:seon.db/write-report-validator`, so a copy's `transact!` could
  run another copy's validator. After the loader gave each copy its own key, I re-took
  every whole-`transact!` number and kept only those. The validation-phase numbers
  call `write-report-error` directly and are unaffected.
- Armed runs on default (pid 9104, booted from `bfcce39ad`, so they cover commits
  up to `bfcce39ad`):
  - `seon.schema.projection-writer-test` + `seon.test.fixture-timing-test`: run
    `5825774e5009`, 41 pass. The 1 error is `ordered-declarations-...`, which also
    errors at HEAD; see residue.
  - db_test: `equal-declaration-content-...` `58e7360b8106`,
    `an-in-transaction-...` `707dd96531cb`, `equal-revisions-...`
    `41e199c43211`, `an-index-page-...` `7beca2459421`. All green.
  - `a-write-user-...`, the #24a and #24d regressions and the owned_value_test
    fixture change were not yet published, so seon.test/run refused them as
    "not eligible". They ran as scratch copies instead:
    - owned_value_test with the fixture fix: 8 tests, 174 assertions, green on HEAD and on the new code.
    - `new-entities-...`: HEAD 200 reverse reads, new 0.
    - render and arity regressions: green on the new code, red on HEAD.
- Contracts: every seon.db source-form contract compiled against the packaged
  projection before each commit (`tmp/writer-cost/contracts.clj`, lane probe JVM):
  97-102 contracts, 190-350 ms. d32a6b2d7 reproduces the P0 refusal.
- HEAD load: each committed db.clj was `load-file`d as `seon.db` in a fresh
  lane JVM (`clojure -M:dev:test`). No `require :reload` in default.

## Timings (default, probe branch, ~470k datoms)

The P0 regression: `apply-compiled!` for a new cluster went from 36,155 / 34,474 ms to 610 / 481 ms.

| operation | HEAD before | after | raw Datahike |
|---|---|---|---|
| new branch at same commit, `carried-projection` | 443 ms, not identical | 5.3-5.5 ms, identical | - |
| fixture_timing p50 / first | 508 / 2,269 ms (red) | 56 / 57 ms (green) | - |
| M3 fault via `commit-fault!` (valid flow fault) | 1,857-2,127 ms | 86-195 ms | 39-41 ms |
| read currency, 6 reads after unrelated write | 16.4-20.6 ms | 0.015-0.019 ms, identical answers | - |
| validation: 4 rows | 8.6-11.3 ms | 3.9-4.7 ms | 0.6-1.6 ms |
| validation: 100 rows | 131-189 ms | 8.3-31 ms | 5.4-8.8 ms |
| validation: 1,000 rows | 1,407-2,154 ms | 55-63 ms | 49-68 ms |
| validation: 1,000-entity purge | 839-905 ms | 98-108 ms | 20-37 ms |
| `transact!` 4 rows | 385-407 ms | 44-58 ms | 46-62 ms |
| `transact!` 1,000-entity purge | 4,716-5,133 ms | 645-767 ms | 214-224 ms |
| arity gate: declaration write | 132-222 ms | 0.05-0.17 ms warm | - |
| arity gate: removed call / empty | 126-134 / 119-129 ms | 0.4-1.8 / 0.06-0.14 ms | - |

A 1,000-entity purge `transact!` breaks down as ~70 ms validation, of which owners
seeks are 40 ms (existing entities, ~40 component attributes each). The rest is
Datahike's own work.

## Residue (not closed here)

- **Datahike conservative revision on tx-fn commits.** `datahike/writer.cljc:249`
  derives the modified attributes from the input tx-data. A `:db.fn/call` is
  therefore "unknown" and advances the conservative revision, which invalidates
  every revision-keyed memo and Datahike's own query cache. The better fix is in
  the fork: derive modified attributes from the tx-report's datoms. That owner is
  `reference-code/datahike`, not this lane.
- **Content-key cost.** The arity key hashes 77k datoms (~9 ms) per gated
  transaction, and the projection content key costs ~5-11 ms per new speculative
  value. Both are proportional to the program, not to the transaction. The fork
  fix above would let revisions replace both.
- **New declarations rebuild the whole projection.** A 4-row declaration
  `transact!` is 170-300 ms because the next projection is a full
  `load-projection`. Incremental projection derivation belongs to seon.schema.
- **Refusal path.** A refusing arity report still spends 480-630 ms in the
  call-preparation snapshot. The owner is seon.call-preparation.
- **Wildcard pulls keep `:all`,** per the invalidation review §4.
- **`ordered-declarations-use-their-database-and-abort-together` errors at HEAD.**
  `seon.schema/projection-with-schema` (`schema.clj:2853`) raises raw Malli
  invalid-schema for `::absent`, and db.clj:3949 rethrows it under the panic
  policy. This is wanted behavior of a surviving seam, and the fix belongs to
  schema.clj's owner.
- **An external `seon.turn/row-tx` redefinition is live in default** (frames show
  `redef-run.clj:6`). Its owner is another lane.
- **Unarmed on default:** the #24a/#24d/R-PRED regressions will first run armed
  after the next publication.

RESET NEEDED: no.
