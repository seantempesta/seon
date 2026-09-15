(ns seon.turn-backstop-test
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.ai :as ai]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.test-support :as test-support]
            [seon.turn :as turn]))

(deftest completion-observer-uses-the-admitted-provider-and-evaluation-parts
  (test-support/with-database
    (fn [connection]
      (let [settings (test-support/effective-config
                      {:seon.config.ai/timeout-ms 30000
                       :seon.config.ai.backup/model :seon.config/absent
                       :seon.config.ai.retry/maximum-retries 2
                       :seon.config.ai.retry/base-delay-ms 100
                       :seon.config.ai.retry/multiplier 2.0
                       :seon.config.ai.retry/jitter-fraction 0.0
                       :seon.config.ai.retry/maximum-total-delay-ms 300})
            targets (ai/targets @connection settings)
            schedule (ai/delays (ai/retry-strategy settings) (constantly 0.5))]
        (is (= [100 200] schedule))
        (is (= 90300 (#'turn/provider-wait-ms
                     (assoc targets :seon.turn.loop/schedule schedule))))
        (is (= 70000 (#'turn/provider-wait-ms
                     (assoc (ai/targets @connection
                                       (assoc settings :seon.config.ai.backup/model "deepseek-flash"
                                                       :seon.config.ai.backup/timeout-ms 40000))
                            :seon.turn.loop/schedule [])))))
      (doseq [[expected message] [[:seon.ai/completion "provider response"]
                                  [:seon.sci.eval/evaluation "evaluation completion"]]]
       (with-open [faults (test-support/closeable (async/chan 1) async/close!)]
        (let [state (atom nil)
              observer (#'turn/arm-turn-completion-backstop!
                        {:seon.agent/executor (cluster/projection-executor
                                               (:seon.sci.eval/projection-state
                                                (test-support/fork-cluster-ctx connection)))
                         :seon.agent/timeout-ms 100
                         :seon.agent/backstop-state state
                         :seon.agent/fault-channel @faults
                         :seon.agent/agent-id "missing-evaluation"
                         :seon.agent/run-id (atom "missing-evaluation-turn")})]
          ((:seon.turn.loop/await-part observer) expected 100)
          (let [failure (try
                          (#'agent/await-turn-completion!
                           (atom {:seon.agent/fault-channel @faults})
                           {:seon.agent/id "missing-evaluation"
                            :seon.agent/turn-stopped (async/promise-chan)
                            :seon.agent/turn-backstop-state state
                            :seon.turn.loop/cluster {:seon.db/connection connection}})
                          nil
                          (catch clojure.lang.ExceptionInfo failure failure))]
            (is (str/includes? (ex-message failure) message))
            (is (= 200 (:seon.config.agent/turn-completion-backstop-ms (ex-data failure)))))))))))
