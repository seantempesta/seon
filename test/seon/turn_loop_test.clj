(ns seon.turn-loop-test
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
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [seon.blob :as blob]
            [seon.db :as db]
            [seon.run :as my.turn]
            [seon.ai :as ai]
            [seon.config :as config]
            [seon.cluster.agent :as cluster.agent]
            [seon.turn :as turn]
            [seon.cluster.prompt :as prompt]

            [seon.cluster.wake :as wake]

            [seon.flow :as seon.flow]
            [seon.eval.drive :as eval.drive]
            [seon.render.transcript :as transcript]
            [seon.render.web :as web]
            [seon.schema :as schema]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as test-support])
  (:import [java.util Date]))

(def ^:private test-environment
  ;; The subset environment (store layer only) every crossing this
  ;; namespace constructs names; boot's own constructor, fewer layers.
  (delay (test-support/environment "seon.turn-loop-test")))

;;; ---------------------------------------------------------------------------
;;; The pure parts
;;; ---------------------------------------------------------------------------

(def ^:private now (Date. 1700000000000))
(def ^:private process "process/one")

(defn- private-loop-fn
  [function-name]
  (deref (ns-resolve 'seon.turn function-name)))

(defn- private-run-fn
  [function-name]
  (deref (ns-resolve 'seon.turn function-name)))

(deftest read-evidence-settlement-reuses-the-outer-transaction-codec
  (let [evidence
        (mapv (fn [ordinal]
                {:seon.db/source-argument-position ordinal
                 :datahike.read/dependency-plan {:ordinal ordinal}
                 :datahike.read/revision {:basis ordinal}})
              (range 1024))
        started (System/nanoTime)
        tx-data
        (with-redefs [schema/projection-from-database
                      (fn [& _]
                        (throw
                         (ex-info "read evidence rebuilt the projection" {})))]
          ((private-run-fn 'receipt-read-evidence-tx)
           {:db/id 42
            :seon.cluster.eval/id "run-1-receipt-0"}
           {:seon.cluster.eval/read-evidence evidence}))
        elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)
        entities (:seon.cluster.eval/read-evidence (first tx-data))]
    (is (= 1024 (count entities)))
    (is (= "seon.cluster.eval/read-evidence/run-1-receipt-0/1023"
           (:db/id (last entities))))
    (is (< elapsed-ms 200.0)
        "read-evidence transaction projection stays within its bound")))

(deftest attempt-settlement-updates-the-registered-model-gauges
  (test-support/with-database
    (fn [connection]
      (config/apply! {:seon.boot/cluster-name "default" :seon.db/connection connection})
      (test-support/transacted!
                   connection
                   (into (test-support/agent-tx @connection "gauge-agent")
                     [{:seon.turn/id "gauge-run"
                       :seon.turn/agent [:seon.agent/id "gauge-agent"]
                       :seon.turn/opened-tx "datomic.tx"}]))
      ;; A REGISTERED id. The gauges are an observation of a model the config
      ;; descriptors declare. This test named "deepseek-v4-flash", which no
      ;; shipped descriptor carries (they are deepseek-flash, deepseek-v4-pro,
      ;; deepseek/deepseek-v4-flash-20260731, kimi-k3, muse-spark-1.1), so
      ;; ai/model-observation-tx had nothing to observe and every gauge read
      ;; back nil.
      ((private-loop-fn 'record-attempt!)
       {:seon.db/connection connection}
       {:seon.ai/target
        (assoc (:seon.ai/primary (ai/targets (schema/handed-projection) (test-support/effective-config))) :seon.ai/model "deepseek-flash")
        :seon.ai/settings
        (ai/settings (test-support/effective-config)
                     {:seon.config.ai/model "deepseek-flash"})
        :seon.turn/id "gauge-run"
        :seon.agent/id "gauge-agent"
        :seon.ai.attempt/ordinal 0
        :seon.ai.model/last-latency-ms 200
        :seon.ai/usage {"completion_tokens" 20}}
       now)
      (let [model (ai/model-entity @connection "deepseek-flash")]
        (is (= now (:seon.ai.model/last-used-at model)))
        (is (= 200 (:seon.ai.model/last-latency-ms model)))
        (is (= 100.0 (:seon.ai.model/last-tokens-per-second model)))
        (is (= 1
               (db/q
                '[:find (count ?attempt) . :in $ ?id
                  :where
                  [?attempt :seon.ai.attempt/id ?id]]
                @connection (#'turn/attempt-id "gauge-run" 0)))
            "the durable attempt and display gauges settle together")))))

(deftest committed-ending-namespace-seeds-a-resumed-fold
  (test-support/with-database
    (fn [connection]
      (let [cluster-name "namespace-resume"
            agent-id "namespace-resume-agent"
            run-id "namespace-resume-run"
            starting-ns 'my.agents.namespace-resume
            ending-ns 'my.generated.after-resume]
        (test-support/seed-cluster! connection cluster-name)
        (test-support/transacted!
                     connection
                     (cluster.agent/creation-tx
                      {:seon.agent/id agent-id
                       :seon.ns/name starting-ns
                       :seon.cluster/name cluster-name}))
        ;; An ending namespace is an observed symbol value. Resuming a fold
        ;; does not require a fabricated namespace entity.
        (test-support/transacted!
                     connection
                     (turn/open-tx {:seon.turn/id run-id :seon.turn/agent [:seon.agent/id agent-id] :seon.turn/opened-tx "datomic.tx"}))

        (test-support/transacted!
                     connection
                     (turn/plan-tx
                      {:seon.turn/id run-id :seon.db.process/id process :seon.turn/starting-ns [:seon.ns/name starting-ns] :seon.turn/sources [{:seon.cluster.eval/source
                         "(when true (in-ns 'my.generated.after-resume))"
                         :seon.ns/name starting-ns}
                        {:seon.cluster.eval/source
                         (str "(defn ^{:malli/schema [:=> [:cat] :int]} "
                              "attributed-after-resume [] 1)")
                         :seon.ns/name starting-ns}]}))
        ;; The freeze minted both evaluations; nothing separate starts one.
        (let [ctx (test-support/fork-cluster-ctx connection)
              first-evaluation
              (test-support/evaluate
               {:seon.cluster.eval/source
                "(when true (in-ns 'my.generated.after-resume))"
                :seon.cluster.eval/ns [:seon.ns/name starting-ns]
                :seon.sci.eval/ctx ctx
                :seon.sci.admit/caps
                (config/result-caps config/defaults)
                :seon.sci.eval/time-limit-ms 2000
                :seon.boot/cluster-name cluster-name
                :seon.agent/id agent-id
                :seon.turn/id run-id
                :seon.cluster.eval/ordinal 0})]
          (is (= ending-ns (:seon.sci.eval/ending-ns first-evaluation)))
          (test-support/transacted!
                       connection
                       ;; ABSENT MEANS NO KEY: the settle request marks every terminal
                       ;; fact optional, and an optional key present as nil fails its
                       ;; contract, so the request carries only the facts the
                       ;; evaluation actually produced.
                       (turn/receipt-settle-tx
                        (merge {:seon.turn/id run-id
                                :seon.cluster.eval/ordinal 0}
                               (select-keys first-evaluation
                                            [:seon.eval/shown
                                             :seon.cluster.eval/ns
                                             :seon.sci.eval/ending-ns]))))
          (is (= ending-ns
                 (:seon.sci.eval/ending-ns
                  (db/pull @connection
                           [:seon.sci.eval/ending-ns]
                           [:seon.cluster.eval/id (turn/receipt-identity run-id 0)]))))
          (let [fold-evaluations (private-loop-fn 'fold-evaluations)
                fold-namespace (private-loop-fn 'fold-namespace)
                resumed-namespace
                (fold-namespace @connection run-id
                                (fold-evaluations @connection run-id) 1)
                defaults config/defaults
                channel (async/chan 1)
                cluster (merge defaults
                               {:seon.db/connection connection
                                :seon.cluster/name cluster-name
                                :seon.db.process/id process
                                :seon.sci.eval/ctx ctx
                                :seon.cluster.wake/channel channel
                                :seon.render/context-channel channel
                                :seon.turn.loop/completion channel
                                :seon.sci.admit/caps (config/result-caps defaults)
                                :seon.config.eval/time-limit-ms 2000})
                outcome
                (try
                  (first
                   (:seon.turn.loop/evaluated-sources
                    (turn/evaluate-sources
                    {:seon.turn.loop/cluster cluster
                     :seon.sci.eval/ctx ctx
                     :seon.agent/id agent-id
                     :seon.turn/id run-id
                     :seon.cluster.eval/ordinal 1
                     :seon.ns/name resumed-namespace
                     :seon.cluster.reply/sources
                     [((private-loop-fn 'fold-source)
                       (first (filter #(= 1 (:seon.cluster.eval/ordinal %))
                                      (fold-evaluations @connection run-id))))]})))
                  (finally (async/close! channel)))
                form (:seon.turn.loop/admitted-form outcome)
                evaluation (:seon.sci.eval/evaluation outcome)]
            (is (= ending-ns resumed-namespace))
            (is (= [:seon.ns/name ending-ns]
                   (:seon.cluster.eval/ns form)))
            (is (nil? (:seon.cluster.eval/error evaluation)))
            ;; Construct the expected name from data: a quoted qualified
            ;; symbol would declare this regression itself as a gate test for
            ;; the function it is evaluating, recursively running the fixture
            ;; under the generated namespace.
            (is (= (symbol (str ending-ns) "attributed-after-resume")
                   (get-in outcome
                           [:seon.batch/change :seon.batch/entities 0 :seon.fn/sym])))))))))

(deftest two-agents-resolve-one-config-entity-with-ordinary-inheritance
  (test-support/with-database
    (fn [connection]
      (let [cluster-name "settings-resolution"]
        (config/apply! {:seon.db/connection connection
                        :seon.boot/cluster-name cluster-name})
        (test-support/seed-cluster! connection cluster-name)
        (test-support/transacted!
                     connection
                     (into (cluster.agent/creation-tx
                            {:seon.agent/id "planner"
                             :seon.ns/name 'my.agents.planner
                             :seon.cluster/name cluster-name})
                           (cluster.agent/creation-tx
                            {:seon.agent/id "worker"
                             :seon.ns/name 'my.agents.worker
                             :seon.cluster/name cluster-name})))
        (test-support/transacted! connection
                                [{:seon.agent/id "planner"
                                  :seon.agent/settings {:seon.config.ai/thinking :high}}])
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
  [{connection :seon.db/connection :as cluster} agent-id run-id message-id]
  (test-support/transacted! connection
                          [{:seon.message/id message-id :seon.message/to [:seon.agent/id agent-id] :seon.message/content "prove live settings"}])
  (test-support/transacted!
               connection
               (into (test-support/agent-tx @connection agent-id)
                 [{:seon.turn/id run-id :seon.turn/agent [:seon.agent/id agent-id] :seon.turn/trigger [:seon.message/id message-id] :seon.turn/opened-tx "datomic.tx"}])))

(defn- call-work
  [agent-id run-id]
  {:seon.turn.work/situation :call
   :seon.agent/id agent-id
   :seon.turn/id run-id})

(defn- settings-attempts
  [db]
  (->> (db/q '[:find ?run-id ?ordinal ?attempt
              :where
              [?run :seon.turn/attempts ?attempt]
              [?run :seon.turn/id ?run-id]
              [?attempt :seon.ai.attempt/ordinal ?ordinal]]
            db)
       (map (fn [[run-id ordinal attempt]]
              (assoc (db/pull db '[*] attempt)
                     ::attempt-run-id run-id
                     ::attempt-ordinal ordinal)))
       (sort-by (juxt ::attempt-run-id ::attempt-ordinal))
       vec))

(deftest refused-generated-opening-captures-evidence-before-close
  (test-support/with-database
   (fn [connection]
     (let [cluster-name "refused-generated-opening"
           agent-id "generated-agent"
           run-id "bootstrap:generated-agent"
           message-id "generated-trigger"
           ;; A declared :seon.cluster.prompt/error: the prompt owner's `refused`
           ;; arm names the agent and its derivation observation. A bare
           ;; diagnostic is not a prompt return, so the turn must not see one.
           message "The generated opening could not be derived."
           ;; The producer declares its error's schema on the value's
           ;; metadata, as `seon.cluster.prompt/prompt` does; the recorder
           ;; requires it.
           refusal (with-meta
                     {:seon.error/at (java.util.Date.) :seon.error/layer :seon.cluster.prompt/invocation :seon.error/operation 'seon.cluster.prompt/prompt
                      :seon.error/message message
                      :seon.cluster.prompt/error-agent-id agent-id
                      :seon.cluster.prompt/derivation-observation
                      {:seon.error.evidence/attribute :seon.error/message
                       :seon.error.evidence/value message}}
                     {:seon.error/declared-schema :seon.cluster.prompt/error})
           ;; The cluster's world exists before its handle reads its config.
           _ (test-support/seed-cluster! connection cluster-name)
           cluster (test-support/cluster-handle
                   {:seon.env/environment (test-support/environment cluster-name connection)
                    :seon.db/connection connection
                    :seon.cluster/name cluster-name
                    :seon.db.process/id process
                    :seon.sci.eval/ctx
                    (test-support/fork-cluster-ctx connection)
                    :seon.config.eval/time-limit-ms 2000
                    :seon.sci.admit/caps
                    (config/result-caps config/defaults)
                    :seon.config.error/recurrence-limit 3
                    :seon.config.message/max-chain 8})]
       (test-support/transacted!
                    connection
                    (cluster.agent/creation-tx
                     {:seon.agent/id agent-id
                      :seon.ns/name 'my.agents.generated-agent
                      :seon.cluster/name cluster-name}))
       (test-support/transacted!
                    connection
                    [{:seon.message/id message-id :seon.message/to [:seon.agent/id agent-id] :seon.message/content "start"}])
       (test-support/transacted!
                    connection
                    (turn/open-tx {:seon.turn/id run-id :seon.turn/agent [:seon.agent/id agent-id] :seon.turn/trigger [:seon.message/id message-id] :seon.turn/opened-tx "datomic.tx"}))

       (with-redefs [prompt/prompt (fn [_database _request] refusal)]
         (let [report
               (turn/turn
                {:seon.turn.loop/cluster cluster
                 :seon.turn.work/next (call-work agent-id run-id)}
                now)
               capture
               (db/q '[:find (pull ?capture [*]) .
                       :in $ ?run-id
                       :where
                       [?run :seon.turn/id ?run-id]
                       [?capture :seon.context.capture/run ?run]]
                     @connection run-id)]
           (is (= :error (:seon.turn.loop/outcome report)))
           (is (inst? (test-support/turn-closed-at @connection run-id)))
           (is (empty? (settings-attempts @connection))
               "no model attempt entity: the provider boundary was never crossed")
           (is (= (:seon.error/message refusal)
                  (:seon.error/message capture)))
           (is (int? (:seon.context.capture/basis-t capture)))
           (is (not (find capture :seon.context.capture/prompt)))))))))

(defn- with-render-context-proc
  [cluster body]
  (let [context-channel (async/chan)
        render-channel (async/chan (async/sliding-buffer 1))
        runtime-eval-channel (async/chan (async/sliding-buffer 1))
        pages-channel (async/chan (async/sliding-buffer 1))
        stream-channel (async/chan (async/sliding-buffer 1))
        completion (async/promise-chan)
        cluster (assoc cluster
                       :seon.env/environment @test-environment
                       :seon.render/context-channel context-channel
                       :seon.turn.loop/stream-channel stream-channel)
        graph
        (flow.core/create-flow
         {:procs
          {:seon.render.web/render
           {:proc
            (seon.flow/var-process
             #'web/render-step :io
             {:seon.env/environment @test-environment
              :seon.render.web/render-channel render-channel
              :seon.render.web/runtime-eval-channel runtime-eval-channel
              :seon.render/context-channel context-channel
              :seon.render.web/pages-channel pages-channel
              :seon.render.web/registration (atom {})
              :seon.render.web/latest-packages (atom {})
              :seon.render.web/interest (atom :all)
              :seon.render.web/completion completion
              :seon.render.web/root-agent-id "planner"
              :seon.turn.loop/cluster cluster})}}
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

(deftest prompt-and-call-resolve-once-record-settings-and-see-next-turn-config
  (test-support/with-database
   (fn [connection]
     (let [cluster-name "live-settings"
           agent-id "planner"
           settings-fn ai/settings
           overlay-fn ai/agent-overlay
           resolutions (atom [])
           overlays (atom [])
           overlay-values (atom [])
           requests (atom [])
           ;; ONE APPLY. `config/apply!` EXACT-reconciles the desired entity, so the
           ;; overlay applied first and `seed-cluster!`'s empty manifest second
           ;; left the shipped models behind — the call then read
           ;; "deepseek-flash", not "before-apply". The cluster is seeded WITH its
           ;; overlay, before its handle reads its config.
           _ (test-support/seed-cluster!
              connection cluster-name
              {:seon.config.ai/model "before-apply"})
           cluster (test-support/cluster-handle
                   {:seon.env/environment (test-support/environment cluster-name connection)
                    :seon.db/connection connection
                    :seon.cluster/name cluster-name
                    :seon.db.process/id process
                    :seon.sci.eval/ctx
                    (test-support/fork-cluster-ctx connection)
                    :seon.config.eval/time-limit-ms 2000
                    :seon.sci.admit/caps
                    (config/result-caps (test-support/effective-config))
                    :seon.config.error/recurrence-limit 3
                    :seon.config.message/max-chain 8})
           completions (atom [{:seon.ai/text "(identity 1)"
                               :seon.ai/usage
                               {"completion_tokens_details"
                                {"reasoning_tokens" 7}}
                               :seon.ai/finish-reason "stop"}
                              {:seon.ai/text "(identity 2)"
                               :seon.ai/finish-reason "stop"}])]
       (test-support/transacted!
                    connection
                    (cluster.agent/creation-tx
                     {:seon.agent/id agent-id
                      :seon.ns/name 'my.agents.live-settings
                      :seon.cluster/name cluster-name}))
       (let [opening (turn/system-turn {:seon.turn.loop/cluster cluster
                                        :seon.agent/id agent-id
                                        :seon.turn/write? true})]
         (is (vector? (:seon.turn/forms opening))))
       (prepare-call! cluster agent-id "settings-run-1" "settings-message-1")
       ;; `prepare-call!` re-runs the agent's creation transaction, which mints
       ;; a fresh settings component; the override is asserted after it.
       (test-support/transacted!
                    connection
                    [{:seon.agent/id agent-id
                      :seon.agent/settings {:seon.config.ai/thinking :high}}])
       (with-render-context-proc
        cluster
        (fn [cluster]
          (with-redefs [ai/agent-overlay
                        (fn [db id]
                          (swap! overlays conj [db id])
                          (let [value (overlay-fn db id)]
                            (swap! overlay-values conj value)
                            value))
                        ai/settings
                        (fn [cluster-settings agent-settings]
                          (let [resolved
                                (settings-fn cluster-settings agent-settings)]
                            (swap! resolutions conj resolved)
                            resolved))
                        ai/complete
                        (fn [_projection request]
                          (swap! requests conj request)
                          (let [completion (first @completions)]
                            (swap! completions subvec 1)
                            completion))]
            (turn/turn
             {:seon.turn.loop/cluster cluster
              :seon.turn.work/next
              (call-work agent-id "settings-run-1")}
             now)
            (testing "the opening prompt and paid call each resolve once"
              (is (= 3 (count @overlays))
                  "profile, prompt and call each read the overlay")
              (is (= 2 (count @resolutions))
                  "prompt budget uses opening facts; attempts share call settings")
              (is (= ["before-apply"]
                     (mapv :seon.ai/model @requests)))
              (is (= [:high]
                     (mapv :seon.ai/thinking @requests))))
            (let [first-settings (first @resolutions)
                  first-entities (settings-attempts @connection)]
              (is (= 1 (count first-entities)))
              (is (every? #(= first-settings
                              (edn/read-string
                               (:seon.ai.attempt/settings-edn %)))
                          first-entities))
              (is (= "stop" (:seon.ai.attempt/finish-reason
                              (first first-entities))))
              (is (= {"completion_tokens_details" {"reasoning_tokens" 7}}
                     (edn/read-string
                      (:seon.ai.attempt/usage-edn (first first-entities)))))
              (is (not (contains?
                        (edn/read-string
                         (:seon.ai.attempt/usage-edn (first first-entities)))
                        :seon.ai/settings))
                  "settings are beside usage, never inside it"))

            ;; `turn/turn` closed settings-run-1 itself; closing it again is
            ;; the fixture writing what the mechanism wrote, and the writer
            ;; says so (`run transition refused: run-closed`).
            (config/apply!
             {:seon.db/connection connection
              :seon.boot/cluster-name cluster-name
              :seon.config/manifest
              {:seon.config.ai/model "after-apply"}})
            (prepare-call!
             cluster agent-id "settings-run-2" "settings-message-2")
            (test-support/transacted!
             connection
             [{:seon.agent/id agent-id
               :seon.agent/settings {:seon.config.ai/thinking :high}}])
            (reset! overlays [])
            (reset! overlay-values [])
            (reset! resolutions [])
            (turn/turn
             {:seon.turn.loop/cluster cluster
              :seon.turn.work/next
              (call-work agent-id "settings-run-2")}
             now)
            (testing "both phases see the next run opened after config apply"
              (is (= 3 (count @overlays)))
              (is (= 2 (count @resolutions)))
              (is (= "after-apply" (:seon.ai/model (last @requests))))
              (is (= :high (:seon.ai/thinking (last @requests))))
              (is (apply = @overlay-values)
                  "the second run's own three reads still settle on one value")
              (let [last-entity (last (settings-attempts @connection))]
                (is (= "after-apply" (:seon.ai/model last-entity)))
                (is (= "after-apply"
                       (:seon.config.ai/model
                        (edn/read-string
                         (:seon.ai.attempt/settings-edn last-entity))))))))))))))

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
;;; off: a refused phase escalates to ANOTHER agent, ONCE per signature (on
;;; its first occurrence of an opening: D13, 9c9b50e3c, 2026-09-19, which
;;; retired the recurrence limit from delivery), and never to the agent whose
;;; run was refused. The bound is the part that makes the class
;;; unrepresentable — a hundred refusals cannot become a hundred wakes for
;;; anyone.
(defn- refuse-phase!
  "Settle one refused `:prompt` phase and return who the transaction mails.
  COMMITS, because the recurrence fence is a query over committed facts:
  a preparation that never lands cannot recur."
  {:malli/schema [:=> [:cat :seon.db/connection :string :string] [:vector :string]]}
  [connection escalate-to agent-id]
  (let [refusal-terminal-data (private-loop-fn 'refusal-terminal-data)
        prepared
        (refusal-terminal-data
         (test-support/cluster-handle
          {:seon.env/environment (test-support/environment (:seon.cluster/name (test-support/execution-handle connection)) connection)
          :seon.db/connection connection
          :seon.cluster/name (:seon.cluster/name (test-support/execution-handle connection))
          :seon.sci.eval/ctx (test-support/fork-cluster-ctx connection)
          :seon.config.error/escalate-to escalate-to
          :seon.db.process/id process
          :seon.config.error/recurrence-limit 3
          :seon.sci.admit/caps (config/result-caps config/defaults)})
         @connection now agent-id nil process nil nil
         ;; The phase-failed value, with its declared schema, from its producer.
         ((private-loop-fn 'phase)
          #(throw (ex-info "injected prompt failure" {:seon.turn.loop/phase :prompt}))))
        before (set (db/q '[:find [?id ...] :where [?m :seon.message/id ?id]] @connection))]
    (test-support/transacted! connection (:seon.db/tx-data prepared))
    (into [] (keep (fn [[id recipient]] (when-not (before id) recipient)))
          (db/q '[:find ?id ?recipient
                  :where [?m :seon.message/id ?id]
                         [?m :seon.message/to ?agent]
                         [?agent :seon.agent/id ?recipient]] @connection))))

(defn- committed-error-count
  [connection]
  (or (db/q '[:find (sum ?count) . :with ?occurrence
              :where [?occurrence :seon.error.occurrence/count ?count]]
            @connection)
      0))

(deftest a-refused-phase-escalates-once-per-signature-and-never-to-itself
  (testing "a supervisor hears about a worker's refused phases — once"
    (test-support/with-database
     (fn [connection]
       (test-support/transacted! connection
                                 (test-support/agents-tx @connection ["worker" "supervisor"]))
       (is (= [["supervisor"] [] [] [] [] []]
              (mapv (fn [_] (refuse-phase! connection "supervisor" "worker"))
                    (range 6)))
           "one escalation on the signature's first occurrence, silence after
            it — the same once-per-opening fence every fault passes through")
       (is (= 6 (committed-error-count connection))
           "every occurrence is still evidence; only the mailing is bounded"))))
  (testing "the failing agent is never mailed about its own refusal"
    (test-support/with-database
     (fn [connection]
       (test-support/transacted! connection (test-support/agent-tx @connection "worker"))
       (is (= [[] [] [] [] [] []]
              (mapv (fn [_] (refuse-phase! connection "worker" "worker"))
                    (range 6)))
           "escalating to yourself is not a notification, it is a wake, and
            the woken turn meets the same unfixed cause")
       (is (empty?
            (db/q '[:find ?message
                    :where [?message :seon.message/about _]]
                  @connection))
           "not one fault message exists to wake it with")
       (is (= 6 (committed-error-count connection)))))))

(deftest the-committed-set-is-computed-and-covers-what-the-loop-writes
  (let [committed (turn/committed-attributes (seon.schema/handed-projection))]
    (is (set? committed))
    (testing "every family the turn commits is in it"
      (is (some #(= "seon.turn" (namespace %)) committed))
      (is (some #(= "seon.cluster.eval" (namespace %)) committed))
      (is (some #(= "seon.cluster.eval" (namespace %)) committed))
      (is (some #(= "seon.ai.attempt" (namespace %)) committed)
          "including the model-attempt chain — a durable row per call,
           so a family boot never learned about is caught here rather
           than by a live drive that loses its whole transaction"))
    (testing "and the trigger is NOT — that is the wake, not our write"
      (is (not (contains? committed :seon.message/to))))))

(deftest the-committed-set-extracts-map-entries-by-shape
  (let [entries [[:seon.test/first :string]
                 [:seon.test/second :string]]
        expected #{:seon.test/first :seon.test/second}
        committed-test-attributes
        (fn [definition]
          (let [projection (schema/build-projection
                            ;; `committed-attributes` reads its fixed entity
                            ;; list, so the definition stands in for one of them.
                            (assoc (:seon.schema.projection/forms (schema/handed-projection))
                                   :seon.turn/turn definition))]
            (into #{}
                  (filter #(= "seon.test" (namespace %)))
                  (turn/committed-attributes projection))))]
    (is (= expected (committed-test-attributes (into [:map] entries)))
        "a propertyless Malli map keeps its first entry")
    (is (= expected
           (committed-test-attributes
            (into [:map {:seon.render/ai 'seon.test/render-ai}] entries)))
        "an optional properties map does not change the extracted entries")))

(deftest a-disposition-is-read-only-when-it-really-is-one
  (is (= (seon.run/wait "later") (turn/disposition (seon.schema/handed-projection) (seon.run/wait "later"))))
  (is (= (seon.run/complete "done")
         (turn/disposition (seon.schema/handed-projection) (seon.run/complete "done"))))
  (testing "and anything else is not a disposition"
    (doseq [value [42 nil "done" {:my.turn/disposition :invented}
                   {:seon.error/message "boom" }
                   {:my.turn/disposition :completed}]]
      (is (nil? (turn/disposition (seon.schema/handed-projection) value))
          "must not read as a disposition")))
  (testing "a host value at a form's root is a value, never a crash: a database seqs its datoms, not entries"
    (test-support/with-database
      (fn [connection]
        (let [database (db/db connection)]
          (doseq [value [database connection (db/with database []) (sorted-map 'a 1)
                         (reify clojure.lang.IPersistentMap (seq [_] (list 1 2)))]]
            (is (nil? (turn/disposition (seon.schema/handed-projection) value))
                "a host value is not a disposition")))))))

(deftest a-clean-last-form-without-a-disposition-is-loud-terminal-evidence
  (test-support/with-database
    (fn [connection]
      (let [agent-id "undisposed-agent"
            run-id "undisposed-run"
            message-id "undisposed-trigger"
            terminal-data (private-loop-fn 'evaluation-terminal-data)]
        (test-support/transacted!
                     connection
                     (into [(test-support/namespace-entity @connection 'my.agents.undisposed-agent)]
                           cat
                           [(test-support/agent-tx @connection agent-id 'my.agents.undisposed-agent)
                            [{:seon.message/id message-id :seon.message/to [:seon.agent/id agent-id] :seon.message/content "prove the contract"}]]))
        (test-support/transacted!
                     connection
                     (into [] cat
                           [(turn/open-tx
                             {:seon.turn/id run-id :seon.turn/agent [:seon.agent/id agent-id] :seon.turn/trigger [:seon.message/id message-id] :seon.turn/opened-tx "datomic.tx"})
                            []
                            (turn/plan-tx
                             {:seon.turn/id run-id :seon.db.process/id process :seon.turn/reply "(defn answer-count [] 2)\n(answer-count)\n(+ (answer-count) 1)" :seon.turn/sources [{:seon.cluster.eval/source
                                "(defn answer-count [] 2)"}
                               {:seon.cluster.eval/source "(answer-count)"}
                               {:seon.cluster.eval/source
                                "(+ (answer-count) 1)"}]})]))
        (doseq [[ordinal value] [[0 "#'my.agents.undisposed-agent/answer-count"]
                                 [1 "2"]]]
          (test-support/transacted!
                       connection
                       (turn/receipt-settle-tx
                        {:seon.turn/id run-id
                         :seon.cluster.eval/ordinal ordinal
                         :seon.eval/shown value})))
        ;; `plan-tx` already minted ordinal 2; it stays unsettled, which is
        ;; what this test is about.
        (let [prepared
              (terminal-data
               {:seon.turn.loop/cluster
                (test-support/cluster-handle
                 ;; The world's own cluster: a second cluster entity would make
                 ;; the world name no single cluster for its readers.
                 {:seon.env/environment (test-support/environment (:seon.cluster/name (test-support/execution-handle connection)) connection)
                  :seon.db/connection connection
                  :seon.cluster/name (:seon.cluster/name (test-support/execution-handle connection))
                  :seon.db.process/id process
                  :seon.sci.eval/ctx (test-support/fork-cluster-ctx connection)})
                :seon.turn.loop/now now
                :seon.agent/id agent-id
                :seon.turn/id run-id
                :seon.db.process/id process
                :seon.cluster.eval/ordinal 2
                :seon.sci.eval/evaluation
                {:seon.eval/shown "3"
                 :seon.sci.admit/value 3}
                :seon.message/trigger message-id})]
          (test-support/transacted! connection (:seon.db/tx-data prepared)))
        (let [terminal
              (eval.drive/terminal-state @connection agent-id process
                                         message-id 6)
              rendered
              (transcript/render-ai
               {:seon.db/db @connection
                :seon.sci.eval/ctx (sci.eval/cluster-ctx @connection)
                :seon.sci.eval/time-limit-ms 1000
                :seon.sci.admit/caps
                (assoc
                 (config/result-caps (test-support/effective-config))
                 :seon.config.eval.result/max-depth 12
                 :seon.config.eval.result/max-collection 64
                 :seon.config.eval.result/max-string 4096
                 :seon.config.eval.result/max-source 1048576
                 :seon.config.eval.result/max-nodes 4096)
                :seon.agent/id agent-id})]
          (is (inst?
               (db/q '[:find ?at .
                       :in $ ?run-id
                       :where
                       [?run :seon.turn/id ?run-id]
                       [?run :seon.turn/closed-tx ?tx]
                       [?tx :db/txInstant ?at]]
                     @connection run-id))
              "the last clean receipt and undisposed close commit together")
          (is (= {:seon.eval.drive/outcome :undisposed
                  :seon.eval.drive/run-ids [run-id]}
                 terminal)
              ;; nil means not idle or not undisposed: name the work that
              ;; keeps the agent busy, so the red says which one.
              "the episode verdict names the missing disposition; next work")
          ;; The undisposed-turn notice is dissolved (decision 8b of
          ;; docs/prds/steward-platform/plan/owner-decisions-2026-09-17.md,
          ;; ruled by T2 of program-facts-are-the-runtime-prd-2026-09-17.md).
          ;; Nothing assembles the history but the walk: the missing
          ;; disposition is the terminal verdict above and a fact the agent
          ;; can query, never a third prompt grammar in the transcript.
          (is (str/includes? rendered "(+ (answer-count) 1)")
              "the following history carries the turn's own evaluations")
          (is (not (str/includes? rendered
                                  "ended without my.turn/complete or my.turn/wait"))
              "the dissolved notice is not re-introduced"))))))

;;; THE CLASS-KILLER: what boot installs must cover what the loop writes
;;;
;;; The live drive died in its first second on `Bad entity attribute
;;; :seon.message/to`. Every suite was green, because every
;;; fixture installs an EXPLICIT attribute list and so bypasses the rule
;;; the boot path actually uses: `canonical-database-attributes`
;;; installs entity-map entries by construction and standalone forms
;;; only when they carry a persistence property. Four families had no
;;; entity map and therefore installed exactly one attribute each.
;;;
;;; These two tests are the recurring surface for that class. The subset
;;; assertion is cheap and states the invariant; the transact-against-a
;;; -boot-built-database test is the one with teeth, because it uses the
;;; SAME derivation boot uses and then writes the entities the turn writes.
;;; ---------------------------------------------------------------------------

(deftest everything-the-loop-writes-is-installable-by-boot
  (let [installable (set (schema/canonical-database-attributes (seon.schema/handed-projection)))]
    (testing "every attribute the loop commits"
      (is (empty? (remove installable (turn/committed-attributes (seon.schema/handed-projection))))
          "an attribute the loop writes that boot cannot install is a
           run that dies on its first transaction"))
    (testing "and every attribute the wake listens for"
      (test-support/with-database
        (fn [connection]
          (is (empty? (remove installable
                              (wake/wake-attributes (db/db connection))))
              "a wake attribute boot cannot install can never be committed,
               so the loop would never wake at all"))))))

;;; ---------------------------------------------------------------------------
;;; The crash walk, as kill positions over facts
;;; ---------------------------------------------------------------------------

(defn- with-database [body]
  (test-support/with-database
   (fn [connection]
      (config/apply! {:seon.db/connection connection
                      :seon.boot/cluster-name "loop-test"})
      (test-support/transacted! connection
                              (into (test-support/agent-tx @connection "agent-a")
                                [(test-support/namespace-entity @connection 'user)
                               
                                 {:seon.message/id "m-1" :seon.message/to [:seon.agent/id "agent-a"] :seon.message/content "go"}]))
      (body connection))))

(defn- commit-run! [connection {:keys [planned? receipts closed? completed?]}]
  ;; OPEN THROUGH THE WRITER. Since ae0e54841 the agent's open turn is read
  ;; through its runtime component (seon.turn/open-for-agent), and only
  ;; `open-call` writes that edge — an authored {:seon.turn/id …} entity leaves
  ;; `agent-run` nil, so every entity of this table derived `:open`.
  (test-support/transacted!
   connection
   (turn/open-tx {:seon.turn/id "run-1"
                  :seon.turn/agent [:seon.agent/id "agent-a"]
                  :seon.turn/trigger [:seon.message/id "m-1"]
                  :seon.turn/opened-tx "datomic.tx"}))
  (test-support/transacted!
               connection
               {:tx-data
                (cond-> []
                  planned?
                  (conj [:db/add [:seon.turn/id "run-1"]
                         :seon.turn/reply-size 64])
                  closed?
                  (conj [:db/add [:seon.turn/id "run-1"]
                         :seon.turn/closed-tx "datomic.tx"])
                  completed?
                  (conj [:db/add [:seon.turn/id "run-1"]
                         :seon.turn/disposition :completed])
                  closed?
                  (conj {:seon.ai.attempt/id "closed-attempt"
                         :seon.turn/_attempts [:seon.turn/id "run-1"]
                         :seon.ai.attempt/ordinal 0
                         :seon.ai.attempt/at now
                         :seon.ai/endpoint "https://fixture.invalid/v1/chat"
                         :seon.ai/model "fixture-model"
                         :seon.ai.attempt/settings-edn "{}"})

                  ;; ONE ENTITY PER (run, ordinal), under the ONE identity derivation the
                  ;; writer uses: the freeze asserts the source and the start instant, and
                  ;; the terminal facts accrete onto that same entity.
                  planned?
                  (into (map (fn [ordinal]
                               {:seon.cluster.eval/id (turn/receipt-identity "run-1" ordinal)
                                :seon.cluster.eval/run [:seon.turn/id "run-1"]
                                :seon.cluster.eval/ordinal ordinal
                                :seon.cluster.eval/at now
                                :seon.cluster.eval/source "(+ 1 1)"
                                :seon.cluster.eval/ns [:seon.ns/name 'user]})
                             (range 2)))

                  (seq receipts)
                  ;; the evaluation's state is WHICH terminal fact it carries: :done →
                  ;; result-edn, :interrupted → interrupted-at, none → running
                  (into (map (fn [[ordinal state]]
                               (cond-> {:seon.cluster.eval/id
                                        (turn/receipt-identity "run-1" ordinal)
                                        :seon.cluster.eval/run
                                        [:seon.turn/id "run-1"]
                                        :seon.cluster.eval/ordinal ordinal
                                        :seon.cluster.eval/at now}
                                 (= :done state)
                                 (assoc :seon.eval/shown "2")
                                 (= :interrupted state)
                                 (assoc :seon.cluster.eval/interrupted-at now)))
                             receipts)))}))

;;; ---------------------------------------------------------------------------
;;; A staged blob settles, and a failed commit still closes the run
;;; ---------------------------------------------------------------------------

;; THE CLASS, in two halves that must never be separated.
;;
;; The terminal commit used to branch on `(seq staged-writes)` and hand
;; `seon.blob/with-publication!` a `ChunkedSeq` where the declared input is
;; `[:vector :seon.blob/staged-write]`. Under the contracts every cluster
;; arms, EVERY turn that staged a blob violated that contract — and an
;; agent's own `def` over the blob threshold is all it takes. The commit is
;; total over an empty vector, so the branch had nothing to decide.
;;
;; The half that made one bad shape fatal: the violation ESCAPED the
;; settlement, so no arm closed the run, and the agent was refused
;; `:seon.turn/agent-already-running` until the JVM was restarted. A
;; failure to record a fault may never leave a run open, so the commit runs
;; under `phase` and a host failure lands in the refusal arm like any other.
(defn- staged-def-evaluation
  "A REAL evaluation of a `def` whose value is over the blob threshold.

  Hand-building the evaluation map is how a fixture ends up asserting a
  shape production never produces; this crosses `seon.sci.eval/evaluate`,
  the one constructor of the value `settle!` declares."
  [connection ctx size]
  (let [decisions config/defaults]
    (test-support/evaluate
     {:seon.cluster.eval/source
      (str "(def probe-staged-def (apply str (repeat " size " \"m\")))")
      :seon.sci.eval/ctx ctx
      :seon.cluster.eval/ns [:seon.ns/name 'user]
      :seon.sci.admit/caps (config/result-caps decisions)
      :seon.sci.eval/time-limit-ms
      (:seon.config.eval/time-limit-ms decisions)
      :seon.db/connection connection})))

(defn- closed-at
  [connection]
  (test-support/turn-closed-at @connection "run-1"))

(defn- commit-agent-receipt!
  "The turn's own last form: an AGENT-AUTHORED receipt at ordinal 0.

  `undisposed?` — the derivation that closes a run whose last form settled
  no disposition — reads authorship and last ordinal from facts, so a
  fixture that mints an unauthored receipt is asserting a different run."
  [connection]
  (test-support/transacted!
               connection
               [{:seon.cluster.eval/id (turn/receipt-identity "run-1" 0)
                 :seon.cluster.eval/run [:seon.turn/id "run-1"]
                 :seon.cluster.eval/ordinal 0
                 :seon.cluster.eval/at now
                 :seon.cluster.eval/author :agent
                 :seon.cluster.eval/source "(def probe-staged-def …)"
                 :seon.cluster.eval/ns [:seon.ns/name 'user]}]))

(defn- settle-staged-def!
  [connection cluster-name]
  (let [ctx (test-support/fork-cluster-ctx connection cluster-name)]
    (turn/settle!
     {:seon.turn.loop/cluster
      (test-support/cluster-handle
       {:seon.db/connection connection
        :seon.cluster/name cluster-name
        :seon.sci.eval/ctx ctx
        :seon.db.process/id process})
      :seon.turn.loop/now now
      :seon.agent/id "agent-a"
      :seon.turn/id "run-1"
      :seon.cluster.eval/ordinal 0
      :seon.sci.eval/evaluation
      (staged-def-evaluation connection ctx 10000)})))

(deftest a-private-def-settles-without-staging-a-blob-and-closes
  (with-database
    (fn [connection]
      (config/apply! {:seon.db/connection connection
                      :seon.boot/cluster-name "loop-blob"})
      (commit-run! connection {})
      (commit-agent-receipt! connection)
      (let [handed (volatile! ::never-called)
            publish (var-get #'blob/with-publication!)
            terminal
            (with-redefs [blob/with-publication!
                          (fn [conn staged-writes commit-roots!]
                            (vreset! handed staged-writes)
                            (publish conn staged-writes commit-roots!))]
              (settle-staged-def! connection "loop-blob"))
]
        (is (vector? @handed)
            "the staged writes cross the blob seam as the declared vector,
             not as the seq a `(seq …)` branch produced")
        (is (empty? @handed)
            "private values never stage blobs")
        (is (some? (:db-after (:seon.turn.loop/outcome terminal)))
            "the settlement committed")
        (is (inst? (closed-at connection))
            "the run closed")))))

(deftest a-refused-terminal-commit-still-closes-the-run
  (with-database
    (fn [connection]
      (config/apply! {:seon.db/connection connection
                      :seon.boot/cluster-name "loop-blob-refused"})
      (commit-run! connection {})
      (commit-agent-receipt! connection)
      (let [attempts (volatile! 0)
            publish (var-get #'blob/with-publication!)
            terminal
            (with-redefs [blob/with-publication!
                          (fn [conn staged-writes commit-roots!]
                            (vswap! attempts inc)
                            (if (= 1 @attempts)
                              (throw (ex-info "the blob store went away"
                                              {:seon.test/commit-broke true}))
                              (publish conn staged-writes commit-roots!)))]
              (settle-staged-def! connection "loop-blob-refused"))]
        (is (true? (get-in terminal [:seon.error/value :seon.turn.loop/phase-failed]))
            "a host failure in the commit is a refused phase, not an escape")
        (is (inst? (closed-at connection))
            "AND THE RUN IS CLOSED: a settlement that cannot commit may not
             leave the agent holding a run no later turn can take")))))

;;; ---------------------------------------------------------------------------
;;; A refused BATCH settlement closes the turn, and the agent turns again
;;; ---------------------------------------------------------------------------

;; THE CLASS the reviews name (astra B4): one settlement per identity does
;; not imply one execution. After the forms have run, a refused terminal
;; transaction leaves no terminal fact — and re-entering the evaluate arm
;; would execute the same side effects again before the identity refused the
;; second settlement. The refusal arm is what stops that: it settles every
;; begun ordinal with the failure and CLOSES the turn in the same
;; transaction, so there is no open turn to re-enter and the agent's next
;; wake opens a new one.
;;
;; The single-form path is proven above; this is the BATCH path, which the
;; independent verification could not prove and which is the one the
;; ordinary fold takes.

(defn- commit-agent-receipts!
  "Two agent-authored evaluations, the shape a stored reply leaves."
  [connection]
  (test-support/transacted!
               connection
               (mapv (fn [ordinal]
                       {:seon.cluster.eval/id (turn/receipt-identity "run-1" ordinal)
                        :seon.cluster.eval/run [:seon.turn/id "run-1"]
                        :seon.cluster.eval/ordinal ordinal
                        :seon.cluster.eval/at now
                        :seon.cluster.eval/author :agent
                        :seon.cluster.eval/source (str "(+ " ordinal " 1)")
                        :seon.cluster.eval/ns [:seon.ns/name 'user]})
                     [0 1])))

(deftest a-refused-batch-settlement-closes-the-turn-and-the-agent-turns-again
  (doseq [failure-mode [:throw :returned-refusal]]
   (with-database
    (fn [connection]
      (config/apply! {:seon.db/connection connection
                      :seon.boot/cluster-name "loop-batch-refused"})
      (commit-run! connection {})
      (commit-agent-receipts! connection)
      (let [ctx (test-support/fork-cluster-ctx connection
                                               "loop-batch-refused")
            cluster (test-support/cluster-handle
                     {:seon.db/connection connection
                      :seon.cluster/name "loop-batch-refused"
                      :seon.sci.eval/ctx ctx
                      :seon.db.process/id process})
            decisions config/defaults
            evaluate
            (fn [ordinal]
              (test-support/evaluate
               {:seon.cluster.eval/source (str "(+ " ordinal " 1)")
                :seon.sci.eval/ctx ctx
                :seon.cluster.eval/ns [:seon.ns/name 'user]
                :seon.sci.admit/caps (config/result-caps decisions)
                :seon.sci.eval/time-limit-ms
                (:seon.config.eval/time-limit-ms decisions)
                :seon.db/connection connection}))
            requests
            (mapv (fn [ordinal]
                    {:seon.turn.loop/cluster cluster
                     :seon.turn.loop/now now
                     :seon.agent/id "agent-a"
                     :seon.turn/id "run-1"
                     :seon.cluster.eval/ordinal ordinal
                     :seon.sci.eval/evaluation (evaluate ordinal)})
                  [0 1])
            attempts (volatile! 0)
            publish (var-get #'blob/with-publication!)
            settled
            (with-redefs [blob/with-publication!
                          (fn [conn staged-writes commit-roots!]
                            (vswap! attempts inc)
                            (if (= 1 @attempts)
                              (if (= :throw failure-mode)
                                (throw (ex-info "the batch commit went away"
                                                {:seon.test/commit-broke true}))
                                (let [refusal (db/transact! conn
                                                {:tx-data [{:db/id [:seon.agent/id "agent-a"]
                                                            :seon.agent/id 42}]})]
                                  (is (inst? (:seon.error/at refusal)))
                                  ;; Keep the real writer's diagnostic.
                                  refusal))
                              (publish conn staged-writes commit-roots!)))]
              ((private-loop-fn 'settle-batch!) cluster requests))
            database @connection
            evaluations
            (db/q '[:find ?ordinal ?error
                    :in $ ?run-id
                    :where
                    [?run :seon.turn/id ?run-id]
                    [?evaluation :seon.cluster.eval/run ?run]
                    [?evaluation :seon.cluster.eval/ordinal ?ordinal]
                    [?evaluation :seon.cluster.eval/error ?error]]
                  database "run-1")]
        (if (= :throw failure-mode)
          (is (true? (:seon.turn.loop/phase-failed (:refused-outcome settled)))
              "A host failure in the batch commit is a refused phase.")
          (do
            (is (string? (:seon.db.write.attempt/request-id (:refused-outcome settled))))
            (is (inst? (:seon.error/at (:refused-outcome settled))))
            (is (string? (:seon.db.write.attempt/request-id (:refused-outcome settled)))
                "The writer's original refusal is retained, including its request identity.")))
        (is (= #{0 1} (into #{} (map first) evaluations))
            "every begun ordinal settled, so no form can execute twice")
        (is (inst? (closed-at connection))
            "and the turn closed in the refusal path itself")
        (is (nil? (turn/open-for-agent database [:seon.agent/id "agent-a"]))
            "the agent holds no wreckage")
        (test-support/transacted! connection
                                  [{:seon.message/id "m-after-refusal" :seon.message/to [:seon.agent/id "agent-a"] :seon.message/content "again"}])
        (is (= :open
               (:seon.turn.work/situation
                (turn/next-agent-work
                 @connection
                 {:seon.agent/id "agent-a"
                  :seon.db.process/id process})))
            "AND THE AGENT TAKES ITS NEXT TURN"))))))
