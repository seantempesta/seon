(ns seon.agent-call-edges-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is]]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.db :as db]
            [seon.eval :as evaluation]
            [seon.flow :as flow]
            [seon.test :as tests]
            [seon.test-support :as support]
            [seon.turn :as turn]))

(defn exercise!
  "Probe declaration calls through ordinary virtual turns on the supplied connection."
  [connection cluster-name agent-id namespace-name]
  (let [ctx (support/fork-cluster-ctx connection cluster-name)
        environment (support/environment cluster-name connection)
        routing (agent/routing)
        qualified #(str namespace-name "/" %)
        target (qualified "target")
        later (qualified "later")
        test-symbol (qualified "target-test")
        early-test (qualified "early-test")
        faults (async/chan (async/sliding-buffer 16))]
    (with-open [launcher (support/closeable
                         (flow/start-work-launcher!
                          {:seon.env/environment environment
                           :seon.flow/configuration
                           (select-keys (config/effective (db/db connection) cluster-name)
                                        flow/flow-workload-attributes)})
                         flow/stop-work-launcher!)]
      (let [handle (support/cluster-handle
                    {:seon.env/environment environment
                     :seon.db/connection connection :seon.cluster/name cluster-name
                     :seon.sci.eval/ctx ctx :seon.flow/work-launcher @launcher
                     :seon.flow/executor (cluster/projection-executor (:seon.sci.eval/projection-state ctx))
                     :seon.db.process/id cluster/boot-process-identity})
            request {:seon.turn.loop/cluster handle :seon.agent/routing routing
                     :seon.agent/id agent-id}
            submit (fn [source]
                     (let [result (turn/virtual-turn! (assoc request :seon.cluster.reply/text source))
                           turn-id (:seon.turn/id result)]
                       (when-not turn-id (throw (ex-info "Virtual turn refused" result)))
                       (try
                         (support/await-event!
                          connection ::turn-closed
                          (fn [_] (:seon.turn/closed-tx
                                   (db/pull (db/db connection) [:seon.turn/closed-tx]
                                            [:seon.turn/id turn-id]))))
                         (catch Throwable failure
                           (throw (ex-info "Virtual turn did not close"
                                           {:seon.test/source source
                                            :seon.test/fault (some-> (async/poll! faults) pr-str)
                                            :seon.test/turn (db/pull (db/db connection) '[*] [:seon.turn/id turn-id])}
                                           failure))))
                       (let [entries (evaluation/of-agent (db/db connection) agent-id)
                             failures (filterv :seon.cluster.eval/error entries)]
                         (when (or (:seon.error/kind entries) (seq failures))
                           (throw (ex-info "Declaration evaluation failed"
                                           {:seon.test/evaluations
                                            (if (:seon.error/kind entries) entries (mapv #(select-keys % [:seon.cluster.eval/source :seon.cluster.eval/error :seon.eval/shown]) failures))}))))
                       turn-id))
            calls (fn [attribute sym]
                    (into #{} (map :seon.fn/sym)
                          (:seon.fn/calls
                           (db/pull (db/db connection) '[{:seon.fn/calls [:seon.fn/sym]}]
                                    [attribute sym]))))]
        (swap! routing assoc :seon.agent/fault-channel faults)
        (try
          (let [created (db/transact! connection
                         [{:seon.agent/id agent-id
                           :seon.agent/namespace {:seon.ns/name namespace-name}
                           :seon.agent/settings {:seon.config/agent [:seon.agent/id agent-id]
                                                 :seon.config.ai/no-provider true}}])]
            (when (:seon.error/kind created) (throw (ex-info "Probe agent refused" created))))
          (agent/arm! request)
          (submit "(defn target {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))")
          (submit "(clojure.test/deftest target-test (clojure.test/is (= 3 (target 2))))")
          (let [after-calls (calls :seon.test/sym test-symbol)
                before (tests/reach-digest (db/db connection) test-symbol)]
            (submit "(defn target {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 1))")
            (let [after (tests/reach-digest (db/db connection) test-symbol)]
              (submit "(declare later)")
              (submit "(clojure.test/deftest early-test (clojure.test/is (= 4 (later 3))))")
              (let [pending (:seon.fn/pending-calls
                             (db/pull (db/db connection) [:seon.fn/pending-calls]
                                      [:seon.test/sym early-test]))]
                (submit "(defn later {:malli/schema [:=> [:cat :int] :int]} [x] (target x))")
                (submit "(defn shadow {:malli/schema [:=> [:cat :int] :int]} [x] (let [target inc] (target x)))")
                {:seon.test/after-calls after-calls
                 :seon.test/digest-before before :seon.test/digest-after after
                 :seon.test/pending (set pending)
                 :seon.test/early-calls (calls :seon.test/sym early-test)
                 :seon.test/function-calls (calls :seon.fn/sym later)
                 :seon.test/shadow-calls (calls :seon.fn/sym (qualified "shadow"))
                 :seon.test/pending-after (:seon.fn/pending-calls
                                           (db/pull (db/db connection) [:seon.fn/pending-calls]
                                                    [:seon.test/sym early-test]))
                 :seon.test/target target :seon.test/later later})))
          (finally
            (agent/disarm! request)
            (doseq [channel [faults (:seon.cluster.wake/channel handle)
                            (:seon.render/context-channel handle) (:seon.turn.loop/completion handle)]]
              (async/close! channel))))))))

(deftest admitted-declarations-retain-call-dependencies-across-turn-order
  (support/with-database
   (fn [connection]
     (support/seed-cluster! connection "agent-call-edges")
     (let [result (exercise! connection "agent-call-edges" "edge-probe" 'my.agents.edge-probe)
           target (:seon.test/target result)
           later (:seon.test/later result)]
       (is (contains? (:seon.test/after-calls result) target) (pr-str result))
       (is (not= (:seon.test/digest-before result) (:seon.test/digest-after result)))
       (is (= #{later} (:seon.test/pending result)))
       (is (contains? (:seon.test/early-calls result) later))
       (is (empty? (:seon.test/pending-after result)))
       (is (contains? (:seon.test/function-calls result) target))
       (is (not (contains? (:seon.test/shadow-calls result) target)))))))
