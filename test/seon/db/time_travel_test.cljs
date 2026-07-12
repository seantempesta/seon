(ns seon.db.time-travel-test
  "Behavioral proofs for immutable temporal database views."
  (:require
    [cljs.test :refer [async deftest is]]
    [datahike.api :as d]
    [seon.db :as db]))

(def ^:private value-attr :seon.db.time-travel-test/value)

(defn- fresh-history-conn
  "Open an isolated historical database with one identity attribute."
  []
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history? true}]
    (-> (d/create-database cfg)
        (.then (fn [_] (d/connect cfg {:sync? false})))
        (.then
          (fn [conn]
            (-> (d/transact! conn [{:db/ident value-attr
                                    :db/valueType :db.type/string
                                    :db/cardinality :db.cardinality/one
                                    :db/unique :db.unique/identity}])
                (.then (fn [_] conn))))))))

(deftest temporal-views-report-the-state-they-actually-represent
  (async done
    (-> (fresh-history-conn)
        (.then
          (fn [conn]
            (-> (d/transact! conn [{value-attr "first"}])
                (.then
                  (fn [first-report]
                    (let [first-t (-> first-report :db-after :max-tx)]
                      (-> (d/transact! conn [{value-attr "second"}])
                          (.then
                            (fn [second-report]
                              (let [head @conn
                                    head-t (-> second-report :db-after :max-tx)
                                    past (db/as-of head first-t)
                                    delta (db/since head first-t)]
                                (is (= first-t (db/basis-t past)))
                                (is (= head-t (db/basis-t delta)))
                                (is (= #{["first"]}
                                       (d/q [:find '?v :where ['_ value-attr '?v]]
                                            past)))
                                (is (= #{["second"]}
                                       (d/q [:find '?v :where ['_ value-attr '?v]]
                                            delta)))))))))))))
        (.catch (fn [error]
                  (is false (str "temporal view proof rejected: " error))))
        (.then (fn [_] (done))))))
