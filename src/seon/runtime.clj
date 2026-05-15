(ns seon.runtime
  "Unified runtime registry for all namespace instances.

   Every running namespace -- whether an Integrant component, agent session,
   or external JVM -- registers here. This provides a single place to answer:
   - What's running?
   - What was running before a crash?
   - Where is a namespace instance located?

   ## Core Concepts

   A namespace instance is the runtime representation of a Clojure namespace:
   - Identity: namespace string (e.g. \"seon.trading.signals\")
   - Status: :running, :stopped, :crashed, :paused
   - Location: :in-process or :external
   - Optional: session-id, nrepl-port, component-key

   ## Connection

   All database access goes through the seon.db API using the :seon.runtime
   db-name keyword. No direct Datalevin access.

   ## Usage

   ```clojure
   ;; Mark crashed instances from previous run
   (runtime/mark-crashed! {})

   ;; Register a running instance
   (runtime/register! {::namespace \"seon.web.server\"
                       ::status :running
                       ::location :in-process
                       ::component-key :seon.web.server/http-server})

   ;; Query instances
   (runtime/instance {::namespace \"seon.web.server\"})
   (runtime/instances {})

   ;; Unregister (sets status to :stopped)
   (runtime/unregister! {::namespace \"seon.web.server\"})
   ```"
  (:require [clojure.core.async.flow :as flow]
            [seon.db :as db]
            [seon.db.schema :as db-schema]
            [seon.schema :as schema]
            [taoensso.timbre :as log])
  (:import [java.security SecureRandom]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::namespace
                  [:string {:min 1
                            :seon.db/identity true
                            :description "Namespace string identifier"}])

(schema/register! ::status
                  [:enum :running :stopped :crashed :paused])

(schema/register! ::location
                  [:enum :in-process :external])

(schema/register! ::session-id
                  [:string {:min 4 :max 6
                            :pattern "^[A-Za-z0-9]{4,6}$"
                            :description "Base62 session ID (4-6 chars)"}])

(schema/register! ::nrepl-port
                  [:int {:min 1 :max 65535
                         :description "nREPL port for external instances"}])

(schema/register! ::component-key
                  [:keyword {:description "Integrant component key"}])

(schema/register! ::started-at
                  [:inst {:description "When the instance was started"}])

(schema/register! ::stopped-at
                  [:inst {:description "When the instance was stopped"}])

(schema/register! ::prefix
                  [:string {:min 1 :max 10
                            :description "Optional prefix for generated IDs (e.g. \"ses\", \"msg\")"}])

(schema/register! ::id
                  [:string {:min 6
                            :description "Base62 ID, optionally prefixed (e.g. \"a1Bx9z\" or \"ses-a1Bx9z\")"}])

(schema/register! ::crashed-count
                  [:int {:min 0 :description "Number of crashed instances"}])

(schema/register! ::hydrated-count
                  [:int {:min 0 :description "Number of hydrated instances"}])

(schema/register! ::cleaned-count
                  [:int {:min 0 :description "Number of stale entities cleaned up"}])

;;; Request/Response Schemas

(schema/register! ::generate-id-request
                  [:map
                   [::prefix {:optional true} ::prefix]])

(schema/register! ::generate-id-response
                  [:map
                   [::id ::id]])

(schema/register! ::register-request
                  [:map
                   [::namespace ::namespace]
                   [::status ::status]
                   [::location ::location]
                   [::session-id {:optional true} ::session-id]
                   [::nrepl-port {:optional true} ::nrepl-port]
                   [::component-key {:optional true} ::component-key]
                   [::started-at {:optional true} ::started-at]])

(schema/register! ::instance-response
                  [:map
                   [::namespace ::namespace]
                   [::status ::status]
                   [::location ::location]
                   [::session-id {:optional true} ::session-id]
                   [::nrepl-port {:optional true} ::nrepl-port]
                   [::component-key {:optional true} ::component-key]
                   [::started-at {:optional true} ::started-at]
                   [::stopped-at {:optional true} ::stopped-at]])

(schema/register! ::unregister-request
                  [:map
                   [::namespace ::namespace]])

(schema/register! ::unregister-response
                  [:maybe ::instance-response])

(schema/register! ::instance-request
                  [:map
                   [::namespace ::namespace]])

(schema/register! ::instances-request
                  [:map])

(schema/register! ::instances-response
                  [:vector ::instance-response])

(schema/register! ::running-sessions-request
                  [:map])

(schema/register! ::running-sessions-response
                  [:vector ::instance-response])

(schema/register! ::mark-crashed-request
                  [:map])

(schema/register! ::mark-crashed-response
                  [:map
                   [::crashed-count ::crashed-count]])

(schema/register! ::cleanup-stale-request
                  [:map])

(schema/register! ::cleanup-stale-response
                  [:map
                   [::cleaned-count ::cleaned-count]])

(schema/register! ::hydrate-cache-request
                  [:map])

(schema/register! ::hydrate-cache-response
                  [:map
                   [::hydrated-count ::hydrated-count]])

(schema/register! ::reset-registry-request
                  [:map])

(schema/register! ::reset-registry-response
                  [:map
                   [::reset :boolean]])

;;; Attribute Schema Registration (must come before entity schemas)

;; Agent run entity attributes — registered so db/transact! enforcement passes
(schema/register! :seon.agent.run/id
                  [:string {:min 1 :seon.db/identity true :description "Agent run identifier"}])
(schema/register! :seon.agent.run/runtime
                  :seon.db/ref)
(schema/register! :seon.agent.run/provider
                  [:keyword {:description "AI provider keyword"}])
(schema/register! :seon.agent.run/status
                  [:keyword {:description "Run status keyword"}])
(schema/register! :seon.agent.run/started-at
                  :inst)
(schema/register! :seon.agent.run/stopped-at
                  :inst)
(schema/register! :seon.agent.run/cost-usd
                  [:double {:min 0.0}])
(schema/register! :seon.agent.run/num-turns
                  [:int {:min 0}])
(schema/register! :seon.agent.run/duration-ms
                  [:int {:min 0}])
(schema/register! :seon.agent.run/namespace
                  [:string {:min 1}])

;; Flow snapshot entity attributes — registered so db/transact! enforcement passes
(schema/register! :seon.flow.snap/id
                  [:string {:min 1 :seon.db/identity true :description "Unique snapshot ID: label/instant"}])
(schema/register! :seon.flow.snap/label
                  [:string {:min 1 :description "Flow label this snapshot belongs to"}])
(schema/register! :seon.flow.snap/created-at
                  :inst)
(schema/register! :seon.flow.snap/reason
                  [:enum :shutdown :backup :manual :error])
(schema/register! :seon.flow.snap/data
                  [:string {:description "pr-str of flow state map"}])

;;; ---------------------------------------------------------------------------
;;; Datalevin Entity Schemas (Malli is the source of truth)
;;; ---------------------------------------------------------------------------

(def runtime-entity-schema
  "Malli schema for a runtime namespace instance entity.
   Defines the shape of what gets stored in Datalevin.
   All persisted attrs have concrete types -- no :any, no [:maybe X].
   Optional fields are conditionally added via cond-> in build-tx-map."
  [:map
   [:seon.runtime/namespace ::namespace]
   [:seon.runtime/status [:enum :running :stopped :crashed :paused]]
   [:seon.runtime/location [:enum :in-process :external]]
   [:seon.runtime/session-id {:optional true} :string]
   [:seon.runtime/nrepl-port {:optional true} :int]
   [:seon.runtime/started-at {:optional true} :inst]
   [:seon.runtime/stopped-at {:optional true} :inst]
   [:seon.runtime/component-key {:optional true} :keyword]])

(def agent-run-entity-schema
  "Malli schema for an agent run entity.
   Created by start-agent-run!, updated by complete-agent-run!.
   Most fields are optional because they're added across the lifecycle:
   start creates id/status/namespace/provider/started-at,
   complete adds stopped-at/cost-usd/num-turns/duration-ms."
  [:map
   [:seon.agent.run/id :seon.agent.run/id]
   [:seon.agent.run/runtime {:optional true} :seon.db/ref]
   [:seon.agent.run/provider {:optional true} :keyword]
   [:seon.agent.run/status :keyword]
   [:seon.agent.run/started-at {:optional true} :inst]
   [:seon.agent.run/stopped-at {:optional true} :inst]
   [:seon.agent.run/cost-usd {:optional true} :double]
   [:seon.agent.run/num-turns {:optional true} :int]
   [:seon.agent.run/duration-ms {:optional true} :int]
   [:seon.agent.run/namespace {:optional true} :string]])

(def flow-snap-entity-schema
  "Malli schema for a flow snapshot entity.
   All fields are required -- snapshot-topology! always provides all of them."
  [:map
   [:seon.flow.snap/id :seon.flow.snap/id]
   [:seon.flow.snap/label :string]
   [:seon.flow.snap/created-at :inst]
   [:seon.flow.snap/reason [:enum :shutdown :backup :manual :error]]
   [:seon.flow.snap/data :string]])

(db-schema/register-entity-schema! "seon.runtime" runtime-entity-schema)
(db-schema/register-entity-schema! "seon.agent.run" agent-run-entity-schema)
(db-schema/register-entity-schema! "seon.flow.snap" flow-snap-entity-schema)

(def runtime-schema
  "Datalevin schema for all runtime entities. Derived from Malli.
   Merged with graph/ctx/trace schemas when creating the seon.runtime connection."
  (merge (db-schema/malli-map->datalevin-schema runtime-entity-schema)
         (db-schema/malli-map->datalevin-schema agent-run-entity-schema)
         (db-schema/malli-map->datalevin-schema flow-snap-entity-schema)))

;; runtime-merged-schema + merged-schema-cache deleted (chunk M-1, 2026-05-15).
;; The aggregator was only consumed by :seon/runtime-db (Integrant key absent
;; from system.edn — defmethods deleted), seon.render/get-conn (deleted), and
;; seon.ns.routes/get-conn (deleted). Datahike-flow namespaces install schema
;; from the Malli registry; no manual schema merge needed.

;;; ---------------------------------------------------------------------------
;;; Session ID Validation
;;; ---------------------------------------------------------------------------

(def session-id-pattern
  "Regex pattern for base62 session IDs (4-6 alphanumeric chars)."
  #"[A-Za-z0-9]{4,6}")

(defn session-id?
  "Returns true if s is a valid base62 session ID (4-6 alphanumeric chars)."
  [s]
  (and (string? s)
       (boolean (re-matches session-id-pattern s))))

;;; ---------------------------------------------------------------------------
;;; ID Generation
;;; ---------------------------------------------------------------------------

(def ^:private secure-random (SecureRandom.))

(def ^:private base62-chars
  "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789")

;; In-memory set of generated IDs for collision checking
(defonce ^:private generated-ids (atom #{}))

(defn generate-id
  "Generate a 6-character base62 instance ID, optionally prefixed.

   Uses SecureRandom and checks against in-memory set to avoid collisions.
   Base62 charset: A-Z, a-z, 0-9. 6 chars = ~56 billion possible IDs.

   Request keys:
     ::prefix - Optional. String prefix separated by \"-\" (e.g. \"ses\", \"msg\")

   Response keys:
     ::id - The generated ID (e.g. \"a1Bx9z\" or \"ses-a1Bx9z\")"
  {:malli/schema [:=> [:cat ::generate-id-request] ::generate-id-response]}
  [{::keys [prefix]}]
  (loop [attempts 0]
    (when (>= attempts 100)
      (throw (ex-info "Failed to generate unique ID after 100 attempts"
                      {:generated-count (count @generated-ids)})))
    (let [sb (StringBuilder. 6)
          _ (dotimes [_ 6]
              (.append sb (.charAt base62-chars (.nextInt secure-random 62))))
          raw-id (str sb)
          full-id (if prefix (str prefix "-" raw-id) raw-id)]
      (if (contains? @generated-ids raw-id)
        (recur (inc attempts))
        (do
          (swap! generated-ids conj raw-id)
          {::id full-id})))))

;;; ---------------------------------------------------------------------------
;;; In-Memory Registry Cache
;;; ---------------------------------------------------------------------------

;; Map of namespace-string -> instance-map
(defonce ^:private registry-cache (atom {}))

;;; ---------------------------------------------------------------------------
;;; Persistence
;;; ---------------------------------------------------------------------------

(defn- build-tx-map
  "Build a Datalevin transaction map from an instance map."
  [instance]
  (cond-> {:seon.runtime/namespace (::namespace instance)
           :seon.runtime/status (::status instance)
           :seon.runtime/location (::location instance)}
    (::session-id instance)
    (assoc :seon.runtime/session-id (::session-id instance))

    (::nrepl-port instance)
    (assoc :seon.runtime/nrepl-port (long (::nrepl-port instance)))

    (::started-at instance)
    (assoc :seon.runtime/started-at (::started-at instance))

    (::stopped-at instance)
    (assoc :seon.runtime/stopped-at (::stopped-at instance))

    (::component-key instance)
    (assoc :seon.runtime/component-key (::component-key instance))))

(defn- persist-instance!
  "Persist an instance to Datalevin. Synchronous in direct mode (tests),
   async (future) in production. Upserts via :db.unique/identity."
  [instance]
  (let [do-persist (fn []
                     (try
                       (db/transact! :seon.runtime [(build-tx-map instance)])
                       (catch Exception e
                         (log/warn "Failed to persist runtime instance"
                                   {:namespace (::namespace instance)
                                    :error (.getMessage e)}))))]
    (if db/*direct-mode*
      (do-persist)
      (future (do-persist)))))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn register!
  "Register a namespace instance in the runtime registry.

   Persists to Datalevin (upsert via namespace identity) and updates the
   in-memory cache.

   Request keys:
     ::namespace     - Required. Namespace string (e.g. \"seon.web.server\")
     ::status        - Required. :running, :stopped, :crashed, or :paused
     ::location      - Required. :in-process or :external
     ::session-id    - Optional. Hex session ID (external instances)
     ::nrepl-port    - Optional. nREPL port (external instances)
     ::component-key - Optional. Integrant component key (in-process)
     ::started-at    - Optional. Start timestamp (default: now)

   Response keys:
     ::namespace, ::status, ::location, etc. - The registered instance"
  {:malli/schema [:=> [:cat ::register-request] ::instance-response]}
  [{::keys [status location session-id nrepl-port component-key started-at]
    :as request}]
  (let [ns-str (::namespace request)
        now (java.util.Date.)
        inst-map (cond-> {::namespace ns-str
                          ::status status
                          ::location location
                          ::started-at (or started-at now)}
                   session-id (assoc ::session-id session-id)
                   nrepl-port (assoc ::nrepl-port nrepl-port)
                   component-key (assoc ::component-key component-key))]
    ;; Update in-memory cache
    (swap! registry-cache assoc ns-str inst-map)
    ;; Persist to Datalevin
    (persist-instance! inst-map)
    (log/debug "Registered runtime instance" {:namespace ns-str
                                               :status status
                                               :location location})
    inst-map))

(defn unregister!
  "Unregister a namespace instance.

   Updates status to :stopped with stopped-at timestamp in both
   in-memory cache and Datalevin.

   Request keys:
     ::namespace - Required. Namespace string to unregister

   Response keys:
     The updated instance map, or nil if not found."
  {:malli/schema [:=> [:cat ::unregister-request] ::unregister-response]}
  [request]
  (let [ns-str (::namespace request)]
    (if-let [inst-map (get @registry-cache ns-str)]
      (let [now (java.util.Date.)
            updated (assoc inst-map
                           ::status :stopped
                           ::stopped-at now)]
        ;; Update in-memory cache
        (swap! registry-cache assoc ns-str updated)
        ;; Persist to Datalevin
        (persist-instance! updated)
        (log/debug "Unregistered runtime instance" {:namespace ns-str})
        updated)
      (do
        (log/warn "Cannot unregister - instance not found" {:namespace ns-str})
        nil))))

(defn instance
  "Get a single instance by namespace string.

   Reads from in-memory cache first.

   Request keys:
     ::namespace - Required. Namespace string to look up

   Response keys:
     The instance map, or nil if not found."
  {:malli/schema [:=> [:cat ::instance-request] [:maybe ::instance-response]]}
  [request]
  (get @registry-cache (::namespace request)))

(defn instances
  "List all registered instances.

   Request keys:
     (none - empty map for consistency)

   Response keys:
     Vector of instance maps from in-memory cache."
  {:malli/schema [:=> [:cat ::instances-request] ::instances-response]}
  [_request]
  (vec (vals @registry-cache)))

(defn running-sessions
  "All external running instances from the in-memory cache.

   Useful for finding active agent sessions (location :external, status :running).

   Request keys:
     (none - empty map for consistency)

   Response keys:
     Vector of instance maps matching external + running."
  {:malli/schema [:=> [:cat ::running-sessions-request] ::running-sessions-response]}
  [_request]
  (->> (vals @registry-cache)
       (filter #(and (= :external (::location %))
                     (= :running (::status %))))
       vec))

(defn mark-crashed!
  "Mark all :running instances as :crashed.

   Called on startup to detect instances from a previous unclean shutdown.
   Reads from Datalevin to find previously-running instances and updates
   their status.

   Request keys:
     (none - empty map for consistency)

   Response keys:
     ::crashed-count - Number of instances marked as crashed"
  {:malli/schema [:=> [:cat ::mark-crashed-request] ::mark-crashed-response]}
  [_request]
  (try
    (let [running (db/query :seon.runtime
                            '[:find ?ns ?e
                              :where
                              [?e :seon.runtime/namespace ?ns]
                              [?e :seon.runtime/status :running]])
          now (java.util.Date.)]
      (doseq [[ns-str _eid] running]
        (log/info "Marking crashed instance" {:namespace ns-str})
        (db/transact! :seon.runtime [{:seon.runtime/namespace ns-str
                                      :seon.runtime/status :crashed
                                      :seon.runtime/stopped-at now}]))
      (log/info "Marked crashed instances" {:count (count running)})
      {::crashed-count (count running)})
    (catch Exception e
      (log/warn "Failed to mark crashed instances" {:error (.getMessage e)})
      {::crashed-count 0})))

(defn- datalevin->cache
  "Convert a Datalevin entity map (`:seon.runtime/*` keys) to the
   in-memory cache format (`::runtime/*` keys). Drops :db/id and any
   keys not part of the runtime schema."
  [entity]
  (let [key-map {:seon.runtime/namespace  ::namespace
                 :seon.runtime/status     ::status
                 :seon.runtime/location   ::location
                 :seon.runtime/session-id ::session-id
                 :seon.runtime/nrepl-port ::nrepl-port
                 :seon.runtime/started-at ::started-at
                 :seon.runtime/stopped-at ::stopped-at
                 :seon.runtime/component-key ::component-key}]
    (reduce-kv (fn [m k v]
                 (if-let [cache-key (key-map k)]
                   (assoc m cache-key v)
                   m))
               {}
               entity)))

(defn- stale-entity?
  "Returns true if a runtime entity is missing required fields.
   Stale entities are legacy data from before :seon.db/identity was added."
  [entity]
  (not (and (:seon.runtime/namespace entity)
            (:seon.runtime/status entity)
            (:seon.runtime/location entity))))

(defn cleanup-stale!
  "Retract runtime entities missing required fields (legacy data from before
   :seon.db/identity was added). Called during hydration to prevent stale
   entities from polluting the cache and failing schema validation.

   Request keys:
     (none - empty map for consistency)

   Response keys:
     ::cleaned-count - Number of stale entities retracted"
  {:malli/schema [:=> [:cat ::cleanup-stale-request] ::cleanup-stale-response]}
  [_request]
  (try
    (let [all-entities (db/query :seon.runtime
                                 '[:find ?e (pull ?e [*])
                                   :where [?e :seon.runtime/namespace _]])
          stale (filter (fn [[_eid entity]] (stale-entity? entity)) all-entities)
          retract-tx (mapv (fn [[eid _]] [:db/retractEntity eid]) stale)]
      (when (seq retract-tx)
        (db/transact! :seon.runtime retract-tx)
        (log/info "Cleaned up stale runtime entities" {:count (count retract-tx)}))
      {::cleaned-count (count retract-tx)})
    (catch Exception e
      (log/warn "Failed to clean up stale runtime entities" {:error (.getMessage e)})
      {::cleaned-count 0})))

(defn hydrate-cache!
  "Load all runtime instances from Datalevin into the in-memory cache.

   First cleans up stale entities missing required fields, then populates
   the cache. Called after mark-crashed! to reflect persisted state (e.g.
   crashed instances from a previous run).

   Request keys:
     (none - empty map for consistency)

   Response keys:
     ::hydrated-count - Number of instances loaded"
  {:malli/schema [:=> [:cat ::hydrate-cache-request] ::hydrate-cache-response]}
  [_request]
  (try
    ;; Clean up legacy entities missing required fields
    (cleanup-stale! {})
    (let [results (db/query :seon.runtime
                            '[:find (pull ?e [*])
                              :where
                              [?e :seon.runtime/namespace _]])
          all-entities (map first results)
          ;; Defensive filter: skip any entity still missing required fields
          ;; (e.g. if cleanup-stale! failed or a race produced new stale data)
          valid-entities (filter (complement stale-entity?) all-entities)
          skipped (- (count all-entities) (count valid-entities))
          cache-map (reduce (fn [m entity]
                              (let [inst (datalevin->cache entity)]
                                (assoc m (::namespace inst) inst)))
                            {}
                            valid-entities)]
      (when (pos? skipped)
        (log/warn "Skipped stale runtime entities missing required fields"
                  {:skipped skipped}))
      (reset! registry-cache cache-map)
      (log/info "Hydrated runtime cache" {:count (count cache-map)})
      {::hydrated-count (count cache-map)})
    (catch Exception e
      (log/warn "Failed to hydrate runtime cache" {:error (.getMessage e)})
      {::hydrated-count 0})))

;;; ---------------------------------------------------------------------------
;;; Agent Run Schemas
;;; ---------------------------------------------------------------------------

(schema/register! ::agent-run-id
                  [:string {:min 1 :description "Agent run identifier (session ID)"}])

(schema/register! ::provider
                  [:keyword {:description "AI provider (e.g. :claude)"}])

(schema/register! ::cost-usd
                  [:double {:min 0.0 :description "Total cost in USD"}])

(schema/register! ::num-turns
                  [:int {:min 0 :description "Number of conversation turns"}])

(schema/register! ::duration-ms
                  [:int {:min 0 :description "Duration in milliseconds"}])

(schema/register! ::start-agent-run-request
                  [:map
                   [::agent-run-id ::agent-run-id]
                   [::namespace ::namespace]
                   [::provider ::provider]])

(schema/register! ::start-agent-run-response
                  [:map
                   [::agent-run-id ::agent-run-id]
                   [::status ::status]])

(schema/register! ::complete-agent-run-request
                  [:map
                   [::agent-run-id ::agent-run-id]
                   [::status [:enum :completed :failed :interrupted :terminated]]
                   [::cost-usd {:optional true} ::cost-usd]
                   [::num-turns {:optional true} ::num-turns]
                   [::duration-ms {:optional true} ::duration-ms]])

(schema/register! ::complete-agent-run-response
                  [:map
                   [::agent-run-id ::agent-run-id]
                   [::status [:enum :completed :failed :interrupted :terminated]]])

(schema/register! ::agent-runs-request
                  [:map
                   [::namespace {:optional true} ::namespace]])

(schema/register! ::agent-run-entity
                  [:map
                   [:seon.agent.run/id :string]
                   [:seon.agent.run/status :keyword]
                   [:seon.agent.run/namespace {:optional true} :string]
                   [:seon.agent.run/provider {:optional true} :keyword]
                   [:seon.agent.run/started-at {:optional true} :inst]
                   [:seon.agent.run/stopped-at {:optional true} :inst]
                   [:seon.agent.run/cost-usd {:optional true} :double]
                   [:seon.agent.run/num-turns {:optional true} :int]
                   [:seon.agent.run/duration-ms {:optional true} :int]])

(schema/register! ::agent-runs-response
                  [:vector ::agent-run-entity])

;;; ---------------------------------------------------------------------------
;;; Agent Run API
;;; ---------------------------------------------------------------------------

(defn start-agent-run!
  "Record the start of an agent run in Datalevin.

   Creates a :seon.agent.run/* entity with status :running, linked to the
   runtime instance via ref if it exists.

   Request keys:
     ::agent-run-id - Required. Run identifier (typically the session ID)
     ::namespace    - Required. Agent namespace string
     ::provider     - Required. AI provider keyword (e.g. :claude)

   Response keys:
     ::agent-run-id - The run ID
     ::status       - :running"
  {:malli/schema [:=> [:cat ::start-agent-run-request] ::start-agent-run-response]}
  [{::keys [agent-run-id namespace provider]}]
  (try
    (let [now (java.util.Date.)
          ;; Look up runtime instance entity for ref linkage
          runtime-eid (ffirst
                       (db/query :seon.runtime
                                 '[:find ?e
                                   :in $ ?ns
                                   :where [?e :seon.runtime/namespace ?ns]]
                                 namespace))
          tx-map (cond-> {:seon.agent.run/id agent-run-id
                          :seon.agent.run/namespace namespace
                          :seon.agent.run/provider provider
                          :seon.agent.run/status :running
                          :seon.agent.run/started-at now}
                   runtime-eid (assoc :seon.agent.run/runtime runtime-eid))]
      (db/transact! :seon.runtime [tx-map]))
    (catch Exception e
      (log/warn "Failed to start agent run" {:agent-run-id agent-run-id
                                              :error (.getMessage e)})))
  {::agent-run-id agent-run-id
   ::status :running})

(defn complete-agent-run!
  "Record the completion of an agent run in Datalevin.

   Updates the :seon.agent.run/* entity with final status and stats.

   Request keys:
     ::agent-run-id - Required. Run identifier
     ::status       - Required. Final status (:completed, :failed, :interrupted, :terminated)
     ::cost-usd     - Optional. Total cost in USD
     ::num-turns    - Optional. Number of conversation turns
     ::duration-ms  - Optional. Duration in milliseconds

   Response keys:
     ::agent-run-id - The run ID
     ::status       - The final status"
  {:malli/schema [:=> [:cat ::complete-agent-run-request] ::complete-agent-run-response]}
  [{::keys [agent-run-id status cost-usd num-turns duration-ms]}]
  (try
    (let [now (java.util.Date.)
          tx-map (cond-> {:seon.agent.run/id agent-run-id
                          :seon.agent.run/status status
                          :seon.agent.run/stopped-at now}
                   cost-usd (assoc :seon.agent.run/cost-usd (double cost-usd))
                   num-turns (assoc :seon.agent.run/num-turns (long num-turns))
                   duration-ms (assoc :seon.agent.run/duration-ms (long duration-ms)))]
      (db/transact! :seon.runtime [tx-map]))
    (catch Exception e
      (log/warn "Failed to complete agent run" {:agent-run-id agent-run-id
                                                 :error (.getMessage e)})))
  {::agent-run-id agent-run-id
   ::status status})

(defn agent-runs
  "Query all agent runs from Datalevin.

   Optionally filtered by namespace.

   Request keys:
     ::namespace - Optional. Filter by namespace string

   Response keys:
     Vector of agent run entity maps."
  {:malli/schema [:=> [:cat ::agent-runs-request] ::agent-runs-response]}
  [{::keys [namespace]}]
  (try
    (let [results (if namespace
                    (db/query :seon.runtime
                              '[:find (pull ?e [:seon.agent.run/id
                                                :seon.agent.run/namespace
                                                :seon.agent.run/provider
                                                :seon.agent.run/status
                                                :seon.agent.run/started-at
                                                :seon.agent.run/stopped-at
                                                :seon.agent.run/cost-usd
                                                :seon.agent.run/num-turns
                                                :seon.agent.run/duration-ms])
                                :in $ ?ns
                                :where
                                [?e :seon.agent.run/id _]
                                [?e :seon.agent.run/namespace ?ns]]
                              namespace)
                    (db/query :seon.runtime
                              '[:find (pull ?e [:seon.agent.run/id
                                                :seon.agent.run/namespace
                                                :seon.agent.run/provider
                                                :seon.agent.run/status
                                                :seon.agent.run/started-at
                                                :seon.agent.run/stopped-at
                                                :seon.agent.run/cost-usd
                                                :seon.agent.run/num-turns
                                                :seon.agent.run/duration-ms])
                                :where [?e :seon.agent.run/id _]]))]
      (mapv first results))
    (catch Exception e
      (log/warn "Failed to query agent runs" {:error (.getMessage e)})
      [])))

;;; ---------------------------------------------------------------------------
;;; Flow Snapshot API
;;; ---------------------------------------------------------------------------

(schema/register! ::flow
                  [:any {:description "A core.async.flow object"}])

(schema/register! ::label
                  [:string {:min 1 :description "Flow label string"}])

(schema/register! ::reason
                  [:enum :shutdown :backup :manual :error])

(schema/register! ::snapshot-request
                  [:map
                   [::flow ::flow]
                   [::label ::label]
                   [::reason ::reason]])

(schema/register! ::snapshot-response
                  [:maybe [:map
                           [:seon.flow.snap/id :string]
                           [:seon.flow.snap/label :string]
                           [:seon.flow.snap/reason ::reason]]])

(schema/register! ::latest-snapshot-request
                  [:map
                   [::label ::label]])

(defn snapshot-topology!
  "Capture flow state and persist to Datalevin.
   Call AFTER pausing the flow (caller must pause first).

   Request keys:
     ::flow    - The flow object (already paused)
     ::label   - Flow label string
     ::reason  - :shutdown, :backup, :manual, :error

   Returns snapshot entity map or nil if ping fails or no connection."
  {:malli/schema [:=> [:cat ::snapshot-request] ::snapshot-response]}
  [{::keys [flow label reason]}]
  (try
    (let [states (flow/ping flow :timeout-ms 5000)
          now (java.util.Date.)
          snap-id (str label "/" (.toInstant now))
          data-str (pr-str states)]
      (db/transact! :seon.runtime [{:seon.flow.snap/id snap-id
                                    :seon.flow.snap/label label
                                    :seon.flow.snap/created-at now
                                    :seon.flow.snap/reason reason
                                    :seon.flow.snap/data data-str}])
      {:seon.flow.snap/id snap-id
       :seon.flow.snap/label label
       :seon.flow.snap/reason reason})
    (catch Exception e
      (log/warn "Failed to snapshot topology" {:label label :error (.getMessage e)})
      nil)))

(defn latest-snapshot
  "Get the most recent snapshot for a flow label.

   Request keys:
     ::label - Flow label string

   Returns the snapshot entity map or nil if not found."
  [{::keys [label]}]
  (try
    (let [results (db/query :seon.runtime
                            '[:find (pull ?e [*]) ?t
                              :in $ ?label
                              :where
                              [?e :seon.flow.snap/label ?label]
                              [?e :seon.flow.snap/created-at ?t]]
                            label)]
      (when (seq results)
        (->> results
             (sort-by second #(compare %2 %1))
             first
             first)))
    (catch Exception e
      (log/warn "Failed to get latest snapshot" {:label label :error (.getMessage e)})
      nil)))

;;; ---------------------------------------------------------------------------
;;; Flow Handle Registry (In-Memory Only)
;;; ---------------------------------------------------------------------------

;; Map of flow-id (keyword) -> {:flow flow-obj :chans chans-map :label string}
;; Flow objects are opaque and not serializable, so this is in-memory only.
(defonce ^:private flow-handles (atom {}))

(defn register-flow!
  "Register a flow handle (in-memory only -- flow objects aren't serializable).

   Also registers in the runtime registry for Datalevin persistence.

   Request keys:
     ::flow-id - Keyword identifier for the flow
     ::flow    - The flow object (opaque, from core.async.flow)
     ::chans   - Map with :error-chan and :report-chan
     ::label   - Human-readable label

   Returns the registered handle map."
  [{::keys [flow-id flow chans label]}]
  (let [now (java.time.Instant/now)
        handle {:flow flow :chans chans :label label :started-at now}]
    (swap! flow-handles assoc flow-id handle)
    ;; Also register in runtime registry for Datalevin persistence
    (register! {::namespace (str "flow." (name flow-id))
                ::status :running
                ::location :in-process})
    handle))

(defn unregister-flow!
  "Remove a flow handle.

   Request keys:
     ::flow-id - Flow identifier to remove

   Returns the removed handle, or nil if not found."
  [{::keys [flow-id]}]
  (let [removed (get @flow-handles flow-id)]
    (swap! flow-handles dissoc flow-id)
    (unregister! {::namespace (str "flow." (name flow-id))})
    removed))

(defn get-flow
  "Get a flow handle by ID.

   Request keys:
     ::flow-id - Flow identifier

   Returns the handle map or nil."
  [{::keys [flow-id]}]
  (get @flow-handles flow-id))

(defn list-flows
  "List all registered flow handles.

   Returns map of flow-id -> handle."
  [_request]
  @flow-handles)

(defn clear-flows!
  "Remove all flow handles. For testing only."
  []
  (reset! flow-handles {}))

;;; ---------------------------------------------------------------------------
;;; Lifecycle Hooks
;;; ---------------------------------------------------------------------------

(defn after-ns-reload
  "Called by clj-reload after reloading. Resets generated-ids (negligible collision
   risk for 6-char base62) and re-populates flow-handles from Integrant system."
  []
  (reset! generated-ids #{})
  ;; Re-populate flow-handles from Integrant if system is running
  (try
    (require 'integrant.repl.state)
    (when-let [sys @(resolve 'integrant.repl.state/system)]
      (when-let [{:keys [flow chans]} (:seon.flow/infrastructure sys)]
        (let [flow-id :seon.flow/infrastructure
              handle {:flow flow :chans chans :label "infrastructure"
                      :started-at (java.time.Instant/now)}]
          (swap! flow-handles assoc flow-id handle))))
    (catch Exception e
      (log/debug "Could not re-populate flow-handles from Integrant" {:error (.getMessage e)}))))

;;; ---------------------------------------------------------------------------
;;; Testing Helpers
;;; ---------------------------------------------------------------------------

(defn reset-registry!
  "Reset the in-memory registry and generated IDs. For testing only.

   Request keys:
     (none - empty map for consistency)

   Response keys:
     ::reset - true if reset succeeded"
  {:malli/schema [:=> [:cat ::reset-registry-request] ::reset-registry-response]}
  [_request]
  (reset! registry-cache {})
  (reset! generated-ids #{})
  (reset! flow-handles {})
  {::reset true})

;;; ---------------------------------------------------------------------------
;;; REPL Helpers
;;; ---------------------------------------------------------------------------

(comment
  ;; Check what's running
  (instances {})

  ;; Look up specific instance
  (instance {::namespace "seon.web.server"})

  ;; Generate IDs
  (generate-id {})
  (count @generated-ids)

  nil)
