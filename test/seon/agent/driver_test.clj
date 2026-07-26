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

(deftest rejected-plan-transaction-refuses-evaluation
  (let [evaluations (atom 0)
        transactions (atom [])
        allocate!
        (fn [{allocations :seon.db.id/allocations}]
          (let [key (:seon.db.id/key (first allocations))]
            {:seon.db.id/ids
             {key (case key
                    :seon.agent.run/id "run-a"
                    :seon.agent.turn/id "turn-a")}}))
        database-functions
        {'db (constantly {})
         'pull (constantly {})
         'transact!
         (fn [{tx-data :seon.db/tx-data}]
           (swap! transactions conj tx-data)
           (if (some #(and (vector? %)
                           (= :seon.agent.run/plan-digest (nth % 2 nil)))
                     tx-data)
             {:seon.error/message "The plan schema is absent."
              :seon.error/kind :core-bug}
             {:db-after {}}))}
        message ["message-a" "agent-a"
                 "Return one harmless form."
                 #inst "2026-07-25T22:00:00.000-00:00"]
        result
        (with-redefs
          [driver/evaluate!
           (fn [_]
             (swap! evaluations inc)
             {:seon.sci.eval/value :evaluated
              :seon.sci.eval/record
              {:seon.eval/duration-ms 1
               :seon.eval/fn-entries 1
               :seon.eval/allocated-bytes 1}})]
          (#'driver/process-message!
           allocate! database-functions
           (constantly {:seon.ai/text "(identity :must-not-run)"})
           "host-1"
           message))]
    (is (= 0 @evaluations)
        "no form crosses the SCI boundary after the plan write fails")
    (is (= {:seon.error/message "The plan schema is absent."
            :seon.error/kind :core-bug}
           result)
        "the driver surfaces the writer's flat error value")
    (is (some
         (fn [tx-data]
           (and (some #(= :error (:seon.agent.turn/status %))
                      (filter map? tx-data))
                (some #(= :error (:seon.agent.run/closed-reason %))
                      (filter map? tx-data))))
         @transactions)
        "the same failure closes the turn and run durably")))

(deftest completed-turn-persists-a-self-attributing-waterfall
  (let [transactions (atom [])
        next-t (atom 100)
        transact-report!
        (fn [tx-data]
          (let [t (swap! next-t inc)]
            (swap! transactions conj {:tx-data tx-data :t t})
            {:db-after {:t t}}))
        allocate!
        (fn [{allocations :seon.db.id/allocations
              transaction-builder :seon.db.id/transaction-builder}]
          (let [key (:seon.db.id/key (first allocations))
                id (case key
                     :seon.agent.run/id "run-a"
                     :seon.agent.turn/id "turn-a"
                     :seon.agent.message/id "reply-a")
                ids {key id}]
            (merge
             {:seon.db.id/ids ids}
             (when transaction-builder
               (transact-report!
                (:seon.db/tx-data (transaction-builder ids)))))))
        database-functions
        {'db (constantly {})
         'pull (constantly {})
         'transact!
         (fn [{tx-data :seon.db/tx-data}]
           (transact-report! tx-data))}
        message-at (java.util.Date.)
        evaluations (atom 0)
        clock (atom 0)
        result
        (binding [driver/*nano-time* #(swap! clock + 1000)]
          (with-redefs
            [driver/evaluate!
             (fn [_]
               (let [ordinal (swap! evaluations inc)]
                 {:seon.sci.eval/value
                  (if (= 1 ordinal)
                    :first
                    {:seon.agent.lifecycle/disposition :completed
                     :seon.agent.lifecycle/result "done"})
                  :seon.sci.eval/record
                  {:seon.eval/duration-ms 0
                   :seon.eval/fn-entries 1
                   :seon.eval/allocated-bytes 1}}))]
            (#'driver/process-message!
             allocate! database-functions
             (constantly
              {:seon.ai/text
               "(identity :first)\n(seon.agent.lifecycle/complete \"done\")"
               :seon.ai/provider-duration-ns 500})
             "host-1"
             ["message-a" "agent-a" "Finish." message-at])))
        timing-row
        (some
         (fn [{:keys [tx-data]}]
           (some #(when (:seon.agent.turn/timings %) %)
                 (filter map? tx-data)))
         @transactions)
        timings (:seon.agent.turn/timings timing-row)
        total-ns (:seon.agent.turn/duration-ns timing-row)
        attributed-ns
        (reduce + (map :seon.agent.turn.timing/duration-ns timings))
        remainder-ns (- total-ns attributed-ns)
        tolerance-ns (max 5000000 (quot total-ns 100))
        settlement-t (:t (last @transactions))
        measured-transaction-refs
        (into #{}
              (keep :seon.agent.turn.timing/transaction)
              timings)]
    (is (= :done (:seon.eval/status result)))
    (is (= #{[:run-admission-transaction-call 0]
             [:turn-transaction-call 0]
             [:context-derivation 0]
             [:provider-request-response 0]
             [:model-envelope-overhead 0]
             [:reply-derivation 0]
             [:plan-transaction-call 0]
             [:eval-admission-transaction-call 0]
             [:eval 0]
             [:eval-terminal-transaction-call 0]
             [:eval-admission-transaction-call 1]
             [:eval 1]
             [:publish-transaction-call 1]}
           (set
            (map
             (juxt :seon.agent.turn.timing/name
                   :seon.agent.turn.timing/ordinal)
             timings)))
        "every transaction/eval is separate and only the final close publishes")
    (is (<= 0 remainder-ns tolerance-ns)
        "derived unexplained wall stays within 5ms or 1%, whichever is larger")
    (is (not (contains? measured-transaction-refs settlement-t))
        "the timing-settlement transaction is an explicit unmeasured artifact")
    (is (every? pos-int?
                (map :seon.agent.turn.timing/duration-ns timings))
        "nanosecond measurements never turn missing evidence into zero")))
