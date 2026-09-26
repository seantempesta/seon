(ns seon.cluster.evaluate-source-test
  "An outside agent's code evaluated as one ordinary run of an agent
  (`seon.cluster.agent/evaluate-source!`), segmented as code."
  (:require [clojure.core.async :as async]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.config :as config]
            [seon.db :as db]
            [seon.eval.drive :as drive]
            [seon.flow :as flow]
            [seon.id :as id]
            [seon.test-support :as support]))

(defn- shown
  "Each settled evaluation's shown text; a result with no run is asserted, never read."
  [connection result]
  (is (:seon.turn/id result) (pr-str result))
  (when (:seon.turn/id result)
    (mapv #(or (:seon.eval/shown %) (:seon.cluster.eval/error %))
          (drive/run-receipts (db/db connection) [(:seon.turn/id result)]))))

(deftest ^{:seon.test/long "Six ordinary agent runs, each a run transaction, one Flow turn and its settlement (measured 184-229 ms warm each on default, 2026-09-26), plus the agent's arming."
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
          (testing "a run starts in the agent's namespace; in-ns moves only the rest of that run"
            (is (= "\"seon.id\"" (last (shown connection (code "(in-ns 'seon.id)\n(str *ns*)"))))
                "the run's own later form reads the namespace its earlier form entered")
            (is (= [(pr-str (str "my.agents." agent-id))] (shown connection (code "(str *ns*)")))
                "the next run starts in the assigned namespace again")
            (is (= :seon.turn/starting-namespace-changed
                   (:seon.turn/rule (agent/evaluate-source! (assoc request :seon.cluster.reply/text "(str *ns*)"
                                                                   :seon.turn/starting-ns [:seon.ns/name 'seon.id]))))
                "the run writer refuses another starting namespace by name"))
          (testing "a defn then a require in one run settles: its row digests against the namespace row the run stores"
            (let [namespace-name (str "my.agents." agent-id)
                  defn-source "(defn run-inc \"Adds one.\" {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))"
                  settled (fn [sources]
                            (let [result (code sources)
                                  receipts (drive/run-receipts (db/db connection) [(:seon.turn/id result)])]
                              (is (not-any? :seon.cluster.eval/error receipts) (pr-str receipts))
                              (shown connection result)))]
              (is (= [(str "#'" namespace-name "/run-inc") "nil"]
                     (settled (str defn-source "\n(require '[malli.core :as m])"))))
              (is (= ["nil" (str "#'" namespace-name "/run-inc2")]
                     (settled (str "(require '[clojure.set :as set])\n"
                                   (str/replace defn-source "run-inc " "run-inc2 ")))))))
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
