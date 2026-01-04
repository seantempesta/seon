(ns seon.trading.ingestion-state
  "Ingestion state tracking for resumable data loads.

  Tracks progress of options data ingestion in XTDB to enable:
  - Resuming interrupted loads from last checkpoint
  - Detecting gaps in historical data
  - Monitoring ingestion progress

  State entities are stored in the :ingestion-state table with schema:
  {:xt/id \"ingestion-state-{SYMBOL}\"
   :ingestion/symbol \"AAPL\"
   :ingestion/last-date #inst \"...\"      ; Last successfully ingested date
   :ingestion/start-date #inst \"...\"     ; When ingestion started
   :ingestion/status :in-progress          ; :in-progress, :complete, :failed
   :ingestion/records-count 12500          ; Total records ingested
   :ingestion/error \"...\"                ; Error message if failed
   :ingestion/updated-at #inst \"...\"}     ; Last update timestamp"
  (:require [seon.db.node :as node]
            [xtdb.api :as xt])
  (:import [java.time Instant LocalDate]
           [java.time.temporal ChronoUnit]))

;;; ---------------------------------------------------------------------------
;;; ID Generation
;;; ---------------------------------------------------------------------------

(defn make-state-id
  "Generate deterministic ID for ingestion state.

  Format: \"ingestion-state-{SYMBOL}\"
  Example: \"ingestion-state-AAPL\"

  Args:
    symbol - Stock symbol (e.g., \"AAPL\")

  Returns:
    Deterministic ID string"
  [symbol]
  (str "ingestion-state-" symbol))

;;; ---------------------------------------------------------------------------
;;; State Queries
;;; ---------------------------------------------------------------------------

(defn get-state
  "Query current ingestion state for a symbol.

  Args:
    node - XTDB node
    symbol - Stock symbol

  Returns:
    State map or nil if no state exists"
  [node symbol]
  (first
   (xt/q node
         ["SELECT _id, ingestion$symbol, ingestion$last_date, ingestion$start_date,
                  ingestion$status, ingestion$records_count, ingestion$error, ingestion$updated_at
           FROM ingestion_state
           WHERE _id = ?"
          (make-state-id symbol)]
         {:key-fn :kebab-case-keyword})))

(defn get-resume-date
  "Get the date to resume ingestion from.

  If state exists and status is :in-progress, returns the day after last-date.
  Otherwise returns nil (start fresh).

  Args:
    node - XTDB node
    symbol - Stock symbol

  Returns:
    Instant of next date to ingest, or nil"
  [node symbol]
  (when-let [state (get-state node symbol)]
    (when (and (= :in-progress (:ingestion/status state))
               (:ingestion/last-date state))
      ;; Return day after last-date
      (.plus (:ingestion/last-date state) 1 ChronoUnit/DAYS))))

;;; ---------------------------------------------------------------------------
;;; State Updates
;;; ---------------------------------------------------------------------------

(defn init-state!
  "Create initial state for new ingestion.

  Sets status to :in-progress and records start time.

  Args:
    node - XTDB node
    symbol - Stock symbol
    start-date - Instant when ingestion started

  Returns:
    Transaction result"
  [node symbol start-date]
  (let [now (Instant/now)
        state-id (make-state-id symbol)
        state {:xt/id state-id
               :ingestion/symbol symbol
               :ingestion/start-date start-date
               :ingestion/status :in-progress
               :ingestion/records-count 0
               :ingestion/updated-at now}]
    (xt/execute-tx node [[:put-docs :ingestion-state state]])))

(defn update-progress!
  "Update ingestion progress after each batch.

  Updates last-date, records-count, and updated-at timestamp.
  Keeps status as :in-progress.

  Args:
    node - XTDB node
    symbol - Stock symbol
    last-date - Instant of last successfully ingested date
    records-count - Total number of records ingested so far

  Returns:
    Transaction result"
  [node symbol last-date records-count]
  (let [now (Instant/now)
        state-id (make-state-id symbol)
        state {:xt/id state-id
               :ingestion/symbol symbol
               :ingestion/last-date last-date
               :ingestion/status :in-progress
               :ingestion/records-count records-count
               :ingestion/updated-at now}]
    (xt/execute-tx node [[:put-docs :ingestion-state state]])))

(defn mark-complete!
  "Mark ingestion as successfully completed.

  Sets status to :complete with final counts.

  Args:
    node - XTDB node
    symbol - Stock symbol
    last-date - Instant of last ingested date
    records-count - Final total of records ingested

  Returns:
    Transaction result"
  [node symbol last-date records-count]
  (let [now (Instant/now)
        state-id (make-state-id symbol)
        state {:xt/id state-id
               :ingestion/symbol symbol
               :ingestion/last-date last-date
               :ingestion/status :complete
               :ingestion/records-count records-count
               :ingestion/updated-at now}]
    (xt/execute-tx node [[:put-docs :ingestion-state state]])))

(defn mark-failed!
  "Mark ingestion as failed with error message.

  Sets status to :failed and records error.

  Args:
    node - XTDB node
    symbol - Stock symbol
    error-msg - Error message string

  Returns:
    Transaction result"
  [node symbol error-msg]
  (let [now (Instant/now)
        state-id (make-state-id symbol)
        state {:xt/id state-id
               :ingestion/symbol symbol
               :ingestion/status :failed
               :ingestion/error error-msg
               :ingestion/updated-at now}]
    (xt/execute-tx node [[:put-docs :ingestion-state state]])))

;;; ---------------------------------------------------------------------------
;;; Gap Detection
;;; ---------------------------------------------------------------------------

(defn find-gaps
  "Find missing date ranges in ingested data.

  Queries all ingested dates for a symbol within the given range and
  identifies continuous gaps. This is useful for backfilling missing data.

  Note: This does NOT check for trading days vs. weekends/holidays. It simply
  identifies any missing dates in the range. Callers should filter against
  actual trading days if needed.

  Args:
    node - XTDB node
    symbol - Stock symbol
    start-date - Start of range to check (Instant)
    end-date - End of range to check (Instant)

  Returns:
    Vector of [gap-start gap-end] Instant pairs representing missing ranges"
  [node symbol start-date end-date]
  (let [;; Query all distinct dates we have data for
        ingested-dates (map :date
                            (xt/q node
                                  ["SELECT DISTINCT quote$date as date
                                    FROM option_greeks
                                    WHERE asset$ticker = ?
                                      AND quote$date >= ?
                                      AND quote$date <= ?
                                    ORDER BY quote$date"
                                   symbol start-date end-date]
                                  {:key-fn :kebab-case-keyword}))
        ingested-set (set ingested-dates)]
    ;; Walk through the date range and identify gaps
    (loop [current start-date
           gaps []
           gap-start nil]
      (if (.isAfter current end-date)
        ;; End of range - close any open gap
        (if gap-start
          (conj gaps [gap-start (.minus end-date 1 ChronoUnit/DAYS)])
          gaps)
        ;; Check if current date is ingested
        (if (contains? ingested-set current)
          ;; Date exists - close any open gap
          (if gap-start
            (recur (.plus current 1 ChronoUnit/DAYS)
                   (conj gaps [gap-start (.minus current 1 ChronoUnit/DAYS)])
                   nil)
            (recur (.plus current 1 ChronoUnit/DAYS)
                   gaps
                   nil))
          ;; Date missing - continue or start gap
          (recur (.plus current 1 ChronoUnit/DAYS)
                 gaps
                 (or gap-start current)))))))

;;; ---------------------------------------------------------------------------
;;; Fine-Grained Progress Tracking (Day-at-a-Time)
;;; ---------------------------------------------------------------------------

(defn make-progress-id
  "Generate deterministic ID for bulk progress.

  Format: progress-{SYMBOL}-{DATE}
  Example: progress-SPY-2024-11-27

  Args:
    symbol - Stock symbol (e.g., \"SPY\")
    date - Date string in YYYY-MM-DD format or LocalDate

  Returns:
    Deterministic ID string"
  [symbol date]
  (str "progress-" symbol "-" date))

(defn get-completed-dates
  "Get set of dates already completed for a symbol.

  Queries the :bulk-progress table for all completed dates
  for the given symbol. Used to resume bulk loads by skipping
  already-processed dates.

  Args:
    node - XTDB node
    symbol - Stock symbol

  Returns:
    Set of LocalDate dates that have been completed"
  [node symbol]
  (->> (xt/q node
             ["SELECT progress$date
               FROM bulk_progress
               WHERE progress$symbol = ?"
              symbol]
             {:key-fn :kebab-case-keyword})
       (map :progress/date)
       (set)))

(defn mark-date-done!
  "Mark a single date as completed with record count.

  Writes a progress record to the :bulk-progress table tracking
  completion of one trading day. This enables fine-grained resume
  if the bulk load is interrupted.

  Args:
    node - XTDB node
    symbol - Stock symbol
    date - LocalDate of the trading day
    records - Number of records ingested for this date

  Returns:
    Transaction result"
  [node symbol date records]
  (let [now (Instant/now)
        date-str (str date)
        progress-id (make-progress-id symbol date-str)]
    (xt/execute-tx node
                   [[:put-docs :bulk-progress
                     {:xt/id progress-id
                      :progress/symbol symbol
                      :progress/date date
                      :progress/records records
                      :progress/completed-at now}]])))

(defn get-resume-work
  "Get dates that still need to be processed.

  Compares the full set of dates against already-completed
  ones to determine remaining work. Returns a vector of dates
  in their original order, excluding any that are already done.

  Args:
    node - XTDB node
    symbol - Stock symbol
    all-dates - Sequence of LocalDate dates to process

  Returns:
    Vector of LocalDate dates that still need processing"
  [node symbol all-dates]
  (let [completed (get-completed-dates node symbol)]
    (vec (remove completed all-dates))))

;;; ---------------------------------------------------------------------------
;;; Convenience Functions
;;; ---------------------------------------------------------------------------

(defn list-all-states
  "List ingestion states for all symbols.

  Args:
    node - XTDB node

  Returns:
    Vector of state maps"
  [node]
  (xt/q node
        "SELECT _id, ingestion$symbol, ingestion$status, ingestion$last_date,
                ingestion$records_count, ingestion$updated_at
         FROM ingestion_state"
        {:key-fn :kebab-case-keyword}))

(defn list-in-progress
  "List all symbols with in-progress ingestion.

  Args:
    node - XTDB node

  Returns:
    Vector of state maps with :in-progress status"
  [node]
  (xt/q node
        "SELECT _id, ingestion$symbol, ingestion$last_date,
                ingestion$records_count, ingestion$updated_at
         FROM ingestion_state
         WHERE ingestion$status = 'in-progress'
         ORDER BY ingestion$updated_at"
        {:key-fn :kebab-case-keyword}))

(defn list-failed
  "List all symbols with failed ingestion.

  Args:
    node - XTDB node

  Returns:
    Vector of state maps with :failed status"
  [node]
  (xt/q node
        "SELECT _id, ingestion$symbol, ingestion$error, ingestion$updated_at
         FROM ingestion_state
         WHERE ingestion$status = 'failed'
         ORDER BY ingestion$updated_at"
        {:key-fn :kebab-case-keyword}))
