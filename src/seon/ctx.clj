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
            [seon.schema :as schema]
            [taoensso.timbre :as log])
  (:import [java.util.concurrent Executors ScheduledExecutorService ScheduledFuture TimeUnit]))

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

(schema/register! ::debounce-ms
                  [:int {:min 0 :max 60000
                         :description "Debounce window in milliseconds (default 100)"}])

(schema/register! ::f
                  [:any {:description "Function to apply via swap!"}])

(schema/register! ::args
                  [:any {:description "Additional arguments for the update function"}])

(schema/register! ::created-at
                  [inst? {:description "When the instance was created"}])

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
;;; Registry
;;; ---------------------------------------------------------------------------

;; Map of instance-id -> {:atom, :conn, :namespace, :persist?, :sse-push?,
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
;;; SSE Push
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
;;; Public API
;;; ---------------------------------------------------------------------------

(defn create!
  "Create a new ctx instance.

   Returns the ctx atom. Changes to this atom may trigger persistence
   and SSE push based on configuration.

   Request keys:
     ::conn          - Optional. Datalevin connection for persistence
     ::instance-id   - Required. Unique instance identifier
     ::namespace     - Optional. Namespace symbol for grouping
     ::initial-value - Optional. Initial ctx value (default {})
     ::persist?      - Optional. Auto-persist on change (default true)
     ::sse-push?     - Optional. Push SSE on change (default true)
     ::debounce-ms   - Optional. Debounce window in ms (default 100)

   Returns:
     The ctx atom."
  [{::keys [conn instance-id namespace initial-value persist? sse-push? debounce-ms]}]
  (let [persist? (if (some? persist?) persist? true)
        sse-push? (if (some? sse-push?) sse-push? true)
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

    ;; Add watch for SSE push
    (when sse-push?
      (add-watch ctx-atom ::sse-push
                 (fn [_ _ old-val new-val]
                   (when (not= old-val new-val)
                     (sse-push!)))))

    (log/info "Created ctx instance" {:instance-id instance-id
                                       :namespace namespace
                                       :persist? persist?
                                       :sse-push? sse-push?})
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
  "Remove an instance, cleaning up watches and scheduler.

   Request keys:
     ::instance-id - Required. The instance ID

   Returns:
     true if instance was found and destroyed, false otherwise."
  [{::keys [instance-id]}]
  (if-let [entry (get @registry instance-id)]
    (let [{:keys [atom scheduler scheduled-task]} entry]
      ;; Cancel any pending persist
      (when-let [^ScheduledFuture task @scheduled-task]
        (.cancel task false))
      ;; Shutdown scheduler
      (when scheduler
        (.shutdown ^ScheduledExecutorService scheduler))
      ;; Remove watches
      (remove-watch atom ::persist)
      (remove-watch atom ::sse-push)
      ;; Remove from registry
      (swap! registry dissoc instance-id)
      (log/info "Destroyed ctx instance" {:instance-id instance-id})
      true)
    false))

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
