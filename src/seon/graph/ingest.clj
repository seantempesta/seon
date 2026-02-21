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
            [seon.db :as db]
            [seon.graph.analyzer :as analyzer]
            [seon.render :as render]
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
   :seon.fn/render-input-keys {:db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}
   :seon.fn/updated-at     {:db/valueType :db.type/instant}

   ;; Call graph (ref-based: points at :seon.fn/qualified-name entities)
   :seon.call/from-fn  {:db/valueType :db.type/ref}
   :seon.call/to-fn    {:db/valueType :db.type/ref}
   :seon.call/row      {:db/valueType :db.type/long}

   ;; NS dependencies
   :seon.ns.dep/from-ns {:db/valueType :db.type/string}
   :seon.ns.dep/to-ns   {:db/valueType :db.type/string}
   :seon.ns.dep/alias   {:db/valueType :db.type/string}

   ;; Spec/schema entities (from static source scanning)
   :seon.spec/key           {:db/valueType :db.type/keyword :db/unique :db.unique/identity}
   :seon.spec/namespace     {:db/valueType :db.type/string}
   :seon.spec/definition    {:db/valueType :db.type/string}
   :seon.spec/base-type     {:db/valueType :db.type/keyword}
   :seon.spec/contains-keys {:db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}
   :seon.spec/updated-at    {:db/valueType :db.type/instant}

   ;; Function-to-spec links (populated by future phases)
   :seon.fn/input-spec  {:db/valueType :db.type/ref}
   :seon.fn/output-spec {:db/valueType :db.type/ref}})

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

(schema/register! ::spec-count
                  [:int {:min 0 :description "Number of specs ingested"}])

(schema/register! ::specs
                  [:vector {:description "Spec entities from scanner"}
                   [:map
                    [:seon.spec/key :keyword]
                    [:seon.spec/namespace :string]
                    [:seon.spec/definition :string]
                    [:seon.spec/base-type :keyword]
                    [:seon.spec/updated-at inst?]]])

(schema/register! ::ingest-result
                  [:map
                   [::namespace-count ::namespace-count]
                   [::function-count ::function-count]
                   [::var-usage-count ::var-usage-count]
                   [::ns-dependency-count ::ns-dependency-count]
                   [::spec-count {:optional true} ::spec-count]])

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
      (db/transact! conn (mapv (fn [[eid]] [:db/retractEntity eid]) eids)))))

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
      (db/transact! conn (mapv (fn [[eid]] [:db/retractEntity eid]) eids)))))

(defn- retract-calls-from-ns!
  "Retract all call entities originating from functions in a specific namespace.
   Uses ref join: finds calls where from-fn points to a fn entity in the given ns."
  [conn ns-name]
  (let [eids (d/q '[:find ?e
                     :in $ ?ns
                     :where
                     [?fn :seon.fn/namespace ?ns]
                     [?e :seon.call/from-fn ?fn]]
                   @conn ns-name)]
    (when (seq eids)
      (db/transact! conn (mapv (fn [[eid]] [:db/retractEntity eid]) eids)))))

(defn- retract-specs-in-ns!
  "Retract all spec entities for a specific namespace."
  [conn ns-name]
  (let [eids (d/q '[:find ?e
                     :in $ ?ns
                     :where
                     [?e :seon.spec/namespace ?ns]]
                   @conn ns-name)]
    (when (seq eids)
      (db/transact! conn (mapv (fn [[eid]] [:db/retractEntity eid]) eids)))))

(defn- retract-ns-deps-from-ns!
  "Retract all ns-dependency entities originating from a specific namespace."
  [conn ns-name]
  (let [eids (d/q '[:find ?e
                     :in $ ?ns
                     :where
                     [?e :seon.ns.dep/from-ns ?ns]]
                   @conn ns-name)]
    (when (seq eids)
      (db/transact! conn (mapv (fn [[eid]] [:db/retractEntity eid]) eids)))))

(defn- transact-in-batches!
  "Transact entities in batches to avoid overwhelming Datalevin."
  [conn entities batch-size]
  (doseq [batch (partition-all batch-size entities)]
    (db/transact! conn (vec batch))))

(defn- qualified-call?
  "Returns true if both from-fn and to-fn are qualified (contain '/')."
  [call-entity]
  (and (.contains ^String (:seon.call/from-fn call-entity) "/")
       (.contains ^String (:seon.call/to-fn call-entity) "/")))

(defn- call-entity->lookup-refs
  "Convert string-valued :seon.call/from-fn and :seon.call/to-fn to lookup refs."
  [call-entity]
  (-> call-entity
      (update :seon.call/from-fn (fn [s] [:seon.fn/qualified-name s]))
      (update :seon.call/to-fn (fn [s] [:seon.fn/qualified-name s]))))

(defn- compute-stub-entities
  "Given a set of known fn qualified-names and call entities (with string values),
   return stub fn entities for any referenced fns not in the known set."
  [known-qnames call-entities]
  (let [referenced (->> call-entities
                        (mapcat (fn [ce] [(:seon.call/from-fn ce) (:seon.call/to-fn ce)]))
                        (filter #(.contains ^String % "/"))
                        set)
        missing (remove known-qnames referenced)]
    (mapv (fn [qn]
            (let [slash-idx (.indexOf ^String qn "/")]
              (if (pos? slash-idx)
                {:seon.fn/qualified-name qn
                 :seon.fn/namespace (subs qn 0 slash-idx)
                 :seon.fn/name (subs qn (inc slash-idx))
                 :seon.fn/private false}
                {:seon.fn/qualified-name qn
                 :seon.fn/namespace qn
                 :seon.fn/name qn
                 :seon.fn/private false})))
          missing)))

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
     ::specs    - Optional. Spec entities from scanner/scan-directory

   Response keys:
     ::namespace-count     - Number of namespaces ingested
     ::function-count      - Number of functions ingested
     ::var-usage-count     - Number of var-usages ingested
     ::ns-dependency-count - Number of namespace dependencies ingested
     ::spec-count          - Number of specs ingested (0 if none provided)

   Example:
     (ingest-analysis! {::conn conn ::entities entities ::specs specs})"
  [{::keys [conn entities specs]}]
  (let [namespaces (::analyzer/namespaces entities)
        functions (::analyzer/functions entities)
        var-usages (::analyzer/var-usages entities)
        ns-deps (::analyzer/namespace-usages entities)
        ;; Compute affected namespaces from all entity types
        affected-ns-names (into #{}
                                (concat
                                 (map :seon.ns/name namespaces)
                                 (map :seon.fn/namespace functions)
                                 (keep (fn [vu]
                                         (let [from (:seon.call/from-fn vu)]
                                           (if (.contains ^String from "/")
                                             (subs from 0 (.indexOf ^String from "/"))
                                             from)))
                                       var-usages)
                                 (map :seon.ns.dep/from-ns ns-deps)
                                 (map :seon.spec/namespace specs)))]
    ;; Clear existing graph data per-namespace (calls before fns since calls hold refs)
    (log/debug "Retracting graph data for affected namespaces..." {:count (count affected-ns-names)})
    (doseq [ns-name affected-ns-names]
      (retract-calls-from-ns! conn ns-name)
      (retract-functions-in-ns! conn ns-name)
      (retract-ns-deps-from-ns! conn ns-name)
      (retract-specs-in-ns! conn ns-name))

    ;; Ingest new data in order: namespaces, specs, functions+stubs, calls (with lookup refs)
    (log/debug "Ingesting namespaces..." {:count (count namespaces)})
    (transact-in-batches! conn namespaces batch-size)

    ;; Ingest specs BEFORE functions (functions reference specs via lookup refs)
    (when (seq specs)
      (log/debug "Ingesting specs..." {:count (count specs)})
      (transact-in-batches! conn specs batch-size))

    ;; Filter to qualified calls only, create stubs, convert to lookup refs
    (let [qualified-usages (filterv qualified-call? var-usages)
          known-qnames (into #{} (map :seon.fn/qualified-name) functions)
          stubs (compute-stub-entities known-qnames qualified-usages)
          all-fns (into (vec functions) stubs)]
      (log/debug "Ingesting functions..." {:count (count all-fns) :stubs (count stubs)})
      (transact-in-batches! conn all-fns batch-size)

      ;; Convert calls to use lookup refs
      (let [ref-calls (mapv call-entity->lookup-refs qualified-usages)]
        (log/debug "Ingesting var-usages..." {:count (count ref-calls)})
        (transact-in-batches! conn ref-calls batch-size))

      (log/debug "Ingesting ns-dependencies..." {:count (count ns-deps)})
      (transact-in-batches! conn ns-deps batch-size)

      (let [result {::namespace-count (count namespaces)
                    ::function-count (count all-fns)
                    ::var-usage-count (count qualified-usages)
                    ::ns-dependency-count (count ns-deps)
                    ::spec-count (count (or specs []))}]
        (log/info "Knowledge graph ingestion complete" result)
        (render/invalidate-render-cache!)
        result))))

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
     ::specs    - Optional. Spec entities from scanner/scan-file

   Response keys:
     ::namespace-count     - Number of namespaces ingested
     ::function-count      - Number of functions ingested
     ::var-usage-count     - Number of var-usages ingested
     ::ns-dependency-count - Number of namespace dependencies ingested
     ::spec-count          - Number of specs ingested (0 if none provided)

   Example:
     (ingest-incremental! {::conn conn ::entities entities ::specs specs})"
  [{::keys [conn entities specs]}]
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
                                 (map :seon.ns.dep/from-ns ns-deps)
                                 (map :seon.spec/namespace specs)))]
    ;; Retract existing data for affected namespaces only
    ;; Retract calls BEFORE functions (calls use ref joins on fn entities)
    (doseq [ns-name affected-ns-names]
      (retract-calls-from-ns! conn ns-name)
      (retract-functions-in-ns! conn ns-name)
      (retract-ns-deps-from-ns! conn ns-name)
      (retract-specs-in-ns! conn ns-name))

    ;; Upsert namespace entities (identity attr :seon.ns/name handles upsert)
    (when (seq namespaces)
      (db/transact! conn (vec namespaces)))

    ;; Insert new functions + stubs (identity attr handles upsert)
    (let [qualified-usages (filterv qualified-call? var-usages)
          known-qnames (into #{} (map :seon.fn/qualified-name) functions)
          stubs (compute-stub-entities known-qnames qualified-usages)
          all-fns (into (vec functions) stubs)]
      (when (seq all-fns)
        (db/transact! conn (vec all-fns)))

      ;; Convert calls to lookup refs and transact
      (when (seq qualified-usages)
        (let [ref-calls (mapv call-entity->lookup-refs qualified-usages)]
          (transact-in-batches! conn ref-calls batch-size)))
      (when (seq ns-deps)
        (db/transact! conn (vec ns-deps)))

      (when (seq specs)
        (db/transact! conn (vec specs)))

      (render/invalidate-render-cache!)
      {::namespace-count (count namespaces)
       ::function-count (count all-fns)
       ::var-usage-count (count qualified-usages)
       ::ns-dependency-count (count ns-deps)
       ::spec-count (count (or specs []))})))

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
