(ns seon.system.config
  "Malli schemas for Integrant component configurations.

   Used by assert-key for validation and by agents for introspection.
   Each schema describes what system.edn provides AFTER #ig/ref resolution."
  (:require [malli.core :as m]
            [malli.error :as me]))

(def schemas
  "Component key -> Malli config schema."
  {;; Datalevin Server
   :seon.db.datalevin/server
   [:map
    [:port [:int {:min 1 :max 65535}]]
    [:root :string]
    [:opts {:optional true} [:map {:closed false}
                             [:idle-timeout {:optional true} [:int {:min 0}]]]]]

   ;; Connection Manager — :server is a resolved #ig/ref
   :seon.db.datalevin/connections
   [:map
    [:server :any]]

   ;; Runtime Database — :connection-manager is a resolved #ig/ref
   :seon/runtime-db
   [:map
    [:connection-manager :any]]

   ;; Schema Registry — empty map, no required keys
   :seon.schema/registry
   [:map]

   ;; nREPL Server
   :seon.dev/nrepl
   [:map
    [:enabled? :boolean]
    [:port {:optional true} [:int {:min 1 :max 65535}]]
    [:bind {:optional true} :string]]

   ;; HTTP Server — :handler injected at runtime, not in system.edn
   :seon.web.server/http-server
   [:map
    [:port [:int {:min 1 :max 65535}]]
    [:bind {:optional true} :string]
    [:handler {:optional true} :any]]

   ;; Tailwind CSS Watcher
   :seon.web/tailwind
   [:map
    [:enabled? :boolean]
    [:input {:optional true} :string]
    [:output {:optional true} :string]]

   ;; Caddy Reverse Proxy
   :seon.web/caddy
   [:map
    [:enabled? :boolean]
    [:config-file {:optional true} :string]
    [:http-server {:optional true} :any]]

   ;; Agent JVM Pool
   :seon.flow/pool
   [:map
    [:size {:optional true} [:int {:min 0}]]
    [:base-port {:optional true} [:int {:min 1 :max 65535}]]
    [:datalevin-server {:optional true} :any]
    [:enabled? {:optional true} :boolean]]

   ;; Orchestrator Sessions
   :seon.orchestrator/sessions
   [:map
    [:connection-manager {:optional true} :any]
    [:pool :any]]

   ;; Code Scanner
   :seon.graph/scanner
   [:map
    [:graph-db {:optional true} :any]
    [:paths [:vector :string]]
    [:enabled? {:optional true} :boolean]]

   ;; Claude Code SDK
   :seon.ai.claude/sdk
   [:map
    [:cli-path :string]]

   ;; Datahike flow (Phase 2 of datahike-migration)
   :seon.db/flow
   [:map
    [:namespaces [:sequential :keyword]]
    [:backend :keyword]
    [:data-root {:optional true} :string]
    [:namespace-schemas {:optional true} [:map-of :keyword :any]]]})

(defn validate
  "Validate a component's config against its registered schema.
   Returns nil if valid, or a humanized error map."
  [component-key config]
  (when-let [schema (get schemas component-key)]
    (when-not (m/validate schema config)
      (me/humanize (m/explain schema config)))))

(defn describe
  "Describe what a component accepts. For agent introspection."
  [component-key]
  (when-let [schema (get schemas component-key)]
    {:key component-key
     :schema schema
     :schema-form (m/form schema)}))
