(ns seon.db-immutability-test
  "The other half of the simultaneity law: every agent turn holds one
  immutable database value, so a concurrent transaction from any other
  agent can never move the ground under a running computation."
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.test-support :as test-support]))

(deftest a-held-database-value-is-immutable-under-later-transactions
  (test-support/with-database
    (fn [connection]
      (db/transact! connection [{:seon.cluster/name "held-basis"}])
      (let [held @connection
            basis (db/basis-t held)]
        (db/transact! connection [{:seon.cluster/name "after-held"}])
        (is (= basis (db/basis-t held))
            "a held value's basis never advances")
        (is (= ["held-basis"]
               (db/q '[:find [?name ...]
                       :where [_ :seon.cluster/name ?name]]
                     held))
            "a held value never sees a later transaction")
        (is (= #{"held-basis" "after-held"}
               (set (db/q '[:find [?name ...]
                            :where [_ :seon.cluster/name ?name]]
                          @connection)))
            "the connection's current value sees both")))))
