(ns seon.web.stats
  "Database statistics queries for dashboard display.

   Uses pre-computed stats stored in XTDB for instant dashboard loads.
   Full table scans only happen when explicitly recomputing stats."
  (:require [xtdb.api :as xt]
            [clojure.java.io :as io])
  (:import [java.time Instant]))

(defn get-total-records
  "Query total count of option-greeks records.
   Returns the count as a number, or 0 if empty/error."
  [xtdb-node]
  (try
    (let [result (xt/q xtdb-node
                       "SELECT COUNT(*) as cnt FROM option_greeks"
                       {:key-fn :kebab-case-keyword})]
      (or (:cnt (first result)) 0))
    (catch Exception _
      0)))

(defn get-date-range
  "Query the earliest and latest quote dates in the database.
   Returns {:earliest LocalDate :latest LocalDate} or nil if empty."
  [xtdb-node]
  (try
    (let [result (first (xt/q xtdb-node
                              "SELECT MIN(quote$date) as earliest, MAX(quote$date) as latest
                               FROM option_greeks"
                              {:key-fn :kebab-case-keyword}))]
      ;; Return nil if earliest or latest are nil (empty DB)
      (when (and (:earliest result) (:latest result))
        result))
    (catch Exception _
      nil)))

(defn get-distinct-symbols
  "Query list of all distinct symbols in the database.
   Returns a vector of ticker symbols."
  [xtdb-node]
  (try
    (let [results (xt/q xtdb-node
                        "SELECT DISTINCT asset$ticker
                         FROM option_greeks
                         ORDER BY asset$ticker"
                        {:key-fn :kebab-case-keyword})]
      (vec (map :asset/ticker results)))
    (catch Exception _
      [])))

(defn get-latest-timestamp
  "Query the most recent record timestamp.
   Returns an Instant or nil if empty."
  [xtdb-node]
  (try
    (let [result (first (xt/q xtdb-node
                              "SELECT MAX(quote$timestamp) as latest
                               FROM option_greeks"
                              {:key-fn :kebab-case-keyword}))]
      (when-let [ts (:latest result)]
        ;; Convert ZonedDateTime to Instant if needed
        (if (instance? java.time.ZonedDateTime ts)
          (.toInstant ts)
          ts)))
    (catch Exception _
      nil)))

(defn get-records-by-symbol-list
  "Query record counts grouped by symbol.
   Returns a list of {:asset/ticker symbol :count n} maps sorted by count descending."
  [xtdb-node]
  (try
    (->> (xt/q xtdb-node
               "SELECT asset$ticker, COUNT(*) as count
                FROM option_greeks
                GROUP BY asset$ticker"
               {:key-fn :kebab-case-keyword})
         (sort-by :count >))
    (catch Exception _
      [])))

(defn get-date-range-by-symbol
  "Query date range per symbol.
   Returns a list of {:asset/ticker symbol :min-date LocalDate :max-date LocalDate}."
  [xtdb-node]
  (try
    (->> (xt/q xtdb-node
               "SELECT asset$ticker, MIN(quote$date) as min_date, MAX(quote$date) as max_date
                FROM option_greeks
                GROUP BY asset$ticker"
               {:key-fn :kebab-case-keyword})
         (sort-by :asset/ticker))
    (catch Exception e
      (println "Error getting date ranges:" (.getMessage e))
      [])))

(defn- dir-size
  "Calculate total size of a directory in bytes."
  [path]
  (let [f (io/file path)]
    (if (.exists f)
      (->> (file-seq f)
           (filter #(.isFile %))
           (map #(.length %))
           (reduce + 0))
      0)))

(defn- format-bytes
  "Format bytes as human-readable string."
  [bytes]
  (cond
    (< bytes 1024) (str bytes " B")
    (< bytes (* 1024 1024)) (format "%.1f KB" (/ bytes 1024.0))
    (< bytes (* 1024 1024 1024)) (format "%.1f MB" (/ bytes (* 1024.0 1024)))
    :else (format "%.2f GB" (/ bytes (* 1024.0 1024 1024)))))

(defn get-disk-usage
  "Get disk usage for the XTDB data directory.
   Returns {:bytes n :formatted string}."
  []
  (let [xtdb-path "data/xtdb"
        bytes (dir-size xtdb-path)]
    {:bytes bytes
     :formatted (format-bytes bytes)}))

(defn get-database-stats
  "Retrieve comprehensive database statistics.
   Returns a map with all stats or gracefully handles empty database.

   Stats included:
   - :total-records - Total count of option-greeks
   - :by-symbol - List of {:asset/ticker symbol :count n} maps (for html.clj)
   - :date-range - {:min-date LocalDate :max-date LocalDate}
   - :date-ranges-by-symbol - List of {:asset/ticker :min-date :max-date} per symbol
   - :distinct-symbols - Vector of symbols
   - :latest-timestamp - Most recent record timestamp
   - :symbols-count - Count of distinct symbols
   - :disk-usage - {:bytes n :formatted string}"
  [xtdb-node]
  (let [total (get-total-records xtdb-node)
        by-symbol (get-records-by-symbol-list xtdb-node)
        date-range (get-date-range xtdb-node)
        date-ranges-by-symbol (get-date-range-by-symbol xtdb-node)
        symbols (get-distinct-symbols xtdb-node)
        latest-ts (get-latest-timestamp xtdb-node)
        disk (get-disk-usage)]
    {:total-records total
     :by-symbol by-symbol  ;; List format for html.clj iteration
     :date-range (when date-range
                   {:min-date (:earliest date-range)
                    :max-date (:latest date-range)})
     :date-ranges-by-symbol date-ranges-by-symbol
     :distinct-symbols symbols
     :symbols-count (count symbols)
     :latest-timestamp latest-ts
     :disk-usage disk
     :empty? (zero? total)}))

;; ========================================
;; Pre-computed Stats (stored in XTDB)
;; ========================================

(def ^:private stats-doc-id "dashboard-stats-current")

(defn get-persisted-stats
  "Read pre-computed stats from XTDB. Instant lookup by ID - no scanning.
   Returns nil if no stats document exists yet."
  [xtdb-node]
  (try
    (first (xt/q xtdb-node
                 ["SELECT _id, total_records, by_symbol, date_range,
                          date_ranges_by_symbol, disk_usage,
                          distinct_symbols, symbols_count,
                          latest_timestamp, computed_at
                   FROM dashboard_stats
                   WHERE _id = ?"
                  stats-doc-id]
                 {:key-fn :kebab-case-keyword}))
    (catch Exception _
      nil)))

(defn save-stats!
  "Persist computed stats to XTDB as a summary document.
   This is called after bulk imports complete."
  [xtdb-node stats]
  (xt/execute-tx xtdb-node
                 [[:put-docs :dashboard-stats
                   (-> stats
                       (assoc :xt/id stats-doc-id)
                       (assoc :computed-at (Instant/now)))]]))

(defn recompute-and-save-stats!
  "Run expensive aggregation queries and persist results.
   Call this after bulk imports or on-demand. Returns the fresh stats."
  [xtdb-node]
  (println "Recomputing dashboard stats (this may take a while)...")
  (let [start (System/currentTimeMillis)
        stats (get-database-stats xtdb-node)
        elapsed (- (System/currentTimeMillis) start)]
    (println (format "Stats computed in %.1f seconds, saving..." (/ elapsed 1000.0)))
    (save-stats! xtdb-node stats)
    (println "Dashboard stats saved to XTDB")
    stats))

;; ========================================
;; In-Memory Cache (reads from persisted stats)
;; ========================================

;; Cached stats with TTL to avoid querying on every request
(defonce stats-cache (atom {:stats nil
                            :updated-at 0
                            :ttl-ms 30000})) ; 30 second cache

(defn get-cached-stats
  "Get database stats with caching.
   Cache is invalidated after 30 seconds."
  [xtdb-node]
  (let [now (System/currentTimeMillis)
        {:keys [stats updated-at ttl-ms]} @stats-cache]
    (if (and stats (< (- now updated-at) ttl-ms))
      stats
      (let [fresh-stats (get-database-stats xtdb-node)]
        (reset! stats-cache {:stats fresh-stats
                             :updated-at now
                             :ttl-ms ttl-ms})
        fresh-stats))))

(defn invalidate-cache!
  "Force invalidation of the stats cache.
   Useful after bulk imports."
  []
  (swap! stats-cache assoc :updated-at 0))

(defn get-cached-stats-nonblocking
  "Get database stats from cache only - never runs queries.
   Returns nil if cache is empty or expired.
   Use this in SSE handlers to avoid blocking."
  []
  (let [now (System/currentTimeMillis)
        {:keys [stats updated-at ttl-ms]} @stats-cache]
    (when (and stats (< (- now updated-at) ttl-ms))
      stats)))

(defn refresh-stats-async!
  "Trigger async refresh of stats cache.
   Returns immediately. Stats will be available via get-cached-stats-nonblocking
   when the query completes.

   Strategy:
   1. First try persisted stats (instant - single document lookup)
   2. If no persisted stats, run expensive queries and persist them

   Options:
   - :on-complete - (fn [stats]) called when refresh completes
   - :force-recompute - if true, always run expensive queries (for manual refresh)"
  [xtdb-node & {:keys [on-complete force-recompute]}]
  (future
    (try
      (let [stats (if force-recompute
                    ;; Force recompute: run expensive queries
                    (recompute-and-save-stats! xtdb-node)
                    ;; Normal: try persisted first, fall back to expensive
                    (or (get-persisted-stats xtdb-node)
                        (recompute-and-save-stats! xtdb-node)))]
        (reset! stats-cache {:stats stats
                             :updated-at (System/currentTimeMillis)
                             :ttl-ms 30000})
        (when on-complete
          (on-complete stats))
        stats)
      (catch Exception e
        (println "Error refreshing stats:" (.getMessage e))
        nil))))
