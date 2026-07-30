(ns seon.fn
  "Build-time indexing of the Clojure program graph through the one reader."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike.api :as d]
            [seon.program :as program]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.sci.reader :as reader]))

(schema.edn/load! {})

(def source-roots
  "The Clojure source roots admitted to the program graph."
  ["src" "test"])

(defn- source-file?
  [file]
  (and (.isFile ^java.io.File file)
       (or (str/ends-with? (.getName ^java.io.File file) ".clj")
           (str/ends-with? (.getName ^java.io.File file) ".cljc"))))

(defn- durable-row
  [event]
  ;; Build indexing keeps every directly read top-level function, including
  ;; private and uncontracted helpers, as input to the future call graph.
  (program/declaration-row event :all))

(defn- unadmitted-functions
  "The function declarations this file produced no row for, with the reason.

  The reader lifts `:seon.fn/arglists` from every `defn`/`defn-` it reads and
  `:seon.fn/sym` only once the namespace is proven, so an arglists event
  without a symbol is a declaration the index cannot place. Dropping it in
  silence is the defect — a check reading absence of a row as health — and it
  is how the graph came to hold 121 rows for 1242 declared functions."
  [events]
  (into []
        (mapcat
         (fn [event]
           (cond-> []
             (and (contains? event :seon.fn/arglists)
                  (not (:seon.fn/sym event)))
             (conj {::line (:seon.sci.reader/line event)
                    ::source (:seon.sci.reader/source event)
                    ::reason ::namespace-unproven})

             (seq (:seon.sci.reader/nested-declarations event))
             (conj {::line (:seon.sci.reader/line event)
                    ::source (:seon.sci.reader/source event)
                    ::reason ::nested-executable-declaration
                    ::declarations
                    (count (:seon.sci.reader/nested-declarations event))}))))
        events))

(defn rows
  "Canonical program rows read from the declared source roots."
  {:malli/schema [:=> [:cat :seon.fn/index-request] [:vector :map]]}
  [{roots :seon.fn/roots}]
  (into
   []
   (comp
    (mapcat (fn [root]
              (->> (file-seq (io/file root))
                   (filter source-file?)
                   (sort-by (fn [file]
                              (.getCanonicalPath ^java.io.File file))))))
    (mapcat
     (fn [file]
       (let [events
             (reader/read
              {:seon.sci.reader/text (slurp file)
               :seon.sci.reader/features #{:clj}
               ;; Standard data literals are source data, not an escape
               ;; hatch. The index needs their forms, never host objects.
               :seon.sci.reader/tags {'inst identity
                                      'uuid identity}})]
         (when (map? events)
           (throw (ex-info (:seon.error/message events)
                           (assoc (:seon.error/data events)
                                  :seon.fn/file
                                  (.getCanonicalPath ^java.io.File file)))))
         (when-let [unadmitted (seq (unadmitted-functions events))]
           (throw
            (ex-info
             (str "Source indexing could not place a function "
                  "declaration in a namespace.")
             {:seon.error/kind ::index-refused
              :seon.fn/file (.getCanonicalPath ^java.io.File file)
              ::unadmitted (vec unadmitted)})))
         events)))
    (keep durable-row))
   roots))

(defn- row-identity
  [row]
  (program/row-identity row))

(defn- ref-value
  [db identity-attr value]
  (when value
    (if (and (vector? value) (= identity-attr (first value)))
      value
      [identity-attr
       (or
        (when (symbol? value) value)
        (when (map? value) (get value identity-attr))
        (d/q '[:find ?identity .
               :in $ ?entity ?identity-attr
               :where [?entity ?identity-attr ?identity]]
             db
             (if (map? value) (:db/id value) value)
             identity-attr)
        (throw
         (ex-info
          "Source indexing could not resolve a program reference."
          {:seon.error/kind ::index-refused
           ::identity-attr identity-attr
           ::reference value})))])))

(defn- process-identity
  [db process]
  (when process
    (d/q '[:find ?process-id .
           :in $ ?process
           :where [?process :seon.db.process/id ?process-id]]
         db
         (if (map? process) (:db/id process) process))))

(defn- require-edge
  [edge]
  (let [refers (:seon.ns.require/refers edge)]
    (cond-> (dissoc edge :db/id :seon.ns.require/refers)
      (seq refers) (assoc :seon.ns.require/refers (set refers)))))

(defn- canonical-row
  [db row]
  (let [row (program/canonical-row row)
        row
        (cond-> row
          (:seon.fn/ns row)
          (update :seon.fn/ns #(ref-value db :seon.ns/name %))

          (:seon.schema/ns row)
          (update :seon.schema/ns #(ref-value db :seon.ns/name %))

          (:seon.test/ns row)
          (update :seon.test/ns #(ref-value db :seon.ns/name %))

          (contains? row :seon.ns/require-edges)
          (update :seon.ns/require-edges
                  #(into #{} (map require-edge) %)))]
    (into
     {}
     (remove
      (fn [[attribute value]]
        (or (nil? value)
            (and (= :seon.ns/require-edges attribute)
                 (empty? value)))))
     row)))

(defn- current-rows
  [db shape]
  (let [identity-attr (:seon.program/identity-attribute shape)
        source-attr (:seon.program/source-attribute shape)
        provenance
        (into
         {}
         (map (fn [[entity process-id]] [entity process-id]))
         (d/q '[:find ?entity ?process-id
                :in $ ?identity-attr ?source-attr
                :where
                [?entity ?identity-attr _]
                [?entity ?source-attr _ ?tx]
                [?tx :seon.db/process ?process]
                [?process :seon.db.process/id ?process-id]]
              db
              identity-attr
              source-attr))]
    (into
     {}
     (map
      (fn [[entity identity]]
        (let [row
              (d/pull
               db
               [:db/id
                identity-attr
                source-attr
                :seon.ns/doc
                {:seon.ns/require-edges
                 [:db/id
                  :seon.ns.require/target
                  :seon.ns.require/alias
                  :seon.ns.require/refers
                  :seon.ns.require/refer-all?
                  :seon.ns.require/as-alias?]}
                {:seon.fn/ns [:db/id :seon.ns/name]}
                :seon.fn/arglists
                :seon.fn/doc
                :seon.fn/private?
                :seon.fn/spec
                :seon.fn/workload
                {:seon.schema/ns [:db/id :seon.ns/name]}
                {:seon.test/ns [:db/id :seon.ns/name]}]
               entity)]
          [[identity-attr identity]
           {::entity entity
            ::process-id (get provenance entity)
            ::row (canonical-row db row)
            ::require-edge-eids
            (into []
                  (keep :db/id)
                  (:seon.ns/require-edges row))}])))
     (d/q '[:find ?entity ?identity
            :in $ ?identity-attr
            :where [?entity ?identity-attr ?identity]]
          db
          identity-attr))))

(defn- assert-one-row-per-identity!
  [desired]
  (when-let [duplicate
             (some (fn [[identity n]] (when (> n 1) identity))
                   (frequencies (map row-identity desired)))]
    (throw
     (ex-info
      "Source indexing refused a duplicate program identity."
      {:seon.error/kind ::index-refused
       ::identity duplicate}))))

(defn- assert-populated!
  [desired]
  (doseq [identity-attr [:seon.ns/name :seon.fn/sym]]
    (when-not (some identity-attr desired)
      (throw
       (ex-info
        (str "Source indexing produced no " identity-attr
             " rows; refusing a partial program graph.")
        {:seon.error/kind ::index-refused
         ::missing-population identity-attr})))))

(defn- changed-row-tx
  [shape identity desired current]
  (let [identity-attr (:seon.program/identity-attribute shape)
        current-row (::row current)
        changed-attrs (program/changed-attributes current-row desired)]
    (when (seq changed-attrs)
      (let [edge-retracts
            (when (some #{:seon.ns/require-edges} changed-attrs)
              (mapv (fn [eid] [:db.fn/retractEntity eid])
                    (::require-edge-eids current)))
            retracts
            (into
             (vec edge-retracts)
             (keep
              (fn [attribute]
                (when (and (not= :seon.ns/require-edges attribute)
                           (contains? current-row attribute)
                           (not= (get current-row attribute)
                                 (get desired attribute)))
                  [:db.fn/retractAttribute identity attribute])))
             changed-attrs)
            additions
            (select-keys desired (conj changed-attrs identity-attr))]
        (cond-> retracts
          (> (count additions) 1) (conj additions))))))

(defn- shape-plan
  [db process-id shape desired]
  (let [identity-attr (:seon.program/identity-attribute shape)
        current (current-rows db shape)
        desired
        (into {}
              (map
               (fn [row]
                 (let [identity (row-identity row)]
                   [identity (canonical-row db row)])))
              (filter identity-attr desired))
        changes
        (into
         []
         (mapcat
          (fn [[identity desired-row]]
            (if-let [current-row (get current identity)]
              (when (or (= process-id (::process-id current-row))
                        (not (contains? (::row current-row)
                                        (:seon.program/source-attribute shape))))
                (changed-row-tx shape identity desired-row current-row))
              [desired-row])))
         desired)
        stale
        (into
         []
         (keep
          (fn [[identity current-row]]
            (when (and (= process-id (::process-id current-row))
                       (not (contains? desired identity)))
              [:db.fn/retractEntity (::entity current-row)])))
         current)]
    (into changes stale)))

(defn- desired-program-rows
  [request]
  (let [source-rows (rows request)
        canonical-schemas (schema/canonical-schema-rows (java.util.Date. 0))
        canonical-keys (into #{} (map :seon.schema/key) canonical-schemas)
        source-namespaces (into #{} (keep :seon.ns/name) source-rows)
        canonical-namespace-stubs
        (into
         []
         (comp
          (keep (comp :seon.ns/name :seon.schema/ns))
          (remove source-namespaces)
          (distinct)
          (map (fn [namespace-name]
                 {:seon.ns/name namespace-name})))
         canonical-schemas)
        source-only
        (remove (fn [row]
                  (contains? canonical-keys (:seon.schema/key row)))
                source-rows)]
    (doseq [{schema-key :seon.schema/key
             form-string :seon.schema/form}
            (filter :seon.schema/key source-only)]
      (when-not (and form-string
                     (schema/malli-form? (edn/read-string form-string)))
        (throw
         (ex-info "Source indexing refused a non-Malli schema declaration."
                  {:seon.error/kind ::index-refused
                   :seon.schema/key schema-key}))))
    (into (into (vec source-only) canonical-namespace-stubs)
          canonical-schemas)))

(defn- digest-plan
  [db desired-digest]
  (if-not desired-digest
    []
    (let [current
          (d/q '[:find ?ancestor ?digest
                 :where [?ancestor :seon.ancestor/digest ?digest]]
               db)]
      (case (count current)
        0 [{:seon.ancestor/digest desired-digest
            :seon.ancestor/built-at (java.util.Date.)}]
        1 (let [[ancestor current-digest] (first current)]
            (if (= current-digest desired-digest)
              []
              [[:db.fn/retractAttribute ancestor :seon.ancestor/digest]
               {:db/id ancestor :seon.ancestor/digest desired-digest}]))
        (throw
         (ex-info
          "Source indexing requires at most one recorded ancestor digest."
          {:seon.error/kind ::index-refused
           ::recorded-digests (into #{} (map second) current)}))))))

(defn index!
  "Exact-reconcile source-owned program rows and preserve authored facts.

  Rows whose current defining datom carries `:seon.db/process` are owned
  only when that process matches this request. Agent-authored rows and all
  non-program facts are therefore outside the reconciled slice. Namespace,
  function, schema, and test rows absent from the desired population are
  removed. The desired schema population is the canonical evaluated registry
  plus source-only declarations, so canonical rows do not need a family-wide
  stale-removal exemption.

  When `:seon.ancestor/digest` is supplied, its one current value advances
  only after the program rows commit. After priming, that value means “this
  cluster was explicitly synchronized from this source digest, preserving
  agent-authored overrides,” not “this branch was originally forked from
  the ancestor branch named by this digest.” A converged call performs no
  transaction."
  {:malli/schema [:=> [:cat :seon.fn/index-request] :seon.reconcile/result]}
  [{connection :seon.store/branch-connection
    process :seon.db/process
    :as request}]
  (let [program-rows (desired-program-rows request)
        _ (assert-one-row-per-identity! program-rows)
        _ (assert-populated! program-rows)
        process-id (process-identity @connection process)
        _ (when (nil? process-id)
            (throw
             (ex-info
              "Source indexing requires a resolvable process identity."
              {:seon.error/kind ::index-refused
               ::process process})))
        transaction
        (fn [operations]
          (when (seq operations)
            (d/transact
             connection
             (cond-> {:tx-data operations}
               process (assoc :tx-meta {:seon.db/process process}))))
          (count operations))
        namespace-plan
        (shape-plan @connection process-id
                    (program/shape :seon.ns/name)
                    program-rows)
        namespace-operations (transaction namespace-plan)
        declaration-plan
        (into
         []
         (mapcat
          #(shape-plan @connection process-id % program-rows))
         (keep (fn [identity-attribute]
                 (when-not (= :seon.ns/name identity-attribute)
                   (program/shape identity-attribute)))
               program/identity-attributes))
        final-plan
        (into declaration-plan
              (digest-plan @connection (:seon.ancestor/digest request)))
        declaration-operations (transaction final-plan)
        operations (+ namespace-operations declaration-operations)]
    {:seon.reconcile/converged? (zero? operations)
     :seon.reconcile/operations operations}))
