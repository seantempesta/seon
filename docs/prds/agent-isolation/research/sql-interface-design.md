# Agent SQL Interface Design

**Date**: 2026-01-06
**Status**: Proposal for review

## Problem

The PRD mixes XTDB APIs inconsistently:
- XTQL syntax: `(xt/execute-tx db [[:put-docs :signals {...}]])`
- SQL syntax: `(xt/q db "SELECT * FROM signals")`

Agents likely know SQL better than XTQL. We need a clean, intuitive interface.

## Key Insight: Column Name Mapping

XTDB maps namespaced keywords to SQL columns using `$` as separator:

| Clojure Keyword | SQL Column |
|-----------------|------------|
| `:xt/id` | `_id` |
| `:signal/symbol` | `signal$symbol` |
| `:seon.trading/direction` | `seon.trading$direction` |

Results come back as keywords automatically.

## Design Decision: SQL Only

**Rationale:**
1. Agents know SQL - it's universal
2. SQL is XTDB v2's primary interface
3. One syntax to learn, not two
4. Easier to document and debug

## Agent Database Isolation

Each agent gets their own XTDB database:
- `seon.trading` namespace → `seon_trading` database
- Agent uses simple table names (no prefix needed)
- Tables are scoped to their database automatically

## Proposed Agent Helpers

Provide two simple functions that use `*ctx*` implicitly:

```clojure
;; sql - for queries (returns vector of maps with keyword keys)
(sql "SELECT * FROM signals")
;; => [{:xt/id "sig-1" :symbol "AAPL" :direction "long"}]

(sql "SELECT * FROM signals WHERE symbol = ?" ["AAPL"])
;; => [{:xt/id "sig-1" :symbol "AAPL" :direction "long"}]

;; sql! - for writes (returns transaction result)
(sql! "INSERT INTO signals (_id, symbol, direction) VALUES (?, ?, ?)"
      ["sig-1" "AAPL" "long"])

;; Batch insert - multiple param vectors
(sql! "INSERT INTO signals (_id, symbol, direction) VALUES (?, ?, ?)"
      ["sig-1" "AAPL" "long"]
      ["sig-2" "TSLA" "short"])

;; Update
(sql! "UPDATE signals SET direction = ? WHERE _id = ?" ["short" "sig-1"])

;; Delete
(sql! "DELETE FROM signals WHERE _id = ?" ["sig-1"])
```

## Implementation

```clojure
(ns seon.agent.helpers
  "SQL helpers for agents. These use *ctx* implicitly."
  (:require [xtdb.api :as xt]
            [seon.orchestrator.nrepl :refer [*ctx*]]))

(defn sql
  "Query the agent's database. Returns vector of maps with keyword keys.

   Examples:
     (sql \"SELECT * FROM signals\")
     (sql \"SELECT * FROM signals WHERE symbol = ?\" [\"AAPL\"])"
  ([query]
   (sql query nil))
  ([query params]
   (let [db (:seon.agent/db @*ctx*)]
     (if params
       (xt/q db (into [query] params) {:key-fn identity})
       (xt/q db query {:key-fn identity})))))

(defn sql!
  "Execute a write statement. Returns transaction result.

   Examples:
     (sql! \"INSERT INTO signals (_id, symbol) VALUES (?, ?)\" [\"sig-1\" \"AAPL\"])
     ;; Batch:
     (sql! \"INSERT INTO ...\" [\"sig-1\" \"AAPL\"] [\"sig-2\" \"TSLA\"])"
  [stmt & param-vectors]
  (let [db (:seon.agent/db @*ctx*)]
    (xt/execute-tx db [(into [:sql stmt] param-vectors)])))
```

## Reserved Keys in ctx

Updated reserved keys:

```clojure
{:seon.agent/namespace   'seon.trading       ; Read-only identity
 :seon.agent/db          <xtdb-connection>   ; Direct XTDB access (escape hatch)
 :seon.agent/render      (fn [hiccup] ...)   ; Push UI updates (Phase 5)
 :seon.agent/worktree    "/path/to/worktree" ; Git worktree path (Phase 6)}
```

**Note:** `sql` and `sql!` are functions in the `seon.agent.helpers` namespace,
automatically required in agent sessions. They read from `*ctx*` implicitly.

## Table Design Conventions

Agents design their own tables. Conventions:

1. **Use `_id` for primary key** (maps to `:xt/id`)
2. **Use snake_case for table names** (e.g., `active_signals`, `trade_history`)
3. **Use snake_case for columns** (e.g., `iv_rank`, `created_at`)
4. **Or use namespaced columns** for domain clarity (e.g., `signal$symbol` → `:signal/symbol`)

Example agent table:
```sql
-- Simple flat table
INSERT INTO signals (_id, symbol, direction, iv_rank, created_at)
VALUES ('sig-1', 'AAPL', 'long', 0.85, CURRENT_TIMESTAMP)

-- Query returns:
-- {:xt/id "sig-1" :symbol "AAPL" :direction "long" :iv_rank 0.85 :created_at #inst "..."}
```

## Example Agent Workflow

```clojure
;; Agent code (evaluated in seon.trading namespace via nREPL)

;; Store signals in SQL table
(sql! "INSERT INTO signals (_id, symbol, direction, iv_rank) VALUES (?, ?, ?, ?)"
      ["sig-1" "AAPL" "long" 0.92]
      ["sig-2" "TSLA" "short" 0.78])

;; Query signals
(def active (sql "SELECT * FROM signals WHERE direction = ?" ["long"]))
;; => [{:xt/id "sig-1" :symbol "AAPL" :direction "long" :iv_rank 0.92}]

;; Store analysis state in ctx (automatically persisted)
(swap! *ctx* assoc :seon.trading/current-analysis
       {:signals active
        :timestamp (java.util.Date.)
        :notes "High IV environment"})

;; Render to UI
((:seon.agent/render @*ctx*)
  [:div#signals-panel
   [:h2 "Active Signals"]
   (for [s active]
     [:div.signal (:symbol s) " - " (:direction s)])])
```

## Migration from PRD Examples

| Old (PRD) | New (SQL-only) |
|-----------|----------------|
| `(xt/execute-tx db [[:put-docs :signals {...}]])` | `(sql! "INSERT INTO signals ..." [...])` |
| `(xt/q db "SELECT * FROM signals")` | `(sql "SELECT * FROM signals")` |
| `:signals` (bare key in ctx) | `:seon.trading/signals` (namespaced) |

## Open Questions

1. **Should `sql` support `:key-fn`?** Currently uses `identity` (preserves keywords).
   Could offer `:kebab-case-keyword` option.

2. **Error messages** - Should `sql!` validate SQL syntax before sending to XTDB?
   Probably not - let XTDB provide errors, they're usually clear.

3. **Table creation** - XTDB v2 creates tables implicitly on first INSERT.
   Should we document this or provide a `create-table!` helper?
