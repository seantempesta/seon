(ns seon.ctx
  "Unified context management.

   Per-instance atoms backed by Datalevin persistence with SSE push.

   Replaces four separate ctx systems with a single module:
   1. seon.web.reactive.instance - ephemeral per-instance atoms
   2. seon.web.reactive.ctx - ephemeral per-namespace atoms
   3. seon.primer.ctx - XTDB-persisted per-session
   4. seon.flow.harness - Datalevin-persisted per-namespace

   Each context instance is:
   - A Clojure atom with optional Datalevin persistence (debounced)
   - Optional SSE push on change
   - Optional client tracking for targeted push
   - Tracked in an in-memory registry

   Usage:
     (def *ctx* (ctx/create! {::conn dl-conn
                               ::instance-id \"a13b\"
                               ::namespace 'seon.health
                               ::initial-value {}
                               ::persist? true
                               ::sse-push? true}))
     (ctx/update! {::instance-id \"a13b\" ::f assoc ::args [:key \"value\"]})
     (ctx/destroy! {::instance-id \"a13b\"})"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [seon.schema :as schema]
            [taoensso.timbre :as log])
  (:import [java.security SecureRandom]
           [java.util.concurrent Executors ScheduledExecutorService ScheduledFuture TimeUnit]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::conn
                  [:any {:description "Datalevin connection for persistence"
                         :gen/fmap (fn [_] (throw (ex-info "Cannot generate Datalevin connection"
                                                           {:type :malli.generator/no-generator})))}])

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

;;; ---------------------------------------------------------------------------
;;; Datalevin Schema
;;; ---------------------------------------------------------------------------

(def datalevin-schema
  "Datalevin schema for ctx persistence.
   Merge with other schemas when creating connections."
  {:seon.ctx/instance-id {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :seon.ctx/namespace   {:db/valueType :db.type/string}
   :seon.ctx/data        {:db/valueType :db.type/string}
   :seon.ctx/updated-at  {:db/valueType :db.type/instant}})

;;; ---------------------------------------------------------------------------
;;; Instance ID Generation
;;; ---------------------------------------------------------------------------

(def ^:private secure-random (SecureRandom.))

(defn generate-id
  "Generate a 4-character hex instance ID."
  []
  (let [bytes (byte-array 2)]
    (.nextBytes secure-random bytes)
    (apply str (map #(format "%02x" (bit-and % 0xff)) bytes))))

;;; ---------------------------------------------------------------------------
;;; Registry
;;; ---------------------------------------------------------------------------

;; Map of instance-id -> {:atom, :conn, :namespace, :persist?, :sse-push?,
;;                         :track-clients?, :clients, :render-fn,
;;                         :created-at, :scheduler, :scheduled-task}
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
  "Persist ctx value to Datalevin. Strips non-serializable values."
  [conn instance-id namespace-sym value]
  (try
    (let [filtered (filter-serializable value)
          edn-str (pr-str filtered)]
      ((requiring-resolve 'datalevin.core/transact!)
       conn [(cond-> {:seon.ctx/instance-id instance-id
                      :seon.ctx/data edn-str
                      :seon.ctx/updated-at (java.util.Date.)}
               namespace-sym (assoc :seon.ctx/namespace (str namespace-sym)))]))
    (catch Exception e
      (log/error "Failed to persist ctx" {:instance-id instance-id
                                          :error (.getMessage e)}))))

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
  "Format HTML as Datastar SSE event (patch-elements format)."
  [html-str]
  (str "event: datastar-patch-elements\n"
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
    (when-let [clients-atom (:clients entry)]
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
    (when-let [render-fn (:render-fn entry)]
      (try
        (let [ns-sym (:namespace entry)
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
;;; Public API
;;; ---------------------------------------------------------------------------

(defn create!
  "Create a new ctx instance.

   Returns the ctx atom. Changes to this atom may trigger persistence
   and SSE push based on configuration.

   Request keys:
     ::conn            - Optional. Datalevin connection for persistence
     ::instance-id     - Required. Unique instance identifier
     ::namespace       - Optional. Namespace symbol for grouping
     ::initial-value   - Optional. Initial ctx value (default {})
     ::persist?        - Optional. Auto-persist on change (default true)
     ::sse-push?       - Optional. Push SSE on change (default true)
     ::track-clients?  - Optional. Track connected clients (default false)
     ::render-fn       - Optional. Function (ctx-value) -> hiccup (for SSE push)
     ::debounce-ms     - Optional. Debounce window in ms (default 100)

   Returns:
     The ctx atom."
  [{::keys [conn instance-id namespace initial-value persist? sse-push?
            track-clients? render-fn debounce-ms]}]
  (let [persist? (if (some? persist?) persist? true)
        sse-push? (if (some? sse-push?) sse-push? true)
        track-clients? (if (some? track-clients?) track-clients? false)
        debounce-ms (or debounce-ms 100)
        initial-value (or initial-value {})
        ctx-atom (atom initial-value)
        created-at (java.util.Date.)
        ^ScheduledExecutorService scheduler (when persist?
                                              (Executors/newSingleThreadScheduledExecutor))
        scheduled-task (atom nil)

        entry {:atom ctx-atom
               :conn conn
               :namespace namespace
               :persist? persist?
               :sse-push? sse-push?
               :track-clients? track-clients?
               :clients (when track-clients? (atom #{}))
               :render-fn render-fn
               :created-at created-at
               :scheduler scheduler
               :scheduled-task scheduled-task}]

    ;; Register in registry
    (swap! registry assoc instance-id entry)

    ;; Add watch for persistence (debounced)
    (when (and persist? conn)
      (add-watch ctx-atom ::persist
                 (fn [_ _ old-val new-val]
                   (when (not= old-val new-val)
                     ;; Cancel pending persist
                     (when-let [^ScheduledFuture task @scheduled-task]
                       (.cancel task false))
                     ;; Schedule new persist
                     (reset! scheduled-task
                             (.schedule scheduler
                                        ^Runnable (fn []
                                                    (do-persist! conn instance-id namespace new-val))
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
                                       :track-clients? track-clients?})
    ctx-atom))

(defn get-atom
  "Get the ctx atom for an instance.

   Request keys:
     ::instance-id - Required. The instance ID

   Returns:
     The atom, or nil if instance not found."
  [{::keys [instance-id]}]
  (:atom (get @registry instance-id)))

(defn get-value
  "Get the current ctx value for an instance.

   Request keys:
     ::instance-id - Required. The instance ID

   Returns:
     The current value, or nil if instance not found."
  [{::keys [instance-id]}]
  (when-let [a (get-atom {::instance-id instance-id})]
    @a))

(defn get-entry
  "Get the full registry entry for an instance.
   Returns map with :atom, :namespace, :clients, :render-fn, etc.
   or nil if not found."
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
  [{::keys [instance-id f args]}]
  (when-let [a (get-atom {::instance-id instance-id})]
    (apply swap! a f args)))

(defn destroy!
  "Remove an instance, cleaning up watches, scheduler, and client connections.

   Request keys:
     ::instance-id - Required. The instance ID

   Returns:
     true if instance was found and destroyed, false otherwise."
  [{::keys [instance-id]}]
  (if-let [entry (get @registry instance-id)]
    (let [{:keys [atom scheduler scheduled-task clients]} entry]
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
  [{::keys [instance-id channel]}]
  (if-let [entry (get @registry instance-id)]
    (if-let [clients-atom (:clients entry)]
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
  [{::keys [instance-id channel]}]
  (if-let [entry (get @registry instance-id)]
    (if-let [clients-atom (:clients entry)]
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
  [{::keys [instance-id]}]
  (when-let [entry (get @registry instance-id)]
    (when-let [clients-atom (:clients entry)]
      @clients-atom)))

(defn client-count
  "Get number of connected clients.

   Request keys:
     ::instance-id - Required. The instance ID

   Returns:
     Number of connected clients (0 if instance not found)."
  [{::keys [instance-id]}]
  (count (or (clients {::instance-id instance-id}) #{})))

(defn force-push!
  "Force render and push to all clients.

   Request keys:
     ::instance-id - Required. The instance ID

   Returns:
     true if push was attempted, false if instance not found."
  [{::keys [instance-id]}]
  (if-let [entry (get @registry instance-id)]
    (do
      (render-and-push! instance-id @(:atom entry))
      true)
    false))

(defn set-render-fn!
  "Set or update the render function for an instance.

   Request keys:
     ::instance-id - Required. The instance ID
     ::render-fn   - Required. Function (ctx-value) -> hiccup

   Returns:
     true if set, false if instance not found."
  [{::keys [instance-id render-fn]}]
  (if (contains? @registry instance-id)
    (do
      (swap! registry assoc-in [instance-id :render-fn] render-fn)
      true)
    false))

;;; ---------------------------------------------------------------------------
;;; Namespace-Level Helpers
;;; ---------------------------------------------------------------------------

(defn instances-for-namespace
  "Get all instances for a specific namespace.

   Returns vector of registry entries with ::instance-id added,
   sorted newest first."
  [ns-sym]
  (->> @registry
       (filter (fn [[_ entry]] (= (:namespace entry) ns-sym)))
       (map (fn [[id entry]] (assoc entry ::instance-id id)))
       (sort-by :created-at #(compare %2 %1))
       vec))

(defn clients-for-namespace
  "Get all client channels across all instances in a namespace."
  [ns-sym]
  (->> (instances-for-namespace ns-sym)
       (mapcat (fn [entry]
                 (when-let [clients-atom (:clients entry)]
                   @clients-atom)))
       set))

(defn client-count-for-namespace
  "Get total connected clients across all instances in a namespace."
  [ns-sym]
  (count (clients-for-namespace ns-sym)))

;;; ---------------------------------------------------------------------------
;;; Persistence API
;;; ---------------------------------------------------------------------------

(defn persist!
  "Manually persist current value to Datalevin.

   Request keys:
     ::conn        - Required. Datalevin connection
     ::instance-id - Required. The instance ID

   Returns:
     The filtered data that was persisted, or nil if instance not found."
  [{::keys [conn instance-id]}]
  (when-let [entry (get @registry instance-id)]
    (let [value @(:atom entry)
          ns-sym (:namespace entry)]
      (do-persist! conn instance-id ns-sym value)
      (filter-serializable value))))

(defn load!
  "Load persisted value from Datalevin.

   Request keys:
     ::conn        - Required. Datalevin connection
     ::instance-id - Required. The instance ID

   Returns:
     The deserialized data, or nil if not found."
  [{::keys [conn instance-id]}]
  (let [d-q (requiring-resolve 'datalevin.core/q)
        results (d-q '[:find ?data
                       :in $ ?id
                       :where
                       [?e :seon.ctx/instance-id ?id]
                       [?e :seon.ctx/data ?data]]
                     @conn instance-id)]
    (when (seq results)
      (edn/read-string (ffirst results)))))

(defn list-instances
  "List all active instances.

   Returns:
     Vector of maps with ::instance-id, ::namespace, ::created-at."
  [{}]
  (mapv (fn [[id entry]]
          {::instance-id id
           ::namespace (:namespace entry)
           ::created-at (:created-at entry)})
        @registry))
