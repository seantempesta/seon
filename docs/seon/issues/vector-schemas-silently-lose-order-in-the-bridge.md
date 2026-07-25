---
type: issue
status: open
severity: correctness
tags: [issue, database, schema]
---

# Stored ordered collections silently lose order

## Problem

Datahike does not preserve insertion order for `:db.cardinality/many`.
Scalar values pull in value order and refs pull in target entity-ID order.
Component refs are not an exception. A stored `[:vector X]` declaration
therefore promises an order the database cannot keep.

## Reproduced (2026-07-25, `clojure -M:writer`, real Datahike)

The invariant in `test/seon/schema_collection_order_test.clj` proves both
failure modes against a real in-memory database:

- scalar input `["zebra" "apple" "mango"]` pulls as
  `["apple" "mango" "zebra"]`; and
- pre-existing refs written in `C B A` order pull in `A B C` entity-ID order.

Homogeneous tuples are not a general solution: Datahike caps them at eight
values and element-level Datalog queries do not match their members.

## Current state

The classified twelve are documented in
`docs/prds/sci-execution-runtime/research/vector-order-audit-2026-07-25.md`.
Eleven are semantically sets; the agent and root config contexts are more
precisely cardinality-one component refs. `:seon/embedding` alone is ordered:
`:db.secondary/only` stores its content hash in the primary index while
Proximum owns the actual vector.

The invariant passes for those loaded registrations. A broader load exposes
one additional offender outside the original twelve:
`:seon.agent.turn/evals` at `src/seon/eval/receipt.cljc`. Its consumers include
code that treats pull order as eval order, so it must not be mechanically
changed to a set.

## Acceptance

1. Redesign `:seon.agent.turn/evals` with an explicit ordering fact or remove
   positional consumers.
2. Load the eval-receipt registration on the recurring invariant-test surface.
3. Keep `:seon/embedding` exempt only through the computed
   `:db.secondary/only` property.

Related: `multi-form-eval-order-is-not-durable.md`,
`datahike-cljs-cardinality-many-collapses-large-bigints.md`.
