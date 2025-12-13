(ns ml-options.web.handlers
  "HTTP request handlers."
  (:require [jsonista.core :as json]
            [clojure.string :as str]
            [ml-options.web.html :as html]
            [ml-options.web.sse :as sse]
            [ml-options.web.jobs :as jobs]
            [ml-options.web.logs :as logs]
            [ml-options.web.stats :as stats])
  (:import [java.time LocalDate]))

(defn health [_request]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/write-value-as-string
          {:status "ok"
           :timestamp (str (java.time.Instant/now))})})

(defn dashboard [_request]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (html/shim-page)})

;; SSE handler for dashboard updates
;; Using hyperlith pattern: view = f(state)
;; Non-blocking: Uses cached stats only, triggers async refresh if stale
(def dashboard-sse
  (sse/render-handler
   (fn [_request]
     ;; Render full dashboard content on each refresh
     ;; CRITICAL: Never block on DB queries here - use cached stats only
     (let [job-status (jobs/get-status)
           node (jobs/get-node)
           ;; Non-blocking: only read from cache, never query
           db-stats (stats/get-cached-stats-nonblocking)]
       ;; If cache is cold, trigger async refresh with SSE update on completion
       (when (and node (nil? db-stats))
         (stats/refresh-stats-async! node
                                     :on-complete (fn [_] (sse/refresh-all!))))
       (html/dashboard-content job-status db-stats)))))

(defn parse-form-body
  "Parse form-urlencoded body into a map."
  [body-str]
  (when body-str
    (into {}
          (for [pair (str/split body-str #"&")
                :let [[k v] (str/split pair #"=" 2)]]
            [(keyword (java.net.URLDecoder/decode k "UTF-8"))
             (java.net.URLDecoder/decode (or v "") "UTF-8")]))))

(defn start-import
  "Start a new bulk import job."
  [request]
  (try
    ;; Validate request body exists
    (when-not (:body request)
      (throw (ex-info "Request body is required" {:status 400})))

    (let [body-str (slurp (:body request))
          content-type (get-in request [:headers "content-type"] "")
          ;; Parse based on content type
          body (if (str/includes? content-type "application/json")
                 (json/read-value body-str json/keyword-keys-object-mapper)
                 (parse-form-body body-str))
          symbols-str (:symbols body)
          start-date-str (:startDate body)
          end-date-str (:endDate body)]

      ;; Validate required fields
      (when (str/blank? symbols-str)
        (throw (ex-info "symbols is required" {:status 400 :field :symbols})))
      (when (str/blank? start-date-str)
        (throw (ex-info "startDate is required" {:status 400 :field :startDate})))
      (when (str/blank? end-date-str)
        (throw (ex-info "endDate is required" {:status 400 :field :endDate})))

      (let [;; Parse symbols (comma-separated)
            symbols (vec (map str/trim (str/split symbols-str #",")))

            ;; Parse dates
            start-date (LocalDate/parse start-date-str)
            end-date (LocalDate/parse end-date-str)

            ;; Get XTDB node from job manager
            node (jobs/get-node)

            result (jobs/start-import! node symbols start-date end-date {:parallelism 4})]

        {:status 200
         :headers {"Content-Type" "application/json"}
         :body (json/write-value-as-string result)}))

    (catch java.time.format.DateTimeParseException e
      {:status 400
       :headers {"Content-Type" "application/json"}
       :body (json/write-value-as-string
              {:error (str "Date could not be parsed: " (.getMessage e))})})

    (catch Exception e
      {:status 400
       :headers {"Content-Type" "application/json"}
       :body (json/write-value-as-string {:error (.getMessage e)})})))

(defn stop-import
  "Stop the currently running import job."
  [_request]
  (try
    (let [result (jobs/stop-job!)]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-value-as-string result)})

    (catch Exception e
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/write-value-as-string {:error (.getMessage e)})})))

(defn job-status
  "Get current job status."
  [_request]
  {:status 200
   :headers {"Content-Type" "application/json"}
   :body (json/write-value-as-string (jobs/get-status))})

(defn database-stats
  "Get database statistics (cached for 30 seconds)."
  [_request]
  (try
    (let [node (jobs/get-node)
          db-stats (stats/get-cached-stats node)]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-value-as-string db-stats)})
    (catch Exception e
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/write-value-as-string {:error (.getMessage e)})})))

;; ========================================
;; Log Viewer Handlers
;; ========================================

(defn log-viewer [_request]
  "Serve the log viewer HTML shim page."
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (html/log-viewer-shim)})

(def log-viewer-sse
  "SSE handler for live log updates."
  (sse/render-handler
   (fn [_request]
     ;; Render filtered log entries on each refresh
     (let [state (logs/get-state)
           filtered-entries (logs/get-filtered-entries)]
       (html/log-viewer-content
        (assoc state :entries filtered-entries))))))

(defn log-filter
  "Update log level filter."
  [request]
  (try
    (let [body-str (slurp (:body request))
          params (parse-form-body body-str)
          level-str (:level params)
          level (keyword level-str)]
      (logs/set-level-filter! level)
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-value-as-string {:ok "Filter updated" :level level})})
    (catch Exception e
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/write-value-as-string {:error (.getMessage e)})})))

(defn log-refresh
  "Manually refresh logs from disk."
  [_request]
  (try
    (let [entries (logs/refresh-logs!)]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-value-as-string {:ok "Logs refreshed" :count (count entries)})})
    (catch Exception e
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/write-value-as-string {:error (.getMessage e)})})))

(defn log-toggle-scroll
  "Toggle auto-scroll setting."
  [_request]
  (try
    (logs/toggle-auto-scroll!)
    (let [auto-scroll (:auto-scroll (logs/get-state))]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body (json/write-value-as-string {:ok "Auto-scroll toggled" :auto-scroll auto-scroll})})
    (catch Exception e
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/write-value-as-string {:error (.getMessage e)})})))
