(ns seon.db.program
  "Compile and reconcile first-party program graph facts.

   Namespace, function, schema, and test rows are diffed by their established
   identities and committed together. Fresh-database initialization pages are
   compiled here too, before an artifact is frozen; runtime code only consumes
   those mandatory pages."
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [datahike.api :as d]
            [hasch.core :as hasch]
            [seon.schema :as schema]
            [seon.schema.form :as schema.form]))

;; A compiled program row is intentionally open: namespace, function, schema,
;; and test entities share only their established identity attributes.
(schema/register! ::program-row [:map-of :qualified-keyword :any])
(schema/register! ::desired [:vector ::program-row])

;; Datahike database values and transaction forms are genuinely polymorphic
;; third-party boundaries retained only inside this authority namespace.
(schema/register! ::db-value :any)
(schema/register! ::tx-data [:vector :any])

(schema/register! :seon.test/sym
                  [:string {:seon.db/identity true}])
(schema/register! :seon.test/source :string)
(schema/register! :seon.test/ns :seon.db/ref)
(schema/register!
 :seon.test
 [:map {:seon.db/entity true}
  [:seon.test/sym :seon.test/sym]
  [:seon.test/ns {:optional true} :seon.test/ns]
  [:seon.test/source {:optional true} :seon.test/source]])

(def ^:private identity-attrs
  [:seon.ns/name :seon.fn/sym :seon.schema/key :seon.test/sym])

(def ^:private required-population-attrs
  [:seon.ns/name :seon.fn/sym :seon.schema/key])

(def ^:private wall-clock-attrs
  [:seon.fn/created-at :seon.schema/created-at :seon.test/created-at])

(def ^:private no-generator :seon.db.id.generator/absent)

(def ^:private current-function-query
  '[:find ?sym ?source ?spec ?doc ?arglists ?private
    :where
    [?function :seon.fn/sym ?sym]
    [?function :seon.fn/source ?source]
    [(get-else $ ?function :seon.fn/spec "") ?spec]
    [(get-else $ ?function :seon.fn/doc "") ?doc]
    [(get-else $ ?function :seon.fn/arglists "") ?arglists]
    [(get-else $ ?function :seon.fn/private? false) ?private]])

(def ^:private boot-function-query
  '[:find [?sym ...]
    :where
    [?function :seon.fn/sym ?sym]
    [?function :seon.fn/source _ ?tx]
    [?tx :seon.db/process ?process]
    [?process :seon.db.process/id :seon.db.process/boot]])

(def ^:private current-schema-query
  '[:find ?key ?form ?generator
    :where
    [?schema :seon.schema/key ?key]
    [?schema :seon.schema/form ?form]
    [(get-else $ ?schema :seon.db.id/generator
               :seon.db.id.generator/absent) ?generator]])

(def ^:private boot-schema-query
  '[:find [?key ...]
    :where
    [?schema :seon.schema/key ?key]
    [?schema :seon.schema/form _ ?tx]
    [?tx :seon.db/process ?process]
    [?process :seon.db.process/id :seon.db.process/boot]])

(def ^:private current-namespace-query
  '[:find ?name ?source
           (pull ?namespace [{:seon.ns/require-edges [*]}])
    :where
    [?namespace :seon.ns/name ?name]
    [?namespace :seon.ns/source ?source]])

(def ^:private current-namespace-without-edges-query
  '[:find ?name ?source ?pulled
    :where
    [?namespace :seon.ns/name ?name]
    [?namespace :seon.ns/source ?source]
    [(ground {}) ?pulled]])

(def ^:private boot-program-row-query
  '[:find ?entity ?identity-attr ?identity ?source
    :where
    (or-join [?entity ?identity-attr ?identity ?source ?tx]
      (and [?entity :seon.ns/name ?identity]
           [?entity :seon.ns/source ?source ?tx]
           [(ground :seon.ns/name) ?identity-attr])
      (and [?entity :seon.fn/sym ?identity]
           [?entity :seon.fn/source ?source ?tx]
           [(ground :seon.fn/sym) ?identity-attr])
      (and [?entity :seon.schema/key ?identity]
           [?entity :seon.schema/form ?source ?tx]
           [(ground :seon.schema/key) ?identity-attr])
      (and [?entity :seon.test/sym ?identity]
           [?entity :seon.test/source ?source ?tx]
           [(ground :seon.test/sym) ?identity-attr]))
    [?tx :seon.db/process ?process]
    [?process :seon.db.process/id :seon.db.process/boot]])

(def ^:private agent-id-query
  '[:find [?id ...] :where [?agent :seon.agent/id ?id]])

(defn- acquire-current [db-value]
  {:current-functions (d/q current-function-query db-value)
   :boot-functions (set (d/q boot-function-query db-value))
   :current-schemas (d/q current-schema-query db-value)
   :boot-schemas (set (d/q boot-schema-query db-value))
   :current-namespaces
   (d/q (if (contains? (:schema db-value) :seon.ns/require-edges)
          current-namespace-query
          current-namespace-without-edges-query)
        db-value)
   :boot-program-rows (d/q boot-program-row-query db-value)
   :agent-ids (d/q agent-id-query db-value)})

(defn- program-identity [row]
  (some (fn [attribute]
          (when (contains? row attribute)
            [attribute (get row attribute)]))
        identity-attrs))

(defn- canonical-row [row]
  (apply dissoc row wall-clock-attrs))

(defn- desired-sort-key [row]
  (let [[attribute value] (program-identity row)]
    [(.indexOf identity-attrs attribute) (pr-str value) (pr-str row)]))

(defn- function-fields [row]
  {:seon.fn/source (:seon.fn/source row)
   :seon.fn/spec (get row :seon.fn/spec "")
   :seon.fn/doc (:seon.fn/doc row)
   :seon.fn/arglists (:seon.fn/arglists row)
   :seon.fn/private? (:seon.fn/private? row)})

(defn- schema-fields [row]
  {:seon.schema/form (:seon.schema/form row)
   :seon.db.id/generator
   (get row :seon.db.id/generator no-generator)})

(defn- normalize-require-edge [edge]
  (let [refers (:seon.ns.require/refers edge)]
    (cond-> (dissoc edge :db/id :seon.ns.require/refers)
      (seq refers) (assoc :seon.ns.require/refers (set refers)))))

(defn- current-program
  [{:keys [current-functions current-schemas current-namespaces]}]
  {:functions
   (into {}
         (map (fn [[sym source spec doc arglists private?]]
                [sym {:seon.fn/source source
                      :seon.fn/spec spec
                      :seon.fn/doc doc
                      :seon.fn/arglists arglists
                      :seon.fn/private? private?}]))
         current-functions)
   :schemas
   (into {}
         (map (fn [[key form generator]]
                [key {:seon.schema/form form
                      :seon.db.id/generator generator}]))
         current-schemas)
   :namespaces
   (into {}
         (map (fn [[name source _pulled]] [name source]))
         current-namespaces)
   :require-edges
   (into {}
         (map (fn [[name _source pulled]]
                [name
                 {:edges (into #{}
                               (map normalize-require-edge)
                               (:seon.ns/require-edges pulled))
                  :eids (->> (:seon.ns/require-edges pulled)
                             (keep :db/id)
                             sort
                             vec)}]))
         current-namespaces)})

(defn- desired-identities [desired]
  (into #{} (keep program-identity) desired))

(defn- desired-values [identities]
  (reduce (fn [values [attribute value]]
            (update values attribute (fnil conj #{}) value))
          {}
          identities))

(defn- assert-complete-populations! [values]
  (doseq [attribute required-population-attrs]
    (when-not (seq (get values attribute))
      (throw
       (ex-info
        (str "Compiled program has no " attribute
             " declarations; refusing removal.")
        {::missing-program-population attribute
         :seon.error/kind :core-bug})))))

(defn- unchanged-row?
  [row functions boot-functions schemas boot-schemas namespaces]
  (or
   (when-some [stored (get functions (:seon.fn/sym row))]
     (or (= stored (function-fields row))
         (not (contains? boot-functions (:seon.fn/sym row)))))
   (and (contains? row :seon.ns/name)
        (= (get namespaces (:seon.ns/name row))
           (:seon.ns/source row)))
   (when-some [stored (get schemas (:seon.schema/key row))]
     (or (= stored (schema-fields row))
         (not (contains? boot-schemas (:seon.schema/key row)))))))

(defn- optional-field-retractions [rows functions schemas]
  (into []
        (mapcat
         (fn [row]
           (if-some [sym (:seon.fn/sym row)]
             (let [stored-spec (get-in functions [sym :seon.fn/spec])]
               (cond-> []
                 (and (not (contains? row :seon.fn/spec))
                      (seq stored-spec))
                 (conj [:db/retract [:seon.fn/sym sym]
                        :seon.fn/spec stored-spec])))
             (when-some [schema-key (:seon.schema/key row)]
               (let [stored-generator
                     (get-in schemas [schema-key :seon.db.id/generator])]
                 (when (and (not= no-generator stored-generator)
                            (not (contains? row :seon.db.id/generator)))
                   [[:db/retract [:seon.schema/key schema-key]
                     :seon.db.id/generator stored-generator]])))))
         rows)))

(defn- require-edge-tx [desired current-edges]
  (into []
        (mapcat
         (fn [row]
           (when (and (contains? row :seon.ns/name)
                      (contains? row :seon.ns/require-edges))
             (let [name (:seon.ns/name row)
                   wanted (into #{} (:seon.ns/require-edges row))
                   current (get current-edges name)]
               (when (not= wanted (:edges current #{}))
                 (into
                  (mapv (fn [eid] [:db/retractEntity eid]) (:eids current))
                  (when (seq wanted)
                    [{:seon.ns/name name
                      :seon.ns/require-edges
                      (vec (sort-by pr-str wanted))}]))))))
         desired)))

(defn- agent-home-name [agent-id]
  (symbol (str "my.agent." agent-id)))

(defn- stale-entity-tx
  [boot-program-rows identities values agent-ids]
  (let [agent-home-names (into #{} (map agent-home-name) agent-ids)]
    (->> boot-program-rows
         (keep
          (fn [[eid identity-attr ident _source]]
            (when (and (or (= :seon.test/sym identity-attr)
                           (seq (get values identity-attr)))
                       (not (contains? identities [identity-attr ident]))
                       (not (and (= :seon.ns/name identity-attr)
                                 (contains? agent-home-names ident))))
              eid)))
         set
         sort
         (mapv (fn [eid] [:db.fn/retractEntity eid])))))

(defn- compile-acquired-tx-data
  [desired {:keys [boot-functions boot-schemas boot-program-rows agent-ids]
            :as current}]
  (let [desired (->> desired
                     (map canonical-row)
                     (sort-by desired-sort-key)
                     vec)
        identities (desired-identities desired)
        values (desired-values identities)
        _ (assert-complete-populations! values)
        {:keys [functions schemas namespaces require-edges]}
        (current-program current)
        changed
        (into []
              (remove #(unchanged-row? % functions boot-functions
                                        schemas boot-schemas namespaces))
              desired)
        edge-tx (require-edge-tx desired require-edges)
        field-retracts (optional-field-retractions changed functions schemas)
        entity-retracts
        (stale-entity-tx boot-program-rows identities values agent-ids)]
    (-> (mapv #(dissoc % :seon.ns/require-edges) changed)
        (into edge-tx)
        (into field-retracts)
        (into entity-retracts))))

(defn compile-tx-data
  "Compile the exact transaction for one database value and desired program."
  {:malli/schema
   [:=> [:catn [::db-value ::db-value] [::desired ::desired]] ::tx-data]}
  [db-value desired]
  (compile-acquired-tx-data desired (acquire-current db-value)))

;;; Fresh-database page compilation

(def ^:private initialization-bootstrap-attributes
  "Fixed schema closure needed to persist genesis, canonical schema rows, and
   the initialization receipt before the rest of the schema corpus exists."
  #{:seon.agent/id
    :seon.db.process/id
    :seon.db/user
    :seon.db/process
    :seon.ns/name
    :seon.schema/key
    :seon.schema/form
    :seon.schema/ns
    :seon.db.id/generator
    :seon.db.initialization/id
    :seon.db.initialization/fingerprint
    :seon.db.initialization/page-fingerprint
    :seon.db.initialization/identities
    :seon.db.initialization/page-count
    :seon.db.initialization/status})

(defn- initialization-schema-form [row]
  (edn/read-string (:seon.schema/form row)))

(defn- schema-reference-closure [projection forms roots]
  (loop [pending (set roots)
         closure #{}]
    (if (empty? pending)
      closure
      (let [key (first pending)
            dependencies
            (schema/direct-references projection (get forms key))]
        (recur (into (disj pending key)
                     (remove closure)
                     dependencies)
               (conj closure key))))))

(defn- schema-dependency-order [projection forms resolved pending]
  (let [known (set (keys forms))]
    (loop [resolved (set resolved)
           pending (set pending)
           ordered []]
      (if (empty? pending)
        ordered
        (let [ready
              (->> pending
                   (filter
                    (fn [key]
                      (set/subset?
                       (set/intersection
                        known
                        (schema/direct-references projection
                                                  (get forms key)))
                       resolved)))
                   (sort-by str)
                   vec)]
          (when (empty? ready)
            (throw
             (ex-info "Database initialization schema dependencies are cyclic."
                      {:seon.db.initialization/pending-schema-keys
                       (vec (sort-by str pending))
                       :seon.error/kind :core-bug})))
          (recur (into resolved ready)
                 (reduce disj pending ready)
                 (into ordered ready)))))))

(defn- initialization-schema-pages [schema-rows page-rows]
  (let [rows-by-key (into {} (map (juxt :seon.schema/key identity))
                          schema-rows)
        forms (update-vals rows-by-key initialization-schema-form)
        projection (schema/build-projection forms)
        missing (set (remove #(contains? forms %)
                             initialization-bootstrap-attributes))]
    (when (seq missing)
      (throw
       (ex-info "Database initialization lacks bootstrap schema forms."
                {:seon.db.initialization/missing-schema-keys
                 (vec (sort missing))
                 :seon.error/kind :core-bug})))
    (let [bootstrap-keys
          (schema-reference-closure
           projection forms initialization-bootstrap-attributes)
          bootstrap-rows
          (into [] (keep rows-by-key) (sort bootstrap-keys))
          remaining-keys
          (set/difference (set (keys rows-by-key)) bootstrap-keys)
          remaining-rows
          (into []
                (keep rows-by-key)
                (schema-dependency-order
                 projection forms bootstrap-keys remaining-keys))]
      (into [bootstrap-rows]
            (comp (map vec) (remove empty?))
            (partition-all page-rows remaining-rows)))))

(defn- initialization-entity-attributes [schema-rows]
  (into #{}
        (comp
         (map initialization-schema-form)
         (filter schema.form/map-shape?)
         (filter #(true? (:seon.db/entity
                          (schema.form/schema-properties %))))
         (mapcat schema.form/map-entries)
         (keep (fn [entry]
                 (let [attribute (when (vector? entry) (first entry))]
                   (when (qualified-keyword? attribute) attribute)))))
        schema-rows))

(defn compile-initialization-pages
  "Compile bounded mandatory initialization pages from one raw build value."
  {:malli/schema [:=> [:cat :seon.db/raw-initialization]
                  :seon.db/initialization-pages]}
  [initialization]
  (let [page-rows (:seon.db.initialization/page-rows initialization)
        program (:seon.db/program initialization)
        schema-rows
        (into [] (filter #(contains? % :seon.schema/key)) program)
        ordinary-program
        (into [] (remove #(contains? % :seon.schema/key)) program)
        schema-pages (initialization-schema-pages schema-rows page-rows)
        attributes
        (into []
              (distinct)
              (concat (:seon.db/attributes initialization)
                      (sort (initialization-entity-attributes schema-rows))))
        fingerprint (str (hasch/uuid initialization))
        payloads
        (into
         (mapv
          (fn [rows]
            {:seon.db.initialization/phase
             :seon.db.initialization.phase/schema
             :seon.db/program rows})
          schema-pages)
         (concat
          (map
           (fn [page-attributes]
             {:seon.db.initialization/phase
              :seon.db.initialization.phase/attributes
              :seon.db/attributes (vec page-attributes)})
           (partition-all page-rows attributes))
          (map
           (fn [rows]
             {:seon.db.initialization/phase
              :seon.db.initialization.phase/program
              :seon.db/program (vec rows)})
           (partition-all page-rows ordinary-program))
          (map
           (fn [rows]
             {:seon.db.initialization/phase
              :seon.db.initialization.phase/initial-data
              :seon.db/initial-data (vec rows)})
           (partition-all page-rows
                          (:seon.db/initial-data initialization)))
          [{:seon.db.initialization/phase
            :seon.db.initialization.phase/completion}]))
        page-count (count payloads)]
    (mapv
     (fn [page-index payload]
       (assoc payload
              :seon.db.initialization/fingerprint fingerprint
              :seon.db.initialization/page-index page-index
              :seon.db.initialization/page-count page-count
              :seon.db.initialization/page-rows page-rows))
     (range)
     payloads)))
