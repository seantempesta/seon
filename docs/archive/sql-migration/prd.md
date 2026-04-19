---
type: prd
status: completed
tags: [prd, archive, database]
---

> **Status: ARCHIVED** — Superseded by Datalevin migration

> **Status: ARCHIVED** — Superseded by Datalevin migration

# PRD: XTDB Query Architecture & Multi-Database Support

**Status**: Research Phase
**Created**: 2025-12-17
**Last Updated**: 2025-12-17

---

## Problem Statement

Seon needs a well-understood, high-performance query architecture that supports:

1. **Multi-database queries** - Separate XTDB nodes per domain with cross-domain join capability
2. **Frozen-in-time agent sessions** - Agents receive a query function locked to a specific time
3. **LLM-accessible domain code** - Domain namespaces use SQL which LLMs understand
4. **Rigorous data validation** - Malli schemas enforce contracts at function boundaries
5. **REPL-friendly development** - Instrumentation catches data issues immediately

---

## Goals

1. **Simple query interface** - `(query db "SELECT ...")` that handles temporal concerns internally
2. **Malli everywhere** - Function specs, data schemas, instrumentation in dev
3. **Preserve namespaced keywords** - Keep `:asset/ticker`, `:greeks/delta` style
4. **Auto-validate query results** - Schema validation on returned data where possible
5. **Domain code is temporally unaware** - Just calls query, doesn't know about time

---

## Key Design Decisions

### 1. Simple Query Wrapper (Not defrecord)

```clojure
;; System layer creates a bound query function
(defn create-query-fn [node as-of-time]
  (fn [sql & [params]]
    (node/sql-query node sql {:current-time as-of-time
                               :args params})))

;; Domain code just uses it - doesn't know implementation details
(defn iv-rank [query ticker lookback]
  (let [results (query "SELECT quote_iv FROM option_greeks WHERE asset_ticker = ?" [ticker])]
    ...))

;; Agent session setup
(let [query (create-query-fn node #inst "2025-07-15")]
  (iv-rank query "SPY" 126))

```

**Why**: Simpler than defrecord, domain code just sees a function, easy to test with mocks.

### 2. Malli Schema Enforcement

All domain functions have Malli schemas:

```clojure
(def IVRankArgs
  [:tuple
   [:=> [:cat :string] [:sequential :map]]  ; query fn
   :string                                   ; ticker
   :int])                                    ; lookback

(def IVRankResult
  [:double {:min 0.0 :max 1.0}])

(defn iv-rank
  {:malli/schema [:=> IVRankArgs IVRankResult]}
  [query ticker lookback]
  ...)

```

With instrumentation enabled, invalid inputs/outputs throw immediately in REPL.

### 3. Query Result Validation

**Research needed**: Can we automatically validate query results against schemas?

Options:
1. **Column-name based** - Infer schema from column names (`:asset/ticker` → string)
2. **Table-specific schemas** - Register schemas per table, validate on query
3. **Pass-through** - Unknown columns pass through, known columns validated

### 4. Namespaced Keywords Preservation

XTDB stores namespaced keywords. Research needed:
- How does SQL handle them? (`asset/ticker` vs `asset_ticker`)
- How does XTQL handle them?
- Can we maintain namespaced keywords in SQL results?

---

## Research Phases

### Phase 1: XTDB Internals ✅ COMPLETE

**Findings** (see `research/xtdb-internals.md`):
- XTQL and SQL converge to same execution path - zero performance difference
- Temporal filtering is scan-time, highly efficient
- Query-time wrapper is the right approach for frozen time
- Multi-database snapshot tokens work with temporal filtering

### Phase 2: Malli Integration & Data Flow

**Goal**: Understand how to enforce schemas throughout the query pipeline.

**Tasks**:
1. **Review current Malli setup** - What's in `seon.db.schema`? How's instrumentation configured?
2. **XTDB data handling** - How does XTQL return data? Namespaced keywords preserved?
3. **SQL column naming** - How are namespaced keywords mapped in SQL?
4. **Query result validation** - Can we auto-validate results against table schemas?
5. **Instrumentation patterns** - Best practices for dev-time validation

**Key Questions**:
- Does XTDB preserve `:asset/ticker` or convert to `asset_ticker`?
- Can we register Malli schemas per table and validate query results?
- How do we instrument domain functions for REPL feedback?

**Deliverable**: `research/malli-data-flow.md` with patterns and recommendations

### Phase 3: Query Wrapper Implementation

**Goal**: Build the simple query wrapper with frozen-time support.

**Tasks**:
1. Implement `create-query-fn`
2. Test temporal isolation
3. Handle both SQL and potentially XTQL
4. Document usage patterns

### Phase 4: Trading Domain Migration

**Goal**: Update trading code to use new patterns.

**Tasks**:
1. Remove `:as-of` parameters from all functions
2. Convert to SQL queries (or keep XTQL based on findings)
3. Add Malli schemas to all functions
4. Enable instrumentation
5. Test thoroughly

### Phase 5: Documentation

**Goal**: Update all docs with new patterns.

---

## Files to Examine

### Current Malli/Schema Setup

| File | Purpose |
|------|---------|
| `seon.db.schema` | Existing Malli schemas |
| `docs/prds/test-coverage-audit/research/malli-instrumentation.md` | Prior Malli research |
| `dev/user.clj` | Dev environment, instrumentation setup |

### XTDB Data Handling

| File | Purpose |
|------|---------|
| `seon.db.node` | Current query functions |
| `reference-code/xtdb/` | XTDB source for understanding data flow |

### Trading Domain

| File | Queries | Notes |
|------|---------|-------|
| `seon.trading.signals` | 8 | DSL primitives - need schemas |
| `seon.trading.analysis` | 3 | Agent analysis - need schemas |

---

## Success Criteria

1. **Query wrapper works** - Simple `(query "SELECT ...")` interface
2. **Temporal isolation** - Agents cannot see future data
3. **Malli instrumentation** - Invalid data caught at function boundaries in REPL
4. **Namespaced keywords** - Preserved through SQL queries (or documented workaround)
5. **Trading domain migrated** - All functions have schemas, use query wrapper
6. **All tests pass** - No regressions

---

## Open Questions

1. **Column naming in SQL** - Does `asset/ticker` become `asset_ticker` or stay namespaced?
2. **Result coercion** - Does XTDB return keywords as strings from SQL?
3. **Schema registry** - Should we register schemas per table for auto-validation?
4. **Instrumentation scope** - Instrument all functions or just domain boundaries?

---

## Research Outputs

- `research/xtdb-internals.md` ✅ Complete
- `research/malli-data-flow.md` - Phase 2 output
- `research/sql-patterns.md` - SQL conventions
- `research/frozen-time-pattern.md` - Implementation details
