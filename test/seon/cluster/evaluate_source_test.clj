(ns seon.cluster.evaluate-source-test
  "An outside agent's code evaluated as one ordinary run of an agent
  (`seon.cluster.agent/evaluate-source!`), segmented as code."
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.db :as db]
            [seon.eval.drive :as drive]
            [seon.flow :as flow]
            [seon.id :as id]
            [seon.test-support :as support]))

(defn- shown [connection result]
  (mapv #(or (:seon.eval/shown %) (:seon.cluster.eval/error %))
        (drive/run-receipts (db/db connection) [(:seon.turn/id result)])))

(deftest ^{:seon.test/long "Four ordinary agent runs, each a run transaction, one Flow turn and its settlement (measured 184-229 ms warm each on default, 2026-09-26), plus the agent's arming."
           :seon.test/long-ms 20000}
  code-evaluates-as-one-ordinary-run-of-its-agent
  (let [target (support/execution-handle nil)
        connection (:seon.db/connection target)
        cluster-name (:seon.cluster/name target)
        agent-id (str "evaluate-source-" (id/id))
        environment (support/environment cluster-name connection)
        ctx (support/fork-cluster-ctx connection cluster-name)
        routing (support/attempt-routing connection)
        faults (async/chan (async/sliding-buffer 16))]
    (with-open [launcher (support/closeable
                          (flow/start-work-launcher!
                           {:seon.env/environment environment
                            :seon.flow/configuration (select-keys (config/effective (db/db connection) cluster-name)
                                                                  flow/flow-workload-attributes)})
                          flow/stop-work-launcher!)]
      (let [handle (support/cluster-handle
                    {:seon.env/environment environment :seon.db/connection connection :seon.cluster/name cluster-name
                     :seon.sci.eval/ctx ctx :seon.flow/work-launcher @launcher
                     :seon.flow/executor (cluster/projection-executor (:seon.sci.eval/projection-state ctx))
                     :seon.db.process/id cluster/boot-process-identity})
            request {:seon.turn.loop/cluster handle :seon.agent/routing routing :seon.agent/id agent-id}
            code #(agent/evaluate-source! (assoc request :seon.agent/segmenter 'seon.cluster.reply/code-sources
                                                 :seon.cluster.reply/text %))]
        (swap! routing assoc :seon.agent/fault-channel faults)
        (support/transacted! connection (support/agent-tx @connection agent-id))
        (agent/arm! request)
        (try
          (testing "every form of the code is one evaluation of the run, in order; nothing is read as prose"
            (let [result (code "1\n#?(:clj 2 :cljs 3)\n(str *ns*)")]
              (is (:seon.turn/id result) (pr-str result))
              (is (= ["1" "2" (pr-str (str "my.agents." agent-id))] (shown connection result)))))
          (testing "without a starting namespace the agent continues where its last evaluation ended"
            (is (= "\"seon.id\"" (last (shown connection (code "(in-ns 'seon.id)\n(str *ns*)"))))
                "the run's own later form reads the namespace its earlier form entered")
            (is (= ["\"seon.id\""] (shown connection (code "(str *ns*)")))
                "the next run starts in the namespace a row names where the last one ended"))
          (testing "the reply planner stays the default: a bare number is prose there"
            (is (:seon.cluster.reply/no-forms (agent/evaluate-source! (assoc request :seon.cluster.reply/text "1")))))
          (testing "a segmenter without the planner's contract refuses by name before any run"
            (let [runs #(count (db/q '[:find ?r :in $ ?id :where [?a :seon.agent/id ?id] [?r :seon.turn/agent ?a]]
                                     (db/db connection) agent-id))
                  before (runs)
                  refused (agent/evaluate-source! (assoc request :seon.agent/segmenter 'clojure.core/str
                                                         :seon.cluster.reply/text "1"))]
              (is (= :seon.agent/segmenter (:seon.error/member refused)) (pr-str refused))
              (is (= before (runs)))))
          (finally
            (agent/disarm! request)
            (doseq [channel [faults (:seon.cluster.wake/channel handle)
                             (:seon.render/context-channel handle) (:seon.turn.loop/completion handle)]]
              (async/close! channel))))))))
