(ns seon.cluster.turn-test
  "Turn integration on canonical databases and real SCI contexts.

  Provider replies are supplied locally. Deliberate failure injection uses
  the production evaluation envelope; observations distinguish agent-authored
  evaluations from generated system reads."
  (:require [malli.core] [clojure.core.async :as async]
            [clojure.core.async.flow :as flow.core]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.api :as d]
            [seon.cluster.message :as my.message]
            [seon.run :as my.turn]
            [seon.ai :as ai]
            [seon.flow :as seon.flow]
            [seon.render.web :as web]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.turn :as turn]
            [seon.error :as error]
            [seon.cluster.message :as message]
            [seon.cluster.prompt :as prompt]
            [seon.cluster.reply :as reply]


            [seon.config :as config]
            [seon.db :as db]
            [seon.fn :as seon.fn]
            [seon.render.transcript :as transcript]
            [sci.core :as sci.core]
            [seon.sci.admit :as admit]
            [seon.sci.eval :as sci.eval]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]
            [seon.test-support :as test-support])
  (:import [java.util Date]))

(def ^:private test-environment
  ;; The subset environment (store layer only) every crossing this
  ;; namespace constructs names; boot's own constructor, fewer layers.
  (delay (test-support/environment "seon.cluster.turn-test")))

;;; NO explicit attribute list. The live boot path installs whatever
;;; `canonical-database-attributes` derives, and a fixture that installs
;;; its own list is exactly how the missing entity maps stayed invisible
;;; until a real drive hit them.

;;; the holder string production uses: <pid>-<start-millis>. A bare
;;; pid is recyclable, and a recycled pid claiming to hold a run is the
;;; one confusion recovery must not have.
(def ^:private process
  (cluster/process-identity {:seon.boot/pid 4242
                             :seon.boot/start-instant (Date. 1700000000000)}))
(def ^:private now (Date. 1700000000000))

;;; the injected evaluator: whatever the current fixture wants the form
;;; to evaluate to, without a sci context.
;;;
;;; DELIBERATELY NOT DYNAMIC, and the reason is load-bearing. The run
;;; loop submits every evaluation across `seon.flow/submit!!` onto a
;;; compute thread (`seon.turn/submit-evaluation!!`), and flow
;;; conveys NOTHING across that crossing but the submission's own data.
;;; A thread-local carrier therefore reaches the SUBMITTING thread only:
;;; the evaluator running the work reads this Var's root value, so the
;;; fixture's chosen evaluation is silently replaced by the default and
;;; every assertion downstream of it fails somewhere else. Tests inject
;;; with `with-redefs`, which alters the root and is therefore visible
;;; from the compute thread. Omitting `^:dynamic` makes `binding` on
;;; this Var a compile error, so the thread-local shape cannot be
;;; written back in.
(def injected-evaluation
  ;; presence is the state: a clean evaluation is result-edn with no
  ;; error and no interrupted-at — there is no status key
  {:seon.cluster.eval/result-edn "1"
   :seon.sci.admit/value 1})

(defn fake-evaluate
  "The stand-in evaluator, for the cases that pin an exact value."
  [_request]
  injected-evaluation)

(defn- semantic-result
  [result-edn]
  (#'admit/semantic-value (edn/read-string result-edn)))

(defn- agent-evaluations
  "Read agent-authored evaluations in turn and ordinal order, excluding system reads."
  {:malli/schema [:=> [:cat :seon.db/database-value] [:vector :map]]}
  [database]
  (let [rows (db/q '[:find ?turn-t ?ordinal (pull ?evaluation [* {:seon.cluster.eval/ns [:seon.ns/name]}])
                     :where
                     [?evaluation :seon.cluster.eval/author :agent]
                     [?evaluation :seon.cluster.eval/run ?turn]
                     [?turn :seon.turn/id _ ?turn-t]
                     [?evaluation :seon.cluster.eval/ordinal ?ordinal]]
                   database)]
    (when (or (:seon.db/invalid-read rows) (:seon.schema/expected-value rows))
      (throw (ex-info "The evaluation observation was refused." rows)))
    (mapv #(nth % 2) (sort-by #(subvec % 0 2) rows))))

;;; THE REAL EVALUATOR, injected through the same seam. The one thing it
;;; adds is the deadline: `turn` passes source + caps only, so the time
;;; limit — the ONE limit — has nowhere to come from at that call site.
;;; That is a seam defect in the loop's call, reported rather than
;;; papered over; this adapter supplies it so the injection can be
;;; proven against a real sci evaluation today.
;;; The seam needs no adapter any more: the cluster handle carries the
;;; deadline dial, the error disposition, and the cluster's ONE live ctx.
;;; This is the real evaluator, injected exactly as production injects it.

(defn- agent-row
  "One agent; prompt membership is derived by the namespace walk."
  [agent-id]
  {:seon.agent/id agent-id
   :seon.agent/namespace
   {:seon.ns/name (symbol (str "my.agents." agent-id))}})

(defn- with-render-context-proc
  [_connection cluster-handle body]
  (let [context-channel (async/chan)
        render-channel (async/chan (async/sliding-buffer 1))
        runtime-eval-channel (async/chan (async/sliding-buffer 1))
        pages-channel (async/chan (async/sliding-buffer 1))
        stream-channel (async/chan (async/sliding-buffer 1))
        completion (async/promise-chan)
        cluster-handle (assoc cluster-handle
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
              :seon.render.web/interest (atom :all)
              :seon.render.web/registration (atom {})
              :seon.render.web/latest-packages (atom {})
              :seon.render.web/completion completion
              :seon.render.web/root-agent-id "agent-a"
              :seon.turn.loop/cluster cluster-handle})}}
          :conns []
          :io-exec
          (cluster/projection-executor
           (:seon.sci.eval/projection-state
            (:seon.sci.eval/ctx cluster-handle)))})
        {:keys [report-chan error-chan]} (flow.core/start graph)]
    (async/go-loop [] (when (async/<! report-chan) (recur)))
    (async/go-loop [] (when (async/<! error-chan) (recur)))
    (try
      (flow.core/resume graph)
      (body cluster-handle)
      (finally
        (flow.core/stop graph)
        (async/<!! completion)))))

(defn- with-cluster
  "Drive `body` against one live cluster, optionally under a stand-in evaluator.

  THE EVALUATOR IS A VAR, NOT A CONFIG FACT. `seon.turn/evaluate-sources`
  calls `seon.sci.eval/evaluate` directly so the program graph carries the edge,
  so a test that wants to pin an exact evaluation replaces the Var's root value
  here. `with-redefs` is the right seam and a dynamic binding is not: a real
  graph evaluates on its proc's own virtual thread, which a thread binding
  never reaches."
  {:malli/schema [:function [:=> [:cat [:=> [:cat :map] :seon.schema/value]] :seon.schema/value] [:=> [:cat [:or :nil [:=> [:cat :map] :map]] [:=> [:cat :map] :seon.schema/value]] :seon.schema/value]]}
  ([body] (with-cluster nil body))
  ([evaluator body]
  (test-support/with-database
   (fn [connection]
    (let [launcher
          (seon.flow/start-work-launcher!
           {:seon.env/environment @test-environment
            ::seon.flow/configuration
            (assoc (select-keys (test-support/effective-config)
                                seon.flow/flow-workload-attributes)
                   :seon.config.flow.compute/queue-depth 10
                   :seon.config.flow.compute/concurrency 2
                   :seon.config.flow.io/queue-depth 2
                   :seon.config.flow.io/concurrency 2)})]
     (try
      ;; Apply the final manifest once; cluster and agent facts need that config.
      (let [result
            (config/apply!
             {:seon.db/connection connection
              :seon.boot/cluster-name "turn-test"
              :seon.config/manifest
              {:seon.config.run/max-episode-runs 100
               :seon.config.ai/endpoint "http://127.0.0.1:1/v1"
               :seon.config.ai/model "probe"
               ;; Failover tests opt in through `configure-backup!`.
               :seon.config.ai.backup/model config/absent
               :seon.config.ai/max-tokens 32
               :seon.config.ai/api-key-variable "SEON_AI_TEST_KEY"
               :seon.config.ai/timeout-ms 200
               :seon.config.ai.retry/base-delay-ms 1
               :seon.config.ai.retry/multiplier 2.0
               :seon.config.ai.retry/jitter-fraction 0.0
               :seon.config.ai.retry/maximum-delay-ms 1
               :seon.config.ai.retry/maximum-retries 0
               :seon.config.ai.retry/maximum-total-delay-ms 0}})]
        (when (or (:seon.config/error-key result) (:seon.db/invalid-read result) (:seon.schema/expected-value result) (:seon.db.write.attempt/request-id result))
          (throw (ex-info "Turn fixture configuration was refused." result))))
      (let [result (cluster/ensure-cluster-entity!
                    connection "turn-test" cluster/boot-process-identity)]
        (when (or (:seon.config/error-key result) (:seon.db/invalid-read result) (:seon.schema/expected-value result) (:seon.db.write.attempt/request-id result))
          (throw (ex-info "Turn fixture cluster was refused." result))))
      (let [result
            (db/transact! connection
                  [{:seon.ns/name 'clojure.set}
                   {:seon.ns/name 'clojure.test}
                   {:seon.ns/name 'seon.schema}
                   (agent-row "agent-a")
                   {:seon.message/id "m-1" :seon.message/to [:seon.agent/id "agent-a"] :seon.message/content "count the widgets"}])]
        (when (or (:seon.config/error-key result) (:seon.db/invalid-read result) (:seon.schema/expected-value result) (:seon.db.write.attempt/request-id result))
          (throw (ex-info "Turn fixture seed was refused." result))))
      (with-render-context-proc
         connection
         ;; THE HANDLE IS THE PRODUCTION HANDLE: `test-support/cluster-handle`
         ;; carries the declared channel members `seon.cluster.agent/arm!`
         ;; creates for every armed agent, so `turn` is called with the shape
         ;; its contract declares instead of one this suite happens to read.
         (test-support/cluster-handle
         {:seon.env/environment @test-environment
               :seon.db/connection connection
               :seon.cluster/name "turn-test"
               :seon.flow/work-launcher launcher
               :seon.db.process/id process
               :seon.sci.eval/ctx
               (test-support/fork-cluster-ctx connection)
               :seon.cluster.wake/channel
               (clojure.core.async/chan (clojure.core.async/sliding-buffer 1))
             :seon.config.eval/time-limit-ms 2000
             :seon.config/on-core-error :panic
             ;; a refused transaction is recorded as a durable error
             ;; fact (D3), so the handle carries the dials that
             ;; recording needs. No escalate-to: this fixture has no
             ;; root agent, and absence is the state.
             :seon.config.error/recurrence-limit 3
             ;; the conversation bound, carried like every other dial.
             ;; Small on purpose: the fixture that proves the guard
             ;; should not need sixteen turns to reach it.
             :seon.config.message/max-chain 2
             ;; max-string bounds BOTH eval-result admission and every
             ;; prompt contribution now (the one cap set) — sized so a
             ;; real prompt is never elided while eval results stay small
               :seon.sci.admit/caps
               (assoc (config/result-caps
                       (test-support/effective-config))
                      :seon.config.eval.result/max-depth 6
                      :seon.config.eval.result/max-collection 8
                      :seon.config.eval.result/max-string 4096
                      :seon.config.eval.result/max-nodes 256)})
         (if evaluator
           (fn [cluster-handle]
             (let [evaluate sci.eval/evaluate]
               (with-redefs [sci.eval/evaluate
                             (fn [request]
                               (let [injected (evaluator request)
                                     evaluated
                                     (evaluate
                                      (-> request
                                          (dissoc :seon.sci.eval/event)
                                          (assoc :seon.cluster.eval/source
                                                 (pr-str (list 'quote (:seon.sci.admit/value injected))))))]
                                 (merge evaluated injected)))]
                 (body cluster-handle))))
           body))
      (finally
        (seon.flow/stop-work-launcher! launcher))))))))

(defn- request
  "The AGENT-SCOPED work request (F2 §3.2).
  The global request died with the central pass; every site in this
  suite already knows its agent, and the fixture's own agent is the
  default. This suite asserts the TURN, which survives whole — so
  re-pointing here is plumbing, not an oracle change."
  ([connection] (request connection "agent-a"))
  ([_connection agent-id]
   {:seon.agent/id agent-id
    :seon.db.process/id process
    :seon.turn.work/now (Date.)}))

(defn- agent-ids
  "Every agent in facts, oldest name first.
  Production gives each of them its OWN turn proc; this suite drives
  them in one thread, so the driver asks each agent's own derivation in
  turn rather than asking a global one that no longer exists."
  [db]
  (sort (db/q '[:find [?id ...] :where [?e :seon.agent/id ?id]] db)))

(defn- any-agent-work
  "The first agent with work, derived through the AGENT-SCOPED
  derivation. Suite plumbing: production has no global pass — each
  agent's proc derives for itself — so this asks each in turn."
  [connection]
  (some (fn [agent-id]
          (turn/next-agent-work @connection (request connection agent-id)))
        (agent-ids @connection)))

(defn- drive-passes!
  "Run the loop's own pass — settle, then derive, then turn — until idle.
  This drives what `step` drives, so a test sees what production sees."
  [cluster limit]
  (let [connection (:seon.db/connection cluster)]
    (loop [passes 0]
      (let [now (Date.)]

        (when-let [work (any-agent-work connection)]
          (when (< passes limit)
            (turn/turn {:seon.turn.loop/cluster cluster
                                :seon.turn.work/next work}
                               now)
            (recur (inc passes))))))))

(defn- drive!
  "Run passes until the loop says idle, or `limit` passes have run."
  [cluster limit]
  (let [connection (:seon.db/connection cluster)]
    (loop [passes 0 reports []]
      (let [request (request connection)
            work (any-agent-work connection)]
        (if (or (nil? work) (>= passes limit))
          reports
          (recur (inc passes)
                 (conj reports
                       (turn/turn
                        {:seon.turn.loop/cluster cluster
                         :seon.turn.work/next work}
                        (:seon.turn.work/now request)))))))))

(defn- drive-agent!
  "Run one agent's passes until that agent is idle, or `limit` is reached."
  [cluster agent-id limit]
  (let [connection (:seon.db/connection cluster)]
    (loop [passes 0
           reports []]
      (let [request (request connection agent-id)
            work (turn/next-agent-work @connection request)]
        (if (or (nil? work) (>= passes limit))
          reports
          (recur (inc passes)
                 (conj reports
                       (turn/turn
                        {:seon.turn.loop/cluster cluster
                         :seon.turn.work/next work}
                        (:seon.turn.work/now request)))))))))


(deftest a-prose-prefixed-contracted-defn-settles-and-doc-answers
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)
            source
            (str
             "; The situation is clear - this cluster has stale function references that need a JVM restart to fix. The maintenance tasks are failing because the operator functions (`seon.operator/census-processes!`, `seon.maintenance/observe-footprint!`, etc.) have been removed from the published program graph but are still loaded in the JVM.\n\n"
             "; This is a systemic issue that I can't fix from within the REPL - it requires someone to restart the JVM. Let me note this finding and see if I can do anything else productive.\n\n"
             "; Actually, let me look at this from the bootstrap task perspective - I had a task to define a function called `largest`. Let me focus on that:\n\n"
             "(defn largest\n"
             "  \"Return the row with the greatest :example/amount, or {} for empty input.\"\n"
             "  {:malli/schema [:=> [:cat [:sequential :map]] [:or :map]]}\n"
             "  [rows]\n"
             "  (if (empty? rows)\n"
             "    {}\n"
             "    (apply max-key #(or (:example/amount %) 0) rows)))\n"
             "(doc my.agents.agent-a/largest)\n"
             "(seon.run/complete \"built largest\")")]
        (with-redefs [ai/complete (fn [_projection _] {:seon.ai/text source})]
          (let [reports (drive-agent! cluster "agent-a" 2)
                row (db/pull @connection
                             '[:seon.fn/sym :seon.fn/spec :seon.fn/doc]
                             [:seon.fn/sym "my.agents.agent-a/largest"])
                doc-output
                (db/q '[:find ?output .
                        :where
                        [?receipt :seon.cluster.eval/ordinal 1]
                        [?receipt :seon.eval/shown ?output]
                        [?receipt :seon.cluster.eval/author :agent]]
                      @connection)]
            (is (= [:open :call]
                   (mapv :seon.turn.work/situation reports)))
            (is (= "[:=> [:cat [:sequential :map]] [:or :map]]"
                   (:seon.fn/spec row)))
            (is (= "Return the row with the greatest :example/amount, or {} for empty input."
                   (:seon.fn/doc row)))
            (is (str/includes? doc-output "my.agents.agent-a/largest"))))))))


(deftest function-install-reads-the-case-count-from-cluster-facts
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)
            configured (config/apply!
                        {:seon.db/connection connection
                         :seon.boot/cluster-name "turn-test"
                         :seon.config/manifest
                         {:seon.config.test/auto-check-cases 3}})]
        (is (boolean? (:seon.reconcile/converged? configured)))
        (with-redefs [ai/complete
                      (fn [_projection _]
                        {:seon.ai/text
                         (str "(defn configured-inc "
                              "{:malli/schema [:=> [:cat :int] :int]} "
                              "[x] (inc x))\n"
                              "(seon.run/complete \"Installed.\")")})]
          (let [reports (drive! (dissoc cluster :seon.config.test/auto-check-cases) 10)
                installed (db/pull @connection [:seon.fn/spec]
                                   [:seon.fn/sym "my.agents.agent-a/configured-inc"])
                cases (db/q '[:find [?n ...]
                              :where [_ :seon.test.accretion/case-count ?n]]
                            @connection)]
            (is (= :closed (:seon.turn.loop/outcome (last reports))))
            (is (string? (:seon.fn/spec installed)))
            (is (= [3] cases))))))))

(deftest a-whole-turn-runs-a-REAL-sci-evaluation-end-to-end
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)]
        (with-redefs [ai/complete
                      (fn [_projection _]
                        {:seon.ai/text
                         (str "(def widgets (map inc (range 3)))\n"
                              "(do (seon.db/q '[:find [?id ...] :where [_ :seon.agent/id ?id]]) widgets)\n"
                              "(seon.run/complete (str \"counted \" (reduce + widgets)))")})]
          (is (= [:open :call]
                 (mapv :seon.turn.work/situation
                       (drive-agent! cluster "agent-a" 2))))
          (let [database (db/db connection)
                evaluations (agent-evaluations database)
                [definition read-evaluation completion] evaluations
                evaluation-id (:db/id read-evaluation)
                [read-basis start-tx settlement-tx]
                (db/q '[:find [?basis ?start ?settlement]
                        :in $ ?evaluation
                        :where
                        [?evaluation :seon.cluster.eval/read-basis-transaction ?basis ?settlement]
                        [?evaluation :seon.cluster.eval/at _ ?start]
                        [?evaluation :seon.eval/shown _ ?settlement]]
                      database evaluation-id)
                evidence
                (db/q '[:find [(pull ?evidence [*]) ...]
                        :in $ ?evaluation
                        :where
                        [?evaluation :seon.cluster.eval/read-evidence ?evidence]]
                      database evaluation-id)]
            (is (= 3 (count evaluations)))
            (is (str/includes? (:seon.eval/shown definition) "my.agents.agent-a/widgets"))
            (is (= "(1 2 3)" (:seon.eval/shown read-evaluation))
                "a later evaluation sees the private object from the persistent SCI context")
            (is (str/includes? (:seon.eval/shown completion) "counted 6"))
            (is (int? read-basis))
            (is (= start-tx read-basis))
            (is (not= settlement-tx read-basis))
            (is (= #{settlement-tx}
                   (set (db/q '[:find [?tx ...]
                                :in $ ?evaluation
                                :where [?evaluation :seon.cluster.eval/read-evidence _ ?tx]]
                              database evaluation-id))))
            (is (seq evidence))
            (is (every? #(and (int? (:seon.db/source-argument-position %))
                             (contains? % :datahike.read/dependency-plan)
                             (map? (:datahike.read/revision %))) evidence))
            (is (int? (:seon.cluster.eval/read-basis-transaction definition))
                "every evaluation records its database basis")
            (is (every? #(and (nil? (:seon.cluster.eval/error %))
                             (nil? (:seon.cluster.eval/interrupted-at %))) evaluations))
            (is (nil? (turn/next-agent-work database (request connection))))))))))

(deftest agent-code-with-defn-and-println-folds-green-without-in-ns
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)]
        (with-redefs [ai/complete
                      (fn [_projection _]
                        {:seon.ai/text
                         (str "(defn widget-count {:malli/schema [:=> [:cat :int] :int]} [n] (* n 3))\n"
                              "(println \"counting\" (widget-count 4) \"in\" (str *ns*))\n"
                              "(seon.run/complete (str \"there are \" (widget-count 4)))")})]
          (drive-agent! cluster "agent-a" 2)
          (let [evaluations (agent-evaluations (db/db connection))]
            (is (= 3 (count evaluations)))
            (is (every? #(and (nil? (:seon.cluster.eval/error %))
                             (nil? (:seon.cluster.eval/interrupted-at %))) evaluations))
            (is (str/includes? (:seon.eval/shown (first evaluations))
                               "my.agents.agent-a/widget-count"))
            (is (= "counting 12 in my.agents.agent-a"
                   (str/trim (:seon.cluster.eval/output (second evaluations)))))
            (is (str/includes? (:seon.eval/shown (nth evaluations 2)) "there are 12"))
            (is (nil? (turn/next-agent-work (db/db connection) (request connection))))))))))

(deftest mixed-plan-publishes-only-the-contracted-function
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)]
        (test-support/transacted! connection
                                [{:seon.ns/name 'my.agents.agent-a}
                                 {:seon.agent/id "agent-a"
                                  :seon.agent/namespace
                                  [:seon.ns/name 'my.agents.agent-a]}])
        (with-redefs
          [ai/complete
           (fn [_projection _]
             {:seon.ai/text
              (str
               "(defn ^{:malli/schema [:=> [:cat [:map "
               "[:my.agents.agent-a/value :int]]] :int]} durable "
               "[request] (inc (:my.agents.agent-a/value request)))\n"
               "(def x {:my.agents.agent-a/value 42 "
               ":my.agents.agent-a/extra :ignored})\n"
               "(durable x)")})]
          (drive-agent! cluster "agent-a" 2)
          (testing "every submitted form leaves its own saved evaluation"
            (is (= 3
                   (count (agent-evaluations (db/db connection))))))
          (testing "installation derives from db-after before the next form"
            (is (= "43"
                   (:seon.eval/shown (nth (agent-evaluations (db/db connection)) 2)))))
          (testing "only the contracted defn enters the program graph"
            (is (=
                 "[:=> [:cat [:map [:my.agents.agent-a/value :int]]] :int]"
                 (db/q '[:find ?spec .
                        :in $ ?sym
                        :where
                        [?fn :seon.fn/sym ?sym]
                        [?fn :seon.fn/spec ?spec]]
                      @connection "my.agents.agent-a/durable"))
                "the open input-map contract admits and persists")
            (is (empty?
                 (db/q '[:find ?entity
                        :where
                        [?entity :seon.fn/sym "my.agents.agent-a/x"]]
                      @connection)))))))))

(deftest ns-unmap-retracts-the-owned-function-after-the-terminal-commit
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)
            identity [:seon.fn/sym "my.agents.agent-a/obsolete"]
            deletion {:seon.program/delete-identities
                      [identity [:seon.test/sym "my.agents.agent-a/obsolete"]]
                      :seon.program/source "(ns-unmap 'my.agents.agent-a 'obsolete)"}]
        (is (true? (sci.eval/committed-row? (db/db connection) deletion))
            "an identity that never existed has no remaining definition facts")
        (with-redefs [ai/complete
                      (fn [_projection _]
                        {:seon.ai/text
                         (str "(defn ^{:malli/schema [:=> [:cat :int] :int]} obsolete [x] (inc x))\n"
                              "(seon.run/complete \"installed\")")})]
          (drive-agent! cluster "agent-a" 2))
        (is (false? (sci.eval/committed-row? (db/db connection) deletion))
            "a live definition cannot be reported deleted")
        (test-support/transacted! connection
                                  [{:seon.message/id "delete-obsolete"
                                    :seon.message/to [:seon.agent/id "agent-a"]
                                    :seon.message/content "Remove obsolete."}])
        (with-redefs [ai/complete
                      (fn [_projection _]
                        {:seon.ai/text
                         "(ns-unmap 'my.agents.agent-a 'obsolete)\n(seon.run/complete \"deleted\")"})]
          (drive-agent! cluster "agent-a" 2))
        (let [evaluations (agent-evaluations (db/db connection))]
          (is (= 4 (count evaluations)))
          (is (= "nil" (:seon.eval/shown (nth evaluations 2))))
          (is (every? #(nil? (:seon.cluster.eval/error %)) evaluations)))
        (is (nil? (db/pull (db/db connection) '[*] identity))
            "program-facts section 1f G1 retracts the owned entity")
        (is (true? (sci.eval/committed-row? (db/db connection) deletion))
            "the retracted function and absent sibling test both verify as deleted")))))

(deftest reply-reading-follows-evaluated-alias-and-dynamic-require-state
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)]
        (with-redefs
          [ai/complete
           (fn [_projection _]
             {:seon.ai/text
              (str
               "(alias 'str 'clojure.string)\n"
               "{:x ::str/after-alias}\n"
               "(require (if true '[clojure.set :as sets] "
               "'[clojure.string :as sets]))\n"
               "{:x ::sets/after-dynamic-require}")})]
          (drive-agent! cluster "agent-a" 2)
          (let [results
                (mapv :seon.eval/shown (agent-evaluations (db/db connection)))]
            (is (= "{:x :clojure.string/after-alias}" (get results 1)))
            (is (= "{:x :clojure.set/after-dynamic-require}" (get results 3))
                "later sources are read only after prior namespace effects")))))))

(deftest qualified-dynamic-ns-unmap-is-durable-in-a-fresh-context
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)
            function-sym "my.agents.agent-a/dynamic-obsolete"]
        (with-redefs [ai/complete
                      (fn [_projection _]
                        {:seon.ai/text
                         (str "(defn ^{:malli/schema [:=> [:cat :int] :int]} dynamic-obsolete [x] (inc x))\n"
                              "(clojure.core/ns-unmap (find-ns 'my.agents.agent-a) (symbol \"dynamic-obsolete\"))\n"
                              "(seon.run/complete \"deleted\")")})]
          (drive-agent! cluster "agent-a" 2)
          (is (nil? (db/pull (db/db connection) '[*] [:seon.fn/sym function-sym]))
              "program-facts section 1f G1 retracts the entity, not only its definition")
          (is (string? (db/q '[:find ?source . :in $ ?symbol :where
                              [?function :seon.fn/sym ?symbol]
                              [?function :seon.fn/source ?source]]
                            (db/history (db/db connection)) function-sym))
              "the historical declaration proves this is deletion, not failed creation")
          (let [database (db/db connection)
                fresh (sci.eval/cluster-ctx
                       database connection
                       (sci.eval/projection-state database (db/carried-projection database)))]
            (is (nil? (sci.core/eval-string* fresh
                       "(resolve 'my.agents.agent-a/dynamic-obsolete)"))
                "fresh acquisition cannot resurrect a deleted definition")))))))

(deftest absent-foreign-ns-unmap-commits-and-mutates-the-run-sci-ctx
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)
            original-evaluate sci.eval/evaluate
            evaluated-ctx (atom nil)]
        (with-redefs [ai/complete
                      (fn [_projection _] {:seon.ai/text "(ns-unmap 'clojure.string 'upper-case)"})
                      sci.eval/evaluate
                      (fn [request]
                        (reset! evaluated-ctx (:seon.sci.eval/ctx request))
                        (original-evaluate request))]
          (drive-agent! cluster "agent-a" 2)
          (let [evaluations (agent-evaluations (db/db connection))]
            (is (= 1 (count evaluations)))
            (is (= "nil" (:seon.eval/shown (first evaluations))))
            (is (nil? (:seon.cluster.eval/error (first evaluations)))))
          (is (nil? (sci.core/eval-string* @evaluated-ctx
                     "(resolve 'clojure.string/upper-case)"))
              "the committed deletion updates the evaluated SCI context"))))))

(deftest import-only-ns-unmap-installs-exactly-after-its-context-commit
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)
            original-evaluate sci.eval/evaluate
            evaluated-ctx (atom nil)]
        (with-redefs
          [ai/complete
           (fn [_projection _]
             {:seon.ai/text
              (str "(clojure.core/ns-unmap *ns* (symbol \"String\"))\n"
                   "(resolve 'String)")})
           sci.eval/evaluate
           (fn [request]
             (reset! evaluated-ctx (:seon.sci.eval/ctx request))
             (original-evaluate request))]
          (drive-agent! cluster "agent-a" 2)
          (is (= "nil"
               (:seon.eval/shown (nth (agent-evaluations (db/db connection)) 1)))
              "the next form sees the committed import removal")
          (is (nil?
               (:val
                (sci.core/eval-string+
                 @evaluated-ctx
                 "(resolve 'String)"
                 {:ns (sci.core/create-ns 'my.agents.agent-a)})))
              "the supplied run ctx receives the exact isolated state")
          (let [namespace-row
                (db/pull @connection
                        '[* {:seon.ns/imports [*]}]
                        [:seon.ns/name 'my.agents.agent-a])
                import-mask
                (some #(when (= 'String (:seon.ns.import/local %)) %)
                      (:seon.ns/imports namespace-row))
                fresh (sci.eval/cluster-ctx (db/db connection) connection)]
            (is (= {:seon.ns.import/local 'String}
                   (dissoc import-mask :db/id))
                "the database stores the import mask as ordinary data")
            (is (nil?
                 (:val
                  (sci.core/eval-string+
                   fresh
                   "(resolve 'String)"
                   {:ns (sci.core/create-ns 'my.agents.agent-a)})))
                "fresh acquisition reinstalls the persisted mask")))))))

(deftest refused-import-only-ns-unmap-leaves-the-run-sci-ctx-unchanged
  (with-cluster
    (fn [cluster]
      (let [original-evaluate sci.eval/evaluate
            evaluated-ctx (atom nil)
            transact! db/transact!]
        (with-redefs
          [ai/complete
           (fn [_projection _]
             {:seon.ai/text
              "(clojure.core/ns-unmap *ns* (symbol \"String\"))"})
           sci.eval/evaluate
           (fn [request]
             (reset! evaluated-ctx (:seon.sci.eval/ctx request))
             (original-evaluate request))
           db/transact!
           (fn [target transaction]
             (let [tx-data (if (map? transaction)
                             (:tx-data transaction)
                             transaction)
                   namespace-mutation?
                   (some
                    (fn [operation]
                      (and (vector? operation)
                           (= :db.fn/call (first operation))
                           (= #'turn/receipt-settle-call (second operation))
                           (some-> (get-in operation
                                         [2 :seon.program/row
                                          :seon.ns/source])
                                   (str/includes? "ns-unmap"))))
                    tx-data)]
               (if namespace-mutation?
                 {:seon.db.write.attempt/request-id "namespace-refusal" :seon.error/at (java.util.Date.) :seon.error/layer :seon.db/invocation :seon.error/operation 'seon.db/transact!
                  :seon.error/message "injected namespace refusal"
                  :seon.error/data {:error :transact/namespace}}
                 (transact! target transaction))))]
          (drive! cluster 10)
          (is (nil?
               (db/q '[:find ?import .
                      :in $ ?namespace ?local
                      :where
                      [?namespace-entity :seon.ns/name ?namespace]
                      [?namespace-entity :seon.ns/imports ?import]
                      [?import :seon.ns.import/local ?local]]
                    @(:seon.db/connection cluster)
                    'my.agents.agent-a
                    'String))
              "a refused mask never reaches the database")
          (is (nil?
               (:val
                (sci.core/eval-string+
                 @evaluated-ctx
                 "(resolve 'String)"
                 {:ns (sci.core/create-ns 'my.agents.agent-a)})))
              "the rejected mutation remains visible only in the discarded turn fork")
          (is (some?
               (:val
                (sci.core/eval-string+
                 (:seon.sci.eval/ctx cluster)
                 "(resolve 'String)"
                 {:ns (sci.core/create-ns 'my.agents.agent-a)})))
              "the live base context is unchanged by the rejected turn"))))))

(deftest import-addition-is-ordinary-data-and-reacquires-exactly
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)]
        (with-redefs
          [ai/complete
           (fn [_projection _]
             {:seon.ai/text
              "(import java.lang.String)"})]
          (drive! cluster 10)
          (let [namespace-row
                (db/pull @connection
                        '[* {:seon.ns/imports [*]}]
                        [:seon.ns/name 'my.agents.agent-a])
                import-row
                (some #(when (= 'String (:seon.ns.import/local %)) %)
                      (:seon.ns/imports namespace-row))
                fresh (sci.eval/cluster-ctx @connection)]
            (is (= {:seon.ns.import/local 'String
                    :seon.ns.import/target-class 'java.lang.String}
                   (dissoc import-row :db/id))
                "the database stores only symbols, never a Class object")
            (is (= 'java.lang.String
                   (get-in (sci.core/namespace-bindings
                            fresh 'my.agents.agent-a)
                           [:imports 'String]))
                "fresh acquisition restores the exact local import")))))))

(deftest refused-terminal-program-transactions-settle-and-do-not-refire
  ;; Checkpoint-audit blocker B1, through the real SCI boundary with the
  ;; terminal database outcome injected at its one transaction seam. ONE
  ;; refused evaluation settles and closes atomically, records once, and never
  ;; replays that turn. An accepted provider reply may continue in a NEW turn
  ;; under PRD section 14 and the same episode cap. `ns-unmap` itself has ordinary
  ;; SCI REPL semantics and is not the source of this refusal.
  (doseq [[label cap next-turn?]
          [["below the episode cap" 2 true]
           ["at the episode cap" 1 false]]]
    (testing label
      (with-cluster
        (fn [cluster]
          (let [connection (:seon.db/connection cluster)
                calls (atom [])
                transact! db/transact!
                original-install! sci.eval/install-row!
                installations (atom [])]
            (let [seed (db/transact!
             connection
             [(agent-row "peer")
              {:db/id [:seon.config/cluster "turn-test"]
               :seon.config.run/max-episode-runs cap}
              {:seon.ns/name 'seon.config}
              {:db/id [:seon.fn/sym "seon.config/defaults"]
               :seon.fn/ns [:seon.ns/name 'seon.config]
               :seon.fn/source "(defn defaults [] {})"
               :seon.fn/spec "[:=> [:cat] :map]"}])]
              (when (:seon.db.write.attempt/request-id seed)
                (throw (ex-info "Terminal refusal fixture seed was refused." seed))))
            ;; This test plants a pre-existing program row directly rather
            ;; than producing it through eval. Finish that cold fixture setup
            ;; before the first turn; live turns never reacquire facts.
            (sci.eval/acquire!
             {:seon.sci.eval/ctx (:seon.sci.eval/ctx cluster)
              :seon.db/db @connection})
            (with-redefs
              [ai/complete
               (fn [_projection request]
                 (swap! calls conj request)
                 {:seon.ai/text
                  (if (= 1 (count @calls))
                    "(ns-unmap (quote seon.config) (quote defaults))"
                    "(seon.run/complete \"recovered\")")})
               db/transact!
               (fn [target transaction]
                 (let [tx-data (if (map? transaction)
                                 (:tx-data transaction)
                                 transaction)
                       deletion?
                       (some
                        (fn [operation]
                          (and (vector? operation)
                               (= :db.fn/call (first operation))
                               (= #'turn/receipt-settle-call
                                  (second operation))
                               (seq
                                (get-in operation
                                        [2 :seon.program/row
                                         :seon.program/delete-identities]))))
                        tx-data)]
                   (if (and (identical? target connection) deletion?)
                     {:seon.db.write.attempt/request-id "program-refusal" :seon.error/at (java.util.Date.) :seon.error/layer :seon.db/invocation :seon.error/operation 'seon.db/transact!
                      :seon.error/message
                      "injected terminal program refusal"
                      :seon.error/data {:error :transact/program}}
                     (transact! target transaction))))
               sci.eval/install-row!
               (fn [request]
                 (when (some #{[:seon.fn/sym "seon.config/defaults"]}
                             (get-in request [:seon.program/row
                                              :seon.program/delete-identities]))
                   (swap! installations conj request))
                 (original-install! request))]
              (let [reports (drive-agent! cluster "agent-a" 2)
                    db @connection
                    evaluations
                    (agent-evaluations db)
                    error-facts
                    (db/q '[:find [?error ...]
                           :where
                           [?error :seon.error/id _]]
                         db)]
                (is (= [:open :call]
                       (mapv :seon.turn.work/situation reports))
                    "the refusal closes in its terminal pass")
                (is (= 1 (count evaluations)))
                (is (turn/terminal? (first evaluations)))
                (is (= "injected terminal program refusal"
                       (:seon.cluster.eval/error (first evaluations))))
                (is (= "injected terminal program refusal"
                       (:seon.error/message
                        (edn/read-string
                         (:seon.eval/shown
                          (first evaluations))))))
                (is (= 1 (count error-facts))
                    "one refusal records exactly one durable error")
                (let [work (turn/next-agent-work db (request connection "agent-a"))
                      refused-run (get-in (first evaluations) [:seon.cluster.eval/run :db/id])]
                  (is (= (when next-turn? :open) (:seon.turn.work/situation work))
                      "accepted replies may continue, but never resume the refused turn")
                  (when next-turn?
                    (is (some? (:seon.turn/closed-tx
                                (db/pull db [:seon.turn/closed-tx] refused-run)))
                        "the refused turn has an actual closing transaction")))
                (is (= next-turn?
                     (turn/more-agent-work?
                      @connection (request connection "agent-a")))
                    "the self-rewake predicate derives the bounded new-turn permission")
                (is (= 1 (count @calls))
                    "the refused turn calls the model exactly once")
                (is (empty?
                     (db/q '[:find ?message
                            :where
                            [?message :seon.message/about ?signature]
                             [?error :seon.error/signature ?signature]
                            [?error :seon.error/id _]]
                          @connection))
                    "a returned error value creates no delivery wake")
                (is (not-any?
                     #(seq (get-in % [:seon.program/row
                                      :seon.program/delete-identities]))
                     @installations)
                    "the refused deletion never installs")
                (is (= "(defn defaults [] {})"
                       (:seon.fn/source
                        (db/pull db
                                [:seon.fn/source]
                                [:seon.fn/sym "seon.config/defaults"])))
                    "commit-first leaves the program row unchanged")
                (test-support/transacted!
                             connection
                             [{:seon.message/id "peer-follow-up" :seon.message/to [:seon.agent/id "agent-a"] :seon.message/from [:seon.agent/id "peer"] :seon.message/content "Try again after reading the error."}])
                (let [next-reports (drive-agent! cluster "agent-a" 10)]
                  (if next-turn?
                    (do
                      (is (= [:open :call]
                             (mapv :seon.turn.work/situation next-reports)))
                      (is (= 2 (count @calls)))
                      (is (str/includes?
                           (:seon.ai/prompt (second @calls))
                           "injected terminal program refusal")
                          "the next turn's context sees the refusal fact"))
                    (do
                      (is (empty? next-reports)
                          "the capped episode simply ends")
                      (is (= 1 (count @calls))
                          "the cap is the only retry budget")
                      (is (= ["peer-follow-up"]
                             (mapv :seon.message/id
                                   (turn/deferred-triggers
                                    @connection "agent-a"))))))
                (is (= 1
                       (count
                        (db/q '[:find ?error
                               :where [?error :seon.error/id _]]
                             @connection)))
                    "later work never re-records the original event"))))))))))

(deftest evaluation-follows-the-readers-parse-time-namespace
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)]
        (with-redefs
          [ai/complete
           (fn [_projection _]
             {:seon.ai/text
              (str
               "(in-ns 'my.gen.alpha)\n"
               "(defn ^{:malli/schema [:=> [:cat :int] :int]} f "
               "[x] (inc x))\n"
               "(f 1)")})]
          (drive-agent! cluster "agent-a" 2)
          (is (= "2"
                 (:seon.eval/shown (nth (agent-evaluations (db/db connection)) 2)))
              "in-ns governs the later definition and call")
          (is (some?
               (db/q '[:find ?fn .
                      :in $ ?sym
                      :where
                      [?fn :seon.fn/sym ?sym]]
                    @connection "my.gen.alpha/f")))
          (is (= ['my.agents.agent-a 'my.gen.alpha 'my.gen.alpha]
                 (mapv #(get-in % [:seon.cluster.eval/ns :seon.ns/name])
                       (agent-evaluations (db/db connection))))
              "each submitted source records the namespace in which it was read"))))))

(deftest contracted-redefinition-exactly-replaces-the-row
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)]
        (with-redefs
          [ai/complete
           (fn [_projection _]
             {:seon.ai/text
              (str
               "(defn ^{:malli/schema [:=> [:cat :int] :int] "
               ":seon.workload :compute} f \"old\" [x] (inc x))\n"
               "(defn ^{:malli/schema [:=> [:cat :int] :int]} f "
               "[x] (+ x 2))\n"
               "(f 1)")})]
          (drive-agent! cluster "agent-a" 2)
          (is (= "3"
                 (:seon.eval/shown (nth (agent-evaluations (db/db connection)) 2))))
          (let [row (db/pull @connection
                            '[*]
                            [:seon.fn/sym "my.agents.agent-a/f"])]
            (is (= "(defn ^{:malli/schema [:=> [:cat :int] :int]} f [x] (+ x 2))"
                   (:seon.fn/source row)))
            (is (= :agent (:seon.schema.admission/source row)))
            (is (not (contains? row :seon.fn/doc)))
            (is (not (contains? row :seon.fn/workload)))))))))

(deftest a-refused-contract-commits-a-receipt-and-no-row
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)]
        (with-redefs
          [ai/complete
           (fn [_projection _]
             {:seon.ai/text
              "(defn ^{:malli/schema [:=> [:cat :missing/schema] :int]} bad [x] x)"})]
          (drive! cluster 10)
          (is (some?
               (db/q '[:find ?error .
                      :where
                      [?receipt :seon.cluster.eval/error ?error]]
                    @connection))
              "the failed form reaches a terminal receipt")
          (is (nil?
               (db/pull @connection
                       [:seon.fn/sym]
                       [:seon.fn/sym "my.agents.agent-a/bad"]))
              "the failed declaration commits no program fact"))))))

(deftest runtime-schema-registration-commits-the-evaluated-form-and-attribute
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)
            persistent-key :my.agents.agent-a/nonnegative
            value-key :my.agents.agent-a/label]
        (with-redefs
          [ai/complete
           (fn [_projection _]
             {:seon.ai/text
              (str
               "(require '[seon.schema :as schema])\n"
               "(schema/register! ::nonnegative "
               "(vector :int {:min 0 :seon.db/index true}))\n"
               "(schema/register! ::label (vector :string {:min 1}))\n"
               "(seon.run/complete \"schemas committed\")")})]
          (drive-agent! cluster "agent-a" 2)
          (let [db @connection
                persistent-row
                (db/pull db '[*] [:seon.schema/key persistent-key])
                value-row (db/pull db '[*] [:seon.schema/key value-key])
                tx-pairs
                (db/q '[:find ?schema-tx ?receipt-tx
                       :in $ ?schema-key
                       :where
                       [?schema :seon.schema/key ?schema-key]
                       [?schema :seon.schema/form _ ?schema-tx]
                       [?receipt :seon.cluster.eval/ordinal 1]
                       [?receipt :seon.eval/shown _ ?receipt-tx]
                       [?receipt :seon.cluster.eval/author :agent]]
                     db persistent-key)]
            (is (= "[:int {:min 0, :seon.db/index true}]"
                   (:seon.schema/form persistent-row))
                "the row contains evaluated canonical EDN, not `(vector ...)`")
            (is (= "[:string {:min 1}]"
                   (:seon.schema/form value-row)))
            (is (= :agent
                   (:seon.schema.admission/source persistent-row)
                   (:seon.schema.admission/source value-row)))
            (is (= :db.unique/identity
                   (get-in db [:schema :seon.schema/key :db/unique]))
                "the schema key is its global identity")
            (is (= 'my.agents.agent-a
                   (:seon.ns/name
                    (db/pull db [:seon.ns/name]
                             (:db/id (:seon.schema/ns persistent-row)))))
                "the namespace ref records where the global declaration was authored")
            (is (= 1 (count tx-pairs)))
            (is (every? (fn [[schema-tx receipt-tx]]
                          (= schema-tx receipt-tx))
                        tx-pairs)
                "the schema row and terminal receipt are one commit")
            (is (contains? (:schema db) persistent-key)
                "an explicit persistence facet installs the Datahike attribute")
            (is (not (contains? (:schema db) value-key))
                "a value schema does not invent a Datahike attribute")
            (when (contains? (:schema db) persistent-key)
              (test-support/transacted! connection [{persistent-key 7}])
              (is (= 7
                     (db/q '[:find ?value .
                            :in $ ?attribute
                            :where [?entity ?attribute ?value]]
                          @connection persistent-key))
                  "the committed attribute accepts a fact immediately"))))))))

(deftest runtime-schema-key-changes-pass-the-one-usage-guarded-decision
  ;; ONE rule decides whether a schema form may change, and it is the usage
  ;; guard: a key nothing depends on is an ordinary declaration, and a key
  ;; current data depends on refuses naming those attributes. `c55879b73`
  ;; briefly layered an unconditional immutability refusal ahead of that
  ;; guard, which swallowed the guard's typed answer; the guard's own suite
  ;; (`seon.schema-usage-guard-test`) owns the refusal and divergence faces,
  ;; and this test owns the live-loop shape: a run may refine a key it owns,
  ;; and the run stays open through it.
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)
            schema-key :shared.runtime/refined]
        (with-redefs
          [ai/complete
           (fn [_projection _]
             {:seon.ai/text
              (str
               "(require '[seon.schema :as schema])\n"
               "(schema/register! :shared.runtime/refined :string)\n"
               "(schema/register! :shared.runtime/refined :string)\n"
               "(schema/register! :shared.runtime/refined :int)\n"
               "(seon.run/complete \"refined\")")})]
          (drive! cluster 10)
          (let [database @connection
                row (db/pull database '[*] [:seon.schema/key schema-key])
                evaluations (agent-evaluations database)]
            (is (= ":int" (:seon.schema/form row))
                "the run's own unconsumed key refines to its latest form")
            (is (= (pr-str schema-key) (get-in evaluations [1 :seon.eval/shown])))
            (is (= (pr-str schema-key) (get-in evaluations [2 :seon.eval/shown]))
                "identical registration is an ordinary idempotent success")
            (is (= (pr-str schema-key) (get-in evaluations [3 :seon.eval/shown]))
                "and so is a change nothing currently depends on")
            (is (every? #(nil? (:seon.cluster.eval/error %)) evaluations)
                "no form was refused")
            (is (= (pr-str (seon.run/complete "refined"))
                   (get-in evaluations [4 :seon.eval/shown]))
                "the run stayed open through the change and completed")))))))

(deftest runtime-schema-unregister-removes-one-unused-global-schema
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)
            schema-key :shared.runtime/unregister-me]
        (with-redefs
          [ai/complete
           (fn [_projection _]
             {:seon.ai/text
              (str
               "(require '[seon.schema :as schema])\n"
               "(schema/register! :shared.runtime/unregister-me "
               "(vector :int {:seon.db/index true}))\n"
               "(schema/unregister! :shared.runtime/unregister-me)\n"
               "(seon.run/complete \"schema removed\")")})]
          (drive! cluster 10)
          (let [db @connection
                evaluations (agent-evaluations db)]
            (is (= (pr-str schema-key) (:seon.eval/shown (nth evaluations 2)))
                "unregister has ordinary REPL return semantics")
            (is (nil? (db/pull db '[*] [:seon.schema/key schema-key]))
                "program-facts section 1f G1 supersedes ruling 47's retained identity")
            (is (string? (db/q '[:find ?form . :in $ ?key :where
                                [?schema :seon.schema/key ?key]
                                [?schema :seon.schema/form ?form]]
                              (db/history db) schema-key)))
            (is (not (contains? (:schema db) schema-key)))
            (is (not (contains?
                      (:seon.schema.projection/forms
                       (schema/projection-from-database db))
                      schema-key))
                "the run-local projection derives absence from db-after")))))))

(deftest refused-runtime-schema-registration-mutates-neither-row-nor-projection
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)
            schema-key :my.agents.agent-a/refused
            global-projection (schema/current-projection)
            global-forms (schema/registered-schemas)]
        (with-redefs
          [ai/complete
           (fn [_projection _]
             {:seon.ai/text
              (str
               "(require '[seon.schema :as schema])\n"
               "(schema/register! ::refused (vector :missing/schema))")})]
          (drive! cluster 10)
          (is (some?
               (db/q '[:find ?error .
                      :where [?receipt :seon.cluster.eval/error ?error]]
                    @connection))
              "the rejected form leaves a terminal error receipt")
          (is (nil? (db/pull @connection [:db/id]
                            [:seon.schema/key schema-key]))
              "a rejected registration leaves no program row")
          (is (not (contains? (:schema @connection) schema-key))
              "a rejected registration installs no Datahike attribute")
          (is (identical? global-projection (schema/current-projection))
              "candidate evaluation never mutates the global projection")
          (is (= global-forms (schema/registered-schemas))
              "candidate evaluation never mutates global declarations"))))))

(deftest runtime-declarations-install-only-from-a-successful-terminal-db-after
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)
            schema-key :my.agents.agent-a/not-committed
            transact! db/transact!
            install! sci.eval/install-row!
            installations (atom [])
            global-forms (schema/registered-schemas)]
        (with-redefs
          [ai/complete
           (fn [_projection _]
             {:seon.ai/text
              (str
               "(require '[seon.schema :as schema])\n"
               "(schema/register! ::not-committed "
               "(vector :int {:seon.db/index true}))")})
           db/transact!
           (fn [target transaction]
             (let [tx-data (if (map? transaction)
                             (:tx-data transaction)
                             transaction)
                   declaration?
                   (some
                    (fn [operation]
                      (and (vector? operation)
                           (= :db.fn/call (first operation))
                           (= #'turn/receipt-settle-call (second operation))
                           (= schema-key
                              (get-in operation
                                      [2 :seon.program/row
                                       :seon.schema/key]))))
                    tx-data)]
               (if declaration?
                 {:seon.db.write.attempt/request-id "declaration-refusal" :seon.error/at (java.util.Date.) :seon.error/layer :seon.db/invocation :seon.error/operation 'seon.db/transact!
                  :seon.error/message "injected declaration refusal"
                  :seon.error/data {:error :transact/schema}}
                 (transact! target transaction))))
           sci.eval/install-row!
           (fn [request]
             (swap! installations conj request)
             (install! request))]
          (drive! cluster 10)
          (is (nil? (db/pull @connection [:db/id]
                            [:seon.schema/key schema-key])))
          (is (not (contains? (:schema @connection) schema-key)))
          (is (not-any? #(= schema-key
                            (get-in % [:seon.program/row
                                       :seon.schema/key]))
                        @installations)
              "a rejected transaction report never reaches installation")
          (is (= global-forms (schema/registered-schemas))
              "a transaction refusal discards the evaluation overlay"))))))

(deftest runtime-tests-install-run-redefine-and-delete-exactly
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)
            test-sym "my.agents.agent-a/versioned-test"]
        (with-redefs [ai/complete
                      (fn [_projection _]
                        {:seon.ai/text
                         (str "(require '[clojure.test :refer [deftest]])\n"
                              "(deftest versioned-test :v1)\n"
                              "((:test (meta (resolve 'versioned-test))))\n"
                              "(deftest versioned-test :v2)\n"
                              "((:test (meta (resolve 'versioned-test))))\n"
                              "(ns-unmap 'my.agents.agent-a 'versioned-test)\n"
                              "(resolve 'versioned-test)")})]
          (drive-agent! cluster "agent-a" 2)
          (let [evaluations (agent-evaluations (db/db connection))]
            (is (= 7 (count evaluations)))
            (is (= ":v1" (:seon.eval/shown (nth evaluations 2))))
            (is (= ":v2" (:seon.eval/shown (nth evaluations 4))))
            (is (= "nil" (:seon.eval/shown (nth evaluations 6))))
            (is (every? #(nil? (:seon.cluster.eval/error %)) evaluations))
            (is (nil? (db/pull (db/db connection) '[*] [:seon.test/sym test-sym]))
                "program-facts section 1f G1 retracts deleted tests too")
            (is (string? (db/q '[:find ?source . :in $ ?symbol :where
                                [?test :seon.test/sym ?symbol]
                                [?test :seon.test/source ?source]]
                              (db/history (db/db connection)) test-sym)))))))))

(deftest incompatible-clusters-alternate-runtime-schema-validation-without-bleed
  (with-cluster
    (fn [cluster-a]
      (let [connection-a (:seon.db/connection cluster-a)
            shared-key :seon.runtime.registration/shared
            a-only-key :seon.runtime.registration/a-only
            global-projection (schema/current-projection)]
        (with-redefs
          [ai/complete
           (fn [_projection _]
             {:seon.ai/text
              (str
               "(require '[seon.schema :as schema])\n"
               "(schema/register! :seon.runtime.registration/shared "
               "(vector :int {:min 0}))\n"
               "(schema/register! :seon.runtime.registration/a-only "
               ":keyword)\n"
               "(seon.run/complete \"cluster A registered\")")})]
          (drive! cluster-a 10))
        (with-cluster
          (fn [cluster-b]
            (let [connection-b (:seon.db/connection cluster-b)]
              (with-redefs
                [ai/complete
                 (fn [_projection _]
                   {:seon.ai/text
                    (str
                     "(require '[seon.schema :as schema])\n"
                     "(schema/register! :seon.runtime.registration/shared "
                     "(vector :string {:min 1}))\n"
                     "(seon.run/complete \"cluster B registered\")")})]
                (drive! cluster-b 10))
              (let [acquire
                    (fn [connection]
                      (:seon.schema/projection
                       (sci.eval/cluster-ctx @connection)))
                    projection-a-1 (acquire connection-a)
                    projection-b (acquire connection-b)
                    projection-a-2 (acquire connection-a)
                    validate-a-1
                    (schema/projection-validator projection-a-1 shared-key)
                    validate-b
                    (schema/projection-validator projection-b shared-key)
                    validate-a-2
                    (schema/projection-validator projection-a-2 shared-key)]
                (is (true? (validate-a-1 7)))
                (is (false? (validate-a-1 "seven")))
                (is (true? (validate-b "seven")))
                (is (false? (validate-b 7)))
                (is (true? (validate-a-2 7))
                    "returning to A preserves A's incompatible declaration")
                (is (false? (validate-a-2 "seven")))
                (is (contains?
                     (:seon.schema.projection/forms projection-a-2)
                     a-only-key))
                (is (not (contains?
                          (:seon.schema.projection/forms projection-b)
                          a-only-key))
                    "an absent key in B never bleeds from A")
                (is (identical? global-projection
                                (schema/current-projection))
                    "A-B-A acquisition never repoints the global registry")))))))))

(deftest accepted-first-party-definition-reaches-an-existing-agent-next-turn
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)
            function-symbol (symbol "seon.eval.drive/uuid-text")
            original (db/pull @connection '[*] [:seon.fn/sym "seon.eval.drive/uuid-text"])
            source "(defn- uuid-text {:malli/schema [:=> [:cat] :string]} [] \"s3-database-definition\")"
            replies (atom [(str source "\n(seon.run/complete \"published\")")
                           "(seon.run/complete (seon.eval.drive/uuid-text))"])]
        (test-support/transacted!
         connection
         (into [[:db/add [:seon.agent/id "agent-a"] :seon.agent/namespace
                 [:seon.ns/name 'seon.eval.drive]]]
               (agent/creation-tx {:seon.cluster/name "turn-test"
                                  :seon.agent/id "s3-peer"
                                  :seon.ns/name 'my.agents.s3-peer})))
        (let [author (agent/acquire-context! cluster "agent-a")
              peer (agent/acquire-context! cluster "s3-peer")]
          (is (some? (sci.core/resolve peer function-symbol)))
        (with-redefs [ai/complete
                      (fn [_projection _]
                        (let [[before _] (swap-vals! replies #(if (seq %) (subvec % 1) %))]
                          {:seon.ai/text (or (first before)
                                            "(seon.run/complete \"finished\")")}))]
          (drive-agent! (assoc cluster :seon.sci.eval/agent-ctx author) "agent-a" 2)
          (let [admitted (db/pull @connection '[*]
                                 [:seon.fn/sym "seon.eval.drive/uuid-text"])]
            (is (= :agent (:seon.schema.admission/source admitted))
                (pr-str (mapv #(select-keys % [:seon.cluster.eval/source
                                               :seon.cluster.eval/error
                                               :seon.eval/shown])
                              (agent-evaluations @connection))))
            (is (= source (:seon.fn/source admitted))))
          (sci.eval/acquire! {:seon.sci.eval/ctx (:seon.sci.eval/ctx cluster)
                             :seon.db/db @connection})
          (agent/acquire-context! cluster "agent-a")
          (is (identical? (#'sci.eval/context-projection (:seon.sci.eval/ctx cluster))
                          (#'sci.eval/context-projection author))
              "the retained handle receives the rebuilt base's contract environment")
          (is (identical? @(sci.core/resolve (:seon.sci.eval/ctx cluster)
                                            function-symbol)
                          @(sci.core/resolve author function-symbol))
              "regeneration does not mistake the author's accepted root for a private def")
          (test-support/transacted!
           connection
           (message/inbound-tx @connection
                               {:seon.agent/id "s3-peer"
                                :seon.message/inbound-content "call the accepted definition"
                                :seon.config.eval.result/max-string 1000}))
          (drive-agent! (assoc cluster :seon.sci.eval/agent-ctx peer) "s3-peer" 2)
          (is (some #(str/includes? % "s3-database-definition")
                    (db/q '[:find [?result ...]
                            :where
                            [?agent :seon.agent/id "s3-peer"]
                            [?turn :seon.turn/agent ?agent]
                            [?evaluation :seon.cluster.eval/run ?turn]
                            [?evaluation :seon.eval/shown ?result]
                            [?evaluation :seon.cluster.eval/author :agent]]
                          @connection))
              "the existing peer evaluates the accepted first-party definition")
          (test-support/transacted! connection [original])
          (sci.eval/acquire! {:seon.sci.eval/ctx (:seon.sci.eval/ctx cluster)
                             :seon.db/db @connection})
          (doseq [agent-id ["agent-a" "s3-peer"]]
            (let [ctx (agent/acquire-context! cluster agent-id)]
              (is (identical? @(ns-resolve 'seon.eval.drive 'uuid-text)
                              @(sci.core/resolve ctx function-symbol))
                  "core restoration reaches both the author and the existing peer")))))))))

(deftest another-agent-sees-a-flat-contract-violation-after-live-install
  (with-cluster
    (fn [cluster]
      (let [cluster (assoc cluster
                           :seon.cluster/name "turn-test")
            connection (:seon.db/connection cluster)
            replies
            (atom
             [(str
               "(defn ^{:malli/schema [:=> [:cat :int] :int]} "
               "strict [x] x)\n"
               "(seon.run/complete \"published\")")
              "(my.agents.agent-a/strict \"wrong\")"])]
        (with-redefs [ai/complete
                      (fn [_projection _]
                        (let [[before _]
                              (swap-vals! replies
                                          #(if (seq %) (subvec % 1) %))]
                          {:seon.ai/text
                           (or (first before)
                               "(seon.run/complete \"recovered\")")}))]
          (drive-agent! cluster "agent-a" 2)
          (test-support/transacted!
                       connection
                       [{:seon.ns/name 'my.agents.agent-b}
                        (assoc (agent-row "agent-b")
                               :seon.agent/namespace
                               [:seon.ns/name 'my.agents.agent-b])
                        {:seon.message/id "m-contract-agent-b" :seon.message/to [:seon.agent/id "agent-b"] :seon.message/content "violate the published contract"}])
          (drive-agent! cluster "agent-b" 2)
          (let [evaluations
                (db/q '[:find [(pull ?evaluation
                                   [:seon.cluster.eval/error
                                    :seon.eval/shown]) ...]
                       :where
                       [?evaluation :seon.cluster.eval/id _]
                       [?evaluation :seon.cluster.eval/error _]]
                     @connection)]
            (is (= 1 (count evaluations)))
            (is (str/includes?
                 (:seon.eval/shown (first evaluations))
                               "my.agents.agent-a/strict")
                "the second agent crossed the one live context-install seam")))))))

(deftest a-refused-definition-stays-in-its-agents-defs
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)
            function-sym "my.agents.agent-a/refused-live"
            agent-ctx (:seon.sci.eval/ctx
                       (sci.eval/fork-for-turn
                        {:seon.sci.eval/ctx (:seon.sci.eval/ctx cluster)
                         :seon.db/db @connection
                         :seon.agent/id "agent-a"}))
            cluster (assoc cluster :seon.sci.eval/agent-ctx agent-ctx)
            replies (atom
                     [(str
                       "(defn ^{:malli/schema [:=> [:cat :int] :int]} "
                       "refused-live [x] (inc x))")
                      (str
                       "(seon.run/complete "
                       "(str (my.agents.agent-a/refused-live 41)))")])
            transact! db/transact!]
        (with-redefs
          [ai/complete
           (fn [_projection _]
             {:seon.ai/text
              (let [reply (first @replies)]
                (swap! replies subvec 1)
                reply)})
           db/transact!
           (fn [target transaction]
             (let [tx-data (if (map? transaction)
                             (:tx-data transaction)
                             transaction)
                   refused-definition?
                   (some
                    (fn [operation]
                      (and (vector? operation)
                           (= :db.fn/call (first operation))
                           (= #'turn/receipt-settle-call (second operation))
                           (= function-sym
                              (get-in operation
                                      [2 :seon.program/row
                                       :seon.fn/sym]))))
                    tx-data)]
               (if refused-definition?
                 {:seon.db.write.attempt/request-id "definition-refusal" :seon.error/at (java.util.Date.) :seon.error/layer :seon.db/invocation :seon.error/operation 'seon.db/transact!
                  :seon.error/message "injected definition refusal"
                  :seon.error/data {:error :transact/program}}
                 (transact! target transaction))))]
          (drive-agent! cluster "agent-a" 2)
          (is (nil? (:seon.fn/source
                     (db/pull @connection [:seon.fn/source]
                              [:seon.fn/sym function-sym])))
              "the refused transaction persists no shared definition")
          (is (= 42 ((deref (sci.core/resolve agent-ctx (symbol function-sym))) 41))
              "the actual executable definition remains in the agent context")
          (is (nil? (sci.core/resolve (:seon.sci.eval/ctx cluster)
                                     (symbol function-sym)))
              "the refused definition never enters the shared base")
          (test-support/transacted!
                       connection
                       [{:seon.message/id "m-agent-a-after-refusal" :seon.message/to [:seon.agent/id "agent-a"] :seon.message/content "call the refused definition"}])
          (drive-agent! cluster "agent-a" 2)
          (is (= (pr-str (seon.run/complete "42"))
                 (:seon.eval/shown (last (agent-evaluations @connection))))
              "the next turn calls the same private definition"))))))

(deftest acquisition-orders-agent-authored-refer-targets-and-ignores-alias-cycles
  (test-support/with-database
    (fn [connection]
      (let [target-source
            "(defn ^{:malli/schema [:=> [:cat :int] :int]} increment [x] (inc x))"
            consumer-source
            "(defn ^{:malli/schema [:=> [:cat :int] :int]} call-plus [x] (plus (target/increment x)))"
            namespace-result
            (db/transact!
             connection
             [{:seon.ns/name 'authored.target
               :seon.schema.admission/source :agent
               :seon.ns/source "(ns authored.target)"}
              {:seon.ns/name 'authored.consumer
               :seon.schema.admission/source :agent
               :seon.ns/source "(ns authored.consumer)"
               :seon.ns/requires [[:seon.ns/name 'authored.target]]
               :seon.ns/aliases
               [{:seon.ns.alias/local 'target
                 :seon.ns.alias/target-ns 'authored.target}]
               :seon.ns/refers
               [{:seon.ns.refer/local 'plus
                 :seon.ns.refer/target-ns 'authored.target
                 :seon.ns.refer/target-name 'increment}]}
              {:seon.ns/name 'alias.cycle-a
               :seon.schema.admission/source :agent
               :seon.ns/source "(ns alias.cycle-a)"
               :seon.ns/aliases
               [{:seon.ns.alias/local 'b
                 :seon.ns.alias/target-ns 'alias.cycle-b}
                {:seon.ns.alias/local 'ghost
                 :seon.ns.alias/target-ns 'not.loaded}]}
              {:seon.ns/name 'alias.cycle-b
               :seon.schema.admission/source :agent
               :seon.ns/source "(ns alias.cycle-b)"
               :seon.ns/aliases
               [{:seon.ns.alias/local 'a
                 :seon.ns.alias/target-ns 'alias.cycle-a}]}])
            function-result
            (db/transact!
             connection
             [{:seon.fn/sym "authored.target/increment"
               :seon.schema.admission/source :agent
               :seon.fn/ns [:seon.ns/name 'authored.target]
               :seon.fn/source target-source
               :seon.fn/arglists "([x])"
               :seon.fn/private? false
               :seon.fn/spec "[:=> [:cat :int] :int]"}
              {:seon.fn/sym "authored.consumer/call-plus"
               :seon.schema.admission/source :agent
               :seon.fn/ns [:seon.ns/name 'authored.consumer]
               :seon.fn/source consumer-source
               :seon.fn/arglists "([x])"
               :seon.fn/private? false
               :seon.fn/spec "[:=> [:cat :int] :int]"}])
            database (db/db connection)
            ctx (sci.eval/build-base-ctx (seon.schema/handed-projection))
            acquired
            (sci.eval/acquire!
             {:seon.sci.eval/ctx ctx
              :seon.db/db database
              :seon.schema/projection (db/carried-projection database)})]
        (is (some? (:db-after namespace-result)))
        (is (some? (:db-after function-result)))
        (is (= 43 (sci.core/eval-string* ctx "(authored.consumer/call-plus 41)")))
        (is (= 'authored.target/increment
               (get-in (sci.core/namespace-bindings ctx 'authored.consumer)
                       [:refers 'plus])))
        (is (= 'alias.cycle-b
               (get-in (sci.core/namespace-bindings ctx 'alias.cycle-a)
                       [:aliases 'b]))
            "alias-only cycles do not become acquisition dependencies")
        (is (= 'not.loaded
               (get-in (sci.core/namespace-bindings ctx 'alias.cycle-a)
                       [:aliases 'ghost]))
            "an as-alias target need not be loaded")
        (is (= 6 (:seon.sci.eval/installed acquired)))))))

(deftest a-lost-model-call-leaves-a-durable-readable-reason
  ;; the drive sat claimed-with-no-plan for 120 s and the operator had to
  ;; reproduce the call by hand to learn it was a missing credential.
  ;; The reason is now a fact, and the next prompt says it.
  ;; REAL SCI, deliberately: the stand-in evaluator replaces every
  ;; generated opening read's value with 1, so a prompt assembled under it
  ;; could never carry a read's actual answer. The subject here IS what the
  ;; next prompt says.
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)]
        (with-redefs [ai/complete
                      (fn [_projection _] {:seon.ai/missing-credential-variable "DEEPSEEK_API_KEY" :seon.error/at (java.util.Date.) :seon.error/layer :seon.ai/invocation :seon.error/operation 'seon.ai/complete
                               :seon.error/message
                               "The environment variable DEEPSEEK_API_KEY is not set."
                               :seon.error/data {}})]
          (drive! cluster 4))
        (testing "the run closed rather than sitting claimed"
          (is (some? (db/q '[:find ?c . :where
                            [_ :seon.turn/closed-tx ?c]] @connection)))
          )
        (testing "and WHY is readable from the database"
          ;; THE OCCURRENCE CARRIES THE MESSAGE. The error identity groups
          ;; by signature and kind; every per-firing fact — message, time,
          ;; process, agent, turn — belongs to the occurrence the writer
          ;; commits (`seon.error/latest-fact` projects it back).
          (is (re-find #"DEEPSEEK_API_KEY"
                       (db/q '[:find ?message .
                               :where
                               [?occurrence :seon.error.occurrence/turn _]
                               [?occurrence :seon.error.occurrence/message ?message]]
                             @connection))))
        (testing "so the agent's next prompt tells it what happened"
          ;; THE LOOP'S OWN PATH TO THE NEXT PROMPT. A terminal provider
          ;; refusal defers reopening until a new outside wake, so the next
          ;; prompt exists only after one arrives; and the prompt the agent
          ;; actually sees is the one the provider call carries. Between the
          ;; two, the system turn re-evaluates every declared read whose
          ;; answer changed — the fault read is one of them.
          (let [prompts (atom [])]
            (let [seeded
                  (db/transact! connection
                                [{:seon.message/id "m-2"
                                  :seon.message/to [:seon.agent/id "agent-a"]
                                  :seon.message/content "try again"}])]
              (is (some? (:db-after seeded))
                  "the wake that reopens the agent must commit"))
            (with-redefs [ai/complete
                          (fn [_projection request]
                            (swap! prompts conj (:seon.ai/prompt request))
                            {:seon.ai/text "(seon.run/complete \"retried\")"})]
              (drive! cluster 6))
            (is (seq @prompts) "the new wake reopened the agent")
            (is (some #(re-find #"DEEPSEEK_API_KEY" %) @prompts)
                "the agent reads the reason its own call was lost")))))))

(deftest a-real-evaluation-that-runs-away-is-stopped-and-recorded
  ;; the loop's honest failure path, end to end: an agent writes an
  ;; infinite loop, the boundary stops it, and the evaluation says so
  (with-cluster
    (fn [cluster]
      (let [cluster (assoc cluster
                           ;; A bounded execution for the deliberately nonterminating source.
                           :seon.config.eval/time-limit-ms 300)
            connection (:seon.db/connection cluster)]
        (with-redefs [ai/complete
                      (fn [_projection _] {:seon.ai/text "(loop [] (recur))"})]
          (drive-agent! cluster "agent-a" 2)
          (is (= 1 (count (db/q '[:find [?at ...] :where
                                 [_ :seon.cluster.eval/interrupted-at ?at]]
                               @connection)))
              "the evaluation records its cut instant, and the fold moved on")
          (is (re-find #"(?i)time"
                       (db/q '[:find ?error . :where
                              [_ :seon.cluster.eval/error ?error]]
                            @connection))))))))

(deftest a-red-form-routes-to-its-namespace-owner-and-the-fold-continues
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)
            route-run "route-run"
            ;; A genuinely red form: SCI throws, and ruling 67/68 make that
            ;; a flat error result on the eval — never a routed problem.
            unbound-source "(inc \"x\")"]
        (test-support/transacted!
                     connection
                     [{:seon.ns/name 'my.gen.planner}
                      {:seon.ns/name 'my.gen.alpha}
                      {:seon.agent/id "agent-b"
                       :seon.agent/namespace [:seon.ns/name 'my.gen.alpha]}
                      {:seon.agent/id "agent-a"
                       :seon.agent/namespace [:seon.ns/name 'my.gen.planner]}
                      {:seon.message/id "route-goal" :seon.message/to [:seon.agent/id "agent-a"] :seon.message/content "Generate the program."}])
        (test-support/transacted!
                     connection
                     [{:seon.turn/id route-run :seon.turn/agent [:seon.agent/id "agent-a"] :seon.turn/trigger [:seon.message/id "route-goal"] :seon.turn/opened-tx "datomic.tx"}])
        (test-support/transacted!
                     connection
                     (into
                      ;; ONE ENTITY PER (run, ordinal): the frozen source and namespace
                      ;; ride the evaluation the start mints. There is no twin row to
                      ;; assert beside it.
                      [{:seon.agent/id "agent-a"
                        }]
                      cat
                      [(turn/receipt-start-tx
                        {:seon.turn/id route-run
                         :seon.cluster.eval/ordinal 0
                         :seon.cluster.eval/at now
                         :seon.cluster.eval/source unbound-source
                         :seon.cluster.eval/ns [:seon.ns/name 'my.gen.alpha]})
                       (turn/receipt-start-tx
                        {:seon.turn/id route-run
                         :seon.cluster.eval/ordinal 1
                         :seon.cluster.eval/at now
                         :seon.cluster.eval/source "42"
                         :seon.cluster.eval/ns [:seon.ns/name 'my.gen.alpha]})]))
        (let [report
              (turn/turn
               {:seon.turn.loop/cluster cluster
                :seon.turn.work/next
                {:seon.turn.work/situation :resume
                 :seon.turn/id route-run
                 :seon.agent/id "agent-a"
                 :seon.cluster.eval/ordinal 0}}
               now)
              receipts
              (->> (db/q '[:find [(pull ?receipt [*]) ...]
                          :in $ ?run-id
                          :where
                          [?run :seon.turn/id ?run-id]
                          [?receipt :seon.cluster.eval/run ?run]]
                        @connection route-run)
                   (sort-by :seon.cluster.eval/ordinal))]
          (is (= 2 (:seon.turn.loop/forms-run report))
              "the batch evaluated the sibling after the red form")
          (is (string? (:seon.cluster.eval/error (first receipts)))
              "the red form settles as a flat error on its own eval")
          (is (= "42" (:seon.eval/shown (second receipts)))
              "the sibling in the other namespace still ran and settled")
          (is (empty?
               (db/q '[:find ?assignment
                      :where
                      [?assignment :seon.message/assignment _]]
                    @connection))
              "no message is written on the agent's behalf: an error is a
               value the agent sees; routing it to an owner is a later
               capability (owner, 2026-09-05), never an automatic wake")
          (is (= 1
                 (db/q '[:find (count ?tx) .
                        :in $ ?run-id
                        :where
                        [?run :seon.turn/id ?run-id]
                        [?receipt :seon.cluster.eval/run ?run]
                        [?receipt :seon.cluster.eval/error _ ?tx]]
                      @connection route-run))
              "the whole batch settles in one transaction"))))))

(deftest a-whole-turn-runs-from-trigger-to-closed-run
  (with-cluster
    (fn [cluster]
      (with-redefs [ai/complete
                    (fn [_projection _] {:seon.ai/text
                             "(+ 1 1)\n(seon.run/complete \"two widgets\")"})]
        (let [connection (:seon.db/connection cluster)
              reports (drive-agent! cluster "agent-a" 2)
              database (db/db connection)
              evaluations (agent-evaluations database)]
          (is (empty? (turn/unanswered-triggers database "agent-a")))
          (is (= [:open :call] (mapv :seon.turn.work/situation reports)))
          (is (= 2 (count evaluations)))
          (is (= "2" (:seon.eval/shown (first evaluations))))
          (is (str/includes? (:seon.eval/shown (second evaluations)) "two widgets"))
          (is (nil? (turn/next-agent-work database (request connection)))))))))


(deftest an-unreadable-reply-is-a-settled-form-with-paid-attempt-evidence
  (doseq [[reply-text hint]
          [["{:a 1 :b}" "even number"]
           ["I explained the result without another form."
            "prose"]]]
    (with-cluster
      (fn [cluster]
        (let [connection (:seon.db/connection cluster)
              usage {"prompt_tokens" 106 "completion_tokens" 12}]
          (with-redefs [ai/complete
                        (fn [_projection _] {:seon.ai/text reply-text
                                 :seon.ai/usage usage
                                 :seon.ai/finish-reason "stop"})]
            (is (= [:open :call :close]
                   (mapv :seon.turn.work/situation
                         (drive-agent! cluster "agent-a" 3)))))
          (let [database (db/db connection)
                evaluations (agent-evaluations database)
                evaluation (first evaluations)
                turn (db/pull database '[* {:seon.turn/attempts [*]}]
                              (:db/id (:seon.cluster.eval/run evaluation)))
                [attempt :as attempts] (:seon.turn/attempts turn)
                rendered
                (transcript/render-ai
                 {:seon.db/db database
                  :seon.agent/id "agent-a"
                  :seon.sci.eval/ctx (:seon.sci.eval/ctx cluster)
                  :seon.sci.eval/time-limit-ms 2000
                  :seon.config/on-core-error :panic
                  :seon.sci.admit/caps (:seon.sci.admit/caps cluster)})]
            (is (= 1 (count evaluations))
                "every rejected reply leaves one terminal evaluation")
            (is (= reply-text (:seon.cluster.eval/source evaluation)))
            (is (str/includes? (:seon.cluster.eval/error evaluation) hint))
            (is (str/includes? (:seon.eval/shown evaluation)
                               (:seon.cluster.eval/error evaluation)))
            (is (some? (:seon.turn/closed-tx turn)))
            (is (= 1 (count attempts)))
            (is (= usage (edn/read-string (:seon.ai.attempt/usage-edn attempt))))
            (is (= "stop" (:seon.ai.attempt/finish-reason attempt)))
            (is (not (contains? attempt :seon.ai.attempt/error))
                "the provider succeeded; interpreting its reply failed")
            (is (empty? (db/q '[:find ?error :where [?error :seon.error/id _]] database))
                "an agent reader error is not a core fault")
            (is (str/includes? rendered reply-text))
            (is (str/includes? rendered (:seon.cluster.eval/error evaluation))
                "the saved diagnostic reaches the agent history for correction")))))))

(deftest a-prose-only-provider-reply-settles-without-parking-the-turn-proc
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)
            reports (atom [])
            reply-text "I explained the result without another form."
            completion (async/chan 1)
            faults (async/chan 1)]
        (with-open [executor (java.util.concurrent.Executors/newVirtualThreadPerTaskExecutor)]
          (let [handle (assoc cluster
                              :seon.flow/executor executor
                              :seon.turn.loop/completion completion
                              :seon.agent/fault-channel faults
                              :seon.agent/turn-backstop-state (atom nil))]
            (test-support/transacted!
             connection
             (turn/open-tx {:seon.turn/id "prose-only"
                            :seon.turn/agent [:seon.agent/id "agent-a"]
                            :seon.turn/trigger [:seon.message/id "m-1"]
                            :seon.turn/opened-tx "datomic.tx"}))
            (is (false? (contains? (:schema (db/db connection))
                                   (keyword "seon.error" "kind"))))
            (d/listen connection ::prose-only #(swap! reports conj %))
            (async/offer! completion :seon.agent/ready)
            (try
              (with-redefs [ai/complete
                            (fn [_projection _request]
                              {:seon.ai/text reply-text
                               :seon.ai/finish-reason "stop"})]
                (let [[state report]
                      (turn/step {:seon.agent/id "agent-a"
                                  :seon.turn.loop/cluster handle}
                                 :seon.agent/episode :seon.agent/wake)
                      database (db/db connection)
                      evaluation (first (agent-evaluations database))]
                  (is (int? (:db/id evaluation)) (pr-str report))
                  (is (= reply-text (:seon.cluster.eval/source evaluation)))
                  (is (str/includes? (or (:seon.cluster.eval/error evaluation) "")
                                     "no form"))
                  (is (seq @reports))
                  (is (every? (fn [transaction]
                                (every? #(contains? (:schema (:db-after transaction)) (:a %))
                                        (:tx-data transaction)))
                              @reports))
                  (is (nil? (:seon.turn.loop/parked state)))
                  (is (zero? (:seon.turn.loop/write-refusals state 0)))
                  (is (nil? (async/poll! faults)))))
              (finally
                (d/unlisten connection ::prose-only)
                (async/close! completion)
                (async/close! faults)
                (.shutdownNow executor)))))))))

(deftest a-completing-disposition-closes-in-the-terminal-transaction
  (with-cluster fake-evaluate
    (fn [cluster]
      (with-redefs [ai/complete
                    (fn [_projection _] {:seon.ai/text "(seon.run/complete \"done\")"})]
        (with-redefs [injected-evaluation {:seon.cluster.eval/result-edn
                               (pr-str (seon.run/complete "done"))
                               :seon.sci.admit/value (seon.run/complete "done")}]
          (let [connection (:seon.db/connection cluster)
                reports (drive! cluster 10)]
            (is (= [:open :call]
                   (mapv :seon.turn.work/situation reports))
                "no separate close pass — the disposition closed it")
            (is (some? (db/q '[:find ?closed .
                              :where [_ :seon.turn/closed-tx ?closed]]
                            @connection))
                "the run closed in the SAME transaction as its receipt")))))))

(deftest a-combined-evaluation-projects-every-terminal-receipt-datom
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)
            installed (atom [])
            install-row! sci.eval/install-row!
            schema-key :shared.runtime/combined
            declaration (str "(seon.schema/register! :shared.runtime/combined "
                             "(do (println \"combined output\") :string))")
            source (str declaration "\n(seon.run/complete \"combined evaluation\")")]
        (with-redefs [ai/complete (fn [_projection _] {:seon.ai/text source})
                      sci.eval/install-row!
                      (fn [request]
                        (let [result (install-row! request)]
                          (swap! installed conj request)
                          result))]
          (is (= [:open :call]
                 (mapv :seon.turn.work/situation
                       (drive-agent! cluster "agent-a" 2))))
          (let [database (db/db connection)
                evaluations (agent-evaluations database)
                evaluation (first evaluations)
                terminal-txs
                (db/q '[:find [?tx ...]
                        :in $ ?evaluation ?schema-key
                        :where
                        [?evaluation :seon.eval/shown _ ?tx]
                        [?evaluation :seon.cluster.eval/output _ ?tx]
                        [?evaluation :seon.cluster.eval/run ?turn]
                        [?turn :seon.turn/closed-tx _ ?tx]
                        [?schema :seon.schema/key ?schema-key]
                        [?schema :seon.schema/form _ ?tx]]
                      database (:db/id evaluation) schema-key)
                schema-installations
                (filter #(= schema-key
                            (get-in % [:seon.program/row :seon.schema/key]))
                        @installed)]
            (is (= 2 (count evaluations)))
            (is (= declaration (:seon.cluster.eval/source evaluation)))
            (is (= ":shared.runtime/combined" (:seon.eval/shown evaluation)))
            (is (str/includes? (:seon.eval/shown (second evaluations)) "combined evaluation"))
            (is (= "combined output" (str/trim (:seon.cluster.eval/output evaluation))))
            (is (nil? (:seon.cluster.eval/error evaluation)))
            (is (= 1 (count terminal-txs))
                "shown text, output, declaration and turn closure share one commit")
            (is (= 1 (count schema-installations))
                "the real installer receives the committed schema exactly once")
            (is (= ":string"
                   (:seon.schema/form
                    (db/pull database [:seon.schema/form]
                             [:seon.schema/key schema-key]))))))))))

(deftest a-waiting-disposition-frees-the-agent-and-keeps-its-note
  ;; REVISED TWICE, each time toward one commit. First: a wait used to
  ;; leave the run open forever because the close refused (the measured
  ;; `:close` livelock — twelve passes, nine error facts). Then the F1
  ;; seal folded in the ruled `my.turn/wait` revision (README
  ;; owner-decisions #4): the wait's terminal transaction settles the
  ;; receipt AND closes the run in ONE commit, so the
  ;; unheld-open-planned intermediate state — the P1 feeder — never
  ;; exists at any basis and no separate `:close` pass runs at all.
  ;; Nothing could ever have resumed that run: its plan was fully
  ;; executed, and what resumes is the AGENT, on its next trigger.
  (with-cluster fake-evaluate
    (fn [cluster]
      (with-redefs [ai/complete
                    (fn [_projection _] {:seon.ai/text "(seon.run/wait \"need input\")"})]
        (with-redefs [injected-evaluation {:seon.eval/shown
                               (pr-str (seon.run/wait "need input"))
                               :seon.sci.admit/value (seon.run/wait "need input")}]
          (let [connection (:seon.db/connection cluster)
                reports (drive! cluster 12)]
            (is (= [:open :call]
                   (mapv :seon.turn.work/situation reports))
                "three passes and then IDLE — no separate close pass")
            (is (= [:released :closed]
                   (mapv :seon.turn.loop/outcome reports)))
            (is (nil? (turn/next-agent-work @connection (request connection)))
                "and nothing is derivable afterwards: no spin")
            (is (empty? (db/q '[:find ?e :where [?e :seon.error/id _]]
                             @connection))
                "no error facts — the old path committed one per pass")
            (is (nil? (db/q '[:find ?a . :where
                             [?turn :seon.turn/agent ?a]
                             (not [?turn :seon.turn/closed-tx])] @connection))
                "the agent has no open turn, so another wake can open one")
            (is (str/includes?
                 (db/q '[:find ?edn . :where
                        [_ :seon.eval/shown ?edn]] @connection)
                 "need input")
                "and the note survives in the receipt, which is what the
                 next prompt reads it back out of")))))))

;;; ---------------------------------------------------------------------------
;;; Failover, backoff, and the attempt chain
;;; ---------------------------------------------------------------------------

;;; EVERY ONE OF THESE COUNTS. "Nothing re-calls a request that may have
;;; been transmitted" is not a claim a test can inspect for — it is a
;;; NUMBER, and the number is asserted twice: once as calls this process
;;; made, and once as durable rows the database can be asked about
;;; afterwards without any logs.

(def ^:private backup-target
  {:seon.ai/endpoint "http://127.0.0.1:2/v1"
   :seon.ai/model "backup-probe"
   :seon.ai/api-key-variable "SEON_AI_TEST_BACKUP_KEY"
   :seon.ai/timeout-ms 200})

(defn- configure-backup!
  {:malli/schema [:=> [:cat :seon.db/connection] :seon.db/transaction-report]}
  [connection]
  (let [report
        (db/transact!
         connection
         (mapv (fn [[attribute value]]
                 [:db/add [:seon.config/cluster "turn-test"] attribute value])
               {:seon.config.ai.backup/endpoint (:seon.ai/endpoint backup-target)
                :seon.config.ai.backup/model (:seon.ai/model backup-target)
                :seon.config.ai.backup/api-key-variable
                (:seon.ai/api-key-variable backup-target)
                :seon.config.ai.backup/timeout-ms (:seon.ai/timeout-ms backup-target)}))]
    (when (:seon.db.write.attempt/request-id report)
      (throw (ex-info "The fixture backup configuration was refused." report)))
    report))

(defn- failure
  "One model failure value carrying the evidence the leaf would record."
  {:malli/schema [:=> [:cat :map :map] :seon.ai/completion]}
  [members evidence]
  (merge {:seon.error/at now
          :seon.error/layer :seon.ai/request
          :seon.error/operation 'seon.ai/complete
          :seon.error/message "probe provider refusal"
          :seon.error/data evidence}
         members))

(def ^:private unpaid
  "A connection the JDK PROVED never left this machine — the one case
  the no-retry ruling leaves open."
  (failure {:seon.ai/transport-failure "https://provider.invalid"}
           {:seon.ai/error-class :transport-before-send
            :seon.ai/request-transmitted? false
            :seon.ai/response-started? false
            :seon.ai/output-observed? false}))

(defn- recording-completer
  "A stub `ai/complete` that records every request and answers in order.
  The recorded vector is the countable attempt log: one entry is one
  request built, which is one call made."
  [requests answers]
  (fn [_projection request]
    (let [index (count @requests)]
      (swap! requests conj request)
      (nth answers index (last answers)))))

(defn- attempt-rows
  "Every attempt this database recorded, in chain order."
  [db]
  (->> (db/q '[:find [?attempt ...]
              :where [?attempt :seon.ai.attempt/ordinal _]]
            db)
       (map #(db/pull db '[*] %))
       (sort-by :seon.ai.attempt/ordinal)
       vec))

(defn- durable-fact
  "Acquire occurrence evidence and project the committed diagnostic."
  [database error-ref]
  (let [fact (error/latest-fact
              (db/pull database
                       '[* {:seon.error/fn [:seon.fn/sym]
                            :seon.error/occurrences
                            [* {:seon.error.occurrence/turn [:seon.turn/id]
                                :seon.error.occurrence/agent [:seon.agent/id]}]}]
                       error-ref))]
    (cond-> (dissoc fact :db/id)
      (:seon.error/run fact)
      (assoc :seon.error/run
             [:seon.turn/id (get-in fact [:seon.error/run :seon.turn/id])])
      (:seon.error/agent fact)
      (assoc :seon.error/agent
             [:seon.agent/id (get-in fact [:seon.error/agent :seon.agent/id])]))))

(defn- derived-disposition
  "Re-derive what the loop decided, from durable facts alone.
  The attempt's error fact carries the evidence, and the backup role is
  the `failover-from` connection — a stored disposition would only
  restate this derivation (owner ruling 2026-07-28)."
  [db row backup-configured?]
  (let [fact (durable-fact db (:db/id (:seon.ai.attempt/error row)))
        value (semantic-result (:seon.error/data-edn fact))]
    (ai/disposition
     {:seon.error/value value
      :seon.ai/backup? (boolean
                        (and backup-configured?
                             (not (contains? row
                                             :seon.ai.attempt/failover-from))))})))

(deftest one-successful-call-leaves-exactly-one-attempt-fact
  (with-cluster fake-evaluate
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)
            requests (atom [])]
        (is (nil? (:seon.config.ai.backup/model
                   (config/effective (db/db connection) "turn-test")))
            "the fixture reconciles its explicit backup absence against seeded defaults")
        (with-redefs [ai/complete
                      (recording-completer
                       requests
                       [{:seon.ai/text "(seon.run/complete \"one\")"}])]
          (with-redefs [injected-evaluation {:seon.cluster.eval/result-edn
                                  (pr-str (seon.run/complete "one"))
                                  :seon.sci.admit/value (seon.run/complete "one")}]
            (drive! cluster 10)))
        (is (= 1 (count @requests)) "one request built, so one call made")
        (let [[row :as rows] (attempt-rows @connection)]
          (is (= 1 (count rows)))
          (is (.equals "probe" (:seon.ai/model row)))
          (is (not (contains? row :seon.ai.attempt/error))
              "no error ref — the ref's presence IS the outcome, and a
               success simply has none")
          (is (not (contains? row :seon.ai.attempt/failover-from))
              "nothing failed over, so nothing points anywhere")
          (is (not (contains? row :seon.ai.attempt/usage-edn))
              "provider usage remains absent when the provider omitted it")
          (is (not (contains? row :seon.ai/disposition))
              "and no disposition is stored on any row — it is derived
               at read from the durable evidence"))
        (is (nil? (db/q '[:find ?e . :where [?e :seon.error/id _]] @connection))
            "and a call that worked committed no error fact")))))

(deftest successful-call-persists-the-providers-open-usage-document
  (doseq [retain? [false true]]
    (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)
            requests (atom [])
            usage {"prompt_tokens" 31
                   "completion_tokens" 7
                   "prompt_cache_hit_tokens" 23
                   "prompt_cache_miss_tokens" 8
                   "prompt_tokens_details" {"cached_tokens" 23}}]
        (when retain?
          (is (some? (:db-after
                      (db/transact! connection
                                    [{:seon.agent/id "agent-a"
                                      :seon.agent/settings
                                      {:seon.config.ai/retain-reasoning true}}])))))
        (with-redefs [ai/complete
                      (recording-completer
                       requests
                       [{:seon.ai/text "(seon.run/complete \"one\")"
                         :seon.ai/reasoning-content "private reasoning"
                         :seon.ai/usage usage
                         :seon.ai/finish-reason "stop"}])]
          (drive-agent! cluster "agent-a" 2))
        (let [[row :as rows] (attempt-rows @connection)]
          (is (= 1 (count rows)))
          (is (= usage
                 (edn/read-string (:seon.ai.attempt/usage-edn row)))
              "the provider-owned map survives the database round trip")
          (is (= 23
                 (:seon.ai.usage/cached-tokens
                  (ai/normalize-usage
                   (edn/read-string (:seon.ai.attempt/usage-edn row)))))
              "DeepSeek's cache-hit tokens normalize from the stored document")
          (is (if retain?
                (= "private reasoning" (:seon.ai.attempt/reasoning row))
                (not (contains? row :seon.ai.attempt/reasoning)))
              "settled reasoning reaches the same durable attempt row")
          (is (= "stop" (:seon.ai.attempt/finish-reason row))
              "finish reason is its own fact, never inserted into usage")))))))

(deftest a-partial-stream-truncation-is-a-durable-nonfailure-attempt-fact
  (with-cluster fake-evaluate
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)
            requests (atom [])
            truncation
            {:seon.error/at now
             :seon.error/layer :seon.ai/request
             :seon.error/operation 'seon.ai/truncation
             :seon.ai/interrupted-text-count 12
             :seon.error/message
             "The provider stream ended after 12 characters."
             :seon.error/data
             {:seon.ai/text-received 12
              :seon.ai/thread-interrupted? false
              :seon.ai/cause-chain
              ["java.io.IOException: closed"
               "java.io.IOException: connection reset by peer"]}}]
        (with-redefs [ai/complete
                      (recording-completer
                       requests
                       [{:seon.ai/text "(seon.run/complete \"partial\")"
                         :seon.ai/truncation truncation}])]
          (with-redefs [injected-evaluation
                        {:seon.cluster.eval/result-edn
                         (pr-str (seon.run/complete "partial"))
                         :seon.sci.admit/value
                         (seon.run/complete "partial")}]
            (drive! cluster 10)))
        (let [database @connection
              attempt-id
              (db/q '[:find ?attempt-id .
                      :where
                      [?attempt :seon.ai.attempt/id ?attempt-id]
                      [?attempt :seon.ai.attempt/truncation]]
                    database)
              truncation-id
              (db/q '[:find ?truncation-id .
                      :in $ ?attempt-id
                      :where
                      [?attempt :seon.ai.attempt/id ?attempt-id]
                      [?attempt :seon.ai.attempt/truncation ?truncation]
                      [?truncation :seon.error/id ?truncation-id]]
                    database attempt-id)
              row (db/pull database '[*]
                           [:seon.ai.attempt/id attempt-id])
              fact (durable-fact database [:seon.error/id truncation-id])]
          (is (= 1 (count @requests)))
          (is (string? attempt-id)
              "the query finds the attempt by truncation-fact presence")
          (is (string? truncation-id)
              "the attempt ref resolves to a durable error fact")
          (is (= truncation
                 (semantic-result (:seon.error/data-edn fact)))
              "the durable fact retains why and where the stream ended")
          (is (not (contains? row :seon.ai.attempt/error))
              "partial output remains an ordinary completion, not a failure")
          (is (some? (db/q '[:find ?digest .
                            :where
                            [?run :seon.turn/reply-size ?digest]]
                          database))
              "the already-arrived completion still settles as the plan"))))))

(deftest reasoning-starvation-persists-usage-finish-and-the-named-error
  (doseq [retain? [false true]]
    (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)
            requests (atom [])
            usage {"prompt_tokens" 104
                   "completion_tokens" 8
                   "completion_tokens_details" {"reasoning_tokens" 8}}
            failure {:seon.ai/exhausted-finish-reason "length" :seon.error/at (java.util.Date.) :seon.error/layer :seon.ai/invocation :seon.error/operation 'seon.ai/complete
                     :seon.error/message
                     "The provider exhausted the completion budget before replying."
                     :seon.error/data
                     {:seon.ai/finish-reason "length"
                      :seon.ai/usage usage
                      :seon.ai/reasoning-content "all reasoning"
                      :seon.ai/error-class :response
                      :seon.ai/request-transmitted? true
                      :seon.ai/response-started? true
                      :seon.ai/output-observed? true}}]
        (when retain?
          (is (some? (:db-after
                      (db/transact! connection
                                    [{:seon.agent/id "agent-a"
                                      :seon.agent/settings
                                      {:seon.config.ai/retain-reasoning true}}])))))
        (with-redefs [ai/complete (recording-completer requests [failure])]
          (drive-agent! cluster "agent-a" 2))
        (let [[row :as rows] (attempt-rows @connection)
              error-fact (db/pull @connection '[*]
                                 (:db/id (:seon.ai.attempt/error row)))]
          (is (= 1 (count rows)))
          (is (= usage
                 (edn/read-string (:seon.ai.attempt/usage-edn row))))
          (is (= "length" (:seon.ai.attempt/finish-reason row)))
          (is (if retain?
                (= "all reasoning" (:seon.ai.attempt/reasoning row))
                (not (contains? row :seon.ai.attempt/reasoning)))
              "reasoning-only starvation still persists the settled trace")
          (is (string? (:seon.ai/exhausted-finish-reason error-fact))
              "the attempt points at the named starvation error fact")))))))

(deftest reasoning-only-time-limit-persists-its-flat-diagnostic
  (with-cluster fake-evaluate
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)
            requests (atom [])
            sent-body
            "{\"thinking\":{\"type\":\"disabled\"},\"stream\":true}"
            failure
            {
    :seon.ai/interrupted-text-count 0
    :seon.error/at (java.util.Date.)
    :seon.error/layer :seon.ai/completion
    :seon.error/operation 'seon.ai/complete
             :seon.error/message
             (str "The provider streamed 8 characters of reasoning but no "
                  "assistant text before the configured time limit fired.")
             :seon.error/data
             {:seon.ai/reasoning-received 8
              :seon.ai/text-received 0
              :seon.ai/cause-chain
              ["java.io.IOException: closed"
               "java.net.http.HttpTimeoutException: request timed out"]
              :seon.ai/error-class :response
              :seon.ai/http-status 200
              :seon.ai/request-transmitted? true
              :seon.ai/response-started? true
              :seon.ai/output-observed? true}
             :seon.ai.attempt/sent-body sent-body}]
        (with-redefs [ai/complete (recording-completer requests [failure])]
          (drive! cluster 10))
        (let [[row :as rows] (attempt-rows @connection)
              error-fact (durable-fact @connection (:db/id (:seon.ai.attempt/error row)))
              recorded (semantic-result (:seon.error/data-edn error-fact))]
          (is (= 1 (count @requests)))
          (is (= 1 (count rows)))
          (is (nil? (:seon.ai.attempt/sent-body row)))
          (is (true? (:seon.ai/output-observed? row)))
          (is (nat-int? (:seon.ai/interrupted-text-count error-fact)))
          (is (= 8 (get-in recorded
                           [:seon.error/data :seon.ai/reasoning-received])))
          (is (zero? (get-in recorded
                             [:seon.error/data :seon.ai/text-received])))
          (is (str/includes? (:seon.error/message recorded)
                             "no assistant text"))
          (is (str/includes? (:seon.error/message recorded)
                             "configured time limit fired")))))))

(deftest an-unpaid-failure-with-a-backup-makes-exactly-two-calls
  (with-cluster fake-evaluate
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)
            requests (atom [])]
        (configure-backup! connection)
        (with-redefs [ai/complete
                      (recording-completer
                       requests
                       [unpaid {:seon.ai/text "(seon.run/complete \"backed up\")"}])]
          (with-redefs [injected-evaluation
                    {:seon.cluster.eval/result-edn
                     (pr-str (seon.run/complete "backed up"))
                     :seon.sci.admit/value (seon.run/complete "backed up")}]
            (drive! cluster 10)))
        (let [[primary-request backup-request] @requests
              rows (attempt-rows @connection)
              [primary-row backup-row] rows]
          (testing "EXACTLY two request builds — the backup is one call,
          not a retry loop that happens to stop"
            (is (= 2 (count @requests))))
          (testing "the second call went to the BACKUP target"
            (is (.equals "probe" (:seon.ai/model primary-request)))
            (is (.equals "backup-probe" (:seon.ai/model backup-request)))
            (is (.equals "SEON_AI_TEST_BACKUP_KEY"
                         (:seon.ai/api-key-variable backup-request))))
          (testing "carrying the ORIGINAL prompt unchanged — the user's
          request is not where a runtime notice belongs"
            (is (= (:seon.ai/prompt primary-request)
                   (:seon.ai/prompt backup-request)))
            (is (not (contains? primary-request :seon.ai/system))))
          (testing "and a system segment that IS the projection of the
          committed fact — asserted against the derivation, never
          against prose written here"
            (let [error-id (:seon.error/id
                            (db/pull @connection '[:seon.error/id]
                                    (db/q '[:find ?e . :where
                                           [?e :seon.error/id _]]
                                         @connection)))
                  fact (durable-fact @connection [:seon.error/id error-id])]
              (is (= (error/ai-prose
                      (error/notice {:seon.error/fact fact
                                     :seon.error/reason :failover}))
                     (:seon.ai/system backup-request)))))
          (testing "two attempt rows tell the whole story"
            (is (= 2 (count rows)))
            (is (= [true false]
                   (mapv #(contains? % :seon.ai.attempt/error) rows))
                "the error ref's presence IS the outcome")
            (is (= :failover-now
                   (derived-disposition @connection primary-row true))
                "and WHY the second call was allowed is derivable from
                 the durable evidence alone — never a stored label")
            (is (false? (:seon.ai/request-transmitted? primary-row))
                "the evidence that made it allowed is on the row too")
            (is (some? (:seon.ai.attempt/error primary-row))
                "the failed attempt points at its error fact")
            (is (= (:db/id primary-row)
                   (:db/id (:seon.ai.attempt/failover-from backup-row)))
                "and the backup points at the attempt it replaced —
                 role by connection, never a :primary/:backup stamp")
            (is (not (contains? backup-row :seon.ai.attempt/delay-ms))
                "a failover waits for nothing"))
          (testing "the run proceeded on the backup's answer"
            (is (some? (db/q '[:find ?d . :where
                              [_ :seon.turn/reply-size ?d]]
                            @connection)))))))))

(def ^:private turn-evidence-partitions
  [{::error-class :credential :seon.error/value {:seon.ai/missing-credential-variable "SEON_TEST_PROVIDER_KEY"}
    ::transmitted? false}
   {::error-class :transport-before-send :seon.error/value {:seon.ai/transport-failure "https://provider.invalid"}
    ::transmitted? false}
   {::error-class :transport-unknown :seon.error/value {:seon.ai/transport-failure "https://provider.invalid"}
    ::transmitted? true}
   {::error-class :timeout :seon.error/value {:seon.ai/timeout 1000} ::transmitted? true}
   {::error-class :rate-limit :seon.error/value {:seon.ai/provider-error 503}
    ::transmitted? true}
   {::error-class :server :seon.error/value {:seon.ai/provider-error 503} ::transmitted? true}
   {::error-class :authentication :seon.error/value {:seon.ai/provider-error 503}
    ::transmitted? true}
   {::error-class :authorization :seon.error/value {:seon.ai/provider-error 503}
    ::transmitted? true}
   {::error-class :model :seon.error/value {:seon.ai/provider-error 503} ::transmitted? true}
   {::error-class :request :seon.error/value {:seon.ai/provider-error 503} ::transmitted? true}
   {::error-class :response :seon.error/value {:seon.ai/unreadable-response-member "body"}
    ::transmitted? true}
   {::error-class :response :seon.error/value {:seon.ai/unreadable-response-member "body"}
    ::transmitted? true ::output? true}])

(def ^:private turn-outcome-generator
  (gen/frequency
   [[1 (gen/return {::outcome :success})]
    [4 (gen/fmap #(assoc % ::outcome :failure)
                 (gen/elements turn-evidence-partitions))]]))

(def ^:private turn-scenario-generator
  (gen/let [outcomes (gen/vector turn-outcome-generator 1 4)
            backup? gen/boolean
            maximum-retries (gen/choose 0 2)]
    {::outcomes outcomes
     ::backup? backup?
     ::maximum-retries maximum-retries}))

(defn- turn-failure-value
  {:malli/schema [:=> [:cat [:map [:seon.error/value :map]
                            [::error-class :keyword]
                            [::transmitted? :boolean]
                            [::output? {:optional true} :boolean]]]
                  :seon.ai/completion]}
  [{::keys [error-class transmitted? output?]
    members :seon.error/value}]
  (failure members
           (cond-> {:seon.ai/error-class error-class
                    :seon.ai/request-transmitted? transmitted?
                    :seon.ai/response-started?
                    (boolean
                     (contains? #{:rate-limit :server :authentication
                                  :authorization :model :request :response}
                                error-class))
                    :seon.ai/output-observed? (boolean output?)}
             (contains? #{:rate-limit :server :authentication
                          :authorization :model :request :response}
                        error-class)
             (assoc :seon.ai/http-status
                    (if (= :response error-class) 200 503)))))

(defn- turn-completion
  [{::keys [outcome] :as generated}]
  (if (= :success outcome)
    {:seon.ai/text "(seon.run/complete \"generated\")"}
    (turn-failure-value generated)))

(defn- oracle-disposition
  [{::keys [error-class output?]} backup?]
  (cond
    output? :fail
    (contains? #{:rate-limit :server :transport-before-send} error-class)
    (if backup? :failover-now :backoff)
    (contains? #{:credential :authentication :authorization :model}
               error-class)
    :fail
    :else :fail))

(defn- expected-attempt-trace
  [{::keys [outcomes backup? maximum-retries]}]
  (let [waits (mapv #(bit-shift-left 1 %) (range maximum-retries))
        answer (fn [ordinal]
                 (nth outcomes ordinal (last outcomes)))]
    (loop [ordinal 0
           target :primary
           failover? false
           delay nil
           remaining-waits (if backup? [] waits)
           trace []]
      (let [{::keys [outcome] :as generated} (answer ordinal)
            failed? (= :failure outcome)
            row (cond-> {::model target ::failed? failed?}
                  failover? (assoc ::failover? true)
                  delay (assoc ::delay-ms delay))
            trace (conj trace row)
            disposition (when failed?
                          (oracle-disposition generated
                                              (and backup?
                                                   (not failover?))))]
        (cond
          (not failed?) {::attempts trace ::succeeded? true}
          (= :failover-now disposition)
          (recur (inc ordinal) :backup true nil [] trace)
          (and (= :backoff disposition) (seq remaining-waits))
          (recur (inc ordinal) target false (first remaining-waits)
                 (rest remaining-waits) trace)
          :else {::attempts trace ::succeeded? false})))))

(defn- actual-attempt-shape
  [row]
  (cond-> {::model (if (= "backup-probe" (:seon.ai/model row))
                     :backup
                     :primary)
           ::failed? (contains? row :seon.ai.attempt/error)}
    (contains? row :seon.ai.attempt/failover-from)
    (assoc ::failover? true)
    (contains? row :seon.ai.attempt/delay-ms)
    (assoc ::delay-ms (:seon.ai.attempt/delay-ms row))))

(defn- generated-turn-agrees-with-durable-facts?
  {:malli/schema [:=> [:cat :map] :boolean]}
  [scenario]
  (with-cluster
    (fn [cluster]
      (let [{::keys [attempts succeeded?]} (expected-attempt-trace scenario)
            connection (:seon.db/connection cluster)
            retry-strategy {:seon.ai.retry/base-delay-ms 1
                            :seon.ai.retry/multiplier 2.0
                            :seon.ai.retry/jitter-fraction 0.0
                            :seon.ai.retry/maximum-delay-ms 4
                            :seon.ai.retry/maximum-retries
                            (::maximum-retries scenario)
                            :seon.ai.retry/maximum-total-delay-ms 1000}
            completions (mapv turn-completion (::outcomes scenario))
            requests (atom [])
            committed-prefixes (atom [])
            complete! (fn [request]
                        (swap! committed-prefixes
                               conj
                               (mapv actual-attempt-shape
                                     (attempt-rows @connection)))
                        (let [ordinal (count @requests)]
                          (swap! requests conj request)
                          (nth completions ordinal (last completions))))]
        (let [report (db/transact!
         connection
         [(cond-> {:db/id [:seon.config/cluster "turn-test"]
                   :seon.config.ai.retry/base-delay-ms
                   (:seon.ai.retry/base-delay-ms retry-strategy)
                   :seon.config.ai.retry/multiplier
                   (:seon.ai.retry/multiplier retry-strategy)
                   :seon.config.ai.retry/jitter-fraction
                   (:seon.ai.retry/jitter-fraction retry-strategy)
                   :seon.config.ai.retry/maximum-delay-ms
                   (:seon.ai.retry/maximum-delay-ms retry-strategy)
                   :seon.config.ai.retry/maximum-retries
                   (:seon.ai.retry/maximum-retries retry-strategy)
                   :seon.config.ai.retry/maximum-total-delay-ms
                   (:seon.ai.retry/maximum-total-delay-ms retry-strategy)}
            (::backup? scenario)
            (assoc :seon.config.ai.backup/endpoint
                   (:seon.ai/endpoint backup-target)
                   :seon.config.ai.backup/model
                   (:seon.ai/model backup-target)
                   :seon.config.ai.backup/api-key-variable
                   (:seon.ai/api-key-variable backup-target)
                   :seon.config.ai.backup/timeout-ms
                   (:seon.ai/timeout-ms backup-target)))])]
          (when (:seon.db.write.attempt/request-id report)
            (throw (ex-info "The generated scenario seed was refused." report))))
        (with-redefs [ai/complete complete!]
          (drive! cluster 12))
        (let [rows (attempt-rows @connection)
              actual (mapv actual-attempt-shape rows)
              run-row (db/q '[:find (pull ?run [*]) .
                             :where [?run :seon.turn/attempts _]]
                           @connection)]
          (and
           (= attempts actual)
           (= (mapv #(subvec attempts 0 %)
                    (range (count attempts)))
              @committed-prefixes)
           (= (mapv ::model attempts)
              (mapv #(if (= "backup-probe" (:seon.ai/model %))
                       :backup
                       :primary)
                    @requests))
           (= (vec (range (count rows)))
              (mapv :seon.ai.attempt/ordinal rows))
           (every? #(not (contains? % :seon.ai/disposition)) rows)
           (contains? run-row :seon.turn/closed-tx)
           (= 1 (turn/episode-runs @connection "agent-a"))
           (= succeeded?
              (contains? run-row :seon.turn/reply-size))))))))

(deftest generated-model-attempt-traces-preserve-presence-and-episode-laws
  (test-support/assert-check!
   (tc/quick-check
    48
    (prop/for-all [scenario turn-scenario-generator]
      (generated-turn-agrees-with-durable-facts? scenario))
    :seed 202607280402)
   "Generated model attempts diverged from durable turn facts."))

;;; ---------------------------------------------------------------------------
;;; The second agent-facing value: a form that sends
;;; ---------------------------------------------------------------------------

(deftest a-turn-delivers-what-a-form-asks-to-send-and-still-finishes
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)
            requests (atom [])]
        (is (some? (:db-after (db/transact! connection [(agent-row "agent-b")]))))
        (with-redefs [ai/complete
                      (recording-completer
                       requests
                       [{:seon.ai/text
                         (str "(do (my.message/send {:my.message/to \"agent-b\" "
                              ":my.message/content \"please count the widgets\"}) :sent)\n"
                              "(seon.run/complete \"asked agent-b\")")}
                        {:seon.ai/text
                         "(seon.run/complete \"there are three widgets\")"}
                        {:seon.ai/text
                         "(seon.run/complete \"accepted agent-b's answer\")"}])]
          (drive! cluster 10))
        (let [database (db/db connection)
              [message-id message-tx]
              (db/q '[:find [?id ?tx]
                      :where
                      [?message :seon.message/content "please count the widgets" ?tx]
                      [?message :seon.message/id ?id]] database)
              settlement-tx
              (db/q '[:find ?tx .
                      :where
                      [?evaluation :seon.cluster.eval/author :agent]
                      [?evaluation :seon.eval/shown ":sent" ?tx]] database)]
          (is (= 3 (count @requests)))
          (is (= #{["please count the widgets" "agent-b" "agent-a"]
                   ["there are three widgets" "agent-a" "agent-b"]}
                 (set (db/q '[:find ?content ?to-id ?from-id
                              :where
                              [?message :seon.message/content ?content]
                              [?message :seon.message/to ?to]
                              [?to :seon.agent/id ?to-id]
                              [?message :seon.message/from ?from]
                              [?from :seon.agent/id ?from-id]] database))))
          (is (int? message-tx))
          (is (int? settlement-tx))
          (is (< message-tx settlement-tx)
              "send writes even when its return value is discarded inside do")
          (is (= 1 (message/chain-depth database message-id)))
          (is (nil? (turn/next-agent-work database (request connection "agent-a"))))
          (is (nil? (turn/next-agent-work database (request connection "agent-b")))))))))


(deftest a-refused-delivery-becomes-a-durable-error-fact
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)
            source "(my.message/send {:my.message/to \"missing-agent\" :my.message/content \"hi\"})"
            evaluate sci.eval/evaluate
            actual-value (atom nil)]
        (with-redefs [ai/complete
                      (fn [_projection _] {:seon.ai/text
                               (str source "\n(seon.run/complete \"tried\")")})
                      sci.eval/evaluate
                      (fn [request]
                        (let [result (evaluate request)]
                          (when (= source (:seon.cluster.eval/source request))
                            (reset! actual-value (:seon.sci.admit/value result)))
                          result))]
          (drive-agent! cluster "agent-a" 2))
        (let [database (db/db connection)
              evaluations (agent-evaluations database)]
          (is (string? (:seon.message/unknown-recipient @actual-value)))
          (is (= 2 (count evaluations)))
          (is (str/includes? (:seon.eval/shown (first evaluations)) "missing-agent"))
          (is (= "There is no agent named \"missing-agent\"."
                 (:seon.eval/shown (first evaluations))))
          (is (empty? (db/q '[:find ?message :where [?message :seon.message/from _]] database)))
          (is (nil? (turn/next-agent-work database (request connection)))))))))

(deftest a-held-runs-paid-call-is-never-duplicated
  ;; P2, the lapsed-lease re-pay cycle, re-expressed as CUSTODY
  ;; MISMATCH now that no lease exists to lapse: across the whole
  ;; open→call→fold interleaving there is exactly ONE provider
  ;; dispatch, and a rewake seen by a DIFFERENT process derives no
  ;; second `:call` for the held run
  ;; (research/trigger-conservation-2026-07-28 §3.2).
  (with-cluster fake-evaluate
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)
            requests (atom [])]
        ;; pass 1: open + claim (the busy fence before the expensive part)
        (let [work (turn/next-agent-work @connection (request connection))]
          (is (= :open (:seon.turn.work/situation work)))
          (turn/turn {:seon.turn.loop/cluster cluster
                              :seon.turn.work/next work}
                             (Date.)))
        (testing "the held run derives :call for its holder ONLY"
          (is (= :call (:seon.turn.work/situation
                        (turn/next-agent-work @connection (request connection)))))
          )
        ;; the rest of the interleaving, arbitrarily later — there is
        ;; no clock on custody, so the pass simply proceeds
        (with-redefs [ai/complete
                      (recording-completer
                       requests
                       [{:seon.ai/text "(seon.run/complete \"one\")"}])]
          (with-redefs [injected-evaluation {:seon.cluster.eval/result-edn
                                  (pr-str (seon.run/complete "one"))
                                  :seon.sci.admit/value
                                  (seon.run/complete "one")}]
            (drive! cluster 6)))
        (is (= 1 (count @requests))
            "zero duplicate provider dispatches across the interleaving")
        (is (= 1 (count (attempt-rows @connection)))
            "and the durable attempt chain agrees")
        (is (some? (db/q '[:find ?c . :where
                          [_ :seon.turn/closed-tx ?c]] @connection))
            "the turn ran to completion")))))

;;; ---------------------------------------------------------------------------
;;; Nothing throws into the agent loop — the prompt refusal seam
;;; ---------------------------------------------------------------------------

(defn- unsettled-ordinals
  [database run-id]
  (->> (db/q '[:find [(pull ?receipt [*]) ...]
               :in $ ?run-id
               :where
               [?run :seon.turn/id ?run-id]
               [?receipt :seon.cluster.eval/run ?run]]
             database run-id)
       (remove turn/terminal?)
       (mapv :seon.cluster.eval/ordinal)
       sort
       vec))

(defn- recover-cut-run!
  [connection run-id]
  (test-support/transacted!
               connection
               (turn/recover-tx {:seon.turn/id run-id

                                :seon.turn/now (Date. 1700000001000)})))

(deftest turn-intent-is-the-complete-crash-falsifier
  (doseq [prefix-count [0 2]]
    (testing (str "recovery after " prefix-count " in-memory evaluations")
      (with-cluster
        (fn [cluster]
          (let [connection (:seon.db/connection cluster)
                source "(+ 1 1)\n(+ 2 2)\n(+ 3 3)"
                open-work (turn/next-agent-work @connection (request connection))
                _ (turn/turn {:seon.turn.loop/cluster cluster
                              :seon.turn.work/next open-work} now)
                call-work (turn/next-agent-work @connection (request connection))
                run-id (:seon.turn/id call-work)
                evaluations (atom 0)
                cut? (atom true)
                transact! db/transact!
                evaluate sci.eval/evaluate]
            (is (thrown-with-msg?
                 clojure.lang.ExceptionInfo #"cut after intent"
                 (with-redefs
                   [ai/complete (fn [_projection _] {:seon.ai/text source})
                    sci.eval/evaluate
                    (fn [request]
                      (swap! evaluations inc)
                      (evaluate request))
                    db/transact!
                    (fn [target transaction]
                      (let [outcome (transact! target transaction)
                            intent? (some #(and (vector? %)
                                                (= #'turn/plan-call (second %)))
                                          (:tx-data transaction))]
                        (if (and intent? (compare-and-set! cut? true false))
                          (throw (ex-info "cut after intent" {:process-cut true}))
                          outcome)))]
                   (turn/turn {:seon.turn.loop/cluster cluster
                               :seon.turn.work/next call-work} now))))
            (let [row (db/pull @connection
                               '[:seon.turn/reply
                                 {:seon.turn/trigger [:seon.message/id]}]
                               [:seon.turn/id run-id])]
              (is (= source (:seon.turn/reply row)))
              (is (= "m-1" (get-in row [:seon.turn/trigger :seon.message/id]))))
            (is (zero? @evaluations))
            (is (= [0 1 2] (unsettled-ordinals @connection run-id)))
            ;; Execute a real prefix without invoking the settlement writer.
            ;; A caught host exception is a phase failure, not a process cut.
            (when (pos? prefix-count)
              (let [ctx (:seon.sci.eval/ctx
                         (sci.eval/fork-for-turn
                          {:seon.sci.eval/ctx (:seon.sci.eval/ctx cluster)
                           :seon.db/db @connection
                           :seon.agent/id "agent-a"}))
                    planned (turn/planned-sources
                             source 'my.agents.agent-a
                             (get-in cluster [:seon.sci.admit/caps
                                              :seon.config.eval.result/max-source]))
                    evaluated
                    (with-redefs [sci.eval/evaluate
                                  (fn [request]
                                    (swap! evaluations inc)
                                    (evaluate request))]
                      (turn/evaluate-sources
                       {:seon.turn.loop/cluster cluster
                        :seon.sci.eval/ctx ctx
                        :seon.agent/id "agent-a"
                        :seon.turn/id run-id
                        :seon.cluster.eval/ordinal 0
                        :seon.ns/name 'my.agents.agent-a
                        :seon.cluster.reply/sources (subvec planned 0 prefix-count)}))]
                (is (= [2 4]
                       (mapv (comp :seon.sci.admit/value :seon.sci.eval/evaluation)
                             evaluated)))))
            (is (= prefix-count @evaluations))
            (is (= [0 1 2] (unsettled-ordinals @connection run-id)))
            (is (some? (:db-after (recover-cut-run! connection run-id))))
            (is (empty? (unsettled-ordinals @connection run-id)))
            (let [row (db/pull @connection [:seon.turn/closed-tx]
                               [:seon.turn/id run-id])
                  work (turn/next-agent-work @connection (request connection))]
              (is (some? (:seon.turn/closed-tx row)))
              (is (= :open (:seon.turn.work/situation work))
                  "an interrupted turn has not answered the wake")
              (is (not= run-id (:seon.turn/id work))
                  "the interrupted turn never resumes"))
            (is (= prefix-count @evaluations)
                "recovery never re-executes the in-memory prefix")))))))

(deftest a-settling-declaration-uses-the-projection-its-database-carries
  ;; THE CLASS: a check that recomputes what its input already carries.
  ;; Settlement derived the complete projection from datoms on every
  ;; defining turn -- three scans over every schema, contract and source
  ;; row, returning the identical projection the database value carried
  ;; (209.4 ms of the 253.0 ms this namespace bounds as settlement and
  ;; writes). The carried value is the writer's current value for every
  ;; transaction whose earlier entries declared nothing.
  (testing "only a declaration earlier in the SAME transaction forces a derivation"
    (let [row {:seon.fn/sym "my.agents.agent-a/declared"
               :seon.ns/name 'my.agents.agent-a
               :seon.fn/ns [:seon.ns/name 'my.agents.agent-a]}
          requests (mapv (fn [ordinal declaration?]
                           (cond-> {:seon.turn/id "marking"
                                    :seon.cluster.eval/ordinal ordinal}
                             declaration? (assoc :seon.program/row row)))
                         (range 4) [false true false true])]
      (is (= [false false true true]
             (mapv (fn [call]
                     (boolean (:seon.turn/declarations-preceding? (last call))))
                   (turn/receipt-settle-batch-tx requests)))
          "a request is marked exactly when a declaration precedes it")))
  (testing "an unpreceded declaration validates against the carried projection"
    (test-support/with-database
      (fn [connection]
        (let [database (db/db connection)
              carried (db/carried-projection database)
              declaration-projection (deref (ns-resolve 'seon.turn
                                                        'declaration-projection))
              derivations (atom 0)
              from-database schema/projection-from-database]
          (is (some? carried) "the canonical database value carries its projection")
          (with-redefs [schema/projection-from-database
                        (fn [& arguments]
                          (swap! derivations inc)
                          (apply from-database arguments))]
            (is (identical? carried (declaration-projection database {}))
                "the value's own projection is used as it is")
            (is (zero? @derivations)
                "nothing rebuilt a projection the database value already carried")
            (is (some? (declaration-projection
                        database {:seon.turn/declarations-preceding? true}))
                "a preceded declaration still derives at the writer")
            (is (= 1 @derivations)
                "exactly the preceded declaration derives")))))))

(deftest delimiter-repair-is-span-local-and-precedes-intent
  (testing "repair is one bounded, honest, idempotent pass"
    (let [repair (deref (ns-resolve 'seon.turn 'repair-source))
          broken "(defn repaired [x]\n  (+ x 1)"
          fixed (repair broken 'my.agents.agent-a 10000)]
      (is (= "(+ 1 2)" (repair "(+ 1 2)" 'my.agents.agent-a 10000))
          "unchanged valid input is rejected as a repair")
      (is (= "{:a 1 :b}" (repair "{:a 1 :b}" 'my.agents.agent-a 10000))
          "still-invalid output is rejected")
      (is (= fixed (repair fixed 'my.agents.agent-a 10000))
          "accepted repair is idempotent")))
  (testing "one missing defn closer is fixed, evaluated, and stored as the history form"
    (with-cluster
      (fn [cluster]
        (let [connection (:seon.db/connection cluster)
              ;; CONTRACTED ON PURPOSE. The repair's own subject is the
              ;; missing closer, but the form after it calls what the
              ;; repaired form defined: only a contracted definition is
              ;; installed into the program, so an uncontracted one makes
              ;; this fixture measure a refusal instead of the repair.
              original
              (str "(defn ^{:malli/schema [:=> [:cat :int] :int]} repaired [x]\n  (+ x 1)\n"
                   "(+ 40 2)\n"
                   "(repaired 2)\n"
                   "(+ 1 1)\n"
                   "(+ 2 2)\n"
                   "(seon.run/complete \"fixed\")")
              write-nanos (atom 0)
              write-count (atom 0)
              transact! db/transact!
              install-nanos (atom 0)
              install-var (ns-resolve 'seon.turn 'gate-function-install)
              install @install-var
              test-thread (Thread/currentThread)
              reply-arrived (atom nil)
              read-sources reply/sources]
          (turn/turn
           {:seon.turn.loop/cluster cluster
            :seon.turn.work/next
            (turn/next-agent-work @connection (request connection))}
           now)
          (let [call-work (turn/next-agent-work @connection
                                                (request connection))]
            (with-redefs-fn
              {install-var
               (fn [& arguments]
                 (if (= test-thread (Thread/currentThread))
                   (let [started (System/nanoTime)]
                     (try (apply install arguments)
                          (finally
                            (swap! install-nanos +
                                   (- (System/nanoTime) started)))))
                   (apply install arguments)))}
              (fn []
               (with-redefs [ai/complete (fn [_projection _] {:seon.ai/text original})
                          reply/sources
                          (fn [& arguments]
                            (reset! reply-arrived (System/nanoTime))
                            (apply read-sources arguments))
                          db/transact!
                          (fn [& arguments]
                            (let [measure? (and (= test-thread (Thread/currentThread))
                                                @reply-arrived)
                                  started (System/nanoTime)]
                              (try (apply transact! arguments)
                                   (finally
                                     (when measure?
                                       (swap! write-count inc)
                                       (swap! write-nanos + (- (System/nanoTime) started)))))))]
              (turn/turn
               {:seon.turn.loop/cluster cluster
                :seon.turn.work/next call-work}
               now))))
            (let [bookkeeping-ms (/ (double @write-nanos) 1000000.0)
                  install-ms (/ (double @install-nanos) 1000000.0)
                  ;; THE SUBJECT IS THE AGENT'S OWN SIX FORMS. System turn 0
                  ;; stores the generated opening in the same evaluation
                  ;; family, so an absolute count over every evaluation
                  ;; measures the opening as well; authorship is the fact
                  ;; that separates them, and `agent-evaluations` orders by
                  ;; turn and ordinal.
                  evaluations (agent-evaluations @connection)
                  sources (mapv :seon.cluster.eval/source evaluations)
                  rendered
                  (transcript/render-ai
                   {:seon.db/db @connection
                    :seon.agent/id "agent-a"
                    :seon.sci.eval/ctx (:seon.sci.eval/ctx cluster)
                    :seon.sci.eval/time-limit-ms 2000
                    :seon.config/on-core-error :panic
                    :seon.sci.admit/caps (:seon.sci.admit/caps cluster)})]
              (is (= 6 (count evaluations)))
              (is (= (str "(defn ^{:malli/schema [:=> [:cat :int] :int]}"
                          " repaired [x]\n  (+ x 1))\n")
                     (first sources)))
              (is (= "(+ 40 2)" (second sources))
                  "the adjacent good form remains byte-identical")
              ;; the evaluation stores its SHOWN TEXT; there is no second
              ;; serialized result to read back (turn PRD 15).
              (is (= ["42" "3" "2" "4"]
                     (mapv :seon.eval/shown (subvec evaluations 1 5))))
              (is (= original
                     (db/q '[:find ?reply .
                             :in $ ?turn-id
                             :where
                             [?turn :seon.turn/id ?turn-id]
                             [?turn :seon.turn/reply ?reply]]
                           @connection (:seon.turn/id call-work)))
                  "raw intent provenance remains the original reply")
              (is (str/includes? rendered "repaired [x]\n  (+ x 1))"))
              (is (= #{"my.agents.agent-a/repaired"}
                     (set (db/q '[:find [?symbol ...] :in $ ?evaluation
                                  :where [?evaluation :seon.fn/calls ?function]
                                  [?function :seon.fn/sym ?symbol]]
                                (db/db connection) (:db/id (nth evaluations 2)))))
                  "a later evaluation retains its edge to the definition in this turn")
              (is (pos? @install-nanos) "the definition install was observed")
              (is (>= @write-count 2) "intent and settlement writes were observed")
              (is (< install-ms 300.0)
                  (str "definition installation took " install-ms " ms"))
              (is (< bookkeeping-ms 300.0)
                  (str "six-form settlement and writes took " bookkeeping-ms " ms"))))))))
  (testing "indent mode repairs a mismatched closer type"
    (with-cluster
      (fn [cluster]
        (let [connection (:seon.db/connection cluster)
              source "(let [x 1)\n  x)\n(seon.run/complete \"fixed\")"]
          (with-redefs [ai/complete (fn [_projection _] {:seon.ai/text source})]
            (drive! cluster 6))
          (let [evaluations (agent-evaluations @connection)]
            (is (= "(let [x 1]\n  x)\n"
                   (:seon.cluster.eval/source (first evaluations))))
            (is (= "1" (:seon.eval/shown (first evaluations)))))))))
  (testing "an odd map stays an error and the following form still settles"
    (with-cluster
      (fn [cluster]
        (let [connection (:seon.db/connection cluster)
              source "{:a 1 :b}\n(+ 20 22)\n(seon.run/complete \"continued\")"]
          (with-redefs [ai/complete (fn [_projection _] {:seon.ai/text source})]
            (drive! cluster 6))
          (let [evaluations (agent-evaluations @connection)]
            (is (= "{:a 1 :b}\n"
                   (:seon.cluster.eval/source (first evaluations))))
            (is (str/includes? (:seon.eval/shown (first evaluations))
                               "seon.sci.reader/read refused source")
                "the unreadable form keeps the reader's own refusal")
            (is (str/includes? (:seon.cluster.eval/error (first evaluations))
                               "Map literals must contain an even number of forms"))
            (is (= "42" (:seon.eval/shown (second evaluations))))))))))

(deftest a-batched-turn-commits-only-queryable-definition-facts
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)
            source
            (str
             "(require '[seon.schema :as schema] '[clojure.test :refer [deftest is]])\n"
             "(schema/register! ::item-id :int)\n"
             "(schema/register! ::item [:map [:id ::item-id]])\n"
             "(defn ^{:malli/schema [:=> [:cat ::item] :int]} extract-id [m] (inc (:id m)))\n"
             "(deftest extract-id-test (is (= 8 (extract-id {:id 7}))))\n"
             "(+ 40 2)\n"
             "(+ 42 1)\n"
             "(seon.run/complete \"indexed\")")]
        (with-redefs [ai/complete (fn [_projection _] {:seon.ai/text source})]
          (drive! cluster 12))
        (let [database @connection
              function-symbol "my.agents.agent-a/extract-id"
              test-symbol "my.agents.agent-a/extract-id-test"
              function-row
              (db/pull database
                       '[:seon.fn/sym :seon.fn/spec
                         {:seon.fn/calls [:seon.fn/sym]}]
                       [:seon.fn/sym function-symbol])
              result
              (db/q '[:find ?result .
                      :where
                      [?evaluation :seon.cluster.eval/ordinal 6]
                      [?evaluation :seon.eval/shown ?result]]
                    database)
              direct-contract-keys
              (db/q '[:find [?key ...]
                      :in $ ?function-symbol
                      :where
                      [?function :seon.fn/sym ?function-symbol]
                      [?function :seon.fn/arities ?arity]
                      [?arity :seon.fn.arity/input-refs ?schema]
                      [?schema :seon.schema/key ?key]]
                    database function-symbol)
              child-schema-keys
              (db/q '[:find [?child-key ...]
                      :in $ ?schema-key
                      :where
                      [?schema :seon.schema/key ?schema-key]
                      [?schema :seon.schema/references ?child]
                      [?child :seon.schema/key ?child-key]]
                    database :my.agents.agent-a/item)]
          (is (= "43" result)
              "every form of a batched turn settles its own result")
          (is (= [test-symbol]
                 (seon.fn/tests-reaching database function-symbol)))
          (is (contains?
               (into #{} (map :seon.fn/sym)
                     (:seon.fn/calls function-row))
               "clojure.core/inc")
              "the function row carries its kondo-resolved population call")
          (is (= #{:my.agents.agent-a/item} (set direct-contract-keys)))
          (is (= #{:my.agents.agent-a/item-id} (set child-schema-keys))
              "the stored contract and registry references expose child keys")
          (is (true? (:seon.db/invalid-read (db/q '[:find ?entity
                          :where [?entity :seon.db/read-result _]]
                        database)))
              "the removed attribute cannot admit a read-result datom")
          (is (not (contains? function-row :seon.cluster.eval/source))
              "program facts and eval facts remain separate row families"))))))

(deftest singleton-enum-uses-are-members-of-their-declared-enums
  (let [forms (schema.edn/packaged-forms)
        declared
        (into {}
              (keep (fn [[schema-key definition]]
                      (when (and (vector? definition)
                                 (= :enum (first definition)))
                        [schema-key (set (let [node (seon.schema/structural-schema definition)] (when (= :enum (malli.core/type node)) (malli.core/children node))))])))
              forms)
        used
        (into #{}
              (comp
               (mapcat #(tree-seq coll? seq %))
               (filter vector?)
               (keep (fn [entry]
                       (let [enum-key (first entry)
                             constraint (last entry)]
                         (when (and (contains? declared enum-key)
                                    (vector? constraint)
                                    (= := (first constraint))
                                    (keyword? (second constraint)))
                           [enum-key (second constraint)])))))
              (vals forms))]
    (is (seq used) "the registry exposes actual singleton enum uses")
    (is (every? (fn [[enum-key member]]
                  (contains? (get declared enum-key) member))
                used)
        (pr-str used))))

(deftest generated-fixed-point-closes-the-run
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)
            run-id "generated-fixed-point"]
        (test-support/transacted!
                     connection
                     (turn/generated-run-tx
                      @connection
                      {:seon.agent/id "agent-a" :seon.turn/id run-id :seon.db.process/id process :seon.turn/opened-tx "datomic.tx" :seon.turn/starting-ns [:seon.ns/name 'my.agents.agent-a]}))
        (test-support/transacted!
                     connection
                     (turn/append-generated-tx
                      {:seon.turn/id run-id
                       :seon.db.process/id process
                       :seon.cluster.eval/at now
                       :seon.cluster.eval/ordinal 0
                       :seon.cluster.eval/source "(help)"
                       :seon.ns/name 'my.agents.agent-a}))
        (test-support/transacted!
                     connection
                     (turn/receipt-settle-tx
                      {:seon.turn/id run-id
                       :seon.cluster.eval/ordinal 0
                       :seon.eval/shown "{:introduced 'my.turn}"}))
        (let [request {:seon.agent/id "agent-a"
                       :seon.db.process/id process}
              generated (turn/next-agent-work @connection request)
              report
              (with-redefs-fn {#'turn/declared-sources
                              (fn [& _] {:seon.turn/forms []})}
                #(turn/turn
                 {:seon.turn.loop/cluster cluster
                  :seon.turn.work/next generated}
                 now))]
          ;; Exhausting the declared system sources closes the turn.
          (is (= :closed (:seon.turn.loop/outcome report)))
          (is (inst? (:db/txInstant
                       (db/pull @connection [:db/txInstant]
                                (get-in (db/pull @connection [:seon.turn/closed-tx]
                                                 [:seon.turn/id run-id])
                                        [:seon.turn/closed-tx :db/id])))))
          (is (not= run-id
                    (:seon.turn/id (turn/next-agent-work @connection
                                                               request)))
              "a closed generated run derives no further work of its own")
          (is (= :system
                 (db/q '[:find ?author .
                         :in $ ?run-id
                         :where
                         [?run :seon.turn/id ?run-id]
                         [?form :seon.cluster.eval/run ?run]
                         [?form :seon.cluster.eval/ordinal 0]
                         [?form :seon.cluster.eval/author ?author]]
                       @connection run-id))))))))

(deftest generated-membership-failure-never-advances-the-run-to-call
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)
            run-id "generated-membership-failure"]
        (test-support/transacted!
                     connection
                     (turn/generated-run-tx
                      @connection
                      {:seon.agent/id "agent-a" :seon.turn/id run-id :seon.db.process/id process :seon.turn/opened-tx "datomic.tx" :seon.turn/starting-ns [:seon.ns/name 'my.agents.agent-a]}))
        (test-support/transacted!
                     connection
                     (turn/append-generated-tx
                      {:seon.turn/id run-id
                       :seon.db.process/id process
                       :seon.cluster.eval/at now
                       :seon.cluster.eval/ordinal 0
                       :seon.cluster.eval/source "(help)"
                       :seon.ns/name 'my.agents.agent-a}))
        (test-support/transacted!
                     connection
                     (turn/receipt-settle-tx
                      {:seon.turn/id run-id
                       :seon.cluster.eval/ordinal 0
                       :seon.eval/shown "{:introduced 'my.turn}"}))
        (let [request {:seon.agent/id "agent-a"
                       :seon.db.process/id process}
              generated (turn/next-agent-work @connection request)
              failure
              {:seon.render.walk/missing-lookup [:seon.agent/id "agent-a"]
               :seon.error/at now
               :seon.error/layer :seon.render.walk/neighborhood
               :seon.error/operation 'seon.render.walk/neighborhood
               :seon.error/message
               "The generated opening root pull returned no membership data."
               :seon.error/data
               {:seon.render.walk/lookup [:seon.agent/id "agent-a"]}}
              report
              (with-redefs-fn {#'turn/declared-sources (constantly failure)}
                #(turn/turn
                 {:seon.turn.loop/cluster cluster
                  :seon.turn.work/next generated}
                 now))
              run-state
              (db/pull @connection
                       [:seon.turn.work/situation
                        :seon.turn/closed-tx
                        {:seon.error.occurrence/_turn [:seon.error.occurrence/message]}]
                       [:seon.turn/id run-id])]
          (is (= :error (:seon.turn.loop/outcome report)))
          (is (not= :call (:seon.turn.work/situation run-state)))
          (is (some? (:seon.turn/closed-tx run-state)))
          (is (= (:seon.error/message failure)
                 (-> run-state :seon.error.occurrence/_turn first :seon.error.occurrence/message))))))))

(deftest generated-phase-failures-converge-through-one-terminal-exit
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)
            sequence-number (atom 0)
            cluster (assoc cluster :seon.config.error/escalate-to "root")
            run-phases (schema/enum-members (schema/handed-projection) :seon.turn.loop/phase)]
        (test-support/transacted! connection [(agent-row "root")])
        (test-support/assert-check!
         (tc/quick-check
          24
          (prop/for-all [failed-phase (gen/elements run-phases)]
            (let [sample (swap! sequence-number inc)
                  run-id (str "phase-failure-" sample)
                  evaluation? (= :evaluate failed-phase)
                  failure {
    :seon.turn.loop/phase-failed true
    :seon.error/at (java.util.Date.)
    :seon.error/layer :seon.turn/phase
    :seon.error/operation 'seon.turn/phase
                           :seon.error/message
                           (str "injected " (name failed-phase) " failure")
                           :seon.error/data {:seon.turn.loop/phase
                                             failed-phase}}]
              (test-support/transacted!
                           connection
                           [{:seon.turn/id run-id :seon.turn/agent [:seon.agent/id "agent-a"] :seon.turn/opened-tx "datomic.tx"}
                            {:seon.agent/id "agent-a"
                             }])
              (when evaluation?
                (test-support/transacted!
                             connection
                             (turn/receipt-start-tx
                              {:seon.turn/id run-id
                               :seon.cluster.eval/ordinal 0
                               :seon.cluster.eval/at now
                               :seon.cluster.eval/source "(identity :phase-probe)"
                               :seon.cluster.eval/ns [:seon.ns/name 'my.agents.agent-a]
                               :seon.cluster.eval/author :agent})))
              (let [settled
                    (turn/settle!
                     (cond-> {:seon.turn.loop/cluster cluster
                              :seon.turn.loop/now now
                              :seon.agent/id "agent-a"
                              :seon.turn/id run-id
                              :seon.error/value failure}
                       evaluation?
                       (assoc :seon.cluster.eval/ordinal 0)))
                    receipt-count
                    (or
                     (db/q '[:find (count ?receipt) .
                             :in $ ?run-id
                             :where
                             [?run :seon.turn/id ?run-id]
                             [?receipt :seon.cluster.eval/run ?run]]
                           @connection run-id)
                     0)]
                (and
                 (some? settled)
                 (= 1
                    (db/q '[:find (count ?closed) .
                            :in $ ?run-id
                            :where
                            [?run :seon.turn/id ?run-id]
                            [?run :seon.turn/closed-tx ?closed]]
                          @connection run-id))
                 (= (if evaluation? 1 0) receipt-count)
                 (= 1
                    (db/q '[:find (count ?error) .
                            :in $ ?run-id
                            :where
                            [?run :seon.turn/id ?run-id]
                            [?error :seon.error/occurrences ?occurrence]
                            [?occurrence :seon.error.occurrence/turn ?run]]
                          @connection run-id))))))
          :seed 2026080601)
         "Every injected phase failure must settle once, close once, and record
          exactly one durable error fact.")
        ;; ESCALATION IS THE ONE BOUNDED OWNER'S, and this is where that is
        ;; asserted — over the whole ledger the property just produced, not
        ;; per sample. `seon.turn/refusal-terminal-data` used to
        ;; `dissoc` the escalation dial and hand-roll one unbounded
        ;; `"A run phase failed: …"` message per failure; that second copy is
        ;; deleted, and the surviving behavior is what these queries state:
        ;; one message per SIGNATURE at the recurrence limit, addressed to the
        ;; escalation owner, and never to the agent whose run was refused.
        (let [db @connection
              faults (db/q '[:find ?to ?signature
                             :keys :seon.agent/id :seon.error/signature
                             :where
                             [?message :seon.message/about ?signature]
                             [?error :seon.error/signature ?signature]
                             [?message :seon.message/to ?agent]
                             [?agent :seon.agent/id ?to]
                             [?error :seon.error/signature ?signature]]
                           db)
              signatures (db/q '[:find ?signature
                                 :where
                                 [?error :seon.error/signature ?signature]
                                 [?error :seon.error/occurrences ?occurrence]
                                 [?occurrence :seon.error.occurrence/turn _]]
                               db)]
          (is (seq faults)
              "the escalation dial is on: a supervisor does hear about a
               worker's refused phases, which is the whole point of it")
          (is (= #{"root"} (into #{} (map :seon.agent/id) faults))
              "only the escalation owner is mailed; the failing agent is never
               told about its own refusal, because delivery is the wake
               attribute and that message would wake the same unfixed cause")
          (is (every? #(= 1 %) (vals (frequencies (map :seon.error/signature
                                                       faults))))
              "one escalation per signature per process — the storm fence.
               Twenty-four failures over six phases may not be twenty-four
               messages")
          (is (<= (count faults) (count signatures))
              "and never more messages than there are distinct signatures"))))))

(deftest a-prompt-refusal-is-a-recorded-error-value-never-a-throw
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)
            requests (atom [])
            refusal {:seon.error/at now
                     :seon.error/layer :seon.cluster.prompt/acquisition
                     :seon.error/operation 'seon.cluster.prompt/prompt
                     :seon.cluster.prompt/refused :retained-context
                     :seon.error/message "The retained prompt could not be acquired."
                     :seon.error/data {}}]
        (turn/turn {:seon.turn.loop/cluster cluster
                    :seon.turn.work/next
                    (turn/next-agent-work @connection (request connection))}
                   now)
        (with-redefs [prompt/prompt (fn [_ _] refusal)
                      ai/complete
                      (recording-completer requests [{:seon.ai/text "unused"}])]
          (let [report (turn/turn
                        {:seon.turn.loop/cluster cluster
                         :seon.turn.work/next
                         (turn/next-agent-work @connection (request connection))}
                        now)]
            (is (= :error (:seon.turn.loop/outcome report))
                "the refused prompt settles as an error value")))
        (is (empty? @requests) "no provider call without a prompt")
        (is (empty? (attempt-rows @connection)) "and no attempt row")
        (is (false? (contains? (:schema (db/db connection))
                              (keyword "seon.error" "kind"))))
        (is (contains? (set (db/q '[:find [?message ...] :where
                                   [?fault :seon.error/occurrences ?e]
                                   [?e :seon.error.occurrence/message ?message]]
                                 @connection))
                       (:seon.error/message refusal))
            "the actual acquisition refusal is durable")))))

;;; ---------------------------------------------------------------------------
;;; The prompt's cause is the run's recorded trigger
;;; ---------------------------------------------------------------------------

(deftest a-run-prompts-from-its-opening-database-value
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)
            requests (atom [])]
        (let [work (turn/next-agent-work @connection (request connection))]
          (is (= :open (:seon.turn.work/situation work)))
          (turn/turn {:seon.turn.loop/cluster cluster
                              :seon.turn.work/next work}
                             (Date.)))
        (test-support/transacted! connection
                                  [{:seon.message/id "m-2" :seon.message/to [:seon.agent/id "agent-a"] :seon.message/content "message B"}])
        (with-redefs [ai/complete
                      (recording-completer
                       requests
                       [{:seon.ai/text "(seon.run/complete \"A settled\")"}
                        {:seon.ai/text "(seon.run/complete \"B settled\")"}])]
          (let [call-a (turn/next-agent-work @connection
                                             (request connection))]
            (is (= :call (:seon.turn.work/situation call-a)))
            (turn/turn {:seon.turn.loop/cluster cluster
                                :seon.turn.work/next call-a}
                               (Date.)))
          (let [prompt-a (:seon.ai/prompt (first @requests))]
            (is (str/includes? prompt-a "count the widgets"))
            (is (str/includes? prompt-a ":seon.message/_to")
                "the opening message appears as its real REPL read form")
            (is (not (str/includes? prompt-a "message B"))
                "a message committed after run A opened is absent by
                 construction from A's opening database value"))
          (let [open-b (turn/next-agent-work @connection
                                             (request connection))]
            (is (= :open (:seon.turn.work/situation open-b)))
            (is (= "m-2" (:seon.message/id open-b))
                "the next derived work is the run triggered by B")
            (turn/turn {:seon.turn.loop/cluster cluster
                                :seon.turn.work/next open-b}
                               (Date.)))
          (let [call-b (turn/next-agent-work @connection
                                             (request connection))]
            (is (= :call (:seon.turn.work/situation call-b)))
            (turn/turn {:seon.turn.loop/cluster cluster
                                :seon.turn.work/next call-b}
                               (Date.)))
          (is (= 2 (count @requests)))
          (let [prompt-b (:seon.ai/prompt (second @requests))]
            (is (str/includes? prompt-b "message B")
                "B is visible in the opening database value of its own run")
            (is (str/includes? prompt-b ":seon.runtime/trigger")
                "the next opening message also appears as a real read")))))))

;;; ---------------------------------------------------------------------------
;;; The F2 sealed suite — streaming rides channels, the database keeps facts
;;; seeds 2026072821, 2026072825
;;; ---------------------------------------------------------------------------

(defn- streaming-completer
  "A provider stub that STREAMS: it feeds the turn's own sink a growing
  sequence of complete snapshots — the shape `seon.ai/stream-fold`
  produces — and then returns the settled completion, exactly as a real
  streamed call does. Records the request so a test can prove the turn
  asked for a stream at all."
  [ledger chunks]
  (fn [_projection request]
    (swap! ledger conj request)
    (let [sink (:seon.ai/sink request)]
      (reduce (fn [text chunk]
                (let [grown (str text chunk)]
                  (when sink
                    (sink {:seon.ai/text grown
                           :seon.ai/tokens (count (str/split grown #"\s+"))}))
                  grown))
              ""
              chunks))
    {:seon.ai/text (apply str chunks)}))

(defn- render-proc-for
  "One render proc reading `cluster`'s stream conn, so a test can ping
  it for what production's page derivation would see. Returns the graph
  and its completion."
  [cluster]
  (let [completion (async/promise-chan)
        render-channel (async/chan (async/sliding-buffer 1))
        runtime-eval-channel (async/chan (async/sliding-buffer 1))
        graph (flow.core/create-flow
               {:procs
                {:seon.render.web/render
                 {:proc (seon.flow/var-process
                         #'web/render-step :io
                         {:seon.env/environment @test-environment
                          :seon.render.web/render-channel
                          render-channel
                          :seon.render.web/runtime-eval-channel
                          runtime-eval-channel
                          :seon.render/context-channel (async/chan)
                          :seon.render.web/pages-channel
                          (async/chan (async/sliding-buffer 1))
                          :seon.render.web/registration (atom {})
                          :seon.render.web/latest-packages (atom {})
                          :seon.render.web/interest (atom :all)
                          :seon.render.web/completion completion
                          :seon.render.web/root-agent-id "root"
                          :seon.turn.loop/cluster cluster})}}
                :conns []})
        {:keys [report-chan error-chan]} (flow.core/start graph)]
    (async/go-loop [] (when (async/<! report-chan) (recur)))
    (async/go-loop [] (when (async/<! error-chan) (recur)))
    (flow.core/resume graph)
    {:graph graph
     :completion completion
     :render-channel render-channel}))

(defn- streaming-agents
  [{:keys [graph]}]
  (-> (flow.core/ping graph)
      (get :seon.render.web/render)
      (get :clojure.core.async.flow/state)
      (get :seon.render.web/streaming-agents)))

(defn- await-streaming!
  "Wait until the render proc's ping reports `expected` streaming
  agents. A busy proc may be absent from Flow's partial ping result, so
  retry until the proc publishes the exact state or the shared loud
  backstop fires."
  [proc expected label]
  (test-support/await-event!
   (future
     (loop []
       (if (= expected (streaming-agents proc))
         expected
         (recur))))
   label))

;;; 1. streaming-writes-zero-datoms-test — seed 2026072821

(deftest streaming-writes-zero-datoms-test
  ;; ORACLE: a stubbed STREAMED :call through the real turn, with the
  ;; real channel sink. The datom census over the whole turn contains
  ;; only the attempt row, the capture and the terminal facts — ZERO
  ;; streaming datoms, because the registry no longer contains any
  ;; `:seon.ai.stream/*` attribute to write. The render proc's ping
  ;; shows no partial after the terminal FACT repaints, and
  ;; the settled reply's text equals the fold's final snapshot text.
  ;;
  ;; The measured margin this replaces: a channel hand-off is 0.01 ms
  ;; where the durable transact of the same value is 74-88 ms, so the
  ;; partials were paying ~7,000x to be facts nobody could need once
  ;; the reply had settled.
  (with-cluster
    (fn [cluster]
      (let [stream-channel (async/chan (async/sliding-buffer 1))
            cluster (assoc cluster :seon.turn.loop/stream-channel
                           stream-channel)
            connection (:seon.db/connection cluster)
            proc (render-proc-for cluster)
            requests (atom [])
            chunks ["(seon.run/complete " "\"streamed" " home\")"]]
        (try
          (with-redefs [ai/complete
                        (let [complete (streaming-completer requests chunks)]
                          (fn [projection request]
                            (let [reply (complete projection request)]
                              (is (= 1 (await-streaming! proc 1 [:streaming-observed]))
                                  "the render proc observed the in-flight partial")
                              reply)))]
            (let [basis-before (:max-tx @connection)
                  reports (drive! cluster 10)]
              (is (= [:open :call]
                     (mapv :seon.turn.work/situation reports))
                  "an ordinary turn — a streamed call and a one-shot
                   call return the same completion value")

              (testing "the turn ASKED for a stream and supplied its sink"
                (let [call (first (filter :seon.ai/stream? @requests))]
                  (is (some? call) "the :call arm set :seon.ai/stream?")
                  (is (fn? (:seon.ai/sink call))
                      "and handed the provider the one-line channel sink")))

              (testing "ZERO streaming datoms were committed by the turn"
                (let [db @connection]
                  ;; the census that matters is over the attributes that
                  ;; EXIST: nothing can have been written under a family
                  ;; the registry does not install, so the class is dead
                  ;; by construction rather than by counting rows
                  (is (empty?
                       (db/q '[:find [?ident ...]
                              :where [_ :db/ident ?ident]
                              [(namespace ?ident) ?ns]
                              [(clojure.string/starts-with? ?ns
                                                            "seon.ai.stream")]]
                            db))
                      "the whole :seon.ai.stream/* family is GONE from
                       the registry — a partial row is unrepresentable")
                  (is (pos? (- (:max-tx db) basis-before))
                      "while the turn's OWN facts did commit")))

              (testing "the attempt row and the terminal facts are what landed"
                (is (= 1 (count (db/q '[:find ?e :where
                                       [?e :seon.ai.attempt/ordinal _]]
                                     @connection)))
                    "ONE attempt row for the one paid call")
                (is (= (pr-str (seon.run/complete "streamed home"))
                       (:seon.eval/shown (first (agent-evaluations @connection))))
                    "the terminal evaluation saved the exact completion"))

              (testing "the settled reply's text equals the fold's final
                        snapshot text"
                (is (= (apply str chunks)
                       (db/q '[:find ?text . :where
                               [?turn :seon.turn/reply ?text]
                               [?turn :seon.turn/attempts _]]
                             @connection))
                    "the provider's final reply is stored exactly"))

              (testing "the terminal fact is the stream terminal"
                ;; The provider observed the partial before returning its reply.
                ;; Production's one routing listener offers this
                ;; payload-free interest on the terminal transaction.
                ;; This turn fixture owns no listener, so publish the
                ;; already-observed fact wake at the same proc port.
                (async/offer! (:render-channel proc) ::terminal-fact)
                (is (= 0 (await-streaming! proc 0
                                           [:streaming-superseded]))
                    "the settled facts repaint the page; no channel
                     value carries done"))))
          (finally
            (flow.core/stop (:graph proc))
            (test-support/await-event! (future (async/<!! (:completion proc)))
                                       [:render-proc-stopped])
            (async/close! (:render-channel proc))
            (async/close! stream-channel)))))))

;;; 5. concurrent-streams-share-one-conn-test — seed 2026072825

(deftest concurrent-streams-share-one-conn-test
  ;; ORACLE: two agents streaming onto the ONE sliding-1 conn. A's offer
  ;; can displace B's newest snapshot — accepted at token cadence (R4)
  ;; — and the repair is B's next chunk. The claims that must hold
  ;; anyway: both agents settle at their EXACT texts, which come from
  ;; FACTS and never from the channel; a displaced snapshot is
  ;; superseded by that agent's next offer; and the producers' fold
  ;; threads are NEVER parked, whatever the render side is doing.
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)
            stream-channel (async/chan (async/sliding-buffer 1))
            cluster (assoc cluster :seon.turn.loop/stream-channel
                           stream-channel)]
        ;; a second agent with a trigger of its own
        (test-support/transacted! connection
                                [(agent-row "agent-b")
                                 {:seon.message/id "m-b" :seon.message/to [:seon.agent/id "agent-b"] :seon.message/content "count the sprockets"}])
        ;; NOBODY reads the conn for the whole run: every offer must
        ;; still return immediately, which is what sliding-1 buys
        (let [offer-results (atom [])
              completed-producers (atom #{})
              request-index (atom -1)
              texts {"agent-a" "(seon.run/complete \"alpha\")"
                     "agent-b" "(seon.run/complete \"beta\")"}
              completer
              (fn [_projection request]
                (let [sink (:seon.ai/sink request)
                      ;; The fixture drives agents in declared sorted order.
                      ;; Agent identity is a database fact, not prompt format.
                      agent-id (nth ["agent-a" "agent-b"]
                                    (swap! request-index inc))
                      text (get texts agent-id)]
                  (doseq [n (range 1 (inc (count text)))]
                    (swap! offer-results conj
                           (when sink
                             (sink {:seon.ai/text (subs text 0 n)
                                    :seon.ai/tokens n}))))
                  (swap! completed-producers conj agent-id)
                  {:seon.ai/text text}))]
          (try
            (with-redefs [ai/complete completer]
              (drive-passes! cluster 24))

            (testing "both agents settled at their EXACT texts, from facts"
              (doseq [[agent-id text] texts]
                (let [sources
                      (db/q '[:find [?source ...]
                             :in $ ?agent-id
                             :where
                             [?agent :seon.agent/id ?agent-id]
                             [?run :seon.turn/agent ?agent]
                             [?form :seon.cluster.eval/run ?run]
                             [?form :seon.cluster.eval/source ?source]
                             [?form :seon.cluster.eval/author :agent]]
                           @connection agent-id)
                      shown-results
                      (db/q '[:find [?shown ...]
                             :in $ ?agent-id
                             :where
                             [?agent :seon.agent/id ?agent-id]
                             [?run :seon.turn/agent ?agent]
                             [?e :seon.cluster.eval/run ?run]
                             [?e :seon.eval/shown ?shown]
                             [?e :seon.cluster.eval/author :agent]]
                           @connection agent-id)]
                  (is (= [(pr-str
                           (seon.run/complete (second (edn/read-string text))))]
                         shown-results)
                      (str agent-id " stored its exact completion result"))
                  ;; the stored reply is the durable record of what the
                  ;; provider settled on. It is a fact, committed once,
                  ;; and it is byte-identical to this agent's own text —
                  ;; while the channel, shared and lossy, carried only
                  ;; presentation that either arrived or did not
                  (is (= [text] sources)
                      (str agent-id "'s settled text came from FACTS: "
                           "the stored reply, not the shared channel")))))

            (testing "the producers' fold threads were NEVER parked"
              (is (= (set (keys texts)) @completed-producers)
                  "both producers completed while nobody read the conn")
              (is (= (reduce + (map count (vals texts)))
                     (count @offer-results))
                  "every emitted prefix reached the sink")
              (is (every? true? @offer-results)
                  "every `offer!` completed immediately on sliding-1"))

            (testing "a displaced snapshot is superseded, never lost work"
              ;; only ONE value is ever pending, and it is the newest
              (let [pending (async/poll! stream-channel)]
                (is (contains? #{"agent-a" "agent-b"}
                               (:seon.agent/id pending))
                    "exactly one newest snapshot, whichever agent won
                     the race — the other's next chunk repaired it")
                (is (string? (:seon.turn/id pending))
                    "the partial names the run whose terminal facts
                     supersede it")
                (is (map? (:seon.ai/partial pending))
                    "only complete partial snapshots ride the conn;
                     there is no clear shape")
                (is (nil? (async/poll! stream-channel))
                    "and never a queue: sliding-1 holds exactly one")))
            (finally
              (async/close! stream-channel))))))))

(deftest a-turn-hands-its-clusters-projection-to-every-database-call
  ;; §2.1, measured: a `seon.db` read or write with no handed projection
  ;; rebuilds the complete projection from the database value it is reading —
  ;; every declared schema and function contract recompiled (507-670 ms on
  ;; `projection-lane`, 2026-09-07). The cache is keyed on committed identity,
  ;; so the turn's OWN commits invalidate it: the rebuild is per commit by
  ;; construction, not once. `turn` therefore binds the cluster's projection
  ;; state for the whole pass.
  ;;
  ;; The pass runs on a BARE thread, which conveys no dynamic binding. That is
  ;; what makes this test honest: any projection the turn sees came from the
  ;; handle, not from the fixture's own ambient binding, so the assertion fails
  ;; if `turn` stops carrying it.
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)
            projection-state (:seon.sci.eval/projection-state
                              (:seon.sci.eval/ctx cluster))
            ;; the production shape: `seon.cluster/loop-handle` carries the
            ;; cluster's own projection state on the handle it hands the turn.
            cluster (assoc cluster
                           :seon.sci.eval/projection-state projection-state)
            derivations (atom 0)
            original schema/projection-from-database]
        (is (some? projection-state)
            "the cluster's own ctx carries the projection state the turn binds")
        (with-redefs [schema/projection-from-database
                      (fn [& arguments]
                        (swap! derivations inc)
                        (apply original arguments))
                      ai/complete
                      (fn [_projection _] {:seon.ai/text
                               "(+ 1 1)\n(seon.run/complete \"two\")"})]
          (let [work (turn/next-agent-work @connection (request connection))
                outcome (promise)
                pass (Thread.
                      ^Runnable
                      (fn []
                        (deliver
                         outcome
                         (try
                           (turn/turn
                            {:seon.turn.loop/cluster cluster
                             :seon.turn.work/next work}
                            (Date.))
                           (catch Throwable throwable throwable))))
                      "turn-projection-regression")]
            (is (some? work) "the seeded message is exactly one open trigger")
            (.start pass)
            (.join pass (long (* 1000 test-support/event-backstop-seconds)))
            (is (not (.isAlive pass))
                (str "the turn did not finish within "
                     test-support/event-backstop-seconds
                     "s on a thread carrying no ambient projection"))
            (let [report (deref outcome 0 ::never-delivered)]
              (is (not (instance? Throwable report))
                  (str "the turn threw on a bare thread: " (pr-str report)))
              (is (= :open (:seon.turn.work/situation report))
                  "the pass ran the situation the work derived"))
            (is (zero? @derivations)
                (str "the turn rebuilt the schema projection "
                     @derivations
                     " time(s); it must receive the cluster's projection "
                     "state instead of re-deriving it per commit"))))))))

(deftest generated-opening-preserves-one-provider-turn-budget
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.db/connection cluster)
            calls (atom 0)
            seeded (db/transact!
                    connection
                    (into [{:db/id [:seon.config/cluster "turn-test"]
                            :seon.config.run/max-episode-runs 1}]
                          (turn/generated-run-tx
                           (db/db connection)
                           {:seon.agent/id "agent-a"
                            :seon.turn/id "budget-opening"
                            :seon.turn/opened-tx "datomic.tx"
                            :seon.turn/starting-ns [:seon.ns/name 'my.agents.agent-a]
                            :seon.turn/trigger [:seon.message/id "m-1"]})))]
        (is (some? (:db-after seeded)) (pr-str (:seon.db.write.attempt/request-id seeded)))
        (when (:seon.db.write.attempt/request-id seeded) (throw (ex-info "Budget fixture refused" seeded)))
        (is (= 0 (turn/episode-runs (db/db connection) "agent-a")))
        (is (= 1 (turn/turns-left (db/db connection) "agent-a")))
        (with-redefs [ai/complete (fn [_projection _] (swap! calls inc) {:seon.ai/text "(+ 20 22)"})]
          (drive! cluster 80))
        (let [database (db/db connection)
              opening (db/pull database '[*] [:seon.turn/id "budget-opening"])
              provider-turns (db/q '[:find (count ?turn) .
                                     :where [?agent :seon.agent/id "agent-a"]
                                     [?turn :seon.turn/agent ?agent]
                                     [?turn :seon.turn/attempts _]] database)]
          (is (some? (:seon.turn/closed-tx opening)))
          (is (nil? (:seon.turn/attempts opening)))
          (is (= 1 @calls) "budget one permits exactly one provider reply after the opening")
          (is (= 1 provider-turns))
          (is (= 1 (turn/episode-runs database "agent-a")))
          (is (= 0 (turn/turns-left database "agent-a")))
          (is (nil? (turn/next-agent-work database (request connection)))))))))
