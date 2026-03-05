(ns seon.ctx
  "Purpose: Unified stateful context for namespace instances — atom + Datalevin
   persistence + SSE push + Malli validation in one module. Every dynamic namespace
   page gets its state through ctx. Replaced four prior systems with no legacy shims.

   Depends on: seon.schema, seon.runtime (ID generation), taoensso.timbre.
   Soft deps: datalevin.core, seon.db, org.httpkit.server, seon.web.sse,
   seon.web.reactive.transform, dev.onionpancakes.chassis.core.

   Depended on by: seon.ns.lifecycle (primary), seon.ns.routes, seon.web.browser,
   seon.orchestrator.session, seon.agent.env (aliases datalevin-schema).

   Consumers:
   - seon.ns.lifecycle: Creates instances, uses resolve-instance to query by
     namespace (not duplication — load! only supports instance-id lookup). Clean.
   - seon.ns.routes: Heaviest user. Uses get-entry to access ::render-fn and ::atom
     directly — acceptable, these are the interface points for SSE push.
   - seon.orchestrator.session: Creates ctx with reserved-keys for agent isolation.
   - seon.web.browser: Uses clients-for-namespace for push targeting. Clean.

   Watch out for:
   - get-entry returns raw registry map — consumers coupled to internal structure
   - create! returns opaque atom, no :malli/schema (by design per CONVENTIONS.md)
   - generate-id delegates to seon.runtime/generate-id (zero-arg, no schema)

   Needs work:
   - P3: No generative tests (most fns have :malli/schema now, could add)
   - P3: get-entry could return curated map to decouple consumers from internals

   What's good:
   - Debounced persistence via ScheduledExecutorService
   - Two validation modes: per-key (::validate?) and whole-state (::ctx-schema)
   - Reserved keys for agent isolation (cannot modify :seon.agent/* or :seon.ns/*)
   - Non-serializable value filtering prevents persistence failures
   - All public functions use map-in pattern with :malli/schema metadata
   - Child namespace seon.ctx.history is pure and well-schemaed

   Last audit: 2026-02-26 | Tests: 27 pass / 0 fail | Commit: 98fa52d"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [seon.db.schema :as db-schema]
            [seon.schema :as schema]
            [taoensso.timbre :as log])
  (:import [java.util.concurrent Executors ScheduledExecutorService ScheduledFuture TimeUnit]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::db-name
                  [:keyword {:description "Database name keyword for persistence (e.g. :seon.runtime)"}])

(schema/register! ::instance-id
                  [:string {:min 1
                            :description "Unique instance identifier"}])

(schema/register! ::namespace
                  [:symbol {:description "Namespace symbol for grouping"}])

(schema/register! ::initial-value
                  [:any {:description "Initial ctx value"}])

(schema/register! ::persist?
                  [:boolean {:description "Auto-persist on change (default true)"}])

(schema/register! ::sse-push?
                  [:boolean {:description "Push SSE on change (default true)"}])

(schema/register! ::track-clients?
                  [:boolean {:description "Track connected clients (default false)"}])

(schema/register! ::debounce-ms
                  [:int {:min 0 :max 60000
                         :description "Debounce window in milliseconds (default 100)"}])

(schema/register! ::f
                  [:any {:description "Function to apply via swap!"}])

(schema/register! ::args
                  [:any {:description "Additional arguments for the update function"}])

(schema/register! ::updated-at
                  [inst? {:description "When the ctx was last persisted to Datalevin"}])

(schema/register! ::data
                  [:string {:description "EDN-serialized ctx state for Datalevin persistence"}])

(schema/register! ::created-at
                  [inst? {:description "When the instance was created"}])

(schema/register! ::render-fn
                  [:any {:description "Function (ctx-value) -> hiccup for SSE push"
                         :gen/fmap (fn [_] (throw (ex-info "Cannot generate render-fn"
                                                           {:type :malli.generator/no-generator})))}])

(schema/register! ::channel
                  [:any {:description "http-kit async channel"
                         :gen/fmap (fn [_] (throw (ex-info "Cannot generate channel"
                                                           {:type :malli.generator/no-generator})))}])

(schema/register! ::validate?
                  [:boolean {:description "Enable Malli validation on swap! (default false)"}])

(schema/register! ::ctx-schema
                  [:any {:description "Malli schema (or registry keyword) for whole-state validation on every swap!"}])

(schema/register! ::reserved-keys
                  [:map {:description "Immutable keys to inject and protect after creation"}])

;;; ---------------------------------------------------------------------------
;;; Request/Response Schemas (for :malli/schema metadata)
;;; ---------------------------------------------------------------------------

(schema/register! ::instance-id-request
                  [:map [::instance-id ::instance-id]])

(schema/register! ::instance-id+channel-request
                  [:map
                   [::instance-id ::instance-id]
                   [::channel ::channel]])

(schema/register! ::namespace-request
                  [:map [::namespace ::namespace]])

(schema/register! ::update-request
                  [:map
                   [::instance-id ::instance-id]
                   [::f ::f]
                   [::args {:optional true} ::args]])

(schema/register! ::set-render-fn-request
                  [:map
                   [::instance-id ::instance-id]
                   [::render-fn ::render-fn]])

(schema/register! ::persist-request
                  [:map
                   [::db-name ::db-name]
                   [::instance-id ::instance-id]])

(schema/register! ::load-request
                  [:map
                   [::db-name ::db-name]
                   [::instance-id ::instance-id]])

(schema/register! ::instance-summary
                  [:map
                   [::instance-id ::instance-id]
                   [::namespace {:optional true} [:maybe ::namespace]]
                   [::created-at ::created-at]])

;; Reserved keys used by agent sessions (formerly in seon.agent.ctx)
(schema/register! :seon.agent/namespace
                  [:symbol {:description "Agent namespace symbol (read-only in ctx)"}])

(schema/register! :seon.agent/db
                  [:any {:description "Database connection (read-only in ctx)"
                         :gen/fmap (fn [_] (throw (ex-info "Cannot generate database connection"
                                                           {:type :malli.generator/no-generator})))}])

(schema/register! :seon.ns/db-name
                  [:keyword {:description "Database name keyword for namespace DB (read-only in ctx)"}])

(schema/register! :seon.ns/session-id
                  [:string {:min 4 :max 6
                            :pattern "^[A-Za-z0-9]{4,6}$"
                            :description "Base62 session ID, 4-6 chars (read-only in ctx)"}])

(schema/register! :seon.ns/namespace
                  [:string {:min 1
                            :description "Agent namespace as string (read-only in ctx)"}])

;;; ---------------------------------------------------------------------------
;;; Datalevin Entity Schema (Malli is the source of truth)
;;; ---------------------------------------------------------------------------

(def ctx-entity-schema
  "Malli schema for a ctx persistence entity.
   Defines the shape of what gets stored in Datalevin.
   All persisted attrs have concrete types — no :any, no [:maybe X]."
  [:map
   [:seon.ctx/instance-id {:db/unique :db.unique/identity} :string]
   [:seon.ctx/namespace {:optional true} :symbol]
   [:seon.ctx/data :string]
   [:seon.ctx/updated-at :inst]])

(def datalevin-schema
  "Datalevin schema for ctx persistence. Derived from Malli.
   Merge with other schemas when creating connections."
  (db-schema/malli-map->datalevin-schema ctx-entity-schema))

;;; ---------------------------------------------------------------------------
;;; Instance ID Generation
;;; ---------------------------------------------------------------------------

(defn generate-id
  "Generate a 6-character hex instance ID.

   Delegates to seon.runtime/generate-id for unified ID generation
   with collision checking."
  []
  (:seon.runtime/id ((requiring-resolve 'seon.runtime/generate-id) {})))

;;; ---------------------------------------------------------------------------
;;; Registry
;;; ---------------------------------------------------------------------------

;; Map of instance-id -> {::atom, ::db-name, ::namespace, ::persist?, ::sse-push?,
;;                         ::track-clients?, ::clients, ::render-fn,
;;                         ::created-at, ::scheduler, ::scheduled-task}
(defonce ^:private registry (atom {}))

;;; ---------------------------------------------------------------------------
;;; Serialization Helpers
;;; ---------------------------------------------------------------------------

(defn- serializable?
  "Test if a value can round-trip through EDN."
  [v]
  (try
    (let [s (pr-str v)]
      (edn/read-string s)
      true)
    (catch Exception _
      false)))

(defn- filter-serializable
  "Filter a map to only serializable key-value pairs. Logs warnings for skipped keys."
  [m]
  (if-not (map? m)
    (do (log/warn "ctx value is not a map, cannot filter" {:type (type m)})
        {})
    (reduce-kv
     (fn [acc k v]
       (if (serializable? v)
         (assoc acc k v)
         (do (log/debug "Skipping non-serializable key in ctx persist" {:key k})
             acc)))
     {}
     m)))

;;; ---------------------------------------------------------------------------
;;; Persistence
;;; ---------------------------------------------------------------------------

(defn- do-persist!
  "Persist ctx value to Datalevin. Strips non-serializable values.
   Resolves conn from db-name internally, checks usability first."
  [db-name instance-id namespace-sym value]
  (let [conn (try
               ((requiring-resolve 'seon.db/resolve-conn) db-name)
               (catch Exception e
                 (log/warn "Cannot resolve conn for persist" {:db-name db-name :error (.getMessage e)})
                 nil))
        conn-usable? (when conn
                       (try
                         (require 'datalevin.conn)
                         (if-let [closed? (resolve 'datalevin.conn/closed?)]
                           (not (closed? conn))
                           true)
                         (catch Exception _ true)))]
    (if-not conn-usable?
      (log/warn "Skipping ctx persist — connection not usable" {:instance-id instance-id :db-name db-name})
      (try
        (let [filtered (filter-serializable value)
              edn-str (pr-str filtered)]
          ((requiring-resolve 'seon.db/transact!)
           db-name [(cond-> {:seon.ctx/instance-id instance-id
                             :seon.ctx/data edn-str
                             :seon.ctx/updated-at (java.util.Date.)}
                      namespace-sym (assoc :seon.ctx/namespace namespace-sym))]))
        (catch Exception e
          (log/error "Failed to persist ctx" {:instance-id instance-id
                                              :error (.getMessage e)}))))))

;;; ---------------------------------------------------------------------------
;;; SSE Push (broadcast)
;;; ---------------------------------------------------------------------------

(defn- sse-push!
  "Trigger SSE refresh if web layer is loaded."
  []
  (try
    (when-let [f (resolve 'seon.web.sse/refresh-all!)]
      (f))
    (catch Exception e
      (log/debug "SSE push failed" {:error (.getMessage e)}))))

;;; ---------------------------------------------------------------------------
;;; Client-Targeted SSE Push
;;; ---------------------------------------------------------------------------

(defn- format-sse-event
  "Format HTML as Datastar SSE event (patch-elements format).
  Includes useViewTransition for smooth CSS view transitions."
  [html-str]
  (str "event: datastar-patch-elements\n"
       "data: useViewTransition true\n"
       "data: elements " (str/replace html-str "\n" "\ndata: elements ")
       "\n\n\n"))

(defn- push-to-client!
  "Push SSE update to a single client. Returns true if successful."
  [channel html-str]
  (try
    (let [open? ((requiring-resolve 'org.httpkit.server/open?) channel)]
      (when open?
        ((requiring-resolve 'org.httpkit.server/send!) channel (format-sse-event html-str) false)
        true))
    (catch Exception e
      (log/debug "Failed to push to client" {:error (.getMessage e)})
      false)))

(defn- push-update!
  "Push update to all clients for an instance."
  [instance-id html-str]
  (when-let [entry (get @registry instance-id)]
    (when-let [clients-atom (::clients entry)]
      (let [channels @clients-atom
            results (doall (map #(push-to-client! % html-str) channels))
            failed (count (filter false? results))]
        (when (pos? failed)
          ;; Clean up dead channels
          (let [open? (requiring-resolve 'org.httpkit.server/open?)]
            (swap! clients-atom #(set (filter open? %)))))
        (log/debug "Pushed SSE update" {:instance-id instance-id
                                        :clients (count channels)
                                        :failed failed})))))

(defn- render-and-push!
  "Render current ctx state and push to all clients."
  [instance-id ctx-value]
  (when-let [entry (get @registry instance-id)]
    (when-let [render-fn (::render-fn entry)]
      (try
        (let [ns-sym (::namespace entry)
              hiccup (render-fn ctx-value)
              transformed ((requiring-resolve 'seon.web.reactive.transform/transform-hiccup)
                           ns-sym hiccup instance-id)
              html-str (str ((requiring-resolve 'dev.onionpancakes.chassis.core/html) transformed))]
          (push-update! instance-id html-str))
        (catch Exception e
          (log/error e "Render failed" {:instance-id instance-id}))))))

(defn- make-client-watch
  "Create a watch function that pushes rendered updates to connected clients."
  [instance-id]
  (fn [_key _ref old-val new-val]
    (when (not= old-val new-val)
      (render-and-push! instance-id new-val))))

;;; ---------------------------------------------------------------------------
;;; Ctx-Schema Validation (whole-state Malli validation)
;;; ---------------------------------------------------------------------------

(defn- make-ctx-schema-validator
  "Create a validator function for ::ctx-schema mode.
   Validates the entire state against a Malli schema on every swap!.
   Returns a function suitable for atom :validator."
  [schema-or-key]
  (let [resolved (if (keyword? schema-or-key)
                   (schema/schema-definition schema-or-key)
                   schema-or-key)
        validator (m/validator resolved)
        explainer (m/explainer resolved)]
    (fn [new-state]
      (if (validator new-state)
        true
        (throw (ex-info "ctx state does not conform to ::ctx-schema"
                        {:spec schema-or-key
                         :errors (me/humanize (explainer new-state))}))))))

;;; ---------------------------------------------------------------------------
;;; Validation Helpers (for ::validate? mode)
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
    ;; Cannot add new reserved keys
    (and (reserved-key? k)
         (not (contains? reserved-keys k)))
    {:error :reserved-key-addition
     :key k
     :message (format "Cannot add reserved key %s" k)}

    ;; Cannot modify existing reserved keys
    (and (reserved-key? k)
         (contains? reserved-keys k)
         (not= v (get reserved-keys k)))
    {:error :reserved-key-modification
     :key k
     :message (format "Cannot modify reserved key %s" k)}

    ;; Skip further validation for reserved keys
    (reserved-key? k)
    nil

    ;; Must be namespaced
    (not (namespaced-key? k))
    {:error :non-namespaced-key
     :key k
     :message (format "Invalid ctx key %s - must be fully namespaced" k)}

    ;; Must have registered schema
    (not (schema/registered? k))
    {:error :missing-spec
     :key k
     :message (format "No spec registered for key %s" k)}

    ;; Value must validate against schema
    :else
    (let [spec (schema/schema-definition k)]
      (when-not (m/validate spec v)
        {:error :validation-failure
         :key k
         :message (format "Value for %s failed validation" k)}))))

(defn- validate-state
  "Validate a complete ctx state. Returns nil if valid, throws if invalid."
  [new-state reserved-keys]
  (when-not (map? new-state)
    (throw (ex-info "ctx state must be a map"
                    {:error :not-a-map :got (type new-state)})))
  ;; Check for removed reserved keys
  (doseq [k (keys reserved-keys)]
    (when-not (contains? new-state k)
      (throw (ex-info (format "Cannot remove reserved key %s" k)
                      {:error :reserved-key-removal :key k}))))
  ;; Validate each key
  (doseq [[k v] new-state]
    (when-let [error (validate-key k v reserved-keys)]
      (throw (ex-info (:message error) error))))
  nil)

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn create!
  "Create a new ctx instance.

   Returns the ctx atom. Changes to this atom may trigger persistence
   and SSE push based on configuration.

   Request keys:
     ::db-name         - Optional. Database name keyword for persistence (e.g. :seon.runtime)
     ::instance-id     - Required. Unique instance identifier
     ::namespace       - Optional. Namespace symbol for grouping
     ::initial-value   - Optional. Initial ctx value (default {})
     ::persist?        - Optional. Auto-persist on change (default true)
     ::sse-push?       - Optional. Push SSE on change (default true)
     ::track-clients?  - Optional. Track connected clients (default false)
     ::render-fn       - Optional. Function (ctx-value) -> hiccup (for SSE push)
     ::debounce-ms     - Optional. Debounce window in ms (default 100)
     ::validate?       - Optional. Enable Malli validation on swap! (default false)
     ::reserved-keys   - Optional. Map of immutable keys to inject and protect
     ::ctx-schema      - Optional. Malli schema for whole-state validation on every swap!

   Returns:
     The ctx atom.

   Note: No :malli/schema - returns an atom (opaque runtime object)
   that cannot be property tested. See CONVENTIONS.md."
  [{::keys [db-name instance-id namespace initial-value persist? sse-push?
            track-clients? render-fn debounce-ms validate? reserved-keys
            ctx-schema]}]
  (let [persist? (if (some? persist?) persist? true)
        sse-push? (if (some? sse-push?) sse-push? true)
        track-clients? (if (some? track-clients?) track-clients? false)
        validate? (if (some? validate?) validate? false)
        debounce-ms (or debounce-ms 100)
        reserved-keys (or reserved-keys {})
        initial-value (merge (or initial-value {}) reserved-keys)
        reserved-keys-snapshot (atom reserved-keys)
        schema-validator (when ctx-schema (make-ctx-schema-validator ctx-schema))
        ctx-atom (atom initial-value
                       :validator (cond
                                    ;; ctx-schema takes precedence — validates whole state
                                    schema-validator
                                    (fn [new-state]
                                      (when validate?
                                        (validate-state new-state @reserved-keys-snapshot))
                                      (schema-validator new-state))
                                    ;; Per-key validation
                                    validate?
                                    (fn [new-state]
                                      (validate-state new-state @reserved-keys-snapshot)
                                      true)))
        created-at (java.util.Date.)
        ^ScheduledExecutorService scheduler (when persist?
                                              (Executors/newSingleThreadScheduledExecutor))
        scheduled-task (atom nil)

        entry {::atom ctx-atom
               ::db-name db-name
               ::namespace namespace
               ::persist? persist?
               ::sse-push? sse-push?
               ::track-clients? track-clients?
               ::validate? validate?
               ::reserved-keys reserved-keys-snapshot
               ::clients (when track-clients? (atom #{}))
               ::render-fn render-fn
               ::created-at created-at
               ::scheduler scheduler
               ::scheduled-task scheduled-task}]

    ;; Register in registry
    (swap! registry assoc instance-id entry)

    ;; Add watch for persistence (debounced)
    (when (and persist? db-name)
      (add-watch ctx-atom ::persist
                 (fn [_ _ old-val new-val]
                   (when (not= old-val new-val)
                     ;; Cancel pending persist
                     (when-let [^ScheduledFuture task @scheduled-task]
                       (.cancel task false))
                     ;; Schedule new persist — use bound-fn to convey dynamic bindings
                     ;; (e.g. db/*direct-mode* in tests) to the scheduler thread
                     (reset! scheduled-task
                             (.schedule scheduler
                                        ^Runnable (bound-fn []
                                                    (do-persist! db-name instance-id namespace new-val))
                                        (long debounce-ms)
                                        TimeUnit/MILLISECONDS))))))

    ;; Add watch for SSE push (broadcast)
    (when sse-push?
      (add-watch ctx-atom ::sse-push
                 (fn [_ _ old-val new-val]
                   (when (not= old-val new-val)
                     (sse-push!)))))

    ;; Add watch for client-targeted push
    (when track-clients?
      (add-watch ctx-atom ::client-push (make-client-watch instance-id)))

    (log/info "Created ctx instance" {:instance-id instance-id
                                      :namespace namespace
                                      :persist? persist?
                                      :sse-push? sse-push?
                                      :track-clients? track-clients?
                                      :validate? validate?})
    ctx-atom))

(defn get-atom
  "Get the ctx atom for an instance.

   Request keys:
     ::instance-id - Required. The instance ID

   Returns:
     The atom, or nil if instance not found."
  {:malli/schema [:=> [:cat ::instance-id-request] [:maybe :any]]}
  [{::keys [instance-id]}]
  (::atom (get @registry instance-id)))

(defn get-value
  "Get the current ctx value for an instance.

   Request keys:
     ::instance-id - Required. The instance ID

   Returns:
     The current value, or nil if instance not found."
  {:malli/schema [:=> [:cat ::instance-id-request] [:maybe :any]]}
  [{::keys [instance-id]}]
  (when-let [a (get-atom {::instance-id instance-id})]
    @a))

(defn get-entry
  "Get the full registry entry for an instance.
   Returns map with ::atom, ::namespace, ::clients, ::render-fn, etc.
   or nil if not found."
  {:malli/schema [:=> [:cat ::instance-id-request] [:maybe :any]]}
  [{::keys [instance-id]}]
  (get @registry instance-id))

(defn update!
  "Update ctx value via swap!.

   Request keys:
     ::instance-id - Required. The instance ID
     ::f           - Required. Function to apply
     ::args        - Optional. Additional arguments (vector)

   Returns:
     The new value, or nil if instance not found."
  {:malli/schema [:=> [:cat ::update-request] [:maybe :any]]}
  [{::keys [instance-id f args]}]
  (when-let [a (get-atom {::instance-id instance-id})]
    (apply swap! a f args)))

(defn destroy!
  "Remove an instance, cleaning up watches, scheduler, and client connections.

   Request keys:
     ::instance-id - Required. The instance ID

   Returns:
     true if instance was found and destroyed, false otherwise."
  {:malli/schema [:=> [:cat ::instance-id-request] :boolean]}
  [{::keys [instance-id]}]
  (if-let [entry (get @registry instance-id)]
    (let [{::keys [atom scheduler scheduled-task clients]} entry]
      ;; Close all client connections
      (when clients
        (doseq [ch @clients]
          (try
            ((requiring-resolve 'org.httpkit.server/close) ch)
            (catch Exception _))))
      ;; Cancel any pending persist
      (when-let [^ScheduledFuture task @scheduled-task]
        (.cancel task false))
      ;; Shutdown scheduler
      (when scheduler
        (.shutdown ^ScheduledExecutorService scheduler))
      ;; Remove watches
      (remove-watch atom ::persist)
      (remove-watch atom ::sse-push)
      (remove-watch atom ::client-push)
      ;; Remove from registry
      (swap! registry dissoc instance-id)
      (log/info "Destroyed ctx instance" {:instance-id instance-id})
      true)
    false))

;;; ---------------------------------------------------------------------------
;;; Client Management
;;; ---------------------------------------------------------------------------

(defn register-client!
  "Register a client channel for an instance.

   Request keys:
     ::instance-id - Required. The instance ID
     ::channel     - Required. The http-kit async channel

   Returns:
     true if registered, false if instance not found or not tracking clients."
  {:malli/schema [:=> [:cat ::instance-id+channel-request] :boolean]}
  [{::keys [instance-id channel]}]
  (if-let [entry (get @registry instance-id)]
    (if-let [clients-atom (::clients entry)]
      (do
        (swap! clients-atom conj channel)
        (log/debug "Client registered" {:instance-id instance-id})
        true)
      (do
        (log/warn "Instance not tracking clients" {:instance-id instance-id})
        false))
    (do
      (log/warn "Cannot register client - instance not found" {:instance-id instance-id})
      false)))

(defn unregister-client!
  "Unregister a client channel from an instance.

   Request keys:
     ::instance-id - Required. The instance ID
     ::channel     - Required. The http-kit async channel

   Returns:
     true if unregistered, false if instance not found."
  {:malli/schema [:=> [:cat ::instance-id+channel-request] :boolean]}
  [{::keys [instance-id channel]}]
  (if-let [entry (get @registry instance-id)]
    (if-let [clients-atom (::clients entry)]
      (do
        (swap! clients-atom disj channel)
        (log/debug "Client unregistered" {:instance-id instance-id})
        true)
      false)
    false))

(defn clients
  "Get connected clients for an instance.

   Request keys:
     ::instance-id - Required. The instance ID

   Returns:
     Set of channels, or nil if instance not found or not tracking."
  {:malli/schema [:=> [:cat ::instance-id-request] [:maybe [:set :any]]]}
  [{::keys [instance-id]}]
  (when-let [entry (get @registry instance-id)]
    (when-let [clients-atom (::clients entry)]
      @clients-atom)))

(defn client-count
  "Get number of connected clients.

   Request keys:
     ::instance-id - Required. The instance ID

   Returns:
     Number of connected clients (0 if instance not found)."
  {:malli/schema [:=> [:cat ::instance-id-request] :int]}
  [{::keys [instance-id]}]
  (count (or (clients {::instance-id instance-id}) #{})))

(defn force-push!
  "Force render and push to all clients.

   Request keys:
     ::instance-id - Required. The instance ID

   Returns:
     true if push was attempted, false if instance not found."
  {:malli/schema [:=> [:cat ::instance-id-request] :boolean]}
  [{::keys [instance-id]}]
  (if-let [entry (get @registry instance-id)]
    (do
      (render-and-push! instance-id @(::atom entry))
      true)
    false))

(defn set-render-fn!
  "Set or update the render function for an instance.

   Request keys:
     ::instance-id - Required. The instance ID
     ::render-fn   - Required. Function (ctx-value) -> hiccup

   Returns:
     true if set, false if instance not found."
  {:malli/schema [:=> [:cat ::set-render-fn-request] :boolean]}
  [{::keys [instance-id render-fn]}]
  (if (contains? @registry instance-id)
    (do
      (swap! registry assoc-in [instance-id ::render-fn] render-fn)
      true)
    false))

;;; ---------------------------------------------------------------------------
;;; Namespace-Level Helpers
;;; ---------------------------------------------------------------------------

(defn instances-for-namespace
  "Get all instances for a specific namespace.

   Request keys:
     ::namespace - Required. Namespace symbol for filtering

   Returns vector of registry entries with ::instance-id added,
   sorted newest first."
  {:malli/schema [:=> [:cat ::namespace-request] [:vector :any]]}
  [{::keys [namespace]}]
  (->> @registry
       (filter (fn [[_ entry]] (= (::namespace entry) namespace)))
       (map (fn [[id entry]] (assoc entry ::instance-id id)))
       (sort-by :created-at #(compare %2 %1))
       vec))

(defn clients-for-namespace
  "Get all client channels across all instances in a namespace.

   Request keys:
     ::namespace - Required. Namespace symbol for filtering

   Returns set of channels."
  {:malli/schema [:=> [:cat ::namespace-request] [:set :any]]}
  [{::keys [namespace] :as req}]
  (->> (instances-for-namespace req)
       (mapcat (fn [entry]
                 (when-let [clients-atom (::clients entry)]
                   @clients-atom)))
       set))

(defn client-count-for-namespace
  "Get total connected clients across all instances in a namespace.

   Request keys:
     ::namespace - Required. Namespace symbol for filtering

   Returns integer count."
  {:malli/schema [:=> [:cat ::namespace-request] :int]}
  [{::keys [namespace] :as req}]
  (count (clients-for-namespace req)))

;;; ---------------------------------------------------------------------------
;;; Persistence API
;;; ---------------------------------------------------------------------------

(defn persist!
  "Manually persist current value to Datalevin.

   Request keys:
     ::db-name     - Required. Database name keyword
     ::instance-id - Required. The instance ID

   Returns:
     The filtered data that was persisted, or nil if instance not found."
  {:malli/schema [:=> [:cat ::persist-request] [:maybe :any]]}
  [{::keys [db-name instance-id]}]
  (when-let [entry (get @registry instance-id)]
    (let [value @(::atom entry)
          ns-sym (::namespace entry)]
      (do-persist! db-name instance-id ns-sym value)
      (filter-serializable value))))

(defn load!
  "Load persisted value from Datalevin.

   Request keys:
     ::db-name     - Required. Database name keyword
     ::instance-id - Required. The instance ID

   Returns:
     The deserialized data, or nil if not found."
  {:malli/schema [:=> [:cat ::load-request] [:maybe :any]]}
  [{::keys [db-name instance-id]}]
  (let [results ((requiring-resolve 'seon.db/query)
                 db-name
                 '[:find ?data
                   :in $ ?id
                   :where
                   [?e :seon.ctx/instance-id ?id]
                   [?e :seon.ctx/data ?data]]
                 instance-id)]
    (when (seq results)
      (edn/read-string (ffirst results)))))

(defn list-instances
  "List all active instances.

   Returns:
     Vector of maps with ::instance-id, ::namespace, ::created-at."
  {:malli/schema [:=> [:cat :any] [:vector ::instance-summary]]}
  [{}]
  (mapv (fn [[id entry]]
          {::instance-id id
           ::namespace (::namespace entry)
           ::created-at (::created-at entry)})
        @registry))
