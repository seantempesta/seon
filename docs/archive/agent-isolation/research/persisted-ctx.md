---
type: research
status: completed
tags: [research, archive, agent]
---

# Persisted Context Research

**Author**: Claude (research agent)
**Date**: 2026-01-04
**Status**: Complete

## Executive Summary

**Recommendation: Use a debounced agent-based approach with full snapshots.**

Key findings:
1. **Non-blocking is achievable**: `add-watch` + Clojure `agent` adds only ~660 ns overhead to `swap!` (from 97ns to 757ns) - effectively imperceptible
2. **Debouncing dramatically reduces writes**: 100 rapid updates collapse to 1 persist (99% reduction)
3. **Full snapshots are fine for agent ctx**: Typical ctx is 5-20KB - XTDB handles this efficiently
4. **XTDB bitemporality works out-of-the-box**: `FOR ALL SYSTEM_TIME` queries give full history for free
5. **Diffs add complexity without proportional benefit**: Given debouncing, storage savings are minimal

The recommended approach:
- Standard Clojure `atom` with `add-watch`
- `send-off` to a Clojure `agent` for async writes
- Configurable debounce window (default 100ms)
- Full ctx snapshots stored in XTDB table
- Malli validation inline (synchronous) before persistence

---

## Benchmarks

### Test Environment

- Apple M-series Mac
- XTDB v2 with local storage
- JVM with default settings
- Seon running system

### Raw Latency Numbers

| Operation | Latency | Notes |
|-----------|---------|-------|
| Plain atom `swap!` | **97 ns/op** | Baseline reference |
| Atom + `add-watch` + agent | **757 ns/op** | Non-blocking async (~7.8x baseline) |
| Atom + debounce (scheduler) | ~95,303 ns/op | Includes ScheduledExecutor overhead |
| XTDB `execute-tx` (sync) | **7.1 ms/op** | Blocks until indexed |
| XTDB `submit-tx` (async) | **6.35 ms/op** | Returns after log write |
| XTDB batch (10 docs/tx) | ~2.8 ms/doc | Batching amortizes overhead |
| XTDB temporal query | **1.0 ms/op** | `FOR ALL SYSTEM_TIME` |
| Malli validation (compiled) | **1,631 ns/op** | Pre-compiled validator |
| EDN `pr-str` (7KB ctx) | ~113 us/op | Medium-sized context |

### Key Observations

1. **Agent overhead is minimal**: 757ns vs 97ns = 660ns overhead, which is 0.00066ms - completely imperceptible to users.

2. **XTDB sync writes are expensive**: 7.1ms is too slow to block `swap!`. This confirms we MUST use async.

3. **Debouncing is highly effective**: 100 rapid updates resulted in only 1 actual persist when using a 50ms debounce window.

### Benchmark Code Used

```clojure
;; Plain atom baseline
(let [a (atom {:x 0})
      iterations 10000]
  (time (dotimes [_ iterations]
          (swap! a assoc :x (rand)))))
;; => 966.92 us total, 97 ns/op

;; Atom with add-watch + agent
(let [write-agent (agent nil)
      watched-atom (atom {:x 0})
      iterations 10000]
  (add-watch watched-atom ::persist
    (fn [_ _ old new]
      (when (not= old new)
        (send-off write-agent (fn [_] nil)))))
  (time (dotimes [i iterations]
          (swap! watched-atom assoc :x i))))
;; => 7571.71 us total, 757 ns/op

;; XTDB sync write
(with-namespace-db node 'seon.dev
  (fn [conn]
    (time (dotimes [i 50]
            (xt/execute-tx conn [[:sql "INSERT INTO bench (_id, value) VALUES (?, ?)"
                                  [(str "bench-" i) i]]])))))
;; => 354.93 ms total, 7.10 ms/op

```

---

## Non-Blocking Approaches

### Option 1: Clojure Agent (RECOMMENDED)

**How it works**:
- `add-watch` on atom triggers on every state change
- Watch function uses `send-off` to queue persist to an agent
- Agent processes writes sequentially on a cached thread pool
- `swap!` returns immediately - persistence happens in background

**Pros**:
- Standard library, no dependencies
- Sequential write guarantee (no race conditions)
- Error handling via `agent-error` / `set-error-handler!`
- Can await completion when needed

**Cons**:
- Every update queues a write (mitigated by debouncing)
- No built-in backpressure

```clojure
(def persist-agent (agent nil))

(add-watch ctx-atom ::persist
  (fn [_ _ old new]
    (when (not= old new)
      (send-off persist-agent
        (fn [_]
          (persist-to-xtdb! new)
          nil)))))

```

### Option 2: core.async Channel + Sliding Buffer

**How it works**:
- Watch puts new values to a channel with `sliding-buffer 1`
- Background go-loop consumes and persists
- Sliding buffer means only latest value retained if writes fall behind

**Pros**:
- Built-in backpressure via buffer
- Natural debouncing with timeouts
- More control over persistence timing

**Cons**:
- Requires core.async dependency
- More complex error handling
- go-loops can be tricky to manage lifecycle

```clojure
(def persist-ch (a/chan (a/sliding-buffer 1)))

(add-watch ctx-atom ::persist
  (fn [_ _ _ new]
    (a/put! persist-ch new)))

(a/go-loop []
  (when-let [v (a/<! persist-ch)]
    (a/<! (a/timeout 100)) ;; debounce
    (persist-to-xtdb! v)
    (recur)))

```

### Option 3: ScheduledThreadPoolExecutor + Debounce

**How it works**:
- Watch schedules a future persist task
- Each new update cancels previous pending task
- Only the task after "quiet period" actually executes

**Pros**:
- True debouncing (coalesces rapid updates)
- Fine-grained timing control
- Standard Java concurrency

**Cons**:
- More complexity (managing scheduled tasks)
- Need to track pending value separately
- Higher per-operation overhead (95us vs 757ns)

```clojure
(def scheduler (Executors/newSingleThreadScheduledExecutor))
(def pending (atom nil))
(def scheduled-task (atom nil))

(add-watch ctx-atom ::persist
  (fn [_ _ _ new]
    (reset! pending new)
    (when-let [task @scheduled-task]
      (.cancel task false))
    (reset! scheduled-task
      (.schedule scheduler
        #(when-let [v @pending]
           (reset! pending nil)
           (persist-to-xtdb! v))
        100
        TimeUnit/MILLISECONDS))))

```

### Comparison Matrix

| Approach | Latency Overhead | Write Coalescing | Complexity | Recommendation |
|----------|------------------|------------------|------------|----------------|
| Agent | ~660 ns | None (all queued) | Low | **Use with debounce wrapper** |
| core.async | ~1-2 us | Via sliding-buffer | Medium | Good alternative |
| Scheduler | ~95 us | True debounce | Medium-High | Overkill for this use case |

### Recommended Hybrid: Agent + Debounce

Combine agent simplicity with debounce efficiency:

```clojure
(defn make-debounced-persister [persist-fn debounce-ms]
  (let [pending (atom nil)
        scheduled (atom nil)
        executor (Executors/newSingleThreadScheduledExecutor)
        agent (agent nil)]

    (fn [new-value]
      (reset! pending new-value)
      (when-let [task @scheduled]
        (.cancel task false))
      (reset! scheduled
        (.schedule executor
          #(when-let [v @pending]
             (reset! pending nil)
             (send-off agent (fn [_] (persist-fn v) nil)))
          debounce-ms
          TimeUnit/MILLISECONDS)))))

```

---

## Storage Efficiency

### Full Snapshots vs Diffs

**Tested payload sizes**:
- Small ctx: 102 bytes
- Medium ctx (50 signals, 20 positions): 6,970 bytes (~7KB)
- Large ctx (+ 100 history entries): 21,416 bytes (~21KB)

**Storage comparison for 10 incremental updates**:

| Strategy | Total Storage | Notes |
|----------|---------------|-------|
| Full snapshots | 2,978 bytes | 10 complete states |
| Diffs only | 512 bytes | ~6x smaller |

**BUT with debouncing** (50ms window, typical agent usage):
- 100 rapid updates -> 1-3 actual persists
- Storage difference becomes negligible

### XTDB Document Storage

XTDB stores documents as immutable facts with system-time versioning:
- Each `execute-tx` creates a new row version
- Old versions are retained (never deleted)
- Queries default to current time; `FOR ALL SYSTEM_TIME` gets history
- Compaction optimizes storage but preserves all versions

**Key insight**: XTDB is already optimized for versioned document storage. Implementing our own diff format would duplicate this functionality.

### editscript Library Assessment

The `editscript` library provides optimized diffing for Clojure data structures:

| Algorithm | Speed | Diff Size | Best For |
|-----------|-------|-----------|----------|
| A* (default) | Slow | Minimal | Storage optimization |
| Quick | 100x faster | Good enough | High-frequency ops |

For nested maps with hundreds of nodes, Quick algorithm takes 2-4ms.

**Verdict**: Not needed. Given debouncing + XTDB's built-in versioning, the added complexity isn't justified.

### Recommendation: Full Snapshots

Store complete ctx state on each persist because:
1. Simpler implementation (no diff/patch logic)
2. Time-travel queries return usable state directly
3. Debouncing already minimizes persist frequency
4. 7-20KB per snapshot is trivial for modern storage
5. XTDB handles versioning natively

---

## XTDB Patterns for State History

### Table Structure

```sql
-- Single table for all namespace ctx snapshots
CREATE TABLE ctx_snapshots (
  _id UUID PRIMARY KEY,           -- Unique snapshot ID
  namespace VARCHAR NOT NULL,     -- e.g., 'seon.trading'
  state TEXT NOT NULL,            -- EDN-serialized ctx
  created_at TIMESTAMP NOT NULL   -- Application-level timestamp
);

-- XTDB automatically adds:
-- _system_from, _system_to (system time range)
-- _valid_from, _valid_to (valid time range)

```

### Query Patterns

**Get current ctx**:

```sql
SELECT state FROM ctx_snapshots
WHERE namespace = 'seon.trading'
ORDER BY created_at DESC
LIMIT 1

```

**Get ctx at specific time** (time travel):

```sql
SELECT state FROM ctx_snapshots
FOR SYSTEM_TIME AS OF TIMESTAMP '2026-01-04 10:00:00'
WHERE namespace = 'seon.trading'
ORDER BY created_at DESC
LIMIT 1

```

**Get full history**:

```sql
SELECT _system_from, state
FROM ctx_snapshots
FOR ALL SYSTEM_TIME
WHERE namespace = 'seon.trading'
ORDER BY _system_from

```

**Get diff between two points** (application-level):

```clojure
(let [old-state (ctx-at db ns #inst "2026-01-04T09:00")
      new-state (ctx-at db ns #inst "2026-01-04T10:00")]
  (clojure.data/diff old-state new-state))

```

### Alternative: Single Row Per Namespace

Instead of appending rows, update a single document:

```clojure
;; Always use same _id per namespace
(xt/execute-tx conn
  [[:sql "INSERT INTO ctx (_id, namespace, state) VALUES (?, ?, ?)
          ON CONFLICT (_id) DO UPDATE SET state = EXCLUDED.state"
    [namespace-sym (str namespace-sym) (pr-str ctx)]]])

```

**Pros**: Simpler queries (no ORDER BY/LIMIT)
**Cons**: Relies entirely on XTDB system-time for history

**Recommendation**: Use the append-only approach with `created_at` for clarity and easier debugging.

---

## Validation Timing

### Synchronous Validation (RECOMMENDED)

Validate before queuing persistence:

```clojure
(add-watch ctx-atom ::validated-persist
  (fn [_ _ old new]
    (when (not= old new)
      (if (and validate-fn (not (validate-fn new)))
        (log/warn "ctx validation failed" {:new new})
        (send-off persist-agent persist-fn new)))))

```

**Why synchronous**:
- Immediate feedback if agent writes invalid state
- Prevents invalid data from reaching storage
- Malli validation is fast (1.6 us) - not a bottleneck

### Validation Performance

With compiled Malli validator:
- Simple schema: ~500 ns
- Medium schema (nested maps, vectors): ~1,600 ns
- Complex schema: ~5,000 ns

All are negligible compared to the 757ns atom+watch overhead.

### Error Handling Options

1. **Log and skip**: Don't persist invalid state, log warning
2. **Throw**: Block the swap! (breaks non-blocking guarantee)
3. **Fallback**: Persist to error table for later inspection

**Recommendation**: Log and skip. Agent shouldn't block on validation errors. Invalid states indicate bugs that should be fixed at the source.

---

## Recommended Design

### Architecture

```
                          ┌──────────────────────────────────────┐
                          │           ctx-atom                   │
                          │  (standard Clojure atom)             │
                          └──────────────┬───────────────────────┘
                                         │
                                   add-watch
                                         │
                          ┌──────────────▼───────────────────────┐
                          │      Validation (Malli)              │
                          │   (synchronous, ~1.6 us)             │
                          └──────────────┬───────────────────────┘
                                         │
                                  if valid
                                         │
                          ┌──────────────▼───────────────────────┐
                          │    Debounce Timer (50-100ms)         │
                          │  (ScheduledExecutor)                 │
                          └──────────────┬───────────────────────┘
                                         │
                                  after quiet period
                                         │
                          ┌──────────────▼───────────────────────┐
                          │     persist-agent (send-off)         │
                          │   (async I/O thread pool)            │
                          └──────────────┬───────────────────────┘
                                         │
                          ┌──────────────▼───────────────────────┐
                          │          XTDB                        │
                          │   (ctx_snapshots table)              │
                          └──────────────────────────────────────┘

```

### Implementation Sketch

```clojure
(ns seon.agent.ctx
  (:require [clojure.core.async :as a]
            [malli.core :as m]
            [xtdb.api :as xt])
  (:import [java.util.concurrent Executors TimeUnit]))

(def ^:dynamic *ctx* nil)

(defn make-persisted-ctx
  "Create a persisted context atom for an agent.

  Options:
    :db           - XTDB connection (required)
    :namespace    - Namespace symbol (required)
    :schema       - Optional Malli schema for validation
    :debounce-ms  - Debounce window (default: 100)
    :on-error     - Error handler fn (default: log)"
  [{:keys [db namespace schema debounce-ms on-error]
    :or {debounce-ms 100
         on-error #(log/error % "ctx persist error")}}]

  (let [ctx-atom (atom {:seon.agent/namespace namespace
                        :seon.agent/db db})

        ;; Compiled validator
        validate (when schema (m/validator schema))

        ;; Persistence agent
        persist-agent (agent {:error-count 0})

        ;; Debounce scheduling
        scheduler (Executors/newSingleThreadScheduledExecutor)
        pending (atom nil)
        scheduled-task (atom nil)

        ;; Actual persist function
        do-persist! (fn [state]
                      (try
                        (xt/execute-tx db
                          [[:sql "INSERT INTO ctx_snapshots
                                  (_id, namespace, state, created_at)
                                  VALUES (?, ?, ?, ?)"
                            [(str (random-uuid))
                             (str namespace)
                             (pr-str state)
                             (java.time.Instant/now)]]])
                        (catch Exception e
                          (on-error e))))]

    ;; Watch with validation + debounced persistence
    (add-watch ctx-atom ::persist
      (fn [_ _ old new]
        (when (not= old new)
          ;; Validate synchronously
          (when (or (nil? validate) (validate new))
            ;; Schedule debounced persist
            (reset! pending new)
            (when-let [task @scheduled-task]
              (.cancel task false))
            (reset! scheduled-task
              (.schedule scheduler
                (fn []
                  (when-let [v @pending]
                    (reset! pending nil)
                    (send-off persist-agent (fn [s] (do-persist! v) s))))
                debounce-ms
                TimeUnit/MILLISECONDS))))))

    ;; Return ctx with lifecycle hooks
    {:atom ctx-atom
     :flush! (fn []
               (when-let [v @pending]
                 (reset! pending nil)
                 (do-persist! v)))
     :close! (fn []
               (remove-watch ctx-atom ::persist)
               (.shutdown scheduler)
               (await persist-agent))}))

;; Time-travel API
(defn ctx-at
  "Get ctx state at a specific point in time."
  [db namespace instant]
  (-> (xt/q db
        [(str "SELECT state FROM ctx_snapshots "
              "FOR SYSTEM_TIME AS OF ? "
              "WHERE namespace = ? "
              "ORDER BY created_at DESC LIMIT 1")
         instant (str namespace)])
      first
      :state
      clojure.edn/read-string))

(defn ctx-history
  "Get all historical ctx states for a namespace."
  [db namespace]
  (->> (xt/q db
         ["SELECT _system_from, state FROM ctx_snapshots
           FOR ALL SYSTEM_TIME
           WHERE namespace = ?
           ORDER BY _system_from"
          (str namespace)])
       (mapv (fn [{:keys [_system_from state]}]
               {:timestamp _system_from
                :state (clojure.edn/read-string state)}))))

```

### Configuration

```clojure
;; Default for interactive development
{:debounce-ms 100}   ;; Persist 100ms after last change

;; For high-frequency trading agents
{:debounce-ms 50}    ;; More responsive

;; For batch processing agents
{:debounce-ms 500}   ;; Fewer persists during bulk ops

```

---

## Open Questions

### For User Decision

1. **Debounce window default**: 100ms seems reasonable but depends on use case. Should this be configurable per-agent or system-wide?

2. **Validation failure behavior**: Currently recommends "log and skip". Should invalid states:
   - Block the persist only (current recommendation)
   - Block the swap! entirely (breaks non-blocking)
   - Trigger an alert/notification

3. **History retention**: XTDB stores all versions by default. Should we add a cleanup policy for old ctx snapshots?

4. **Reserved keys protection**: How to prevent agents from modifying `:seon.agent/*` keys? Options:
   - Validator that rejects changes to reserved keys
   - Wrap swap! to filter out reserved key modifications
   - Documentation + trust (simplest)

### Technical Considerations

1. **Recovery on startup**: When agent nREPL starts, should it restore the latest persisted ctx? Or start fresh?

2. **Concurrent agents**: What happens if the same namespace is started twice? Current design doesn't prevent this.

3. **Large ctx handling**: At what size should we warn or fail? 100KB? 1MB?

---

## Files Examined

### XTDB Source Code

- `/Users/sean/src/seon/reference-code/xtdb/api/src/main/clojure/xtdb/api.clj` - API surface
- `/Users/sean/src/seon/reference-code/xtdb/src/test/clojure/xtdb/as_of_test.clj` - Temporal query patterns
- `/Users/sean/src/seon/reference-code/xtdb/docs/src/content/docs/about/dbs-in-xtdb.md` - Multi-database architecture

### Seon Source Code

- `/Users/sean/src/seon/src/seon/db/multi.clj` - Namespace database management
- `/Users/sean/src/seon/docs/prds/agent-isolation/prd.md` - PRD with requirements

### External Resources (via gemini/search)

- Clojure atom persistence patterns
- duratom library internals
- editscript library performance
- XTDB v2 temporal query documentation
