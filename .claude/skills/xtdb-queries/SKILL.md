---
name: xtdb-queries
description: "XTDB database query patterns. Use when writing queries, debugging empty results, editing queries.clj or node.clj, working with from/where/aggregate clauses, or accessing option-greeks data. Use when you see XTQL, xt/template, ml-options.db namespace, or when queries return empty maps."
---

# XTDB Query Patterns

## Critical Rules

1. **NEVER use `[*]`** - Returns empty maps. Always list columns explicitly.
2. **Use `xt/template`** for dynamic values - Not string interpolation.
3. **Use our wrapper** - `ml-options.db.node/query`, not `xt/q` directly.

## Correct Pattern

```clojure
(require '[ml-options.db.node :as node])
(require '[xtdb.api :as xt])

;; Simple query - list columns explicitly
(node/query (user/xtdb-node)
  '(from :option-greeks [asset/ticker quote/iv]))

;; With dynamic values - use xt/template
(node/query (user/xtdb-node)
  (xt/template
    (from :option-greeks [{:asset/ticker ~ticker} quote/iv])))
```

## Common Operations

```clojure
;; Filter
(-> (from :option-greeks [asset/ticker quote/iv])
    (where (> quote/iv 0.3)))

;; Aggregate
(-> (from :option-greeks [asset/ticker xt/id])
    (aggregate {:cnt (count xt/id)} asset/ticker))

;; Order and limit
(-> (from :option-greeks [asset/ticker quote/iv])
    (order-by [[:quote/iv :desc]])
    (limit 10))
```

## Option Greeks Schema

| Column | Type | Description |
|--------|------|-------------|
| `asset/ticker` | string | Underlying (AAPL, SPY) |
| `quote/iv` | double | Implied volatility |
| `quote/delta` | double | Delta |
| `quote/gamma` | double | Gamma |
| `quote/theta` | double | Theta |
| `xt/id` | string | Unique ID |

## Key Files

| File | Purpose |
|------|---------|
| `src/ml_options/db/node.clj` | Query wrapper |
| `src/ml_options/db/queries.clj` | Domain queries |

## For More Details

See `docs/reference/xtdb-v2-reference.md` for temporal queries and full XTQL reference.
