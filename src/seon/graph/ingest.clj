(ns seon.graph.ingest
  "Ingest analysis data into the Datalevin knowledge graph.

   Transforms clj-kondo analysis output (via seon.graph.analyzer/extract-entities)
   into Datalevin entities and transacts them into the master database.

   Two ingestion modes:
   - Bulk: ingest-analysis! for full project analysis at startup
   - Incremental: ingest-incremental! for single-form updates during agent work

   Entity types stored (discriminated by unique identity attrs, no :graph/type):
   - :seon.ns/*       - Namespace definitions (identity: :seon.ns/name)
   - :seon.fn/*       - Function definitions (identity: :seon.fn/qualified-name)
   - :seon.call/*     - Function call edges
   - :seon.ns.dep/*   - Namespace dependency edges

   Example:
     (require '[seon.graph.ingest :as ingest])
     (require '[seon.graph.analyzer :as analyzer])

     ;; Bulk ingest from project analysis
     (let [analysis (analyzer/analyze-project! {})
           entities (analyzer/extract-entities {::analyzer/raw-analysis (::analyzer/raw-analysis analysis)})]
       (ingest/ingest-analysis! {::conn my-conn ::entities entities}))

     ;; Incremental ingest from a single form
     (let [analysis (analyzer/analyze-form {::analyzer/source \"(defn foo [x] x)\"})
           entities (analyzer/extract-entities {::analyzer/raw-analysis (::analyzer/raw-analysis analysis)})]
       (ingest/ingest-incremental! {::conn my-conn ::entities entities}))"
  (:require [datalevin.core :as d]
            [seon.graph.analyzer :as analyzer]
            [seon.schema :as schema]
            [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Datalevin Schema
;;; ---------------------------------------------------------------------------

(def datalevin-schema
  "Schema for the knowledge graph in Datalevin."
  {;; Namespace entities
   :seon.ns/name       {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :seon.ns/doc        {:db/valueType :db.type/string}
   :seon.ns/file       {:db/valueType :db.type/string}
   :seon.ns/target     {:db/valueType :db.type/keyword}

   ;; Function entities
   :seon.fn/qualified-name {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :seon.fn/namespace      {:db/valueType :db.type/string}
   :seon.fn/name           {:db/valueType :db.type/string}
   :seon.fn/doc            {:db/valueType :db.type/string}
   :seon.fn/arglists       {:db/valueType :db.type/string}
   :seon.fn/row            {:db/valueType :db.type/long}
   :seon.fn/private        {:db/valueType :db.type/boolean}

   ;; Call graph (strings for now, refs in A2)
   :seon.call/from-fn  {:db/valueType :db.type/string}
   :seon.call/to-fn    {:db/valueType :db.type/string}
   :seon.call/row      {:db/valueType :db.type/long}

   ;; NS dependencies
   :seon.ns.dep/from-ns {:db/valueType :db.type/string}
   :seon.ns.dep/to-ns   {:db/valueType :db.type/string}
   :seon.ns.dep/alias   {:db/valueType :db.type/string}})

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::conn
                  [:any {:description "Datalevin connection"}])

(schema/register! ::entities
                  [:map {:description "Extracted entities from analyzer"}
                   [::analyzer/namespaces ::analyzer/namespaces]
                   [::analyzer/functions ::analyzer/functions]
                   [::analyzer/var-usages ::analyzer/var-usages]
                   [::analyzer/namespace-usages ::analyzer/namespace-usages]])

(schema/register! ::namespace-count
                  [:int {:min 0 :description "Number of namespaces ingested"}])

(schema/register! ::function-count
                  [:int {:min 0 :description "Number of functions ingested"}])

(schema/register! ::var-usage-count
                  [:int {:min 0 :description "Number of var-usages ingested"}])

(schema/register! ::ns-dependency-count
                  [:int {:min 0 :description "Number of ns-dependencies ingested"}])

(schema/register! ::ingest-result
                  [:map
                   [::namespace-count ::namespace-count]
                   [::function-count ::function-count]
                   [::var-usage-count ::var-usage-count]
                   [::ns-dependency-count ::ns-dependency-count]])

;;; ---------------------------------------------------------------------------
;;; Private Implementation
;;; ---------------------------------------------------------------------------

(defn- retract-all-with-attr!
  "Retract all entities that have the given attribute.
   Used before bulk re-ingestion to avoid stale data."
  [conn attr]
  (let [eids (d/q (list :find '?e
                        :where ['?e attr])
                  @conn)]
    (when (seq eids)
      (d/transact! conn (mapv (fn [[eid]] [:db/retractEntity eid]) eids)))))

(defn- retract-functions-in-ns!
  "Retract all function entities for a specific namespace.
   Used during incremental updates to replace stale definitions."
  [conn ns-name]
  (let [eids (d/q '[:find ?e
                     :in $ ?ns
                     :where
                     [?e :seon.fn/namespace ?ns]]
                   @conn ns-name)]
    (when (seq eids)
      (d/transact! conn (mapv (fn [[eid]] [:db/retractEntity eid]) eids)))))

(defn- retract-calls-from-ns!
  "Retract all call entities originating from functions in a specific namespace.
   Used during incremental updates."
  [conn ns-name]
  (let [prefix (str ns-name "/")
        ;; Find calls where from-fn starts with ns-name/ or equals ns-name (top-level)
        eids (d/q '[:find ?e ?from
                     :where
                     [?e :seon.call/from-fn ?from]]
                   @conn)
        matching (filter (fn [[_ from]]
                           (or (= from ns-name)
                               (.startsWith ^String from prefix)))
                         eids)]
    (when (seq matching)
      (d/transact! conn (mapv (fn [[eid _]] [:db/retractEntity eid]) matching)))))

(defn- retract-ns-deps-from-ns!
  "Retract all ns-dependency entities originating from a specific namespace."
  [conn ns-name]
  (let [eids (d/q '[:find ?e
                     :in $ ?ns
                     :where
                     [?e :seon.ns.dep/from-ns ?ns]]
                   @conn ns-name)]
    (when (seq eids)
      (d/transact! conn (mapv (fn [[eid]] [:db/retractEntity eid]) eids)))))

(defn- transact-in-batches!
  "Transact entities in batches to avoid overwhelming Datalevin."
  [conn entities batch-size]
  (doseq [batch (partition-all batch-size entities)]
    (d/transact! conn (vec batch))))

(def ^:const batch-size
  "Entities per Datalevin transaction.
   Tuned for memory vs latency tradeoff on typical project analysis."
  500)

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn ingest-analysis!
  "Bulk ingest extracted entities into Datalevin.

   Clears existing graph data and replaces with fresh analysis.
   Use this for initial graph population at startup.

   Note: No :malli/schema - conn is a runtime object that cannot be generated.

   Request keys:
     ::conn     - Required. Datalevin connection (runtime object)
     ::entities - Required. Extracted entities from analyzer/extract-entities

   Response keys:
     ::namespace-count     - Number of namespaces ingested
     ::function-count      - Number of functions ingested
     ::var-usage-count     - Number of var-usages ingested
     ::ns-dependency-count - Number of namespace dependencies ingested

   Example:
     (ingest-analysis! {::conn conn ::entities entities})"
  [{::keys [conn entities]}]
  (let [namespaces (::analyzer/namespaces entities)
        functions (::analyzer/functions entities)
        var-usages (::analyzer/var-usages entities)
        ns-deps (::analyzer/namespace-usages entities)]
    ;; Clear existing graph data
    (log/debug "Clearing existing graph data...")
    (retract-all-with-attr! conn :seon.ns/name)
    (retract-all-with-attr! conn :seon.fn/qualified-name)
    (retract-all-with-attr! conn :seon.call/from-fn)
    (retract-all-with-attr! conn :seon.ns.dep/from-ns)

    ;; Ingest new data in batches
    (log/debug "Ingesting namespaces..." {:count (count namespaces)})
    (transact-in-batches! conn namespaces batch-size)

    (log/debug "Ingesting functions..." {:count (count functions)})
    (transact-in-batches! conn functions batch-size)

    (log/debug "Ingesting var-usages..." {:count (count var-usages)})
    (transact-in-batches! conn var-usages batch-size)

    (log/debug "Ingesting ns-dependencies..." {:count (count ns-deps)})
    (transact-in-batches! conn ns-deps batch-size)

    (let [result {::namespace-count (count namespaces)
                  ::function-count (count functions)
                  ::var-usage-count (count var-usages)
                  ::ns-dependency-count (count ns-deps)}]
      (log/info "Knowledge graph ingestion complete" result)
      result)))

(defn ingest-incremental!
  "Incrementally ingest entities for a single form/namespace change.

   Replaces existing entities for the affected namespace(s) and adds
   new ones. This is used after each agent eval to keep the graph current.

   For namespaces and functions, retract-then-insert ensures no stale data.
   Call entities and ns-dependencies from the affected namespace are also replaced.

   Note: No :malli/schema - conn is a runtime object that cannot be generated.

   Request keys:
     ::conn     - Required. Datalevin connection (runtime object)
     ::entities - Required. Extracted entities from analyzer/extract-entities

   Response keys:
     ::namespace-count     - Number of namespaces ingested
     ::function-count      - Number of functions ingested
     ::var-usage-count     - Number of var-usages ingested
     ::ns-dependency-count - Number of namespace dependencies ingested

   Example:
     (ingest-incremental! {::conn conn ::entities entities})"
  [{::keys [conn entities]}]
  (let [namespaces (::analyzer/namespaces entities)
        functions (::analyzer/functions entities)
        var-usages (::analyzer/var-usages entities)
        ns-deps (::analyzer/namespace-usages entities)
        ;; Determine affected namespaces for targeted retraction
        affected-ns-names (into #{}
                                (concat
                                 (map :seon.ns/name namespaces)
                                 (map :seon.fn/namespace functions)
                                 (keep (fn [vu]
                                         (let [from (:seon.call/from-fn vu)]
                                           ;; Extract ns from "ns/fn" or just "ns"
                                           (if (.contains ^String from "/")
                                             (subs from 0 (.indexOf ^String from "/"))
                                             from)))
                                       var-usages)
                                 (map :seon.ns.dep/from-ns ns-deps)))]
    ;; Retract existing data for affected namespaces only
    (doseq [ns-name affected-ns-names]
      (retract-functions-in-ns! conn ns-name)
      (retract-calls-from-ns! conn ns-name)
      (retract-ns-deps-from-ns! conn ns-name))

    ;; Upsert namespace entities (identity attr :seon.ns/name handles upsert)
    (when (seq namespaces)
      (d/transact! conn (vec namespaces)))

    ;; Insert new functions (identity attr :seon.fn/qualified-name handles upsert)
    (when (seq functions)
      (d/transact! conn (vec functions)))
    (when (seq var-usages)
      (transact-in-batches! conn var-usages batch-size))
    (when (seq ns-deps)
      (d/transact! conn (vec ns-deps)))

    {::namespace-count (count namespaces)
     ::function-count (count functions)
     ::var-usage-count (count var-usages)
     ::ns-dependency-count (count ns-deps)}))

;;; ---------------------------------------------------------------------------
;;; REPL Helpers
;;; ---------------------------------------------------------------------------

(comment
  ;; Requires running system with Datalevin
  (require '[integrant.repl.state :as state])
  (require '[seon.db.datalevin.conn :as conn])

  ;; Get connection
  (def mgr (:seon/connection-manager state/system))
  (def dl-conn (conn/get-master-conn!
                {:seon.db.datalevin.conn/manager mgr}))

  ;; Full project analysis + ingest
  (def project (analyzer/analyze-project! {}))
  (def entities (analyzer/extract-entities
                 {::analyzer/raw-analysis (::analyzer/raw-analysis project)}))
  (ingest-analysis! {::conn dl-conn ::entities entities})

  ;; Incremental form analysis + ingest
  (def form-result
    (analyzer/analyze-form
     {::analyzer/source "(defn my-test [x] (+ x 1))"}))
  (def form-entities
    (analyzer/extract-entities
     {::analyzer/raw-analysis (::analyzer/raw-analysis form-result)}))
  (ingest-incremental! {::conn dl-conn ::entities form-entities})

  ;; Check what's in the graph
  (d/q '[:find ?name :where [?e :seon.ns/name ?name]]
       @dl-conn)

  nil)
