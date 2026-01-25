(ns seon.system
  "Integrant system configuration and component definitions.

  Defines init-key and halt-key! methods for all system components:
  - :seon/xtdb-node - XTDB v2 database node (single node with attached databases)
  - :seon/namespace-dbs - Manages attached databases for namespaces
  - :seon/schema-registry - Malli schema registry
  - :seon.web.server/http-server - HTTP server for web UI
  - :seon/nrepl-server - nREPL for REPL-driven development
  - :seon.orchestrator/namespace-nrepls - Per-namespace nREPL servers for agent isolation

  ## Multi-Database Architecture

  Instead of running 3 separate XTDB nodes (~4.5GB memory), we now run a single
  node with attached databases for each namespace:

  - Primary 'xtdb' database: orchestrator data (data/xtdb)
  - 'seon_primer' database: primer sessions (data/namespaces/seon.primer)
  - 'seon_dev' database: dev hook events (data/namespaces/seon.dev)

  This reduces memory usage significantly while maintaining isolation."
  (:require [integrant.core :as ig]
            [clojure.java.io :as io]
            [taoensso.timbre :as log]
            [seon.db.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; XTDB Node Component
;;; ---------------------------------------------------------------------------

(defmethod ig/init-key :seon/xtdb-node
  [_ {:keys [storage memory-cache disk-cache compactor]}]
  (log/info "Starting XTDB node..." {:storage storage :compactor compactor})
  (require '[xtdb.node :as xtn])
  (require '[xtdb.api :as xt])
  ;; Ensure XTQL protocol namespaces are loaded to prevent classloader mismatches
  ;; after (reset). This guarantees the PlanQuery protocol extensions are in place.
  (require '[xtdb.xtql.plan])
  (let [start-node (resolve 'xtn/start-node)
        node (if (= storage :in-memory)
               (start-node)
               ;; XTDB v2 API: [:local {:path ...}] format
               (let [base-path (if (map? storage) (:path storage) (str storage))
                     config (cond-> {:log [:local {:path (io/file base-path "log")}]
                                     :storage [:local {:path (io/file base-path "objects")}]}
                              memory-cache (assoc :memory-cache memory-cache)
                              disk-cache (assoc :disk-cache disk-cache)
                              compactor (assoc :compactor compactor))]
                 (start-node config)))]
    (log/info "XTDB node started" {:compactor compactor})
    node))

(defmethod ig/halt-key! :seon/xtdb-node
  [_ node]
  (log/info "Stopping XTDB node...")
  (when node
    (.close node))
  (log/info "XTDB node stopped"))

;;; ---------------------------------------------------------------------------
;;; Namespace Databases Component
;;; ---------------------------------------------------------------------------
;;; Attaches secondary databases for namespace isolation after the primary
;;; node starts. This replaces the previous separate node components.

(defmethod ig/init-key :seon/namespace-dbs
  [_ {:keys [node namespaces]}]
  (log/info "Attaching namespace databases..." {:namespaces namespaces})
  (require 'seon.db.multi)
  (let [attach-all! (resolve 'seon.db.multi/attach-all-namespace-dbs!)
        create-conn (resolve 'seon.db.multi/create-namespace-connection)
        results (attach-all! node namespaces)
        ;; Create persistent connections for namespaces that need them
        connections (atom {})]
    (log/info "Namespace databases attached" {:results results})

    ;; Initialize primer ctx system with a connection to its database
    (when (some #(= 'seon.primer %) namespaces)
      (let [primer-conn (create-conn node 'seon.primer)]
        (swap! connections assoc 'seon.primer primer-conn)
        (require 'seon.primer.ctx)
        ((resolve 'seon.primer.ctx/init!) primer-conn)
        ((resolve 'seon.primer.ctx/start-auto-sync!) 5000)
        (log/info "Primer ctx system initialized with namespace database")))

    ;; Return state for halt
    {:node node
     :namespaces namespaces
     :connections connections}))

(defmethod ig/halt-key! :seon/namespace-dbs
  [_ {:keys [connections]}]
  (log/info "Cleaning up namespace database connections...")
  ;; Stop primer auto-sync
  (try
    (require 'seon.primer.ctx)
    ((resolve 'seon.primer.ctx/stop-auto-sync!))
    (catch Exception e
      (log/debug "Could not stop primer auto-sync" {:error (.getMessage e)})))
  ;; Close all connections
  (doseq [[ns-sym conn] @connections]
    (try
      (.close conn)
      (log/debug "Closed connection for namespace" {:namespace ns-sym})
      (catch Exception e
        (log/warn "Error closing connection" {:namespace ns-sym :error (.getMessage e)}))))
  (log/info "Namespace database connections cleaned up"))

;;; ---------------------------------------------------------------------------
;;; Legacy Node Components (REMOVED)
;;; ---------------------------------------------------------------------------
;;; The separate :seon.primer/xtdb-node and :seon.dev/xtdb-node components
;;; have been replaced by :seon/namespace-dbs which attaches databases to
;;; the single :seon/xtdb-node. This reduces memory usage from ~4.5GB to ~1.5GB.

;;; ---------------------------------------------------------------------------
;;; Python Bridge Component - DISABLED
;;; ---------------------------------------------------------------------------
;;; Python code moved to src/ml_options/_python_disabled
;;; To re-enable, restore the python directory and uncomment this section

;; (defmethod ig/init-key :seon/python-bridge
;;   [_ {:keys [conda-env auto-initialize?]}]
;;   (log/info "Initializing Python bridge..." {:conda-env conda-env})
;;   (when auto-initialize?
;;     (require '[libpython-clj2.python :as py])
;;     (let [initialize! (resolve 'py/initialize!)]
;;       ;; libpython-clj will read python.edn for configuration
;;       (initialize!)))
;;   (log/info "Python bridge initialized")
;;   {:conda-env conda-env
;;    :initialized? auto-initialize?})

;; (defmethod ig/halt-key! :seon/python-bridge
;;   [_ bridge]
;;   (log/info "Python bridge shutdown")
;;   ;; libpython-clj manages its own cleanup
;;   nil)

;;; ---------------------------------------------------------------------------
;;; Schema Registry Component
;;; ---------------------------------------------------------------------------

(defmethod ig/init-key :seon/schema-registry
  [_ _]
  (log/info "Initializing Malli schema registry...")
  (let [registry-value @schema/registry]
    (log/info "Schema registry initialized" {:schema-count (count registry-value)})
    registry-value))

(defmethod ig/halt-key! :seon/schema-registry
  [_ _]
  (log/info "Schema registry shutdown")
  nil)

;;; ---------------------------------------------------------------------------
;;; nREPL Server Component
;;; ---------------------------------------------------------------------------

(defmethod ig/init-key :seon/nrepl-server
  [_ {:keys [enabled? port bind]}]
  (if enabled?
    (do
      (require 'nrepl.server)
      (require 'cider.nrepl)
      (log/info "Starting nREPL server" {:port port :bind bind})
      (let [start-server (resolve 'nrepl.server/start-server)
            handler @(resolve 'cider.nrepl/cider-nrepl-handler)
            server (start-server :port port :bind bind :handler handler)]
        ;; Write .nrepl-port file for tooling discovery
        (spit ".nrepl-port" (str port))
        (log/info "nREPL server started" {:port port})
        server))
    (do
      (log/info "nREPL server disabled for this profile")
      nil)))

(defmethod ig/halt-key! :seon/nrepl-server
  [_ server]
  (when server
    (log/info "Stopping nREPL server...")
    (require 'nrepl.server)
    ((resolve 'nrepl.server/stop-server) server)
    ;; Clean up .nrepl-port file
    (when (.exists (java.io.File. ".nrepl-port"))
      (.delete (java.io.File. ".nrepl-port")))
    (log/info "nREPL server stopped")))

;; Keep nREPL alive during (reset) - critical for REPL-driven development
(defmethod ig/suspend-key! :seon/nrepl-server [_ server] server)

(defmethod ig/resume-key :seon/nrepl-server
  [key opts old-opts old-server]
  (if (= opts old-opts)
    old-server
    (do (ig/halt-key! key old-server)
        (ig/init-key key opts))))

;;; ---------------------------------------------------------------------------
;;; Namespace nREPL Servers Component
;;; ---------------------------------------------------------------------------
;;; Manages per-namespace nREPL servers for agent isolation.
;;; Each namespace gets its own nREPL on a unique port with injected *ctx*.

(defmethod ig/init-key :seon.orchestrator/namespace-nrepls
  [_ {:keys [namespaces node]}]
  (log/info "Starting namespace nREPL servers..." {:namespaces namespaces})
  (require 'seon.orchestrator.nrepl)
  (require 'seon.db.multi)
  (let [start! (resolve 'seon.orchestrator.nrepl/start-namespace-nrepl!)
        create-conn (resolve 'seon.db.multi/create-namespace-connection)
        ;; Generate a deterministic session-id from namespace (for Integrant-managed nREPLs)
        ns->session-id (fn [ns-sym] (str "ig-" (hash ns-sym)))
        ;; Start an nREPL for each namespace
        results (into {}
                      (for [ns-sym namespaces]
                        (let [session-id (ns->session-id ns-sym)
                              ;; Create a db connection for this namespace if node is provided
                              db (when node
                                   (try
                                     (create-conn node ns-sym)
                                     (catch Exception e
                                       (log/warn "Could not create db connection"
                                                 {:namespace ns-sym :error (.getMessage e)})
                                       nil)))
                              result (start! {:session-id session-id
                                              :namespace ns-sym
                                              :db db})]
                          [ns-sym (assoc result :session-id session-id)])))]
    (log/info "Namespace nREPL servers started"
              {:servers (into {} (for [[ns {:keys [port status]}] results]
                                   [ns {:port port :status status}]))})
    {:namespaces namespaces
     :servers results}))

(defmethod ig/halt-key! :seon.orchestrator/namespace-nrepls
  [_ {:keys [servers]}]
  (log/info "Stopping namespace nREPL servers...")
  (require 'seon.orchestrator.nrepl)
  (let [stop! (resolve 'seon.orchestrator.nrepl/stop-namespace-nrepl!)]
    (doseq [[ns-sym {:keys [ctx session-id]}] servers]
      (try
        ;; Close any db connection we created
        (when-let [db (:seon.agent/db @ctx)]
          (when (instance? java.io.Closeable db)
            (.close ^java.io.Closeable db)))
        ;; Stop the nREPL server (keyed by session-id, not namespace)
        (stop! session-id)
        (catch Exception e
          (log/warn "Error stopping namespace nREPL"
                    {:namespace ns-sym :session-id session-id :error (.getMessage e)})))))
  (log/info "Namespace nREPL servers stopped"))

;; Keep namespace nREPLs alive during (reset) like the main nREPL
(defmethod ig/suspend-key! :seon.orchestrator/namespace-nrepls [_ state] state)

(defmethod ig/resume-key :seon.orchestrator/namespace-nrepls
  [key opts old-opts old-state]
  (if (= (:namespaces opts) (:namespaces old-opts))
    old-state
    (do (ig/halt-key! key old-state)
        (ig/init-key key opts))))

;;; ---------------------------------------------------------------------------
;;; Claude Code SDK Configuration
;;; ---------------------------------------------------------------------------
;;; Configuration for the Claude Code CLI. Currently just holds the CLI path.
;;; The actual CLI interaction is in seon.ai.claude.sdk.

(defmethod ig/init-key :seon/claude-code
  [_ config]
  (log/info "Claude Code SDK configured" {:cli-path (:cli-path config)})
  config)
