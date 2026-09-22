(ns seon.cluster.retired-attributes-test
  "Retirement reads only attribute idents from Datahike's installed schema.

  Datahike's `:schema` also maps each installed attribute's entity id to its
  ident (`reference-code/datahike/src/datahike/db/transaction.cljc:120`), so its
  keys mix longs and keywords. Sorting them before selecting idents threw
  `Keyword cannot be cast to Number` on every database and refused the
  nuclear reset of 2026-09-23 (landing lane-nuke-is-total-2026-09-23.md)."
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.cluster]))

(deftest retirement-reads-idents-from-a-schema-keyed-by-entity-id-too
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :keep-history? false
                       :schema-flexibility :write}]
    (d/create-database configuration)
    (try
      (let [connection (d/connect configuration)]
        (try
          (d/transact connection [{:db/ident :retired.fixture/kept
                                   :db/valueType :db.type/string
                                   :db/cardinality :db.cardinality/one}
                                  {:db/ident :retired.fixture/gone
                                   :db/valueType :db.type/string
                                   :db/cardinality :db.cardinality/one}])
          (let [database @connection]
            (is (some number? (keys (:schema database)))
                "the installed schema carries entity-id keys")
            (is (= [:retired.fixture/gone]
                   (#'seon.cluster/retired-attributes
                    database
                    {:seon.schema.projection/forms {:retired.fixture/kept :string}}))
                "an undeclared attribute retires; a declared one and the entity-id keys do not"))
          (finally (d/release connection))))
      (finally (d/delete-database configuration)))))
