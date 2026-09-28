(ns seon.db-applied-write-test
  "`seon.db/transact-applied!` answers once the writer has applied a write, in
  writer order, and hands a failed commit to its caller; `seon.db/transact!`
  still answers only after the commit, so every write that is not an agent
  form's own settlement stays durable before its caller is answered."
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [datahike.gc-guard :as gc-guard]
            [seon.blob :as blob]
            [seon.db :as db]
            [seon.id :as id]
            [seon.test-support :as support]))

(deftest an-applied-write-answers-in-order-and-an-ordinary-write-after-its-commit
  (let [connection (:seon.db/connection (support/execution-handle nil))
        failed (promise)
        agent-id #(str "applied-write-" (id/id))]
    (testing "an ordinary write (a task, an accept, a config apply) answers after its commit"
      (let [report (db/transact! connection (support/agent-tx @connection (agent-id)))]
        (is (:db-after report))
        (is (<= (db/basis-t (:db-after report)) (db/basis-t @connection))
            "the connection already holds the committed value when the caller is answered")))
    (testing "an applied write answers with the writer's value; the next write builds on it"
      (let [first-id (agent-id)
            second-id (agent-id)
            first-report (db/transact-applied! connection (support/agent-tx @connection first-id)
                                               #(deliver failed %))
            second-report (db/transact! connection (support/agent-tx @connection second-id))]
        (is (:db-after first-report))
        (is (db/q '[:find ?a . :in $ ?id :where [?a :seon.agent/id ?id]] (:db-after first-report) first-id)
            "the applied value holds the write")
        (is (db/q '[:find ?a . :in $ ?id :where [?a :seon.agent/id ?id]] (:db-after second-report) first-id)
            "a later durable write commits after it, in writer order, so the applied write is durable too")
        (is (map? (deref (:seon.db/durable first-report) 5000 nil))
            "the applied report carries its pending commit, which resolves to the committed report")
        (is (not (realized? failed)) "a committed write hands nothing to its failure route")))))

(deftest a-blob-permit-is-held-until-an-applied-write-commits
  (let [connection (:seon.db/connection (support/execution-handle nil))
        store-id (get-in @connection [:config :store :id])
        commit (promise)
        staged (blob/stage! connection (str "applied-write permit " (id/id)))
        _ (blob/with-publication! connection [staged] (fn [] {:seon.db/durable commit}))
        sweep (gc-guard/acquire-sweep-permit! store-id {:sync? false})]
    (try
      (is (nil? (async/poll! sweep)) "no sweep is admitted while the applied write is pending")
      (deliver commit {:committed true})
      (let [[granted] (async/alts!! [sweep (async/timeout 5000)])]
        (is (some? granted) "the sweep is admitted once the commit resolves")
        (some-> granted gc-guard/release-reachability-permit!))
      (finally (deliver commit {:committed true})))))
