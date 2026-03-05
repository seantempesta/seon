(ns seon.graph.ingest
  "Ingest analysis data into the Datalevin knowledge graph.

   Transforms clj-kondo analysis output (via seon.graph.analyzer/extract-entities)
   into Datalevin entities and transacts them into the master database.

   Uses upsert + retract-stale pattern: identity attrs handle create-or-update,
   then entities present in the old scan but absent from the new scan are retracted.
   This prevents data loss when a scan is incomplete.

   Entity types stored (discriminated by unique identity attrs, no :graph/type):
   - :seon.ns/*       - Namespace definitions (identity: :seon.ns/name)
   - :seon.fn/*       - Function definitions (identity: :seon.fn/qualified-name)
   - :seon.var/*      - Var definitions (identity: :seon.var/qualified-name)
   - :seon.call/*     - Function call edges
   - :seon.ns.dep/*   - Namespace dependency edges
   - :seon.spec/*     - Schema/spec entities (identity: :seon.spec/key)

   Example:
     (require '[seon.graph.ingest :as ingest])
     (require '[seon.graph.analyzer :as analyzer])

     ;; Bulk ingest from project analysis
     (let [analysis (analyzer/analyze-project! {})
           entities (analyzer/extract-entities {::analyzer/raw-analysis (::analyzer/raw-analysis analysis)})]
       (ingest/ingest-analysis! {::db-name :seon.runtime ::entities entities}))

     ;; Incremental ingest for a single namespace
     (ingest/ingest-namespace! {::db-name :seon.runtime ::ns-name \"seon.foo\"
                                ::functions fns ::specs specs ::vars vars})"
  (:require [seon.db :as db]
            [seon.db.schema :as db-schema]
            [seon.graph.analyzer :as analyzer]
            [seon.render :as render]
            [seon.schema :as schema]
            [taoensso.timbre :as log]))

;;; ---------------------------------------------------------------------------
;;; Datalevin Entity Schemas (Malli is the source of truth)
;;; ---------------------------------------------------------------------------

(def ns-entity-schema
  "Malli schema for a namespace entity.
   Identity: :seon.ns/name. All persisted attrs have concrete types."
  [:map
   [:seon.ns/name {:db/unique :db.unique/identity} :string]
   [:seon.ns/doc {:optional true} :string]
   [:seon.ns/file {:optional true} :string]
   [:seon.ns/target {:optional true} :keyword]
   [:seon.ns/dynamic? {:optional true} :boolean]])

(def fn-entity-schema
  "Malli schema for a function entity.
   Identity: :seon.fn/qualified-name. Ref fields (:input-spec, :output-spec)
   point at :seon.spec/key entities via lookup refs at transact time."
  [:map
   [:seon.fn/qualified-name {:db/unique :db.unique/identity} :string]
   [:seon.fn/namespace :string]
   [:seon.fn/name :string]
   [:seon.fn/doc {:optional true} :string]
   [:seon.fn/arglists {:optional true} :string]
   [:seon.fn/row {:optional true} :int]
   [:seon.fn/private :boolean]
   [:seon.fn/updated-at {:optional true} :inst]
   [:seon.fn/input-spec {:optional true} :seon.db/ref]
   [:seon.fn/output-spec {:optional true} :seon.db/ref]])

(def call-entity-schema
  "Malli schema for a call graph edge entity.
   No identity key (retract-then-insert). Ref fields point at
   :seon.fn/qualified-name entities via lookup refs."
  [:map
   [:seon.call/from-fn :seon.db/ref]
   [:seon.call/to-fn :seon.db/ref]
   [:seon.call/row {:optional true} :int]])

(def ns-dep-entity-schema
  "Malli schema for a namespace dependency edge entity.
   No identity key (retract-then-insert)."
  [:map
   [:seon.ns.dep/from-ns :string]
   [:seon.ns.dep/to-ns :string]
   [:seon.ns.dep/alias {:optional true} :string]])

(def spec-entity-schema
  "Malli schema for a spec/schema entity.
   Identity: :seon.spec/key. Cardinality-many fields use [:vector :keyword]."
  [:map
   [:seon.spec/key {:db/unique :db.unique/identity} :keyword]
   [:seon.spec/namespace :string]
   [:seon.spec/definition :string]
   [:seon.spec/base-type :keyword]
   [:seon.spec/contains-keys {:optional true} [:vector :keyword]]
   [:seon.spec/optional-keys {:optional true} [:vector :keyword]]
   [:seon.spec/references {:optional true} [:vector :keyword]]
   [:seon.spec/updated-at :inst]])

(def var-entity-schema
  "Malli schema for a var entity (def, not defn).
   Identity: :seon.var/qualified-name."
  [:map
   [:seon.var/qualified-name {:db/unique :db.unique/identity} :string]
   [:seon.var/namespace :string]
   [:seon.var/name :string]
   [:seon.var/doc {:optional true} :string]
   [:seon.var/row {:optional true} :int]
   [:seon.var/private :boolean]
   [:seon.var/value-type {:optional true} :keyword]
   [:seon.var/updated-at {:optional true} :inst]])

(db-schema/register-entity-schema! "seon.ns" ns-entity-schema)
(db-schema/register-entity-schema! "seon.fn" fn-entity-schema)
(db-schema/register-entity-schema! "seon.call" call-entity-schema)
(db-schema/register-entity-schema! "seon.ns.dep" ns-dep-entity-schema)
(db-schema/register-entity-schema! "seon.spec" spec-entity-schema)
(db-schema/register-entity-schema! "seon.var" var-entity-schema)

(def datalevin-schema
  "Schema for the knowledge graph in Datalevin. Derived from Malli entity schemas."
  (merge (db-schema/malli-map->datalevin-schema ns-entity-schema)
         (db-schema/malli-map->datalevin-schema fn-entity-schema)
         (db-schema/malli-map->datalevin-schema call-entity-schema)
         (db-schema/malli-map->datalevin-schema ns-dep-entity-schema)
         (db-schema/malli-map->datalevin-schema spec-entity-schema)
         (db-schema/malli-map->datalevin-schema var-entity-schema)))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

;; Datalevin entity attributes (registered so db/transact! enforcement passes)
;; Namespace entity attrs
(schema/register! :seon.ns/name :string)
(schema/register! :seon.ns/file :string)
(schema/register! :seon.ns/doc :string)
(schema/register! :seon.ns/target :keyword)
(schema/register! :seon.ns/dynamic? :boolean)

;; Function entity attrs
(schema/register! :seon.fn/qualified-name :string)
(schema/register! :seon.fn/namespace :string)
(schema/register! :seon.fn/name :string)
(schema/register! :seon.fn/doc :string)
(schema/register! :seon.fn/arglists :string)
(schema/register! :seon.fn/row :int)
(schema/register! :seon.fn/private :boolean)
(schema/register! :seon.fn/updated-at :inst)
(schema/register! :seon.fn/input-spec :seon.db/ref)
(schema/register! :seon.fn/output-spec :seon.db/ref)

;; Var entity attrs
(schema/register! :seon.var/qualified-name :string)
(schema/register! :seon.var/namespace :string)
(schema/register! :seon.var/name :string)
(schema/register! :seon.var/doc :string)
(schema/register! :seon.var/row :int)
(schema/register! :seon.var/private :boolean)
(schema/register! :seon.var/value-type :keyword)
(schema/register! :seon.var/updated-at :inst)

;; Call graph attrs
(schema/register! :seon.call/from-fn :seon.db/ref)
(schema/register! :seon.call/to-fn :seon.db/ref)
(schema/register! :seon.call/row :int)

;; NS dependency attrs
(schema/register! :seon.ns.dep/from-ns :string)
(schema/register! :seon.ns.dep/to-ns :string)
(schema/register! :seon.ns.dep/alias :string)

;; Spec/schema attrs
(schema/register! :seon.spec/key :keyword)
(schema/register! :seon.spec/namespace :string)
(schema/register! :seon.spec/definition :string)
(schema/register! :seon.spec/base-type :keyword)
(schema/register! :seon.spec/contains-keys [:vector :keyword])
(schema/register! :seon.spec/optional-keys [:vector :keyword])
(schema/register! :seon.spec/references [:vector :keyword])
(schema/register! :seon.spec/updated-at :inst)

(schema/register! ::db-name
                  [:keyword {:description "Database name keyword (e.g. :seon.runtime)"}])

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

(schema/register! ::var-count
                  [:int {:min 0 :description "Number of vars ingested"}])

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
                    [:seon.spec/updated-at :inst]]])

(schema/register! ::ingest-result
                  [:map
                   [::namespace-count ::namespace-count]
                   [::function-count ::function-count]
                   [::var-usage-count ::var-usage-count]
                   [::ns-dependency-count ::ns-dependency-count]
                   [::spec-count {:optional true} ::spec-count]
                   [::var-count {:optional true} ::var-count]])

;;; ---------------------------------------------------------------------------
;;; Private Implementation - Retract Stale
;;; ---------------------------------------------------------------------------

(defn- retract-stale-fns!
  "Retract functions in namespace not present in new scan."
  [db-name ns-str new-qualified-names]
  (let [existing (db/query db-name
                           '[:find ?e ?qn
                             :in $ ?ns
                             :where
                             [?e :seon.fn/namespace ?ns]
                             [?e :seon.fn/qualified-name ?qn]]
                           ns-str)
        new-set (set new-qualified-names)
        stale (remove (fn [[_ qn]] (new-set qn)) existing)]
    (when (seq stale)
      (db/transact! db-name (mapv (fn [[eid _]] [:db/retractEntity eid]) stale)))))

(defn- retract-stale-specs!
  "Retract specs in namespace not present in new scan."
  [db-name ns-str new-spec-keys]
  (let [existing (db/query db-name
                           '[:find ?e ?k
                             :in $ ?ns
                             :where
                             [?e :seon.spec/namespace ?ns]
                             [?e :seon.spec/key ?k]]
                           ns-str)
        new-set (set new-spec-keys)
        stale (remove (fn [[_ k]] (new-set k)) existing)]
    (when (seq stale)
      (db/transact! db-name (mapv (fn [[eid _]] [:db/retractEntity eid]) stale)))))

(defn- retract-stale-vars!
  "Retract vars in namespace not present in new scan."
  [db-name ns-str new-qualified-names]
  (let [existing (db/query db-name
                           '[:find ?e ?qn
                             :in $ ?ns
                             :where
                             [?e :seon.var/namespace ?ns]
                             [?e :seon.var/qualified-name ?qn]]
                           ns-str)
        new-set (set new-qualified-names)
        stale (remove (fn [[_ qn]] (new-set qn)) existing)]
    (when (seq stale)
      (db/transact! db-name (mapv (fn [[eid _]] [:db/retractEntity eid]) stale)))))

(defn- retract-calls-from-ns!
  "Retract all call entities originating from functions in a specific namespace.
   Call edges lack identity attrs, so retract-then-insert is fine."
  [db-name ns-str]
  (let [eids (db/query db-name
                       '[:find ?e
                         :in $ ?ns
                         :where
                         [?fn :seon.fn/namespace ?ns]
                         [?e :seon.call/from-fn ?fn]]
                       ns-str)]
    (when (seq eids)
      (db/transact! db-name (mapv (fn [[eid]] [:db/retractEntity eid]) eids)))))

(defn- retract-ns-deps-from-ns!
  "Retract all ns-dependency entities originating from a specific namespace.
   NS-dep edges lack identity attrs, so retract-then-insert is fine."
  [db-name ns-str]
  (let [eids (db/query db-name
                       '[:find ?e
                         :in $ ?ns
                         :where
                         [?e :seon.ns.dep/from-ns ?ns]]
                       ns-str)]
    (when (seq eids)
      (db/transact! db-name (mapv (fn [[eid]] [:db/retractEntity eid]) eids)))))

;;; ---------------------------------------------------------------------------
;;; Private Implementation - Helpers
;;; ---------------------------------------------------------------------------

(defn- transact-in-batches!
  "Transact entities in batches to avoid overwhelming Datalevin."
  [db-name entities batch-size]
  (doseq [batch (partition-all batch-size entities)]
    (db/transact! db-name (vec batch))))

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

(defn ingest-namespace!
  "Ingest entities for a single namespace using upsert + retract-stale.

   Identity attrs (:seon.fn/qualified-name, :seon.spec/key, :seon.var/qualified-name)
   handle create-or-update automatically. Only entities in the old scan but absent
   from the new scan are retracted, preventing data loss from incomplete scans.

   Call edges and ns-deps use retract-then-insert (they lack identity attrs).

   Request keys:
     ::db-name     - Required. Database name keyword (e.g. :seon.runtime)
     ::ns-name     - Required. Namespace name string
     ::functions   - Optional. Function entities with :seon.fn/* keys
     ::specs       - Optional. Spec entities with :seon.spec/* keys
     ::vars        - Optional. Var entities with :seon.var/* keys
     ::call-edges  - Optional. Call graph edges with :seon.call/* keys
     ::ns-deps     - Optional. Namespace dependencies with :seon.ns.dep/* keys
     ::ns-entities - Optional. Namespace entity maps with :seon.ns/* keys

   Returns map with counts of ingested entities."
  [{::keys [db-name functions specs vars call-edges ns-deps ns-entities]
    ns-str ::ns-name}]
  (when ns-str
    ;; 1. Upsert namespace entities
    (when (seq ns-entities)
      (db/transact! db-name (vec ns-entities)))

    ;; 2. Upsert specs BEFORE functions (functions reference specs via lookup refs)
    (when (seq specs)
      (db/transact! db-name (vec specs)))

    ;; 3. Upsert functions + stubs for call graph targets
    (let [qualified-usages (filterv qualified-call? (or call-edges []))
          known-qnames (into #{} (map :seon.fn/qualified-name) (or functions []))
          stubs (compute-stub-entities known-qnames qualified-usages)
          all-fns (into (vec (or functions [])) stubs)]
      (when (seq all-fns)
        (db/transact! db-name (vec all-fns)))

      ;; 4. Upsert vars
      (when (seq vars)
        (db/transact! db-name (vec vars)))

      ;; 5. Retract stale entities (in old scan but not in new)
      (retract-stale-fns! db-name ns-str (map :seon.fn/qualified-name all-fns))
      (retract-stale-specs! db-name ns-str (map :seon.spec/key specs))
      (retract-stale-vars! db-name ns-str (map :seon.var/qualified-name vars))

      ;; 6. Call edges + ns-deps: retract-then-insert (no identity attrs)
      (retract-calls-from-ns! db-name ns-str)
      (when (seq qualified-usages)
        (let [ref-calls (mapv call-entity->lookup-refs qualified-usages)]
          (transact-in-batches! db-name ref-calls batch-size)))

      (retract-ns-deps-from-ns! db-name ns-str)
      (when (seq ns-deps)
        (db/transact! db-name (vec ns-deps)))

      (render/invalidate-render-cache!)
      {::namespace-count (count (or ns-entities []))
       ::function-count (count all-fns)
       ::var-usage-count (count qualified-usages)
       ::ns-dependency-count (count (or ns-deps []))
       ::spec-count (count (or specs []))
       ::var-count (count (or vars []))})))

(defn ingest-analysis!
  "Bulk ingest extracted entities into Datalevin.

   Uses upsert + retract-stale per namespace. Safe for initial graph population
   at startup -- entities not in the new scan are retracted, but entities the
   analyzer finds are upserted (not deleted and re-created).

   Request keys:
     ::db-name  - Required. Database name keyword (e.g. :seon.runtime)
     ::entities - Required. Extracted entities from analyzer/extract-entities
     ::specs    - Optional. Spec entities from scanner/scan-directory
     ::vars     - Optional. Var entities from scanner

   Response keys:
     ::namespace-count     - Number of namespaces ingested
     ::function-count      - Number of functions ingested
     ::var-usage-count     - Number of var-usages ingested
     ::ns-dependency-count - Number of namespace dependencies ingested
     ::spec-count          - Number of specs ingested (0 if none provided)
     ::var-count           - Number of vars ingested (0 if none provided)

   Example:
     (ingest-analysis! {::db-name :seon.runtime ::entities entities ::specs specs})"
  [{::keys [db-name entities specs vars]}]
  (let [namespaces (::analyzer/namespaces entities)
        functions (::analyzer/functions entities)
        var-usages (::analyzer/var-usages entities)
        ns-deps (::analyzer/namespace-usages entities)
        ;; Group entities by namespace for per-ns upsert
        fns-by-ns (group-by :seon.fn/namespace functions)
        specs-by-ns (group-by :seon.spec/namespace specs)
        vars-by-ns (group-by :seon.var/namespace vars)
        ns-deps-by-ns (group-by :seon.ns.dep/from-ns ns-deps)
        calls-by-ns (group-by (fn [vu]
                                 (let [from (:seon.call/from-fn vu)]
                                   (if (.contains ^String from "/")
                                     (subs from 0 (.indexOf ^String from "/"))
                                     from)))
                               var-usages)
        ns-entities-by-ns (group-by :seon.ns/name namespaces)
        ;; All affected namespaces
        all-ns-names (into #{}
                           (concat (keys fns-by-ns) (keys specs-by-ns)
                                   (keys vars-by-ns) (keys ns-deps-by-ns)
                                   (keys calls-by-ns) (keys ns-entities-by-ns)))
        ;; Accumulate counts
        totals (atom {::namespace-count 0 ::function-count 0
                      ::var-usage-count 0 ::ns-dependency-count 0
                      ::spec-count 0 ::var-count 0})]
    (log/debug "Ingesting graph data for namespaces..." {:count (count all-ns-names)})
    (doseq [ns-str all-ns-names]
      (when-let [result (ingest-namespace!
                          {::db-name db-name
                           ::ns-name ns-str
                           ::functions (get fns-by-ns ns-str)
                           ::specs (get specs-by-ns ns-str)
                           ::vars (get vars-by-ns ns-str)
                           ::call-edges (get calls-by-ns ns-str)
                           ::ns-deps (get ns-deps-by-ns ns-str)
                           ::ns-entities (get ns-entities-by-ns ns-str)})]
        (swap! totals (fn [t]
                        (merge-with + t (select-keys result
                                                     [::namespace-count ::function-count
                                                      ::var-usage-count ::ns-dependency-count
                                                      ::spec-count ::var-count]))))))
    (let [result @totals]
      (log/info "Knowledge graph ingestion complete" result)
      result)))

(defn ingest-incremental!
  "Incrementally ingest entities for a single namespace change.

   Delegates to ingest-namespace! which uses upsert + retract-stale.
   Kept for backward compatibility with callers that pass ::entities.

   Request keys:
     ::db-name  - Required. Database name keyword (e.g. :seon.runtime)
     ::entities - Required. Extracted entities from analyzer/extract-entities
     ::specs    - Optional. Spec entities from scanner/scan-file
     ::vars     - Optional. Var entities from scanner

   Returns map with counts of ingested entities."
  [{::keys [db-name entities specs vars]}]
  (let [namespaces (::analyzer/namespaces entities)
        functions (::analyzer/functions entities)
        var-usages (::analyzer/var-usages entities)
        ns-deps (::analyzer/namespace-usages entities)
        ;; Determine the single namespace being updated
        ns-str (or (:seon.ns/name (first namespaces))
                   (:seon.fn/namespace (first functions))
                   (:seon.spec/namespace (first specs)))]
    (if-not ns-str
      {::namespace-count 0 ::function-count 0 ::var-usage-count 0
       ::ns-dependency-count 0 ::spec-count 0 ::var-count 0}
      (ingest-namespace!
        {::db-name db-name
         ::ns-name ns-str
         ::functions functions
         ::specs specs
         ::vars vars
         ::call-edges var-usages
         ::ns-deps ns-deps
         ::ns-entities namespaces}))))

(defn ingest-file!
  "Extract graph from a source file and transact into Datalevin.

   Uses seon.graph.extract for unified extraction pipeline, then
   delegates to ingest-namespace! for transacting.

   Request keys:
     ::db-name   - Database name keyword (e.g. :seon.runtime)
     ::file-path - Path to a Clojure source file

   Returns map with counts of ingested entities."
  [{::keys [db-name file-path]}]
  (require 'seon.graph.extract)
  (let [extract-fn (resolve 'seon.graph.extract/extract-graph-from-file)
        graph (extract-fn {:seon.graph.extract/file-path file-path})
        ns-str (:seon.graph.extract/ns-name graph)]
    (if-not ns-str
      {::spec-count 0 ::function-count 0 ::var-count 0}
      (ingest-namespace!
        {::db-name db-name
         ::ns-name ns-str
         ::functions (:seon.graph.extract/functions graph)
         ::specs (:seon.graph.extract/specs graph)
         ::vars (:seon.graph.extract/vars graph)
         ::call-edges (:seon.graph.extract/call-edges graph)
         ::ns-deps (:seon.graph.extract/ns-deps graph)
         ::ns-entities (:seon.graph.extract/namespaces graph)}))))

;;; ---------------------------------------------------------------------------
;;; REPL Helpers
;;; ---------------------------------------------------------------------------

(comment
  ;; Full project analysis + ingest
  (def project (analyzer/analyze-project! {}))
  (def entities (analyzer/extract-entities
                 {::analyzer/raw-analysis (::analyzer/raw-analysis project)}))
  (ingest-analysis! {::db-name :seon.runtime ::entities entities})

  ;; Incremental form analysis + ingest
  (def form-result
    (analyzer/analyze-form
     {::analyzer/source "(defn my-test [x] (+ x 1))"}))
  (def form-entities
    (analyzer/extract-entities
     {::analyzer/raw-analysis (::analyzer/raw-analysis form-result)}))
  (ingest-incremental! {::db-name :seon.runtime ::entities form-entities})

  ;; Check what's in the graph
  (db/query :seon.runtime '[:find ?name :where [?e :seon.ns/name ?name]])

  nil)
