(ns seon.web.caddy
  "Caddy reverse proxy Integrant component.

  Spawns `caddy run` as a child process for HTTP/2 + TLS on localhost:3030.
  Kills stale Caddy processes on startup to prevent port conflicts.

  Configuration:
  - :enabled? - whether to start Caddy (true in dev only)
  - :config-file - path to Caddyfile (default: \"Caddyfile\")"
  (:require [clojure.java.io :as io]
            [integrant.core :as ig]
            [taoensso.timbre :as log])
  (:import [java.lang ProcessBuilder ProcessBuilder$Redirect]))

(defn- ensure-log-dir!
  "Ensure the logs directory exists."
  []
  (let [log-dir (io/file "logs")]
    (when-not (.exists log-dir)
      (.mkdirs log-dir))))

(defn- kill-stale-caddy!
  "Kill any stale Caddy processes from previous runs."
  []
  (try
    (let [builder (ProcessBuilder. ^java.util.List ["pkill" "-f" "caddy"])]
      (.redirectOutput builder ProcessBuilder$Redirect/DISCARD)
      (.redirectErrorStream builder true)
      (let [process (.start builder)]
        (.waitFor process 3 java.util.concurrent.TimeUnit/SECONDS)
        (when (zero? (.exitValue process))
          (log/info "Killed stale Caddy processes")
          ;; Give OS time to release the port
          (Thread/sleep 500))))
    (catch Exception e
      (log/debug "No stale Caddy processes to kill" {:msg (.getMessage e)}))))

(defn- start-caddy-process!
  "Start the Caddy process. Returns the Process object or nil on failure."
  [{:keys [config-file]}]
  (ensure-log-dir!)
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

(defmethod ig/init-key :seon/caddy-proxy
  [_ {:keys [enabled? config-file]
      :or {config-file "Caddyfile"}}]
  (if enabled?
    (do
      (kill-stale-caddy!)
      (let [process (start-caddy-process! {:config-file config-file})]
        {:process process :config-file config-file}))
    (do
      (log/info "Caddy proxy disabled")
      {:process nil})))

(defmethod ig/halt-key! :seon/caddy-proxy
  [_ {:keys [process]}]
  (stop-caddy-process! process))

;; Suspend/resume: keep Caddy alive during (reset) — expensive to restart, no state to refresh
(defmethod ig/suspend-key! :seon/caddy-proxy [_ state] state)

(defmethod ig/resume-key :seon/caddy-proxy
  [_ opts _old-opts old-state]
  (if (and (:process old-state) (.isAlive ^Process (:process old-state)))
    old-state
    (do (ig/halt-key! :seon/caddy-proxy old-state)
        (ig/init-key :seon/caddy-proxy opts))))
