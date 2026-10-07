---
type: notes
status: active
created: 2026-10-07
lane: review-schemameta-opus
---

# Review: Datahike fork 8ed09208 "a commit that changes no schema reuses its schema-meta key"

Spec: `review-schema-meta-spec.txt`. Commit 8ed09208 on dbca76d5 (branch `seon/schema-meta-key`). Seon's gitlink
stays at dbca76d5 (not bumped).

## Broken things first

- **EQUIVALENT is falsified for mutable leaves.** The reuse test is `identical?` on the five schema-meta maps. A
  persistent map is immutable, but a leaf inside it need not be: a `java.util.Date` or a byte array held in an
  attribute's schema value (e.g. `:db.secondary/config {:value (java.util.Date. 1000)}`), mutated after the commit,
  changes the content while every map stays `identical?`. 8ed09208 then reuses the old key, so the stored record
  names a schema-meta whose content is no longer the database's; the parent rehashes and names the new content.
  Found by the parallel reviewer (Astra), whose uncommitted test `mutable-schema-values-are-rehashed` in the fork
  checkout I ran against an archive of 8ed09208: 4 failures (both values, the stored-key and stored-record
  assertions). Their fix (in progress on `seon/schema-meta-key`, uncommitted when I looked) walks the schema once
  per new schema and reuses the key only when every leaf is an immutable EDN value. Mutating a value held by a
  committed database also breaks Datahike's index ordering for datoms, so this needs deliberate misuse, but the
  claim "a reused key can never name a different schema" did not hold.
- **I pushed 8ed09208 before seeing that.** `main` and `seon/identity-join-first` on origin were fast-forwarded
  dbca76d5 → 8ed09208 (no force). The fix has to land on top and be fast-forwarded again; nothing in Seon pins
  8ed09208. Local `seon/identity-join-first` is still dbca76d5 (the checkout was switched by the other reviewer, I
  did not move it); local `main` is 8ed09208.
- **Measurement trap in the fork's own classpath.** The fork's `deps.edn` pins konserve 5b39fddd, which predates
  konserve c73cbde (LZ4 frames with 64 KiB blocks). A JVM started from the fork's aliases allocates 52-122 MB per
  one-datom commit on a clone of default, JFR: 65.7 MB of 68.7 MB per commit in `LZ4FrameOutputStream.<init>`
  (4 MiB block buffers per value). Seon's own pin (konserve 8700ca7) has the fix: 4-6 MB per commit. Any fork
  measurement must override konserve to `reference-code/konserve` (as below) or bump the fork's pin.

## Method

Own JVMs only, from `git archive 8ed09208` in the scratchpad (`clojure -M:test`, konserve overridden to
`reference-code/konserve` for the cost rows). Parent behaviour: `load-file` of `git show dbca76d5:src/datahike/writing.cljc`
into the same JVM, alternating with the change. Store: `cp -c -R data/store` while default had 0 open turns,
opened with Seon's store configuration and its original id; branch `:cluster-default` (2,318 attributes).
Allocation: `getTotalThreadAllocatedBytes` around each `d/transact` (the writer runs on another thread).
Probe data must be a non-schema attribute: my first runs used `{:db/doc ...}`, which Datahike treats as a schema
entity (the schema grew by one entry per commit and every commit rehashed), so those rows were discarded.

## (1) Equivalence probe (file store, keep-history)

After each commit: the head record's `:schema-meta-key` = `hasch/uuid` of the db's schema-meta = `hasch/uuid` of
the stored schema-meta value, and the stored value equals the db's. 23/23 OK: fresh store, schema added, data
commit, `:db/doc` changed, cardinality changed, a doc retracted (schema returns to earlier content), an attribute
retracted, two stores with different schemas alternating 3x, a branch with its own schema alternating with main
3x, `merge!` of that branch, a commit after merge, release and reconnect then commit. Rename is refused by
Datahike under `:write` flexibility (no case). None of these mutate a leaf, so they do not reach the counterexample
above.

## (2) Cost, clone of default, `:cluster-default`, one datom per commit, n = 30 per row

| round | parent ms / MB (median) | change ms / MB (median) |
|---|---|---|
| 1 | 17.0 / 23.3 | 12.0 / 5.5 |
| 2 | 15.9 / 21.9 | 12.8 / 4.0 |
| 3 | 15.9 / 21.9 | 12.0 / 4.0 |

One `hasch/uuid` of this schema-meta: 29 MB, 35-41 ms cold. A reused key: 0.002 ms, 12-20 KB. Commits alternating
between `:cluster-default` (key 0dc80bc3, 2,318 attributes) and an agent branch on the older schema (key 297f57f1,
2,316): parent 16.0 ms / 21.8 MB, change 16.0 ms / 21.8 MB, so the single slot gives nothing, and costs nothing,
when branches with different schemas interleave. Default's clone has 33 branch heads on 4 schema keys (23 on
297f57f1, 8 on 0dc80bc3). A value-keyed small cache (lookup by the schema-meta map: its hasheq and `=` short-cut on
the identical inner maps) would cover interleaving; not required for this landing.

## (3) Regression test

`schema-meta-key-test/a-commit-that-changes-no-schema-hashes-no-schema` is red on the parent writing.cljc
(`(not (< 130970080 19542584))`, the allocation assertion) and green on 8ed09208; an "always reuse" implementation
fails its schema-change assertion. It does not cover mutable leaves (above). Fork suites on 8ed09208, `clj-pss`:
`schema-meta-key-test schema-test transact-test versioning-test commit-graph-test writer-error-test api-test`
80 tests, 607 assertions, 0 failures.

## Verdict

Not as claimed: (2) and (3) hold, (1) holds for every Datahike operation probed but is falsified by mutable schema
leaves. 8ed09208 was already fast-forwarded and pushed by me; the mutable-leaf fix on `seon/schema-meta-key` must
be reviewed and fast-forwarded on top before root bumps Seon's gitlink past dbca76d5.
