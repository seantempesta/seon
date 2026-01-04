(ns seon.trading.bulk-load
  "CLI script for bulk loading options data.

  Usage:
    clojure -M:dev -m seon.trading.bulk-load SYMBOL1 SYMBOL2 ... --start YYYY-MM-DD --end YYYY-MM-DD

  Example:
    clojure -M:dev -m seon.trading.bulk-load SPY AAPL NVDA --start 2025-05-28 --end 2025-11-27"
  (:require [seon.trading.ingest :as ingest]
            [seon.trading.validation :refer [*quiet*]]
            [seon.trading.thetadata :as theta]
            [seon.trading.ingestion-state :as state]
            [seon.trading.date-utils :refer [local-date->eod-instant]]
            [seon.db.node :as node]
            [xtdb.node :as xtn]
            [clojure.java.io :as io])
  (:import [java.time LocalDate]
           [java.time.format DateTimeFormatter])
  (:gen-class))

(def ^:private date-formatter (DateTimeFormatter/ofPattern "yyyy-MM-dd"))

(defn- parse-date [s]
  (LocalDate/parse s date-formatter))

(defn- parse-args [args]
  (loop [remaining args
         symbols []
         opts {}]
    (if (empty? remaining)
      (assoc opts :symbols symbols)
      (let [[arg & more] remaining]
        (cond
          (= "--start" arg)
          (recur (rest more) symbols (assoc opts :start-date (parse-date (first more))))

          (= "--end" arg)
          (recur (rest more) symbols (assoc opts :end-date (parse-date (first more))))

          (= "--db-path" arg)
          (recur (rest more) symbols (assoc opts :db-path (first more)))

          (= "--parallelism" arg)
          (recur (rest more) symbols (assoc opts :parallelism (Integer/parseInt (first more))))

          :else
          (recur more (conj symbols arg) opts))))))

(defn process-daily-items!
  "Process daily work items with bounded parallelism and checkpointing.

   For each batch of `parallelism` items:
   1. Check circuit breaker (throw if open)
   2. Process batch in parallel via pmap
   3. Transform, validate, and ingest each fetched result
   4. Checkpoint progress after each successful ingestion

   Returns map with :results and :stats.
   Results exclude :data field to save memory.

   Args:
     node - XTDB node
     symbol - Symbol being processed (for logging)
     items - Vector of work items from plan-daily-work
     opts - Options map:
       :parallelism - Number of parallel API calls (default 4)
       :progress-fn - Optional callback fn called with progress map after each day

   Stats tracked:
     :records - Total records successfully ingested
     :fetched - Number of dates successfully fetched
     :errors - Number of failed fetches
     :no-data - Number of dates with no data"
  [node symbol items {:keys [parallelism progress-fn] :or {parallelism 4}}]
  (let [results (atom [])
        stats (atom {:records 0 :errors 0 :no-data 0 :fetched 0})
        total (count items)]

    (println (format "\n[%s] Processing %d days with parallelism=%d"
                     symbol total parallelism))

    (doseq [batch (partition-all parallelism items)]
      ;; Circuit breaker check before each batch
      (when (theta/circuit-open?)
        (throw (ex-info "Circuit breaker open"
                        {:type :circuit-open
                         :symbol symbol
                         :completed @results
                         :stats @stats})))

      ;; Process batch in parallel
      (let [fetched (doall (pmap ingest/execute-daily-work-item! batch))]
        (doseq [{:keys [symbol date data status error records] :as result} fetched]
          (case status
            :fetched
            (do
              ;; Transform and validate
              (let [docs (->> data
                              (map ingest/thetadata->xtdb-doc)
                              (remove nil?)
                              vec)
                    valid-count (count docs)]

                ;; Ingest batch if we have valid docs
                (when (seq docs)
                  (let [vf (or (:xt/valid-from (first docs))
                               (local-date->eod-instant date))]
                    (doseq [b (partition-all 1000 docs)]
                      (ingest/ingest-batch! node b vf))))

                ;; Checkpoint progress
                (state/mark-date-done! node symbol date valid-count)
                (swap! stats update :records + valid-count)
                (swap! stats update :fetched inc)

                (let [days-done (inc (count @results))]
                  (println (format "[%s] ✓ %s: %d records (%d/%d days complete)"
                                   symbol date valid-count days-done total))
                  ;; Call progress callback if provided
                  (when progress-fn
                    (progress-fn {:current-symbol symbol
                                  :current-day (str date)
                                  :days-completed days-done
                                  :total-days total
                                  :records-loaded (:records @stats)})))))

            :no-data
            (do
              (state/mark-date-done! node symbol date 0)
              (swap! stats update :no-data inc)
              (println (format "[%s] - %s: no data" symbol date)))

            :failed
            (do
              (swap! stats update :errors inc)
              (println (format "[%s] ✗ %s: %s" symbol date error)))

            :circuit-open
            (throw (ex-info "Circuit breaker open during fetch"
                            {:type :circuit-open
                             :symbol symbol
                             :completed @results
                             :stats @stats})))

          ;; Store result without data (save memory)
          (swap! results conj (dissoc result :data)))))

    {:results @results :stats @stats}))

(defn import-status
  "Query current import progress and system health.

  Returns a map with:
    :symbols-in-progress - Vector of symbols currently being imported
    :all-states - All ingestion states (in-progress, complete, failed)
    :total-option-records - Count of :option-greeks records in database
    :circuit-breaker - Circuit breaker status from thetadata
    :memory - JVM memory usage stats

  Use this to monitor long-running imports without interrupting them.

  Args:
    node - XTDB node

  Example:
    (import-status (xtdb-node))
    ;; => {:symbols-in-progress [{:symbol \"SPY\" :last-date ...}]
    ;;     :circuit-breaker {:state :closed :consecutive-failures 0}
    ;;     :total-option-records 1234567
    ;;     :memory {:used-mb 512 :max-mb 4096}}"
  [node]
  (let [runtime (Runtime/getRuntime)
        ;; Get in-progress symbols
        in-progress (state/list-in-progress node)
        ;; Get all states for overview
        all-states (state/list-all-states node)
        ;; Count total option records (using SQL)
        record-count (-> (node/q node "SELECT COUNT(*) as cnt FROM option_greeks")
                         first
                         :cnt
                         (or 0))]
    {:symbols-in-progress (mapv #(select-keys % [:ingestion/symbol
                                                 :ingestion/last-date
                                                 :ingestion/records-count
                                                 :ingestion/updated-at])
                                in-progress)
     :all-states (mapv #(select-keys % [:ingestion/symbol
                                        :ingestion/status
                                        :ingestion/records-count])
                       all-states)
     :total-option-records record-count
     :circuit-breaker (theta/circuit-status)
     :rate-limit (theta/rate-limit-status)
     :memory {:used-mb (quot (- (.totalMemory runtime) (.freeMemory runtime))
                             1048576)
              :max-mb (quot (.maxMemory runtime) 1048576)
              :free-mb (quot (.freeMemory runtime) 1048576)}}))

(defn resilient-bulk-load!
  "Bulk load with resumption support and bounded parallelism.

   For each symbol:
   1. Get completed dates from state table
   2. Generate daily work items (skipping completed)
   3. Process with bounded parallelism and checkpointing
   4. Handle circuit-open gracefully

   Returns:
     {:success bool
      :results {symbol -> {:results [...] :stats {...}}}
      :stats {:total-records int :symbols-completed int :symbols-failed int}}

   Args:
     node - XTDB node
     symbols - Vector of symbol strings
     start-date - LocalDate
     end-date - LocalDate
     opts - Options map:
       :parallelism - Number of parallel API calls (default 4)
       :progress-fn - Optional callback fn called with progress map after each day"
  ([node symbols start-date end-date]
   (resilient-bulk-load! node symbols start-date end-date {}))
  ([node symbols start-date end-date opts]
   (let [all-results (atom {})
         global-stats (atom {:total-records 0
                             :symbols-completed 0
                             :symbols-failed 0})
         parallelism (:parallelism opts 4)
         progress-fn (:progress-fn opts)]

     (println (format "\n=== Bulk Load: %d symbols from %s to %s ==="
                      (count symbols) start-date end-date))
     (println (format "Parallelism: %d" parallelism))

     (doseq [symbol symbols]
       (try
         (println (format "\n--- Starting %s ---" symbol))

         ;; Get completed dates (for resume)
         (let [completed (state/get-completed-dates node symbol)]
           (when (seq completed)
             (println (format "[%s] Resuming: %d days already completed"
                              symbol (count completed))))

           ;; Generate work items
           (let [items (ingest/plan-daily-work symbol start-date end-date completed)]
             (if (empty? items)
               (do
                 (println (format "[%s] ✓ All days already completed" symbol))
                 (swap! all-results assoc symbol {:results [] :stats {:records 0}})
                 (swap! global-stats update :symbols-completed inc))

               (do
                 (println (format "[%s] %d days to process" symbol (count items)))

                 ;; Process with parallelism
                 (let [{:keys [results stats]} (process-daily-items!
                                                node symbol items
                                                {:parallelism parallelism
                                                 :progress-fn progress-fn})]
                   (swap! all-results assoc symbol {:results results :stats stats})
                   (swap! global-stats update :total-records + (:records stats))
                   (swap! global-stats update :symbols-completed inc)

                   (println (format "\n[%s] Complete: %d records, %d fetched, %d no-data, %d errors"
                                    symbol
                                    (:records stats)
                                    (:fetched stats)
                                    (:no-data stats)
                                    (:errors stats))))))))

         (catch clojure.lang.ExceptionInfo e
           (if (= :circuit-open (:type (ex-data e)))
             (do
               (println (format "\n[%s] Circuit breaker open - stopping bulk load" symbol))
               (println "Progress has been saved. You can resume after ThetaData Terminal is restarted.")
               (swap! global-stats update :symbols-failed inc)
               (swap! all-results assoc symbol {:error "Circuit breaker open"
                                                :partial-results (-> e ex-data :completed)
                                                :partial-stats (-> e ex-data :stats)}))
             (do
               (println (format "\n[%s] Error: %s" symbol (ex-message e)))
               (swap! global-stats update :symbols-failed inc)
               (swap! all-results assoc symbol {:error (ex-message e)}))))

         (catch Exception e
           (println (format "\n[%s] Unexpected error: %s" symbol (ex-message e)))
           (swap! global-stats update :symbols-failed inc)
           (swap! all-results assoc symbol {:error (ex-message e)}))))

     (let [final-stats @global-stats
           success? (zero? (:symbols-failed final-stats))]
       (println (format "\n=== Bulk Load Complete ==="))
       (println (format "Total records: %d" (:total-records final-stats)))
       (println (format "Symbols completed: %d" (:symbols-completed final-stats)))
       (println (format "Symbols failed: %d" (:symbols-failed final-stats)))

       {:success success?
        :results @all-results
        :stats final-stats}))))

(defn bulk-load-from-repl!
  "Load options data using an existing XTDB node (e.g., from Integrant).

  Use this from the REPL instead of -main to leverage the Integrant-managed node.
  This avoids creating a duplicate standalone node and uses the already-running
  system's XTDB instance.

  Args:
    node - XTDB node (from (user/xtdb-node) or system)
    symbols - Vector of symbol strings
    start-date - java.time.LocalDate
    end-date - java.time.LocalDate
    opts - (optional) Options map with :parallelism (default 4)

  Example:
    (require '[seon.data.bulk-load :as bulk-load])
    (bulk-load/bulk-load-from-repl!
      (user/xtdb-node)
      [\"SPY\" \"AAPL\"]
      (java.time.LocalDate/of 2024 11 25)
      (java.time.LocalDate/of 2024 11 27)
      {:parallelism 8})"
  ([node symbols start-date end-date]
   (bulk-load-from-repl! node symbols start-date end-date {}))
  ([node symbols start-date end-date opts]
   {:pre [(some? node) (seq symbols) (some? start-date) (some? end-date)]}
   (binding [*quiet* false]
     (resilient-bulk-load! node symbols start-date end-date opts))))

(defn- try-get-repl-node
  "Check if there's a running nREPL and hint to use REPL function instead."
  []
  (try
    (when (.exists (io/file ".nrepl-port"))
      (let [port (Integer/parseInt (slurp ".nrepl-port"))]
        (println "")
        (println "Tip: Found nREPL on port" port)
        (println "     Consider using the REPL for faster loads:")
        (println "     (bulk-load/bulk-load-from-repl! (xtdb-node) [\"SPY\"] start end)")
        (println "")))
    (catch Exception _ nil)))

(defn -main [& args]
  (let [{:keys [symbols start-date end-date db-path parallelism]
         :or {db-path "data/xtdb" parallelism 4}} (parse-args args)]
    (when (or (empty? symbols) (nil? start-date) (nil? end-date))
      (println "Usage: clojure -M:dev -m seon.data.bulk-load SYMBOL... --start YYYY-MM-DD --end YYYY-MM-DD [--parallelism N]")
      (println "")
      (println "Options:")
      (println "  --parallelism N  Number of parallel API calls (default: 4)")
      (println "")
      (println "Example:")
      (println "  clojure -M:dev -m seon.data.bulk-load SPY AAPL --start 2024-11-25 --end 2024-11-27 --parallelism 8")
      (System/exit 1))

    ;; Hint about REPL option if nREPL is running
    (try-get-repl-node)

    (println "Starting standalone XTDB node at" db-path "...")
    (let [compactor-threads (max 2 (quot (.availableProcessors (Runtime/getRuntime)) 2))
          _ (println "Using" compactor-threads "compactor threads")
          node (xtn/start-node {:log [:local {:path (io/file db-path "log")}]
                                :storage [:local {:path (io/file db-path "objects")}]
                                :compactor {:threads compactor-threads}})]
      (try
        (let [result (resilient-bulk-load! node symbols start-date end-date {:parallelism parallelism})]
          (if (:success result)
            (System/exit 0)
            (System/exit 1)))
        (finally
          (.close node))))))
