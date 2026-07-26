(ns seon.agent.driver-test
  (:require [clojure.test :refer [deftest is testing]]
            [seon.agent.driver :as driver]))

(def plan-request
  {:seon.agent/id "agent-a"
   :seon.agent.run/id "run-a"
   :seon.agent.run/claim-epoch 3
   :seon.agent.run/plan-digest "reply-a"
   ::driver/sources ["(+ 1 2)" "(clojure.string/upper-case \"x\")"]})

(deftest ordered-plan-is-one-cas-fenced-transaction
  (let [tx-data (driver/plan-tx-data plan-request)
        run-row (last tx-data)]
    (is (= [:db.fn/cas
            [:seon.agent.run/id "run-a"]
            :seon.agent.run/plan-digest nil "reply-a"]
           (nth tx-data 2)))
    (is (= [0 1]
           (mapv :seon.agent.run.form/ordinal
                 (:seon.agent.run/forms run-row))))
    (is (= ["(+ 1 2)" "(clojure.string/upper-case \"x\")"]
           (mapv :seon.agent.run.form/source
                 (:seon.agent.run/forms run-row))))))

(deftest resume-uses-first-nonterminal-ordinal
  (let [forms (:seon.agent.run/forms
               (last (driver/plan-tx-data plan-request)))]
    (is (= 0 (:seon.agent.run.form/ordinal
              (driver/next-form forms []))))
    (is (= 1
           (:seon.agent.run.form/ordinal
            (driver/next-form
             forms
             [{:seon.eval/ordinal 0 :seon.eval/status :done}
              {:seon.eval/ordinal 1 :seon.eval/status :running}]))))
    (is (nil?
         (driver/next-form
          forms
          [{:seon.eval/ordinal 0 :seon.eval/status :done}
           {:seon.eval/ordinal 1 :seon.eval/status :error}])))))

(deftest allocated-run-and-pointer-share-the-generated-identity
  (let [[run cas]
        (driver/open-run-tx-data
         "run-a" "host-1" "message-a" "agent-a"
         #inst "2026-07-25T22:00:00.000-00:00"
         #inst "2026-07-25T22:02:00.000-00:00")]
    (is (= [:seon.agent.run/id (:seon.agent.run/id run)]
           (last cas)))
    (is (= [:seon.agent.message/id "message-a"]
           (:seon.agent.run/cause run)))
    (is (= 1 (:seon.agent.run/claim-epoch run)))))

(deftest completion-value-closes-run-and-delivers-once
  (let [request {:seon.agent/id "agent-a"
                 :seon.agent.run/id "run-a"
                 :seon.agent.run/claim-epoch 3
                 :seon.agent.turn/id "turn-a"
                 :seon.eval/ordinal 2
                 :seon.eval/at
                 #inst "2026-07-25T22:00:00.000-00:00"}
        tx-data
        (driver/lifecycle-tx-data
         request
         {:seon.agent.lifecycle/disposition :completed
          :seon.agent.lifecycle/result "X"})
        message
        (some #(when (:seon.agent.message/id %) %) tx-data)]
    (is (= :closed
           (:seon.agent.run/status
            (some #(when (:seon.agent.run/status %) %) tx-data))))
    (is (= "X" (:seon.agent.message/content message)))
    (is (= "seon.agent.driver/message"
           (:seon.agent.message/id message))
        "the allocation transaction replaces this local placeholder")))

(deftest rejected-agent-value-terminalizes-receipt-alone
  (let [transactions (atom [])
        transact!
        (fn [tx-data]
          (swap! transactions conj tx-data)
          (when (some #{[:poison]} tx-data)
            (throw (ex-info "poison" {})))
          {:db-after {}})
        result
        (driver/execute-form!
         transact!
         (constantly
          {:seon.sci.eval/value :value
           :seon.sci.eval/record
           {:seon.eval/duration-ms 1
            :seon.eval/fn-entries 1
            :seon.eval/allocated-bytes 1}})
         (fn [_ _] [[:poison]])
         {:seon.agent/id "agent-a"
          :seon.agent.run/id "run-a"
          :seon.agent.run/claim-epoch 3
          :seon.agent.turn/id "turn-a"
          :seon.eval/at #inst "2026-07-25T22:00:00.000-00:00"
          :seon.eval/ordinal 0
          :seon.eval/total 2
          :seon.eval/source "(identity :value)"
          :seon.eval/ns 'my.agent.a
          :seon.sci.interrupt/time-limit-ms 100})]
    (is (= :error (:seon.eval/status result)))
    (is (= 3 (count @transactions)))
    (testing "the recovery transaction contains no rejected value"
      (is (not-any? #{[:poison]} (last @transactions))))
    (is (some #(and (map? %)
                    (= "The evaluated value was not admitted."
                       (:seon.eval/error %)))
              (last @transactions)))))
