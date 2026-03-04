(ns seon.db-test
  (:require [clojure.test :refer [deftest is testing]]
            [datalevin.core :as d]
            [seon.db :as db]
            [seon.db.datalevin.conn :as conn]
            [seon.schema :as schema]
            [seon.test-utils :as tu]))

;;; ---------------------------------------------------------------------------
;;; Helpers
;;; ---------------------------------------------------------------------------

;; Register test attrs so schema enforcement passes
(schema/register! :name :string)
(schema/register! :age :int)

(def ^:private test-schema
  {:name {:db/valueType :db.type/string}
   :age  {:db/valueType :db.type/long}})

;;; ---------------------------------------------------------------------------
;;; Named Convenience API
;;; ---------------------------------------------------------------------------

(defn- make-fake-manager
  "Build a fake conn manager backed by a local Datalevin connection.
   Maps ::master to the given conn."
  [conn]
  {::conn/port 0
   ::conn/connections (atom {:seon {::conn/connection conn}})})

(defn- with-named-db
  "Set up a local db, bind it as the :seon named database via *conn-manager*,
   and call f with the raw connection."
  [f]
  (tu/with-temp-conn test-schema
    (fn [conn]
      (binding [db/*conn-manager* (make-fake-manager conn)]
        (f conn)))))

(deftest query-test
  (testing "query resolves db by name and runs Datalog"
    (with-named-db
      (fn [conn]
        (d/transact! conn [{:name "Alice" :age 30}])
        (let [results (db/query :seon '[:find ?n :where [?e :name ?n]])]
          (is (= #{["Alice"]} (set results))))))))

(deftest query-with-inputs-test
  (testing "query passes additional inputs to d/q"
    (with-named-db
      (fn [conn]
        (d/transact! conn [{:name "Bob" :age 25}
                           {:name "Carol" :age 35}])
        ;; Use an input binding for age threshold
        (let [results (db/query :seon
                                '[:find ?n
                                  :in $ ?min-age
                                  :where
                                  [?e :name ?n]
                                  [?e :age ?a]
                                  [(>= ?a ?min-age)]]
                                30)]
          (is (= #{["Carol"]} (set results))))))))

(deftest pull-by-name-test
  (testing "pull-by-name resolves entity by eid"
    (with-named-db
      (fn [conn]
        (d/transact! conn [{:name "Dave" :age 40}])
        (let [eid (ffirst (d/q '[:find ?e :where [?e :name "Dave"]] @conn))
              result (db/pull-by-name :seon '[:name :age] eid)]
          (is (= "Dave" (:name result)))
          (is (= 40 (:age result))))))))

(deftest pull-many-by-name-test
  (testing "pull-many-by-name resolves multiple entities"
    (with-named-db
      (fn [conn]
        (d/transact! conn [{:name "Eve" :age 28}
                           {:name "Frank" :age 33}])
        (let [eids (mapv first (d/q '[:find ?e :where [?e :name _]] @conn))
              results (db/pull-many-by-name :seon '[:name] eids)]
          (is (= 2 (count results)))
          (is (= #{["Eve"] ["Frank"]}
                 (set (map (comp vector :name) results)))))))))

;;; ---------------------------------------------------------------------------
;;; Schema enforcement (validates attrs without needing infrastructure flow)
;;; ---------------------------------------------------------------------------

(deftest transact-rejects-unregistered-attrs-test
  (testing "transact! throws for unregistered attributes"
    (tu/with-temp-conn test-schema
      (fn [conn]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Unregistered attributes"
             (db/transact! conn [{:bogus/unregistered "nope"}])))))))

(deftest transact-rejects-unregistered-vector-tuple-test
  (testing "transact! throws for unregistered attrs in vector tuples"
    (tu/with-temp-conn test-schema
      (fn [conn]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Unregistered attributes"
             (db/transact! conn [[:db/add 1 :bogus/field "val"]])))))))
