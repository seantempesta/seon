(ns seon.system
  "Integrant system configuration and component definitions.

  Defines init-key and halt-key! methods for all system components:
  - :seon.db.datalevin/server - Datalevin server for all data storage
  - :seon/runtime-db - Runtime database connection (code graph + runtime registry)
  - :seon.schema/registry - Malli schema registry
  - :seon.web.server/http-server - HTTP server for web UI
  - :seon.dev/nrepl - nREPL for REPL-driven development
  - :seon.orchestrator/namespace-nrepls - Per-namespace nREPL servers for agent isolation

  Datalevin is the sole database."
  (:require [clojure.core.async.flow :as flow]
            [integrant.core :as ig]
            [taoensso.timbre :as log]
            [seon.schema :as schema]
            [seon.db.datalevin.conn :as dl-conn]
            [seon.flow.msg :as msg]
            [seon.flow.status :as status]
            [seon.flow.topology :as topology]
            [seon.runtime :as runtime]
            [seon.dev.instrumentation :as instrumentation]
            ;; Load component namespaces for their ig/init-key methods
            [seon.web.tailwind]
            [seon.web.caddy]
            [seon.db.datalevin.server]
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
  (let [schemas (schema/registered-schemas)]
    (log/info "Schema registry initialized" {:schema-count (count schemas)})
    schemas))

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
;;; ---------------------------------------------------------------------------
;;; Orchestrator Sessions Component
;;; ---------------------------------------------------------------------------
;;; Initializes the orchestrator session system with the Datalevin connection manager.

(defmethod ig/init-key :seon.orchestrator/sessions
  [_ {:keys [connection-manager pool]}]
  (log/info "Initializing orchestrator sessions...")
  (require 'seon.orchestrator.session)
  ((resolve 'seon.orchestrator.session/init!) connection-manager :pool pool)
  (log/info "Orchestrator sessions initialized")
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

(defn runtime-db-conn
  "Get a fresh runtime database connection from the runtime-db component.
   Always goes through the connection manager, so handles reconnection
   after connection pool recycling or server restarts.

   Usage:
     (runtime-db-conn (:seon/runtime-db state/system))"
  [{:keys [connection-manager]}]
  (dl-conn/get-conn!
   {::dl-conn/manager connection-manager
    ::dl-conn/db :seon.runtime
    ::dl-conn/schema (runtime/runtime-merged-schema)}))

(defmethod ig/init-key :seon/runtime-db
  [_ {:keys [connection-manager]}]
  ;; Get conn through the connection manager (handles staleness, auto-reconnect)
  (let [conn (dl-conn/get-conn!
              {::dl-conn/manager connection-manager
               ::dl-conn/db :seon.runtime
               ::dl-conn/schema (runtime/runtime-merged-schema)})]
    (log/info "Runtime database connected via connection manager")
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
    ;; Only store connection-manager — consumers must call runtime-db-conn
    ;; to get a fresh connection. Storing :conn here causes stale references
    ;; after TTL cleanup or server restarts.
    {:connection-manager connection-manager}))

(defmethod ig/halt-key! :seon/runtime-db
  [_ _state]
  (log/info "Stopping runtime-db component...")
  ;; Unregister this component
  (runtime/unregister! {::runtime/namespace "seon.graph.db"})
  ;; Don't close conn directly — connection manager owns it
  (log/info "Runtime-db component stopped"))

;; Suspend/resume to survive (reset) like nREPL
(defmethod ig/suspend-key! :seon/runtime-db [_ state] state)

(defmethod ig/resume-key :seon/runtime-db
  [_ opts old-opts old-state]
  (if (= (:connection-manager opts) (:connection-manager old-opts))
    ;; Connection manager unchanged — conn is still valid (manager handles staleness)
    old-state
    (do (ig/halt-key! :seon/runtime-db old-state)
        (ig/init-key :seon/runtime-db opts))))

;;; ---------------------------------------------------------------------------
;;; Code Scanner Component
;;; ---------------------------------------------------------------------------
;;; Populates the Datalevin knowledge graph at startup by analyzing the
;;; codebase with clj-kondo and scanning for schema/register! calls.
;;; Receives its connection from :seon/runtime-db component.
;;; Runs in a background future to avoid blocking startup (~3s savings).

(defn- run-code-scan!
  "Execute the code scan in the current thread. Called from a future."
  [paths]
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
        ;; Phase 1: Extract graphs in parallel (CPU-bound, no DB)
        (let [graphs (->> clj-files
                          (pmap (fn [^java.io.File f]
                                  (try
                                    (extract-graph-from-file
                                     {:seon.graph.extract/file-path (.getAbsolutePath f)})
                                    (catch Throwable e
                                      (log/warn "Code scanner: extract failed"
                                                {:file (.getName f) :error (.getMessage e)})
                                      nil))))
                          (filter :seon.graph.extract/ns-name)
                          vec)]
          ;; Phase 2: Ingest sequentially (DB writes)
          (doseq [graph graphs]
            (try
              (let [ns-str (:seon.graph.extract/ns-name graph)]
                (ingest-namespace!
                 {:seon.graph.ingest/db-name :seon.runtime
                  :seon.graph.ingest/ns-name ns-str
                  :seon.graph.ingest/functions (:seon.graph.extract/functions graph)
                  :seon.graph.ingest/specs (:seon.graph.extract/specs graph)
                  :seon.graph.ingest/vars (:seon.graph.extract/vars graph)
                  :seon.graph.ingest/call-edges (:seon.graph.extract/call-edges graph)
                  :seon.graph.ingest/ns-deps (:seon.graph.extract/ns-deps graph)
                  :seon.graph.ingest/ns-entities (:seon.graph.extract/namespaces graph)}))
              (catch Exception e
                (log/warn "Code scanner: ingest failed"
                          {:ns (:seon.graph.extract/ns-name graph)
                           :error (.getMessage e)}))))
          (log/info "Code scanner complete"
                    {:files-processed total :namespaces-ingested (count graphs)})))
      (catch Exception e
        (log/error "Code scanner failed" {:error (.getMessage e)})))))

(defmethod ig/init-key :seon.graph/scanner
  [_ {:keys [graph-db paths enabled?]}]
  (when enabled?
    ;; Verify connectivity before starting scan
    (when-not (runtime-db-conn graph-db)
      (log/warn "Code scanner: no graph-db connection available")
      (throw (ex-info "Code scanner requires graph-db connection" {})))
    (require 'seon.graph.extract)
    (require 'seon.graph.ingest)
    ;; Register immediately so system sees the component
    (runtime/register! {::runtime/namespace "seon.graph.scanner"
                        ::runtime/status :running
                        ::runtime/location :in-process
                        ::runtime/component-key :seon.graph/scanner})
    ;; Run scan in background so startup isn't blocked (~3s savings)
    (let [scan-future (future (run-code-scan! paths))]
      {:graph-db graph-db :paths paths :scan-future scan-future})))

(defmethod ig/halt-key! :seon.graph/scanner
  [_ state]
  (when (:graph-db state)
    (log/info "Stopping code scanner...")
    ;; Cancel scan if still running
    (when-let [f (:scan-future state)]
      (future-cancel f))
    ;; Unregister from runtime (connection owned by graph-db, not closed here)
    (runtime/unregister! {::runtime/namespace "seon.graph.scanner"})
    (log/info "Code scanner stopped")))

;; Always halt+init on resume. The scanner holds a Datalevin connection
;; captured at init time (passed to run-code-scan! future). After reset,
;; that connection may be closed by TTL cleanup or server restart.
;; Re-scanning is cheap (~3s in background) and ensures fresh connections.
(defmethod ig/suspend-key! :seon.graph/scanner [_ state] state)

(defmethod ig/resume-key :seon.graph/scanner
  [_ opts _old-opts old-state]
  (ig/halt-key! :seon.graph/scanner old-state)
  (ig/init-key :seon.graph/scanner opts))

;;; ---------------------------------------------------------------------------
;;; Infrastructure Flow Component
;;; ---------------------------------------------------------------------------
;;; Starts the infrastructure flow (writer + reply-router + sinks) at boot.
;;; The writer handles ALL database writes via the connection manager.
;;; Namespace flows are NOT part of this — they're created lazily later.

(defmethod ig/init-key :seon.flow/infrastructure
  [_ {:keys [connection-manager]}]
  (log/info "Starting infrastructure flow...")
  (let [result (topology/build-infrastructure!
                {::topology/connection-manager connection-manager})]
    (log/info "Infrastructure flow started"
              {:flow-id (::topology/flow-id result)})
    result))

(defmethod ig/halt-key! :seon.flow/infrastructure
  [_ state]
  (when-let [fl (::topology/flow state)]
    (log/info "Stopping infrastructure flow...")
    (let [flow-id (::topology/flow-id state)]
      (try
        ;; Don't use stop-topology! here -- it tries to snapshot via db/transact!
        ;; which routes through THIS flow. Circular. Just stop directly.
        (flow/pause fl)
        (flow/ping fl :timeout-ms 3000)
        (catch Throwable t
          (log/warn "Pause/ping failed during halt" {:error (.getMessage t)})))
      (try
        (flow/stop fl)
        (catch Throwable t
          (log/warn "Error stopping infrastructure flow" {:error (.getMessage t)})))
      ;; Clean up pending promises
      (doseq [[_id p] @topology/pending-promises]
        (deliver p {::msg/status :error
                    ::msg/error-type :timeout
                    ::msg/error-message "Infrastructure flow stopped"}))
      (reset! topology/pending-promises {})
      ;; Unregister from runtime
      (when flow-id
        (status/stop-error-drain! {::status/id flow-id})
        (runtime/unregister-flow! {::runtime/flow-id flow-id})))
    (log/info "Infrastructure flow stopped")))

;; Always halt+init on resume. Flow objects are immutable — if code has been
;; reloaded and build-infrastructure! now includes new processes (e.g. repl-step
;; added after initial build), the old flow object won't have them.
(defmethod ig/suspend-key! :seon.flow/infrastructure [_ state] state)

(defmethod ig/resume-key :seon.flow/infrastructure
  [_ opts _old-opts old-state]
  (ig/halt-key! :seon.flow/infrastructure old-state)
  (ig/init-key :seon.flow/infrastructure opts))

;;; ---------------------------------------------------------------------------
;;; Claude Code SDK Configuration
;;; ---------------------------------------------------------------------------
;;; Configuration for the Claude Code CLI. Currently just holds the CLI path.
;;; The actual CLI interaction is in seon.ai.claude.sdk.

;;; ---------------------------------------------------------------------------
;;; Malli Instrumentation Component
;;; ---------------------------------------------------------------------------
;;; Instruments all functions with :malli/schema metadata for runtime validation.
;;; Agent-friendly error messages on schema violations.

(defmethod ig/init-key :seon.dev/instrumentation
  [_ opts]
  (instrumentation/start! opts))

(defmethod ig/halt-key! :seon.dev/instrumentation
  [_ _]
  (instrumentation/stop!))

;; Survives reset — instrumentation persists across reloads
(defmethod ig/suspend-key! :seon.dev/instrumentation [_ state] state)
(defmethod ig/resume-key :seon.dev/instrumentation [_ _ _ old] old)

;;; ---------------------------------------------------------------------------
;;; Claude Code SDK Configuration
;;; ---------------------------------------------------------------------------

(defmethod ig/init-key :seon.ai.claude/sdk
  [_ config]
  (log/info "Claude Code SDK configured" {:cli-path (:cli-path config)})
  config)
