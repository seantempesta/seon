(ns seon.schema.datahike-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.api :as d]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.test-support :as support]))

(schema/register! ::title :string)

(def ^:private scalar-generator
  (gen/elements
   [:string :int :double :float :keyword :boolean :inst :uuid :symbol]))

(def ^:private facet-generator
  (gen/let [indexed? gen/boolean
            no-history? gen/boolean
            uniqueness (gen/elements [nil :identity :value])]
    (cond-> {}
      indexed? (assoc :seon.db/index true)
      no-history? (assoc :seon.db/no-history? true)
      (= :identity uniqueness) (assoc :seon.db/identity true)
      (= :value uniqueness) (assoc :seon.db/unique true))))

(def ^:private supported-form-generator
  (gen/one-of
   [(gen/let [base scalar-generator
              properties facet-generator]
      {:base base :properties properties})
    (gen/let [head (gen/elements [:vector :set :sequential])
              child scalar-generator
              indexed? gen/boolean
              no-history? gen/boolean]
      {:base [head child]
       :properties
       (cond-> {}
         indexed? (assoc :seon.db/index true)
         no-history? (assoc :seon.db/no-history? true))})
    (gen/let [component? gen/boolean
              identity? gen/boolean]
      {:base [:set :seon.db/ref]
       :properties
       (cond-> {}
         component? (assoc :seon.db/component true)
         identity? (assoc :seon.db/identity true))})
    (gen/fmap (fn [base]
                {:base base :properties {:db.secondary/only true}})
              (gen/elements [:double :float]))
    (gen/return {:base [:or :string :int] :properties {}})]))

(defn- carry-properties
  [form properties]
  (if (empty? properties)
    form
    (if (vector? form)
      (into [(first form) properties] (rest form))
      [form properties])))

(defn- declarations
  [{:keys [base properties]}]
  (let [direct (carry-properties base properties)
        snapshot (schema/snapshot-state)]
    (try
      (schema/register! ::direct direct)
      (schema/register! ::wrapped [:and properties base])
      (schema/register! ::alias-base direct)
      (schema/register! ::alias-middle ::alias-base)
      (schema/register! ::aliased ::alias-middle)
      (mapv #(dissoc (schema.datahike/malli->datahike-attr %) :db/ident)
            [::direct ::wrapped ::aliased])
      (finally
        (schema/restore-state! snapshot)))))

(deftest supported-ast-wrappers-and-aliases-have-one-declaration
  (support/assert-check!
   (tc/quick-check
    80
    (prop/for-all [generated supported-form-generator]
      (let [[direct wrapped aliased] (declarations generated)]
        (and (= direct wrapped aliased)
             (contains? direct :db/valueType)
             (contains? direct :db/cardinality)
             (contains? #{:db.cardinality/one :db.cardinality/many}
                        (:db/cardinality direct)))))
    :seed 202607280701)
   "supported schema AST equivalence"))

(deftest literal-schemas-derive-their-native-datahike-value-type
  (doseq [[literal expected]
          [[true :db.type/boolean]
           ["one" :db.type/string]
           [:one :db.type/keyword]
           ['one :db.type/symbol]
           [1 :db.type/long]
           [1.0 :db.type/double]]]
    (testing (pr-str literal)
      (let [snapshot (schema/snapshot-state)]
        (try
          (schema/register! ::literal [:= literal])
          (is (= expected
                 (:db/valueType
                  (schema.datahike/malli->datahike-attr ::literal))))
          (finally
            (schema/restore-state! snapshot)))))))

(def ^:private refused-form-generator
  (gen/elements
   [{:form [:maybe :string] :rule :nilable}
    {:form [:string {:db.secondary/only true}] :rule :secondary}
    {:form [:enum "not-a-keyword"] :rule :enum}
    {:form [:map-of :string :string] :rule :unstorable}]))

(deftest unsupported-database-attributes-refuse-at-one-rule
  (support/assert-check!
   (tc/quick-check
    40
    (prop/for-all [{:keys [form]} refused-form-generator]
      (let [snapshot (schema/snapshot-state)]
        (try
          (let [data (try
                       (schema/register! ::refused form)
                       (schema.datahike/malli->datahike-attr ::refused)
                       support/committed
                       (catch clojure.lang.ExceptionInfo error
                         (ex-data error)))]
            (and (map? data)
                 (= :user-input (:seon.error/kind data))))
          (finally
            (schema/restore-state! snapshot)))))
    :seed 202607280702)
   "unsupported database attribute refusal"))

(deftest registered-shape-round-trips-through-datahike
  (support/with-database
    {:seon.test-support/extra-schema
     [(schema.datahike/malli->datahike-attr ::title)]}
    (fn [connection]
      (testing "derive, install, transact, and read through the public call shape"
        (d/transact connection [{::title "Alpha"}])
        (is (= "Alpha"
               (d/q '[:find ?title .
                      :where [_ ::title ?title]]
                    (d/db connection))))))))
