(ns seon.web.logs
  "Log viewer state management and log fetching.

  Manages live log updates similar to jobs.clj pattern."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [taoensso.timbre :as log]))

;; ========================================
;; State Management
;; ========================================

(defonce log-state
  (atom {:entries []
         :level-filter :all
         :max-entries 200}))

;; ========================================
;; Log Parsing
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
       :level (keyword (str/lower-case level))
       :logger logger
       :message message
       :raw line})))

(defn read-log-file
  "Read recent log entries from a file, hard-capped at max-lines."
  [file-path max-lines]
  (try
    (let [result (shell/sh "sh" "-c" (str "tail -n " max-lines " " file-path))]
      (if (zero? (:exit result))
        (->> (str/split-lines (:out result))
             (keep parse-log-line)
             vec)
        []))
    (catch Exception e
      (log/error e "Error reading log file" {:file file-path})
      [])))

;; ========================================
;; State Accessors
;; ========================================

(defn get-state
  "Get current log viewer state."
  []
  @log-state)

(defn set-level-filter!
  "Set the log level filter."
  [level]
  (swap! log-state assoc :level-filter level))

(defn refresh-logs!
  "Reload logs from disk and update state.
  This is called on-demand or via a background poller."
  ([] (refresh-logs! "logs/app.log"))
  ([file-path]
   (let [max-entries (:max-entries @log-state)
         entries (read-log-file file-path max-entries)]
     (swap! log-state assoc :entries entries)
     (log/debug "Refreshed logs" {:count (count entries) :file file-path})
     entries)))

(defn get-filtered-entries
  "Get log entries filtered by current level filter."
  []
  (let [{:keys [entries level-filter]} @log-state]
    (if (= :all level-filter)
      entries
      (filter #(= level-filter (:level %)) entries))))

