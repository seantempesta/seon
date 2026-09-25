---
type: landing
status: committed; not adopted into default (hook publication paused); tests not run (load rule)
created: 2026-09-25
tags: [agent-platform, fix-src, census-3]
---

# Lane fix-src-singles-db-schema (2026-09-25)

This lane fixes five single-owner items from the third red-baseline census (FIX-SRC §3–§6,
red-baseline-census-2026-09-25b.md, aab55ddd3) and the arity-base miss from
lane-perf-small-publication-2026-09-25.md. Each fix is its own commit. Nothing is installed in default:
root adopts and runs the listed tests.

## Broken or unfinished first

- **None of these commits is loaded in default**, and no test request was run. The db.clj edits passed
  clj-kondo only; the one error it reports, `parser.type/->Variable`, was already there on HEAD and comes from
  the stale-cache class. The arity-seed timings below come from a copy of the edited functions in a
  throwaway namespace (`lane-fsdb.probe`, file `tmp/fix-src-singles-db-schema/probe-arity.clj`). They were
  not measured in `seon.db` itself.
- **Census item 4 is only half in this lane.** `seon.boot.edn:55` already declared its message. The second
  missing predicate is **`resources/seon/schemas/seon.effect.edn:53`** (`:result-validator`,
  `[:fn {:gen/schema …} clojure.core/ifn?]` with no `:error/message`). That file is not owned here. The fix is
  one line, and `schema_test/every-predicate-schema-declares-what-it-accepts` stays red until it lands.
- **Census item 3 needs a caller change outside this lane.** `fn_test.clj:1836`
  (`publication-writes-one-definition-digest-per-declaration-family`) calls
  `canonical-schema-rows` with `db/carried-projection`, and that projection does not declare the test's own
  `:sample.definition-digest/value`. Now the refusal names the key instead of Malli's anonymous
  `{:schema nil}`, but the test still fails until it builds a complete projection, as db_test.clj:1960 does
  (`schema/build-projection (assoc forms key definition)`). This is UPDATE-TEST in fn_test.clj, which is not owned here.
- **Census item 2: the cause is correct, but a reader question remains.** Of all 312 src files, only
  schema.clj failed `source-round-trips?`, and it failed the `:span` clause. The trailing banner, a comment with no form after
  it, was absorbed into the last form's span. Deleting it fixes the file. The reader will still refuse the next
  file that ends in a comment. Whether a trailing comment should get its own span belongs to the
  `seon.sci.reader` owner (not filed; root decides).
- **Side effect of the probe:** the old-code timing probe added three speculative content entries to default's
  `seon.db/arity-base-cache` (LRU of 4). They are content-valid and will be evicted normally.

## Fixes and evidence

| # | commit | fix | probe before → after |
|---|---|---|---|
| 1 | b220ad367 | `read-declarations` names `:seon.error/operation` and drops two undeclared keys that duplicated `:seon.error/data`. The regression reads flat members. | NPE `Named.getNamespace()` at `diagnostic` (db.clj:182) → the diagnostic validates as `:seon.db.read/unreadable-declarations-error` (explain nil). The other 17 `diagnostic` callers in db.clj already pass an operation. |
| 2 | e5826ea62 | Deleted the orphan `;;; Development Helpers` banner at the end of schema.clj. | Full-tree `source-round-trips?` failures: `{"src/seon/schema.clj" [:span]}` → none on the stripped text. The bad span was the last form, 197445–198359. |
| 3 | 83be4608f | `storable-properties-in` refuses a key missing from the projection, using the same `:seon.schema/missing-reference` shape as `projection-validator`. New regression in schema/datahike_test. | `mr/schema` answers nil → Malli `::invalid-schema {:schema nil}`. The rewritten body gives the same result as the old one for all 3,391 declared keys. |
| 4 | a84f879d8 | `seon.operator.edn` `:seon.boot/prepl-server` gets boot.edn's `:error/message` and `:gen/gen`. | Resource walk before: operator + effect missing. After: only `seon.effect.edn` (above). |
| 5 | 863221d3e | `report-arity-comparison` seeds a lazy base for its `:db-after` under the existing content key (`arity-content-key`, extracted). `advanced-arity-base` advances it by the report's removed and affected edges, and `::by-caller`/`::by-callee` now hold sets. New db_test regression. | Two chained `d/with` writes on default, where the second write's `:db-before` is the first write's after. Second write: **105/108/126 ms → 5.7/5.9/7.0 ms**. First write: 0.2–0.7 ms → **2.1–3.2 ms**, because the seed builds and hashes the ~78,900-datom content key; that is below the hot-path threshold. The seeded base equals a full derivation (`=`, no differing keys; the change was one removal and one addition). |

Item 5: why it missed. Before this change, `arity-base` already keyed by content (2f4ea22d9), so a fork of seen content hit.
A publication's scratch branch, however, forks the `current-src` head. That head is the previous publication's `:db-after`,
and no base had ever been derived from it, so the first write always missed. Seeding makes that exact value
a cache input. The cost only follows changed inputs: the seed runs only when a report touched caller or callee symbols,
and the advance is O(touched edges), lazily.
Remaining limit: the cache is an LRU of 4, so heavy interleaved program writes on several branches can still evict the head.

Timings (default, JVM mode, warm):
- `failed-tests` read: 330–433 ms.
- Full-tree reader probe: 1,802 ms (read-only, 312 reads).
- Everything else: under 300 ms.

## Changed paths and sizes

- `src/seon/db.clj` (+45/−14 over two commits);
- `test/seon/db_test.clj` (+34/−5);
- `src/seon/schema.clj` (−4);
- `src/seon/schema/datahike.clj` (+15/−5);
- `test/seon/schema/datahike_test.clj` (+11);
- `resources/seon/schemas/seon.operator.edn` (+5/−1);
- this note.

Net source: about +42 lines. Net tests: about +40 lines.

Released paths: all of the above. `seon.boot.edn` was not edited.

## Root runs (serially, after adopting the commits)

1. `bin/test-check default --test seon.db-test/a-refused-declarations-read-refuses-decoding`
2. `bin/test-check default --test seon.sci.reader-test/source-round-trips-and-spans-partition-the-tree`
3. `bin/test-check default --test seon.schema.datahike-test/a-schema-absent-from-the-projection-refuses-by-name`
4. `bin/test-check default --test seon.fn-test/publication-writes-one-definition-digest-per-declaration-family`
   (expected: the named missing-reference refusal until fn_test builds a complete projection)
5. `bin/test-check default --test seon.schema-test/every-predicate-schema-declares-what-it-accepts`
   (expected: red on `seon.effect.edn:53` only)
6. `bin/test-check default --test seon.db-test/a-write-seeds-its-final-values-arity-base`
7. `bin/test-check default --test seon.db-test/arity-components-and-callers-are-checked-in-the-final-state`
   and `--test seon.fn-test/arity-mismatch-refuses-the-final-write` (arity gate behavior unchanged)
8. Re-run `tmp/perf-small-publication/probe-change.clj` and read `write-report-error` / `arity-base` in the
   explain window. Expect well under the 1,344 ms measured there.
