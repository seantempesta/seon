(ns seon.db.program
  "Authority-owned reconciliation of compiled program facts."
  (:require [datahike.api :as d]
            [seon.schema :as schema]))

;; A compiled program row is intentionally open: namespace, function, schema,
;; and test entities share only their established identity attributes.
(schema/register! ::program-row [:map-of :qualified-keyword :any])
(schema/register! ::desired [:vector ::program-row])

;; Datahike database values and transaction forms are genuinely polymorphic
;; third-party boundaries retained only inside this authority namespace.
(schema/register! ::db-value :any)
(schema/register! ::tx-data [:vector :any])

(def ^:private identity-attrs
  [:seon.ns/name :seon.fn/sym :seon.schema/key :seon.test/sym])

(def ^:private required-population-attrs
  [:seon.ns/name :seon.fn/sym :seon.schema/key])

(def ^:private wall-clock-attrs
  [:seon.fn/created-at :seon.schema/created-at :seon.test/created-at])

(def ^:private no-generator :seon.db.id.generator/absent)

(def ^:private current-function-query
  '[:find ?sym ?source ?spec ?doc ?arglists ?private ?agent-facing
    :where
    [?function :seon.fn/sym ?sym]
    [?function :seon.fn/source ?source]
    [(get-else $ ?function :seon.fn/spec "") ?spec]
    [(get-else $ ?function :seon.fn/doc "") ?doc]
    [(get-else $ ?function :seon.fn/arglists "") ?arglists]
    [(get-else $ ?function :seon.fn/private? false) ?private]
    [(get-else $ ?function :seon.fn/agent-facing? false) ?agent-facing]])

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
   :seon.fn/private? (:seon.fn/private? row)
   :seon.fn/agent-facing? (true? (:seon.fn/agent-facing? row))})

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
         (map (fn [[sym source spec doc arglists private? agent-facing?]]
                [sym {:seon.fn/source source
                      :seon.fn/spec spec
                      :seon.fn/doc doc
                      :seon.fn/arglists arglists
                      :seon.fn/private? private?
                      :seon.fn/agent-facing? agent-facing?}]))
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
             (let [stored-spec (get-in functions [sym :seon.fn/spec])
                   stored-agent-facing?
                   (get-in functions [sym :seon.fn/agent-facing?])]
               (cond-> []
                 (and (not (contains? row :seon.fn/spec))
                      (seq stored-spec))
                 (conj [:db/retract [:seon.fn/sym sym]
                        :seon.fn/spec stored-spec])

                 (and stored-agent-facing?
                      (not (contains? row :seon.fn/agent-facing?)))
                 (conj [:db/retract [:seon.fn/sym sym]
                        :seon.fn/agent-facing? true])))
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
  (keyword (str "my.agent." agent-id)))

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
