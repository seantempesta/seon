---
type: research
status: completed
tags: [prd, research, database]
---
# Temporal Strategy: Handling Time-Travel Without Bitemporality

This document analyzes each temporal use case in Seon and proposes migration strategies for Datalevin, which lacks XTDB's built-in bitemporality.

---

## Summary

| File | Current Usage | Recommendation |
|------|---------------|----------------|
| `agent/ctx.clj` | Full bitemporality (system-time travel, history) | Option B: Append-only snapshots |
| `db/queries.clj` | Valid-time filtering for historical IV | Option A: Explicit `:recorded-at` column |
| `trading/signals.clj` | `:current-time` for backtesting lockdown | Option A: Explicit `:quote/recorded-at` |
| `trading/bulk_load.clj` | `:xt/valid-from` for historical data | Option A: Explicit `:quote/recorded-at` |
| `primer/ctx.clj` | Point-in-time queries, history | Option B: Append-only snapshots |
| `dev/context.clj` | `_valid_from` for ordering/filtering | Option A: Explicit `:created-at` |
| `db/node.clj` | Generic `entity-history` wrapper | Provide both strategies |

---

## Detailed Analysis

### 1. `seon.agent.ctx` - Agent Context Time-Travel

**Current Usage:**

```clojure
;; Point-in-time query using SYSTEM_TIME
(at {::db conn ::namespace 'seon.trading ::instant #inst "2026-01-04T10:00"})
;; Returns ctx state AS IT WAS RECORDED at that time

;; Full history
(history {::db conn ::namespace 'seon.trading})
;; Returns all snapshots with ::system-time

```

**Why It Needs Time Travel:**

- Agents can "go back in time" to debug state changes
- Recovery: restore to a known-good state
- Auditing: see what the agent's context looked like at any point
- Uses SYSTEM_TIME (when recorded), not VALID_TIME (when true)

**Impact Assessment:** **HIGH** - Core debugging/recovery feature

**Proposed Strategy: Option B (Append-only snapshots)**

Instead of relying on XTDB's system-time:

1. Every `swap!` persists a new row with explicit `:snapshot/recorded-at`
2. Never update rows, only insert new ones
3. `(at instant)` becomes `WHERE recorded-at <= ? ORDER BY recorded-at DESC LIMIT 1`
4. `(history)` becomes `SELECT * ORDER BY recorded-at`

```clojure
;; New Datalevin schema
{:ctx/namespace {:db/valueType :db.type/string :db/index true}
 :ctx/recorded-at {:db/valueType :db.type/instant :db/index true}
 :ctx/state {:db/valueType :db.type/string}} ; EDN serialized

;; Query at time
(d/q '[:find (pull ?e [*]) .
       :in $ ?ns ?as-of
       :where
       [?e :ctx/namespace ?ns]
       [?e :ctx/recorded-at ?t]
       [(<= ?t ?as-of)]]
     db namespace instant)

```

**Trade-offs:**

- (+) Full history preserved
- (+) Point-in-time queries work identically
- (+) No magic - explicit timestamps
- (-) More storage (every change = new row)
- (-) Need to handle cleanup/compaction eventually

**Migration Effort:** Medium - rewrite `at`, `history`, `restore!` functions

---

### 2. `seon.db.queries` - Historical IV Time Series

**Current Usage:**

```clojure
(defn historical-ivs [node ticker lookback-days]
  (xt/q node
    ["SELECT quote$iv, _valid_from
      FROM option_greeks FOR ALL VALID_TIME
      WHERE asset$ticker = ?
        AND _valid_from >= ?"
     ticker start-date]))

```

**Why It Needs Time Travel:**

- Build IV percentile ranks from historical data
- Compare current IV to 252-day rolling history
- Calculate where current IV sits vs historical distribution

**Impact Assessment:** **MEDIUM** - Trading analytics feature

**Proposed Strategy: Option A (Explicit timestamp column)**

The bulk loader already sets `:xt/valid-from` explicitly. Rename to `:quote/recorded-at`:

```clojure
;; Datalevin schema
{:quote/recorded-at {:db/valueType :db.type/instant :db/index true}}

;; Query historical IVs
(d/q '[:find ?iv ?recorded-at
       :in $ ?ticker ?since
       :where
       [?e :asset/ticker ?ticker]
       [?e :quote/iv ?iv]
       [?e :quote/recorded-at ?t]
       [(>= ?t ?since)]]
     db ticker start-date)

```

**Trade-offs:**

- (+) Simple, explicit, no magic
- (+) Already have the data (just different column name)
- (+) Composable with other predicates
- (-) Slightly more verbose queries
- (-) Must remember to set timestamp on insert

**Migration Effort:** Low - rename column, update queries

---

### 3. `seon.trading.signals` - Backtesting Lockdown

**Current Usage:**

```clojure
(defn iv-rank [db ticker lookback {:keys [as-of]}]
  (xt/q db
    ["SELECT quote$iv, _valid_from FROM option_greeks
      WHERE asset$ticker = ? ..."
     ticker]
    {:current-time as-of}))  ; <-- XTDB temporal lockdown

```

**Why It Needs Time Travel:**

- Backtesting must only see data available at simulation time
- `{:as-of #inst "2025-07-15"}` restricts to data with valid-time <= that instant
- Prevents look-ahead bias in trading simulations

**Impact Assessment:** **HIGH** - Critical for backtesting correctness

**Proposed Strategy: Option A (Explicit filtering)**

With explicit `:quote/recorded-at`, backtesting becomes explicit filtering:

```clojure
(defn iv-rank [conn ticker lookback {:keys [as-of]}]
  (d/q '[:find ?iv
         :in $ ?ticker ?as-of
         :where
         [?e :asset/ticker ?ticker]
         [?e :quote/iv ?iv]
         [?e :quote/recorded-at ?t]
         [(<= ?t ?as-of)]]  ; <-- Explicit temporal filter
       (d/db conn) ticker as-of))

```

**Trade-offs:**

- (+) Explicit is better than implicit
- (+) No hidden temporal magic to understand
- (+) Easier to debug ("why don't I see this data?")
- (-) Every query needs the filter (can abstract)
- (-) Easy to forget (but causes obvious bugs)

**Migration Effort:** Medium - update all signal primitives

---

### 4. `seon.trading.bulk_load` - Historical Data Insertion

**Current Usage:**

```clojure
;; Sets explicit valid-from for historical data
{:xt/id "SPY..."
 :xt/valid-from #inst "2024-11-25T22:00:00Z"  ; Quote date
 :quote/iv 0.15
 ...}

```

**Why It Needs Time Travel:**

- Loading historical data (e.g., 6 months of options quotes)
- Each quote has a "quote date" that's the business date
- Backtesting needs to filter by this date

**Impact Assessment:** **LOW** - Just rename the column

**Proposed Strategy: Option A (Explicit column)**

Simply use `:quote/recorded-at` instead of `:xt/valid-from`:

```clojure
{:db/id (str occ-symbol "@" timestamp)
 :quote/recorded-at #inst "2024-11-25T22:00:00Z"
 :quote/iv 0.15
 ...}

```

**Trade-offs:**

- (+) Trivial migration
- (+) Makes timestamp purpose clearer
- (-) None significant

**Migration Effort:** Low - rename field in transformer

---

### 5. `seon.primer.ctx` - Session History

**Current Usage:**

```clojure
;; Point-in-time query
(db/entity @primer-node :primer-sessions session-id
           {:current-time as-of-instant})

;; Full history
(db/entity-history @primer-node :primer-sessions session-id)

```

**Why It Needs Time Travel:**

- View session state at any checkpoint
- Restore to previous state for recovery
- Debug "what did this session look like an hour ago?"

**Impact Assessment:** **MEDIUM** - Same pattern as agent/ctx

**Proposed Strategy: Option B (Append-only snapshots)**

Same approach as `agent/ctx.clj`:

```clojure
;; Every checkpoint creates new row
{:session/id "abc"
 :session/checkpointed-at #inst "2026-01-28T10:00:00Z"
 :session/state "{:foo 1 :bar 2}"}  ; EDN

```

**Trade-offs:**

- (+) Consistent with agent/ctx approach
- (+) Full history preserved
- (-) More storage

**Migration Effort:** Medium - same as agent/ctx

---

### 6. `seon.dev.context` - Hook Event Ordering

**Current Usage:**

```clojure
;; Events ordered by XTDB's _valid_from
"SELECT *, _valid_from FROM edit_event ORDER BY _valid_from"

;; Time range filtering
"SELECT * FROM review_event
 WHERE _valid_from >= ? AND _valid_from <= ?"

```

**Why It Needs Time Travel:**

- Order events chronologically
- Query events in time ranges
- "Edits since last review" uses _valid_from comparison

**Impact Assessment:** **LOW** - Only uses auto-set valid_from for ordering

**Proposed Strategy: Option A (Explicit :created-at)**

XTDB sets `_valid_from` automatically on insert. We just need to set it ourselves:

```clojure
;; On insert
{:edit/id (random-uuid)
 :edit/file "/path/to/file.clj"
 :edit/created-at (java.time.Instant/now)}  ; <-- Explicit

;; Query
(d/q '[:find (pull ?e [*])
       :in $ ?since
       :where
       [?e :edit/created-at ?t]
       [(> ?t ?since)]]
     db last-review-time)

```

**Trade-offs:**

- (+) Trivial migration
- (+) More explicit
- (-) Must remember to set timestamp

**Migration Effort:** Low - add explicit timestamp to inserts

---

### 7. `seon.db.node` - Generic Entity History

**Current Usage:**

```clojure
(defn entity-history [node table id]
  "SELECT * FROM table FOR ALL VALID_TIME WHERE _id = ?")

```

**Why It Needs Time Travel:**

- Generic wrapper used by primer/ctx.clj
- Returns all versions of an entity

**Impact Assessment:** **LOW** - Abstraction layer

**Proposed Strategy: Depends on caller**

This is infrastructure. The migration strategy depends on what the callers need:

- For append-only tables (ctx, sessions): query by entity-id, order by timestamp
- For non-temporal tables: not applicable

```clojure
;; For append-only entities
(defn entity-history [conn entity-type entity-id]
  (d/q '[:find (pull ?e [*])
         :in $ ?type ?id
         :where
         [?e :entity/type ?type]
         [?e :entity/id ?id]]
       (d/db conn) entity-type entity-id))

```

**Migration Effort:** Low - callers define the pattern

---

## Summary of Strategies

### Option A: Explicit Timestamp Columns

**Use for:** Trading data, dev events, any "facts about the world"

- Add `:recorded-at`, `:created-at`, or `:quote/date` column
- Set explicitly on insert (no magic)
- Filter explicitly in queries
- Similar to how relational DBs work

**Affected files:**

- `db/queries.clj` - `:quote/recorded-at`
- `trading/signals.clj` - use `:quote/recorded-at` filter
- `trading/bulk_load.clj` - rename `:xt/valid-from` → `:quote/recorded-at`
- `dev/context.clj` - `:created-at` on events

### Option B: Append-Only with Explicit Snapshots

**Use for:** Agent context, session state - anything needing "what was the state at time T?"

- Never update, only insert new rows
- Each row has `:recorded-at` or `:checkpointed-at`
- Point-in-time = latest row where `recorded-at <= as-of`
- History = all rows ordered by `recorded-at`

**Affected files:**

- `agent/ctx.clj` - ctx snapshots
- `primer/ctx.clj` - session checkpoints

### Option C: Git Snapshots

**Not needed for any current use case.** All temporal needs can be met with A or B.

Git snapshots remain useful for:

- Disaster recovery (backup the data directory)
- Major version rollbacks

### Option D: Drop the Feature

**Not applicable.** All temporal features are actively used:

- Trading data: IV percentiles, backtesting
- Agent context: debugging, recovery
- Dev events: ordering, ranges

---

## Migration Path

### Phase 1: Trading Domain

1. Add `:quote/recorded-at` to schema
2. Update bulk_load transformer to use new field name
3. Update signals.clj to filter explicitly
4. Update db/queries.clj IV time series

### Phase 2: Agent Context

1. Implement append-only persistence in agent/ctx.clj
2. Migrate `at`, `history`, `restore!` functions
3. Apply same pattern to primer/ctx.clj

### Phase 3: Dev Events

1. Add `:created-at` to edit_event, review_event, todo_event
2. Update record-* functions to set timestamp
3. Update query functions to use explicit column

---

## Recommended Schema Design

```clojure
;; Trading quotes (Option A)
{:asset/ticker {:db/valueType :db.type/string :db/index true}
 :quote/iv {:db/valueType :db.type/double}
 :quote/recorded-at {:db/valueType :db.type/instant :db/index true}}

;; Agent context snapshots (Option B)
{:ctx/namespace {:db/valueType :db.type/string :db/index true}
 :ctx/recorded-at {:db/valueType :db.type/instant :db/index true}
 :ctx/state {:db/valueType :db.type/string}}

;; Dev events (Option A)
{:edit/file {:db/valueType :db.type/string}
 :edit/created-at {:db/valueType :db.type/instant :db/index true}}

```

---

## Conclusion

Seon's temporal requirements are well-defined and can be handled without bitemporality:

1. **Trading backtesting** needs "data as of time T" → explicit `:recorded-at` column
2. **Agent debugging** needs "state as of time T" → append-only snapshots
3. **Event ordering** needs "when did this happen" → explicit `:created-at`

The key insight is that XTDB's bitemporality is convenient but not essential. Making timestamps explicit actually improves clarity and debuggability - you always know exactly what timestamp is being used because you wrote the query.
