(ns seon.trading.ingest
  "ThetaData ingestion pipeline for options Greeks.

  Fetches historical option Greeks from ThetaData Terminal, validates,
  and ingests into XTDB with:
  - Deterministic IDs (OCC symbol + timestamp)
  - Batch processing (30-day API chunks, 1000-doc XTDB batches)
  - State tracking for resumable loads
  - Proper valid-time for bitemporal queries

  Core functions (used by bulk_load.clj):
  - thetadata->xtdb-doc: Transform and validate a single record
  - ingest-batch!: Efficient batch insert with shared valid-from
  - plan-work: Generate work items as data (work queue model)
  - execute-work-item!: Fetch data for one expiration

  Daily/historical ingestion:
  - ingest-symbol-date-range!: Fetch and ingest data for a date range
  - ingest-symbol-history!: Per-symbol entry point with state tracking
  - run-daily-update!: Daily EOD updates for configured symbols

  For bulk multi-symbol loading, use seon.trading.bulk-load/resilient-bulk-load!"
  (:require [seon.trading.thetadata :as theta]
            [seon.trading.validation :as valid :refer [*quiet*]]
            [seon.trading.ingestion-state :as state]
            [seon.trading.date-utils :refer [local-date->eod-instant
                                             instant->local-date
                                             weekend?]]
            [xtdb.api :as xt]
            [taoensso.timbre :as log])
  (:import [java.time LocalDate Instant]))

;;; ---------------------------------------------------------------------------
;;; Date/Time Utilities - see seon.trading.date-utils
;;; ---------------------------------------------------------------------------

;;; ---------------------------------------------------------------------------
;;; Data Transformation & Validation
;;; ---------------------------------------------------------------------------

(defn thetadata->xtdb-doc
  "Transform and validate a single ThetaData record for XTDB.

  Record from thetadata.clj already has :xt/id and :xt/valid-from set.
  This function just validates and returns the record, or nil if invalid.

  Args:
    record - Record map from theta/fetch-option-greeks-eod

  Returns:
    Validated record or nil if validation fails"
  [record]
  (when record
    (let [validation (valid/validate-record record)]
      (when (:valid? validation)
        record))))

;;; ---------------------------------------------------------------------------
;;; Batch Ingestion
;;; ---------------------------------------------------------------------------

(defn ingest-batch!
  "Insert batch of validated docs into XTDB.

  Uses efficient batch pattern with shared valid-from timestamp.
  CRITICAL: Uses {:into :option-greeks :valid-from valid-from} pattern.

  Args:
    node - XTDB node
    docs - Vector of validated document maps
    valid-from - Instant for valid-time (5pm ET on quote date)

  Returns:
    XTDB transaction result"
  [node docs valid-from]
  (xt/execute-tx node
                 [(into [:put-docs {:into :option-greeks :valid-from valid-from}]
                        docs)]))

;;; ---------------------------------------------------------------------------
;;; Date Range Ingestion
;;; ---------------------------------------------------------------------------

(defn- filter-relevant-expirations
  "Filter expirations to those relevant for the date range.
   Includes expirations that expire during or after the start-date,
   and were listed before end-date."
  [expirations start-date end-date]
  (->> expirations
       (filter #(not (.isBefore % start-date)))  ;; Expiry >= start-date
       (filter #(.isBefore % (.plusMonths end-date 6)))  ;; Reasonable future limit
       vec))

(defn ingest-symbol-date-range!
  "Fetch and ingest Greeks for a symbol over a date range.

  OPTIMIZED: Fetches by expiration instead of day-by-day with expiration=*.
  - First fetches all expirations (FREE tier, fast)
  - For each expiration, fetches multi-day ranges (30 days at a time)
  - This is 30x more efficient than day-by-day

  Args:
    node - XTDB node
    symbol - Stock ticker (e.g., 'AAPL')
    start-date - LocalDate to start ingestion
    end-date - LocalDate to end ingestion

  Returns:
    Total number of records ingested"
  [node symbol start-date end-date]
  (let [api-batch-days 30  ;; 30 days per API call (multi-day range per expiration)
        xtdb-batch-size 1000
        inter-request-delay-ms 500
        total-records (atom 0)]
    ;; Get all expirations first (FREE tier, single fast call)
    (log/info "Fetching expirations" {:symbol symbol})
    (let [all-expirations (theta/fetch-option-expirations symbol)
          relevant-exps (filter-relevant-expirations all-expirations start-date end-date)]
      (log/info "Found expirations" {:total (count all-expirations)
                                     :relevant (count relevant-exps)})
      ;; Process each expiration
      (doseq [expiration relevant-exps]
        (loop [current-start start-date]
          (when-not (.isAfter current-start end-date)
            (let [current-end (let [proposed (.plusDays current-start (dec api-batch-days))]
                                (if (.isAfter proposed end-date)
                                  end-date
                                  proposed))
                  _ (log/info "Fetching Greeks"
                              {:symbol symbol
                               :expiration expiration
                               :start current-start
                               :end current-end})
                  ;; Fetch data for this expiration across date range
                  raw-data (theta/fetch-option-greeks-eod symbol
                                                          {:start-date current-start
                                                           :end-date current-end
                                                           :expiration expiration
                                                           :strike "*"
                                                           :right "both"})
                  validated-docs (when raw-data
                                   (->> raw-data
                                        (map thetadata->xtdb-doc)
                                        (remove nil?)
                                        vec))
                  _ (when (seq validated-docs)
                      (log/info "Validated records"
                                {:expiration expiration
                                 :fetched (count (or raw-data []))
                                 :valid (count validated-docs)}))
                  batched-docs (partition-all xtdb-batch-size validated-docs)]
              ;; Insert each batch
              (doseq [batch batched-docs]
                (when (seq batch)
                  ;; Use each record's own valid-from (already set in transform)
                  (let [valid-from (or (:xt/valid-from (first batch))
                                       (local-date->eod-instant current-end))]
                    (ingest-batch! node batch valid-from))
                  (swap! total-records + (count batch))))
              ;; Delay between API calls
              (Thread/sleep inter-request-delay-ms)
              ;; Move to next batch
              (recur (.plusDays current-end 1)))))
        ;; Update state after each expiration
        (state/update-progress! node symbol
                                (local-date->eod-instant end-date)
                                @total-records)))
    @total-records))

;;; ---------------------------------------------------------------------------
;;; Error Classification Helpers
;;; ---------------------------------------------------------------------------

(defn- no-data-error?
  "Check if exception is ThetaData 'no data found' (status 472) - expected, not error."
  [ex]
  (and (instance? clojure.lang.ExceptionInfo ex)
       (= 472 (:status (ex-data ex)))))

;;; ---------------------------------------------------------------------------
;;; Work Queue Model (Day-at-a-Time)
;;; ---------------------------------------------------------------------------

(defn plan-daily-work
  "Generate daily work items as data. Pure function.

  Returns vector of work items: [{:symbol :date :status :pending}]
  Skips weekends (no trading data) and already-completed dates.

  Args:
    symbol - Stock ticker (e.g., 'AAPL')
    start-date - LocalDate to start ingestion
    end-date - LocalDate to end ingestion
    completed-dates - Set of LocalDate dates already completed

  Returns:
    Vector of work item maps with :pending status"
  [symbol start-date end-date completed-dates]
  (loop [current start-date
         items []]
    (if (.isAfter current end-date)
      items
      (cond
        ;; Skip weekends - no trading data
        (weekend? current)
        (recur (.plusDays current 1) items)

        ;; Skip already completed dates
        (contains? completed-dates current)
        (recur (.plusDays current 1) items)

        ;; Add weekday to work queue
        :else
        (recur (.plusDays current 1)
               (conj items {:symbol symbol
                            :date current
                            :status :pending}))))))

(defn execute-daily-work-item!
  "Fetch data for one trading day with ALL expirations. Returns result as data.

  Uses expiration=* to fetch all expirations in a single API call per day.
  This is ~38x more efficient than per-expiration queries.

  Possible statuses: :fetched, :failed, :no-data, :circuit-open

  Args:
    item - Work item map with :symbol, :date

  Returns:
    Work item map with updated :status and additional fields:
    - :status :fetched -> adds :data and :records
    - :status :failed -> adds :error message
    - :status :no-data -> no additional fields
    - :status :circuit-open -> adds :error message"
  [{:keys [symbol date] :as item}]
  (if (theta/circuit-open?)
    (assoc item :status :circuit-open :error "Circuit breaker open")
    (try
      (let [data (theta/fetch-option-greeks-eod symbol
                                                {:start-date date
                                                 :end-date date
                                                 :expiration "*"
                                                 :strike "*"
                                                 :right "both"})]
        (if data
          (assoc item :status :fetched :data data :records (count data))
          (assoc item :status :no-data)))
      (catch Exception e
        (if (no-data-error? e)
          (assoc item :status :no-data)
          (assoc item :status :failed :error (ex-message e)))))))

;;; ---------------------------------------------------------------------------
;;; Historical Ingestion with State Tracking
;;; ---------------------------------------------------------------------------

(defn ingest-symbol-history!
  "Ingest historical Greeks for a symbol.

  - Checks for existing state and resumes if needed
  - Initializes state tracking
  - Calls ingest-symbol-date-range! in chunks
  - Marks complete or failed

  Args:
    node - XTDB node
    symbol - Stock ticker (e.g., 'AAPL')
    opts - Map with:
      :start-date - LocalDate to start ingestion (required)
      :end-date - LocalDate to end ingestion (required)

  Returns:
    {:success true :total-records N} on success
    Throws exception on failure (after marking state as failed)"
  [node symbol {:keys [start-date end-date]}]
  (try
    (let [;; Check for existing state
          existing-state (state/get-state node symbol)
          ;; get-resume-date returns Instant, convert to LocalDate for date range ops
          resume-date (when (and existing-state
                                 (= :in-progress (:ingestion/status existing-state)))
                        (some-> (state/get-resume-date node symbol)
                                instant->local-date))
          actual-start (or resume-date start-date)
          _ (log/info "Starting ingestion"
                      {:symbol symbol
                       :start-date actual-start
                       :end-date end-date
                       :resume? (some? resume-date)})
          ;; Initialize state if not resuming
          _ (when-not resume-date
              (state/init-state! node symbol (local-date->eod-instant actual-start)))
          ;; Ingest the date range
          total-records (ingest-symbol-date-range! node symbol actual-start end-date)]
      ;; Mark complete
      (state/mark-complete! node symbol
                            (local-date->eod-instant end-date)
                            total-records)
      (log/info "Ingestion complete"
                {:symbol symbol
                 :total-records total-records})
      {:success true
       :total-records total-records})
    (catch Exception e
      (log/error e "Ingestion failed" {:symbol symbol})
      (state/mark-failed! node symbol (.getMessage e))
      (throw e))))

;;; ---------------------------------------------------------------------------
;;; Daily Updates
;;; ---------------------------------------------------------------------------

(defn run-daily-update!
  "Run daily EOD update for configured symbols.

  Idempotent - safe to run multiple times.
  Fetches data for yesterday (most recent complete trading day).

  Args:
    node - XTDB node
    symbols - Vector of stock tickers (e.g., ['AAPL' 'SPY'])

  Returns:
    {:success true :symbols [...] :date LocalDate}"
  [node symbols]
  (let [today (LocalDate/now)
        yesterday (.minusDays today 1)]
    (doseq [symbol symbols]
      (try
        (log/info "Running daily update"
                  {:symbol symbol
                   :date yesterday})
        (ingest-symbol-date-range! node symbol yesterday yesterday)
        (log/info "Daily update complete" {:symbol symbol})
        (catch Exception e
          (log/error e "Daily update failed" {:symbol symbol}))))
    {:success true
     :symbols symbols
     :date yesterday}))

;;; ---------------------------------------------------------------------------
;;; Example Usage
;;; ---------------------------------------------------------------------------

(comment
  ;; === Setup ===
  (require '[xtdb.node :as xtn])
  (require '[seon.db.node :as node])
  (def test-node (xtn/start-node))

  ;; === Test Single Day ===
  (def test-date (LocalDate/of 2025 11 27))
  (ingest-symbol-date-range! test-node "AAPL" test-date test-date)
  ;; => ~2000 (number of records ingested)

  ;; Verify data
  (node/query test-node
              '(-> (from :option-greeks [xt/id asset/ticker greeks/delta])
                   (limit 5)))

  ;; Verify idempotency (should not duplicate)
  (ingest-symbol-date-range! test-node "AAPL" test-date test-date)
  ;; => same count - no duplicates

  ;; Count total records
  (node/query test-node
              '(-> (from :option-greeks [xt/id])
                   (aggregate {:count (count xt/id)})))
  ;; => [{:count N}]

  ;; === Historical Ingestion ===
  (ingest-symbol-history! test-node "AAPL"
                          {:start-date (LocalDate/of 2025 11 1)
                           :end-date (LocalDate/of 2025 11 30)})

  ;; Check state
  (require '[seon.data.ingestion-state :as state])
  (state/get-state test-node "AAPL")

  ;; === Daily Update ===
  (run-daily-update! test-node ["AAPL" "SPY" "QQQ"])

  ;; === Resume Interrupted Load ===
  ;; If ingest-symbol-history! was interrupted, it will auto-resume from last checkpoint
  (ingest-symbol-history! test-node "AAPL"
                          {:start-date (LocalDate/of 2025 5 28)
                           :end-date (LocalDate/of 2025 11 27)}))
