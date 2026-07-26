---
type: issue
status: resolved
severity: blocker
tags: [issue, database, schema]
---

# Stored ordered collections silently lose order

## Problem

Datahike does not preserve insertion order for `:db.cardinality/many`.
Scalar values pull in value order and refs pull in target entity-ID order.
Component refs are not an exception. A stored `[:vector X]` declaration
therefore promises an order the database cannot keep.

## Datahike verification (fork `caf526850084`, 2026-07-26)

Cardinality-many is membership, not insertion order:

- `datahike.db.utils/multival?` recognizes only
  `:db.cardinality/many` (`reference-code/datahike/src/datahike/db/utils.cljc:24-26`).
- Each index is a `PersistentSortedSet` configured with the index comparator
  (`reference-code/datahike/src/datahike/index/persistent_set.cljc:480-485`).
  Insert suppresses an existing equal `(e,a,v)` triple
  (`persistent_set.cljc:133-139`), so duplicates do not survive.
- EAVT compares entity, attribute, then value
  (`reference-code/datahike/src/datahike/datom.cljc:325-330`). A ref's value is
  its target entity ID, so ref children sort by entity ID.
- Pull scans EAVT for `[entity attribute]` and copies those datom values into a
  vector (`reference-code/datahike/src/datahike/pull_api.cljc:240-243,270-274`).
  The returned vector is therefore index order, not write order.

The isolated in-memory REPL probe retained at
`tmp/plan-evidence/datahike-cardinality-many-repl-2026-07-26.log` wrote
`["zebra" "apple" "mango"]`. EAVT and pull both returned
`["apple" "mango" "zebra"]`; `:verify.order/order-preserved?` was `false`.
The recurring test separately proves that refs created `A B C` and written
`C B A` pull back by entity ID as `A B C`.

Tuple is narrower than this issue previously claimed:

- Datahike documents heterogeneous tuples as fixed-length with a declared
  vector of 2-8 element types and homogeneous tuples as variable-length
  (`reference-code/datahike/doc/schema.md:77-80,129-150`).
- Transaction validation enforces the homogeneous eight-value cap and element
  type, while the heterogeneous branch enforces equality with the declared
  `:db/tupleTypes` count and the per-position types
  (`reference-code/datahike/src/datahike/db/transaction.cljc:1006-1028`).
- The same isolated probe rejected a nine-element homogeneous tuple but
  accepted a heterogeneous tuple declared with nine types. The implementation
  therefore does not enforce the documentation's heterogeneous upper bound.

A tuple remains one vector value, not a cardinality-many relationship. For
ordered unbounded children, the database model is a component ref plus a child
position. A query joins parent → child → position; ordinary Datalog results are
deduplicated into a set
(`reference-code/datahike/src/datahike/query.cljc:4246-4252`), and explicit
`:order-by` sorts to a vector (`query.cljc:3551-3560`). The equivalent existing
Seon read is
`seon.agent.driver/ordered-forms`: pull children, then `sort-by` their stored
ordinal. That adds the child-position join plus an `O(n log n)` sort on read;
it is the cost of a genuine unbounded ordered relationship.

## Seven-attribute usage verdict

The exact recurring population is retained in
`tmp/plan-evidence/vector-order-seven-before-2026-07-26.log`.

| attribute | verdict | strongest actual-use evidence |
|---|---|---|
| `:portable.record/children` | SET | Test-only portable transaction fixture; its sole stored example has one child and only checks child encoding. No consumer assigns sequence meaning. |
| `:seon.agent.ctx/capabilities` | SET | `seon.agent.ctx` explicitly says the field is parked with no consumer; capabilities are membership. |
| `:seon.agent.message/to` | SET | Every query tests ref membership and renderers map recipient labels. The boundary already deduplicates recipients; no reader assigns recipient position meaning. |
| `:seon.agent.run/forms` | POSITION | `plan-tx-data` stores `:seon.agent.run.form/ordinal`; resume pulls the component children, calls `ordered-forms`, and selects the first ordinal without a terminal receipt. |
| `:seon.agent.turn/evals` | POSITION | Each running receipt stores `:seon.eval/ordinal`. Resume derives terminal ordinal membership, while transcript reconstruction sorts each turn's evals by that ordinal. |
| `:seon.agent.turn/timings` | SET | There is no production reader. The one behavioral test reduces `(name, ordinal)` pairs to a set, so no consumer depends on collection order. The ordinal remains useful measurement identity, but does not make the parent edge ordered. |
| `:seon.render/children` | SET | The attribute has no writer or reader; the render walker never consumes it. The surviving declaration can promise membership only. |

No verdict is ambiguous and no tuple is warranted. All seven declarations are
sets. The two POSITION collections store their ordering on child rows; their
writers emit set membership and their readers derive order from ordinals.

## Owner

The owning schema namespaces and their writers/readers. Clusters reset to the
new schema; no migration or compatibility path.

## Acceptance

1. The recurring collection-order invariant loads all seven registrations and
   reports no offender.
2. The resume-ordinal-hole and duplicate-execution regression classes remain
   green.
3. Full `bin/test-writer` evidence is retained under `tmp/plan-evidence/`.
4. `:seon/embedding` remains exempt only through the computed
   `:db.secondary/only` property.

## Resolution

Resolved by commit `e988115d5`:

- all seven stored cardinality-many schemas now declare set membership;
- the run-form and eval relationships derive order only from their existing
  child ordinals; and
- set-backed recipient rendering sorts labels only at the presentation
  boundary.

Proof on 2026-07-26:

- the isolated Datahike probe is retained at
  `tmp/plan-evidence/datahike-cardinality-many-repl-2026-07-26.log`;
- the focused writer proof ran 29 tests / 196 assertions with zero failures
  and errors at
  `tmp/plan-evidence/vector-order-focused-after-2026-07-26.log`;
- the final CLJS gate ran 1,300 tests / 6,682 assertions with zero failures
  and errors; and
- the full writer gate ran 551 tests / 3,881 assertions with zero failures
  and errors at
  `tmp/plan-evidence/vector-order-test-writer-full-2026-07-26.log`.

Related: `multi-form-eval-order-is-not-durable.md`,
`datahike-cljs-cardinality-many-collapses-large-bigints.md`.
