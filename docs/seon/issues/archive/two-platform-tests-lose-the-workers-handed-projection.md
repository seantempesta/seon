---
type: issue
status: resolved
severity: blocker
tags: [issue, test, schema, runtime, class/p1]
---

# Two platform tests fail when the worker's handed projection does not reach them

Batch 26's platform tier (`bin/test --platform`, working-tree overlay) went red
on two tests that build their own stores:

- `seon.cluster.registry-test/non-temporal-collection-marks-current-blob-references`
  (ERROR: `seon.db/transact!` refused `:seon.schema/key` as an undeclared
  attribute)
- `seon.cluster.store-test/branch-connections-inherit-the-root-history-representation`
  (FAIL: `seon.db/q` refused `:seon.store.test/marker` as uninstalled)

Both refusals report
`:seon.db/registered-candidates [:db/ident :db/txInstant :db.entity/attrs
:db.entity/preds :db.valid/from :db.valid/to :dh.ref/db :dh.ref/value]` —
Datahike's base attributes only. That is the signature of NO first-party
projection in force, not of a missing declaration.

## Measured, in the adopted `default` JVM (2026-09-16)

Both tests were loaded through a test loader and run in-process with
`seon.test/run` against the current working tree:

- run with no projection handed by the caller: BOTH reproduce the batch-26
  failures verbatim;
- run inside `(schema/call-with-projection (schema/declaration-projection
  (schema.edn/packaged-forms)) …)` — the exact projection a worker arms with
  (`src/seon/test/runner.clj:1080`, served around every worker command at
  `src/seon/test/runner.clj:1273`): BOTH PASS (3 and 1 assertions, 0 failures).

So the tests are correct and the tree is correct; what varies is whether the
worker's handed projection reaches the thread that runs the test. Neither test
touches the population path that batch 26's overlay changed
(`seon.cluster/populate-source!`, `accrete-schema-population!`,
`declaration-changes`): their call paths are `store/open-store!`,
`db/transact!`, `d/branch!`, `store/open-branch!`, `db/q`, `registry/branch!`.

## Lead

`on-caller-loader` (`src/seon/test/runner.clj:53`) pins the submitting thread's
CLASSLOADER onto every executor submission but conveys no dynamic bindings — it
wraps a plain `fn`, where `bound-fn` would carry the frame. Any test executed
through such a submission runs without `*projection*`, and
`seon.db`'s resolution then falls through to whatever the connection carries,
which for a freshly opened store is Datahike's base attributes.

Secondary observation from the same runs: with a first-party projection in
force, `seon.db/retain-transaction` calls `d/history`
(`src/seon/db.clj:2844`) while snapshotting retention rules, and a
`:keep-history? false` store answers "history is only allowed on temporal
indexed databases" — the write then fails inside Datahike's writer. Retention
rule count on the current declaration projection is 1, so every non-temporal
store write depends on that path staying quiet.

Owned by the test runner (shared) and `seon.db` (under concurrent edit), so
this is filed rather than fixed here.

## Resolved 2026-09-16 — two real owner defects, and a corrected diagnosis

Measured in the adopted `default` JVM (pid 53378) through
`seon.test/run`'s own loader, one form at a time.

**The lead above is only half right, and the "no first-party projection"
inference is wrong.** `:seon.db/registered-candidates` is derived from the
DATABASE's installed schema — `installed-attribute-declarations`
(`src/seon/db.clj:1069`) reads `(dbi/-schema (schema-database database))` —
not from the handed projection. Base attributes there mean the STORE has no
first-party attributes, which is exactly what a store whose schema
installation silently failed looks like. Reproduced directly: after
`db/transact!` of the probe schema into `store/open-store!`'s connection with
`:seon.config.db/keep-history? false`, `(:max-tx @main)` was still
`536870912` and the schema was unchanged, while Datahike's writer logged
"history is only allowed on temporal indexed databases".

1. **`seon.db/retention-snapshot` (`src/seon/db.clj:2844`)** asked Datahike
   for `history` unconditionally, so ONE declared
   `:seon.db/append-only-after` rule broke EVERY write to EVERY
   non-temporal store — inside the writer, after admission, so the refusal
   arrived as a failed transaction rather than a value. It now derives from
   the database itself when `(dbi/-temporal-index? database)` is false: a
   non-temporal database's current datoms ARE its complete record.
   Regression:
   `seon.transact-feedback-test/non-temporal-stores-admit-writes-while-a-retention-rule-is-in-force`
   (3/0/0 with the fix; 0 passes and 3 failures with the old `history`
   call restored by `alter-var-root`). With only this fix, both platform
   tests went green through the ordinary `(seon.test/run var connection)`
   arity — this, not projection delivery, is what made batch 26 red.

2. **`on-caller-loader` (`src/seon/test/runner.clj:53`)** did drop the
   caller's frame, independently and as filed. Measured with a promise and
   `.execute` (a Clojure fn is both `Runnable` and `Callable`, so a
   `.submit` probe silently selects `submit(Runnable)` and answers `nil`
   whatever the task returns — that artifact is worth remembering): inside
   `schema/call-with-projection`, the old wrapper's task saw NO handed
   projection and the `bound-fn*` wrapper saw the caller's. It now conveys
   the frame. Regression:
   `seon.test.runner-test/executor-submissions-carry-the-callers-handed-projection`
   (2/0/0).

The in-process availability note this pair was blocked on is refuted and
archived: `docs/seon/issues/archive/the-development-cluster-jvm-cannot-run-an-in-process-regression.md`.
