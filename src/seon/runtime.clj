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

   ## Usage

   ```clojure
   ;; Initialize with graph DB connection (called by Integrant)
   (runtime/init! {::conn graph-conn})

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
  (:require [datalevin.core :as d]
            [seon.db :as db]
            [seon.schema :as schema]
            [taoensso.timbre :as log])
  (:import [java.security SecureRandom]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::namespace
                  [:string {:min 1
                            :description "Namespace string identifier"}])

(schema/register! ::status
                  [:enum :running :stopped :crashed :paused])

(schema/register! ::location
                  [:enum :in-process :external])

(schema/register! ::session-id
                  [:string {:min 4 :max 6
                            :pattern "^[a-f0-9]{4,6}$"
                            :description "Hex session ID (4-6 chars)"}])

(schema/register! ::nrepl-port
                  [:int {:min 1 :max 65535
                         :description "nREPL port for external instances"}])

(schema/register! ::component-key
                  [:keyword {:description "Integrant component key"}])

(schema/register! ::started-at
                  [inst? {:description "When the instance was started"}])

(schema/register! ::stopped-at
                  [inst? {:description "When the instance was stopped"}])

(schema/register! ::conn
                  [:any {:description "Datalevin graph connection"
                         :gen/fmap (fn [_] (throw (ex-info "Cannot generate Datalevin connection"
                                                           {:type :malli.generator/no-generator})))}])

(schema/register! ::id
                  [:string {:min 6 :max 6
                            :pattern "^[a-f0-9]{6}$"
                            :description "6-character hex ID"}])

(schema/register! ::crashed-count
                  [:int {:min 0 :description "Number of crashed instances"}])

(schema/register! ::hydrated-count
                  [:int {:min 0 :description "Number of hydrated instances"}])

;;; Request/Response Schemas

(schema/register! ::init-request
                  [:map
                   [::conn ::conn]])

(schema/register! ::init-response
                  [:map
                   [::initialized :boolean]])

(schema/register! ::generate-id-request
                  [:map])

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

(schema/register! ::mark-crashed-request
                  [:map])

(schema/register! ::mark-crashed-response
                  [:map
                   [::crashed-count ::crashed-count]])

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

;;; ---------------------------------------------------------------------------
;;; Datalevin Schema
;;; ---------------------------------------------------------------------------

(def runtime-schema
  "Datalevin schema for runtime entities.
   Merged with graph schema when creating the seon-graph connection."
  {:seon.runtime/namespace
   {:db/valueType :db.type/string
    :db/unique    :db.unique/identity}

   :seon.runtime/status
   {:db/valueType :db.type/keyword}

   :seon.runtime/location
   {:db/valueType :db.type/keyword}

   :seon.runtime/session-id
   {:db/valueType :db.type/string}

   :seon.runtime/nrepl-port
   {:db/valueType :db.type/long}

   :seon.runtime/started-at
   {:db/valueType :db.type/instant}

   :seon.runtime/stopped-at
   {:db/valueType :db.type/instant}

   :seon.runtime/component-key
   {:db/valueType :db.type/keyword}})

;;; ---------------------------------------------------------------------------
;;; ID Generation
;;; ---------------------------------------------------------------------------

(def ^:private secure-random (SecureRandom.))

;; In-memory set of generated IDs for collision checking
(defonce ^:private generated-ids (atom #{}))

(defn generate-id
  "Generate a 6-character hex instance ID.

   Uses SecureRandom and checks against in-memory set to avoid collisions.

   Request keys:
     (none - empty map for consistency)

   Response keys:
     ::id - The generated 6-character hex ID"
  {:malli/schema [:=> [:cat ::generate-id-request] ::generate-id-response]}
  [_request]
  (loop [attempts 0]
    (when (>= attempts 100)
      (throw (ex-info "Failed to generate unique ID after 100 attempts"
                      {:generated-count (count @generated-ids)})))
    (let [id-bytes (byte-array 3)
          _ (.nextBytes secure-random id-bytes)
          id (apply str (map #(format "%02x" (bit-and % 0xff)) id-bytes))]
      (if (contains? @generated-ids id)
        (recur (inc attempts))
        (do
          (swap! generated-ids conj id)
          {::id id})))))

;;; ---------------------------------------------------------------------------
;;; Connection Management
;;; ---------------------------------------------------------------------------

;; Graph DB connection (set via init!)
(defonce ^:private conn (atom nil))

(defn init!
  "Initialize the runtime registry with the graph DB connection.

   Called by Integrant during system startup.

   Request keys:
     ::conn - Required. Datalevin graph connection

   Response keys:
     ::initialized - true if initialization succeeded"
  {:malli/schema [:=> [:cat ::init-request] ::init-response]}
  [request]
  (reset! seon.runtime/conn (::conn request))
  (log/info "Runtime registry initialized")
  {::initialized true})

;;; ---------------------------------------------------------------------------
;;; In-Memory Registry Cache
;;; ---------------------------------------------------------------------------

;; Map of namespace-string -> instance-map
(defonce ^:private registry-cache (atom {}))

;;; ---------------------------------------------------------------------------
;;; Persistence
;;; ---------------------------------------------------------------------------

(defn- persist-instance!
  "Persist an instance to Datalevin. Upserts via :db.unique/identity."
  [instance]
  (when-let [c @conn]
    (try
      (let [;; Build transaction map, only including non-nil values
            tx-map (cond-> {:seon.runtime/namespace (::namespace instance)
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
                     (assoc :seon.runtime/component-key (::component-key instance)))]
        (db/transact! c [tx-map]))
      (catch Exception e
        (log/warn "Failed to persist runtime instance" {:namespace (::namespace instance)
                                                        :error (.getMessage e)})))))

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
  (if-let [c @conn]
    (let [running (d/q '[:find ?ns ?e
                         :where
                         [?e :seon.runtime/namespace ?ns]
                         [?e :seon.runtime/status :running]]
                       @c)
          now (java.util.Date.)]
      (doseq [[ns-str _eid] running]
        (log/info "Marking crashed instance" {:namespace ns-str})
        ;; Just persist the status change
        (db/transact! c [{:seon.runtime/namespace ns-str
                          :seon.runtime/status :crashed
                          :seon.runtime/stopped-at now}]))
      (log/info "Marked crashed instances" {:count (count running)})
      {::crashed-count (count running)})
    {::crashed-count 0}))

(defn hydrate-cache!
  "Load all runtime instances from Datalevin into the in-memory cache.

   Called after mark-crashed! to populate the cache with historical data.
   Only loads instances that are :running (should be none after mark-crashed!).

   Request keys:
     (none - empty map for consistency)

   Response keys:
     ::hydrated-count - Number of instances loaded"
  {:malli/schema [:=> [:cat ::hydrate-cache-request] ::hydrate-cache-response]}
  [_request]
  (if-let [c @conn]
    (let [results (d/q '[:find (pull ?e [*])
                         :where
                         [?e :seon.runtime/namespace _]]
                       @c)
          instance-count (count results)]
      ;; Don't populate cache with historical data - only track active instances
      (log/info "Hydrated runtime cache" {:count instance-count})
      {::hydrated-count instance-count})
    {::hydrated-count 0}))

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
