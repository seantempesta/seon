(ns seon.agent-driver-writer-test
  "JVM claim-driver reply-policy regressions."
  (:require [clojure.test :refer [deftest is]]
            [seon.db :as db]
            [seon.agent.driver.host :as driver.host]
            [seon.agent.turn.core :as turn.core]
            [seon.host.eval :as host.eval]))

(deftest successful-attempt-freezes-the-reply-evaluation
  (let [reply-evaluation #'driver.host/successful-reply-evaluation]
    (is (= :first-form
           (reply-evaluation
            {:seon.agent.turn/llm-attempts
             [{:seon.ai.attempt/ordinal 0
               :seon.ai.attempt/outcome :success
               :seon.ai.attempt/reply-evaluation :first-form}
              {:seon.ai.attempt/ordinal 1
               :seon.ai.attempt/outcome :outer-timeout
               :seon.ai.attempt/reply-evaluation :batch}]})))
    (is (= :batch
           (reply-evaluation
            {:seon.agent.turn/llm-attempts
             [{:seon.ai.attempt/ordinal 0
               :seon.ai.attempt/outcome :transport-error
               :seon.ai.attempt/reply-evaluation :first-form}
              {:seon.ai.attempt/ordinal 1
               :seon.ai.attempt/outcome :success
               :seon.ai.attempt/reply-evaluation :batch}]})))))

(deftest reply-program-receives-the-successful-attempts-frozen-mode
  (let [received (atom nil)
        turn {:seon.agent.turn/reply-blob {:my.blob/hash "reply-hash"}
              :seon.agent.turn/llm-attempts
              [{:seon.ai.attempt/ordinal 0
                :seon.ai.attempt/outcome :success
                :seon.ai.attempt/reply-evaluation :batch}]}]
    (with-redefs-fn
      {#'driver.host/read-blob
       (fn [_storage-view _hash]
         {:my.blob/ok? true :my.blob/content "(+ 1 2)"})
       #'host.eval/agent-home-ns (constantly 'my.agent)
       #'turn.core/reply-program
       (fn [raw-reply reply-evaluation starting-ns]
         (reset! received [raw-reply reply-evaluation starting-ns])
         {:seon.repl/eval-entries [] :seon.repl/errors []})}
      (fn []
        (#'driver.host/reply-program nil turn "agent")))
    (is (= ["(+ 1 2)" :batch 'my.agent] @received))))

(deftest unplannable-reply-does-not-open-the-eval-phase
  (let [transactions (atom [])
        steering
        {:seon.error/message "No exact execution plan."
         :seon.error/kind :agent
         :seon.error/data
         {:seon.execution/roots ['(unknown/call)]
          :seon.execution/missing-capability-leaves #{"unknown/call"}
          :seon.execution/missing-artifact-exports #{}
          :seon.execution/missing-schema-keys #{}
          :seon.execution/unresolved
          [{:seon.execution/reason :unresolved-symbol}]
          :seon.execution/planned-basis {:t 7}
          :seon.execution/observed-basis {:t 7}
          :seon.execution/planned-generation "graph-7"
          :seon.execution/observed-generation "graph-7"
          :seon.execution/eligible-tiers #{}
          :seon.execution/inspected-tiers #{:jvm}}}
        claim
        {:seon.db/db {:t 7}
         :seon.agent.run/claim-epoch 2
         :seon.agent.driver/run
         {:seon.agent/id "agent"
          :seon.agent.run/id "run"
          :seon.agent.run/current-turn
          {:seon.agent.turn/id "turn"}}}]
    (with-redefs-fn
      {#'driver.host/reply-program
       (fn [& _] {:seon.repl/eval-entries
                  [{:seon.repl/kind :form
                    :seon.repl/form '(unknown/call)}]})
       #'driver.host/invocation-configuration! (constantly {})
       #'driver.host/parsed-reply-plan
       (fn [& _]
         {:seon.execution/plan {}
          :seon.agent.driver/disposition
          {:seon.agent.driver/disposition :steering
           :seon.agent.driver/error steering}})
       #'db/transact!
       (fn [request]
         (swap! transactions conj request)
         {:db-after {:t 8}})}
      (fn []
        (is (= steering (#'driver.host/eval-step! {} nil claim)))))
    (is (empty? @transactions)
        "planning steering precedes phase and receipt transactions")))
