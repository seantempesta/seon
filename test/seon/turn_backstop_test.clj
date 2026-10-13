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
                      {:seon.config.ai/timeout-ms 30000})
            targets (ai/targets (seon.schema/handed-projection) @connection settings)]
        (is (= 30000 (#'turn/provider-wait-ms targets))
            "the one provider call's timeout is the whole admitted provider part"))
      (doseq [[expected message] [[:seon.ai/completion "provider response"]
                                  [:seon.sci.eval/evaluation "evaluation completion"]]]
        (let [state (atom nil)
              observer (#'turn/arm-turn-completion-backstop!
                        {:seon.agent/executor (cluster/projection-executor
                                               (:seon.sci.eval/projection-state
                                                (test-support/fork-cluster-ctx connection)))
                         :seon.env/environment (test-support/environment
                                                (:seon.cluster/name (test-support/execution-handle connection))
                                                connection)
                         :seon.agent/timeout-ms 100
                         :seon.agent/process "backstop-test-process"
                         :seon.agent/backstop-state state
                         :seon.agent/agent-id "missing-evaluation"
                         :seon.agent/run-id (atom "missing-evaluation-turn")})]
          ((:seon.turn.loop/await-part observer) expected 100)
          ;; the failure the one fault route (`seon.fault/fault!`) recorded
          (let [failure (test-support/await-event!
                         (:seon.agent/failure-channel observer) ::backstop-fault
                         (constantly true) observation-ms)]
            (is (str/includes? (ex-message failure) message))
            (is (= 200 (:seon.config.agent/turn-completion-backstop-ms (ex-data failure))))))))))

(deftest a-thrown-step-cancels-its-completion-backstop
  ;; A process identity no request admits makes the pass throw after the
  ;; backstop is armed; the step republishes the permit and cancels the bound.
  ;; The bound (2 s) exceeds the throwing pass (the contract refusal measured
  ;; 197 ms), so only a missed cancel can publish a fault inside the window.
  (test-support/with-database
   (fn [connection]
     (let [ctx (test-support/fork-cluster-ctx connection)
             backstop-state (atom nil)
             backstop-ms 2000
             armed (atom [])
             observer (atom nil)
             completion (async/chan 1)
             state {:seon.agent/id "thrown-step"
                    :seon.turn.loop/cluster
                    (test-support/cluster-handle
                    {:seon.db/connection connection
                     :seon.cluster/name (:seon.cluster/name (test-support/execution-handle connection))
                     :seon.db.process/id :not-a-process
                     :seon.turn.loop/completion completion
                     :seon.flow/executor
                     (cluster/projection-executor (:seon.sci.eval/projection-state ctx))
                     :seon.config.agent/turn-completion-backstop-ms backstop-ms
                     :seon.agent/turn-backstop-state backstop-state})}]
         ;; A waiting message gives the pass work, so it reaches `turn`.
         (test-support/transacted!
          connection (agent/creation-tx
                      {:seon.agent/id "thrown-step" :seon.ns/name 'my.agents.thrown-step
                       :seon.cluster/name (get-in state [:seon.turn.loop/cluster :seon.cluster/name])}))
         (test-support/assign-task! connection "thrown-step")
         (test-support/transacted!
          connection [{:seon.message/id "thrown-step-wake" :seon.message/content "wake"
                       :seon.message/to [:seon.agent/id "thrown-step"]}])
         (add-watch backstop-state ::armed
                    (fn [_ _ _ armed-observer]
                      (when armed-observer (reset! observer armed-observer))
                      (swap! armed conj (some? armed-observer))))
         (async/offer! completion :seon.agent/ready)
         (is (thrown? Exception (turn/step state :seon.agent/episode :seon.agent/wake)))
         (is (= [true false] @armed) "Armed after the permit, cleared by the throw.")
         (is (= :seon.agent/ready (async/poll! completion)) "The permit is republished.")
         ;; The whole bound passes with no fault: the cancelled observer
         ;; closes its failure channel, a fault would have delivered a failure.
         (let [[failure port] (async/alts!! [(:seon.agent/failure-channel @observer)
                                             (async/timeout (+ backstop-ms 200))])]
           (is (nil? failure) "No backstop fault follows the thrown step's own fault")
           (is (identical? port (:seon.agent/failure-channel @observer)) "The cancel closed the observer, the bound never fired"))))))
