(ns seon.schema.datahike-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]))

(schema/register! ::title :string)
(schema/register! ::mixed-value [:or :string :int])
(schema/register! ::string-set
                  [:set {:seon.db/index true
                         :seon.db/no-history? true}
                   :string])
(schema/register! ::string-set-alias ::string-set)
(schema/register! ::tags ::string-set-alias)
(schema/register! ::secondary-double
                  [:double {:db.secondary/only true}])
(schema/register! ::and-secondary-double
                  [:and {:db.secondary/only true} :double])
(schema/register! ::secondary-float
                  [:float {:db.secondary/only true}])
(schema/register! ::and-secondary-float
                  [:and {:db.secondary/only true} :float])
(schema/register! ::and-string-set
                  [:and {} [:set :string]])

(defn- result-or-error-kind [f form]
  (try
    [:value (f form)]
    (catch clojure.lang.ExceptionInfo error
      [:error (:seon.error/kind (ex-data error))])))

(deftest mixed-unions-declare-the-edn-string-storage-type
  (is (= {:db/ident ::mixed-value
          :db/valueType :db.type/string
          :db/cardinality :db.cardinality/one}
         (schema.datahike/malli->datahike-attr ::mixed-value))))

(deftest alias-chains-preserve-collection-cardinality-and-storage-facets
  (is (= {:db/ident ::tags
          :db/valueType :db.type/string
          :db/cardinality :db.cardinality/many
          :db/index true
          :db/noHistory true}
         (schema.datahike/malli->datahike-attr ::tags))))

(deftest and-wrapped-secondary-attributes-map
  (is (= {:db/ident ::and-secondary-double
          :db/valueType :db.type/tuple
          :db/cardinality :db.cardinality/one
          :db.secondary/only true}
         (schema.datahike/malli->datahike-attr ::and-secondary-double))))

(deftest guards-treat-direct-and-and-wrapped-forms-identically
  (testing "secondary-only admission resolves the stored value type"
    (doseq [[direct wrapped] [[::secondary-double ::and-secondary-double]
                              [::secondary-float ::and-secondary-float]]]
      (is (= (dissoc (schema.datahike/malli->datahike-attr direct) :db/ident)
             (dissoc (schema.datahike/malli->datahike-attr wrapped)
                     :db/ident)))))
  (testing "collection child and cardinality guards resolve the stored form"
    (is (= {:db/valueType :db.type/string
            :db/cardinality :db.cardinality/many}
           (dissoc (schema.datahike/malli->datahike-attr ::and-string-set)
                   :db/ident))))
  (testing "every form guard has direct and :and-wrapped parity"
    (doseq [guard [schema.datahike/form->datahike-value-type
                   schema.datahike/form->cardinality
                   schema.datahike/form->child-form]
            form [:string
                  [:enum :open :done]
                  [:set :string]
                  [:maybe :string]]]
      (is (= (result-or-error-kind guard form)
             (result-or-error-kind guard [:and {} form]))
          (str guard " disagreed for " form)))))

(deftest registered-shape-round-trips-through-datahike
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :schema-flexibility :write}
        _ (d/create-database configuration)
        connection (d/connect configuration)]
    (try
      (testing "derive, install, transact, and read through the public call shape"
        (d/transact connection
                    (schema.datahike/malli->datahike-schema [::title]))
        (d/transact connection [{::title "Alpha"}])
        (is (= "Alpha"
               (d/q '[:find ?title .
                      :where [_ ::title ?title]]
                    (d/db connection)))))
      (finally
        (d/release connection)
        (d/delete-database configuration)))))
