(ns seon.test-support-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.cluster :as cluster]
            [seon.config :as config]
            [seon.schema :as schema]
            [seon.test-support :as test-support]))

(def ^:private marker-schema
  [{:db/ident ::marker
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}])

(deftest a-canonical-database-is-the-production-ancestor-population
  (test-support/with-database
    (fn [connection]
      (let [database @connection
            installed
            (into #{} (filter keyword?) (keys (:schema database)))
            expected-schema-keys
            (into #{}
                  (map :seon.schema/key)
                  (schema/canonical-schema-rows (java.util.Date.)))
            actual-schema-keys
            (d/q
             '[:find [?key ...]
               :where
               [_ :seon.schema/key ?key]]
             database)]
        (is (every? installed (schema/canonical-database-attributes)))
        (is (= expected-schema-keys (set actual-schema-keys)))
        (is (= #{cluster/boot-process-identity
                 config/managing-process-identity}
               (set
                (d/q
                 '[:find [?process-id ...]
                   :where
                   [?process :seon.db.process/id ?process-id]]
                 database))))))))

(deftest config-reconciliation-cannot-retract-the-schema-population
  (test-support/with-database
    (fn [connection]
      (let [before
            (d/q
             '[:find [?key ...]
               :where
               [_ :seon.schema/key ?key]]
             @connection)
            result
            (config/apply!
             {:seon.config/connection connection
              :seon.config/manifest (config/defaults)
              :seon.boot/cluster-name "fixture-proof"})
            after
            (d/q
             '[:find [?key ...]
               :where
               [_ :seon.schema/key ?key]]
             @connection)]
        (is (= 1 (:seon.reconcile/operations result)))
        (is (= (set before) (set after)))))))

(deftest explicit-synthetic-schema-rows-extend-only-that-database
  (test-support/with-database
    {::test-support/extra-schema marker-schema}
    (fn [connection]
      (d/transact connection [{::marker "installed"}])
      (is (= "installed"
             (d/q
              '[:find ?value .
                :where
                [_ :seon.test-support-test/marker ?value]]
              @connection))))))
