(ns seon.agent.ctx
  "Persisted context atom for agent isolation.

   Provides a special atom that:
   - Validates all updates (namespaced keys, registered schemas)
   - Persists state with 1s debounce
   - Supports time-travel queries
   - Protects reserved :seon.agent/* keys from modification

   ## Usage

   ```clojure
   ;; Create a persisted context
   (def ctx-result (make-persisted-ctx {::db conn ::namespace 'seon.trading}))
   (def *ctx* (::atom ctx-result))

   ;; Use like a normal atom - persistence happens automatically
   (swap! *ctx* assoc :seon.trading/signals [...])

   ;; Time travel
   (at {::db conn ::namespace 'seon.trading ::instant #inst \"2026-01-04T10:00\"})

   ;; Clean up when done
   ((::close! ctx-result))
   ```

   ## Validation Rules

   1. Value must be a map
   2. ALL keys must be fully namespaced (e.g., :seon.trading/signals)
   3. Each key must have a Malli spec registered in seon.schema/registry
   4. The value for each key must validate against its spec
   5. Reserved :seon.agent/* keys are immutable after creation"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [malli.core :as m]
            [seon.schema :as schema]
            [taoensso.timbre :as log])
  (:import [java.util.concurrent Executors ScheduledFuture TimeUnit]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

;; API request/response schemas (used in function signatures)
(schema/register! ::db
                  [:any {:description "Database connection"
                         :gen/fmap (fn [_] (throw (ex-info "Cannot generate database connection"
                                                           {:type :malli.generator/no-generator})))}])

(schema/register! ::namespace
                  [:symbol {:description "Agent namespace symbol"}])

;; Reserved keys inside ctx atom (prefixed :seon.agent/*)
;; These are set by the system and immutable - agents can read them
(schema/register! :seon.agent/namespace
                  [:symbol {:description "Agent namespace symbol (read-only in ctx)"}])

(schema/register! :seon.agent/db
                  [:any {:description "Database connection (read-only in ctx)"
                         :gen/fmap (fn [_] (throw (ex-info "Cannot generate database connection"
                                                           {:type :malli.generator/no-generator})))}])

;; Datalevin namespace isolation keys (per PRD spec)
;; These provide agents with isolated namespace databases
(schema/register! :seon.ns/conn
                  [:any {:description "Datalevin connection to namespace DB (read-only in ctx)"
                         :gen/fmap (fn [_] (throw (ex-info "Cannot generate Datalevin connection"
                                                           {:type :malli.generator/no-generator})))}])

(schema/register! :seon.ns/session-id
                  [:string {:min 4 :max 4
                            :pattern "^[a-f0-9]{4}$"
                            :description "4-character hex session ID (read-only in ctx)"}])

(schema/register! :seon.ns/namespace
                  [:string {:min 1
                            :description "Agent namespace as string (read-only in ctx)"}])

(schema/register! ::debounce-ms
                  [:int {:min 100 :max 60000
                         :description "Debounce window in milliseconds"}])

(schema/register! ::instant
                  [inst? {:description "Point in time for time-travel"}])

(schema/register! ::atom
                  [:any {:description "The persisted ctx atom"}])

(schema/register! ::state
                  [:map {:description "Ctx state (namespaced keys only)"}])

(schema/register! ::system-time
                  [inst? {:description "System time of the snapshot"}])

(schema/register! ::flush!
                  [:any {:description "Force immediate persist - zero-arg function"}])

(schema/register! ::close!
                  [:any {:description "Cleanup resources - zero-arg function"}])

(schema/register! ::snapshots
                  [:vector [:map
                            [::state ::state]
                            [::system-time ::instant]]])

;;; Request/Response Schemas

(schema/register! ::extra-reserved
                  [:map {:description "Additional reserved keys to inject into ctx"}])

(schema/register! ::make-request
                  [:map
                   [::db ::db]
                   [::namespace ::namespace]
                   [::debounce-ms {:optional true} ::debounce-ms]
                   [::extra-reserved {:optional true} ::extra-reserved]])

(schema/register! ::make-response
                  [:map
                   [::atom ::atom]
                   [::flush! ::flush!]
                   [::close! ::close!]])

(schema/register! ::at-request
                  [:map
                   [::db ::db]
                   [::namespace ::namespace]
                   [::instant ::instant]])

(schema/register! ::at-response
                  [:map
                   [::state ::state]
                   [::system-time ::system-time]])

(schema/register! ::history-request
                  [:map
                   [::db ::db]
                   [::namespace ::namespace]])

(schema/register! ::history-response
                  [:map
                   [::snapshots ::snapshots]])

(schema/register! ::restore-request
                  [:map
                   [::atom ::atom]
                   [::db ::db]
                   [::namespace ::namespace]
                   [::instant ::instant]])

(schema/register! ::restore-response
                  [:map
                   [::state ::state]
                   [::restored-from ::instant]])

(schema/register! ::load-latest-request
                  [:map
                   [::db ::db]
                   [::namespace ::namespace]])

(schema/register! ::load-latest-response
                  [:map
                   [::state [:maybe ::state]]
                   [::system-time [:maybe ::instant]]])

;;; ---------------------------------------------------------------------------
;;; Validation Helpers
;;; ---------------------------------------------------------------------------

(defn- namespaced-key?
  "Check if a keyword is fully namespaced."
  [k]
  (and (keyword? k)
       (some? (namespace k))))

(defn- reserved-key?
  "Check if a key is a reserved :seon.agent/* or :seon.ns/* key."
  [k]
  (and (keyword? k)
       (contains? #{"seon.agent" "seon.ns"} (namespace k))))

(defn- validate-key
  "Validate a single key. Returns nil if valid, error map if invalid."
  [k v reserved-keys]
  (cond
    ;; Check for reserved key modification (compare with original reserved keys)
    (and (reserved-key? k)
         (not (contains? reserved-keys k)))
    {:error :reserved-key-addition
     :key k
     :message (format "Cannot add reserved key %s\nReserved :seon.agent/* keys are set by the system and immutable."
                      k)}

    ;; Reserved keys that exist in original cannot be modified
    (and (reserved-key? k)
         (contains? reserved-keys k)
         (not= v (get reserved-keys k)))
    {:error :reserved-key-modification
     :key k
     :message (format "Cannot modify reserved key %s\nReserved :seon.agent/* keys are set by the system and immutable."
                      k)}

    ;; Skip validation for reserved keys (they're system-managed)
    (reserved-key? k)
    nil

    ;; Check if key is namespaced
    (not (namespaced-key? k))
    {:error :non-namespaced-key
     :key k
     :message (format "Invalid ctx key %s\nAll keys must be fully namespaced (e.g., :seon.trading/signals)\nTo fix: Use (swap! *ctx* assoc :%s/%s [...])"
                      k
                      (or (namespace k) "your.namespace")
                      (name k))}

    ;; Check if schema is registered
    (not (schema/registered? k))
    {:error :missing-spec
     :key k
     :message (format "No spec registered for key %s\nRegister a Malli spec in seon.schema/registry first.\nTo fix: Add (schema/register! %s <schema>) to the schema registry"
                      k k)}

    ;; Validate value against schema
    :else
    (let [spec (schema/schema-definition k)]
      (when-not (m/validate spec v)
        {:error :validation-failure
         :key k
         :expected spec
         :got v
         :message (format "Value for %s failed validation\nExpected: %s\nGot: %s\nTo fix: Provide a value matching the schema"
                          k (pr-str spec) (pr-str v))}))))

(defn- validate-state
  "Validate a complete ctx state. Returns nil if valid, throws ex-info if invalid."
  [new-state reserved-keys]
  (when-not (map? new-state)
    (throw (ex-info "ctx state must be a map"
                    {:error :not-a-map
                     :got (type new-state)})))

  ;; Check for removed reserved keys
  (doseq [k (keys reserved-keys)]
    (when-not (contains? new-state k)
      (throw (ex-info (format "Cannot remove reserved key %s\nReserved :seon.agent/* keys are set by the system and immutable." k)
                      {:error :reserved-key-removal
                       :key k}))))

  ;; Validate each key
  (doseq [[k v] new-state]
    (when-let [error (validate-key k v reserved-keys)]
      (throw (ex-info (:message error) error))))

  nil)

;;; ---------------------------------------------------------------------------
;;; Persistence Helpers
;;; ---------------------------------------------------------------------------

(defn- persistable-state
  "Remove reserved :seon.agent/* keys from state before serialization.
   These keys contain non-EDN-serializable values like db connections."
  [state]
  (into {} (remove (fn [[k _]] (reserved-key? k)) state)))

(defn- persist-snapshot!
  "Persist a ctx state snapshot to storage."
  [db namespace state]
  (try
    (let [_ns-str (str namespace)
          ;; Filter out reserved keys before serialization
          _serializable (persistable-state state)]
      ;; TODO: migrate to Datalevin persistence
      (log/debug "ctx persistence not yet migrated to Datalevin"
                 {:namespace namespace :db (some? db)}))
    (catch Exception e
      (log/error "Failed to persist ctx snapshot"
                 {:namespace namespace
                  :error (.getMessage e)})
      (throw e))))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn make-persisted-ctx
  "Create a persisted context atom for an agent.

   Request keys:
     ::db          - Required. Database connection
     ::namespace   - Required. Agent namespace symbol
     ::debounce-ms - Optional. Debounce window (default: 1000)

   Response keys:
     ::atom   - The persisted ctx atom
     ::flush! - Force immediate persist (for shutdown)
     ::close! - Cleanup resources

   Example:
     (make-persisted-ctx {::db conn ::namespace 'seon.trading})
     ;; From outside namespace:
     (ctx/make-persisted-ctx {::ctx/db conn ::ctx/namespace 'seon.trading})"
  {:malli/schema [:=> [:cat ::make-request] ::make-response]}
  [{::keys [db namespace debounce-ms extra-reserved]}]
  (let [debounce-ms (or debounce-ms 1000)

        ;; Load latest state if exists
        ;; TODO: migrate to Datalevin persistence
        latest nil

        initial-state (if latest
                        (edn/read-string (:state latest))
                        {})

        ;; Reserved keys - set by system, immutable
        ;; Includes :seon.agent/* keys plus any extra-reserved (e.g., :seon.ns/*)
        reserved (merge {:seon.agent/namespace namespace
                         :seon.agent/db db}
                        extra-reserved)

        ;; Merge reserved keys into initial state
        initial-with-reserved (merge initial-state reserved)

        ;; Track reserved keys for validation
        reserved-keys (atom reserved)

        ;; Create the atom with validator
        ctx-atom (atom initial-with-reserved
                       :validator (fn [new-state]
                                    ;; Skip validation during restore (metadata flag)
                                    (if (::restoring (meta new-state))
                                      true
                                      (do
                                        (validate-state new-state @reserved-keys)
                                        true))))

        ;; Debounced persistence via scheduled executor
        scheduler (Executors/newSingleThreadScheduledExecutor)
        pending-state (atom nil)
        scheduled-task (atom nil)
        persist-agent (agent nil)

        ;; Actual persist function
        do-persist! (fn [state]
                      (send-off persist-agent
                                (fn [_]
                                  (try
                                    (persist-snapshot! db namespace state)
                                    (log/debug "Persisted ctx snapshot"
                                               {:namespace namespace})
                                    (catch Exception e
                                      (log/error "ctx persist failed"
                                                 {:namespace namespace
                                                  :error (.getMessage e)})))
                                  nil)))

        ;; Watch for changes
        _ (add-watch ctx-atom ::persist
                     (fn [_ _ old new]
                       (when (and (not= old new)
                         ;; Don't persist during restore
                                  (not (::restoring (meta new))))
                ;; Schedule debounced persist
                         (reset! pending-state new)
                         (when-let [^ScheduledFuture task @scheduled-task]
                           (.cancel task false))
                         (reset! scheduled-task
                                 (.schedule scheduler
                                            ^Runnable (fn []
                                                        (when-let [state @pending-state]
                                                          (reset! pending-state nil)
                                                          (do-persist! state)))
                                            debounce-ms
                                            TimeUnit/MILLISECONDS)))))

        ;; Flush function - persist immediately
        flush! (fn []
                 (when-let [^ScheduledFuture task @scheduled-task]
                   (.cancel task false))
                 (when-let [state @pending-state]
                   (reset! pending-state nil)
                   ;; Synchronous persist
                   (persist-snapshot! db namespace state)))

        ;; Close function - cleanup
        close! (fn []
                 (flush!)
                 (remove-watch ctx-atom ::persist)
                 (.shutdown scheduler)
                 ;; Wait for pending persists
                 (await persist-agent))]

    (log/info "Created persisted ctx"
              {:namespace namespace
               :debounce-ms debounce-ms
               :restored-from (when latest (:system-from latest))})

    {::atom ctx-atom
     ::flush! flush!
     ::close! close!}))

(defn at
  "Get ctx state at a specific point in time (read-only time-travel).

   Request keys:
     ::db        - Required. Database connection
     ::namespace - Required. Agent namespace symbol
     ::instant   - Required. Point in time

   Response keys:
     ::state       - The ctx state at that time
     ::system-time - Actual system time of the snapshot

   Example:
     (at {::db conn ::namespace 'seon.trading ::instant #inst \"2026-01-04T10:00\"})"
  {:malli/schema [:=> [:cat ::at-request] ::at-response]}
  [{::keys [_db _namespace _instant]}]
  ;; TODO: migrate to Datalevin persistence
  {::state {}
   ::system-time nil})

(defn history
  "Get all historical ctx snapshots for a namespace.

   Request keys:
     ::db        - Required. Database connection
     ::namespace - Required. Agent namespace symbol

   Response keys:
     ::snapshots - Vector of {::state, ::system-time} maps

   Example:
     (history {::db conn ::namespace 'seon.trading})"
  {:malli/schema [:=> [:cat ::history-request] ::history-response]}
  [{::keys [_db _namespace]}]
  ;; TODO: migrate to Datalevin persistence
  {::snapshots []})

(defn restore!
  "Restore ctx to a historical state WITHOUT triggering persistence.
   The restored state is already in storage history.

   Request keys:
     ::atom      - Required. The persisted ctx atom
     ::db        - Required. Database connection
     ::namespace - Required. Agent namespace symbol
     ::instant   - Required. Point in time to restore to

   Response keys:
     ::state         - The restored state
     ::restored-from - The instant restored from

   Example:
     (restore! {::atom *ctx* ::db conn ::namespace 'seon.trading
                ::instant #inst \"2026-01-04T10:00\"})"
  {:malli/schema [:=> [:cat ::restore-request] ::restore-response]}
  [{::keys [atom db namespace instant]}]
  (let [historical (at {::db db ::namespace namespace ::instant instant})
        historical-state (::state historical)
        ;; Preserve current reserved keys (they're not persisted)
        current-reserved (into {} (filter (fn [[k _]] (reserved-key? k)) @atom))
        ;; Merge reserved keys into historical state
        restored-state (merge historical-state current-reserved)]
    ;; Reset with ::restoring metadata to skip persist
    (reset! atom (with-meta restored-state {::restoring true}))
    ;; Immediately clear the metadata so future swaps persist
    (reset! atom (vary-meta @atom dissoc ::restoring))
    {::state restored-state
     ::restored-from instant}))

(defn load-latest
  "Load the most recent persisted state for a namespace.
   Used for recovery on startup.

   Request keys:
     ::db        - Required. Database connection
     ::namespace - Required. Agent namespace symbol

   Response keys:
     ::state       - The latest state (nil if none)
     ::system-time - When it was persisted (nil if none)

   Example:
     (load-latest {::db conn ::namespace 'seon.trading})"
  {:malli/schema [:=> [:cat ::load-latest-request] ::load-latest-response]}
  [{::keys [_db _namespace]}]
  ;; TODO: migrate to Datalevin persistence
  {::state nil
   ::system-time nil})

(comment
  ;; REPL exploration

  ;; First, register some test schemas
  (schema/register! :test.ns/value [:int {:min 0}])
  (schema/register! :test.ns/name [:string {:min 1}])

  ;; Create a persisted ctx
  (def ctx-result (make-persisted-ctx {::db nil ::namespace 'test.ctx}))
  (def *ctx* (::atom ctx-result))

  ;; Check initial state
  @*ctx*

  ;; Valid update
  (swap! *ctx* assoc :test.ns/value 42)

  ;; Invalid updates (these should throw)
  (try
    (swap! *ctx* assoc :invalid-key "oops")
    (catch Exception e (ex-data e)))

  (try
    (swap! *ctx* assoc :test.ns/value "not-an-int")
    (catch Exception e (ex-data e)))

  ;; Clean up
  ((::close! ctx-result))
  (.close conn)

  nil)
