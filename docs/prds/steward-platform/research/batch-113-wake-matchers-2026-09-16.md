# Batch 113: a wake matcher read its datom by position

Date: 2026-09-16
Lane: bounded fix, branch `steward-platform`
Subject: `seon.cluster.wake/wake-matchers`, `seon.schema.datahike/encode-attribute-value-in`

## The red

`bin/test` batch 113 (`tmp/orchestrator/gate-results/batch-113.log:433`):

```
ERROR in (issue-settlement-runs-tests-and-derives-completion) (RT.java:952)
The committed budget datom addresses the resumed worker's existing wake route.
actual: java.lang.UnsupportedOperationException: nth not supported on this type: PersistentArrayMap
    at [seon.cluster.wake$wake_matchers$fn__4484$fn__4488 invoke wake.clj 422]
    at [seon.issue_settlement_test$... invoke issue_settlement_test.clj 210]
```

`fn__4488` is the inner `(fn [datom] ...)` a matcher compiles, and
`src/seon/cluster/wake.clj:422` at that commit was
`(or (nil? entity) (= entity (nth datom 0)))`. The throw is on the DATOM, not
on the encoded constraint value.

## The attribution in the assignment is refuted

The assignment named `wake.clj:418-422`'s
`(nth (first (schema.datahike/encode-transaction-in @projection [[:db/add 1 attribute logical]])) 3)`
as the root, on the hypothesis that `encode-transaction-in` stopped returning
vector operations. It did not.

- `encode-transaction-data-in` (`src/seon/schema/datahike.clj:487`) maps each
  operation: a map operation stays a map, a `[:db/add e a v]` vector operation
  is `(update operation 3 ...)` and stays a vector, and an unmatched operation
  falls to `:else operation` unchanged. A vector op in is a vector op out.
- `git log -L 526,543:src/seon/schema/datahike.clj` shows exactly two commits
  since the function was introduced at `10dfe0ff4`: `305be0b29` ("Supply schema
  projection while recording faults") wrapped the body in
  `schema/call-with-forms` and changed no return shape. The write-admission
  commits `35c5d2fa8`, `b1508dc8a`, `2df9ecccc` do not touch it.
- The stack frame is `fn__4488`, the matcher closure, not `fn__4484`, the
  enclosing reduce function where the encode call lives. The encode call never
  ran in the failing frame.

So `nth ... 3` was not the firing defect. It was, however, reading a value out
of a shape the contract does not promise (below), and is dissolved by the same
fix.

## The root cause: two declared datom shapes, one positional accessor

The system declares TWO datom shapes for the same fact, and a matcher is a
predicate over one datom:

| shape | source | `resources/seon/schemas/seon.db.edn` |
|---|---|---|
| host `Datom` | a transaction report's `:tx-data` | — (Datahike's own type) |
| `:seon.db/datom` map | `seon.db/datoms`, via `datom->data` (`src/seon/db.clj:1931`) | `:173` `[:map [:e ..] [:a ..] [:v ..] [:tx ..] [:added ..]]` |

Datahike's `Datom` answers BOTH accessors — `clojure.lang.Indexed` for `nth`
and `clojure.lang.ILookup` for `:e`/`:a`/`:v`/`:tx`/`:added`
(`reference-code/datahike/src/datahike/datom.cljc:75`, `:84`, `val-at-datom`
at `:116`, `nth-datom` at `:128`). An ordinary map answers only the keys. The
matcher and `route!` used `nth`, which is the accessor only one of the two
shapes implements, so the matcher was usable only from inside `route!`.

`test/seon/issue_settlement_test.clj:208` holds a committed datom the ordinary
way — `(first (db/datoms database :eavt issue-eid :seon.issue/budget))` — and
handed the matcher a `:seon.db/datom` map. `nth` refused it.

Keyword access is also the DEPENDENCY's own name for these fields
(`val-at-datom`'s `case`), and the same names `:seon.db/datom` declares, so
there is one accessor for the one fact.

## The fix

`src/seon/cluster/wake.clj`

- `wake-matchers`' compiled matcher: `(nth datom 0)` -> `(:e datom)`,
  `(nth datom 2)` -> `(:v datom)`.
- `route!`'s datom pass, for the same reason and the same one accessor:
  `(nth datom 1)` -> `(:a datom)` (twice), `(nth datom 2)` -> `(:v datom)`,
  `(nth datom 4)` -> `(:added datom)`. `nth-datom` and `val-at-datom` return
  the identical field for each index, so `route!`'s behaviour is unchanged.
- The constraint encoding no longer fabricates a one-operation transaction to
  read one value back out of it by position; it calls the single-value encoder
  directly.

`src/seon/schema/datahike.clj`

- New public `encode-attribute-value-in [projection attr value]`, the exact
  storage counterpart of the existing `decode-attribute-value-in`, wrapping the
  already-present private `encode-value-in` in `schema/call-with-forms` for the
  reason `305be0b29` recorded on `encode-transaction-in`.

This is the contract point the assignment asked about. `encode-transaction-in`
declares `:seon.store/transaction` in and out
(`src/seon/schema/datahike.clj:528`), and `:seon.store/transaction-operation`
is `[:or :map [:vector :seon.schema/value]]`
(`resources/seon/schemas/seon.store.edn:41`). Reading index 3 out of a returned
operation assumes the vector branch the contract does not promise. Its two
callers did NOT disagree about the shape: `src/seon/db.clj:3157` passes the
whole transaction straight to Datahike and never indexes into the result, and
wake was the only caller reading a component back out. A caller holding one
value now has a function whose contract answers it.

## The stored-versus-decoded boundary, recorded not papered over

A matcher compares the attribute's STORED form. Report `:tx-data` carries
stored values; `seon.db/datoms` decodes EDN-backed attributes
(`src/seon/db.clj:1938`). For an attribute the storage codec leaves alone —
every scalar and every ref, which is every attribute a listen pattern has been
written against — the two are identical and a matcher answers both shapes the
same. For an EDN-encoded attribute they are not, and the matcher answers the
stored form. That is stated in the `wake-matchers` docstring rather than
hidden.

## Where it fired live

`data/clusters/default/logs/seon.log:1169` — exactly one occurrence, and it is
the same assertion from the same test, run in process on the development
cluster (`seon.test/run`), not a production wake delivery. `route!` only ever
hands its matchers host `Datom`s from `:tx-data`, so no committed transaction
ever took this throw. The defect was real and total for every caller outside
`route!`.

## Regression

`test/seon/cluster/wake-test.clj`,
`a-constrained-listen-matcher-reads-both-declared-datom-shapes`: one agent with
two constrained runtime listens — a value on the ref attribute
`:seon.message/to` and a value on the scalar attribute `:example/amount` — then
the matchers are fed the SAME two committed facts twice: once as the host
`Datom` from the transaction report's own `:tx-data`, once as the
`:seon.db/datom` map `seon.db/datoms` returns. Both shapes must fire the
matcher for the recipient, and a non-matching value must still not fire. The
class it kills is "a datom read through one shape's accessor", which is why the
assertion is over both shapes rather than over the one that used to throw.

## Verification boundary

Command (three-slot `--paths` overlay of exactly this lane's files on HEAD):

```
bin/test-fast --paths src/seon/cluster/wake.clj test/seon/cluster/wake_test.clj \
  src/seon/schema/datahike.clj -- seon.cluster.wake-test seon.issue-settlement-test
```

`Ran 19 tests containing 168 assertions. 1 failures, 2 errors.`

- `seon.issue-settlement-test/issue-settlement-runs-tests-and-derives-completion`
  PASSES. The batch-113 `nth not supported on this type: PersistentArrayMap`
  is gone; the test's own settlement output is
  `Issue settlement-fixture: resolved; 0 turns remaining.`
- `seon.cluster.wake-test/a-constrained-listen-matcher-reads-both-declared-datom-shapes`
  (the new regression) PASSES.

### The three reds are pre-existing at HEAD, named not weakened

All three belong to ONE red,
`seon.cluster.wake-test/a-fault-wakes-the-steward-of-the-failing-functions-namespace`,
and all three are ONE cause: the fault recording transaction is refused by the
writer —
`:error datahike.writer :datahike/write-rejected {:kind :transaction/validation-rejected,
:cause "Transaction report validation rejected."}` — so
`(:db-after (db/transact! connection (:seon.db/tx-data recording)))` is `nil`
(`test/seon/cluster/wake_test.clj:455` in this working tree), which then makes
`seon.error/steward` refuse a nil fact (`src/seon/instrument.clj:425`) and the
mailbox event never arrive (`test/seon/test_support.clj:818`).

Baseline proving it pre-existing: the same command with a single UNMODIFIED
overlay path, which is HEAD for every file this lane touched —

```
bin/test-fast --paths src/seon/id.clj -- seon.cluster.wake-test
```

`Ran 16 tests containing 76 assertions. 1 failures, 2 errors.` — the identical
three, at HEAD line `test/seon/cluster/wake_test.clj:384` (this tree's 455
minus the 71 lines the new regression adds), with the identical writer
rejection line. Nothing in this lane's change is on that path: the change is
the datom ACCESSOR, and that red never reaches a matcher. It is out of this
lane's scope and is left named, not weakened.

- NOT run: `bin/test`, `--platform`, any full suite. `src/seon/db.clj` and
  `src/seon/render/*` are untouched.
- No live cluster was reset, started or stopped. Every claim above is from
  source, git history and these two runs.
