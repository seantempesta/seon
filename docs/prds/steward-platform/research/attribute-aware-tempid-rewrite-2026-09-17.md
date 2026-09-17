---
type: research
status: landed
created: 2026-09-17
tags: [publication, program-graph, datahike]
---

# Attribute-aware transaction tempid rewrite — 2026-09-17

## Seam

Incremental and complete program publication both flatten program rows through
`compile-index-transaction` (`src/seon/fn.clj:2564`). Its identity index maps
each known `[identity-attribute value]` pair to one transaction tempid. Before
this change, the recursive rewrite replaced every matching two-element vector,
including members of value attributes.

That contradicted Datahike's own transaction boundary. Entity-map expansion
preserves ordinary attribute values and resolves a value through `entid-strict`
only when the installed attribute is `:db.type/ref`
(`reference-code/datahike/src/datahike/db/transaction.cljc:739-767`,
`:786-795`). The database value's carried projection is the corresponding
first-party authority: `seon.schema.datahike/storable-attribute-in?` and
`malli->datahike-attr-in` derive the installed value type from that projection
(`src/seon/schema/datahike.clj:233-309`).

The live read-only JVM probe against `default` at basis `536871054` confirmed
that `:seon.schema/references` is installed as cardinality-many
`:db.type/keyword`; it is storable and is not a ref. No operation, restart, or
development adoption was performed against `default`.

## Fix

`compile-index-transaction` now receives the immutable projection owned by its
database value. It derives and memoizes the ref question per encountered
attribute. The recursive tempid substitution runs only below attributes whose
derived `:db/valueType` is `:db.type/ref`; every value attribute is copied
unchanged. `reconcile-tx-in` and the fresh population path first use the
database value's carried projection, deriving it from that exact database value
only when absent (`src/seon/fn.clj:2564-2655`, `:2691-2728`, `:2875-2882`).

The canonical fixture regression installs one tuple value attribute and one ref
attribute. Both hold the same `[:seon.ns/name symbol]` identity-shaped vector.
It positively asserts that the compiled value remains the vector, the compiled
ref becomes the target tempid, the real writer admits the transaction, and the
stored tuple and joined ref target both equal their expected values
(`test/seon/fn_test.clj:1857-1920`). The writer refusal remains the negative arm:
the pre-fix rewrite would put a tempid string into the tuple and fail admission.

## Verification

Owned fast proof:

```text
bin/test-fast --paths src/seon/fn.clj test/seon/fn_test.clj -- seon.fn-test
Ran 62 tests containing 427 assertions.
0 failures, 0 errors.
```

The requested combined fast command admitted exactly the two owned paths and
ran to a total result:

```text
bin/test-fast --paths src/seon/fn.clj test/seon/fn_test.clj -- seon.fn-test seon.cluster.source-test
Ran 80 tests containing 551 assertions.
3 failures, 9 errors.
```

`seon.fn-test`, including the new regression, completed without a failure in
that run. The red boundary is the unowned `seon.cluster.source-test`: the
snapshot announced an overlay graph 18 commits behind HEAD, several fixtures
failed in `seon.schema/projection-cache-value` with
`:malli.core/invalid-schema`, and
`incremental-first-party-publication-retains-complete-scalar-rows` expected only
`seon.id/id` while the snapshot contained seven current `seon.id` functions.
Concurrent `current-src` head changes were also reported during its publication
fixtures. None names the owned tempid rewrite or its regression. The
orchestrator still owes the cold path-limited gate and platform proof.
