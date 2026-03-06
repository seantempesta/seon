(ns seon.core
  "Entry point for the Seon system.

  Provides unified system lifecycle management that works correctly
  with both runner (./bin/run) and REPL (integrant.repl).

  KEY INSIGHT - THE REAL KIT PATTERN:
  - DON'T maintain a separate system atom
  - ALWAYS use integrant.repl.state for system state
  - Runner (-main) uses integrant.repl functions under the hood
  - This ensures (reset) works regardless of how system was started!

  The env.clj provides profile-specific hooks (:init, :start, :stop)
  that we call during lifecycle events.

  TWO-PHASE STARTUP:
  Phase 1 starts core services (nREPL, HTTP, schema registry) so the
  developer always has a REPL to debug with. Phase 2 resumes the full
  system, reusing Phase 1 components via ig/resume. If Phase 2 fails,
  Phase 1 remains running and the system is degraded but debuggable.

  Datalevin is the sole database."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [taoensso.timbre :as log]
   [integrant.core :as ig]
   [integrant.repl :as ig-repl]
   [integrant.repl.state :as state]
   [seon.config :as config]
   [seon.db :as db]
   [seon.env :refer [defaults]]
   [seon.logging :as logging]
   [seon.ns.lifecycle :as lifecycle]

   ;; Load all system component namespaces
   [seon.system]
   [seon.health :as health])
  (:gen-class)
  (:import [java.net Socket InetSocketAddress]
           [clojure.lang ExceptionInfo]))

;; log uncaught exceptions in threads
(Thread/setDefaultUncaughtExceptionHandler
 (reify Thread$UncaughtExceptionHandler
   (uncaughtException [_ thread ex]
     (log/error ex "Uncaught exception on" (.getName thread)))))

;;; ---------------------------------------------------------------------------
;;; Pre-flight Port Checks
;;; ---------------------------------------------------------------------------

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

(defn- check-port-free!
  "Throw if port is already bound. Provides actionable error with PID."
  [port label]
  (try
    (with-open [socket (Socket.)]
      (.connect socket (InetSocketAddress. "127.0.0.1" (int port)) 500))
    ;; Connection succeeded = something is listening
    (let [pid (find-pid-on-port port)]
      (throw (ex-info (str label " port " port " in use"
                          (when pid (str " (pid " pid ")"))
                          ". Fix: "
                          (if pid
                            (str "kill -9 " pid)
                            (str "lsof -ti :" port " | xargs kill -9")))
                      {:type :port-conflict :port port :pid pid :label label})))
    (catch java.net.ConnectException _)
    (catch java.net.SocketTimeoutException _)
    (catch ExceptionInfo e (throw e))))

(defn- fetch-health
  "Fetch health from a running Seon instance. Returns parsed map or nil."
  []
  (try
    (json/parse-string (slurp "http://localhost:8080/api/health") true)
    (catch Exception _ nil)))

(defn- print-running-status!
  "Print a summary of the running system and exit."
  [health]
  (println (str "Seon is already running (" (:status health) ")."))
  (println "  Dashboard:  http://localhost:8080")
  (when-let [phase (:startup-phase health)]
    (println (str "  Phase:      " phase)))
  (println)
  (doseq [[svc check] (:checks health)]
    (let [ok? (:ok check)
          mode (:mode check)
          port (:port check)]
      (println (format "  %-12s %s%s%s"
                       (name svc)
                       (if ok? "ok" "DOWN")
                       (if port (str "  :" port) "")
                       (if mode (str "  (" mode ")") "")))))
  (System/exit 0))

(defn- preflight-port-checks!
  "Check that in-process ports are free before starting.
   Datalevin (8898) is NOT checked here — the server component auto-detects
   and adopts an existing server if one is running.
   If Seon is already running and healthy, prints status summary and exits."
  []
  (when-let [health (fetch-health)]
    (print-running-status! health))
  (log/info "Pre-flight port checks...")
  (check-port-free! 7888 "nREPL")
  (check-port-free! 8080 "HTTP")
  (log/info "All ports available"))

;;; ---------------------------------------------------------------------------
;;; Two-Phase Integrant Init
;;; ---------------------------------------------------------------------------

(def ^:private phase-1-keys
  "Core services that start without any DB dependency.
  These give the developer a REPL and HTTP server immediately."
  [:seon.schema/registry
   :seon.dev/nrepl
   :seon.web.server/http-server
   :seon.web/tailwind
   :seon.ai.claude/sdk])

(defn- log-startup-summary
  "Log a summary of which components are running after both phases."
  [system phase-2-error]
  (let [status (if phase-2-error :degraded :healthy)]
    (when phase-2-error
      (log/error phase-2-error
                 "Phase 2 failed — system is degraded. REPL and HTTP still available."))
    (if phase-2-error
      (log/info "Startup complete (degraded)"
                {:status status
                 :components (count system)})
      ;; Full system ready — log the clean service summary
      (health/log-startup-summary!))))

;;; ---------------------------------------------------------------------------
;;; System Lifecycle
;;; ---------------------------------------------------------------------------

(defn stop-app
  "Stop the running system gracefully.
  Calls profile-specific :stop hook, then halts via integrant.repl."
  []
  ((or (:stop defaults) (fn [])))
  (ig-repl/halt))

(defn start-app
  "Start the system using two-phase Integrant init.

  Phase 1: Core services (nREPL, HTTP, schema registry) — fast, no DB deps.
  Phase 2: Full system via ig/resume, reusing Phase 1 components.

  If Phase 2 fails, Phase 1 remains running so the developer has a REPL.

  Params map supports:
    :init - Override env init hook
    :start - Override env start hook
    :opts - Override env opts (used to build config)"
  [& [params]]
  ;; Call init hook
  ((or (:init params) (:init defaults) (fn [])))

  (let [opts (merge (:opts defaults) (:opts params))
        cfg-fn (fn []
                 (let [cfg (config/system-config opts)]
                   (ig/load-namespaces cfg)
                   (ig/expand cfg)))
        full-config (cfg-fn)
        start-ms (System/currentTimeMillis)]

    ;; Phase 1: Core services only
    (health/set-startup-phase! :phase-1)
    (log/info "Phase 1: Starting core services...")
    (let [phase-1-system (ig/init full-config phase-1-keys)]
      (log/info "Phase 1 complete — nREPL and HTTP available"
                {:elapsed-ms (- (System/currentTimeMillis) start-ms)
                 :components (count phase-1-system)})

      ;; Phase 2: Resume with full config — reuses Phase 1, starts the rest
      (health/set-startup-phase! :phase-2)
      (log/info "Phase 2: Starting database and dependent services...")
      (let [[final-system phase-2-error]
            (try
              [(ig/resume full-config phase-1-system) nil]
              (catch Throwable e
                ;; Phase 2 failed — keep Phase 1 running
                [phase-1-system e]))]

        ;; Store in integrant.repl.state so (reset) works
        (alter-var-root #'state/system (constantly final-system))

        ;; Set prep! so future (reset) calls do a full init
        (ig-repl/set-prep! cfg-fn)

        ;; Determine startup phase: if Phase 2 failed, degraded.
        ;; If Phase 2 succeeded, run readiness gate to verify operational health.
        (if phase-2-error
          (health/set-startup-phase! :degraded)
          (let [{:keys [all-pass? failed]} (health/readiness-gate)]
            (if all-pass?
              (do
                (health/set-startup-phase! :ready)
                (log/info "Readiness gate: all operational checks passed"))
              (do
                (health/set-startup-phase! :degraded)
                (log/warn "Readiness gate: some checks failed — system is degraded"
                          {:failed-checks (keys failed)
                           :details failed})))))

        (log-startup-summary final-system phase-2-error)

        ;; Start post-startup observation (re-checks at 30s and 60s)
        (when-not phase-2-error
          (health/start-post-start-observation!))

        ;; Call start hook
        ((or (:start params) (:start defaults) (fn [])))

        final-system))))

(defn -main
  "Main entry point for standalone system.
  Uses integrant.repl under the hood for unified state management.

  Args:
    profile - Optional profile name (default: dev)
              'prod' for production, 'test' for testing"
  [& args]
  (let [profile (keyword (or (first args) "dev"))
        opts {:profile profile}]
    (try
      ;; Configure logging before anything else
      (logging/configure! {})

      ;; Pre-flight: ensure critical ports are free
      (preflight-port-checks!)

      ;; Start the system via two-phase init
      (start-app {:opts opts})

      ;; Show connection info
      (when (:seon.dev/nrepl state/system)
        (log/info "Connect your editor to nREPL port 7888"))

      (log/info "System running. Press Ctrl+C to stop.")
      (log/info "Use (reset) to reload code changes via nREPL.")

      ;; Install shutdown hook
      (.addShutdownHook
       (Runtime/getRuntime)
       (Thread. ^Runnable (fn []
                            (log/info "Shutdown signal received")
                            (log/info "Pausing infrastructure writer...")
                            (try (db/pause-writer!) (catch Exception _))
                            (log/info "Backing up ctx instances...")
                            (try
                              (lifecycle/backup-all-instances! {::lifecycle/db-name :seon.runtime})
                              (catch Throwable t
                                (log/warn "Failed to backup ctx instances"
                                          {:error (.getMessage t)})))
                            (stop-app)
                            (shutdown-agents))))

      ;; Block until shutdown
      @(promise)

      (catch Exception e
        (if (= :port-conflict (:type (ex-data e)))
          (do (println (str "\nERROR: " (ex-message e)))
              (System/exit 1))
          (do (log/error e "Failed to start system")
              (System/exit 1)))))))
