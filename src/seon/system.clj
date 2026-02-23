(ns seon.system
  "Integrant system configuration and component definitions.

  Defines init-key and halt-key! methods for all system components:
  - :seon.db.datalevin/server - Datalevin server for all data storage
  - :seon/runtime-db - Runtime database connection (code graph + runtime registry)
  - :seon.schema/registry - Malli schema registry
  - :seon.web.server/http-server - HTTP server for web UI
  - :seon.dev/nrepl - nREPL for REPL-driven development
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
;;; Config Validation (assert-key)
;;; ---------------------------------------------------------------------------
;;; Validates all :seon/component configs against Malli schemas before init.
;;; The hierarchy in resources/integrant/hierarchy.edn derives all component
;;; keys from :seon/component, so this single method catches everything.

(defmethod ig/assert-key :seon/component
  [component-key value]
  (require 'seon.system.config)
  (when-let [errors ((resolve 'seon.system.config/validate) component-key value)]
    (throw (ex-info (str "Invalid config for " component-key ":\n"
                         (pr-str errors)
                         "\n\nCheck resources/system.edn")
                    {:key component-key :value value :errors errors}))))

;;; ---------------------------------------------------------------------------
;;; Schema Registry Component
;;; ---------------------------------------------------------------------------

(defmethod ig/init-key :seon.schema/registry
  [_ _]
  (log/info "Initializing Malli schema registry...")
  (let [registry-value @schema/registry]
    (log/info "Schema registry initialized" {:schema-count (count registry-value)})
    registry-value))

(defmethod ig/halt-key! :seon.schema/registry
  [_ _]
  (log/info "Schema registry shutdown")
  nil)

;; Pure value, survives reset. Only re-init if config changes.
(defmethod ig/suspend-key! :seon.schema/registry [_ state] state)

(defmethod ig/resume-key :seon.schema/registry
  [_ opts old-opts old-state]
  (if (= opts old-opts)
    old-state
    (do (ig/halt-key! :seon.schema/registry old-state)
        (ig/init-key :seon.schema/registry opts))))

;;; ---------------------------------------------------------------------------
;;; nREPL Server Component
;;; ---------------------------------------------------------------------------

(defmethod ig/init-key :seon.dev/nrepl
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

(defmethod ig/halt-key! :seon.dev/nrepl
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
(defmethod ig/suspend-key! :seon.dev/nrepl [_ server] server)

(defmethod ig/resume-key :seon.dev/nrepl
  [_ opts old-opts old-server]
  (if (= opts old-opts)
    old-server
    (do (ig/halt-key! :seon.dev/nrepl old-server)
        (ig/init-key :seon.dev/nrepl opts))))

;;; ---------------------------------------------------------------------------
;;; Primer Ctx Component
;;; ---------------------------------------------------------------------------
;;; Initializes the primer ctx system with the Datalevin connection manager.

(defmethod ig/init-key :seon.primer/ctx
  [_ {:keys [connection-manager]}]
  (require 'seon.primer.ctx)
  ((resolve 'seon.primer.ctx/init!) connection-manager)
  {:connection-manager connection-manager})

(defmethod ig/halt-key! :seon.primer/ctx
  [_ _]
  (log/info "Primer ctx shutdown"))

(defmethod ig/suspend-key! :seon.primer/ctx [_ state] state)

(defmethod ig/resume-key :seon.primer/ctx
  [_ opts old-opts old-state]
  (if (= opts old-opts)
    old-state
    (do (ig/halt-key! :seon.primer/ctx old-state)
        (ig/init-key :seon.primer/ctx opts))))

;;; ---------------------------------------------------------------------------
;;; Orchestrator Sessions Component
;;; ---------------------------------------------------------------------------
;;; Initializes the orchestrator session system with the Datalevin connection manager.

(defmethod ig/init-key :seon.orchestrator/sessions
  [_ {:keys [connection-manager pool]}]
  (require 'seon.orchestrator.session)
  ((resolve 'seon.orchestrator.session/init!) connection-manager :pool pool)
  {:connection-manager connection-manager :pool pool})

(defmethod ig/halt-key! :seon.orchestrator/sessions
  [_ _]
  (log/info "Orchestrator sessions shutdown"))

(defmethod ig/suspend-key! :seon.orchestrator/sessions [_ state] state)

(defmethod ig/resume-key :seon.orchestrator/sessions
  [_ opts old-opts old-state]
  (if (= opts old-opts)
    (do
      ;; Re-wire pool atom — defonce atoms survive reload but init! may not
      ;; have been called if resume short-circuited on a previous cycle
      (require 'seon.orchestrator.session)
      ((resolve 'seon.orchestrator.session/init!)
       (:connection-manager opts) :pool (:pool opts))
      old-state)
    (do (ig/halt-key! :seon.orchestrator/sessions old-state)
        (ig/init-key :seon.orchestrator/sessions opts))))

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

(defmethod ig/init-key :seon/runtime-db
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
    ;; Register this component
    (runtime/register! {::runtime/namespace "seon.graph.db"
                        ::runtime/status :running
                        ::runtime/location :in-process
                        ::runtime/component-key :seon/runtime-db})
    {:conn conn :connection-manager connection-manager}))

(defmethod ig/halt-key! :seon/runtime-db
  [_ {:keys [conn]}]
  (when conn
    (log/info "Stopping graph database...")
    ;; Unregister this component
    (runtime/unregister! {::runtime/namespace "seon.graph.db"})
    (require 'datalevin.core)
    ((resolve 'datalevin.core/close) conn)
    (log/info "Graph database stopped")))

;; Suspend/resume to survive (reset) like nREPL
(defmethod ig/suspend-key! :seon/runtime-db [_ state] state)

(defmethod ig/resume-key :seon/runtime-db
  [_ opts old-opts old-state]
  (if (= (:connection-manager opts) (:connection-manager old-opts))
    (do
      ;; Re-wire runtime atom — defonce atoms survive reload
      ;; but may hold stale refs if conn was closed by a previous halt cycle
      (runtime/init! {::runtime/conn (:conn old-state)})
      old-state)
    (do (ig/halt-key! :seon/runtime-db old-state)
        (ig/init-key :seon/runtime-db opts))))

;;; ---------------------------------------------------------------------------
;;; Code Scanner Component
;;; ---------------------------------------------------------------------------
;;; Populates the Datalevin knowledge graph at startup by analyzing the
;;; codebase with clj-kondo and scanning for schema/register! calls.
;;; Receives its connection from :seon/runtime-db component.

(defmethod ig/init-key :seon.graph/scanner
  [_ {:keys [graph-db paths enabled?]}]
  (when enabled?
    (let [conn (:conn graph-db)]
      (when-not conn
        (log/warn "Code scanner: no graph-db connection available")
        (throw (ex-info "Code scanner requires graph-db connection" {})))
      (require 'seon.graph.extract)
      (require 'seon.graph.ingest)
      (let [extract-graph-from-file (resolve 'seon.graph.extract/extract-graph-from-file)
            ingest-namespace! (resolve 'seon.graph.ingest/ingest-namespace!)]
        (try
          (log/info "Code scanner: extracting graph for project..." {:paths paths})
          (let [clj-files (->> (mapcat #(file-seq (java.io.File. %)) paths)
                               (filter (fn [^java.io.File f]
                                         (and (.isFile f)
                                              (let [n (.getName f)]
                                                (or (.endsWith n ".clj")
                                                    (.endsWith n ".cljs")
                                                    (.endsWith n ".cljc"))))))
                               vec)
                total (count clj-files)]
            (log/info "Code scanner: found files to process" {:count total})
            (doseq [^java.io.File f clj-files]
              (try
                (let [path (.getAbsolutePath f)
                      graph (extract-graph-from-file {:seon.graph.extract/file-path path})
                      ns-str (:seon.graph.extract/ns-name graph)]
                  (when ns-str
                    (ingest-namespace!
                     {:seon.graph.ingest/conn conn
                      :seon.graph.ingest/ns-name ns-str
                      :seon.graph.ingest/functions (:seon.graph.extract/functions graph)
                      :seon.graph.ingest/specs (:seon.graph.extract/specs graph)
                      :seon.graph.ingest/vars (:seon.graph.extract/vars graph)
                      :seon.graph.ingest/call-edges (:seon.graph.extract/call-edges graph)
                      :seon.graph.ingest/ns-deps (:seon.graph.extract/ns-deps graph)
                      :seon.graph.ingest/ns-entities (:seon.graph.extract/namespaces graph)})))
                (catch Exception e
                  (log/debug "Code scanner: failed to process file"
                             {:file (.getName f) :error (.getMessage e)}))))
            (log/info "Code scanner initialized" {:files-processed total}))
          ;; Register this component in runtime
          (runtime/register! {::runtime/namespace "seon.graph.scanner"
                              ::runtime/status :running
                              ::runtime/location :in-process
                              ::runtime/component-key :seon.graph/scanner})
          {:conn conn :paths paths}
          (catch Exception e
            (log/error "Code scanner failed" {:error (.getMessage e)})
            {:conn conn :paths paths :error (.getMessage e)}))))))

(defmethod ig/halt-key! :seon.graph/scanner
  [_ state]
  (when (:conn state)
    (log/info "Stopping code scanner...")
    ;; Unregister from runtime (connection owned by graph-db, not closed here)
    (runtime/unregister! {::runtime/namespace "seon.graph.scanner"})
    (log/info "Code scanner stopped")))

;; Suspend/resume: keep scanner results alive during (reset).
;; The graph-db connection survives reset, so re-scanning is wasteful.
;; Only re-scan if paths changed.
(defmethod ig/suspend-key! :seon.graph/scanner [_ state] state)

(defmethod ig/resume-key :seon.graph/scanner
  [_ opts old-opts old-state]
  (if (= (:paths opts) (:paths old-opts))
    old-state
    (do (ig/halt-key! :seon.graph/scanner old-state)
        (ig/init-key :seon.graph/scanner opts))))

;;; ---------------------------------------------------------------------------
;;; Claude Code SDK Configuration
;;; ---------------------------------------------------------------------------
;;; Configuration for the Claude Code CLI. Currently just holds the CLI path.
;;; The actual CLI interaction is in seon.ai.claude.sdk.

(defmethod ig/init-key :seon.ai.claude/sdk
  [_ config]
  (log/info "Claude Code SDK configured" {:cli-path (:cli-path config)})
  config)
