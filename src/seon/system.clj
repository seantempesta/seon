(ns seon.system
  "Integrant system configuration and component definitions.

  Defines init-key and halt-key! methods for all system components:
  - :seon/datalevin-server - Datalevin server for all data storage
  - :seon/schema-registry - Malli schema registry
  - :seon.web.server/http-server - HTTP server for web UI
  - :seon/nrepl-server - nREPL for REPL-driven development
  - :seon.orchestrator/namespace-nrepls - Per-namespace nREPL servers for agent isolation

  Datalevin is the sole database. XTDB has been removed."
  (:require [integrant.core :as ig]
            [taoensso.timbre :as log]
            [seon.db.schema :as schema]
            ;; Load component namespaces for their ig/init-key methods
            [seon.web.tailwind]
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
;;; Namespace nREPL Servers Component
;;; ---------------------------------------------------------------------------
;;; Manages per-namespace nREPL servers for agent isolation.
;;; Each namespace gets its own nREPL on a unique port with injected *ctx*.

(defmethod ig/init-key :seon.orchestrator/namespace-nrepls
  [_ {:keys [namespaces]}]
  (log/info "Starting namespace nREPL servers..." {:namespaces namespaces})
  (require 'seon.orchestrator.nrepl)
  (let [start! (resolve 'seon.orchestrator.nrepl/start-namespace-nrepl!)
        ;; Generate a deterministic session-id from namespace (for Integrant-managed nREPLs)
        ns->session-id (fn [ns-sym] (str "ig-" (hash ns-sym)))
        ;; Start an nREPL for each namespace
        results (into {}
                      (for [ns-sym namespaces]
                        (let [session-id (ns->session-id ns-sym)
                              result (start! {:session-id session-id
                                              :namespace ns-sym
                                              :db nil})]
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
    (doseq [[ns-sym {:keys [session-id]}] servers]
      (try
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
        ;; Wire the graph connection into the render system for Datalevin resolution
        (require 'seon.render)
        ((resolve 'seon.render/set-conn!) conn)
        (log/info "Render system connected to graph database")
        {:conn conn :paths paths}
        (catch Exception e
          (log/error "Code scanner failed" {:error (.getMessage e)})
          ;; Still return conn so halt can close it
          {:conn conn :paths paths})))))

(defmethod ig/halt-key! :seon/code-scanner
  [_ state]
  (when-let [conn (:conn state)]
    (log/info "Stopping code scanner...")
    ;; Disconnect render system before closing the graph connection
    (require 'seon.render)
    ((resolve 'seon.render/set-conn!) nil)
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
