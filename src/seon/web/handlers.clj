(ns seon.web.handlers
  "HTTP request handlers."
  (:require [jsonista.core :as json]
            [clojure.string :as str]
            [seon.health :as health]
            [seon.web.html :as html]
            [seon.web.sse :as sse]
            [seon.web.logs :as logs]
            [seon.ai.agent :as agent]))

(defn health-check
  "Health check endpoint. Returns deep system health including pool status."
  [_request]
  (let [result (health/check {})
        status-code (if (= :healthy (::health/status result)) 200 503)]
    {:status status-code
     :headers {"Content-Type" "application/json"}
     :body (json/write-value-as-string
            {:status (name (::health/status result))
             :timestamp (str (::health/timestamp result))
             :startup-phase (name (::health/startup-phase result))
             :checks (::health/checks result)
             :resources (::health/resources result)})}))

(defn dashboard [_request]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (html/shim-page)})

;; ---------------------------------------------------------------------------
;; SSE Handlers (use var references for hot reload support)
;; ---------------------------------------------------------------------------
;; Pattern: Define render fn separately, pass var reference to render-handler.
;; This enables hot reload because vars deref to current binding on each call.
;; The after-ns-reload hook ensures handler objects are recreated on reload.

(defn- dashboard-sse-render
  "Render function for dashboard SSE. Defined separately for hot reload."
  [_request]
  (let [running-agents (agent/agents {})]
    (html/dashboard-content running-agents)))

(def dashboard-sse
  "SSE handler for dashboard updates."
  (sse/render-handler #'dashboard-sse-render))

(defn parse-form-body
  "Parse form-urlencoded body into a map."
  [body-str]
  (when body-str
    (into {}
          (for [pair (str/split body-str #"&")
                :let [[k v] (str/split pair #"=" 2)]]
            [(keyword (java.net.URLDecoder/decode k "UTF-8"))
             (java.net.URLDecoder/decode (or v "") "UTF-8")]))))

;; ========================================
;; Log Viewer Handlers
;; ========================================

(defn log-viewer
  "Serve the log viewer HTML shim page."
  [_request]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (html/log-viewer-shim)})

(defn- log-viewer-sse-render
  "Render function for log viewer SSE. Defined separately for hot reload."
  [_request]
  (logs/refresh-logs!)
  (let [state (logs/get-state)
        filtered-entries (logs/get-filtered-entries)]
    (html/log-viewer-content
     (assoc state :entries filtered-entries))))

(def log-viewer-sse
  "SSE handler for live log updates. Polls every 2 seconds."
  (sse/render-handler #'log-viewer-sse-render :poll-ms 2000))

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

;; ---------------------------------------------------------------------------
;; Hot Reload Support
;; ---------------------------------------------------------------------------
;; clj-reload calls this after reloading the namespace.
;; Recreates SSE handler objects so they use updated render functions.

(defn after-ns-reload
  "Called by clj-reload after namespace reload. Recreates SSE handlers."
  []
  (alter-var-root #'dashboard-sse
                  (constantly (sse/render-handler #'dashboard-sse-render)))
  (alter-var-root #'log-viewer-sse
                  (constantly (sse/render-handler #'log-viewer-sse-render :poll-ms 2000))))

