(ns seon.db.claimant-validation-test
  "Claimant writes validate against the retained committed projection."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.agent.driver.host :as driver.host]
            [seon.db :as db]
            [seon.host.context :as context]
            [seon.schema :as schema]))

(def ^:private claimant-projection
  (schema/build-projection
   {:seon.db/lookup-ref-value [:or :string :uuid :keyword :symbol :int]
    :seon.db/ref
    [:or :int :string [:tuple :keyword :seon.db/lookup-ref-value]]
    :seon.db/user :seon.db/ref
    :seon.db/process :seon.db/ref
    :seon.agent.run/claim-epoch [:int {:min 1}]
    :seon.agent.turn/phase
    [:enum :rendered :attempt-open :reply-ready :evaling :evaled :published]}))

(defn- rejected-claimant-transaction [tx-data]
  (let [writer
        {::context/projection-state
         (atom {::context/projection claimant-projection})}
        leaf
        (assoc
         (driver.host/database-leaf writer)
         :seon.db.leaf/transaction-call!
         (fn [_request _recoverable?]
           (throw
            (ex-info
             "Invalid claimant data reached the writer transport."
             {:seon.error/kind :core-bug}))))]
    (binding [db/*leaf* leaf]
      (db/transact! {::db/tx-data tx-data}))))

(deftest invalid-claimant-values-fail-before-the-writer-transport
  (doseq [[label tx-data expected-attribute]
          [["turn phase"
            [{:seon.agent.turn/phase :not-a-turn-phase}]
            :seon.agent.turn/phase]
           ["claim epoch"
            [{:seon.agent.run/claim-epoch 0}]
            :seon.agent.run/claim-epoch]]]
    (testing label
      (let [result (rejected-claimant-transaction tx-data)]
        (is (= :user-input (:seon.error/kind result)))
        (is (= expected-attribute
               (get-in result [:seon.error/data :seon.db/attr])))
        (is (string? (:seon.error/message result)))
        (is (not (contains? result :seon/error)))))))

(deftest claimant-invocation-uses-the-acquired-deadline-and-result-facts
  (let [before (System/currentTimeMillis)
        invocation
        ((var-get #'driver.host/invocation)
         "agent" {:db-name "claimant" :t 1} "run" 3 "turn"
         {:seon.repl/eval-entries []}
         {:seon.config.claim-driver/invocation-deadline-ms 4321
          :seon.config.claim-driver/invocation-result-maximum-bytes 9876}
         {})
        after (System/currentTimeMillis)]
    (is (<= (+ before 4321)
            (:seon.execution/deadline-ms invocation)
            (+ after 4321)))
    (is (= 9876 (:seon.execution/result-limit-bytes invocation)))))
