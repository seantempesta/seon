---
type: issue
status: open
severity: blocker
tags: [seon.db, q, contracts, silent-fallback, live-test]
created: 2026-09-15
---

# `seon.db/q` accepts a stray `[]` argument and returns `#{}` instead of refusing

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
