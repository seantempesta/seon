---
type: issue
status: resolved
severity: friction
tags: [issue, database, architecture]
---

# JDK Integers refuse at every database boundary

Datahike's `:db.type/long` predicate accepts exactly
`java.lang.Long`. JDK APIs commonly return `java.lang.Integer`, so one
such value could refuse an otherwise-valid whole transaction. The
HTTP-status call site had already acquired a local `(long …)` cast in
`ff9dde36e`; relying on every caller to remember that cast kept
reintroducing the same bug class.

## Resolution

Commit `6a2c8f1fb` makes `seon.cluster.store/transact!` normalize
transaction data immediately before calling Datahike. A postwalk
changes exactly `java.lang.Integer` values to `java.lang.Long`, whether
they occur in entity maps or datom vectors. No other numeric class is
coerced: a `Double` presented to a `:db.type/long` attribute still
returns `:seon.db/rejected`, and no part of that transaction commits.

The HTTP response status in `src/seon/ai.cljc` now remains the
`java.lang.Integer` returned by the JDK until it crosses the transaction
boundary; the per-site cast from `ff9dde36e` is deleted.

The redundant casts in `src/seon/cluster/loop.cljc` and
`src/seon/config.cljc` remain because those files had active owners
during this fix; neither is now a correctness dependency because the
transaction boundary owns normalization.

## Proof

Before the implementation,
`bin/test seon.cluster.store-test` ran 11 tests and 35 assertions with
the two expected failures: transactions containing an `Integer` in an
entity map or datom vector were rejected. The `Double` refusal already
passed.

After the implementation,
`bin/test seon.cluster.store-test seon.ai-stream-fold-test` ran 26
tests and 77 assertions with zero failures or errors. The regression
queries both stored values and proves their exact class is
`java.lang.Long`; it also proves the `Double` transaction remains
rejected and its marker is absent.
