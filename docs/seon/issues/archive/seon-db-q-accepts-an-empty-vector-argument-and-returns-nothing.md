---
type: issue
status: resolved
severity: blocker
tags: [issue, seon.db, q, contracts, silent-fallback, live-test]
created: 2026-09-15
resolved: 2026-09-15
---

# `seon.db/q` accepts a stray `[]` argument and returns `#{}` instead of refusing

## Resolution

q now declares and arms a Malli function guard over the parsed `:in` count
and database source positions, for positional and argument-map calls. The
read seam independently refuses the same malformed inputs before Datahike
runs. Errors name `:in`, the supplied arguments, and both valid call shapes.
Pull/pull-many and datoms guards reject ambiguous or ignored trailing inputs;
entity's existing fixed arities already reject them. Both complete run-6
source strings are retained in `test/seon/run6_db_test.clj`.

The isolated DB/instrumentation/loop/help/grammar gate passed 78 tests and
864 assertions. Live SCI returned the guard refusal instead of an empty
result. Full evidence and the dated broad-input inventory are in
[the landing note](../../prds/context-generation/research/run6-blockers-landing-2026-09-15.md).

## Observed (live run 6, default, 2026-09-15 14:45Z)

The opening teaches `(seon.db/q '[… :in $ [?attribute ...] …] [:example/amount …])`.
The model generalised "second argument = inputs" and wrote, for queries
with no `:in`:

```clojure
(seon.db/q '[:find ?id ?customer ?amount :where [?e :example/order ?id] …] [])
(seon.db/q '[:find (pull ?e [*]) :where [?e :example/order _]] [])
```

Both returned `#{}` / `[]` with no error. The same queries without the
`[]` return the four orders. The model concluded "the entities with
:example/order, :example/customer, :example/amount are not the same
entity" and spent its turns investigating a database that was fine.

## Why

The elided-db arity takes the first positional argument as the database
value or as inputs depending on shape; an empty vector is admitted as an
input for a query that declares no `:in`, and Datahike returns nothing.
A silent fallback that happens to run is the defect class named in
AGENTS.md §2.4.

## Wanted

- `seon.db/q` validates the argument count against the query's `:in`
  (default `$` only): extra or missing inputs are a flat `:seon.error`
  naming the query's `:in`, the supplied argument, and the two correct
  calls (elided db, or explicit db first). Regression with both run-6
  forms.
- The same for `seon.db/pull` and `pull-many` shapes.
