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
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [my.run :as my.run]
            [seon.ai :as ai]
            [seon.config :as config]
            [seon.cluster.agent :as cluster.agent]
            [seon.cluster.loop :as cluster.loop]
            [seon.cluster.run :as run]
            [seon.cluster.wake :as wake]
            [seon.cluster.work :as work]
            [seon.fn.analyzer :as fn.analyzer]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as test-support])
  (:import [java.util Date]))

;;; ---------------------------------------------------------------------------
;;; The pure parts
;;; ---------------------------------------------------------------------------

(def ^:private now (Date. 1700000000000))
(def ^:private process "process/one")

(defn- private-loop-fn
  [function-name]
  (deref (ns-resolve 'seon.cluster.loop function-name)))

(deftest attempt-evidence-prefers-completion-and-falls-back-to-error-data
  (let [project (private-loop-fn 'attempt-evidence)]
    (is (= {:seon.ai/usage {:source :completion}
            :seon.ai/reasoning-content "fallback reasoning"
            :seon.ai/finish-reason "stop"}
           (project
            {:seon.ai/completion
             {:seon.ai/usage {:source :completion}
              :seon.ai/finish-reason "stop"
              :seon.error/data
              {:seon.ai/usage {:source :error}
               :seon.ai/reasoning-content "fallback reasoning"
               :seon.ai/finish-reason "length"}}})))))

(deftest attempt-request-assembles-evidence-and-optional-provenance
  (let [failure {:seon.error/kind :provider/refused
                 :seon.error/message "refused"}
        evidence {:seon.ai/usage {"prompt_tokens" 3}
                  :seon.ai/reasoning-content "reasoning"
                  :seon.ai/finish-reason "length"}]
    (is (= {:seon.ai/target {:seon.ai/endpoint "https://provider.invalid"
                             :seon.ai/model "model"}
            :seon.ai/settings {:seon.config.ai/thinking :high}
            :seon.cluster.run/id "run-1"
            :seon.cluster.agent/id "agent-1"
            :seon.ai.attempt/ordinal 2
            :seon.ai/usage {"prompt_tokens" 3}
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
                  ai/targets (fn [actual-settings]
                               (swap! calls conj [:targets actual-settings])
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
              [:targets settings]
              [:strategy settings]]
             @calls)
          "a configured backup makes the schedule empty without deriving delays"))))

(deftest admitted-form-preserves-current-namespace-and-lints-one-source
  (let [db {:immutable :database-value}
        ctx {:live :context}
        form {:seon.cluster.run.form/source "(+ 1 2)"
              :seon.cluster.run.form/ns [:seon.ns/name 'parse.namespace]}
        namespace-row {:seon.ns/name 'current.namespace
                       :seon.ns/requires [{:seon.ns/name 'clojure.set}]}
        available [{:seon.fn/sym 'clojure.core/+}]
        calls (atom [])]
    (with-redefs-fn
      {(ns-resolve 'seon.cluster.loop 'form-data)
       (fn [actual-db run-id ordinal]
         (swap! calls conj [:form actual-db run-id ordinal])
         form)
       #'d/pull
       (fn [actual-db pattern lookup-ref]
         (swap! calls conj [:pull actual-db pattern lookup-ref])
         namespace-row)
       (ns-resolve 'seon.cluster.loop 'available-functions)
       (fn [actual-db actual-ctx]
         (swap! calls conj [:available actual-db actual-ctx])
         available)
       #'cluster.loop/lint-form
       (fn [request]
         (swap! calls conj [:lint request])
         (assoc (::cluster.loop/source request)
                :seon.cluster.run.form/source "(inc 2)"))}
      (fn []
        (is (= {:seon.cluster.run.form/source "(inc 2)"
                :seon.cluster.run.form/ns
                [:seon.ns/name 'current.namespace]}
               ((private-loop-fn 'admitted-form)
                {:seon.db/db db
                 :seon.cluster.run/id "run-1"
                 :seon.cluster.run.form/ordinal 3
                 :seon.sci.eval/ctx ctx
                 :seon.cluster.loop/current-namespace 'current.namespace
                 :seon.cluster.loop/fallback-namespace 'fallback.namespace})))
        (is (= [:form db "run-1" 3] (first @calls)))
        (is (= [:seon.ns/name 'current.namespace]
               (last (nth @calls 1)))
            "the per-form namespace pull uses the current evaluation namespace")
        (is (= [:available db ctx] (nth @calls 2)))
        (is (= 'current.namespace
               (get-in (last @calls) [1 :seon.ns/name])))))))

(deftest evaluation-request-projects-the-admitted-form-and-cluster-controls
  (let [ctx {:live :context}
        form {:seon.cluster.run.form/source "(inc 2)"
              :seon.cluster.run.form/ns [:seon.ns/name 'old.namespace]}
        caps {:seon.config.eval.result/max-depth 4}
        cluster {:seon.sci.admit/caps caps
                 :seon.config.eval/time-limit-ms 500
                 :seon.config/on-core-error :panic}]
    (is (= {:seon.cluster.run.form/source "(inc 2)"
            :seon.cluster.run.form/ns [:seon.ns/name 'current.namespace]
            :seon.sci.admit/caps caps
            :seon.sci.eval/ctx ctx
            :seon.cluster.agent/id "agent-1"
            :seon.sci.eval/time-limit-ms 500
            :seon.config/on-core-error :panic}
           ((private-loop-fn 'evaluation-request)
            {:seon.cluster.loop/admitted-form form
             :seon.cluster.loop/evaluation-namespace 'current.namespace
             :seon.cluster.loop/cluster cluster
             :seon.sci.eval/ctx ctx
             :seon.cluster.agent/id "agent-1"})))))

(deftest receipt-request-projects-one-schema-valid-terminal-request
  (let [result-blob (apply str (repeat 64 "a"))
        completed (my.run/complete "done")
        program-row {:seon.schema/key :my.agent/registered
                     :seon.schema/form ":string"}
        request
        ((private-loop-fn 'receipt-request)
         {:seon.cluster.run/id "run-1"
          :seon.cluster.run/process process
          :seon.cluster.run.form/ordinal 2
          :seon.sci.eval/evaluation
          {:seon.sci.admit/value
           {:seon.error/kind :evaluation/kind}
           :seon.cluster.eval/error "evaluation error"
           :seon.cluster.eval/interrupted-at now
           :seon.cluster.eval/output "printed\n"
           :seon.cluster.eval/ns [:seon.ns/name 'my.agent]
           :seon.sci.eval/program-row program-row}
          :seon.cluster.loop/settlement-evaluation
          {:seon.cluster.eval/result-edn "{:result :projected}"
           :seon.cluster.eval/result-blob result-blob
           :seon.cluster.eval/result-size 100}
          :seon.problems/form-problem
          {:seon.cluster.eval/error "problem error"
           :seon.error/kind :problem/kind}
          :my.run/value completed})]
    (is (= {:seon.cluster.run/id "run-1"
            :seon.cluster.run/process process
            :seon.cluster.run.form/ordinal 2
            :seon.cluster.eval/result-edn "{:result :projected}"
            :seon.cluster.eval/result-blob result-blob
            :seon.cluster.eval/result-size 100
            :seon.cluster.eval/error "evaluation error"
            :seon.cluster.eval/interrupted-at now
            :seon.error/kind :evaluation/kind
            :seon.cluster.eval/output "printed\n"
            :seon.cluster.eval/ns [:seon.ns/name 'my.agent]
            :seon.sci.eval/program-row program-row
            :my.run/value completed}
           request))
    (is (schema/valid-candidate-value?
         :seon.cluster.loop/terminal-request request))))

(deftest two-agents-resolve-one-config-row-with-ordinary-inheritance
  (test-support/with-database
    (fn [connection]
      (let [cluster-name "settings-resolution"]
        (config/apply! {:seon.config/connection connection
                        :seon.boot/cluster-name cluster-name})
        (test-support/seed-cluster! connection cluster-name)
        (d/transact
         connection
         (into (cluster.agent/creation-tx
                {:seon.cluster.agent/id "planner"
                 :seon.ns/name 'my.agents.planner
                 :seon.cluster/name cluster-name})
               (cluster.agent/creation-tx
                {:seon.cluster.agent/id "worker"
                 :seon.ns/name 'my.agents.worker
                 :seon.cluster/name cluster-name})))
        (d/transact connection
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
  (d/transact connection
              [{:seon.cluster.message/id message-id
                :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
                :seon.cluster.message/content "prove live settings"
                :seon.cluster.message/at now}])
  (d/transact
   connection
   {:tx-data [{:seon.cluster.run/id run-id
               :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
               :seon.cluster.run/opened-at now
               :seon.cluster.run/process process}
              {:seon.cluster.agent/id agent-id
               :seon.cluster.agent/run [:seon.cluster.run/id run-id]}]
    :tx-meta {:seon.db/trigger
              [:seon.cluster.message/id message-id]}}))

(defn- call-work
  [agent-id run-id]
  {:seon.cluster.work/situation :call
   :seon.cluster.agent/id agent-id
   :seon.cluster.run/id run-id})

(defn- settings-attempts
  [db]
  (->> (d/q '[:find ?run-id ?ordinal ?attempt
              :where
              [?attempt :seon.ai.attempt/run ?run]
              [?run :seon.cluster.run/id ?run-id]
              [?attempt :seon.ai.attempt/ordinal ?ordinal]]
            db)
       (map (fn [[run-id ordinal attempt]]
              (assoc (d/pull db '[*] attempt)
                     ::attempt-run-id run-id
                     ::attempt-ordinal ordinal)))
       (sort-by (juxt ::attempt-run-id ::attempt-ordinal))
       vec))

(deftest call-resolves-once-records-settings-and-sees-next-turn-config
  (test-support/with-database
    (fn [connection]
      (let [cluster-name "live-settings"
            agent-id "planner"
            settings-fn ai/settings
            overlay-fn ai/agent-overlay
            resolutions (atom [])
            overlays (atom [])
            requests (atom [])
            cluster {:seon.store/branch-connection connection
                     :seon.cluster/name cluster-name
                     :seon.cluster.run/process process
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
         {:seon.config/connection connection
          :seon.boot/cluster-name cluster-name
          :seon.config/manifest
          {:seon.config.ai/model "before-apply"
           :seon.config.ai.backup/model "backup-before-apply"}})
        (test-support/seed-cluster! connection cluster-name)
        (d/transact
         connection
         (cluster.agent/creation-tx
          {:seon.cluster.agent/id agent-id
           :seon.ns/name 'my.agents.live-settings
           :seon.cluster/name cluster-name}))
        (d/transact connection
                    [{:seon.cluster.agent/id agent-id
                      :seon.config.ai/thinking :high}])
        (prepare-call! connection agent-id "settings-run-1" "settings-message-1")
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
          (testing "failover reuses the turn's one resolution"
            (is (= 1 (count @overlays)))
            (is (= 1 (count @resolutions)))
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

          (d/transact connection
                      (run/close-tx
                       {:seon.cluster.run/id "settings-run-1"
                        :seon.cluster.run/process process
                        :seon.cluster.run/closed-at now}))
          (config/apply!
           {:seon.config/connection connection
            :seon.boot/cluster-name cluster-name
            :seon.config/manifest
            {:seon.config.ai/model "after-apply"}})
          (prepare-call! connection agent-id "settings-run-2" "settings-message-2")
          (cluster.loop/turn
           {:seon.cluster.loop/cluster cluster
            :seon.cluster.work/next
            (call-work agent-id "settings-run-2")}
           now)
          (testing "the same loop handle sees config apply on the next turn"
            (is (= 2 (count @overlays)))
            (is (= 2 (count @resolutions)))
            (is (= "after-apply" (:seon.ai/model (last @requests))))
            (is (= :high (:seon.ai/thinking (last @requests))))
            (let [last-row (last (settings-attempts @connection))]
              (is (= "after-apply" (:seon.ai/model last-row)))
              (is (= "after-apply"
                     (:seon.config.ai/model
                      (edn/read-string
                       (:seon.ai.attempt/settings-edn last-row))))))))))))

(defn- lint-plan
  [sources]
  (mapv
   (fn [source]
     (cluster.loop/lint-form
      {:seon.ns/name 'my.agents.lint-test
       :seon.cluster.loop/namespace-row
       {:seon.ns/name 'my.agents.lint-test}
       :seon.cluster.loop/source
       {:seon.cluster.run.form/source source
        :seon.ns/name 'my.agents.lint-test}}))
   sources))

(deftest lint-rejection-is-per-form-data-at-execution
  (let [originals (mapv #(str "(+ " % " " % ")") (range 10))
        originals (assoc originals 4 "(missing 4)")
        admitted (lint-plan originals)
        admitted-sources (mapv :seon.cluster.run.form/source admitted)
        rejected-value (second (read-string (nth admitted-sources 4)))]
    (testing "nine independent forms retain their exact bytes and execute"
      (is (= (vec (concat (subvec originals 0 4)
                          (subvec originals 5)))
             (vec (concat (subvec admitted-sources 0 4)
                          (subvec admitted-sources 5)))))
      (is (= [0 2 4 6 10 12 14 16 18]
             (mapv (comp eval read-string)
                   (concat (subvec admitted-sources 0 4)
                           (subvec admitted-sources 5))))))
    (testing "the rejected ordinal is a literal flat error, not executable source"
      (is (= ::cluster.loop/lint-rejected (:seon.error/kind rejected-value)))
      (is (= "(missing 4)"
             (get-in rejected-value
                     [:seon.error/data :seon.cluster.run.form/source])))
      (is (= :unresolved-symbol
             (get-in rejected-value
                     [:seon.error/data ::fn.analyzer/findings 0
                      ::fn.analyzer/type])))
      (let [evaluation
            (sci.eval/evaluate
             {:seon.cluster.run.form/source (nth admitted-sources 4)
              :seon.sci.admit/caps
              (config/result-caps (config/defaults))
              :seon.sci.eval/time-limit-ms 2000
              :seon.config/on-core-error :panic})]
        (is (= rejected-value (:seon.sci.admit/value evaluation)))
        (is (string? (:seon.cluster.eval/result-edn evaluation))))))
  (testing "warnings never reject"
    (let [warning-source "(let [unused 1] 2)"]
      (is (= warning-source
             (:seon.cluster.run.form/source
              (first (lint-plan [warning-source])))))))
  (testing "a dependent form is separately rejected when kondo flags it"
    (let [admitted (lint-plan ["(defn broken [] (missing))"
                               "(broken 1)"])]
      (is (every?
           #(= ::cluster.loop/lint-rejected
               (:seon.error/kind
                (second
                 (read-string (:seon.cluster.run.form/source %)))))
           admitted)))))

(deftest linting-a-new-agent-namespace-uses-the-database-program-graph
  (let [source "(my.run/complete \"done\")"
        admitted
        (cluster.loop/lint-form
         {:seon.ns/name 'my.agents.new-agent
          :seon.cluster.loop/available-functions
          [{:seon.fn/sym "my.run/complete" :seon.fn/private? false}]
          :seon.cluster.loop/source
          {:seon.cluster.run.form/source source
           :seon.ns/name 'my.agents.new-agent}})]
    (is (= source (:seon.cluster.run.form/source admitted))
        "an absent namespace row is valid for a newly created agent")))

(deftest linting-projects-required-namespace-refs-to-analyzer-symbols
  (let [source "(authored.target/increment 41)"
        admitted
        (cluster.loop/lint-form
         {:seon.ns/name 'authored.consumer
          :seon.cluster.loop/namespace-row
          {:seon.ns/name 'authored.consumer
           :seon.ns/requires #{{:seon.ns/name 'authored.target}}}
          :seon.cluster.loop/available-functions
          [{:seon.fn/sym "authored.target/increment"
            :seon.fn/private? false
            :seon.fn/arglists "([x])"}]
          :seon.cluster.loop/source
          {:seon.cluster.run.form/source source
           :seon.ns/name 'authored.consumer}})]
    (is (= source (:seon.cluster.run.form/source admitted))
        "a nested ref pull supplies the required namespace name to lint")))

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

(deftest the-terminal-transaction-is-one-transaction
  (let [base {:seon.cluster.run/id "run-1"
              :seon.cluster.run/process "process/one"
              :seon.cluster.run.form/ordinal 0
              :seon.cluster.eval/result-edn "[1 :seon.sci.admit/elided]"
              :seon.cluster.eval/result-blob (apply str (repeat 64 "a"))
              :seon.cluster.eval/result-size 1000}
        without (cluster.loop/terminal-tx base now)
        with (cluster.loop/terminal-tx
              (assoc base :my.run/value (my.run/complete "all done"))
              now)]
    (is (vector? without))
    (is (seq without) "a receipt is always written")
    (is (= (select-keys base
                        [:seon.cluster.eval/result-edn
                         :seon.cluster.eval/result-blob
                         :seon.cluster.eval/result-size])
           (select-keys (nth (first without) 2)
                        [:seon.cluster.eval/result-edn
                         :seon.cluster.eval/result-blob
                         :seon.cluster.eval/result-size]))
        "the closed terminal request carries both primitive blob facts")
    (testing "the disposition rides in the SAME tx-data, never a second one"
      (is (> (count with) (count without))
          "the completion's facts are in this vector, not a later commit")
      (is (some #(and (sequential? %)
                      (= :db.fn/call (first %)))
                with)
          "and it goes through a transition, so the run's own fence
           applies to the close as much as to the claim")
      (is (= [now]
             (keep (comp :seon.cluster.run/closed-at last)
                   with))
          "the close receives the exact pass instant, not a second clock"))))

;;; ---------------------------------------------------------------------------
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
      (d/transact connection
                  (schema.datahike/malli->datahike-schema
                   (schema/canonical-database-attributes)))
      (testing "the trigger — the exact transact the live drive failed on"
        (is (map? (d/transact connection
                              [{:seon.cluster.agent/id "alice"}
                               {:seon.cluster.message/id "m-live"
                                :seon.cluster.message/to
                                [:seon.cluster.agent/id "alice"]
                                :seon.cluster.message/content "count the widgets"
                                :seon.cluster.message/at now}]))))
      (testing "the run, its agent pointer, and the trigger as tx-meta"
        (is (map? (d/transact
                   connection
                   {:tx-data [{:seon.cluster.run/id "run-live"
                               :seon.cluster.run/agent
                               [:seon.cluster.agent/id "alice"]
                               :seon.cluster.run/opened-at now
                               :seon.cluster.run/process "process/one"
                               :seon.cluster.run/plan-digest
                               (apply str (repeat 64 "a"))}
                              {:seon.cluster.agent/id "alice"
                               :seon.cluster.agent/run
                               [:seon.cluster.run/id "run-live"]}]
                    :tx-meta {:seon.db/trigger
                              [:seon.cluster.message/id "m-live"]}}))))
      (testing "one frozen form"
        (is (map? (d/transact connection
                              [{:seon.cluster.run.form/id "f-0"
                                :seon.cluster.run.form/run
                                [:seon.cluster.run/id "run-live"]
                                :seon.cluster.run.form/ordinal 0
                                :seon.cluster.run.form/source "(+ 1 1)"}]))))
      (testing "a running receipt (no terminal fact) and its settlement"
        (is (map? (d/transact connection
                              [{:seon.cluster.eval/id "e-0"
                                :seon.cluster.eval/run
                                [:seon.cluster.run/id "run-live"]
                                :seon.cluster.eval/ordinal 0
                                :seon.cluster.eval/at now}])))
        (is (map? (d/transact connection
                              [{:seon.cluster.eval/id "e-0"
                                :seon.cluster.eval/result-edn "2"}]))))
      (testing "the model-attempt chain: a failed primary carrying its
      transport evidence, and the backup that points back at it"
        (is (map? (d/transact connection
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
        (is (map? (d/transact connection
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
        (is (map? (d/transact connection
                              [{:seon.cluster.eval/id "e-1"
                                :seon.cluster.eval/run
                                [:seon.cluster.run/id "run-live"]
                                :seon.cluster.eval/ordinal 1
                                :seon.cluster.eval/at now
                                :seon.cluster.eval/error "boom"
                                :seon.cluster.eval/result-edn "{:seon.error/kind :x}"}]))))
      (testing "the refs really are refs — a follow, not a string"
        (is (= "alice"
               (d/q '[:find ?id .
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
   :seon.cluster.run/id :seon.cluster.run/agent :seon.cluster.run/opened-at
   :seon.cluster.run/closed-at :seon.cluster.run/process
   :seon.cluster.run/plan-digest :seon.cluster.run/forms
   :seon.cluster.run.form/id :seon.cluster.run.form/run
   :seon.cluster.run.form/ordinal :seon.cluster.run.form/source
   :seon.cluster.eval/id :seon.cluster.eval/run :seon.cluster.eval/ordinal
   :seon.cluster.eval/at
   :seon.cluster.eval/interrupted-at :seon.cluster.eval/result-edn
   :seon.cluster.eval/error
   :seon.cluster.message/id :seon.cluster.message/to
   :seon.cluster.message/content :seon.cluster.message/at
   :seon.db/trigger])

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
      (d/transact connection (schema.datahike/malli->datahike-schema attributes))
      (d/transact connection
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
  (d/transact
   connection
   {:tx-data
    (cond-> [(cond-> {:seon.cluster.run/id "run-1"
                      :seon.cluster.run/agent [:seon.cluster.agent/id "agent-a"]
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
                 receipts)))
    :tx-meta {:seon.db/trigger [:seon.cluster.message/id "m-1"]}}))

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
        (let [derived (work/next-agent-work (d/db connection) request)]
          (testing (str "work derivation row " row)
            (is (= expected (:seon.cluster.work/situation derived)))))))))
