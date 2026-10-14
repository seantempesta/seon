---
title: "Projection content key, phase 1: stopped, patch saved"
date: 2026-10-14
basis: "HEAD 09062eec4"
---
# Projection content key, phase 1 (lane projections, 2026-10-14): stopped, patch saved

Basis HEAD 09062eec4. Patch: `proj-content-key-2.patch` (supersedes `proj-content-key.patch`; applies to
`src/seon/db.clj`, `test/seon/db_test.clj`, `resources/seon/tasks/seon.db.edn`). Not committed: root asked lanes to
stop at ~15 minutes and to release `src/seon/db.clj` (S3d-3 waits on it); the key-shape question below is open.

## What the patch does

- `seon.db/declaration-content-key`: the rows `seon.schema/load-projection` joins, per range in
  `seon.schema/projection-ranges`: `[identity values sources]` in identity (AVET) order. It holds no e or tx; the loader
  reads neither (`parsed-rows` drops the tx it is given). It covers only rows that have an identity, as the loader does.
- `seon.db/speculative-report-projection`: the advanced projection goes in under the content key of `:db-after`
  (`content-projection`, 5-arity with a derivation fn). `advanced-key` and the value's revision key are aliases.
  `hold-committed-projection!` resolves the advanced alias in place of `present ::value-projection`.
- Proof `seon.db-test/equal-declarations-in-another-transaction-share-one-projection`, tagged with the task
  `seon.db/one-projection-per-declaration-content`. It checks another tx, other entity ids, a changed declaration, and
  a writer-committed population re-written by `d/with` (identical object, no miss, no new entry).
  `a-definition-write-keys-its-projection-by-its-own-datoms` now expects one content-key call per declaration write
  (it used to expect none).

## Runs (`bin/seon check`)

- Red on HEAD: run b102d45ac727 (HEAD + test). Content-key equality fails, and "equal content holds one entry"
  fails with 7 vs 9.
- Green with the change, in two orders: run 8c96777eefbe (the proof first, then projection-writer ns: 63 pass), and
  run 9414f75c37a6 (projection-writer ns, then all of seon.db-test: the proof is green).
- HEAD in the same order, run 1634e568bbf7, has 5 reds. Four of them the change fixes:
  `a-new-branch-at-an-equal-commit-reads-the-held-projection`, `committed-reads-derive-once-per-declaration-population`,
  `ordered-declarations-use-their-database-and-abort-together`, and `speculative-projections-are-process-cache-hits`
  (the last is red on both, flaky: misses are counted across the whole kind while tests run in parallel). The fifth,
  `equal-declaration-content-shares-one-projection-across-values` (fixture commits differ), is a pre-existing red.
- OPEN: `seon.db-test/nested-native-reports-admit-bounded-reference-identities` is red with the change, run alone
  (run ea02a195303e: artifact 6,922 vs 4,096 chars), and green at HEAD in run 1634e568bbf7. The artifact is an empty
  transaction's report, and its tx-data carries profile cost facts (`:seon.fn.cost/key`) flushed with the write. So its
  size follows how many new cost keys this JVM has, not the projection. The HEAD-alone control was refused (dangling-id
  from the new test in the candidate) and is not yet run.

## Numbers (temporary measurement deftest in a check JVM, armed; not committed)

| | HEAD | change |
|---|---|---|
| content key per call (armed, default-size fixture) | 0.94-1.41 ms (datom vectors) | 3.47-3.71 ms (rows) |
| content key, JVM mode on default, unarmed (10,644 rows) | 1.13 ms | 3.86 ms; +hash 5.1 ms; +`seon.id/digest` 13.0 ms |
| previous lane's `frequencies` rows key, default | | 20.1 ms (+26 ms digest) |
| ruling B: registration write before a 22-`with` batch, median of 7 | 46.7 ms | 45.6 ms |
| ruling B: write after the batch, median of 7 | 110.5 ms | 95.4 ms |
| 10 registrations on each of 2 branches (equal content), projection kinds: added entries / bytes / misses | +20 / +215 MB / +20 | +10 / +107.5 MB / +10 |

Both columns' after-batch writes double. That is eviction pressure from `::value-projection` (phase 2's subject):
after the 22-value batch the kind holds 44 entries and ~474 MB of the 512 MB budget.

## Key shape (root ruling 2026-10-14: option 2)

reconcile-writer found that every committed hit calls `alias!`, which compares two equal but separate copies of the
content key with `=` (1.04 ms per read). Root ruled: keep the rows key, no new identity function. Two changes:
`seon.db/alias!` compares the stored canonical by `identical?` before `=`, and `content-projection` names the content
in the alias kind first (`alias!` with the content key as its own alias key). That alias answers the canonical first
named with that key, so every alias and every later `present` holds one key object, compared by identity. The other
options (a 64-char digest, +9 ms per miss; a 128-bit hash fold, a new identity function) were not taken.

## Phase 2 (speculative projections per operation): open question, deferred (plan §0)

The writer derives the report's projection in the Datahike validator, on the writer thread. Datahike's validator
contract only returns nil or a refusal (`datahike.db.transaction/validate-report`), so the projection cannot ride
on the committed report. Options:
(a) a one-shot `promise` created in `seon.db/transact!` and delivered by `validate-write-report`.
    `hold-committed-projection!` would then retain it under the content key. This adds a place, so it needs the owner's
    reason.
(b) a fork change: the validator may return an accepted report extension that Datahike carries onto the committed
    report. This is a dependency bump in a restart batch.
(c) `seon.db/with` returns `:db-after` with the projection in its metadata (`construction-projection` already reads
    `(:seon.schema/projection (meta db))`). Raw `d/with` values and transaction-function arguments would derive on
    every read. `speculative-projections-are-process-cache-hits` asserts the opposite and would be deleted.

The owner's direction ("a speculative projection lives for the operation that needs it, held as a value, passed
explicitly") favors a value carrier, (b) or (c). Option (a) adds a mutable place, which needs the owner's reason.
Filed with its numbers as `seon.cache/the-cache-sits-at-its-byte-budget-during-check-runs`.
