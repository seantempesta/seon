(ns seon.agent.jvm-runtime-schema-test
  "JVM coverage for the portable run, turn, and message contracts."
  (:require
    [clojure.test :refer [deftest is]]
    [seon.agent.message]
    [seon.agent.run.core :as run.core]
    [seon.db :as db]
    [seon.eval.receipt]
    [seon.schema :as schema]))

(deftest cold-jvm-load-registers-runtime-attributes
  (let [registered (schema/snapshot)]
    (is (contains? registered :seon.agent/run))
    (is (contains? registered :seon.agent.run/id))
    (is (contains? registered :seon.agent.run/process))
    (is (contains? registered :seon.agent.run))
    (is (contains? registered :seon.agent.message/id))
    (is (contains? registered :seon.agent.message))
    (is (contains? registered :seon.agent.turn/id))
    (is (contains? registered :seon.agent.turn/evals))))

(deftest runtime-attributes-derive-the-database-contract
  (is
    (=
      [{:db/ident :seon.agent/run
        :db/valueType :db.type/ref
        :db/cardinality :db.cardinality/one}
       {:db/ident :seon.agent.run/id
        :db/valueType :db.type/string
        :db/cardinality :db.cardinality/one
        :db/unique :db.unique/identity}
       {:db/ident :seon.agent.run/process
        :db/valueType :db.type/string
        :db/cardinality :db.cardinality/one}
       {:db/ident :seon.agent.run/claim-epoch
        :db/valueType :db.type/long
        :db/cardinality :db.cardinality/one}
       {:db/ident :seon.agent.run/lease-until
        :db/valueType :db.type/instant
        :db/cardinality :db.cardinality/one}
       {:db/ident :seon.agent.message/id
        :db/valueType :db.type/string
        :db/cardinality :db.cardinality/one
        :db/unique :db.unique/identity}
       {:db/ident :seon.agent.message/to
        :db/valueType :db.type/ref
        :db/cardinality :db.cardinality/many}
       {:db/ident :seon.agent.turn/id
        :db/valueType :db.type/string
        :db/cardinality :db.cardinality/one
        :db/unique :db.unique/identity}
       {:db/ident :seon.agent.turn/evals
        :db/valueType :db.type/ref
        :db/cardinality :db.cardinality/many
        :db/isComponent true}]
      (db/malli->datahike-schema
        [:seon.agent/run
         :seon.agent.run/id
         :seon.agent.run/process
         :seon.agent.run/claim-epoch
         :seon.agent.run/lease-until
         :seon.agent.message/id
         :seon.agent.message/to
         :seon.agent.turn/id
         :seon.agent.turn/evals]))))

(deftest run-transitions-use-process-custody
  (let [lease-until (java.util.Date. 1000)
        tx-data (run.core/acquire-tx-data
                  "agent-a" "run-a" "process-a" lease-until)
        released (run.core/release-tx-data "agent-a" "run-a" 1)]
    (is (some #{[:db.fn/cas
                 [:seon.agent.run/id "run-a"]
                 :seon.agent.run/process nil "process-a"]}
              tx-data))
    (is (= [:db/retract
            [:seon.agent.run/id "run-a"]
            :seon.agent.run/process]
           (last released)))))

(deftest lease-instant-drives-one-shot-wake-and-takeover
  (let [lease-until (java.util.Date. 1000)
        next-lease (java.util.Date. 2000)
        run {:seon.agent/id "agent-a"
             :seon.agent.run/id "run-a"
             :seon.agent.run/status :open
             :seon.agent.run/process "process-a"
             :seon.agent.run/claim-epoch 4
             :seon.agent.run/lease-until lease-until}
        transition
        (run.core/claim-plan run "process-b" lease-until next-lease)]
    (is (= lease-until (run.core/lease-wake-at run)))
    (is (true? (run.core/live-process? run (java.util.Date. 999))))
    (is (false? (run.core/expired-lease? run (java.util.Date. 999))))
    (is (true? (run.core/expired-lease? run lease-until)))
    (is (false? (run.core/live-process? run lease-until)))
    (is (= :steal (:seon.agent.run/claim-transition transition)))
    (is (some #{[:db.fn/cas
                 [:seon.agent.run/id "run-a"]
                 :seon.agent.run/lease-until lease-until lease-until]}
              (:seon.db/tx-data transition)))
    (is (some #{[:db/add
                 [:seon.agent.run/id "run-a"]
                 :seon.agent.run/lease-until next-lease]}
              (:seon.db/tx-data transition)))))
