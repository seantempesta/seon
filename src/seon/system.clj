(ns seon.system
  "Integrant system configuration and component definitions.

  Defines init-key and halt-key! methods for all system components:
  - :seon.db/flow - Datahike per-namespace conn-process flow (sole database)
  - :seon.schema/registry - Malli schema registry
  - :seon.web.server/http-server - HTTP server for web UI
  - :seon.dev/nrepl - nREPL for REPL-driven development
  - :seon.flow/infrastructure - Reply-router + REPL eval + sinks
  - :seon.flow/pool - Pre-warmed agent JVM pool"
  (:require [clojure.core.async.flow :as flow]
            [integrant.core :as ig]
            [taoensso.timbre :as log]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.flow.msg :as msg]
            [seon.flow.status :as status]
            [seon.flow.topology :as topology]
            [seon.runtime :as runtime]
            [seon.dev.instrumentation :as instrumentation]
            ;; Load component namespaces for their ig/init-key methods
            [seon.web.tailwind]
            [seon.web.caddy]
            [seon.db.datahike.system]
            [seon.flow.pool]
            ;; Load namespaces whose schemas the datahike flow refers to.
            ;; The :seon.db/flow Integrant key reads :namespace-schemas at init
            ;; and resolves keyword schema refs through the Malli registry —
            ;; the registry must be populated by then.
            [seon.session]
            [seon.repl]
            [seon.flow.trace]
            [seon.orchestrator.session]
            [seon.phase2.demo]))

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
;;; Schema Consistency Check Component
;;; ---------------------------------------------------------------------------
;;; Validates all persisted entity schemas at boot. Catches :any, :some,
;;; [:maybe X], mixed enums, and :nil before they reach the storage layer.
;;; Depends on :seon.schema/registry to ensure all modules are loaded.

(defmethod ig/init-key :seon.db.schema/consistency-check
  [_ {:keys [registry]}]
  (require 'seon.db.schema)
  (log/info "Running schema consistency check...")
  (let [result ((resolve 'seon.db.schema/validate-persisted-schemas!))]
    (log/info "Schema consistency check complete"
              {:schema-count (:schema-count result)})
    result))

;; Pure check result, survives reset.
(defmethod ig/suspend-key! :seon.db.schema/consistency-check [_ state] state)

(defmethod ig/resume-key :seon.db.schema/consistency-check
  [_ opts old-opts old-state]
  (if (= opts old-opts)
    old-state
    (ig/init-key :seon.db.schema/consistency-check opts)))

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
;;; Initializes the orchestrator session system. The legacy connection-manager
;;; arg is retained on the init key for backwards compatibility; system.edn no
;;; longer supplies it, and `init!` already ignored it.

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
;;; Code Scanner Component
;;; ---------------------------------------------------------------------------
;;; Populates the runtime knowledge graph at startup by analyzing the codebase
;;; with clj-kondo and scanning for schema/register! calls. Currently absent
;;; from resources/system.edn — the scanner depends on a runtime db connection
;;; that is not yet wired into the datahike flow. Re-introducing the scanner
;;; waits on the :seon.runtime datahike migration.

;;; ---------------------------------------------------------------------------
;;; Infrastructure Flow Component
;;; ---------------------------------------------------------------------------
;;; Starts the infrastructure flow (reply-router + sinks + REPL eval + status)
;;; at boot. Namespace flows are NOT part of this — they're created lazily
;;; later. Database writes are owned by per-namespace conn-processes in
;;; `:seon.db/flow`.

(defmethod ig/init-key :seon.flow/infrastructure
  [_ _opts]
  (log/info "Starting infrastructure flow...")
  (let [result (topology/build-infrastructure! {})]
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
