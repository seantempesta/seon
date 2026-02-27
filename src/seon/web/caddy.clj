(ns seon.web.caddy
  "Caddy reverse proxy Integrant component.

  Manages Caddy as a child process for HTTP/2 + TLS on localhost:3030.
  Auto-detects if Caddy is already running and adopts it instead of restarting.
  Only stops Caddy on halt if we started it ourselves.

  Configuration:
  - :enabled? - whether to start Caddy (true in dev only)
  - :config-file - path to Caddyfile (default: \"Caddyfile\")"
  (:require [clojure.java.io :as io]
            [integrant.core :as ig]
            [taoensso.timbre :as log])
  (:import [java.lang ProcessBuilder ProcessBuilder$Redirect]))

(defn- port-open?
  "Check if a port is accepting connections."
  [port timeout-ms]
  (try
    (let [socket (java.net.Socket.)]
      (.connect socket (java.net.InetSocketAddress. "localhost" (int port)) (int timeout-ms))
      (.close socket)
      true)
    (catch Exception _ false)))

(defn- start-caddy-process!
  "Start the Caddy process. Returns the Process object or nil on failure."
  [{:keys [config-file]}]
  (.mkdirs (io/file "logs"))
  (let [command ["caddy" "run" "--config" config-file]
        log-file (io/file "logs/caddy.log")
        builder (ProcessBuilder. ^java.util.List command)]
    (.redirectOutput builder (ProcessBuilder$Redirect/appendTo log-file))
    (.redirectErrorStream builder true)
    (.directory builder (io/file "."))
    (log/info "Starting Caddy" {:command command :log-file (.getPath log-file)})
    (let [process (.start builder)]
      (Thread/sleep 1000)
      (if (.isAlive process)
        (do
          (log/info "Caddy started" {:pid (.pid process)})
          (when-not (port-open? 3030 2000)
            (log/warn "Caddy process alive but port 3030 not yet accepting connections"))
          process)
        (let [exit-code (.exitValue process)]
          (log/error "Caddy failed to start" {:exit-code exit-code
                                               :log-file (.getPath log-file)})
          nil)))))

(defn- stop-caddy-process!
  "Stop the Caddy process."
  [^Process process]
  (when (and process (.isAlive process))
    (log/info "Stopping Caddy" {:pid (.pid process)})
    (.destroy process)
    (let [exited? (.waitFor process 2 java.util.concurrent.TimeUnit/SECONDS)]
      (when-not exited?
        (log/warn "Caddy didn't stop gracefully, forcing")
        (.destroyForcibly process)
        (.waitFor process 1 java.util.concurrent.TimeUnit/SECONDS)))
    (log/info "Caddy stopped")))

;;; ---------------------------------------------------------------------------
;;; Integrant Component
;;; ---------------------------------------------------------------------------

(defmethod ig/init-key :seon.web/caddy
  [_ {:keys [enabled? config-file]
      :or {config-file "Caddyfile"}}]
  (if enabled?
    (if (port-open? 3030 500)
      ;; Existing Caddy detected and healthy — adopt it
      (do
        (log/info "Using existing Caddy" {:port 3030})
        {:process nil :adopted? true
         :url "https://localhost:3030"
         :upstream "http://localhost:8080"})
      ;; No Caddy running — start one
      (let [process (start-caddy-process! {:config-file config-file})]
        {:process process
         :adopted? false
         :config-file config-file
         :pid (when process (.pid process))
         :url "https://localhost:3030"
         :upstream "http://localhost:8080"}))
    (do
      (log/info "Caddy proxy disabled")
      {:process nil})))

(defmethod ig/halt-key! :seon.web/caddy
  [_ {:keys [process adopted?]}]
  (when-not adopted?
    (stop-caddy-process! process)))

;; Suspend/resume: keep Caddy alive during (reset) — expensive to restart, no state to refresh
(defmethod ig/suspend-key! :seon.web/caddy [_ state] state)

(defmethod ig/resume-key :seon.web/caddy
  [_ opts _old-opts old-state]
  (if (or (:adopted? old-state)
          (and (:process old-state) (.isAlive ^Process (:process old-state))))
    old-state
    (do (ig/halt-key! :seon.web/caddy old-state)
        (ig/init-key :seon.web/caddy opts))))
