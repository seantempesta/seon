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
            [seon.test-support :as support]
            [seon.turn :as turn]))

(defn- shown
  "Each settled evaluation's shown text; a result with no run is asserted, never read."
  [connection result]
  (is (:seon.turn/id result) (pr-str result))
  (when (:seon.turn/id result)
    (mapv #(or (:seon.eval/shown %) (:seon.cluster.eval/error %))
          (drive/run-receipts (db/db connection) [(:seon.turn/id result)]))))

(defn- receipt-id-of
  "The evaluation id of a one-form run's only form."
  [result]
  (turn/receipt-identity (:seon.turn/id result) 0))

(deftest ^{:seon.test/long "Fourteen ordinary agent runs (three defining, one gated redefinition, a writing form, a renderer fault), each one Flow pass and its settlement commit, plus arming and one stopped run: green in under 20 s on a quiet JVM, 32.4 s measured under concurrent test load on default (b32060df4, 2026-09-26; settlement commits ~120-150 ms each there)."
           :seon.test/long-ms 40000
           :seon/tasks #{"d741e6812a31" "62c45543598c" "c9e0cde3edb3"}}
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
            (is (= ["\"seon.id\""]
                   (shown connection (agent/evaluate-source! (assoc request :seon.cluster.reply/text "(str *ns*)"
                                                                    :seon.turn/starting-ns [:seon.ns/name 'seon.id]))))
                "a requested starting namespace is where the run starts (85b459845: no starting-namespace refusal)"))
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
          (testing "the answer carries the value its settlement left; the MCP observation reads it there"
            ;; eval-path cut 1 (audit M4, task branch-eval-answers-no-events):
            ;; the bridge re-read the connection and could answer no events.
            (let [observe #(agent/run-observation % agent-id cluster-name)
                  shown-of #(mapv (fn [e] (or (:seon.eval/shown e) (:seon.cluster.eval/error e))) (observe %))
                  value (code "(inc 1)")
                  failed (code "(throw (ex-info \"a form's own error\" {}))")]
              (is (some? (:seon.db/db value)) (pr-str (dissoc value :seon.db/db)))
              (is (= ["2"] (shown-of value)) "a successful value, from the settlement's own value")
              (is (= [0] (mapv :seon.cluster.eval/ordinal (observe value))))
              (is (some? (:seon.cluster.eval/error (first (observe failed))))
                  "a returned error value is its run's observation")
              (is (string? (:seon.repl/response (first (observe failed)))))
              (support/transacted! connection (support/agent-tx @connection (str agent-id "-later")))
              (is (< (db/basis-t (:seon.db/db value)) (db/basis-t (db/db connection)))
                  "a later commit moves the connection, not the reply's basis")
              (is (= ["2"] (shown-of value)) "and the reply's observation is unchanged")))
          (testing "a refused settlement answers its run's observation on the refusal's own value"
            (code "(defn refused-helper \"Add one.\" {:malli/schema [:=> [:cat :int] :int]} [x] (inc x))")
            (code "(defn refused-caller \"Call.\" {:malli/schema [:=> [:cat :int] :int]} [x] (refused-helper x))")
            (let [result (code (str "(ns-unmap '" (str "my.agents." agent-id) " 'refused-helper)"))
                  observed (agent/run-observation result agent-id cluster-name)]
              (is (some? (:seon.db/db result)) (pr-str (dissoc result :seon.db/db)))
              (is (re-find #"surviving referrers" (str (:seon.cluster.eval/error (first observed))))
                  (pr-str (mapv #(select-keys % [:seon.cluster.eval/ordinal :seon.cluster.eval/error]) observed)))))
          (testing "a form's own durable write opens its run first, so its provenance names a written evaluation"
            ;; Under one commit per form the run's rows were written only at
            ;; settlement, and every write a form made refused "Nothing found
            ;; for entity id [:seon.cluster.eval/id ...]" (task c9e0cde3edb3).
            (let [note-id (str "one-commit-note-" (id/id))
                  result (code (pr-str (list 'seon.db/transact!
                                             {:tx-data [{:my.note/id note-id
                                                         :my.note/agent [:seon.agent/id agent-id]
                                                         :my.note/content "written by a form"}]})))
                  receipts (drive/run-receipts (db/db connection) [(:seon.turn/id result)])]
              (is (not-any? :seon.cluster.eval/error receipts) (pr-str receipts))
              (is (= (receipt-id-of result)
                     (db/q '[:find ?receipt-id . :in $ ?note-id
                             :where [?note :my.note/id ?note-id] [?note :my.note/content _ ?tx]
                             [?tx :seon.db/receipt ?receipt] [?receipt :seon.cluster.eval/id ?receipt-id]]
                           (db/db connection) note-id))
                  "the write's transaction names the form's evaluation")))
          (testing "a fault recorded during a form names its run without refusing: the value is answered"
            ;; A renderer that throws is a later stage's failure: recorded as a
            ;; fault attributed to the run, beside the value, never a panic.
            (let [result (code "{:seon.render/ai (fn [_] (throw (ex-info \"render probe\" {}))) :probe 1}")]
              (is (:seon.turn/id result) (pr-str result))
              (is (= 1 (count (drive/run-receipts (db/db connection) [(:seon.turn/id result)]))))))
          (testing "the run is written in the one transaction that settles it; submission commits nothing"
            (let [before (db/db connection)
                  result (code "(+ 1 2)")
                  after (db/db connection)
                  txs (db/q '[:find ?opened ?shown ?closed :in $ ?id
                              :where [?run :seon.turn/id ?id ?opened] [?e :seon.cluster.eval/run ?run]
                              [?e :seon.eval/shown _ ?shown] [?run :seon.turn/closed-tx ?closed]]
                            after (:seon.turn/id result))
                  [[opened settled closed]] (vec txs)]
              (is (= ["3"] (shown connection result)))
              (is (= opened settled) "the run opens in the settlement transaction")
              (is (= opened closed) "and closes in it")
              (is (= 1 (- (db/basis-t after) (db/basis-t before))) "one commit per form")))
          (testing "a submission is a value on the agent's request port; a pass stopped before settlement leaves no half-written run"
            (let [submitted (agent/submit-source! (assoc request :seon.agent/segmenter 'seon.cluster.reply/code-sources
                                                         :seon.cluster.reply/text "(loop [] (recur))"))
                  reply (:seon.agent/reply submitted)
                  requests (:seon.agent/requests (agent/armed routing agent-id))]
              (is (:seon.turn/id submitted) (pr-str submitted))
              (support/await-event! (async/go-loop []
                                      (if (zero? (count (.buf ^clojure.core.async.impl.channels.ManyToManyChannel requests)))
                                        true
                                        (do (async/<! (async/timeout 5)) (recur))))
                                    ::request-taken)
              (agent/disarm! request)
              (is (= reply (second (async/alts!! [reply (async/timeout 0)] :priority true)))
                  "the caller's reply is answered or closed when the stop returns")
              (let [database (db/db connection)]
                (is (empty? (db/q '[:find ?run :in $ ?agent-id
                                    :where [?agent :seon.agent/id ?agent-id] [?run :seon.turn/agent ?agent]
                                    (not [?run :seon.turn/closed-tx _])]
                                  database agent-id))
                    "no open run remains")
                (is (empty? (db/q '[:find ?e :in $ ?id
                                    :where [?run :seon.turn/id ?id] [?e :seon.cluster.eval/run ?run]
                                    (not [?e :seon.eval/shown _]) (not [?e :seon.cluster.eval/error _])
                                    (not [?e :seon.cluster.eval/interrupted-at _])]
                                  database (:seon.turn/id submitted)))
                    "and no evaluation without a terminal fact"))))
          (finally
            (agent/disarm! request)
            (doseq [channel [faults (:seon.cluster.wake/channel handle)
                             (:seon.render/context-channel handle) (:seon.turn.loop/completion handle)]]
              (async/close! channel))))))))
