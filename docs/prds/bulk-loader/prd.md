# PRD: Bulk Loader Resilience & Resume

**Status:** Ready for Implementation
**Priority:** High
**Branch:** `feature/bulk-loader-resilience`

---

## Problem Statement

The bulk loader (`ml-options.data.bulk-load`) failed during a 5-year historical load (2019-2025) after ~10 hours and ~5M records. The process got stuck in an infinite retry loop when ThetaData Terminal crashed, with no way to resume from the failure point.

**Impact:** Lost ~10 hours of work. SPY has gaps (missing 2022-2024). Other symbols never started.

---

## Root Causes

| Issue | Location | Severity |
|-------|----------|----------|
| No circuit breaker for dead terminal | `ingest.clj:with-retry` | Critical |
| State tracking too coarse (per-symbol) | `ingestion-state.clj` | High |
| No health check before retry loops | `thetadata.clj` | High |
| Sequential processing only | `ingest.clj:bulk-load!` | Medium |
| Retry doesn't distinguish error types | `ingest.clj:with-retry` | Medium |

---

## Current Data State (Post-Crash)

```
Total: 7.65M records in XTDB

SPY:   6.6M | 2019-2021 ✓ | 2022 partial | 2023-2024 MISSING | 2025 ✓
AAPL:  200K | 2025-05-28 → 2025-11-26 only
NVDA:  351K | 2025-05-28 → 2025-11-26 only
MSFT:  294K | 2025-05-28 → 2025-11-26 only
GOOGL: 195K | 2025-05-28 → 2025-11-26 only
```

---

## Solution Design

### Architecture: Data-Oriented Pipeline

Replace tightly-coupled side-effectful code with a **work queue model**:

```
[Plan] → [Work Items] → [Fetch] → [Transform] → [Persist] → [Checkpoint]
  ↑                                                              |
  └──────────────── Resume from checkpoint ──────────────────────┘
```

Each stage produces **data** that can be inspected, serialized, and retried.

### Key Components

#### 1. Circuit Breaker (`thetadata.clj`)

```clojure
;; Track terminal health
(def ^:private circuit-state
  (atom {:healthy true :consecutive-failures 0 :last-check nil}))

(defn terminal-healthy?
  "Check if terminal is responsive. Caches result for 30s."
  []
  (let [{:keys [last-check healthy]} @circuit-state
        stale? (or (nil? last-check)
                   (> (- (System/currentTimeMillis) last-check) 30000))]
    (if stale?
      (let [ok? (health-check)]
        (swap! circuit-state assoc
               :healthy ok?
               :last-check (System/currentTimeMillis)
               :consecutive-failures (if ok? 0 (inc (:consecutive-failures @circuit-state))))
        ok?)
      healthy)))

(defn circuit-open?
  "Circuit opens after 3 consecutive failures"
  []
  (>= (:consecutive-failures @circuit-state) 3))
```

#### 2. Fine-Grained Progress Tracking (`ingestion-state.clj`)

New table: `:bulk-progress` tracks per-expiration completion.

```clojure
;; Schema
{:xt/id "progress-SPY-2022-01-21"  ;; symbol-expiration
 :progress/symbol "SPY"
 :progress/expiration #inst "2022-01-21"
 :progress/records 12500
 :progress/completed-at #inst "..."}

;; Functions to add
(defn get-completed-expirations [node symbol])
(defn mark-expiration-done! [node symbol expiration records])
(defn get-resume-work [node symbol start-date end-date])
```

#### 3. Work Queue Model (`bulk-load.clj`)

```clojure
(defn plan-work
  "Generate work items as data. Pure function."
  [symbol start-date end-date completed-exps]
  (->> (theta/fetch-option-expirations symbol)
       (filter #(and (not (.isBefore % start-date))
                     (.isBefore % (.plusMonths end-date 6))))
       (remove completed-exps)
       (mapv (fn [exp]
               {:symbol symbol
                :expiration exp
                :start-date start-date
                :end-date (if (.isBefore exp end-date) exp end-date)
                :status :pending}))))

(defn execute-work-item!
  "Fetch data for one expiration. Returns result as data."
  [{:keys [symbol expiration start-date end-date] :as item}]
  (if (circuit-open?)
    (assoc item :status :circuit-open :error "Terminal unreachable")
    (try
      (let [data (theta/fetch-option-greeks-eod symbol {...})]
        (assoc item :status :fetched :data data :records (count data)))
      (catch Exception e
        (assoc item :status :failed :error (ex-message e))))))
```

#### 4. Parallel Processing with Bounded Concurrency

```clojure
(defn process-work-items!
  "Process items with parallelism, checkpoint after each."
  [node items {:keys [parallelism] :or {parallelism 4}}]
  (let [results (atom [])]
    (doseq [batch (partition-all parallelism items)]
      ;; Check circuit before each batch
      (when (circuit-open?)
        (throw (ex-info "Circuit breaker open" {:completed @results})))
      ;; Process batch in parallel
      (let [fetched (pmap execute-work-item! batch)]
        (doseq [{:keys [symbol expiration data status] :as result} fetched]
          (when (= :fetched status)
            (let [docs (mapv thetadata->xtdb-doc data)
                  valid (remove nil? docs)]
              (when (seq valid)
                (ingest-batch! node valid (:xt/valid-from (first valid))))
              (mark-expiration-done! node symbol expiration (count valid))))
          (swap! results conj (dissoc result :data)))))
    @results))
```

---

## File Changes Required

| File | Changes |
|------|---------|
| `src/ml_options/data/thetadata.clj` | Add circuit breaker state + `terminal-healthy?` + `circuit-open?` |
| `src/ml_options/data/ingestion_state.clj` | Add `:bulk-progress` table functions |
| `src/ml_options/data/ingest.clj` | Refactor to work-queue model |
| `src/ml_options/data/bulk_load.clj` | Use new `process-work-items!` |

---

## Implementation Order

1. **Circuit Breaker** (thetadata.clj) - Prevent infinite loops
2. **Progress Tracking** (ingestion-state.clj) - Enable resume
3. **Work Queue** (ingest.clj) - Decouple fetch/persist
4. **Parallel Processing** (bulk-load.clj) - Bounded concurrency

---

## Testing Checklist

- [ ] Circuit breaker opens after 3 failures
- [ ] Circuit breaker auto-recovers when terminal comes back
- [ ] Progress persists per-expiration to XTDB
- [ ] Resume skips already-completed expirations
- [ ] Parallel processing respects concurrency limit
- [ ] Graceful shutdown saves current progress

---

## Key Files to Read

Before implementing, read these files for context:

```
src/ml_options/data/thetadata.clj    # ThetaData API client (lines 39-77 for make-request)
src/ml_options/data/ingest.clj       # Current pipeline (lines 333-379 for process-expiration!)
src/ml_options/data/ingestion_state.clj  # State tracking (lines 92-138 for updates)
src/ml_options/data/bulk_load.clj    # CLI entry point
docs/thetadata-v3-api.md             # API reference
```

---

## Success Criteria

1. Bulk load survives ThetaData Terminal restart
2. Resume from exact failure point (within 1 expiration)
3. No data loss on crash
4. Progress visible during load (which expirations done)
5. Complete 5-year load for all 5 symbols

---

## Notes

- ThetaData logs go to `/tmp` (config.toml) but appear empty - health check is better approach
- XTDB had compaction errors during load - monitor but likely XTDB v2.0.0 bug
- Current 60s timeout is appropriate for large SPY queries
- The `theta/health-check` function already exists (line 387-401 in thetadata.clj)
