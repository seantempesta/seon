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

## Six live offenders, surfaced 2026-07-26 when the gate came back

`bin/test-writer` had been discovering 0 tests (stale compiled artifact), so this
invariant could not fire. Restoring the gate surfaced these immediately —
`no-stored-attribute-promises-an-order-the-database-cannot-keep`
(`schema_collection_order_test.clj:61`), retained log
`tmp/plan-evidence/test-writer-2026-07-26.log`:

| attribute | declared | order actually load-bearing? |
|---|---|---|
| `:seon.agent.run/forms` | `[:vector component ref]` | **yes** — the resume unit. Recovered in practice: each child carries `:seon.agent.run.form/ordinal` and `plan-forms` sorts by it, so behaviour is correct and only the DECLARATION lies. |
| `:seon.agent.turn/evals` | `[:vector component ref]` | its one order consumer is `no-progress-streak`, whose failure mode is a false negative (a reordered pull resets the streak, so a run does not close at limit) |
| `:seon.agent.turn/timings` | `[:vector component ref]` | children carry `:seon.agent.turn.timing/ordinal` |
| `:seon.agent.message/to` | `[:vector ref]` | **no** — recipients are a set; this one is simply wrong |
| `:seon.render/children` | `[:vector component ref]` | render order matters; check for an explicit ordinal |
| `:portable.record/children` | `[:vector component map]` | check |

This is the population the vector-order audit predicted: *"at least thirteen
ordered declarations survive in namespaces the recurring test cannot load."* The
test can load them now.

**Each needs a verdict, not a bulk rewrite.** Where a child already stores an
ordinal, the declaration becomes `[:set …]` and nothing else changes. Where no
ordinal exists and order matters, the ordinal is the fix — never a tuple (caps at
8 values, kills element queries) and never a cardinality change.
