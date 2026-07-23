(ns seon.agent.turn-llm-writer-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.agent.driver.host :as driver.host]
            [seon.agent.run.core :as run.core]
            [seon.agent.turn.core :as turn.core]))

(defn- schema [ident value-type & {:keys [cardinality unique]}]
  (cond-> {:db/ident ident
           :db/valueType value-type
           :db/cardinality (or cardinality :db.cardinality/one)}
    unique (assoc :db/unique :db.unique/identity)))

(defn- cas-envelope [failure]
  (loop [failure failure]
    (when failure
      (let [data (ex-data failure)]
        (if (= :transact/cas (:error data))
          data
          (recur (ex-cause failure)))))))

(deftest open-attempt-terminal-cas-is-a-real-database-fence
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :schema-flexibility :write}
        _ (d/create-database configuration)
        connection (d/connect configuration)
        schemas [(schema :seon.agent/id :db.type/string :unique true)
                 (schema :seon.agent/run :db.type/ref)
                 (schema :seon.agent.run/id :db.type/string :unique true)
                 (schema :seon.agent.run/claim-epoch :db.type/long)
                 (schema :seon.agent.turn/id :db.type/string :unique true)
                 (schema :seon.agent.turn/phase :db.type/keyword)
                 (schema :seon.agent.turn/llm-attempts :db.type/ref
                         :cardinality :db.cardinality/many)
                 (schema :seon.agent.turn/reply-blob :db.type/ref)
                 (schema :seon.ai.attempt/id :db.type/string :unique true)
                 (schema :seon.ai.attempt/outcome :db.type/keyword)
                 (schema :my.blob/hash :db.type/string :unique true)]
        _ (d/transact connection schemas)
        _ (d/transact
           connection
           [{:seon.agent.run/id "run" :seon.agent.run/claim-epoch 7}
            {:seon.agent/id "agent"
             :seon.agent/run [:seon.agent.run/id "run"]}
            {:seon.agent.turn/id "turn"
             :seon.agent.turn/phase :attempt-open
             :seon.agent.turn/llm-attempts
             [{:seon.ai.attempt/id "attempt"
               :seon.ai.attempt/outcome :open}]}
            {:my.blob/hash "reply"}])
        tx-data
        (turn.core/terminal-attempt-tx-data
         (run.core/run-fence "agent" "run" 7)
         "turn" "attempt" {:seon.ai.attempt/outcome :success}
         [:my.blob/hash "reply"])]
    (try
      (d/transact connection tx-data)
      (is (= :success
             (:seon.ai.attempt/outcome
              (d/pull @connection
                      [:seon.ai.attempt/outcome]
                      [:seon.ai.attempt/id "attempt"]))))
      (let [result
            (try
              (d/transact connection tx-data)
              (catch Throwable failure
                failure))
            loser (if (instance? Throwable result)
                    (cas-envelope result)
                    result)]
        (is (= :transact/cas (:error loser))
            "the second terminalizer retains the direct CAS envelope")
        (is (= :attempt-open (:expected loser))))
      (finally
        (d/release connection)
        (d/delete-database configuration)))))

(deftest llm-eligibility-requires-real-platform-leaves
  (is (= #{:seon.agent.driver.capability/eval}
         (driver.host/claimant-capabilities {})))
  (is (= #{:seon.agent.driver.capability/eval}
         (driver.host/claimant-capabilities
          {:seon.agent.driver/llm-transport! identity})))
  (is (contains?
       (driver.host/claimant-capabilities
        {:seon.agent.driver/llm-transport! identity
         :seon.agent.driver/blob-leaf {:my.blob/put! identity}})
       :seon.agent.driver.capability/llm)))
