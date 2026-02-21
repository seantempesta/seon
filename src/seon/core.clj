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

  Datalevin is the sole database."
  (:require
   [taoensso.timbre :as log]
   [integrant.core :as ig]
   [integrant.repl :as ig-repl]
   [integrant.repl.state :as state]
   [seon.config :as config]
   [seon.env :refer [defaults]]

   ;; Load all system component namespaces
   [seon.system])
  (:gen-class))

;; log uncaught exceptions in threads
(Thread/setDefaultUncaughtExceptionHandler
 (reify Thread$UncaughtExceptionHandler
   (uncaughtException [_ thread ex]
     (log/error ex "Uncaught exception on" (.getName thread)))))

(defn stop-app
  "Stop the running system gracefully.
  Calls profile-specific :stop hook, then halts via integrant.repl."
  []
  ((or (:stop defaults) (fn [])))
  (ig-repl/halt))

(defn start-app
  "Start the system using integrant.repl machinery.
  This ensures the system state is managed consistently.

  Params map supports:
    :init - Override env init hook
    :start - Override env start hook
    :opts - Override env opts (used to build config)"
  [& [params]]
  ;; Call init hook
  ((or (:init params) (:init defaults) (fn [])))

  ;; Set up integrant.repl with our config
  ;; CRITICAL: Use ig/expand (modern) not ig/prep (deprecated)
  ;; ig/prep was deprecated in Integrant 0.9.0, causing silent reset failures
  ;; Still need load-namespaces to ensure component ns are loaded
  (let [opts (merge (:opts defaults) (:opts params))]
    (ig-repl/set-prep! (fn []
                         (let [cfg (config/system-config opts)]
                           (ig/load-namespaces cfg)
                           (ig/expand cfg)))))

  ;; Start via integrant.repl - this populates state/system
  (ig-repl/go)

  ;; Call start hook
  ((or (:start params) (:start defaults) (fn [])))

  ;; Return the system (now in state/system)
  state/system)

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
      ;; Start the system via integrant.repl
      (start-app {:opts opts})

      ;; Show connection info
      (when-let [nrepl (:seon/nrepl-server state/system)]
        (log/info "Connect your editor to nREPL port 7888"))

      (log/info "System running. Press Ctrl+C to stop.")
      (log/info "Use (reset) to reload code changes via nREPL.")

      ;; Install shutdown hook
      (.addShutdownHook
       (Runtime/getRuntime)
       (Thread. ^Runnable (fn []
                            (log/info "Shutdown signal received")
                            (stop-app)
                            (shutdown-agents))))

      ;; Block until shutdown
      @(promise)

      (catch Exception e
        (log/error e "Failed to start system")
        (System/exit 1)))))
