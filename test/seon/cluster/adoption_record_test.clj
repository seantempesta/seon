(ns seon.cluster.adoption-record-test
  "An adoption's schema, program and task rows are ONE transaction
  (`seon.cluster/adopt-rows!`); the adoption record is written after load and
  arm, in its own transaction, by `development-source-refresh!`."
  (:require [clojure.test :refer [deftest is]]
            [seon.cluster]
            [seon.cluster.registry :as registry]
            [seon.cluster.source :as source]
            [seon.db :as db]
            [seon.test-support :as support]))

(defn- published
  "The live `current-src` head the writer guards, naming `database` as its entities."
  {:malli/schema [:=> [:cat :seon.db/connection] :seon.source/published]}
  [connection]
  {:seon.source/branch source/current-branch
   :seon.source/commit-id (registry/connection-branch-commit-id connection source/current-branch)
   :seon.source/digest (apply str (repeat 64 "0"))
   :seon.source/built? true})

(deftest ^{:seon.test/fixture-observation
           "The writer's head guard reads the shared store's live current-src head."}
  adoption-entities-land-in-one-transaction
  (support/with-database
   (fn [connection]
     (let [adopted (symbol "seon.cluster.adoption-record-test" "adopted-value")
           cited (symbol "seon.cluster.adoption-record-test" "cited-value")
           database (db/db connection)
           _ (support/transacted!
              connection [(support/program-fn-entity database adopted "(defn adopted-value [] 1)")
                          (support/program-fn-entity database cited "(defn cited-value [] 2)")])
           source-database (db/db connection)
           _ (support/transacted! connection [[:db/retractEntity [:seon.fn/sym adopted]]
                                              [:db/retractEntity [:seon.fn/sym cited]]])
           adopt! (fn [identities]
                    (try (#'seon.cluster/adopt-entities! connection (published connection)
                                                      identities source-database)
                         (catch clojure.lang.ExceptionInfo refused refused)))
           present? #(some? (:db/id (db/pull (db/db connection) [:db/id] %)))
           before (db/basis-t (db/db connection))]
       (is (not (present? [:seon.fn/sym adopted])) "the retracted program entity is absent before adoption")
       (is (= before (db/basis-t (db/db connection))))
       (adopt! #{[:seon.fn/sym adopted] [:seon.fn/sym cited]})
       (let [database (db/db connection)
             tx-of #(:tx (first (db/datoms database :eavt (:db/id (db/pull database [:db/id] %)))))
             txs (set (map tx-of [[:seon.fn/sym adopted] [:seon.fn/sym cited]]))]
         (is (= 1 (count txs)) "the program entities are one transaction")
         (is (empty? (db/datoms database :eavt (first txs) :seon.test/adoption-inputs))
             "the entities carry no adoption record; it follows load and arm"))
       nil))))
