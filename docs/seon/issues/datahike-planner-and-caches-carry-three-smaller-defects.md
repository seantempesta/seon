---
type: issue
status: open
severity: friction
tags: [issue, database, datahike]
---

# Three smaller defects in the vendored Datahike, found beside the card-many scan bug

All three surfaced while tracking
`archive/booted-block-derivation-returns-one-of-four.md`. None of them produces
a wrong answer today; each is a live trap. Owner is our fork,
`reference-code/datahike` — the roster-race precedent (`357ffc87`) says we fix
these ourselves.

## 1. Plan selection depends on variable-symbol identity

For two clause sets identical except for variable names, the planner picks a
different pattern for the entity-group's scan position. Against one booted
cluster and one database value:

```text
[?agent :…/id "root"] [?agent :…/blocks ?block]  → scan = the :blocks pattern
[?a     :…/id "root"] [?a     :…/blocks ?b]      → scan = the :id pattern
```

Same costs, same schema, same data; the plan cache was cleared explicitly and
the rebuilt plans still differed. Something in plan construction (`logical` →
`lower` → `plan/dp-order-fuse-ops`) is ordered by a hash-ordered collection
keyed by the variable symbols, so a cost tie is broken by symbol hash. A plan
that changes with the reader's choice of variable name is unreviewable and
makes every planner bug look intermittent — this is what made the card-many
scan bug read as "probabilistic across boots" for a whole session.

Acceptance: for a fixed database value and schema, `create-plan-via-ir` returns
the same plan structure under a consistent renaming of the query's variables.
A property test over alpha-renamed queries is the natural shape.

## 2. `*query-result-cache?*` is a dial that does nothing

`reference-code/datahike/src/datahike/query.cljc:72` declares and documents
`*query-result-cache?*` ("Bind to false for benchmarking raw query
execution"). Nothing ever reads it — the only two occurrences in the file are
the `def` and its docstring. Binding it false silently changes nothing, so any
benchmark that used it measured the cache. `clear-query-cache!` is the working
operation.

Acceptance: either the binding suppresses cache reads and writes, or the var is
deleted and the docstring points at `clear-query-cache!`. Not both, and not a
dial that lies.

## 3. `execute-card-many-merge`'s CLJ branch runs both merge paths

`reference-code/datahike/src/datahike/query/execute.cljc`, in
`execute-card-many-merge`: the CLJS branch is `(if card-many? <slice-path>
<lookupGE-path>)`, while the CLJ branch is `(if card-many? <slice-path>)`
followed by the `lookupGE` path as a second body form of the enclosing `let`.
So on the JVM a card-many merge does its correct cross-product recursion AND
then the card-one cursor probe, emitting one extra tuple per scan datom and
advancing a shared forward cursor it had no business touching. The results
happen to be deduplicated into the same set, which is why nothing is red.

Acceptance: the two branches have the same shape, and a test asserts the emit
count (not just the result set) for a card-many merge.
