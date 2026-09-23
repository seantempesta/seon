(ns seon.turn-backstop-test
  (:require [seon.schema] [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [seon.ai :as ai]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.test-support :as test-support]
            [seon.turn :as turn]))

(def ^:private observation-ms
  "One second bounds observing cancellation or the admitted 200 ms fault."
  1000)

(deftest cancelled-completion-observer-releases-every-existing-waiter
  (test-support/with-database
   (fn [connection]
     (let [ctx (test-support/fork-cluster-ctx connection)
           state (atom nil)
           observer
           (#'turn/arm-turn-completion-backstop!
            {:seon.agent/executor
             (cluster/projection-executor (:seon.sci.eval/projection-state ctx))
             :seon.agent/timeout-ms
             (* 1000 test-support/event-backstop-seconds)
             :seon.agent/backstop-state state
             :seon.agent/fault-channel (async/chan 1)
             :seon.agent/agent-id "cancelled-observer"
             :seon.agent/run-id (atom nil)})
           completion (future (async/<!! (:seon.agent/failure-channel observer)))]
       (is (identical? observer @state))
       (try
         (async/offer! (:seon.agent/cancel observer) :seon.agent/completed)
         (is (nil? (test-support/await-event! completion ::observer-cancelled
                                             (constantly true) observation-ms)))
         (is (nil? @state))
         (finally
           (async/offer! (:seon.agent/cancel observer) :seon.agent/completed)))))))

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
            targets (ai/targets (seon.schema/handed-projection) @connection settings)
            schedule (ai/delays (ai/retry-strategy settings) (constantly 0.5))]
        (is (= [100 200] schedule))
        (is (= 90300 (#'turn/provider-wait-ms
                     (assoc targets :seon.turn.loop/schedule schedule))))
        (is (= 70000 (#'turn/provider-wait-ms
                     (assoc (ai/targets (seon.schema/handed-projection) @connection (assoc settings :seon.config.ai.backup/model "deepseek-flash"
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
          (let [fault (test-support/await-event!
                       @faults ::backstop-fault
                       #(= :seon.agent/turn-completion-backstop (::flow/op %))
                       observation-ms)
                joined (future (try
                          (#'agent/await-turn-completion!
                           (atom {:seon.agent/fault-channel @faults})
                           {:seon.agent/id "missing-evaluation"
                            :seon.agent/turn-stopped (async/promise-chan)
                            :seon.agent/turn-backstop-state state
                            :seon.turn.loop/cluster {:seon.db/connection connection}})
                          nil
                          (catch clojure.lang.ExceptionInfo failure failure)))
                failure (test-support/await-event! joined ::backstop-joined
                                                   (constantly true) observation-ms)]
            (is (identical? (::flow/ex fault) failure)
                "The join reports the exact fault the observer published.")
            (is (str/includes? (ex-message failure) message))
            (is (= 200 (:seon.config.agent/turn-completion-backstop-ms (ex-data failure)))))))))))

(deftest a-thrown-step-cancels-its-completion-backstop
  ;; A process identity no request admits makes the pass throw after the
  ;; backstop is armed; the step republishes the permit and cancels the bound.
  ;; The bound (2 s) exceeds the throwing pass (the contract refusal measured
  ;; 197 ms), so only a missed cancel can publish a fault inside the window.
  (test-support/with-database
   (fn [connection]
     (with-open [faults (test-support/closeable (async/chan 1) async/close!)]
       (let [ctx (test-support/fork-cluster-ctx connection)
             backstop-state (atom nil)
             backstop-ms 2000
             armed (atom [])
             completion (async/chan 1)
             state {:seon.agent/id "thrown-step"
                    :seon.turn.loop/cluster
                    {:seon.db/connection connection
                     :seon.cluster/name (:seon.cluster/name (test-support/execution-handle connection))
                     :seon.db.process/id :not-a-process
                     :seon.turn.loop/completion completion
                     :seon.flow/executor
                     (cluster/projection-executor (:seon.sci.eval/projection-state ctx))
                     :seon.agent/fault-channel @faults
                     :seon.config.agent/turn-completion-backstop-ms backstop-ms
                     :seon.agent/turn-backstop-state backstop-state}}]
         ;; A waiting message gives the pass work, so it reaches `turn`.
         (test-support/transacted!
          connection (agent/creation-tx
                      {:seon.agent/id "thrown-step" :seon.ns/name 'my.agents.thrown-step
                       :seon.cluster/name (get-in state [:seon.turn.loop/cluster :seon.cluster/name])}))
         (test-support/transacted!
          connection [{:seon.message/id "thrown-step-wake" :seon.message/content "wake"
                       :seon.message/to [:seon.agent/id "thrown-step"]}])
         (add-watch backstop-state ::armed
                    (fn [_ _ _ observer] (swap! armed conj (some? observer))))
         (async/offer! completion :seon.agent/ready)
         (is (thrown? Exception (turn/step state :seon.agent/episode :seon.agent/wake)))
         (is (= [true false] @armed) "Armed after the permit, cleared by the throw.")
         (is (= :seon.agent/ready (async/poll! completion)) "The permit is republished.")
         ;; The whole bound passes with no fault: the await's timeout is the
         ;; declared observation, its ex-data the evidence.
         (let [observed (try (test-support/await-event! @faults ::backstop-fault
                                                        (constantly true) (+ backstop-ms 200))
                             (catch clojure.lang.ExceptionInfo absent (ex-data absent)))]
           (is (= (+ backstop-ms 200) (::test-support/timeout-ms observed))
               (str "No backstop fault follows the thrown step's own fault: "
                    (pr-str observed)))))))))
