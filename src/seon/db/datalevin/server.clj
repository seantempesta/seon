(ns seon.db.datalevin.server
  "Datalevin server component for Integrant.

   Runs Datalevin as a **separate JVM process** — like Caddy. Survives Seon
   restarts. Integrant suspend/resume keeps the process alive across (reset).

   ## Log Files

   | File | Contents |
   |------|----------|
   | logs/datalevin.log | Datalevin JVM stdout/stderr (server messages, client connects) |
   | logs/app.log | Seon-side lifecycle events (start, stop, adopt, health checks) |

   ## Log Rotation

   Datalevin process output is piped through a daemon thread that writes each
   line through a rotating writer (50MB max, 3 backlog files). This prevents
   unbounded log growth during long-running sessions.

   ## Configuration

   ```clojure
   {:seon.db.datalevin/server
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

   ## Troubleshooting

   1. Check Datalevin's own output: `tail -f logs/datalevin.log`
   2. Check Seon lifecycle events: `(user/logs :grep \"datalevin\")`
   3. Check if process is running: `(user/status)` or `lsof -ti :8898`
   4. PID file: `data/datalevin/server.pid`
   5. Force restart: `(user/restart-db!)`"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [integrant.core :as ig]
            [seon.logging :as seon-log]
            [taoensso.timbre :as log])
  (:import [java.io BufferedReader InputStreamReader]
           [java.net Socket InetSocketAddress]))

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

(schema/register! ::opts
                  [:map {:description "Additional server options (Integrant config)"}
                   [:idle-timeout {:optional true} ::idle-timeout]])

(schema/register! ::adopted?
                  [:boolean {:description "When true, server was already running and we adopted it (don't stop on halt)"}])

(schema/register! ::timeout-ms
                  [:int {:min 0 :description "Connection timeout in milliseconds"}])

(schema/register! ::ok
                  [:boolean {:description "Health check passed"}])

(schema/register! ::latency-ms
                  [:int {:min 0 :description "Connection latency in milliseconds"}])

(schema/register! ::error
                  [:string {:description "Error message if check failed"}])

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
;;; Port & PID Helpers
;;; ---------------------------------------------------------------------------

(defn- port-open?
  "Check if a port is accepting connections."
  [port timeout-ms]
  (try
    (with-open [socket (Socket.)]
      (.connect socket (InetSocketAddress. "127.0.0.1" (int port)) (int timeout-ms)))
    true
    (catch Exception _ false)))

(defn- find-pid-on-port
  "Find the PID holding a port via lsof. Returns PID string or nil."
  [port]
  (try
    (let [proc (.exec (Runtime/getRuntime)
                      (into-array String ["lsof" "-ti" (str ":" port)]))]
      (.waitFor proc 2 java.util.concurrent.TimeUnit/SECONDS)
      (when (zero? (.exitValue proc))
        (let [output (slurp (.getInputStream proc))]
          (first (str/split-lines (str/trim output))))))
    (catch Exception _ nil)))

(defn- pid-file-path
  "Path to the PID file."
  [root]
  (io/file root "server.pid"))

(defn- write-pid!
  "Write PID to file for tracking."
  [root pid]
  (.mkdirs (io/file root))
  (spit (pid-file-path root) (str pid)))

(defn- read-pid
  "Read PID from file. Returns long or nil."
  [root]
  (let [f (pid-file-path root)]
    (when (.exists f)
      (try
        (Long/parseLong (str/trim (slurp f)))
        (catch Exception _ nil)))))

(defn- clean-pid!
  "Remove the PID file."
  [root]
  (let [f (pid-file-path root)]
    (when (.exists f)
      (.delete f))))

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
;;; Process Output Piping
;;; ---------------------------------------------------------------------------

(defn- start-log-pipe!
  "Start a daemon thread that reads lines from a process's stdout/stderr
   and writes them through a rotating log writer.

   Returns the Thread (for joining on shutdown)."
  [^Process process log-path]
  (let [writer (seon-log/create-rotating-writer
                 {:path log-path
                  :max-size (* 50 1024 1024)    ; 50MB
                  :max-backlog 3})
        thread (Thread.
                 (fn []
                   (try
                     (with-open [reader (BufferedReader.
                                          (InputStreamReader.
                                            (.getInputStream process)))]
                       (loop []
                         (when-let [line (.readLine reader)]
                           (seon-log/write-line! writer line)
                           (recur))))
                     (catch java.io.IOException _
                       ;; Process ended, stream closed — normal shutdown
                       nil)
                     (catch Exception e
                       (log/warn "Datalevin log pipe error" {:error (.getMessage e)}))))
                 "datalevin-log-pipe")]
    (.setDaemon thread true)
    (.start thread)
    thread))

;;; ---------------------------------------------------------------------------
;;; Process Management
;;; ---------------------------------------------------------------------------

(defn- start-datalevin-process!
  "Start the Datalevin server as an external JVM process.
   Returns state map with :process, :port, :root, :adopted? false, :log-pipe.

   Polls port every 500ms for up to 15s waiting for the JVM to start.
   Process output is piped through a rotating writer to logs/datalevin.log.

   Throws on:
   - Process dies during startup (check logs/datalevin.log)
   - Startup timeout (15s)"
  [{:keys [port root]}]
  (.mkdirs (io/file "logs"))
  (.mkdirs (io/file root))
  (let [command ["bin/run-datalevin"]
        log-path "logs/datalevin.log"
        builder (doto (ProcessBuilder. ^java.util.List command)
                  (.redirectErrorStream true)
                  (.directory (io/file ".")))
        env (.environment builder)]
    (.put env "DATALEVIN_PORT" (str port))
    (.put env "DATALEVIN_ROOT" (str root))
    (log/info "Starting Datalevin process"
              {:command command :port port :root root
               :log-file log-path})
    (let [process (.start builder)
          pid (.pid process)
          log-pipe (start-log-pipe! process log-path)]
      (write-pid! root pid)
      (log/info "Datalevin process spawned, waiting for port" {:pid pid :port port})
      ;; Poll for port to become available (JVM startup is slow)
      (loop [attempts 0]
        (cond
          (not (.isAlive process))
          (let [exit-code (.exitValue process)]
            (log/error "Datalevin process died during startup"
                       {:exit-code exit-code :pid pid
                        :log-file log-path})
            (clean-pid! root)
            (throw (ex-info (str "Datalevin process died (exit " exit-code
                                 "). Check logs/datalevin.log")
                            {:exit-code exit-code
                             :log-file log-path})))

          (port-open? port 500)
          (do (log/info "Datalevin server ready"
                        {:pid pid :port port
                         :startup-ms (* attempts 500)})
              {:process process :port port :root root
               :adopted? false :log-pipe log-pipe})

          (>= attempts 30)
          (do (log/error "Datalevin server startup timeout"
                         {:pid pid :port port :waited-ms (* attempts 500)
                          :log-file log-path})
              (.destroy process)
              (.waitFor process 3 java.util.concurrent.TimeUnit/SECONDS)
              (clean-pid! root)
              (throw (ex-info (str "Datalevin server did not start within 15s. "
                                   "Check logs/datalevin.log")
                              {:pid pid :port port})))

          :else
          (do (Thread/sleep 500)
              (recur (inc attempts))))))))

(defn- stop-datalevin-process!
  "Gracefully stop the Datalevin process.
   SIGTERM first — lets Datalevin call mdb_env_sync + mdb_env_close on all envs.
   Force-kill after 5s if SIGTERM doesn't work."
  [^Process process root]
  (when (and process (.isAlive process))
    (let [pid (.pid process)]
      (log/info "Stopping Datalevin server (SIGTERM)" {:pid pid})
      (.destroy process)
      (let [exited? (.waitFor process 5 java.util.concurrent.TimeUnit/SECONDS)]
        (if exited?
          (log/info "Datalevin server stopped cleanly" {:pid pid :exit-code (.exitValue process)})
          (do
            (log/warn "Datalevin didn't stop within 5s, sending SIGKILL" {:pid pid})
            (.destroyForcibly process)
            (.waitFor process 2 java.util.concurrent.TimeUnit/SECONDS)
            (log/info "Datalevin server force-killed" {:pid pid}))))
      (clean-pid! root))))

;;; ---------------------------------------------------------------------------
;;; Integrant Component
;;; ---------------------------------------------------------------------------

(defmethod ig/init-key :seon.db.datalevin/server
  [_ {:keys [port root]
      :or {port 8898
           root "data/datalevin"}}]
  (if (port-open? port 500)
    ;; Existing server detected — adopt it
    (let [existing-pid (or (find-pid-on-port port) "unknown")]
      (log/info "Adopting existing Datalevin server"
                {:port port :pid existing-pid})
      {:port port :root root :adopted? true :pid existing-pid})
    ;; No server running — start external process
    (start-datalevin-process! {:port port :root root})))

(defmethod ig/halt-key! :seon.db.datalevin/server
  [_ {:keys [process adopted? root port] :as state}]
  (if adopted?
    (log/info "Adopted Datalevin server — leaving it running"
              {:port port :pid (:pid state)})
    (stop-datalevin-process! process root)))

;; Suspend/resume: keep Datalevin alive during (reset)
(defmethod ig/suspend-key! :seon.db.datalevin/server [_ state]
  (log/debug "Suspending Datalevin component (process stays alive)"
             {:port (:port state) :adopted? (:adopted? state)})
  state)

(defmethod ig/resume-key :seon.db.datalevin/server
  [_ opts _old-opts old-state]
  (cond
    ;; Adopted server — check it's still alive
    (:adopted? old-state)
    (if (port-open? (:port old-state) 500)
      (do (log/debug "Adopted Datalevin still healthy on resume" {:port (:port old-state)})
          old-state)
      (do (log/warn "Adopted Datalevin server died, starting new one"
                     {:port (:port old-state)})
          (ig/init-key :seon.db.datalevin/server opts)))

    ;; We own the process — check it's still alive
    (and (:process old-state) (.isAlive ^Process (:process old-state)))
    (do (log/debug "Datalevin process still alive on resume"
                   {:pid (.pid ^Process (:process old-state))})
        old-state)

    ;; Process died somehow — restart
    :else
    (do (log/warn "Datalevin process died, restarting"
                  {:old-state (dissoc old-state :process)})
        (ig/halt-key! :seon.db.datalevin/server old-state)
        (ig/init-key :seon.db.datalevin/server opts))))

;;; ---------------------------------------------------------------------------
;;; REPL Helpers
;;; ---------------------------------------------------------------------------

(comment
  (require '[integrant.repl.state :as state])

  ;; Check if server is in system
  (:seon.db.datalevin/server state/system)

  ;; Health check
  (healthy? {::port 8898})
  (healthy? {::port 8898 ::timeout-ms 500})

  ;; Health check from component
  (let [{:keys [port]} (:seon.db.datalevin/server state/system)]
    (healthy? {::port port}))

  ;; Check logs
  ;; tail -f logs/datalevin.log     — Datalevin's own output
  ;; (user/logs :grep "datalevin")  — Seon lifecycle events

  nil)
