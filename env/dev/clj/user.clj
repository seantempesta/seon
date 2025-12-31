(ns user
  "Userspace functions you can run by default in your local REPL.

  THE REAL KIT PATTERN (FINALLY UNDERSTOOD!):
  - BOTH ./bin/run AND (go) use integrant.repl under the hood
  - core/-main calls integrant.repl/go internally
  - This means EVERYTHING goes through integrant.repl.state/system
  - Therefore (reset) ALWAYS works, regardless of how system was started!

  This is the key insight: runner doesn't bypass integrant.repl,
  it USES integrant.repl. Unified state management achieved."
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell]
   [clojure.pprint]
   [clojure.spec.alpha :as s]
   [clojure.string]
   [clojure.tools.namespace.repl :as repl]
   [expound.alpha :as expound]
   [integrant.core :as ig]
   [integrant.repl :refer [clear go halt prep init reset reset-all]]
   [integrant.repl.state :as state]
   [seon.ai.gemini :as gemini]
   [seon.config :as config]))

(alter-var-root #'s/*explain-out* (constantly expound/printer))

(add-tap (bound-fn* clojure.pprint/pprint))

(defn dev-prep!
  "Configure integrant.repl for dev profile.
  Uses ig/expand (modern) instead of deprecated ig/prep."
  []
  (integrant.repl/set-prep! (fn []
                              (let [cfg (config/system-config {:profile :dev})]
                                (ig/load-namespaces cfg)
                                (ig/expand cfg)))))

(defn test-prep!
  "Configure integrant.repl for test profile.
  Uses ig/expand (modern) instead of deprecated ig/prep."
  []
  (integrant.repl/set-prep! (fn []
                              (let [cfg (config/system-config {:profile :test})]
                                (ig/load-namespaces cfg)
                                (ig/expand cfg)))))

;; Use dev profile by default. Change to test-prep! for running tests.
(dev-prep!)

(repl/set-refresh-dirs "src")

(def refresh repl/refresh)

;; Convenience accessors - now always use state/system
(defn xtdb-node
  "Get the XTDB node from the running system."
  []
  (when state/system
    (:seon/xtdb-node state/system)))

(defn dev-xtdb-node
  "Get the Dev Hook XTDB node from the running system.
  This is a separate database for dev hook data (edit events, review events)."
  []
  (when state/system
    (:seon.dev/xtdb-node state/system)))

(defn schema-registry
  "Get the Malli schema registry from the running system."
  []
  (when state/system
    (:seon/schema-registry state/system)))

(defn status
  "Show system status."
  []
  (if state/system
    (do
      (println "System running with" (count state/system) "components:")
      (doseq [k (sort (keys state/system))]
        (println "  " k))
      (when-let [node (xtdb-node)]
        (println "")
        (println "XTDB status:")
        (require 'seon.db.node)
        (clojure.pprint/pprint ((resolve 'seon.db.node/status) node))))
    (println "System not running. Start with: (go) or ./bin/run")))

;; ========================================
;; AI Research (use when stuck!)
;; ========================================

(defn search
  "Search the web via Gemini. Use this when you're stuck or need current info.

  Examples:
    (search \"XTDB v2 SQL syntax for temporal queries\")
    (search \"Clojure Malli coercion patterns\")"
  [query]
  (gemini/search {::gemini/prompt query}))

(defn ask
  "Ask Gemini a question (no web search, uses model knowledge).

  Examples:
    (ask \"Explain the difference between XTQL and SQL in XTDB\")"
  [query]
  (gemini/ask {::gemini/prompt query}))

;; ========================================
;; Database Management
;; ========================================

(defn db-reset!
  "Delete all XTDB data and restart with fresh database.
  WARNING: This deletes all data!"
  []
  (println "Stopping system...")
  (halt)
  (println "Deleting XTDB data directory...")
  (let [data-dir (io/file "data/xtdb")]
    (when (.exists data-dir)
      (doseq [f (reverse (file-seq data-dir))]
        (.delete f))))
  (println "Starting fresh system...")
  (go)
  (println "Database reset complete."))

(defn list-backups
  "List available XTDB backups."
  []
  (let [backup-dir (io/file "data/backups")]
    (if (.exists backup-dir)
      (doseq [f (sort (.listFiles backup-dir))]
        (println (.getName f)))
      (println "No backups directory found at data/backups"))))

;; ========================================
;; Log Parsing and Analysis Functions
;; ========================================

(defn parse-log-line
  "Parse a logback log line into structured data.
  Format: 2025-12-02 11:39:25,396 [main] INFO  seon.core - Message
  Returns map with :timestamp, :thread, :level, :logger, :message, :raw"
  [line]
  (when (and line (string? line))
    (when-let [[_ timestamp thread level logger message]
               (re-matches #"(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2},\d{3}) \[([^\]]+)\] (\w+)\s+([^\s]+) - (.*)"
                           line)]
      {:timestamp timestamp
       :thread thread
       :level (keyword (clojure.string/lower-case level))
       :logger logger
       :message message
       :raw line})))

(defn read-log-file
  "Read and parse a log file, returning parsed entries.
  Hard-capped at max-lines to prevent context blowout."
  [file-path max-lines]
  (try
    (let [result (clojure.java.shell/sh "sh" "-c" (str "tail -n " max-lines " " file-path))]
      (if (zero? (:exit result))
        (->> (clojure.string/split-lines (:out result))
             (keep parse-log-line)
             vec)
        []))
    (catch Exception e
      [])))

(defn log-health
  "Quick health check - error count, last error, warnings.
  Returns structured data, hard-capped at ~20 lines of output."
  []
  (let [app-logs (read-log-file "logs/app.log" 500)
        errors (filter #(= :error (:level %)) app-logs)
        warnings (filter #(= :warn (:level %)) app-logs)
        last-error (last errors)]
    {:total-checked (count app-logs)
     :error-count (count errors)
     :warning-count (count warnings)
     :last-error (when last-error
                   {:timestamp (:timestamp last-error)
                    :logger (:logger last-error)
                    :message (clojure.string/trim
                              (subs (:message last-error) 0 (min 200 (count (:message last-error)))))})
     :status (cond
               (> (count errors) 10) :unhealthy
               (> (count errors) 0) :degraded
               :else :healthy)}))

(defn log-errors
  "Get recent errors with surrounding context.
  Returns structured data, hard-capped at 100 lines total."
  ([] (log-errors {}))
  ([{:keys [max-errors context-lines]
     :or {max-errors 5 context-lines 2}}]
   (let [all-logs (read-log-file "logs/app.log" 200)
         error-indices (keep-indexed
                        (fn [idx entry]
                          (when (= :error (:level entry)) idx))
                        all-logs)
         errors (for [error-idx (take max-errors error-indices)
                      :let [start (max 0 (- error-idx context-lines))
                            end (min (count all-logs) (+ error-idx context-lines 1))
                            context (subvec all-logs start end)]]
                  {:error-line (get all-logs error-idx)
                   :context context})]
     {:error-count (count error-indices)
      :showing (count errors)
      :errors errors})))

(defn log-context
  "Get lines around a specific line number (1-indexed).
  Returns structured data, hard-capped at 20 lines."
  [line-number & {:keys [context-lines]
                  :or {context-lines 5}}]
  (let [all-logs (read-log-file "logs/app.log" 1000)
        idx (dec line-number)
        start (max 0 (- idx context-lines))
        end (min (count all-logs) (+ idx context-lines 1))]
    (when (< idx (count all-logs))
      {:requested-line line-number
       :actual-index idx
       :context (subvec all-logs start end)})))

(defn log-tail
  "Safe tail with hard caps and filtering.
  Returns structured data, never more than 100 lines."
  [& {:keys [file lines level grep]
      :or {file :app lines 50}}]
  (let [max-lines (min lines 100)  ; Hard cap
        log-file (case file
                   :app   "logs/app.log"
                   :error "logs/error.log"
                   :xtdb  "logs/xtdb.log"
                   (str "logs/" (name file) ".log"))
        entries (read-log-file log-file max-lines)
        filtered (cond->> entries
                   level (filter #(= level (:level %)))
                   grep (filter #(clojure.string/includes? (:raw %) grep)))]
    {:file log-file
     :requested-lines lines
     :returned-lines (count filtered)
     :capped-at max-lines
     :filters {:level level :grep grep}
     :entries filtered}))

;; ========================================
;; Legacy Functions (for backwards compat)
;; ========================================

(defn logs
  "View recent log entries. Useful for AI agents and debugging.
  NOW RETURNS STRUCTURED DATA instead of printing.

  Options:
    :file    - Which log file to read (:app, :error, :xtdb). Default: :app
    :lines   - Number of lines to show. Default: 50, max: 100
    :level   - Filter by log level (:error, :warn, :info, :debug). Default: all
    :grep    - Filter lines containing string. Default: nil

  Examples:
    (logs)                          ; Last 50 lines from app.log
    (logs :lines 100)               ; Last 100 lines (hard-capped)
    (logs :file :error)             ; Last 50 lines from error.log
    (logs :level :error)            ; Only ERROR level entries
    (logs :grep \"XTDB\")             ; Lines containing 'XTDB'
    (logs :file :error :lines 20)   ; Last 20 errors"
  [& {:keys [file lines level grep]
      :or {file :app lines 50}}]
  (log-tail :file file :lines lines :level level :grep grep))

(defn log-summary
  "Show a summary of recent log activity across all log files.
  Great for AI agents to quickly understand system health.
  NOW RETURNS STRUCTURED DATA instead of printing."
  []
  (let [health (log-health)
        file-stats (for [log-file ["logs/app.log" "logs/error.log" "logs/xtdb.log"]]
                     (try
                       (let [result (clojure.java.shell/sh "sh" "-c"
                                                           (str "wc -l " log-file " 2>/dev/null | awk '{print $1}'"))]
                         {:file log-file
                          :lines (if (zero? (:exit result))
                                   (Integer/parseInt (clojure.string/trim (:out result)))
                                   0)})
                       (catch Exception e
                         {:file log-file :lines 0 :error (.getMessage e)})))]
    {:health health
     :file-stats file-stats
     :recent-logs (take 10 (:entries (log-tail :lines 10)))}))

(comment
  (go)
  (reset)

  ;; View logs
  (logs)                    ; Last 50 lines from app.log
  (logs :file :error)       ; View error log
  (logs :level :error)      ; Only errors from app.log
  (logs :grep "compaction") ; Lines mentioning compaction
  (log-summary)             ; Overall log health check
  )
