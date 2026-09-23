---
type: landing
status: landed
lane: nsa-slice3
plan: docs/prds/agent-platform/plan/lane-namespace-agents-first-loop.md (slice 3, "Guard existing merge writer")
created: 2026-09-23
---

# Slice 3: the merge writer shares the transaction guard

## The seams (fork parent `684d3290`)

| Seam | What it already does | What was missing |
|---|---|---|
| `writing.cljc:877` `transact!` | checks `:datahike/expected-basis-t` against the writer's `:max-tx` in the same writer operation | merge did not call it |
| `writing.cljc:864` `merge-writer!` | put parents on `:meta :datahike/merge-parents` after `core/with` | skipped the basis fence. It already ran `:datahike/validate-report` (`db/transaction.cljc:1224`) because `core/with` runs it |
| `writer.cljc:233-242` commit loop | turns parents into a multi-parent commit. `writing.cljc:477-478` resolves branch keywords to immutable commit ids | read parents from the batch's last report only |
| `writer.cljc:118-190` processing loop | threads `(:db-after res)` into the next operation | carried the merge's parents into it |
| `versioning.cljc:738` `merge!`, `writer.cljc:426` `merge-db!` | `merge-db!` passes its whole arg map to the writer | `merge!` built the arg map itself and dropped the basis key |

Seon's write path is `src/seon/db.clj` `transact-call`. It runs the pre-write check, the report
validator in `tx-meta`, the codec and the bound. It now picks `d/merge-db!` when the prepared
request has `:parents`, and `d/transact!` otherwise. Nothing is copied.

## Change

- Fork `131ca636` (pushed to `origin/main`):
  - `merge-writer!` is now `transact!` plus parents.
  - `merge!`, `merge-async!`, `merge-db` and `merge-db!` accept an arg map.
  - The processing loop no longer threads merge parents into the next operation.
  - The commit loop takes the union of parents across its batch.
  - Also: the API spec arity, the CHANGELOG, and one regression.
- Superproject:
  - gitlink `684d3290` → `131ca636`.
  - `transact-call` dispatch.
  - The `transact!` docstring.
  - `resources/seon/schemas/seon.store.edn`: `:seon.store/transaction` declares
    `:datahike/expected-basis-t :int` and `:parents [:set {:min 1} :uuid]`. Branch keywords and
    an empty set are refused at the contract, so lineage is never a mutable name.
  - Regression `seon.db-test/a-merge-shares-the-write-fence-and-records-immutable-lineage`.

An existing lineage bug was found while probing the parent, on the fork JVM with
`(d/merge-db conn #{foo-cid} …)` followed by one ordinary `d/transact`. The next commit's
parents were `#{merge-cid foo-cid}`, so the ordinary transaction inherited the merge's parent.
If the fence had been added without the loop fix, batching would have silently lost merge
lineage. The fix is the 6 added / 3 deleted lines in `writer.cljc`. The plan did not price it.

Cost: a merge costs the same as a transaction over its delta, plus one set union per commit
batch. The ordinary path adds one keyword lookup in Seon, and one `dissoc` on meta per writer
operation in Datahike.

## Proof

**Fork.** One warm socket-REPL JVM (port 5877). Parent sources were loaded with `load-file`
from `git show HEAD:`, and mine with `require :reload`.

| namespace | parent | mine |
|---|---|---|
| versioning-test | 11 tests: 2 fail, 1 error, all in the new test (arity, stale basis, validator) | 11 tests, 82 pass |
| transact-test | 14 / 99 pass | 14 / 99 pass |
| writer-error-test | 10 / 144 pass | 10 / 144 pass |
| secondary-versioning-test | 3 / 14 pass | 3 / 14 pass |
| gc-test, specification-test (mine only) | — | 3 / 20 and 4 / 33 pass |

The lineage half is also red on the parent without the arity: see the probe above.

clj-kondo reports the same 7 errors on the five fork files as on the parent (all pre-existing).

**Seon.** Scratch root `tmp/nsa3-root`. The source is `tmp/nsa3-src`: `git archive 03f5afc70`,
with reference-code linked to the checkouts (datahike at `131ca636`) and the dependency class
cache linked (a hit, 373 namespaces). It booted at HEAD and was then adopted incrementally with
`init --dev --changed`. There was no from-zero boot of the schema change.

1. Adopted `seon.store.edn` and the test with the parent `db.clj`. Run `44bd7330e0ea`: 5 pass,
   1 fail. It fails at `:1185`: parents were `#{h}`, not `#{h c}`, so the lineage was dropped
   silently. The stale and validator assertions already pass on the parent.
2. Adopted `db.clj`. The `transact-call` digest moved `39b41d…` → `9a6448…` (armed). Run
   `f14049f4edd9`: 6 pass, 0 fail.
   - A stale basis refuses with `:transaction/stale-basis`.
   - The final owning-value validator refuses an incomplete `:seon.fn/sym` create.
   - The head is unchanged after both refusals.
   - The merge commit's parents are `#{h c}`, the held connection is the merge commit, and `c`
     is unchanged.

Contracts against the packaged projection of `tmp/nsa3-src`:
- `seon.contracts-compile-test/check` over `db.clj` and `db_test.clj` found nothing (1,343 ms).
- A `:seon.store/transaction` value with uuid parents and a basis is valid.
- `#{:foo}` and `#{}` are refused.

clj-kondo on `db.clj` and `db_test.clj` reports the same 20 warnings as the parent and 0 errors.

**Hot path, ordinary transact, parent against mine.** Each run is 60 empty transactions; the
first 10 are dropped and the rest give a median.

| probe | parent | mine |
|---|---|---|
| fork JVM `d/transact` (memory store, three alternating rounds) | 529 / 490 / 338 µs | 626 / 426 / 290 µs |
| scratch `seon.db/transact!` (file store) | 42.2 ms | 49.9 / 48.5 / 45.0 ms |
| same JVM, interleaved: raw `d/transact!` against the dispatch form | 37.8 ms | 37.6 ms |

The Seon medians rise with each run because every run adds 60 commits. In the interleaved
same-JVM pair, the dispatch costs nothing measurable, so there is no regression. The roughly
38 ms floor is Datahike's durable file-store commit and was already there.

**Load boundary.** The scratch JVM ran HEAD `03f5afc70` plus exactly these three files and the
fork at `131ca636`, all adopted and armed. HEAD after this commit was not booted separately.

## Reds outside this change

These reached the same members but have nothing to do with merge:
- `db-test/non-unique-writer-rejections-retain-their-datahike-data`
- `db-test/transaction-wrappers-cannot-hide-a-classified-refusal`
- `db-test/a-system-write-carries-no-per-write-bound`

All three write `{:seon.agent/id …}` fixtures without the now-required `:seon.agent/branch`,
and are refused before any Datahike call. This is a retired assumption, so the fix is the
expectation. No issue note was filed, because `docs/seon/issues/` is not in this lane's paths.

## Lines

| part | added | deleted | net |
|---|---:|---:|---:|
| fork source (5 files) | 34 | 17 | +17 |
| fork test | 39 | 0 | +39 |
| fork CHANGELOG | 2 | 0 | +2 |
| Seon `db.clj` | 5 | 1 | +4 |
| Seon schema | 3 | 1 | +2 |
| Seon test | 29 | 0 | +29 |

Code and tests together: 110 added, net +91. The plan priced 65–90. The extra is the unpriced
writer-loop lineage fix and the regression that pins it.

## TIMINGS (over 1 s)

| operation | wall | justification |
|---|---|---|
| scratch boot `start nsa3` | 96.9 s (ready 81.8 s) | **Defect over 10 s.** This is the existing `from-zero-boot-takes-minutes` class. A new root indexes the whole program; the dependency-class cache hit. |
| adoption: schema + test | 41.7 s | **Defect over 10 s**, adoption class. Most of it is `full-source-refresh!` (37.7 s). |
| adoption: `db.clj` | 43.6 s | **Defect over 10 s**, same class. |
| adoption: test-only re-adoptions ×4 | 6.8–12.1 s | same class. Two of the four runs (10.7 and 12.1 s) were over 10 s. |
| one-member `test-check` | 5.8–8.4 s | fixture acquisition through `acquire-context!` plus a file-store commit per write, proportional to the member's writes. Over the 5 s member ceiling in total wall, not in the body. |
| 5-member `test-check` | 10.4 s | **Over 10 s**, same fixture class |
| hot-path probes (60–180 transacts) | 2.6–7.8 s | proportional to call count at about 40 ms per durable commit |
| contracts check plus projection | 1.34 s | two whole-population projection builds (614 schemas) |
| fork suite, per namespace | versioning 2.4 s, secondary-versioning 6.5 s, gc 2.6 s | Pre-existing dependency tests on file stores and secondary indices, the same on the parent. |
| fork namespace reload | 0.94–1.26 s | recompiles 6–7 Datahike namespaces |
| fork push | 2.0 s | network round trip |
| git archive snapshot | 1.5 s | proportional to repository size |
| scratch `down` | 1.0 s | JVM exit |

RESET NEEDED: JVM restart only. `default` loads Datahike from source, and the fork change is
live after a restart. Nothing is stored.

Stopped before this report: the fork JVM and the scratch JVM (pid 82942). The disposable
directories `tmp/nsa3-root` and `tmp/nsa3-src` are kept as evidence.
