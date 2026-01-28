(ns seon.db.datalevin.server
  "Datalevin server component for Integrant.

   Runs Datalevin as a server process inside the JVM, enabling multiple
   agent processes to connect as clients to isolated namespace databases.

   ## Configuration

   ```clojure
   {:seon/datalevin-server
    {:port 8898           ; Server listening port
     :root \"data/datalevin\"  ; Root directory for all databases
     :opts {:idle-timeout 300000}}}  ; Optional server options
   ```

   ## Health Check

   ```clojure
   (require '[seon.db.datalevin.server :as dtlv-server])
   (dtlv-server/healthy? {::dtlv-server/port 8898})
   ;; => {::dtlv-server/ok true ::dtlv-server/latency-ms 2}
   ```

   ## Usage Notes

   - Server runs in-process (same JVM as orchestrator)
   - Each namespace gets its own database under root/
   - Clients connect via URI: dtlv://user:pass@localhost:port/db-name
   - Default credentials: datalevin:datalevin (change in production!)"
  (:require [integrant.core :as ig]
            [clojure.java.io :as io]
            [taoensso.timbre :as log])
  (:import [java.net Socket InetSocketAddress]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(require '[seon.schema :as schema])

(schema/register! ::port
                  [:int {:min 1 :max 65535 :description "Server listening port"}])

(schema/register! ::root
                  [:string {:description "Root directory for database files"}])

(schema/register! ::idle-timeout
                  [:int {:min 0 :description "Session idle timeout in milliseconds"}])

;; Note: opts uses non-namespaced keys to match Integrant config conventions
(schema/register! ::opts
                  [:map {:description "Additional server options (Integrant config)"}
                   [:idle-timeout {:optional true} ::idle-timeout]])

(schema/register! ::server
                  [:any {:description "Datalevin server instance"}])

(schema/register! ::timeout-ms
                  [:int {:min 0 :description "Connection timeout in milliseconds"}])

(schema/register! ::ok
                  [:boolean {:description "Health check passed"}])

(schema/register! ::latency-ms
                  [:int {:min 0 :description "Connection latency in milliseconds"}])

(schema/register! ::error
                  [:string {:description "Error message if check failed"}])

;; Health check request/response schemas
(schema/register! ::healthy?-request
                  [:map {:description "Request for health check"}
                   [::port ::port]
                   [::timeout-ms {:optional true} ::timeout-ms]])

(schema/register! ::healthy?-response
                  [:map {:description "Health check result"}
                   [::ok ::ok]
                   [::latency-ms ::latency-ms]
                   [::error {:optional true} ::error]])

;;; ---------------------------------------------------------------------------
;;; Server Lifecycle
;;; ---------------------------------------------------------------------------

(defn- ensure-root-dir!
  "Ensure the root directory exists and is writable."
  [root]
  (let [dir (io/file root)]
    (when-not (.exists dir)
      (log/info "Creating Datalevin root directory" {:path root})
      (.mkdirs dir))
    (when-not (.isDirectory dir)
      (throw (ex-info "Datalevin root path is not a directory" {:path root})))
    (when-not (.canWrite dir)
      (throw (ex-info "Datalevin root directory is not writable" {:path root})))
    dir))

(defn- create-server
  "Create a Datalevin server instance.

   Options:
     :port - Server port (default 8898)
     :root - Root directory for databases (default \"data/datalevin\")
     :opts - Additional options map:
       :idle-timeout - Session idle timeout in ms (default 300000)"
  [{:keys [port root opts]
    :or {port 8898
         root "data/datalevin"}}]
  (ensure-root-dir! root)
  (require 'datalevin.server)
  (let [create (resolve 'datalevin.server/create)
        server-opts (merge {:port port
                            :root root
                            :verbose false}
                          (when-let [timeout (:idle-timeout opts)]
                            {:idle-timeout timeout}))]
    (log/info "Creating Datalevin server" {:port port :root root})
    (create server-opts)))

(defn- start-server!
  "Start a Datalevin server."
  [server]
  (require 'datalevin.server)
  (let [start (resolve 'datalevin.server/start)]
    (start server)
    server))

(defn- stop-server!
  "Stop a Datalevin server."
  [server]
  (when server
    (require 'datalevin.server)
    (let [stop (resolve 'datalevin.server/stop)]
      (stop server))))

;;; ---------------------------------------------------------------------------
;;; Health Check
;;; ---------------------------------------------------------------------------

(defn healthy?
  "Check if the Datalevin server is healthy by attempting a TCP connection.

   Request keys:
     ::port - Server port to check
     ::timeout-ms - Connection timeout in milliseconds (default 1000)

   Response keys:
     ::ok - boolean indicating health status
     ::latency-ms - connection latency in milliseconds
     ::error - error message if unhealthy (only present when ::ok is false)"
  {:malli/schema [:=> [:cat ::healthy?-request] ::healthy?-response]}
  [{::keys [port timeout-ms] :or {timeout-ms 1000}}]
  (let [start (System/currentTimeMillis)]
    (try
      (with-open [socket (Socket.)]
        (.connect socket (InetSocketAddress. "127.0.0.1" (int port)) timeout-ms)
        {::ok true
         ::latency-ms (- (System/currentTimeMillis) start)})
      (catch Exception e
        {::ok false
         ::latency-ms (- (System/currentTimeMillis) start)
         ::error (.getMessage e)}))))

;;; ---------------------------------------------------------------------------
;;; Integrant Component
;;; ---------------------------------------------------------------------------

(defmethod ig/init-key :seon/datalevin-server
  [_ {:keys [port root opts]
      :or {port 8898
           root "data/datalevin"}}]
  (log/info "Starting Datalevin server..." {:port port :root root})
  (let [server (create-server {:port port :root root :opts opts})]
    (start-server! server)
    ;; Return component state with config for health checks
    {:server server
     :port port
     :root root}))

(defmethod ig/halt-key! :seon/datalevin-server
  [_ {:keys [server]}]
  (log/info "Stopping Datalevin server...")
  (stop-server! server)
  (log/info "Datalevin server stopped"))

;; Suspend/resume to survive (reset) like nREPL
(defmethod ig/suspend-key! :seon/datalevin-server [_ state] state)

(defmethod ig/resume-key :seon/datalevin-server
  [key opts old-opts old-state]
  (if (and (= (:port opts) (:port old-opts))
           (= (:root opts) (:root old-opts)))
    old-state
    (do (ig/halt-key! key old-state)
        (ig/init-key key opts))))

;;; ---------------------------------------------------------------------------
;;; REPL Helpers
;;; ---------------------------------------------------------------------------

(comment
  ;; Manual testing
  (require '[integrant.repl.state :as state])

  ;; Check if server is in system
  (:seon/datalevin-server state/system)

  ;; Health check
  (healthy? {::port 8898})
  (healthy? {::port 8898 ::timeout-ms 500})

  ;; Health check from component
  (let [{:keys [port]} (:seon/datalevin-server state/system)]
    (healthy? {::port port}))

  ;; Manual server lifecycle
  (def srv (create-server {:port 8898 :root "data/datalevin"}))
  (start-server! srv)
  (healthy? {::port 8898})
  (stop-server! srv)

  nil)
