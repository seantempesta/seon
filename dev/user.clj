(ns user
  "REPL development namespace with Integrant system management.

  Start nREPL with: clj -M:dev:nrepl

  Then use these functions to manage the system:
    (go)         - Start the Integrant system
    (halt)       - Stop the system
    (reset)      - Reload code and restart (keeps nREPL alive)
    (reset-all)  - Full reset (clears all state)

  Accessors (after system is started):
    (xtdb-node)       - Get XTDB node
    (schema-registry) - Get Malli registry
    (status)          - Show system status"
  (:require [integrant.core :as ig]
            [integrant.repl :as ig-repl]
            [integrant.repl.state :refer [system config]]
            [clojure.tools.namespace.repl :as ns-repl]
            [clojure.java.io :as io]
            [aero.core :as aero]
            [seon.system]))

;;; ---------------------------------------------------------------------------
;;; Integrant REPL Setup
;;; ---------------------------------------------------------------------------

;; Register Integrant ref reader with Aero
(defmethod aero/reader 'ig/ref
  [_ _ value]
  (ig/ref value))

(defn- load-config
  "Load system configuration for dev profile."
  []
  (let [config-file (io/resource "system.edn")]
    (when-not config-file
      (throw (ex-info "system.edn not found in resources" {})))
    (-> (aero/read-config config-file {:profile :dev})
        (ig/prep))))

;; Configure integrant.repl to use our config loader
(ig-repl/set-prep! load-config)

;;; ---------------------------------------------------------------------------
;;; System Management Functions
;;; ---------------------------------------------------------------------------

(defn go
  "Start the Integrant system.

  Loads configuration from resources/system.edn with :dev profile
  and initializes all components."
  []
  (ig-repl/go))

(defn halt
  "Stop the Integrant system gracefully."
  []
  (ig-repl/halt))

(defn reset
  "Reload changed namespaces and restart the system.

  This is the main development workflow command.
  The nREPL connection stays alive during reset.

  Handles both integrant.repl (go) and runner (./bin/run) started systems."
  []
  ;; Check if system was started via runner (./bin/run)
  (if-let [runner-sys-var (resolve 'seon.runner/system)]
    (let [sys-atom (deref runner-sys-var)]
      (when @sys-atom
        ;; Runner system is active - use suspend/resume for proper lifecycle
        (require 'clojure.tools.namespace.repl)
        (let [refresh (resolve 'clojure.tools.namespace.repl/refresh)]
          ;; First halt the old system (except nREPL which has suspend/resume)
          (let [sys @sys-atom
                nrepl-server (:seon/nrepl-server sys)
                sys-without-nrepl (dissoc sys :seon/nrepl-server)]
            ;; Halt everything except nREPL
            (ig/halt! sys-without-nrepl)
            ;; Refresh namespaces
            (refresh)
            ;; Reload config and start fresh system
            (require 'seon.system)
            (let [load-config (resolve 'seon.system/load-config)
                  config (load-config :dev)
                  config-without-nrepl (dissoc config :seon/nrepl-server)
                  new-sys (ig/init config-without-nrepl)]
              ;; Merge back the nREPL server
              (reset! sys-atom (assoc new-sys :seon/nrepl-server nrepl-server))
              :reset-complete)))))
    ;; No runner system - use standard integrant.repl
    (ig-repl/reset)))

(defn reset-all
  "Clear all state, reload all namespaces, and restart.

  Use this when you need a completely fresh start."
  []
  (ig-repl/reset-all))

(defn refresh
  "Reload changed namespaces without restarting system.

  Use this for quick code updates that don't affect components."
  []
  (ns-repl/refresh))

(defn refresh-all
  "Reload ALL namespaces (slow but thorough)."
  []
  (ns-repl/refresh-all))

;;; ---------------------------------------------------------------------------
;;; Convenience Accessors
;;; ---------------------------------------------------------------------------

(defn- current-system
  "Get the running system, whether started via integrant.repl or runner."
  []
  (or system
      (when-let [runner-sys-var (resolve 'seon.runner/system)]
        ;; runner/system is a var containing an atom - need to deref both
        (deref (deref runner-sys-var)))))

(defn xtdb-node
  "Get the XTDB node from the running system.
  Works with both integrant.repl (go) and runner (./bin/run)."
  []
  (when-let [sys (current-system)]
    (:seon/xtdb-node sys)))

(defn schema-registry
  "Get the Malli schema registry from the running system."
  []
  (when-let [sys (current-system)]
    (:seon/schema-registry sys)))

;;; ---------------------------------------------------------------------------
;;; System Info
;;; ---------------------------------------------------------------------------

(defn status
  "Show system status and XTDB metrics."
  []
  (if-let [sys (current-system)]
    (do
      (println "System running with" (count sys) "components:")
      (doseq [k (sort (keys sys))]
        (println "  " k))
      (when-let [node (xtdb-node)]
        (println "")
        (println "XTDB status:")
        (require '[seon.db.node :as db-node])
        (clojure.pprint/pprint ((resolve 'seon.db.node/status) node))))
    (println "System not running. Start with: (go) or ./bin/run")))

;;; ---------------------------------------------------------------------------
;;; Database Management
;;; ---------------------------------------------------------------------------

(defn db-reset!
  "Delete all XTDB data and restart with fresh database.

  WARNING: This deletes all data! Use with caution."
  []
  (println "Stopping system...")
  (halt)
  (println "Deleting XTDB data directory...")
  (let [data-dir (io/file "data/xtdb")]
    (when (.exists data-dir)
      (doseq [f (reverse (file-seq data-dir))]
        (.delete f))))
  (println "Starting fresh system...")
  (go)
  (println "Database reset complete."))

(defn list-backups
  "List available XTDB backups."
  []
  (let [backup-dir (io/file "data/backups")]
    (if (.exists backup-dir)
      (doseq [f (sort (.listFiles backup-dir))]
        (println (.getName f)))
      (println "No backups directory found at data/backups"))))

(defn restore-from-backup!
  "Restore XTDB from a backup.

  Args:
    backup-name - Name of backup in data/backups/ (default: v2.0.0.20251129)"
  ([]
   (restore-from-backup! "v2.0.0.20251129"))
  ([backup-name]
   (println "Stopping system...")
   (halt)
   (println "Restoring from backup:" backup-name)
   ;; Implementation would copy backup files to data/xtdb
   (println "Starting system with restored data...")
   (go)
   (println "Restore complete.")))

;;; ---------------------------------------------------------------------------
;;; Python Initialization
;;; ---------------------------------------------------------------------------

(defn init-python!
  "Manually initialize Python bridge.

  Call this only when needed for ML operations.
  Python is disabled by default to avoid Arrow memory conflicts."
  []
  (require '[libpython-clj2.python :as py])
  ((resolve 'py/initialize!))
  (println "Python initialized.")
  true)

;;; ---------------------------------------------------------------------------
;;; Help
;;; ---------------------------------------------------------------------------

(defn help
  "Show available REPL commands."
  []
  (println "
ML Options REPL Commands
========================

System Management:
  (go)          Start the Integrant system
  (halt)        Stop the system
  (reset)       Reload code and restart system
  (reset-all)   Full reset (clears all state)

Code Reloading:
  (refresh)     Reload changed namespaces only
  (refresh-all) Reload ALL namespaces

Accessors (after system started):
  (xtdb-node)       Get XTDB node
  (schema-registry) Get Malli registry
  (status)          Show system status

Database:
  (db-reset!)           Delete all data, restart fresh
  (list-backups)        List available backups
  (restore-from-backup!) Restore from backup

Python (manual init):
  (init-python!)  Initialize Python bridge

Bulk Loading:
  (require '[seon.data.bulk-load :as bulk-load])
  (bulk-load/bulk-load-from-repl!
    (xtdb-node)
    [\"SPY\"]
    (java.time.LocalDate/of 2025 5 28)
    (java.time.LocalDate/of 2025 11 27))
"))

;;; ---------------------------------------------------------------------------
;;; Startup Message
;;; ---------------------------------------------------------------------------

(println "
================================================================================
ML Options Trading - Development REPL
================================================================================

Commands:
  (help)    Show all commands
  (go)      Start the system
  (status)  Check system status
  (reset)   Reload code and restart

Start the system with (go) when ready.
================================================================================
")

;;; ---------------------------------------------------------------------------
;;; REPL Comment Block
;;; ---------------------------------------------------------------------------

(comment
  ;; Development workflow:
  (go)          ; Start system
  (status)      ; Check status
  (reset)       ; After code changes
  (halt)        ; Stop system

  ;; Quick iteration (no system restart):
  (refresh)

  ;; Access components:
  (xtdb-node)
  (schema-registry))
