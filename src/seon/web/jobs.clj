(ns seon.web.jobs
  "Job manager for bulk import operations.
   Manages a single active job at a time with status tracking."
  (:require [taoensso.timbre :as log]
            [seon.data.bulk-load :as bulk-load]
            [seon.web.sse :as sse]
            [seon.web.stats :as stats])
  (:import [java.time LocalDate Instant]
           [java.util UUID]))

;; Job state atom - single job at a time
(defonce job-state
  (atom {:current nil      ; Currently running job
         :history []}))    ; Completed/failed jobs (last N)

;; XTDB node reference - initialized at startup
(defonce xtdb-node (atom nil))

;; Forward declaration for progress callback
(declare update-progress!)

;; Auto-refresh on state change (Task 7: Hyperlith CQRS pattern)
;; When job-state changes, automatically trigger SSE updates
;; This eliminates the need for manual refresh calls
(defonce _state-watch
  (add-watch job-state :sse-auto-refresh
             (fn [_key _ref old-state new-state]
               (when (not= old-state new-state)
                 (sse/refresh-all!)))))

(defn init!
  "Initialize the job manager with the XTDB node.
   Called by the server component at startup."
  [node]
  (reset! xtdb-node node)
  (log/info "Job manager initialized with XTDB node"))

(defn get-node
  "Get the XTDB node reference."
  []
  @xtdb-node)

(defn new-job-id []
  (str (UUID/randomUUID)))

(defn get-current-job []
  (:current @job-state))

(defn get-job-history []
  (:history @job-state))

(defn job-running? []
  (some-> (get-current-job) :status (= :running)))

(defn start-import!
  "Starts a bulk import job. Returns {:ok job-id} or {:error message}.

   Args:
   - node: XTDB node
   - symbols: Vector of ticker symbols
   - start-date: LocalDate
   - end-date: LocalDate
   - opts: Options map (parallelism, etc.)"
  [node symbols start-date end-date opts]
  (if (job-running?)
    {:error "A job is already running"}
    (let [job-id (new-job-id)
          job {:id job-id
               :symbols symbols
               :start-date (str start-date)
               :end-date (str end-date)
               :status :running
               :started-at (str (Instant/now))
               :progress {:current-symbol nil
                          :current-day nil
                          :days-completed 0
                          :total-days 0
                          :records-loaded 0}
               :log []
               :future nil}]

      ;; Update state with new job
      (swap! job-state assoc :current job)

      ;; Start the import in a background thread
      (let [import-future
            (future
              (try
                (log/info "Starting bulk import job" {:job-id job-id :symbols symbols})

                (let [result (bulk-load/bulk-load-from-repl!
                              node symbols start-date end-date
                              (merge {:parallelism 4
                                      :progress-fn update-progress!}
                                     opts))]

                  ;; Mark job complete
                  (swap! job-state
                         (fn [state]
                           (-> state
                               (assoc-in [:current :status] :completed)
                               (assoc-in [:current :completed-at] (str (Instant/now)))
                               (assoc-in [:current :result] result)
                               (update :history conj (dissoc (:current state) :future))
                               (update :history #(take-last 10 %)))))

                  (log/info "Bulk import job completed" {:job-id job-id})

                  ;; Refresh dashboard stats after successful import
                  (log/info "Refreshing dashboard stats after import...")
                  (stats/recompute-and-save-stats! node))

                (catch InterruptedException _
                  (log/info "Job interrupted" {:job-id job-id})
                  (swap! job-state
                         (fn [state]
                           (-> state
                               (assoc-in [:current :status] :cancelled)
                               (assoc-in [:current :cancelled-at] (str (Instant/now)))
                               (update :history conj (dissoc (:current state) :future))))))

                (catch Exception e
                  (log/error e "Job failed" {:job-id job-id})
                  (swap! job-state
                         (fn [state]
                           (-> state
                               (assoc-in [:current :status] :failed)
                               (assoc-in [:current :failed-at] (str (Instant/now)))
                               (assoc-in [:current :error] (.getMessage e))
                               (update :history conj (dissoc (:current state) :future)))))

                  ;; Refresh stats even after failure (partial data may have loaded)
                  (log/info "Refreshing dashboard stats after failed import...")
                  (try
                    (stats/recompute-and-save-stats! node)
                    (catch Exception _)))))]

        ;; Store the future for cancellation
        (swap! job-state assoc-in [:current :future] import-future))

      {:ok job-id})))

(defn stop-job!
  "Stop the currently running job."
  []
  (if-let [job (get-current-job)]
    (if (= :running (:status job))
      (do
        (when-let [f (:future job)]
          (future-cancel f))
        (swap! job-state assoc-in [:current :status] :stopping)
        {:ok "Job stop requested"})
      {:error (str "Job is not running, status: " (:status job))})
    {:error "No current job"}))

(defn get-status
  "Get current job status for display."
  []
  (let [current (:current @job-state)
        history (:history @job-state)]
    {:current (when current
                (dissoc current :future))
     :history-count (count history)}))

;; Progress update hook - will be called by bulk loader
(defn update-progress!
  "Update progress of current job. Called by bulk loader hooks."
  [progress-data]
  (swap! job-state
         (fn [state]
           (if (:current state)
             (-> state
                 (update-in [:current :progress] merge progress-data)
                 (update-in [:current :log] conj
                            {:timestamp (str (Instant/now))
                             :message (str (:current-symbol progress-data) " day " (:current-day progress-data))}))
             state))))
