(ns seon.cluster.loop-test
  "Sealed acceptance draft for the run loop (N3, C9).

  DRAFT FOR ORCHESTRATOR SEAL (drafted 2026-07-27). Two surfaces, for
  two different reasons:

  1. THE PURE PARTS are tested directly — the committed-attribute set
     (which the wake suite's disjointness property consumes), the
     disposition reader, and the ONE terminal transaction.
  2. THE CRASH WALK is driven as KILL POSITIONS OVER FACTS: each row of
     n3-plan §9.3 is the exact committed state a kill at that point
     leaves, and the assertion is what the loop does next. This is
     deterministic and needs no child JVM, because the rows are defined
     by what is committed — not by how the process died. The live
     `kill -9` falsifier against a real child stays the orchestrator's
     integration proof, in the style of `store_child.clj`; it proves
     the process boundary, and this proves the derivation."
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow.core]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.db :as db]
            [my.run :as my.run]
            [seon.ai :as ai]
            [seon.config :as config]
            [seon.cluster.agent :as cluster.agent]
            [seon.cluster.loop :as cluster.loop]
            [seon.cluster.message :as message]
            [seon.cluster.run :as run]
            [seon.cluster.wake :as wake]
            [seon.cluster.work :as work]
            [seon.flow :as seon.flow]
            [seon.problems :as problems]
            [seon.render.web :as web]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as test-support])
  (:import [java.util Date]))

(def ^:private test-environment
  ;; The subset environment (store layer only) every crossing this
  ;; namespace constructs names; boot's own constructor, fewer layers.
  (delay (test-support/environment "seon.cluster.loop-test")))


;;; ---------------------------------------------------------------------------
;;; The pure parts
;;; ---------------------------------------------------------------------------

(def ^:private now (Date. 1700000000000))
(def ^:private process "process/one")

(defn- private-loop-fn
  [function-name]
  (deref (ns-resolve 'seon.cluster.loop function-name)))

(deftest session-purity-is-derived-from-capabilities-not-workload
  (let [rows
        {'sample/pure-io
         {:seon.fn/sym "sample/pure-io"
          :seon.fn/workload :io}
         'sample/capability
         {:seon.fn/sym "sample/capability"
          :seon.fn/workload :io
          :seon.effect/capability 'sample.jvm/handler}
         'sample/compute
         {:seon.fn/sym "sample/compute"
          :seon.fn/workload :compute}
         'sample/mixed-caller
         {:seon.fn/sym "sample/mixed-caller"
          :seon.fn/calls [{:seon.fn/sym "sample/capability"}
                          {:seon.fn/sym "sample/compute"}]}}
        capability-free? (private-loop-fn 'capability-free-references?)]
    (with-redefs [db/pull
                  (fn [_db _selector lookup-ref]
                    (get rows (symbol (second lookup-ref))))]
      (is (true? (capability-free? :db #{'sample/pure-io} #{}))
          "a blocking pure helper remains restorable")
      (is (false? (capability-free? :db #{'sample/capability} #{})))
      (is (false? (capability-free? :db #{'sample/mixed-caller} #{}))
          "compute reachability never hides the separate capability fact")
      (is (false? (capability-free? :db #{'sample/absent}
                                    #{'sample/absent}))
          "an unresolved called Var still fails closed"))))

(deftest attempt-evidence-prefers-completion-and-falls-back-to-error-data
  (let [project (private-loop-fn 'attempt-evidence)]
    (is (= {:seon.ai.model/last-latency-ms 42
            :seon.ai/usage {:source :completion}
            :seon.ai/reasoning-content "fallback reasoning"
            :seon.ai/finish-reason "stop"}
           (project
            {:seon.ai/completion
             {:seon.ai/usage {:source :completion}
              :seon.ai.model/last-latency-ms 42
              :seon.ai/finish-reason "stop"
              :seon.error/data
              {:seon.ai/usage {:source :error}
               :seon.ai/reasoning-content "fallback reasoning"
               :seon.ai/finish-reason "length"}}})))))

(deftest attempt-request-assembles-evidence-and-optional-provenance
  (let [failure {:seon.error/kind :provider/refused
                 :seon.error/message "refused"}
        evidence {:seon.ai/usage {"prompt_tokens" 3}
                  :seon.ai.model/last-latency-ms 42
                  :seon.ai/reasoning-content "reasoning"
                  :seon.ai/finish-reason "length"}]
    (is (= {:seon.ai/target {:seon.ai/endpoint "https://provider.invalid"
                             :seon.ai/model "model"}
            :seon.ai/settings {:seon.config.ai/thinking :high}
            :seon.cluster.run/id "run-1"
            :seon.cluster.agent/id "agent-1"
            :seon.ai.attempt/ordinal 2
            :seon.ai/usage {"prompt_tokens" 3}
            :seon.ai.model/last-latency-ms 42
            :seon.ai/reasoning-content "reasoning"
            :seon.ai/finish-reason "length"
            :seon.error/value failure
            :seon.ai.attempt/failover-from "run-1:1"
            :seon.ai.attempt/delay-ms 200}
           ((private-loop-fn 'attempt-request)
            {:seon.ai/target {:seon.ai/endpoint "https://provider.invalid"
                              :seon.ai/model "model"}
             :seon.ai/settings {:seon.config.ai/thinking :high}
             :seon.cluster.run/id "run-1"
             :seon.cluster.agent/id "agent-1"
             :seon.ai.attempt/ordinal 2
             :seon.cluster.loop/attempt-evidence evidence
             :seon.error/value failure
             :seon.ai.attempt/failover-from "run-1:1"
             :seon.ai.attempt/delay-ms 200})))))

(deftest provider-targets-resolves-once-and-omits-backoff-for-a-backup
  (let [db {:immutable :database-value}
        cluster-settings {:scope :cluster}
        overlay {:scope :agent}
        settings {:resolved true}
        primary {:seon.ai/model "primary"}
        backup {:seon.ai/model "backup"}
        strategy {:retry :strategy}
        calls (atom [])]
    (with-redefs [config/effective
                  (fn [actual-db cluster-name]
                    (swap! calls conj [:effective actual-db cluster-name])
                    cluster-settings)
                  ai/agent-overlay
                  (fn [actual-db agent-id]
                    (swap! calls conj [:overlay actual-db agent-id])
                    overlay)
                  ai/settings
                  (fn [actual-cluster-settings actual-overlay]
                    (swap! calls conj
                           [:settings actual-cluster-settings actual-overlay])
                    settings)
                  ai/targets (fn [actual-db actual-settings]
                               (swap! calls conj
                                      [:targets actual-db actual-settings])
                               {:seon.ai/primary primary
                                :seon.ai/backup backup})
                  ai/retry-strategy
                  (fn [actual-settings]
                    (swap! calls conj [:strategy actual-settings])
                    strategy)
                  ai/delays
                  (fn [& arguments]
                    (swap! calls conj [:delays arguments])
                    [10 20])]
      (is (= {:seon.ai/primary primary
              :seon.ai/backup backup
              :seon.ai/settings settings
              :seon.cluster.loop/schedule []}
             ((private-loop-fn 'provider-targets)
              {:seon.db/db db
               :seon.cluster/name "cluster"
               :seon.cluster.agent/id "agent"})))
      (is (= [[:effective db "cluster"]
              [:overlay db "agent"]
              [:settings cluster-settings overlay]
              [:targets db settings]
              [:strategy settings]]
             @calls)
          "a configured backup makes the schedule empty without deriving delays"))))

(deftest attempt-settlement-updates-the-registered-model-gauges
  (test-support/with-database
    (fn [connection]
      (config/apply! {:seon.db/connection connection})
      (db/transact! connection [{:seon.cluster.run/id "gauge-run"}])
      ((private-loop-fn 'record-attempt!)
       {:seon.db/connection connection}
       {:seon.ai/target
        {:seon.ai/endpoint "https://api.deepseek.com/chat/completions"
         :seon.ai/model "deepseek-v4-flash"}
        :seon.ai/settings {:seon.config.ai/model "deepseek-v4-flash"}
        :seon.cluster.run/id "gauge-run"
        :seon.cluster.agent/id "gauge-agent"
        :seon.ai.attempt/ordinal 0
        :seon.ai.model/last-latency-ms 200
        :seon.ai/usage {"completion_tokens" 20}}
       now)
      (let [model (ai/model-row @connection "deepseek-v4-flash")]
        (is (= now (:seon.ai.model/last-used-at model)))
        (is (= 200 (:seon.ai.model/last-latency-ms model)))
        (is (= 100.0 (:seon.ai.model/last-tokens-per-second model)))
        (is (= 1
               (db/q
                '[:find (count ?attempt) .
                  :where
                  [?attempt :seon.ai.attempt/id "gauge-run-attempt-0"]]
                @connection))
            "the durable attempt and display gauges settle together")))))

(deftest admitted-form-preserves-current-namespace-and-source
  (let [db {:immutable :database-value}
        form {:seon.cluster.run.form/source "(+ 1 2)"
              :seon.cluster.run.form/ns [:seon.ns/name 'parse.namespace]}
        calls (atom [])]
    (with-redefs-fn
      {(ns-resolve 'seon.cluster.loop 'form-data)
       (fn [actual-db run-id ordinal]
         (swap! calls conj [:form actual-db run-id ordinal])
         form)}
      (fn []
        (is (= {:seon.cluster.run.form/source "(+ 1 2)"
                :seon.cluster.run.form/ns
                [:seon.ns/name 'current.namespace]}
               ((private-loop-fn 'admitted-form)
                {:seon.db/db db
                 :seon.cluster.run/id "run-1"
                 :seon.cluster.run.form/ordinal 3
                 :seon.cluster.loop/current-namespace 'current.namespace
                 :seon.cluster.loop/fallback-namespace 'fallback.namespace})))
        (is (= [[:form db "run-1" 3]] @calls))
        (is (= "(+ 1 2)" (:seon.cluster.run.form/source form))
            "the evaluator receives the durable source without a second admission pass")))))

(deftest evaluation-request-projects-the-admitted-form-and-cluster-controls
  (let [ctx {:live :context}
        form {:seon.cluster.run.form/source "(inc 2)"
              :seon.cluster.run.form/ns [:seon.ns/name 'old.namespace]}
        caps {:seon.config.eval.result/max-depth 4}
        cluster {:seon.sci.admit/caps caps
                 :seon.cluster/name "default"
                 :seon.config.eval/time-limit-ms 500
                 :seon.config/on-core-error :panic}]
    (is (= {:seon.cluster.run.form/source "(inc 2)"
            :seon.cluster.run.form/ns [:seon.ns/name 'current.namespace]
            :seon.sci.admit/caps caps
            :seon.sci.eval/ctx ctx
            :seon.cluster.agent/id "agent-1"
            :seon.cluster.run/id "run-1"
            :seon.cluster.run.form/ordinal 3
            :seon.boot/cluster-name "default"
            :seon.sci.eval/time-limit-ms 500
            :seon.config/on-core-error :panic}
           ((private-loop-fn 'evaluation-request)
            {:seon.cluster.loop/admitted-form form
             :seon.cluster.loop/evaluation-namespace 'current.namespace
             :seon.cluster.loop/cluster cluster
             :seon.sci.eval/ctx ctx
             :seon.cluster.agent/id "agent-1"
             :seon.cluster.run/id "run-1"
             :seon.cluster.run.form/ordinal 3})))))

(deftest asked-value-preserves-explicit-reply-and-problem-precedence
  (let [db {:immutable :database-value}
        explicit {:my.message/to "agent-2" :my.message/content "explicit"}
        reply {:my.message/to "agent-3" :my.message/content "reply"}
        assignment {:my.message/to "agent-4" :my.message/content "repair"}
        completed (my.run/complete "done")]
    (with-redefs [cluster.loop/messages (fn [value]
                                         (when (= :explicit value) explicit))
                  message/reply (fn [actual-db request]
                                  (is (= db actual-db))
                                  (is (= {:my.run/result "done"
                                          :seon.cluster.agent/id "agent-1"
                                          :seon.cluster.message/trigger "m-1"}
                                         request))
                                  reply)
                  problems/assignment-value (constantly assignment)]
      (is (= explicit
             ((private-loop-fn 'asked-value)
              {:seon.db/db db
               :seon.sci.eval/evaluation {:seon.sci.admit/value :explicit}
               :seon.cluster.loop/settled completed
               :seon.problems/form-problem {:seon.problems/id :problem}
               :seon.cluster.agent/id "agent-1"
               :seon.cluster.message/trigger "m-1"})))
      (is (= reply
             ((private-loop-fn 'asked-value)
              {:seon.db/db db
               :seon.sci.eval/evaluation {:seon.sci.admit/value :ordinary}
               :seon.cluster.loop/settled completed
               :seon.problems/form-problem {:seon.problems/id :problem}
               :seon.cluster.agent/id "agent-1"
               :seon.cluster.message/trigger "m-1"})))
      (is (= assignment
             ((private-loop-fn 'asked-value)
              {:seon.db/db db
               :seon.sci.eval/evaluation {:seon.sci.admit/value :ordinary}
               :seon.cluster.loop/settled nil
               :seon.problems/form-problem {:seon.problems/id :problem}
               :seon.cluster.agent/id "agent-1"}))))))

(deftest delivery-rows-projects-rows-and-every-refusal-transaction
  (let [db {:immutable :database-value}
        asked [{:my.message/to "agent-2" :my.message/content "deliver"}
               {:my.message/to "missing" :my.message/content "refuse"}]
        rows [{:seon.cluster.message/id "delivered"}]
        failures [{:seon.error/kind :failure/one}
                  {:seon.error/kind :failure/two}]
        cluster {:seon.config.message/max-chain 8}
        requests (atom [])]
    (with-redefs-fn
      {#'message/delivery
       (fn [actual-db request]
         (swap! requests conj [:delivery actual-db request])
         {:seon.cluster.message/rows rows
          :seon.error/values failures})
       (ns-resolve 'seon.cluster.loop 'error-tx)
       (fn [actual-cluster actual-db failure actual-now attribution]
         (swap! requests conj
                [:error actual-cluster actual-db failure actual-now attribution])
         [[:error/tx (:seon.error/kind failure)]])}
      (fn []
        (is (= {:seon.cluster.message/rows rows
                :seon.error/values-tx
                [[:error/tx :failure/one] [:error/tx :failure/two]]}
               ((private-loop-fn 'delivery-rows)
                {:seon.db/db db
                 :seon.cluster.loop/cluster cluster
                 :seon.cluster.loop/asked asked
                 :seon.cluster.agent/id "agent-1"
                 :seon.cluster.run/id "run-1"
                 :seon.cluster.run.form/ordinal 2
                 :seon.cluster.loop/now now
                 :seon.cluster.message/trigger "m-1"})))
        (is (= [:delivery db
                {:my.message/value asked
                 :seon.cluster.agent/id "agent-1"
                 :seon.cluster.run/id "run-1"
                 :seon.cluster.run.form/ordinal 2
                 :seon.cluster.message/at now
                 :seon.config.message/max-chain 8
                 :seon.cluster.message/trigger "m-1"}]
               (first @requests)))
        (is (= failures
               (mapv #(nth % 3) (rest @requests))))))))

(deftest committed-ending-namespace-seeds-a-resumed-fold
  (test-support/with-database
    (fn [connection]
      (let [cluster-name "namespace-resume"
            agent-id "namespace-resume-agent"
            run-id "namespace-resume-run"
            starting-ns 'my.agents.namespace-resume
            ending-ns 'my.generated.after-resume]
        (test-support/seed-cluster! connection cluster-name)
        (db/transact!
         connection
         (cluster.agent/creation-tx
          {:seon.cluster.agent/id agent-id
           :seon.ns/name starting-ns
           :seon.cluster/name cluster-name}))
        (db/transact!
         connection
         (run/open-tx {::run/id run-id
                       ::run/agent [:seon.cluster.agent/id agent-id]
                       ::run/opened-at now}))
        (db/transact!
         connection
         (run/claim-tx {::run/id run-id
                        ::run/process process
                        ::run/live-processes #{process}
                        ::run/now now}))
        (db/transact!
         connection
         (run/plan-tx
          {::run/id run-id
           ::run/process process
           ::run/starting-ns [:seon.ns/name starting-ns]
           ::run/plan-digest "namespace-resume-plan"
           ::run/sources
           [{:seon.cluster.run.form/source
             "(when true (in-ns 'my.generated.after-resume))"
             :seon.ns/name starting-ns}
            {:seon.cluster.run.form/source
             (str "(defn ^{:malli/schema [:=> [:cat] :int]} "
                  "attributed-after-resume [] 1)")
             :seon.ns/name starting-ns}]}))
        (db/transact!
         connection
         (run/receipt-start-tx
          {::run/id run-id
           :seon.cluster.eval/ordinal 0
           :seon.cluster.eval/at now}))
        (let [ctx (test-support/fork-cluster-ctx connection)
              first-evaluation
              (sci.eval/evaluate
               {:seon.cluster.run.form/source
                "(when true (in-ns 'my.generated.after-resume))"
                :seon.cluster.run.form/ns [:seon.ns/name starting-ns]
                :seon.sci.eval/ctx ctx
                :seon.sci.admit/caps
                (config/result-caps (config/defaults))
                :seon.sci.eval/time-limit-ms 2000
                :seon.config/on-core-error :panic
                :seon.boot/cluster-name cluster-name
                :seon.cluster.agent/id agent-id
                :seon.cluster.run/id run-id
                :seon.cluster.run.form/ordinal 0})]
          (is (= ending-ns (:seon.sci.eval/ending-ns first-evaluation)))
          (db/transact!
           connection
           (run/receipt-settle-tx
            {:seon.cluster.run/id run-id
             :seon.cluster.eval/ordinal 0
             :seon.cluster.eval/result-edn
             (:seon.cluster.eval/result-edn first-evaluation)
             :seon.cluster.eval/ns
             (:seon.cluster.eval/ns first-evaluation)
             :seon.sci.eval/ending-ns
             (:seon.sci.eval/ending-ns first-evaluation)}))
          (is (= ending-ns
                 (:seon.sci.eval/ending-ns
                  (db/pull @connection
                           [:seon.sci.eval/ending-ns]
                           [:seon.cluster.eval/id (pr-str [run-id 0])]))))
          (let [fold-namespace (private-loop-fn 'fold-namespace)
                admitted-form (private-loop-fn 'admitted-form)
                resumed-namespace (fold-namespace @connection run-id 1)
                form
                (admitted-form
                 {:seon.db/db @connection
                  :seon.cluster.run/id run-id
                  :seon.cluster.run.form/ordinal 1
                  :seon.cluster.loop/current-namespace resumed-namespace
                  :seon.cluster.loop/fallback-namespace starting-ns})
                evaluation
                (sci.eval/evaluate
                 {:seon.cluster.run.form/source
                  (:seon.cluster.run.form/source form)
                  :seon.cluster.run.form/ns
                  (:seon.cluster.run.form/ns form)
                  :seon.sci.eval/ctx ctx
                  :seon.sci.admit/caps
                  (config/result-caps (config/defaults))
                  :seon.sci.eval/time-limit-ms 2000
                  :seon.config/on-core-error :panic
                  :seon.boot/cluster-name cluster-name
                  :seon.cluster.agent/id agent-id
                  :seon.cluster.run/id run-id
                  :seon.cluster.run.form/ordinal 1})]
            (is (= ending-ns resumed-namespace))
            (is (= [:seon.ns/name ending-ns]
                   (:seon.cluster.run.form/ns form)))
            (is (= "my.generated.after-resume/attributed-after-resume"
                   (get-in evaluation
                           [:seon.program/row :seon.fn/sym])))))))))

(deftest two-agents-resolve-one-config-row-with-ordinary-inheritance
  (test-support/with-database
    (fn [connection]
      (let [cluster-name "settings-resolution"]
        (config/apply! {:seon.db/connection connection
                        :seon.boot/cluster-name cluster-name})
        (test-support/seed-cluster! connection cluster-name)
        (db/transact!
         connection
         (into (cluster.agent/creation-tx
                {:seon.cluster.agent/id "planner"
                 :seon.ns/name 'my.agents.planner
                 :seon.cluster/name cluster-name})
               (cluster.agent/creation-tx
                {:seon.cluster.agent/id "worker"
                 :seon.ns/name 'my.agents.worker
                 :seon.cluster/name cluster-name})))
        (db/transact! connection
                    [{:seon.cluster.agent/id "planner"
                      :seon.config.ai/thinking :high}])
        (let [db @connection
              cluster-settings (config/effective db cluster-name)
              planner-settings
              (ai/settings cluster-settings (ai/agent-overlay db "planner"))
              worker-settings
              (ai/settings cluster-settings (ai/agent-overlay db "worker"))]
          (is (= :high (:seon.config.ai/thinking planner-settings)))
          (is (= :disabled (:seon.config.ai/thinking worker-settings))
              "absence on the agent inherits the explicit shipped default")
          (is (= (:seon.config.ai/model cluster-settings)
                 (:seon.config.ai/model planner-settings)
                 (:seon.config.ai/model worker-settings)))
          (is (= #{:seon.config.ai/thinking}
                 (set (keys (ai/agent-overlay db "planner")))))
          (is (empty? (ai/agent-overlay db "worker"))))))))

(defn- prepare-call!
  [connection agent-id run-id message-id]
  (db/transact! connection
              [{:seon.cluster.message/id message-id
                :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
                :seon.cluster.message/content "prove live settings"
                :seon.cluster.message/at now}])
  (db/transact!
   connection
   [{:seon.cluster.run/id run-id
     :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
     :seon.cluster.run/trigger [:seon.cluster.message/id message-id]
     :seon.cluster.run/opened-at now
     :seon.cluster.run/process process}
    {:seon.cluster.agent/id agent-id
     :seon.cluster.agent/run [:seon.cluster.run/id run-id]}]))

(defn- call-work
  [agent-id run-id]
  {:seon.cluster.work/situation :call
   :seon.cluster.agent/id agent-id
   :seon.cluster.run/id run-id})

(defn- settings-attempts
  [db]
  (->> (db/q '[:find ?run-id ?ordinal ?attempt
              :where
              [?attempt :seon.ai.attempt/run ?run]
              [?run :seon.cluster.run/id ?run-id]
              [?attempt :seon.ai.attempt/ordinal ?ordinal]]
            db)
       (map (fn [[run-id ordinal attempt]]
              (assoc (db/pull db '[*] attempt)
                     ::attempt-run-id run-id
                     ::attempt-ordinal ordinal)))
       (sort-by (juxt ::attempt-run-id ::attempt-ordinal))
       vec))

(defn- with-render-context-proc
  [cluster body]
  (let [context-channel (async/chan)
        render-channel (async/chan (async/sliding-buffer 1))
        pages-channel (async/chan (async/sliding-buffer 1))
        stream-channel (async/chan (async/sliding-buffer 1))
        completion (async/promise-chan)
        cluster (assoc cluster
                       :seon.env/environment @test-environment
                       :seon.render/context-channel context-channel
                       :seon.cluster.loop/stream-channel stream-channel)
        graph
        (flow.core/create-flow
         {:procs
          {:seon.render.web/render
           {:proc
            (seon.flow/var-process
             #'web/render-step :io
             {:seon.env/environment @test-environment
              :seon.render.web/render-channel render-channel
              :seon.render/context-channel context-channel
              :seon.render.web/pages-channel pages-channel
              :seon.render.web/registration (atom {})
              :seon.render.web/latest-packages (atom {})
              :seon.render.web/completion completion
              :seon.render.web/root-agent-id "planner"
              :seon.cluster.loop/cluster cluster})}}
          :conns []})
        {:keys [report-chan error-chan]} (flow.core/start graph)]
    (async/go-loop [] (when (async/<! report-chan) (recur)))
    (async/go-loop [] (when (async/<! error-chan) (recur)))
    (try
      (flow.core/resume graph)
      (body cluster)
      (finally
        (flow.core/stop graph)
        (async/<!! completion)))))

(deftest assigned-namespace-seeds-the-run-and-its-receipt
  (test-support/with-database
    (fn [connection]
      (let [cluster-name "assigned-run-namespace"
            agent-id "toolsmith"
            run-id "assigned-run"
            assigned-namespace 'my.tools.demo]
        (test-support/seed-cluster! connection cluster-name)
        (db/transact!
         connection
         (cluster.agent/creation-tx
          {:seon.cluster.agent/id agent-id
           :seon.ns/name assigned-namespace
           :seon.cluster/name cluster-name}))
        (db/transact!
         connection
         (run/open-tx {::run/id run-id
                       ::run/agent [:seon.cluster.agent/id agent-id]
                       ::run/opened-at now}))
        (db/transact!
         connection
         (run/claim-tx {::run/id run-id
                        ::run/process process
                        ::run/live-processes #{process}
                        ::run/now now}))
        (db/transact!
         connection
         (run/plan-tx
          {::run/id run-id
           ::run/process process
           ::run/plan-digest "assigned-run-plan"
           ::run/sources
           [{:seon.cluster.run.form/source "(ns-name *ns*)"}]}))
        (let [planned-form
              (db/pull
               @connection
               '[:seon.cluster.run.form/source
                 {:seon.cluster.run.form/ns [:seon.ns/name]}]
               (db/q '[:find ?form .
                       :in $ ?run-id
                       :where
                       [?run :seon.cluster.run/id ?run-id]
                       [?form :seon.cluster.run.form/run ?run]
                       [?form :seon.cluster.run.form/ordinal 0]]
                     @connection run-id))]
          (is (= assigned-namespace
                 (db/q '[:find ?namespace-name .
                         :in $ ?run-id
                         :where
                         [?run :seon.cluster.run/id ?run-id]
                         [?run :seon.cluster.run/starting-ns ?namespace]
                         [?namespace :seon.ns/name ?namespace-name]]
                       @connection run-id))
              "the normal run writes its starting namespace from the assignment")
          (is (= assigned-namespace
                 (get-in planned-form
                         [:seon.cluster.run.form/ns :seon.ns/name])))
          (db/transact!
           connection
           (run/receipt-start-tx
            {::run/id run-id
             :seon.cluster.eval/ordinal 0
             :seon.cluster.eval/at now}))
          (let [evaluation
                (sci.eval/evaluate
                 {:seon.cluster.run.form/source
                  (:seon.cluster.run.form/source planned-form)
                  :seon.cluster.run.form/ns
                  [:seon.ns/name
                   (get-in planned-form
                           [:seon.cluster.run.form/ns :seon.ns/name])]
                  :seon.sci.eval/ctx
                  (test-support/fork-cluster-ctx connection)
                  :seon.sci.admit/caps
                  (config/result-caps (config/defaults))
                  :seon.sci.eval/time-limit-ms 2000
                  :seon.config/on-core-error :panic
                  :seon.boot/cluster-name cluster-name
                  :seon.cluster.agent/id agent-id
                  :seon.cluster.run/id run-id
                  :seon.cluster.run.form/ordinal 0})]
            (db/transact!
             connection
             (run/receipt-settle-tx
              {:seon.cluster.run/id run-id
               :seon.cluster.eval/ordinal 0
               :seon.cluster.eval/result-edn
               (:seon.cluster.eval/result-edn evaluation)
               :seon.cluster.eval/ns
               (:seon.cluster.eval/ns evaluation)
               :seon.sci.eval/ending-ns
               (:seon.sci.eval/ending-ns evaluation)}))
            (is (= assigned-namespace
                   (:seon.sci.admit/value evaluation)))
            (is (= assigned-namespace
                   (db/q '[:find ?namespace-name .
                           :in $ ?run-id
                           :where
                           [?run :seon.cluster.run/id ?run-id]
                           [?receipt :seon.cluster.eval/run ?run]
                           [?receipt :seon.cluster.eval/ordinal 0]
                           [?receipt :seon.cluster.eval/ns ?namespace]
                           [?namespace :seon.ns/name ?namespace-name]]
                         @connection run-id))
                "the settled receipt records the evaluated namespace")))))))

(deftest prompt-and-call-resolve-once-record-settings-and-see-next-turn-config
  (test-support/with-database
   (fn [connection]
     (let [cluster-name "live-settings"
           agent-id "planner"
           settings-fn ai/settings
           overlay-fn ai/agent-overlay
           resolutions (atom [])
           overlays (atom [])
           requests (atom [])
           cluster {:seon.db/connection connection
                    :seon.cluster/name cluster-name
                    :seon.cluster.run/process process
                    :seon.sci.eval/ctx
                    (test-support/fork-cluster-ctx connection)
                    :seon.config.eval/time-limit-ms 2000
                    :seon.config/on-core-error :panic
                    :seon.sci.admit/caps
                    {:seon.config.eval.result/max-depth 6
                     :seon.config.eval.result/max-collection 8
                     :seon.config.eval.result/max-string 4096
                     :seon.config.eval.result/max-nodes 256}
                    :seon.config.error/recurrence-limit 3
                    :seon.config.message/max-chain 8}
           unpaid {:seon.error/kind :seon.ai/transport-failure
                   :seon.error/message "connection refused"
                   :seon.error/data
                   {:seon.ai/error-class :transport-before-send
                    :seon.ai/request-transmitted? false
                    :seon.ai/response-started? false
                    :seon.ai/output-observed? false}}
           completions (atom [unpaid
                              {:seon.ai/text "(identity 1)"
                               :seon.ai/usage
                               {"completion_tokens_details"
                                {"reasoning_tokens" 7}}
                               :seon.ai/finish-reason "stop"}
                              {:seon.ai/text "(identity 2)"
                               :seon.ai/finish-reason "stop"}])]
       (config/apply!
        {:seon.db/connection connection
         :seon.boot/cluster-name cluster-name
         :seon.config/manifest
         {:seon.config.ai/model "before-apply"
          :seon.config.ai.backup/model "backup-before-apply"}})
       (test-support/seed-cluster! connection cluster-name)
       (db/transact!
        connection
        (cluster.agent/creation-tx
         {:seon.cluster.agent/id agent-id
          :seon.ns/name 'my.agents.live-settings
          :seon.cluster/name cluster-name}))
       (db/transact!
        connection
        [{:seon.cluster.agent/id agent-id
          :seon.config.ai/thinking :high}])
       (prepare-call! connection agent-id "settings-run-1" "settings-message-1")
       (with-render-context-proc
        cluster
        (fn [cluster]
          (with-redefs [ai/agent-overlay
                        (fn [db id]
                          (swap! overlays conj [db id])
                          (overlay-fn db id))
                        ai/settings
                        (fn [cluster-settings agent-settings]
                          (let [resolved
                                (settings-fn cluster-settings agent-settings)]
                            (swap! resolutions conj resolved)
                            resolved))
                        ai/complete
                        (fn [request]
                          (swap! requests conj request)
                          (let [completion (first @completions)]
                            (swap! completions subvec 1)
                            completion))]
            (cluster.loop/turn
             {:seon.cluster.loop/cluster cluster
              :seon.cluster.work/next
              (call-work agent-id "settings-run-1")}
             now)
            (testing "the opening prompt and paid call each resolve once"
              (is (= 2 (count @overlays))
                  "failover does not resolve either phase again")
              (is (= 2 (count @resolutions))
                  "prompt budget uses opening facts; attempts share call settings")
              (is (= ["before-apply" "backup-before-apply"]
                     (mapv :seon.ai/model @requests)))
              (is (= [:high :high]
                     (mapv :seon.ai/thinking @requests))))
            (let [first-settings (first @resolutions)
                  first-rows (settings-attempts @connection)]
              (is (= 2 (count first-rows)))
              (is (every? #(= first-settings
                              (edn/read-string
                               (:seon.ai.attempt/settings-edn %)))
                          first-rows))
              (is (= "stop" (:seon.ai.attempt/finish-reason
                              (second first-rows))))
              (is (= {"completion_tokens_details" {"reasoning_tokens" 7}}
                     (edn/read-string
                      (:seon.ai.attempt/usage-edn (second first-rows)))))
              (is (not (contains?
                        (edn/read-string
                         (:seon.ai.attempt/usage-edn (second first-rows)))
                        :seon.ai/settings))
                  "settings are beside usage, never inside it"))

            (db/transact!
             connection
             (run/close-tx
              {:seon.cluster.run/id "settings-run-1"
               :seon.cluster.run/process process
               :seon.cluster.run/closed-at now}))
            (config/apply!
             {:seon.db/connection connection
              :seon.boot/cluster-name cluster-name
              :seon.config/manifest
              {:seon.config.ai/model "after-apply"}})
            (prepare-call!
             connection agent-id "settings-run-2" "settings-message-2")
            (cluster.loop/turn
             {:seon.cluster.loop/cluster cluster
              :seon.cluster.work/next
              (call-work agent-id "settings-run-2")}
             now)
            (testing "both phases see the next run opened after config apply"
              (is (= 4 (count @overlays)))
              (is (= 4 (count @resolutions)))
              (is (= "after-apply" (:seon.ai/model (last @requests))))
              (is (= :high (:seon.ai/thinking (last @requests))))
              (let [last-row (last (settings-attempts @connection))]
                (is (= "after-apply" (:seon.ai/model last-row)))
                (is (= "after-apply"
                       (:seon.config.ai/model
                        (edn/read-string
                         (:seon.ai.attempt/settings-edn last-row))))))))))))))

;;; THE class regression for the self-feeding fault loop (2026-08-08 live
;;; drive). Delivery is the wake attribute, so any message a refused phase
;;; commits to the failing agent is a wake, and the woken turn meets the same
;;; unfixed cause. On cluster `default` that cycle made nine paid provider
;;; calls in twenty minutes with no external stimulus, because
;;; `:seon.config.error/escalate-to` named root and root was the only agent —
;;; and because this site had hand-rolled a SECOND escalation path that
;;; `dissoc`ed the dial to silence `seon.error/commit-tx` and then mailed one
;;; unbounded message per failure, never asking who had failed.
;;;
;;; That copy is deleted. The wanted behavior is stated here over both
;;; directions of the one surviving derivation, because asserting only the
;;; self case would leave the fix indistinguishable from switching escalation
;;; off: a refused phase escalates to ANOTHER agent, ONCE per signature per
;;; process at the recurrence limit, and never to the agent whose run was
;;; refused. The bound is the part that makes the class unrepresentable — a
;;; hundred refusals cannot become a hundred wakes for anyone.
(defn- refuse-phase!
  "Settle one refused `:prompt` phase and return who the transaction mails.
  COMMITS, because the recurrence fence is a query over committed facts:
  a preparation that never lands cannot recur."
  [connection escalate-to agent-id]
  (let [refusal-terminal-data (private-loop-fn 'refusal-terminal-data)
        prepared
        (refusal-terminal-data
         {:seon.config.error/escalate-to escalate-to
          :seon.cluster.run/process process
          :seon.config.error/recurrence-limit 3
          :seon.sci.admit/caps (config/result-caps (config/defaults))}
         @connection now agent-id nil process nil nil
         {:seon.error/kind :seon.cluster.loop.phase/prompt
          :seon.error/message "injected prompt failure"
          :seon.error/data {:seon.cluster.loop/phase :prompt}})
        recipients (into []
                         (keep #(second (:seon.cluster.message/to %)))
                         (filter :seon.cluster.message/id
                                 (:seon.db/tx-data prepared)))]
    (db/transact! connection (:seon.db/tx-data prepared))
    recipients))

(defn- committed-error-count
  [connection]
  (or (db/q '[:find (count ?error) . :where [?error :seon.error/id _]]
            @connection)
      0))

(deftest a-refused-phase-escalates-once-per-signature-and-never-to-itself
  (testing "a supervisor hears about a worker's refused phases — once"
    (test-support/with-database
     (fn [connection]
       (db/transact! connection
                     [{:seon.cluster.agent/id "worker"}
                      {:seon.cluster.agent/id "supervisor"}])
       (is (= [[] [] ["supervisor"] [] [] []]
              (mapv (fn [_] (refuse-phase! connection "supervisor" "worker"))
                    (range 6)))
           "silent below the recurrence limit, one escalation AT it, silence
            past it — the same fence every other failure passes through")
       (is (= 6 (committed-error-count connection))
           "every occurrence is still evidence; only the mailing is bounded"))))
  (testing "the failing agent is never mailed about its own refusal"
    (test-support/with-database
     (fn [connection]
       (db/transact! connection [{:seon.cluster.agent/id "worker"}])
       (is (= [[] [] [] [] [] []]
              (mapv (fn [_] (refuse-phase! connection "worker" "worker"))
                    (range 6)))
           "escalating to yourself is not a notification, it is a wake, and
            the woken turn meets the same unfixed cause")
       (is (empty?
            (db/q '[:find ?message
                    :where [?message :seon.cluster.message/about _]]
                  @connection))
           "not one fault message exists to wake it with")
       (is (= 6 (committed-error-count connection)))))))

(deftest the-committed-set-is-computed-and-covers-what-the-loop-writes
  (let [committed (cluster.loop/committed-attributes)]
    (is (set? committed))
    (testing "every family the turn commits is in it"
      (is (some #(= "seon.cluster.run" (namespace %)) committed))
      (is (some #(= "seon.cluster.run.form" (namespace %)) committed))
      (is (some #(= "seon.cluster.eval" (namespace %)) committed))
      (is (some #(= "seon.ai.attempt" (namespace %)) committed)
          "including the model-attempt chain — a durable row per call,
           so a family boot never learned about is caught here rather
           than by a live drive that loses its whole transaction"))
    (testing "and the trigger is NOT — that is the wake, not our write"
      (is (not (contains? committed :seon.cluster.message/to))))))

(deftest the-committed-set-extracts-map-entries-by-shape
  (let [entries [[:seon.test/first :string]
                 [:seon.test/second :string]]
        expected #{:seon.test/first :seon.test/second}
        committed-test-attributes
        (fn [definition]
          (with-redefs [schema/schema-definition (constantly definition)]
            (into #{}
                  (filter #(= "seon.test" (namespace %)))
                  (cluster.loop/committed-attributes))))]
    (is (= expected (committed-test-attributes (into [:map] entries)))
        "a propertyless Malli map keeps its first entry")
    (is (= expected
           (committed-test-attributes
            (into [:map {:seon.db/entity true}] entries)))
        "an optional properties map does not change the extracted entries")))

(deftest a-disposition-is-read-only-when-it-really-is-one
  (is (= (my.run/wait "later") (cluster.loop/disposition (my.run/wait "later"))))
  (is (= (my.run/complete "done")
         (cluster.loop/disposition (my.run/complete "done"))))
  (testing "and anything else is not a disposition"
    (doseq [value [42 nil "done" {:my.run/disposition :invented}
                   {:seon.error/message "boom" :seon.error/kind :x}
                   {:my.run/disposition :completed}]]
      (is (nil? (cluster.loop/disposition value))
          (str "must not read as a disposition: " (pr-str value))))))

;;; THE CLASS-KILLER: what boot installs must cover what the loop writes
;;;
;;; The live drive died in its first second on `Bad entity attribute
;;; :seon.cluster.message/to`. Every suite was green, because every
;;; fixture installs an EXPLICIT attribute list and so bypasses the rule
;;; the boot path actually uses: `canonical-database-attributes`
;;; installs entity-map entries by construction and standalone forms
;;; only when they carry a persistence facet. Four families had no
;;; entity map and therefore installed exactly one attribute each.
;;;
;;; These two tests are the recurring surface for that class. The subset
;;; assertion is cheap and states the invariant; the transact-against-a
;;; -boot-built-database test is the one with teeth, because it uses the
;;; SAME derivation boot uses and then writes the rows the turn writes.
;;; ---------------------------------------------------------------------------

(deftest everything-the-loop-writes-is-installable-by-boot
  (let [installable (set (schema/canonical-database-attributes))]
    (testing "every attribute the loop commits"
      (is (empty? (remove installable (cluster.loop/committed-attributes)))
          "an attribute the loop writes that boot cannot install is a
           run that dies on its first transaction"))
    (testing "and every attribute the wake listens for"
      (is (empty? (remove installable (wake/wake-attributes)))
          "a wake attribute boot cannot install can never be committed,
           so the loop would never wake at all"))))

(deftest a-boot-built-database-takes-every-row-the-turn-writes
  ;; NO explicit attribute list: the schema comes from the same
  ;; derivation the ancestor build uses, so this database is the one the
  ;; live drive boots onto.
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :schema-flexibility :write}
        _ (d/create-database configuration)
        connection (d/connect configuration)]
    (try
      (db/transact! connection
                  (schema.datahike/malli->datahike-schema
                   (schema/canonical-database-attributes)))
      (testing "the trigger — the exact transact the live drive failed on"
        (is (map? (db/transact! connection
                              [{:seon.cluster.agent/id "alice"}
                               {:seon.cluster.message/id "m-live"
                                :seon.cluster.message/to
                                [:seon.cluster.agent/id "alice"]
                                :seon.cluster.message/content "count the widgets"
                                :seon.cluster.message/at now}]))))
      (testing "the run, its agent pointer, and recorded trigger"
        (is (map? (db/transact!
                   connection
                   {:tx-data [{:seon.cluster.run/id "run-live"
                               :seon.cluster.run/agent
                               [:seon.cluster.agent/id "alice"]
                               :seon.cluster.run/trigger
                               [:seon.cluster.message/id "m-live"]
                               :seon.cluster.run/opened-at now
                               :seon.cluster.run/process "process/one"
                               :seon.cluster.run/plan-digest
                               (apply str (repeat 64 "a"))}
                              {:seon.cluster.agent/id "alice"
                               :seon.cluster.agent/run
                               [:seon.cluster.run/id "run-live"]}]}))))
      (testing "one frozen form"
        (is (map? (db/transact! connection
                              [{:seon.cluster.run.form/id "f-0"
                                :seon.cluster.run.form/run
                                [:seon.cluster.run/id "run-live"]
                                :seon.cluster.run.form/ordinal 0
                                :seon.cluster.run.form/source "(+ 1 1)"}]))))
      (testing "a running receipt (no terminal fact) and its settlement"
        (is (map? (db/transact! connection
                              [{:seon.cluster.eval/id "e-0"
                                :seon.cluster.eval/run
                                [:seon.cluster.run/id "run-live"]
                                :seon.cluster.eval/ordinal 0
                                :seon.cluster.eval/at now}])))
        (is (map? (db/transact! connection
                              [{:seon.cluster.eval/id "e-0"
                                :seon.cluster.eval/result-edn "2"}]))))
      (testing "the model-attempt chain: a failed primary carrying its
      transport evidence, and the backup that points back at it"
        (is (map? (db/transact! connection
                              [{:seon.ai.attempt/id "run-live-attempt-0"
                                :seon.ai.attempt/run
                                [:seon.cluster.run/id "run-live"]
                                :seon.ai.attempt/ordinal 0
                                :seon.ai.attempt/at now
                                :seon.ai/endpoint "https://example.invalid/v1"
                                :seon.ai/model "primary-probe"
                                :seon.ai/http-status 503
                                :seon.ai/request-transmitted? false
                                :seon.ai/response-started? false
                                :seon.ai/output-observed? false}])))
        (is (map? (db/transact! connection
                              [{:seon.ai.attempt/id "run-live-attempt-1"
                                :seon.ai.attempt/run
                                [:seon.cluster.run/id "run-live"]
                                :seon.ai.attempt/ordinal 1
                                :seon.ai.attempt/at now
                                :seon.ai/endpoint "https://example.invalid/v2"
                                :seon.ai/model "backup-probe"
                                :seon.ai.attempt/delay-ms 0
                                :seon.ai.attempt/failover-from
                                [:seon.ai.attempt/id "run-live-attempt-0"]}]))))
      (testing "and an error receipt, whose result and error both land"
        (is (map? (db/transact! connection
                              [{:seon.cluster.eval/id "e-1"
                                :seon.cluster.eval/run
                                [:seon.cluster.run/id "run-live"]
                                :seon.cluster.eval/ordinal 1
                                :seon.cluster.eval/at now
                                :seon.cluster.eval/error "boom"
                                :seon.cluster.eval/result-edn "{:seon.error/kind :x}"}]))))
      (testing "the refs really are refs — a follow, not a string"
        (is (= "alice"
               (db/q '[:find ?id .
                      :where
                      [?m :seon.cluster.message/id "m-live"]
                      [?m :seon.cluster.message/to ?agent]
                      [?agent :seon.cluster.agent/id ?id]]
                    @connection))))
      (finally
        (d/release connection)
        (d/delete-database configuration)))))

;;; ---------------------------------------------------------------------------
;;; The crash walk, as kill positions over facts
;;; ---------------------------------------------------------------------------

(def ^:private attributes
  [:seon.cluster.agent/id :seon.cluster.agent/run
   :seon.cluster.run/id :seon.cluster.run/agent :seon.cluster.run/trigger
   :seon.cluster.run/opened-at
   :seon.cluster.run/closed-at :seon.cluster.run/process
   :seon.cluster.run/plan-digest :seon.cluster.run/forms
   :seon.cluster.run.form/id :seon.cluster.run.form/run
   :seon.cluster.run.form/ordinal :seon.cluster.run.form/source
   :seon.cluster.eval/id :seon.cluster.eval/run :seon.cluster.eval/ordinal
   :seon.cluster.eval/at
   :seon.cluster.eval/interrupted-at :seon.cluster.eval/result-edn
   :seon.cluster.eval/error
   :seon.cluster.message/id :seon.cluster.message/to
   :seon.cluster.message/content :seon.cluster.message/at])

(def ^:private request
  "The AGENT-SCOPED request (F2 §3.2): the kill positions below are
  per-agent facts and always were, so the crash walk derives through
  `next-agent-work` with the same rows and the same expected
  situations."
  {:seon.cluster.agent/id "agent-a"
   :seon.cluster.run/process process
   :seon.cluster.work/now now})

(defn- with-database [body]
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :schema-flexibility :write}
        _ (d/create-database configuration)
        connection (d/connect configuration)]
    (try
      (db/transact! connection (schema.datahike/malli->datahike-schema attributes))
      (db/transact! connection
                  [{:seon.cluster.agent/id "agent-a"}
                   {:seon.cluster.message/id "m-1"
                    :seon.cluster.message/to [:seon.cluster.agent/id "agent-a"]
                    :seon.cluster.message/content "go"
                    :seon.cluster.message/at now}])
      (body connection)
      (finally
        (d/release connection)
        (d/delete-database configuration)))))

(defn- commit-run! [connection {:keys [held? planned? receipts closed?]}]
  (db/transact!
   connection
   {:tx-data
    (cond-> [(cond-> {:seon.cluster.run/id "run-1"
                      :seon.cluster.run/agent [:seon.cluster.agent/id "agent-a"]
                      :seon.cluster.run/trigger
                      [:seon.cluster.message/id "m-1"]
                      :seon.cluster.run/opened-at now}
               held? (assoc :seon.cluster.run/process process)
               planned? (assoc :seon.cluster.run/plan-digest
                               (apply str (repeat 64 "a")))
               closed? (assoc :seon.cluster.run/closed-at now))]
      (not closed?)
      (conj {:seon.cluster.agent/id "agent-a"
             :seon.cluster.agent/run [:seon.cluster.run/id "run-1"]})

      planned?
      (into (map (fn [ordinal]
                   {:seon.cluster.run.form/id (str "f-" ordinal)
                    :seon.cluster.run.form/run [:seon.cluster.run/id "run-1"]
                    :seon.cluster.run.form/ordinal ordinal
                    :seon.cluster.run.form/source "(+ 1 1)"})
                 (range 2)))

      (seq receipts)
      ;; the receipt's state is WHICH terminal fact it carries: :done →
      ;; result-edn, :interrupted → interrupted-at, none → running
      (into (map (fn [[ordinal state]]
                   (cond-> {:seon.cluster.eval/id (str "e-" ordinal)
                            :seon.cluster.eval/run
                            [:seon.cluster.run/id "run-1"]
                            :seon.cluster.eval/ordinal ordinal
                            :seon.cluster.eval/at now}
                     (= :done state)
                     (assoc :seon.cluster.eval/result-edn "2")
                     (= :interrupted state)
                     (assoc :seon.cluster.eval/interrupted-at now)))
                 receipts)))}))

;;; The F2 sealed suite — kill-positions-per-agent-test, seed 2026072827.
;;; ORACLE: the crash-walk rows 1-10, re-grounded — `next-agent-work`
;;; derives the same expected situation per row under the AGENT-SCOPED
;;; request. Boot-recovery rows are absent here because recovery now
;;; closes interrupted runs before this derivation can see them. The
;;; rows were always per-agent facts; the global pass just asked the
;;; question badly.

(deftest kill-positions-per-agent-test
  (doseq [[row state expected]
          [["1 — trigger only" nil :open]
           ["2-4 — claimed, no plan, custody died" {} nil]
           ["5 — planned, no receipts" {:held? true :planned? true} :resume]
           ["8 — one terminal receipt"
            {:held? true :planned? true :receipts [[0 :done]]} :resume]
           ["9 — every receipt terminal, run open"
            {:held? true :planned? true
             :receipts [[0 :done] [1 :done]]} :close]
           ["10 — closed"
            {:held? true :planned? true :closed? true
             :receipts [[0 :done] [1 :done]]} nil]]]
    (with-database
      (fn [connection]
        (when state (commit-run! connection state))
        (let [derived (work/next-agent-work (db/db connection) request)]
          (testing (str "work derivation row " row)
            (is (= expected (:seon.cluster.work/situation derived)))))))))
