(ns seon.server.temporal-read-test
  "Behavioral proofs for historical wire reads."
  (:require
    [clojure.test :refer [deftest is]]
    [datahike.api :as d]
    [seon.server.boot :as boot]
    [seon.server.wire :as wire]))

(def ^:private value-attr :seon.server.temporal-read-test/value)
(def ^:private runtime (boot/writer-runtime))

(defn- with-history-conn
  "Call `f` with a fresh historical connection, then release it."
  [f]
  (let [cfg {:store {:backend :memory :id (random-uuid)}
             :schema-flexibility :write
             :keep-history? true}]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (try
        (d/transact conn [{:db/ident value-attr
                           :db/valueType :db.type/string
                           :db/cardinality :db.cardinality/one
                           :db/unique :db.unique/identity}])
        (f conn)
        (finally
          (d/release conn))))))

(deftest historical-wire-read-echoes-its-selected-coordinate
  (with-history-conn
    (fn [conn]
      (let [first-report (d/transact conn [{value-attr "first"}])
            first-t (-> first-report :db-after :max-tx)
            _ (d/transact conn [{value-attr "second"}])
            response (wire/handle-op
                       runtime
                       conn
                       {:seon.store.wire/op "q"
                        :seon.store.wire/query
                        [:find '?v :where ['_ value-attr '?v]]
                        :seon.store.wire/args []
                        :seon.store.wire/basis-t first-t})]
        (is (true? (:seon.store.wire/ok response)))
        (is (= first-t (:seon.store.wire/basis-t response)))
        (is (= #{["first"]} (:seon.store.wire/result response)))))))
