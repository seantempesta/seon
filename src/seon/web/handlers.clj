(ns seon.web.handlers
  "HTTP request handlers."
  (:require [jsonista.core :as json]
            [clojure.string :as str]
            [seon.web.html :as html]
            [seon.web.sse :as sse]
            [seon.web.logs :as logs]
            [seon.ai.agent :as agent]))

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
(def dashboard-sse
  (sse/render-handler
   (fn [_request]
     ;; Render namespace-focused dashboard content
     (let [running-agents (agent/agents {})]
       (html/dashboard-content running-agents)))))

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
