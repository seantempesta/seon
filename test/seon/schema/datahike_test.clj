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
