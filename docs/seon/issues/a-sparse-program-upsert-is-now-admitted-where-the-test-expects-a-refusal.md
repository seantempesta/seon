---
type: issue
status: open
severity: friction
created: 2026-09-17
tags: [issue, write-admission, seon.db, testing]
---

# A sparse program upsert is now admitted where the test expects a refusal

At HEAD `ca8fd63b9`, `seon.cluster.source-test/incremental-first-party-publication-retains-complete-scalar-rows`
fails at `test/seon/cluster/source_test.clj:334`:

```
FAIL … the canonical fixture has authored entity validation armed
expected: (= :seon.db/invalid-write (:seon.error/kind refused))
  actual: (not (= :seon.db/invalid-write nil))
```

## Correction, 2026-09-17 — it is the create path, not a stale expectation

The transaction named above is the WRONG one. The failing `refused` binding is
`test/seon/cluster/source_test.clj:336-338`:

```clojure
existing (db/pull (db/db connection) '[*] [:seon.fn/sym "seon.id/id"])   ; :332
sparse   [{:seon.fn/sym "seon.id/id" :seon.fn/doc "updated documentation"}]  ; :333
updated  (db/transact! connection sparse)                                ; :334 — asserted to SUCCEED at :340
refused  (db/transact! connection
           [{:seon.fn/sym "seon.source.test/incomplete"                  ; :337
             :seon.fn/doc "incomplete"}])                                ; :338 — the failing assertion at :341
```

`seon.source.test/incomplete` is a NEW identity carrying only `:seon.fn/sym`
and `:seon.fn/doc`. `:seon.fn/fn` requires `:seon.fn/ns` and
`:seon.schema.admission/source`
(`resources/seon/schemas/seon.fn.edn`), and `:seon.fn/sym` still declares
`:seon.program/row-schema :seon.fn/fn` with `:seon.db/identity true`
(`:178-181`). So this is an incomplete CREATE being admitted.

That resolves the open question without the requested probe, and it resolves
it the other way: the test DOES establish the existing row — `:332` pulls it
and `:339` asserts `(some? (:db/id existing))` with the message "the sparse
write updates a complete fixture row". The partial upsert of an existing
entity is the separate, passing assertion at `:340`. The expectation at `:341`
is NOT stale; the validator's create path is admitting an entity that its
declared schema refuses.

This is the project's recurring class from the write side: the check reports
health when its subject is absent. A create with no `:seon.fn/ns` produces no
datom for the required key, and the whole-entity validator returns `nil`
rather than a refusal.

Owner: whoever owns `write-entity-error` / `write-entity-schemas`
(`src/seon/db.clj:2703-3031`), landed today in `35c5d2fa8` and `b1508dc8a`.

Attribution evidence (it is not the incremental-publication slice): at HEAD
`6a2201f29`, 23 minutes BEFORE `2dd9a4970`, a `bin/test-fast --paths` snapshot
carrying zero source changes — one untracked Markdown note only — produced
this identical failure. At HEAD after `2dd9a4970` and `5e54c9ae1`, with those
files overlaid, `seon.cluster.source-test` is 18 tests / 170 assertions /
1 failure / 0 errors: this one assertion and nothing else.

Found while fixing the source seal refusal
([note](../../prds/steward-platform/research/source-seal-refusal-2026-09-17.md));
out of that lane's scope.
