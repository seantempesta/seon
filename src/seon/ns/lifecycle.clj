(ns seon.ns.lifecycle
  "Lifecycle management for dynamic namespaces.

   When a namespace registers a `::*ctx*` spec (detected by the scanner as
   `:seon.ns/dynamic? true`), this module handles:
   - Creating validated ctx atoms with Malli schema enforcement
   - Injecting `*ctx*` and `*conn*` dynamic vars into the namespace
   - Resolving page render functions from Datalevin metadata
   - Persisting and restoring ctx state across restarts

   Usage:
     (lifecycle/ensure-instance! {::conn graph-conn
                                  ::ns-sym 'seon.health.workout})
     ;; => {::instance-id \"abc123\" ::ctx-atom #<Atom@...>}

     (lifecycle/backup-all-instances! {::conn graph-conn})
     (lifecycle/restore-instances! {::conn graph-conn})"
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datalevin.core :as d]
            [malli.core :as m]
            [malli.generator :as mg]
            [seon.ctx :as ctx]
            [seon.graph.query :as gq]
            [seon.runtime :as runtime]
            [seon.schema :as schema]
            [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::conn
                  [:any {:description "Datalevin connection for graph queries and ctx persistence"
                         :gen/fmap (fn [_] (throw (ex-info "Cannot generate Datalevin connection"
                                                           {:type :malli.generator/no-generator})))}])

(schema/register! ::ns-sym
                  [:symbol {:description "Namespace symbol (e.g. 'seon.health.workout)"}])

(schema/register! ::instance-id
                  [:string {:min 1 :description "Instance ID"}])

(schema/register! ::dynamic?
                  [:boolean {:description "Whether namespace is dynamic"}])

(schema/register! ::ctx-spec-key
                  [:keyword {:description "Ctx spec keyword for a namespace"}])

(schema/register! ::ctx-atom
                  [:any {:description "Ctx atom reference"
                         :gen/fmap (fn [_] (throw (ex-info "Cannot generate ctx atom"
                                                           {:type :malli.generator/no-generator})))}])

(schema/register! ::render-fn
                  [:any {:description "Page render function var"
                         :gen/fmap (fn [_] (throw (ex-info "Cannot generate render fn"
                                                           {:type :malli.generator/no-generator})))}])

(schema/register! ::data
                  [:any {:description "Ctx data value (map)"}])

(schema/register! ::backed-up
                  [:int {:min 0 :description "Number of instances backed up"}])

(schema/register! ::restored
                  [:int {:min 0 :description "Number of instances restored"}])

(schema/register! ::total
                  [:int {:min 0 :description "Total instances processed"}])

;;; Request/Response Schemas

(schema/register! ::dynamic-namespace-request
                  [:map
                   [::conn ::conn]
                   [::ns-sym ::ns-sym]])

(schema/register! ::dynamic-namespace-response
                  [:map
                   [::dynamic? ::dynamic?]])

(schema/register! ::ctx-spec-key-request
                  [:map
                   [::ns-sym ::ns-sym]])

(schema/register! ::ctx-spec-key-response
                  [:map
                   [::ctx-spec-key ::ctx-spec-key]])

(schema/register! ::initial-value-request
                  [:map
                   [::ns-sym ::ns-sym]])

(schema/register! ::initial-value-response
                  [:map
                   [::data ::data]])

(schema/register! ::find-page-render-fn-request
                  [:map
                   [::conn ::conn]
                   [::ns-sym ::ns-sym]])

(schema/register! ::find-page-render-fn-response
                  [:map
                   [::render-fn [:maybe ::render-fn]]])

(schema/register! ::make-render-fn-request
                  [:map
                   [::render-fn ::render-fn]
                   [::ns-sym ::ns-sym]])

(schema/register! ::inject-vars-request
                  [:map
                   [::ns-sym ::ns-sym]
                   [::ctx-atom ::ctx-atom]
                   [::conn {:optional true} ::conn]])

(schema/register! ::resolve-instance-request
                  [:map
                   [::conn ::conn]
                   [::ns-sym ::ns-sym]
                   [::instance-id {:optional true} [:maybe ::instance-id]]])

(schema/register! ::resolve-instance-response
                  [:maybe [:map
                           [::instance-id ::instance-id]
                           [::data ::data]]])

(schema/register! ::ensure-instance-request
                  [:map
                   [::conn {:optional true} ::conn]
                   [::ns-sym ::ns-sym]
                   [::instance-id {:optional true} ::instance-id]])

(schema/register! ::ensure-instance-response
                  [:map
                   [::instance-id ::instance-id]
                   [::ctx-atom ::ctx-atom]])

(schema/register! ::backup-all-instances-request
                  [:map
                   [::conn ::conn]])

(schema/register! ::backup-all-instances-response
                  [:map
                   [::backed-up ::backed-up]
                   [::total ::total]])

(schema/register! ::restore-instances-request
                  [:map
                   [::conn ::conn]])

(schema/register! ::restore-instances-response
                  [:map
                   [::restored ::restored]
                   [::total ::total]])

;;; ---------------------------------------------------------------------------
;;; Query Helpers
;;; ---------------------------------------------------------------------------

(defn dynamic-namespace?
  "Check if a namespace is dynamic (has ::*ctx* spec).
   Queries Datalevin for :seon.ns/dynamic? set by the scanner.

   Request keys:
     ::conn   - Required. Datalevin connection
     ::ns-sym - Required. Namespace symbol

   Response keys:
     ::dynamic? - true if namespace has ::*ctx* spec"
  {:malli/schema [:=> [:cat ::dynamic-namespace-request] ::dynamic-namespace-response]}
  [{::keys [conn ns-sym]}]
  (let [ns-str (str ns-sym)
        results (d/q '[:find ?dyn
                       :in $ ?ns
                       :where
                       [?e :seon.ns/name ?ns]
                       [?e :seon.ns/dynamic? ?dyn]]
                     @conn ns-str)]
    {::dynamic? (true? (ffirst results))}))

(defn ctx-spec-key
  "Convert namespace symbol to its *ctx* spec keyword.
   'seon.health.workout => :seon.health.workout/*ctx*

   Request keys:
     ::ns-sym - Required. Namespace symbol

   Response keys:
     ::ctx-spec-key - The spec keyword"
  {:malli/schema [:=> [:cat ::ctx-spec-key-request] ::ctx-spec-key-response]}
  [{::keys [ns-sym]}]
  {::ctx-spec-key (keyword (str ns-sym) "*ctx*")})

(defn initial-value
  "Get the initial ctx value for a namespace.
   Tries the namespace's `initial-state` fn first, falls back to mg/generate.

   Request keys:
     ::ns-sym - Required. Namespace symbol

   Response keys:
     ::data - The initial value map"
  {:malli/schema [:=> [:cat ::initial-value-request] ::initial-value-response]}
  [{::keys [ns-sym]}]
  (let [init-fn-sym (symbol (str ns-sym) "initial-state")
        spec-key (::ctx-spec-key (ctx-spec-key {::ns-sym ns-sym}))]
    {::data
     (if-let [init-fn (try (requiring-resolve init-fn-sym) (catch Exception _ nil))]
       (@init-fn)
       (when (schema/registered? spec-key)
         (try
           (mg/generate (schema/schema-definition spec-key))
           (catch Exception e
             (log/warn "Failed to generate initial value from spec"
                       {:ns ns-sym :error (.getMessage e)})
             {}))))}))

(defn find-page-render-fn
  "Find the page renderer function for a namespace via Datalevin metadata.
   Uses functions-with-output-key to find HTML renderers, then filters for
   those whose required-keys include the namespace's *ctx* key.
   This handles renderers in child namespaces
   (e.g. seon.health.workout.render/page-render for seon.health.workout).

   Request keys:
     ::conn   - Required. Datalevin connection
     ::ns-sym - Required. Namespace symbol

   Response keys:
     ::render-fn - The resolved var, or nil"
  {:malli/schema [:=> [:cat ::find-page-render-fn-request] ::find-page-render-fn-response]}
  [{::keys [conn ns-sym]}]
  (let [ctx-key (keyword (str ns-sym) "*ctx*")
        ;; Find all HTML renderers via ref join
        candidates (gq/functions-with-output-key {::gq/conn conn ::gq/output-key :seon.render/html})
        ;; Filter for renderers whose required-keys include the *ctx* key
        matching (->> candidates
                      (filter (fn [e]
                                (some #(str/ends-with? (name %) "*ctx*")
                                      (:required-keys e))))
                      ;; Prefer exact match on ctx-key
                      (sort-by (fn [e]
                                 (if (contains? (:required-keys e) ctx-key) 0 1)))
                      first)]
    {::render-fn
     (when-let [qname (:seon.fn/qualified-name matching)]
       (let [sym (symbol qname)]
         (try
           (requiring-resolve sym)
           (catch Exception e
             (log/warn "Failed to resolve page renderer"
                       {:fn qname :error (.getMessage e)})
             nil))))}))

(defn make-render-fn
  "Wrap a page renderer for use with ctx push.
   Raw ctx-value -> {ctx-key ctx-value} -> renderer -> :seon.render/html

   Request keys:
     ::render-fn - Required. Page render function var
     ::ns-sym    - Required. Namespace symbol

   Returns a function (ctx-value) -> hiccup."
  {:malli/schema [:=> [:cat ::make-render-fn-request] :any]}
  [{::keys [render-fn ns-sym]}]
  (let [ck (::ctx-spec-key (ctx-spec-key {::ns-sym ns-sym}))]
    (fn [ctx-value]
      (let [input {ck ctx-value}
            result (@render-fn input)]
        (:seon.render/html result)))))

(defn inject-vars!
  "Inject *ctx* and *conn* dynamic vars into a namespace.
   Uses intern + .setDynamic to create proper dynamic vars.

   Request keys:
     ::ns-sym   - Required. Namespace symbol
     ::ctx-atom - Required. The ctx atom
     ::conn     - Optional. Datalevin connection for *conn*"
  {:malli/schema [:=> [:cat ::inject-vars-request] :boolean]}
  [{::keys [ns-sym ctx-atom conn]}]
  (when-let [ns-obj (find-ns ns-sym)]
    (let [v (intern ns-obj '*ctx* ctx-atom)]
      (.setDynamic v true))
    (when conn
      (let [v (intern ns-obj '*conn* conn)]
        (.setDynamic v true)))
    true))

;;; ---------------------------------------------------------------------------
;;; Instance Resolution
;;; ---------------------------------------------------------------------------

(defn resolve-instance
  "Resolve an existing instance from Datalevin.
   With instance-id: look up that specific instance.
   Without: find the most recent instance for this namespace.

   Request keys:
     ::conn        - Required. Datalevin connection
     ::ns-sym      - Required. Namespace symbol
     ::instance-id - Optional. Specific instance to look up

   Response: map with ::instance-id and ::data, or nil."
  {:malli/schema [:=> [:cat ::resolve-instance-request] ::resolve-instance-response]}
  [{::keys [conn ns-sym instance-id]}]
  (let [ns-str (str ns-sym)]
    (if instance-id
      ;; Look up specific instance
      (let [results (d/q '[:find ?data ?updated
                           :in $ ?id
                           :where
                           [?e :seon.ctx/instance-id ?id]
                           [?e :seon.ctx/data ?data]
                           [?e :seon.ctx/updated-at ?updated]]
                         @conn instance-id)]
        (when (seq results)
          (let [[data-str _] (first results)]
            {::instance-id instance-id
             ::data (edn/read-string data-str)})))
      ;; Find most recent for namespace
      (let [results (d/q '[:find ?id ?data ?updated
                           :in $ ?ns
                           :where
                           [?e :seon.ctx/namespace ?ns]
                           [?e :seon.ctx/instance-id ?id]
                           [?e :seon.ctx/data ?data]
                           [?e :seon.ctx/updated-at ?updated]]
                         @conn ns-str)]
        (when (seq results)
          (let [[id data-str _] (->> results
                                     (sort-by #(nth % 2) #(compare %2 %1))
                                     first)]
            {::instance-id id
             ::data (edn/read-string data-str)}))))))

;;; ---------------------------------------------------------------------------
;;; Instance Lifecycle
;;; ---------------------------------------------------------------------------

(defn ensure-instance!
  "Resolve or create a ctx instance for a dynamic namespace.

   FIRST checks in-memory registry for existing live instance.
   If found, returns it immediately (preserving in-memory state).

   If not in memory, checks Datalevin for persisted state.
   If valid, restores from persistence. Otherwise creates fresh.

   Request keys:
     ::conn        - Optional. Datalevin connection
     ::ns-sym      - Required. Namespace symbol
     ::instance-id - Optional. Specific instance to resume

   Response keys:
     ::instance-id - The instance ID (existing or newly generated)
     ::ctx-atom    - The ctx atom"
  {:malli/schema [:=> [:cat ::ensure-instance-request] ::ensure-instance-response]}
  [{::keys [conn ns-sym instance-id]}]
  ;; CRITICAL: Check in-memory registry FIRST to avoid creating duplicate atoms
  (if-let [existing-atom (when instance-id
                           (ctx/get-atom {::ctx/instance-id instance-id}))]
    ;; Instance already exists in memory - return it
    (do
      (log/debug "Using existing in-memory instance" {:ns ns-sym :instance instance-id})
      {::instance-id instance-id
       ::ctx-atom existing-atom})
    ;; Not in memory - check persistence or create fresh
    (let [spec-key (::ctx-spec-key (ctx-spec-key {::ns-sym ns-sym}))
          persisted (when conn
                      (resolve-instance {::conn conn ::ns-sym ns-sym ::instance-id instance-id}))
          page-render-var (when conn
                            (::render-fn (find-page-render-fn {::conn conn ::ns-sym ns-sym})))
          render-fn (when page-render-var
                      (make-render-fn {::render-fn page-render-var ::ns-sym ns-sym}))
          ;; Check if persisted state is valid against current spec
          persisted-valid? (when persisted
                             (if (schema/registered? spec-key)
                               (m/validate (schema/schema-definition spec-key)
                                           (::data persisted))
                               true))
          use-persisted? (and persisted persisted-valid?)
          iid (if use-persisted?
                (::instance-id persisted)
                (or instance-id
                    (:seon.runtime/id (runtime/generate-id {}))))
          init-val (if use-persisted?
                     (::data persisted)
                     (::data (initial-value {::ns-sym ns-sym})))]

      (when (and persisted (not persisted-valid?))
        (log/warn "Persisted ctx state invalid against current spec, creating fresh"
                  {:ns ns-sym :instance-id (::instance-id persisted)}))

      (let [ctx-atom (ctx/create!
                      (cond-> {::ctx/instance-id iid
                               ::ctx/namespace ns-sym
                               ::ctx/initial-value init-val
                               ::ctx/persist? (some? conn)
                               ::ctx/sse-push? true
                               ::ctx/track-clients? true}
                        conn (assoc ::ctx/conn conn)
                        render-fn (assoc ::ctx/render-fn render-fn)
                        (schema/registered? spec-key)
                        (assoc ::ctx/ctx-schema spec-key)))]

        (inject-vars! {::ns-sym ns-sym ::ctx-atom ctx-atom ::conn conn})

        {::instance-id iid
         ::ctx-atom ctx-atom}))))

;;; ---------------------------------------------------------------------------
;;; Shutdown / Restore
;;; ---------------------------------------------------------------------------

(defn backup-all-instances!
  "Force-persist all ctx atoms to Datalevin. Called on shutdown.

   Request keys:
     ::conn - Required. Datalevin connection

   Response keys:
     ::backed-up - Number successfully persisted
     ::total     - Total instances"
  {:malli/schema [:=> [:cat ::backup-all-instances-request] ::backup-all-instances-response]}
  [{::keys [conn]}]
  (let [instances (ctx/list-instances {})
        persisted (atom 0)]
    (doseq [{::ctx/keys [instance-id]} instances]
      (try
        (ctx/persist! {::ctx/conn conn ::ctx/instance-id instance-id})
        (swap! persisted inc)
        (catch Exception e
          (log/warn "Failed to backup instance"
                    {:instance-id instance-id :error (.getMessage e)}))))
    (log/info "Backed up ctx instances" {:count @persisted :total (count instances)})
    {::backed-up @persisted ::total (count instances)}))

(defn restore-instances!
  "Restore persisted instances from Datalevin on startup.
   Queries for all persisted ctx instances and recreates valid ones.

   Request keys:
     ::conn - Required. Datalevin connection

   Response keys:
     ::restored - Number successfully restored
     ::total    - Total persisted instances found"
  {:malli/schema [:=> [:cat ::restore-instances-request] ::restore-instances-response]}
  [{::keys [conn]}]
  (let [results (d/q '[:find ?id ?ns ?data
                       :where
                       [?e :seon.ctx/instance-id ?id]
                       [?e :seon.ctx/namespace ?ns]
                       [?e :seon.ctx/data ?data]]
                     @conn)
        restored (atom 0)]
    (doseq [[id ns-str data-str] results]
      (let [ns-sym (symbol ns-str)
            spec-key (::ctx-spec-key (ctx-spec-key {::ns-sym ns-sym}))]
        (try
          (let [data (edn/read-string data-str)]
            (if (and (schema/registered? spec-key)
                     (m/validate (schema/schema-definition spec-key) data))
              (do
                (ensure-instance! {::conn conn
                                   ::ns-sym ns-sym
                                   ::instance-id id})
                (swap! restored inc))
              (log/warn "Skipping invalid persisted instance"
                        {:instance-id id :ns ns-str})))
          (catch Exception e
            (log/warn "Failed to restore instance"
                      {:instance-id id :ns ns-str :error (.getMessage e)})))))
    (log/info "Restored ctx instances" {:count @restored :total (count results)})
    {::restored @restored ::total (count results)}))
