(ns seon.system
  "Integrant system configuration and component definitions.

  Defines init-key and halt-key! methods for all system components:
  - :seon/xtdb-node - XTDB v2 database node (single node with attached databases)
  - :seon/namespace-dbs - Manages attached databases for namespaces
  - :seon/datalevin-server - Datalevin server for agent namespace isolation
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

  This reduces memory usage significantly while maintaining isolation.

  ## Datalevin Migration (Phase 0)

  A Datalevin server component (:seon/datalevin-server) is being added for
  eventual migration from XTDB. See docs/prds/datalevin-migration/prd.md."
  (:require [integrant.core :as ig]
            [clojure.java.io :as io]
            [taoensso.timbre :as log]
            [seon.db.schema :as schema]
            ;; Load component namespaces for their ig/init-key methods
            [seon.web.tailwind]
            [seon.db.datalevin.server]
            [seon.db.datalevin.conn]
            [seon.flow.pool]))

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
        results (attach-all! node namespaces)]
    (log/info "Namespace databases attached" {:results results})
    {:node node
     :namespaces namespaces}))

(defmethod ig/halt-key! :seon/namespace-dbs
  [_ _]
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
;;; Primer Ctx Component
;;; ---------------------------------------------------------------------------
;;; Initializes the primer ctx system with the Datalevin connection manager.

(defmethod ig/init-key :seon/primer-ctx
  [_ {:keys [connection-manager]}]
  (require 'seon.primer.ctx)
  ((resolve 'seon.primer.ctx/init!) connection-manager)
  {:connection-manager connection-manager})

;;; ---------------------------------------------------------------------------
;;; Orchestrator Sessions Component
;;; ---------------------------------------------------------------------------
;;; Initializes the orchestrator session system with the Datalevin connection manager.

(defmethod ig/init-key :seon/orchestrator-sessions
  [_ {:keys [connection-manager]}]
  (require 'seon.orchestrator.session)
  ((resolve 'seon.orchestrator.session/init!) connection-manager)
  {:connection-manager connection-manager})

;;; ---------------------------------------------------------------------------
;;; Code Scanner Component
;;; ---------------------------------------------------------------------------
;;; Populates the Datalevin knowledge graph at startup by analyzing the
;;; codebase with clj-kondo and scanning for schema/register! calls.

(defmethod ig/init-key :seon/code-scanner
  [_ {:keys [connection-manager paths enabled?]}]
  (when enabled?
    (require 'seon.graph.analyzer)
    (require 'seon.graph.scanner)
    (require 'seon.graph.ingest)
    (require 'datalevin.core)
    (require 'seon.db.datalevin.conn)
    (let [;; Resolve functions
          analyze-project! (resolve 'seon.graph.analyzer/analyze-project!)
          extract-entities (resolve 'seon.graph.analyzer/extract-entities)
          scan-directory (resolve 'seon.graph.scanner/scan-directory)
          link-fns-to-specs (resolve 'seon.graph.scanner/link-fns-to-specs)
          ingest-analysis! (resolve 'seon.graph.ingest/ingest-analysis!)
          datalevin-schema (deref (resolve 'seon.graph.ingest/datalevin-schema))
          ;; Get a graph connection with schema via the connection manager internals
          get-conn (resolve 'datalevin.core/get-conn)
          build-uri-fn (fn [mgr db-name]
                         (format "dtlv://%s:%s@%s:%d/%s"
                                 (or (:seon.db.datalevin.conn/username mgr) "datalevin")
                                 (or (:seon.db.datalevin.conn/password mgr) "datalevin")
                                 (or (:seon.db.datalevin.conn/host mgr) "127.0.0.1")
                                 (:seon.db.datalevin.conn/port mgr)
                                 db-name))
          graph-uri (build-uri-fn connection-manager "seon-graph")
          conn (get-conn graph-uri datalevin-schema)]
      (try
        ;; Full project analysis
        (log/info "Code scanner: analyzing project..." {:paths paths})
        (let [project (analyze-project! {:seon.graph.analyzer/paths paths})]
          (if-not (:seon.graph.analyzer/success project)
            (log/warn "Code scanner: analysis failed" {:error (:seon.graph.analyzer/error project)})
            (let [entities (extract-entities {:seon.graph.analyzer/raw-analysis
                                             (:seon.graph.analyzer/raw-analysis project)})
                  ;; Scan for specs
                  specs (into [] (mapcat #(scan-directory {:seon.graph.scanner/dir-path %})) paths)
                  ;; Link functions to specs
                  linked-fns (link-fns-to-specs (:seon.graph.analyzer/functions entities) specs)
                  entities (assoc entities :seon.graph.analyzer/functions linked-fns)
                  ;; Ingest everything
                  result (ingest-analysis! {:seon.graph.ingest/conn conn
                                            :seon.graph.ingest/entities entities
                                            :seon.graph.ingest/specs specs})]
              (log/info "Code scanner initialized" result))))
        {:conn conn :paths paths}
        (catch Exception e
          (log/error "Code scanner failed" {:error (.getMessage e)})
          ;; Still return conn so halt can close it
          {:conn conn :paths paths})))))

(defmethod ig/halt-key! :seon/code-scanner
  [_ state]
  (when-let [conn (:conn state)]
    (log/info "Stopping code scanner...")
    (require 'datalevin.core)
    ((resolve 'datalevin.core/close) conn)
    (log/info "Code scanner stopped")))

;;; ---------------------------------------------------------------------------
;;; Claude Code SDK Configuration
;;; ---------------------------------------------------------------------------
;;; Configuration for the Claude Code CLI. Currently just holds the CLI path.
;;; The actual CLI interaction is in seon.ai.claude.sdk.

(defmethod ig/init-key :seon/claude-code
  [_ config]
  (log/info "Claude Code SDK configured" {:cli-path (:cli-path config)})
  config)
