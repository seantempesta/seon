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
   [clj-reload.core :as reload]
   [clojure.java.io :as io]
   [clojure.java.shell]
   [clojure.pprint]
   [clojure.repl :refer [doc source apropos dir]]
   [clojure.spec.alpha :as s]
   [clojure.string]
   [clojure.tools.namespace.repl :as repl]
   [expound.alpha :as expound]
   [integrant.core :as ig]
   [integrant.repl :refer [clear go halt prep init reset-all]]
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

(repl/set-refresh-dirs "src" "test")

;; Initialize clj-reload for fast code reloading
;; Unlike tools.namespace, clj-reload:
;; - Preserves defonce values
;; - Only reloads what actually changed (no "reload world" on first call)
;; - Faster and more incremental
(reload/init {:dirs ["src" "env/dev/clj" "test"]
              :no-reload '#{user}})

(defn reload
  "Fast reload of changed code via clj-reload.
  Preserves defonce values and only reloads what changed.
  Use this for quick code sync between editor and REPL.

  For full system restart (when changing config, components), use (reset)."
  []
  (reload/reload))

(defn reset
  "Safe system reset that shuts down agents before reloading.

  Standard integrant.repl/reset can cause core.async protocol corruption
  when agents have open channels. This function:
  1. Shuts down all running agents (closes channels, destroys processes)
  2. Calls integrant.repl/reset

  This prevents the 'No implementation of method: :exec of protocol:
  #'clojure.core.async.impl.protocols/Executor' error."
  []
  ;; Shut down agents first to prevent core.async protocol corruption
  (try
    (require 'seon.ai.agent)
    (let [shutdown! (resolve 'seon.ai.agent/shutdown-all!)
          result (shutdown! {})]
      (when (pos? (:seon.ai.agent/shutdown-count result))
        (println "Shut down" (:seon.ai.agent/shutdown-count result) "agents before reset")))
    (catch Exception e
      (println "Warning: Could not shut down agents:" (.getMessage e))))
  ;; Now do the standard reset
  (integrant.repl/reset))

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
;; Agent Management (convenience wrappers)
;; ========================================
;; These mirror seon.ai.claude functions but auto-fill the XTDB node.
;; Use from any namespace via user/launch-agent!! etc.

(defn launch-agent!!
  "Launch agent and block until completion. Returns result map.

  Example: (user/launch-agent!! 'seon.trading \"Read the PRD and implement Phase 1.\")"
  [namespace prompt]
  ((requiring-resolve 'seon.ai.claude/launch-agent!!)
   #:seon.ai{:node (xtdb-node) :namespace namespace :prompt prompt}))

(defn launch-agent!
  "Launch agent without blocking. Returns handle with ::ai/session-id.

  Example: (user/launch-agent! 'seon.trading \"Implement feature X\")"
  [namespace prompt]
  ((requiring-resolve 'seon.ai.claude/launch-agent!)
   #:seon.ai{:node (xtdb-node) :namespace namespace :prompt prompt}))

(defn agents
  "List running agents with status, namespace, session-id, port."
  []
  ((requiring-resolve 'seon.ai.claude/agents) {}))

(defn interrupt-agent!
  "Interrupt an agent by session-id (4-char hex like \"a1b2\")."
  [session-id]
  ((requiring-resolve 'seon.ai.claude/interrupt!)
   #:seon.ai{:session-id session-id}))

(defn agent-result
  "Get result from a completed agent by session-id."
  [session-id]
  ((requiring-resolve 'seon.ai.claude/get-result)
   #:seon.ai{:session-id session-id}))

(defn wait-for-agent!!
  "Block until a running agent completes. Use to re-attach after MCP timeout.

  Example: (user/wait-for-agent!! \"a1b2\")"
  [session-id]
  ((requiring-resolve 'seon.ai.claude/wait-for-agent!!)
   #:seon.ai{:session-id session-id}))

(defn agent-messages
  "Get recent messages from an agent to check progress.

  Example: (user/agent-messages \"a1b2\")"
  [session-id]
  ((requiring-resolve 'seon.ai.claude/agent-messages)
   #:seon.ai{:session-id session-id}))

(defn agent-health
  "Get comprehensive health status for an agent.

  Returns a map with:
    :session-id      - The agent session ID
    :status          - Current status (:running, :completed, :failed, etc.)
    :process-alive?  - Whether the Java Process is still alive
    :result-subtype  - Why it failed (\"error_max_turns\", etc.) if applicable
    :last-activity   - When the agent last had activity
    :idle-seconds    - Seconds since last activity
    :running-seconds - Total time the agent has been running
    :diagnosis       - Human-readable health assessment

  Example: (user/agent-health \"a1b2\")"
  [session-id]
  (let [;; Get running agent info
        running-agents ((requiring-resolve 'seon.ai.claude/agents) {})
        running (first (filter #(= session-id (:seon.ai/session-id %)) running-agents))
        ;; Get result info from DB
        result ((requiring-resolve 'seon.ai.claude/get-result)
                #:seon.ai{:session-id session-id})
        status (:seon.ai.claude/agent-status result)
        now (java.time.Instant/now)]
    (if running
      ;; Running agent - compute health metrics
      (let [last-activity (:seon.ai.claude/last-activity-at running)
            process-alive? (:seon.ai.claude/process-alive? running)
            idle-ms (when last-activity
                      (- (.toEpochMilli now) (.toEpochMilli last-activity)))
            idle-seconds (when idle-ms (/ idle-ms 1000.0))
            diagnosis (cond
                        (not process-alive?)
                        "STUCK: Process is dead but status shows running"

                        (and idle-seconds (> idle-seconds 300))
                        (str "WARNING: No activity for " (int idle-seconds) " seconds")

                        (and idle-seconds (> idle-seconds 60))
                        (str "SLOW: Idle for " (int idle-seconds) " seconds")

                        :else "HEALTHY: Agent is active")]
        {:session-id session-id
         :status (:seon.ai.claude/agent-status running)
         :process-alive? process-alive?
         :last-activity last-activity
         :idle-seconds idle-seconds
         :diagnosis diagnosis})
      ;; Completed/failed agent - get info from result
      (cond-> {:session-id session-id
               :status status
               :process-alive? false
               :diagnosis (case status
                            :completed "DONE: Agent completed successfully"
                            :failed "FAILED: Agent encountered an error"
                            :interrupted "INTERRUPTED: Agent was stopped"
                            :terminated "TERMINATED: Agent process died"
                            "UNKNOWN: Agent not found")}
        (:seon.ai.claude/result-subtype result)
        (assoc :result-subtype (:seon.ai.claude/result-subtype result))

        (:seon.ai.claude/duration-ms result)
        (assoc :running-seconds (/ (:seon.ai.claude/duration-ms result) 1000.0))

        (:seon.ai.claude/cost-usd result)
        (assoc :cost-usd (:seon.ai.claude/cost-usd result))

        (:seon.ai.claude/num-turns result)
        (assoc :num-turns (:seon.ai.claude/num-turns result))))))

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
