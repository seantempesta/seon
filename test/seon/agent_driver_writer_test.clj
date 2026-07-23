(ns seon.agent-driver-writer-test
  "JVM claim-driver reply-policy regressions."
  (:require [clojure.test :refer [deftest is]]
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
