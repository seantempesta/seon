---
type: research
status: completed
tags: [research, archive, database]
---

# Frozen-Time Database Pattern

**Status**: Not Started
**Goal**: Design a pattern where domain code receives a temporally-isolated database view

---

## Problem

Currently, domain functions accept temporal options and apply them internally:

```clojure
;; Current: temporal awareness leaked into domain
(defn iv-rank [db ticker lookback opts]
  (let [query-opts {:current-time (:as-of opts)}  ; <-- knows about time
        results (node/query db query opts)]
    ...))

```

We want domain functions to be temporally unaware:

```clojure
;; Target: domain just queries, time is handled elsewhere
(defn iv-rank [db ticker lookback]
  (let [results (query db "SELECT ...")] ; <-- no temporal awareness
    ...))

```

The system layer creates a "frozen" view and hands it to domain code.

---

## Requirements

1. **Domain isolation** - Domain code cannot see data after the freeze point
2. **Transparent** - Domain queries work normally without special syntax
3. **Performant** - Minimal overhead per query
4. **Consistent** - All queries in a session see the same point in time

---

## Candidate Approaches

### Approach 1: Wrapper Node with Default Time

Create a wrapper around the XTDB node that injects `:current-time` into every query:

```clojure
(defrecord FrozenNode [node as-of-time]
  ;; Implement same protocol as node but inject time
  ...)

(defn create-frozen-db [node as-of-time]
  (->FrozenNode node as-of-time))

```

**Pros**:
- Clean abstraction
- Works with existing query functions
- Domain code truly unaware

**Cons**:
- Must implement full node protocol
- Every query goes through wrapper

**Research needed**:
- What protocol(s) must we implement?
- Can we use `extend-type` or need full implementation?

### Approach 2: Query Function Wrapper

Instead of wrapping the node, wrap the query function:

```clojure
(defn make-frozen-query [node as-of-time]
  (fn [query-form]
    (node/query node query-form {:current-time as-of-time})))

;; Domain uses the wrapped function
(defn iv-rank [query-fn ticker lookback]
  (let [results (query-fn "SELECT ...")]
    ...))

```

**Pros**:
- Simple, no protocol implementation
- Explicit about what's happening

**Cons**:
- Changes function signatures (pass query-fn not db)
- Less idiomatic

### Approach 3: Dynamic Binding

Use Clojure's dynamic vars to set default time:

```clojure
(def ^:dynamic *default-time* nil)

(defn query [node q]
  (node/query node q {:current-time *default-time*}))

;; Usage
(binding [*default-time* #inst "2025-07-15"]
  (iv-rank db "SPY" 126))

```

**Pros**:
- Minimal code changes
- Clojure idiomatic

**Cons**:
- Implicit state (harder to reason about)
- Not thread-safe without care
- Domain could accidentally bypass

### Approach 4: XTDB Snapshot Tokens

Use XTDB's built-in snapshot mechanism:

```sql
SHOW SNAPSHOT_TOKEN;
-- Returns a token representing current state

BEGIN READ ONLY WITH (SNAPSHOT_TOKEN = 'abc123...');
-- All queries in this transaction see that snapshot

```

**Pros**:
- XTDB native, likely most performant
- Consistency guaranteed

**Cons**:
- Requires understanding token semantics
- May not support arbitrary past times?

**Research needed**:
- Can snapshot token represent an arbitrary past time?
- Or only current state?

### Approach 5: XTDB Session/Connection Default

Check if XTDB supports setting default temporal bounds at connection level:

```clojure
;; Hypothetical - need to verify if this exists
(def frozen-node
  (xtn/start-node {...
                   :default-valid-time #inst "2025-07-15"}))

```

**Research needed**:
- Does XTDB support connection-level temporal defaults?
- Check node configuration options

---

## Evaluation Criteria

| Criterion | Weight | Notes |
|-----------|--------|-------|
| Domain isolation | High | Must prevent future data access |
| Performance | High | Can't add significant overhead |
| Code simplicity | Medium | Prefer minimal changes |
| XTDB native | Medium | Built-in support preferred |
| Thread safety | Medium | Must work in concurrent scenarios |

---

## Research Tasks

1. [ ] Examine XTDB source for temporal filtering mechanism
2. [ ] Test snapshot token capabilities
3. [ ] Check XTDB node configuration for temporal defaults
4. [ ] Prototype Approach 1 (wrapper node)
5. [ ] Prototype Approach 4 (snapshot tokens)
6. [ ] Benchmark each approach

---

## Prototype Code

### Approach 1: Wrapper Node

```clojure
(ns seon.db.frozen
  "Temporally-frozen database views for agent sessions."
  (:require [seon.db.node :as node]
            [xtdb.protocols :as xtp]))

;; TODO: Determine which protocols to implement
;; Examine xtdb.protocols for IXtdbNode, IXtdbSubmitClient, etc.

(defrecord FrozenNode [node as-of-time]
  ;; Implement query protocol
  ;; Forward all queries with :current-time injected
  )

(defn create-session
  "Create a frozen database view for an agent session.

   The returned db will only show data with valid-time <= as-of-time.
   Agent cannot see future data even with explicit temporal queries."
  [node as-of-time]
  (->FrozenNode node as-of-time))

```

### Approach 4: Snapshot Tokens

```clojure
(ns seon.db.frozen
  "Temporally-frozen database views using XTDB snapshot tokens."
  (:require [seon.db.node :as node]))

(defn get-snapshot-at
  "Get a snapshot token representing the database at a specific time.

   TODO: Verify this is possible - snapshot tokens might only
   represent current state, not arbitrary past times."
  [node as-of-time]
  ;; Query with temporal bounds to establish snapshot?
  ;; Or use SHOW SNAPSHOT_TOKEN with temporal context?
  )

(defn query-with-snapshot
  "Execute query using a snapshot token for consistency."
  [node snapshot-token query]
  ;; BEGIN READ ONLY WITH (SNAPSHOT_TOKEN = ...)
  ;; Execute query
  ;; COMMIT
  )

```

---

## Findings

*(To be filled in during research)*

### Recommended Approach

TBD

### Implementation Details

TBD

### Performance Characteristics

TBD

---

## Final Design

*(To be filled in after research and prototyping)*
