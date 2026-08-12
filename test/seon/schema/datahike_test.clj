(ns seon.schema.datahike-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.schema.edn :as schema.edn]
            [seon.test-support :as support])
  (:import [java.util.concurrent Callable FutureTask]))

(def ^:private schema-delta (schema/begin-registration-delta))

(schema/call-with-registration-delta
 schema-delta
 {:seon.schema.admission/source :core}
 #(schema/register! ::title :string))

(use-fixtures
 :each
 (fn [test-body]
   (schema/call-with-registration-delta
    schema-delta
    {:seon.schema.admission/source :core}
    test-body)))

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
  (let [direct (carry-properties base properties)]
    (schema/register! ::direct direct)
    (schema/register! ::wrapped [:and properties base])
    (schema/register! ::alias-base direct)
    (schema/register! ::alias-middle ::alias-base)
    (schema/register! ::aliased ::alias-middle)
    (mapv #(dissoc (schema.datahike/malli->datahike-attr %) :db/ident)
          [::direct ::wrapped ::aliased])))

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
      (schema/register! ::literal [:= literal])
      (is (= expected
             (:db/valueType
              (schema.datahike/malli->datahike-attr ::literal)))))))

(deftest schema-row-properties-lift-only-when-their-declarations-are-storable
  (let [forms {:seon.error/class [:= true]
               :gen/schema :seon.schema/definition
               :seon.error/message :string
               ::error
               [:map {:seon.error/class true
                      :gen/schema :string}
                [:seon.error/message :seon.error/message]]}
        projection {:seon.schema.projection/forms forms}
        attributes (set (schema.datahike/database-attributes-in projection))]
    (is (schema.datahike/storable-attribute-in?
         projection :seon.error/class))
    (is (not (schema.datahike/storable-attribute-in?
              projection :gen/schema)))
    (is (contains? attributes :seon.error/class))
    (is (not (contains? attributes :gen/schema)))))

(deftest database-attribute-derivation-resolves-the-population-once
  (let [without-bindings
        (fn [operation]
          ;; A raw Java task starts with no Clojure thread bindings, matching
          ;; the HTTP worker on which the live regression was measured.
          (let [task (FutureTask. ^Callable (fn [] (operation)))
                thread (Thread. task)]
            (.start thread)
            (.get task)))
        resource-reads
        (fn [operation]
          (let [reads (atom 0)
                read-one @#'schema.edn/read-schema-resource]
            (with-redefs [schema.edn/read-schema-resource
                          (fn [resource]
                            (swap! reads inc)
                            (read-one resource))]
              (let [value (without-bindings operation)]
                {:resource-reads @reads :value value}))))
        one-population
        (:resource-reads (resource-reads schema/declaration-population))
        result
        (resource-reads schema/canonical-database-attributes)]
    (testing "the route-shaped bridge operation resolves once, not per attribute"
      (is (pos? one-population)
          "the fallback must read resources or the count is vacuous")
      (is (> (count (:value result)) 500)
          "the regression must exercise the production-wide attribute walk")
      (is (= one-population (:resource-reads result))
          "one operation carries one population through every bridge question"))))

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
      (let [data (try
                   (schema/register! ::refused form)
                   (schema.datahike/malli->datahike-attr ::refused)
                   support/committed
                   (catch clojure.lang.ExceptionInfo error
                     (ex-data error)))]
        (and (map? data)
             (= :user-input (:seon.error/kind data)))))
    :seed 202607280702)
   "unsupported database attribute refusal"))

(deftest registered-shape-round-trips-through-datahike
  (support/with-database
    {:seon.test-support/extra-schema
     [(schema.datahike/malli->datahike-attr ::title)]}
    (fn [connection]
      (testing "derive, install, transact, and read through the public call shape"
        (db/transact! connection [{::title "Alpha"}])
        (is (= "Alpha"
               (db/q '[:find ?title .
                      :where [_ ::title ?title]]
                    (db/db connection))))))))

(deftest encode-transaction-resolves-the-declaration-population-once
  ;; The class: the encode seam resolving the declaration population PER
  ;; ATTRIBUTE. With no population supplied on the calling thread,
  ;; `schema/declaration-population` falls through to
  ;; `seon.schema.edn/packaged-forms`, which re-reads and re-validates every
  ;; schema resource from the classpath (~14 ms). Per attribute that turned
  ;; `seon.cluster.work-test/situation-totality-property` into a suite wedge
  ;; that never finished inside the 300 s liveness backstop
  ;; (docs/prds/sci-execution-runtime/research/parallel-turns-hang-cause-2026-08-07.md).
  ;; One resolution per transaction is the wanted behavior, and it must not
  ;; grow with the transaction's attribute count or nesting depth.
  (let [resolutions (atom 0)
        real-declaration-population schema/declaration-population
        wide {:seon.cluster.agent/id "agent-a"
              :seon.cluster.message/id "m-1"
              :seon.cluster.message/content "do the thing"
              :seon.cluster.message/at (java.util.Date.)
              :seon.cluster.run/id "run-1"
              ::title "Alpha"}
        nested {:seon.cluster.agent/id "agent-b"
                :seon.cluster.agent/namespace {:seon.ns/name 'my.agents.b}}]
    (with-redefs [schema/declaration-population
                  (fn []
                    (swap! resolutions inc)
                    (real-declaration-population))]
      (testing "a six-attribute transaction resolves the population once"
        (reset! resolutions 0)
        (schema.datahike/encode-transaction [wide])
        (is (= 1 @resolutions)))

      (testing "nesting does not add resolutions"
        (reset! resolutions 0)
        (schema.datahike/encode-transaction [nested])
        (is (= 1 @resolutions)))

      (testing "the argument-map transaction shape resolves once as well"
        (reset! resolutions 0)
        (schema.datahike/encode-transaction {:tx-data [wide nested]})
        (is (= 1 @resolutions)
            "resolution count is per transaction, never per entity")))))

(deftest edn-backed-attributes-round-trip-reader-inexpressible-identifiers
  (let [projection (schema/declaration-projection)
        branch (keyword "seon.test-support.fixture" "0")
        revision
        {:datahike.cache/connection-id [(random-uuid) branch]
         :datahike.cache/generation (random-uuid)
         :datahike.read/attributes #{:seon.cluster.agent/id}
         :datahike.cache/attribute-revisions
         {:seon.cluster.agent/id (random-uuid)}}
        encoded
        (get (first
              (schema.datahike/encode-transaction-in
               projection [{:datahike.read/revision revision}]))
             :datahike.read/revision)]
    (is (= revision
           (schema.datahike/decode-attribute-value-in
            projection :datahike.read/revision encoded))
        "numeric branch keywords remain exact across the string storage seam")))
