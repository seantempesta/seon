(ns seon.agent.jvm-runtime-schema-test
  "JVM coverage for portable agent, attempt, run, turn, and message contracts."
  (:require
    [clojure.test :refer [deftest is]]
    [seon.agent.core]
    [seon.agent.message]
    [seon.agent.run.core :as run.core]
    [seon.agent.turn]
    [seon.ai.attempt]
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
    (is (contains? registered :seon.agent.turn/evals))
    (is (contains? registered :seon.agent.turn/current-id))
    (is (contains? registered :seon.agent.turn/id-of-turn))
    (is (contains? registered :seon.agent.turn/llm-attempts))
    (is (contains? registered :seon.agent.turn/phase))
    (is (contains? registered :seon.agent.turn/prompt-blob))
    (is (contains? registered :seon.agent.turn/reply-blob))
    (is (contains? registered :seon.agent.turn/scheduled?))
    (is (every?
         registered
         [:seon.ai.attempt/id
          :seon.ai.attempt/ordinal
          :seon.ai.attempt/config-digest
          :seon.ai.attempt/deadline-at
          :seon.ai.attempt/provider
          :seon.ai.attempt/adapter
          :seon.ai.attempt/requested-model
          :seon.ai.attempt/temperature
          :seon.ai.attempt/max-tokens
          :seon.ai.attempt/thinking
          :seon.ai.attempt/endpoint
          :seon.ai.attempt/adapter-timeout-ms
          :seon.ai.attempt/outer-timeout-ms
          :seon.ai.attempt/stream?
          :seon.ai.attempt/reply-evaluation
          :seon.ai.attempt/partial-text
          :seon.ai.attempt/extra-body-digest
          :seon.ai.attempt/dg-backend
          :seon.ai.attempt/api-key-env
          :seon.ai.attempt/credential-class
          :seon.ai.attempt/outcome
          :seon.ai.attempt/error-status
          :seon.ai.attempt/response-model
          :seon.ai.attempt/system-fingerprint
          :seon.ai.attempt/request-id
          :seon.ai.attempt/evidence-error
          :seon.ai.attempt/entity]))
    (is (every?
         (set (schema/canonical-database-attributes))
         [:seon.agent.run/plan-digest
          :seon.agent.run/forms
          :seon.agent.run.form/id
          :seon.agent.run.form/run
          :seon.agent.run.form/ordinal
          :seon.agent.run.form/source
          :seon.agent.turn/duration-ns
          :seon.agent.turn/timings
          :seon.agent.turn.timing/name
          :seon.agent.turn.timing/ordinal
          :seon.agent.turn.timing/duration-ns
          :seon.agent.turn.timing/transaction])
        "the cold page-plan authority includes plan and timing facts")))

(deftest attempt-schema-matches-the-durable-evidence-row
  (let [attempt
        {:seon.ai.attempt/id "a00000000000"
         :seon.ai.attempt/ordinal 0
         :seon.ai.attempt/config-digest
         "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
         :seon.ai.attempt/deadline-at (java.util.Date. 45000)
         :seon.ai.attempt/provider :deepseek
         :seon.ai.attempt/adapter :openai-compat
         :seon.ai.attempt/requested-model "small-model"
         :seon.ai.attempt/temperature 0.0
         :seon.ai.attempt/max-tokens 512
         :seon.ai.attempt/endpoint
         "http://127.0.0.1:8080/v1/chat/completions"
         :seon.ai.attempt/adapter-timeout-ms 30000
         :seon.ai.attempt/outer-timeout-ms 45000
         :seon.ai.attempt/stream? false
         :seon.ai.attempt/reply-evaluation :batch
         :seon.ai.attempt/credential-class :configured-env
         :seon.ai.attempt/outcome :success}]
    (is (schema/valid-candidate-value?
         :seon.ai.attempt/entity attempt))
    (is (not (schema/valid-candidate-value?
              :seon.ai.attempt/entity
              (assoc attempt :seon.ai.attempt/ordinal -1))))))

(deftest attempt-and-turn-attributes-derive-honest-database-facets
  (is
   (=
    [{:db/ident :seon.ai.attempt/id
      :db/valueType :db.type/string
      :db/cardinality :db.cardinality/one
      :db/unique :db.unique/identity}
     {:db/ident :seon.ai.attempt/partial-text
      :db/valueType :db.type/string
      :db/cardinality :db.cardinality/one
      :db/noHistory true}
     {:db/ident :seon.agent.turn/llm-attempts
      :db/valueType :db.type/ref
      :db/cardinality :db.cardinality/many
      :db/isComponent true}
     {:db/ident :seon.agent.turn/prompt-blob
      :db/valueType :db.type/ref
      :db/cardinality :db.cardinality/one}
     {:db/ident :seon.agent.turn/scheduled?
      :db/valueType :db.type/boolean
      :db/cardinality :db.cardinality/one}
     {:db/ident :seon.agent/schedules
      :db/valueType :db.type/ref
      :db/cardinality :db.cardinality/many
      :db/isComponent true}]
    (db/malli->datahike-schema
     [:seon.ai.attempt/id
      :seon.ai.attempt/partial-text
      :seon.agent.turn/llm-attempts
      :seon.agent.turn/prompt-blob
      :seon.agent.turn/scheduled?
      :seon.agent/schedules])))
  (is (= :set
         (first (schema/schema-definition
                 :seon.agent.turn/llm-attempts))))
  (is (= :set
         (first (schema/schema-definition :seon.agent/schedules)))))

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

(deftest turn-timings-derive-component-and-transaction-ref-facets
  (is
   (=
    [{:db/ident :seon.agent.turn/duration-ns
      :db/valueType :db.type/long
      :db/cardinality :db.cardinality/one}
     {:db/ident :seon.agent.turn/timings
      :db/valueType :db.type/ref
      :db/cardinality :db.cardinality/many
      :db/isComponent true}
     {:db/ident :seon.agent.turn.timing/duration-ns
      :db/valueType :db.type/long
      :db/cardinality :db.cardinality/one}
     {:db/ident :seon.agent.turn.timing/transaction
      :db/valueType :db.type/ref
      :db/cardinality :db.cardinality/one}]
    (db/malli->datahike-schema
     [:seon.agent.turn/duration-ns
      :seon.agent.turn/timings
      :seon.agent.turn.timing/duration-ns
      :seon.agent.turn.timing/transaction]))))

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
