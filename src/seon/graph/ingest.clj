(ns seon.graph.ingest
  "Ingest analysis data into the Datalevin knowledge graph.

   Transforms clj-kondo analysis output (via seon.graph.analyzer/extract-entities)
   into Datalevin entities and transacts them into the master database.

   Two ingestion modes:
   - Bulk: ingest-analysis! for full project analysis at startup
   - Incremental: ingest-incremental! for single-form updates during agent work

   Entity types stored:
   - :namespace  - Namespace definitions with file paths and docs
   - :function   - Function definitions with arglists, visibility, line numbers
   - :ns-dependency - Namespace require/use edges
   - :var-usage  - Function call edges (who calls what)

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

(defn- retract-by-type!
  "Retract all entities of a given :graph/type from Datalevin.
   Used before bulk re-ingestion to avoid stale data."
  [conn graph-type]
  (let [eids (d/q '[:find ?e
                     :in $ ?type
                     :where
                     [?e :graph/type ?type]]
                   @conn graph-type)]
    (when (seq eids)
      (d/transact! conn (mapv (fn [[eid]] [:db/retractEntity eid]) eids)))))

(defn- retract-functions-in-ns!
  "Retract all function entities for a specific namespace.
   Used during incremental updates to replace stale definitions."
  [conn ns-name]
  (let [eids (d/q '[:find ?e
                     :in $ ?ns
                     :where
                     [?e :graph/type :function]
                     [?e :graph/ns ?ns]]
                   @conn ns-name)]
    (when (seq eids)
      (d/transact! conn (mapv (fn [[eid]] [:db/retractEntity eid]) eids)))))

(defn- retract-var-usages-from-ns!
  "Retract all var-usage entities originating from a specific namespace.
   Used during incremental updates."
  [conn ns-name]
  (let [eids (d/q '[:find ?e
                     :in $ ?ns
                     :where
                     [?e :graph/type :var-usage]
                     [?e :graph/from-ns ?ns]]
                   @conn ns-name)]
    (when (seq eids)
      (d/transact! conn (mapv (fn [[eid]] [:db/retractEntity eid]) eids)))))

(defn- retract-ns-deps-from-ns!
  "Retract all ns-dependency entities originating from a specific namespace."
  [conn ns-name]
  (let [eids (d/q '[:find ?e
                     :in $ ?ns
                     :where
                     [?e :graph/type :ns-dependency]
                     [?e :graph/from-ns ?ns]]
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
    (retract-by-type! conn :namespace)
    (retract-by-type! conn :function)
    (retract-by-type! conn :var-usage)
    (retract-by-type! conn :ns-dependency)

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
   Var-usages and ns-dependencies from the affected namespace are also replaced.

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
                                 (map :graph/name namespaces)
                                 (map :graph/ns functions)
                                 (map :graph/from-ns var-usages)
                                 (map :graph/from-ns ns-deps)))]
    ;; Retract existing data for affected namespaces only
    (doseq [ns-name affected-ns-names]
      (retract-functions-in-ns! conn ns-name)
      (retract-var-usages-from-ns! conn ns-name)
      (retract-ns-deps-from-ns! conn ns-name))

    ;; Upsert namespace entities (by name)
    (doseq [ns-entity namespaces]
      (let [ns-name (:graph/name ns-entity)
            existing (d/q '[:find ?e
                            :in $ ?name
                            :where
                            [?e :graph/type :namespace]
                            [?e :graph/name ?name]]
                          @conn ns-name)]
        (if (seq existing)
          ;; Update existing namespace
          (d/transact! conn [(assoc ns-entity :db/id (ffirst existing))])
          ;; Insert new namespace
          (d/transact! conn [ns-entity]))))

    ;; Insert new functions, var-usages, ns-dependencies
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
  (d/q '[:find ?name :where [?e :graph/type :namespace] [?e :graph/name ?name]]
       @dl-conn)

  nil)
