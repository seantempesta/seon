(ns seon.db-test
  "Tests for the seon.db public API (transact!/query/pull-by-name/pull-many-by-name)
   against the datahike `:memory` fixture. Ported from the legacy
   `with-temp-conn` + `*direct-mode*` + `*conn-manager*` shape in M-2b.

   Decision 7 stamping (`:seon.db/namespace`) is applied automatically by
   `seon.db/transact!`. Schema enforcement (unregistered-attr rejection)
   fires before the flow dispatch so it is exercised here without needing
   any flow-side data."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.test-utils :as tu]))

;;; ---------------------------------------------------------------------------
;;; Helpers
;;; ---------------------------------------------------------------------------

;; Register test attrs so schema enforcement passes
(schema/register! ::name :string)
(schema/register! ::age :int)

(def ^:private test-malli-schema
  "Malli :map schema for the fixture's datahike conn-process. Lands as
   datahike idents via `seon.db.datahike.schema/malli-map->datahike-schema`
   at conn-process :init."
  [:map
   [::name :string]
   [::age :int]])

(use-fixtures :each
  (tu/with-test-db-fixture
    {::tu/namespaces [:seon]
     ::tu/schemas    {:seon test-malli-schema}}))

;;; ---------------------------------------------------------------------------
;;; Named Convenience API
;;; ---------------------------------------------------------------------------

(deftest query-test
  (testing "query resolves db by name and runs Datalog"
    (db/transact! :seon [{::name "Alice" ::age 30}])
    (let [results (db/query :seon
                            '[:find ?n
                              :in $ ?attr
                              :where [?e ?attr ?n]]
                            ::name)]
      (is (= #{["Alice"]} (set results))))))

(deftest query-with-inputs-test
  (testing "query passes additional inputs to the underlying query engine"
    (db/transact! :seon [{::name "Bob" ::age 25}
                         {::name "Carol" ::age 35}])
    (let [results (db/query :seon
                            '[:find ?n
                              :in $ ?min-age ?name-attr ?age-attr
                              :where
                              [?e ?name-attr ?n]
                              [?e ?age-attr ?a]
                              [(>= ?a ?min-age)]]
                            30 ::name ::age)]
      (is (= #{["Carol"]} (set results))))))

(deftest pull-by-name-test
  (testing "pull-by-name resolves entity by eid"
    (db/transact! :seon [{::name "Dave" ::age 40}])
    (let [eid (ffirst (db/query :seon
                                '[:find ?e
                                  :in $ ?name-attr
                                  :where [?e ?name-attr "Dave"]]
                                ::name))
          result (db/pull-by-name :seon [::name ::age] eid)]
      (is (= "Dave" (::name result)))
      (is (= 40 (::age result))))))

(deftest pull-many-by-name-test
  (testing "pull-many-by-name resolves multiple entities"
    (db/transact! :seon [{::name "Eve" ::age 28}
                         {::name "Frank" ::age 33}])
    (let [eids (mapv first (db/query :seon
                                     '[:find ?e
                                       :in $ ?name-attr
                                       :where [?e ?name-attr _]]
                                     ::name))
          results (db/pull-many-by-name :seon [::name] eids)]
      (is (= 2 (count results)))
      (is (= #{["Eve"] ["Frank"]}
             (set (map (comp vector ::name) results)))))))

;;; ---------------------------------------------------------------------------
;;; Schema enforcement (validates attrs without needing infrastructure flow)
;;; ---------------------------------------------------------------------------

(deftest transact-rejects-unregistered-attrs-test
  (testing "transact! throws for unregistered attributes"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Unregistered attributes"
         (db/transact! :seon [{:bogus/unregistered "nope"}])))))

(deftest transact-rejects-unregistered-vector-tuple-test
  (testing "transact! throws for unregistered attrs in vector tuples"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Unregistered attributes"
         (db/transact! :seon [[:db/add 1 :bogus/field "val"]])))))
