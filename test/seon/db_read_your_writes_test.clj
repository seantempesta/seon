(ns seon.db-read-your-writes-test
  "A write through `seon.db/transact!` answers after its commit, so the
  connection's current value (`seon.db/db`) already holds it: a call after a
  write reads it (AGENTS \"a call after a write sees it\")."
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.id :as id]
            [seon.test-support :as support]))

(deftest a-write-is-on-the-connection-when-it-answers
  (let [connection (:seon.db/connection (support/execution-handle nil))
        agent-ids (mapv (fn [_] (str "read-your-writes-" (id/id))) (range 3))
        reports (mapv #(db/transact! connection (support/agent-tx @connection %)) agent-ids)
        seen (fn [agent-id]
               (db/q '[:find ?a . :in $ ?id :where [?a :seon.agent/id ?id]]
                     (db/db connection) agent-id))]
    (is (every? :db-after reports))
    (is (every? some? (map seen agent-ids))
        "every answered write is on the connection's value, read right after it answers")
    (is (<= (db/basis-t (:db-after (peek reports))) (db/basis-t (db/db connection)))
        "the connection's value is at or past the last write's basis, never an earlier commit")))
