(ns seon.system
  "Integrant system configuration and component definitions.

  Defines init-key and halt-key! methods for all system components:
  - :seon/datalevin-server - Datalevin server for all data storage
  - :seon/graph-db - Graph database connection (code graph + runtime registry)
  - :seon/schema-registry - Malli schema registry
  - :seon.web.server/http-server - HTTP server for web UI
  - :seon/nrepl-server - nREPL for REPL-driven development
  - :seon.orchestrator/namespace-nrepls - Per-namespace nREPL servers for agent isolation

  Datalevin is the sole database. XTDB has been removed."
  (:require [integrant.core :as ig]
            [taoensso.timbre :as log]
            [seon.db.schema :as schema]
            [seon.runtime :as runtime]
            ;; Load component namespaces for their ig/init-key methods
            [seon.web.tailwind]
            [seon.web.caddy]
            [seon.db.datalevin.server]
            [seon.db.datalevin.conn]
            [seon.flow.pool]))

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
  [_ {:keys [connection-manager pool]}]
  (require 'seon.orchestrator.session)
  ((resolve 'seon.orchestrator.session/init!) connection-manager :pool pool)
  {:connection-manager connection-manager :pool pool})

;;; ---------------------------------------------------------------------------
;;; Graph Database Component
;;; ---------------------------------------------------------------------------
;;; Owns the seon.runtime Datalevin connection used by:
;;; - Code scanner (code graph entities)
;;; - Runtime registry (namespace instance tracking)
;;; - Render system (function resolution)

(defn- build-graph-uri
  "Build Datalevin URI for the graph database."
  [connection-manager]
  (format "dtlv://%s:%s@%s:%d/%s"
          (or (:seon.db.datalevin.conn/username connection-manager) "datalevin")
          (or (:seon.db.datalevin.conn/password connection-manager) "datalevin")
          (or (:seon.db.datalevin.conn/host connection-manager) "127.0.0.1")
          (:seon.db.datalevin.conn/port connection-manager)
          "seon.runtime"))

(defmethod ig/init-key :seon/graph-db
  [_ {:keys [connection-manager]}]
  (require 'datalevin.core)
  (require 'seon.graph.ingest)
  (let [get-conn (resolve 'datalevin.core/get-conn)
        ;; Merge code graph schema with runtime schema
        graph-schema (deref (resolve 'seon.graph.ingest/datalevin-schema))
        merged-schema (merge graph-schema runtime/runtime-schema)
        graph-uri (build-graph-uri connection-manager)
        conn (get-conn graph-uri merged-schema)]
    (log/info "Graph database connected" {:uri (str "dtlv://...@.../" "seon.runtime")})
    ;; Initialize runtime registry with the graph connection
    (runtime/init! {::runtime/conn conn})
    ;; Mark any instances from previous unclean shutdown as crashed
    (let [{::runtime/keys [crashed-count]} (runtime/mark-crashed! {})]
      (when (pos? crashed-count)
        (log/warn "Found crashed instances from previous run" {:count crashed-count})))
    ;; Hydrate in-memory cache from Datalevin (includes crashed instances)
    (let [{::runtime/keys [hydrated-count]} (runtime/hydrate-cache! {})]
      (when (pos? hydrated-count)
        (log/info "Hydrated runtime cache from Datalevin" {:count hydrated-count})))
    ;; Wire into render system
    (require 'seon.render)
    ((resolve 'seon.render/set-conn!) conn)
    (log/info "Render system connected to graph database")
    ;; Register this component
    (runtime/register! {::runtime/namespace "seon.graph.db"
                        ::runtime/status :running
                        ::runtime/location :in-process
                        ::runtime/component-key :seon/graph-db})
    {:conn conn :connection-manager connection-manager}))

(defmethod ig/halt-key! :seon/graph-db
  [_ {:keys [conn]}]
  (when conn
    (log/info "Stopping graph database...")
    ;; Unregister this component
    (runtime/unregister! {::runtime/namespace "seon.graph.db"})
    ;; Disconnect render system before closing
    (require 'seon.render)
    ((resolve 'seon.render/set-conn!) nil)
    (require 'datalevin.core)
    ((resolve 'datalevin.core/close) conn)
    (log/info "Graph database stopped")))

;; Suspend/resume to survive (reset) like nREPL
(defmethod ig/suspend-key! :seon/graph-db [_ state] state)

(defmethod ig/resume-key :seon/graph-db
  [key opts old-opts old-state]
  (if (= (:connection-manager opts) (:connection-manager old-opts))
    old-state
    (do (ig/halt-key! key old-state)
        (ig/init-key key opts))))

;;; ---------------------------------------------------------------------------
;;; Code Scanner Component
;;; ---------------------------------------------------------------------------
;;; Populates the Datalevin knowledge graph at startup by analyzing the
;;; codebase with clj-kondo and scanning for schema/register! calls.
;;; Receives its connection from :seon/graph-db component.

(defmethod ig/init-key :seon/code-scanner
  [_ {:keys [graph-db paths enabled?]}]
  (when enabled?
    (let [conn (:conn graph-db)]
      (when-not conn
        (log/warn "Code scanner: no graph-db connection available")
        (throw (ex-info "Code scanner requires graph-db connection" {})))
      (require 'seon.graph.analyzer)
      (require 'seon.graph.scanner)
      (require 'seon.graph.ingest)
      (let [analyze-project! (resolve 'seon.graph.analyzer/analyze-project!)
            extract-entities (resolve 'seon.graph.analyzer/extract-entities)
            scan-directory (resolve 'seon.graph.scanner/scan-directory)
            link-fns-to-specs (resolve 'seon.graph.scanner/link-fns-to-specs)
            ingest-analysis! (resolve 'seon.graph.ingest/ingest-analysis!)]
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
          ;; Register this component in runtime
          (runtime/register! {::runtime/namespace "seon.graph.scanner"
                              ::runtime/status :running
                              ::runtime/location :in-process
                              ::runtime/component-key :seon/code-scanner})
          {:conn conn :paths paths}
          (catch Exception e
            (log/error "Code scanner failed" {:error (.getMessage e)})
            {:conn conn :paths paths :error (.getMessage e)}))))))

(defmethod ig/halt-key! :seon/code-scanner
  [_ state]
  (when (:conn state)
    (log/info "Stopping code scanner...")
    ;; Unregister from runtime (connection owned by graph-db, not closed here)
    (runtime/unregister! {::runtime/namespace "seon.graph.scanner"})
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
