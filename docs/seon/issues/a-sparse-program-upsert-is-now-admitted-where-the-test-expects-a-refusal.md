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

The transaction is `[{:seon.fn/sym "seon.id/id" :seon.fn/doc "incomplete"}]`
against the canonical database fixture, and it is now admitted.

Admitting a partial upsert of an entity that ALREADY EXISTS is the behaviour
`35c5d2fa8` intended — it is the resolution recorded in
`archive/a-partial-upsert-of-an-existing-entity-is-validated-against-its-complete-required-keys.md`.
So the assertion may simply be stale. What nothing here proves is which case
this is: the test does not establish that `seon.id/id` has a row in the fixture
before the sparse write, so an incomplete CREATE being admitted is not ruled out,
and that would be a genuine gap in the new validator rather than a stale
expectation.

Resolution needs one probe by the write-admission owner: assert whether
`[:seon.fn/sym "seon.id/id"]` resolves in the fixture before the sparse
transaction, then either correct the expectation (naming the required keys the
existing entity already carries) or fix the validator's create path.

Found while fixing the source seal refusal
([note](../../prds/steward-platform/research/source-seal-refusal-2026-09-17.md));
out of that lane's scope.
