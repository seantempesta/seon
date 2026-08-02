(ns seon.cluster.turn-test
  "Coverage for the turn's four situations (N3, package 2).

  Author-written rather than orchestrator-sealed: `turn` and `step` are
  the two functions the sealed loop suite does not reach, because a
  full turn needs the guarded eval that the `seon.sci.eval` adoption
  will bring. The evaluator is INJECTED as a qualified symbol, so this
  drives every branch now with a fake one and the adoption plugs in
  without touching a line here. The model call is stubbed for the same
  reason the sealed AI suite has no network: a suite that needs a paid
  call is a suite nobody runs."
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow.core]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.api :as d]
            [my.run :as my.run]
            [seon.ai :as ai]
            [seon.flow :as seon.flow]
            [seon.render.web :as web]
            [seon.cluster :as cluster]
            [seon.cluster.loop :as cluster.loop]
            [seon.error :as error]
            [seon.render :as render]
            [seon.cluster.message :as message]
            [seon.cluster.prompt :as prompt]
            [seon.cluster.run :as run]
            [seon.cluster.store :as store]
            [seon.cluster.work :as work]
            [seon.config :as config]
            [seon.instrument :as instrument]
            [sci.core :as sci.core]
            [seon.sci.admit :as admit]
            [seon.sci.eval :as sci.eval]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.test-support :as test-support])
  (:import [java.util Date]))

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
;;; to evaluate to, without a sci context
(def ^:dynamic *evaluation*
  ;; presence is the state: a clean evaluation is result-edn with no
  ;; error and no interrupted-at — there is no status key
  {:seon.cluster.eval/result-edn "1"
   :seon.sci.admit/value 1})

(defn fake-evaluate
  "The stand-in evaluator, for the cases that pin an exact value."
  [_request]
  *evaluation*)

(defn- semantic-result
  [result-edn]
  (#'admit/semantic-value (edn/read-string result-edn)))

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
  {:seon.cluster.agent/id agent-id})

(defn- with-cluster [body]
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :schema-flexibility :write}
        _ (d/create-database configuration)
        connection (d/connect configuration)]
    (try
      (seon.flow/install-work-launcher!
       {::seon.flow/configuration
        {:seon.config.flow.compute/queue-depth 10
         :seon.config.flow.compute/concurrency 2}})
      (d/transact connection
                  (schema.datahike/malli->datahike-schema
                   (schema/canonical-database-attributes)))
      (d/transact connection
                  [{:seon.ns/name 'clojure.set}
                   {:seon.ns/name 'clojure.test}
                   {:seon.ns/name 'seon.schema}
                   (agent-row "agent-a")
                   ;; The loop resolves the one current config row at every
                   ;; :call. Compile the complete production shape, changing
                   ;; only the values that make this an inert paid-call
                   ;; fixture. A sparse hand-built row would pin the deleted
                   ;; boot-captured target shape.
                   (:seon.config/desired-row
                    (config/compile-manifest
                     {:seon.boot/cluster-name "turn-test"
                      :seon.config/manifest
                      {:seon.config.run/max-episode-runs 100
                       :seon.config.ai/endpoint "http://127.0.0.1:1/v1"
                       :seon.config.ai/model "probe"
                       :seon.config.ai/max-tokens 32
                       :seon.config.ai/api-key-variable "SEON_AI_TEST_KEY"
                       :seon.config.ai/timeout-ms 200
                       :seon.config.ai.retry/base-delay-ms 1
                       :seon.config.ai.retry/multiplier 2.0
                       :seon.config.ai.retry/jitter-fraction 0.0
                       :seon.config.ai.retry/maximum-delay-ms 1
                       :seon.config.ai.retry/maximum-retries 0
                       :seon.config.ai.retry/maximum-total-delay-ms 0}}))
                   {:seon.cluster.message/id "m-1"
                    :seon.cluster.message/to [:seon.cluster.agent/id "agent-a"]
                    :seon.cluster.message/content "count the widgets"
                    :seon.cluster.message/at now}])
      ;; This fixture installs the production schema but deliberately has no
      ;; packaged program graph. Static admission derives its resolver context
      ;; from that graph, so exercising it here would turn every ordinary
      ;; `my.*` call into a fixture-induced unresolved-namespace refusal.
      ;; The source-populated restart test owns the integrated lint/eval proof;
      ;; these turn tests keep their narrower transaction and REPL semantics.
      (with-redefs
        [cluster.loop/lint-form
         (fn [{source :seon.cluster.loop/source}] source)]
        (body {:seon.store/branch-connection connection
               :seon.cluster/name "turn-test"
               :seon.cluster.run/process process
               :seon.sci.eval/ctx (sci.eval/build-base-ctx)
               :seon.cluster.wake/channel
               (clojure.core.async/chan (clojure.core.async/sliding-buffer 1))
             :seon.cluster.loop/evaluate 'seon.cluster.turn-test/fake-evaluate
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
               {:seon.config.eval.result/max-depth 6
                :seon.config.eval.result/max-collection 8
                :seon.config.eval.result/max-string 4096
                :seon.config.eval.result/max-nodes 256}}))
      (finally
        (seon.flow/stop-installed-work-launcher!)
        (d/release connection)
        (d/delete-database configuration)))))

(defn- request
  "The AGENT-SCOPED work request (F2 §3.2).
  The global request died with the central pass; every site in this
  suite already knows its agent, and the fixture's own agent is the
  default. This suite asserts the TURN, which survives whole — so
  re-pointing here is plumbing, not an oracle change."
  ([connection] (request connection "agent-a"))
  ([_connection agent-id]
   {:seon.cluster.agent/id agent-id
    :seon.cluster.run/process process
    :seon.cluster.work/now (Date.)}))

(defn- agent-ids
  "Every agent in facts, oldest name first.
  Production gives each of them its OWN turn proc; this suite drives
  them in one thread, so the driver asks each agent's own derivation in
  turn rather than asking a global one that no longer exists."
  [db]
  (sort (d/q '[:find [?id ...] :where [?e :seon.cluster.agent/id ?id]] db)))

(defn- settle-orphans!
  "Settle each agent's OWN orphan, the way its turn proc would."
  [cluster connection now]
  (doseq [agent-id (agent-ids @connection)]
    (when-let [orphan (work/interruption @connection agent-id)]
      (cluster.loop/settle-interruption! cluster
                                         (:seon.cluster.run/id orphan)
                                         now))))

(defn- any-agent-work
  "The first agent with work, derived through the AGENT-SCOPED
  derivation. Suite plumbing: production has no global pass — each
  agent's proc derives for itself — so this asks each in turn."
  [connection]
  (some (fn [agent-id]
          (work/next-agent-work @connection (request connection agent-id)))
        (agent-ids @connection)))

(defn- drive-passes!
  "Run the loop's own pass — settle, then derive, then turn — until idle.
  This drives what `step` drives, so a test sees what production sees."
  [cluster limit]
  (let [connection (:seon.store/branch-connection cluster)]
    (loop [passes 0]
      (let [now (Date.)]
        (settle-orphans! cluster connection now)
        (when-let [work (any-agent-work connection)]
          (when (< passes limit)
            (cluster.loop/turn {:seon.cluster.loop/cluster cluster
                                :seon.cluster.work/next work}
                               now)
            (recur (inc passes))))))))

(defn- drive!
  "Run passes until the loop says idle, or `limit` passes have run."
  [cluster limit]
  (let [connection (:seon.store/branch-connection cluster)]
    (loop [passes 0 reports []]
      (let [request (request connection)
            work (any-agent-work connection)]
        (if (or (nil? work) (>= passes limit))
          reports
          (recur (inc passes)
                 (conj reports
                       (cluster.loop/turn
                        {:seon.cluster.loop/cluster cluster
                         :seon.cluster.work/next work}
                        (:seon.cluster.work/now request)))))))))

(defn- drive-agent!
  "Run one agent's passes until that agent is idle, or `limit` is reached."
  [cluster agent-id limit]
  (let [connection (:seon.store/branch-connection cluster)]
    (loop [passes 0
           reports []]
      (let [request (request connection agent-id)
            work (work/next-agent-work @connection request)]
        (if (or (nil? work) (>= passes limit))
          reports
          (recur (inc passes)
                 (conj reports
                       (cluster.loop/turn
                        {:seon.cluster.loop/cluster cluster
                         :seon.cluster.work/next work}
                        (:seon.cluster.work/now request)))))))))

(defn- running-refusal-receipt!
  "Create one held run with ordinal zero running, for the terminal seam."
  [cluster tag]
  (let [connection (:seon.store/branch-connection cluster)
        run-id (str "terminal-refusal-" tag)
        at (Date.)]
    (d/transact
     connection
     (into
      (run/open-tx {:seon.cluster.run/id run-id
                    :seon.cluster.run/agent
                    [:seon.cluster.agent/id "agent-a"]
                    :seon.cluster.run/opened-at at})
      (run/claim-tx {:seon.cluster.run/id run-id
                     :seon.cluster.run/process process
                     :seon.cluster.run/live-processes #{process}
                     :seon.cluster.run/now at})))
    (d/transact
     connection
     (run/receipt-start-tx {:seon.cluster.run/id run-id
                            :seon.cluster.eval/ordinal 0
                            :seon.cluster.eval/at at}))
    {:seon.cluster.run/id run-id
     :seon.cluster.run/process process
     :seon.cluster.run.form/ordinal 0}))

(defn- terminal-refused!
  [cluster outcome receipt]
  ((deref (ns-resolve 'seon.cluster.loop 'terminal-refused!))
   cluster outcome (Date.)
   {:seon.cluster.agent/id "agent-a"
    :seon.cluster.run/id (:seon.cluster.run/id receipt)}
   receipt))

(defn- transition-transaction?
  [transaction transition]
  (let [tx-data (if (map? transaction)
                  (:tx-data transaction)
                  transaction)]
    (boolean
     (some (fn [operation]
             (and (vector? operation)
                  (= :db.fn/call (first operation))
                  (= transition (second operation))))
           tx-data))))

(defn- recover-terminal-refusal!
  [cluster receipt]
  (let [connection (:seon.store/branch-connection cluster)
        recovered-process "terminal-refusal-recovery"]
    (d/transact
     connection
     (run/recover-tx
      {:seon.cluster.run/id (:seon.cluster.run/id receipt)
       :seon.cluster.run/live-processes #{recovered-process}
       :seon.cluster.run/now (Date.)}))
    (some?
     (:seon.cluster.run/closed-at
      (d/pull @connection
              '[:seon.cluster.run/closed-at]
              [:seon.cluster.run/id
               (:seon.cluster.run/id receipt)])))))

(deftest a-whole-turn-runs-a-REAL-sci-evaluation-end-to-end
  ;; the injection seam, proven: the same qualified symbol the cluster
  ;; handle carries now points at seon.sci.eval, so this drives a real
  ;; armed boundary, a real fork, and real admission — with no model
  ;; call, because the reply is the only thing stubbed.
  (with-cluster
    (fn [cluster]
      (let [cluster (assoc cluster :seon.cluster.loop/evaluate
                           'seon.sci.eval/evaluate)
            connection (:seon.store/branch-connection cluster)]
        (with-redefs [ai/complete
                      (fn [_] {:seon.ai/text
                               (str "(def widgets (map inc (range 3)))\n"
                                    "widgets\n"
                                    "(my.run/complete (str \"counted \" "
                                    "(reduce + widgets)))")})]
          (let [reports (drive! cluster 10)]
            (is (= [:open :call :resume]
                   (mapv :seon.cluster.work/situation reports))
                "open, model, then ONE fold — the whole plan over one ctx")
            (testing "the receipts carry what sci actually produced"
              (let [results (into {}
                                  (map (fn [[ordinal result-edn]]
                                         [ordinal
                                          (semantic-result result-edn)]))
                                  (d/q '[:find ?ordinal ?edn
                                         :where
                                         [?e :seon.cluster.eval/ordinal ?ordinal]
                                         [?e :seon.cluster.eval/result-edn ?edn]]
                                       @connection))]
                (is (= {:seon.sci.admit/reference "sci.lang.Var"
                        :seon.sci.admit/name
                        "#'my.agents.agent-a/widgets"}
                       (get results 0))
                    "a def evaluates to a VAR, admission names it without
                     dereferencing it, and it landed in the AGENT'S
                     namespace — no in-ns anywhere")
                (is (= '(1 2 3) (get results 1))
                    "form 1 SAW form 0's def — one ctx per run, not per
                     form — and its lazy sequence came back REALIZED")
                (is (= (my.run/complete "counted 6")
                       (get results 2))
                    "and the disposition round-tripped through admission")))
            (testing "every receipt settled clean — no interrupt, no error"
              (is (= 3 (count (d/q '[:find ?e :where
                                     [?e :seon.cluster.eval/result-edn _]]
                                   @connection))))
              (is (empty? (d/q '[:find ?e :where
                                 (or [?e :seon.cluster.eval/error _]
                                     [?e :seon.cluster.eval/interrupted-at _])]
                               @connection))
                  "no error and no cut instant anywhere — presence is
                   the state"))
            (is (some? (d/q '[:find ?c . :where
                              [_ :seon.cluster.run/closed-at ?c]] @connection))
                "and the run closed")))))))

(deftest agent-code-with-defn-and-println-folds-green-without-in-ns
  ;; the live drive's two wiring failures, as one falsifier: the model
  ;; writes ordinary Clojure — a defn, a println, a call — and never
  ;; mentions a namespace, because it does not have to.
  (with-cluster
    (fn [cluster]
      (let [cluster (assoc cluster :seon.cluster.loop/evaluate
                           'seon.sci.eval/evaluate)
            connection (:seon.store/branch-connection cluster)]
        (with-redefs [ai/complete
                      (fn [_] {:seon.ai/text
                               (str "(defn widget-count [n] (* n 3))\n"
                                    "(println \"counting\" (widget-count 4)"
                                    " \"in\" (str *ns*))\n"
                                    "(my.run/complete (str \"there are \""
                                    " (widget-count 4)))")})]
          (drive! cluster 10)
          (testing "every form ran and settled clean"
            (is (= 3 (count (d/q '[:find ?e :where
                                   [?e :seon.cluster.eval/result-edn _]]
                                 @connection))))
            (is (empty? (d/q '[:find ?e :where
                               (or [?e :seon.cluster.eval/error _]
                                   [?e :seon.cluster.eval/interrupted-at _])]
                             @connection))))
          (testing "the def landed in the agent's namespace"
            (is (re-find #"my\.agents\.agent-a/widget-count"
                         (d/q '[:find ?edn . :where
                                [?e :seon.cluster.eval/ordinal 0]
                                [?e :seon.cluster.eval/result-edn ?edn]]
                              @connection))))
          (testing "and what it PRINTED is durable, not dropped"
            (let [output (d/q '[:find ?out . :where
                                [?e :seon.cluster.eval/ordinal 1]
                                [?e :seon.cluster.eval/output ?out]]
                              @connection)]
              ;; content, not exact whitespace: sci's println does not
              ;; leave its trailing newline in the writer, and pinning
              ;; that would be pinning sci's io rather than our contract
              (is (= "counting 12 in my.agents.agent-a" (str/trim output)))))
          (testing "and the disposition closed the run"
            (is (some? (d/q '[:find ?c . :where
                              [_ :seon.cluster.run/closed-at ?c]]
                            @connection)))))))))

(deftest mixed-plan-publishes-only-the-contracted-function
  (with-cluster
    (fn [cluster]
      (let [cluster (assoc cluster :seon.cluster.loop/evaluate
                           'seon.sci.eval/evaluate)
            connection (:seon.store/branch-connection cluster)]
        (d/transact connection
                    [{:seon.ns/name 'my.agents.agent-a}
                     {:seon.cluster.agent/id "agent-a"
                      :seon.cluster.agent/namespace
                      [:seon.ns/name 'my.agents.agent-a]}])
        (with-redefs
          [ai/complete
           (fn [_]
             {:seon.ai/text
              (str
               "(defn ^{:malli/schema [:=> [:cat :int] :int]} durable "
               "[x] (inc x))\n"
               "(def x 42)\n"
               "(durable x)")})]
          (drive! cluster 10)
          (testing "all forms leave inert receipt history"
            (is (= 3
                   (count
                    (d/q '[:find ?receipt
                           :where
                           [?receipt :seon.cluster.eval/result-edn _]]
                         @connection)))))
          (testing "installation derives from db-after before the next form"
            (is (= 43
                   (semantic-result
                    (d/q '[:find ?result .
                          :where
                          [?receipt :seon.cluster.eval/ordinal 2]
                          [?receipt :seon.cluster.eval/result-edn ?result]]
                         @connection)))))
          (testing "only the contracted defn enters the program graph"
            (is (= #{"my.agents.agent-a/durable"}
                   (set
                    (d/q '[:find [?sym ...]
                           :where
                           [_ :seon.fn/sym ?sym]]
                         @connection))))
            (is (empty?
                 (d/q '[:find ?entity
                        :where
                        [?entity :seon.fn/sym "my.agents.agent-a/x"]]
                      @connection)))))))))

(deftest ns-unmap-retracts-the-owned-function-after-the-terminal-commit
  (with-cluster
    (fn [cluster]
      (let [cluster (assoc cluster :seon.cluster.loop/evaluate
                           'seon.sci.eval/evaluate)
            connection (:seon.store/branch-connection cluster)]
        (d/transact connection
                    [{:seon.ns/name 'my.agents.agent-a}
                     {:seon.cluster.agent/id "agent-a"
                      :seon.cluster.agent/namespace
                      [:seon.ns/name 'my.agents.agent-a]}])
        (with-redefs
          [ai/complete
           (fn [_]
             {:seon.ai/text
              (str
               "(defn ^{:malli/schema [:=> [:cat :int] :int]} obsolete "
               "[x] (inc x))\n"
               "(ns-unmap 'my.agents.agent-a 'obsolete)")})]
          (drive! cluster 10)
          (is (= 2
                 (count
                  (d/q '[:find ?receipt
                         :where
                         [?receipt :seon.cluster.eval/result-edn _]]
                       @connection)))
              "the declaration and deletion both leave receipts")
          (is (nil?
               (d/pull @connection
                       [:seon.fn/sym]
                       [:seon.fn/sym "my.agents.agent-a/obsolete"]))
              "the explicit delete retracts the durable identity"))))))

(deftest reply-reading-follows-evaluated-alias-and-dynamic-require-state
  (with-cluster
    (fn [cluster]
      (let [cluster (assoc cluster :seon.cluster.loop/evaluate
                           'seon.sci.eval/evaluate)
            connection (:seon.store/branch-connection cluster)]
        (with-redefs
          [ai/complete
           (fn [_]
             {:seon.ai/text
              (str
               "(alias 'str 'clojure.string)\n"
               "{:x ::str/after-alias}\n"
               "(require (if true '[clojure.set :as sets] "
               "'[clojure.string :as sets]))\n"
               "{:x ::sets/after-dynamic-require}")})]
          (drive! cluster 10)
          (let [results
                (into {}
                      (map (fn [[ordinal result-edn]]
                             [ordinal (semantic-result result-edn)]))
                      (d/q '[:find ?ordinal ?result
                             :where
                             [?receipt :seon.cluster.eval/ordinal ?ordinal]
                             [?receipt :seon.cluster.eval/result-edn ?result]]
                           @connection))]
            (is (= {:x :clojure.string/after-alias} (get results 1)))
            (is (= {:x :clojure.set/after-dynamic-require} (get results 3))
                "later sources are read only after prior namespace effects")))))))

(deftest qualified-dynamic-ns-unmap-is-durable-in-a-fresh-context
  (with-cluster
    (fn [cluster]
      (let [cluster (assoc cluster :seon.cluster.loop/evaluate
                           'seon.sci.eval/evaluate)
            connection (:seon.store/branch-connection cluster)
            function-sym "my.agents.agent-a/dynamic-obsolete"]
        (with-redefs
          [ai/complete
           (fn [_]
             {:seon.ai/text
              (str
               "(defn ^{:malli/schema [:=> [:cat :int] :int]} "
               "dynamic-obsolete [x] (inc x))\n"
               "(clojure.core/ns-unmap "
               "(find-ns 'my.agents.agent-a) (symbol \"dynamic-obsolete\"))")})]
          (drive! cluster 10)
          (is (nil? (d/pull @connection [:db/id]
                            [:seon.fn/sym function-sym])))
          (let [fresh (sci.eval/cluster-ctx @connection)]
            (is (nil? (sci.core/eval-string*
                       fresh
                       "(resolve 'my.agents.agent-a/dynamic-obsolete)"))
                "acquisition cannot resurrect the deleted function")))))))

(deftest absent-foreign-ns-unmap-commits-and-mutates-the-run-sci-ctx
  (with-cluster
    (fn [cluster]
      (let [cluster (assoc cluster :seon.cluster.loop/evaluate
                           'seon.sci.eval/evaluate)
            connection (:seon.store/branch-connection cluster)
            original-evaluate sci.eval/evaluate
            evaluated-ctx (atom nil)]
        (with-redefs
          [ai/complete
           (fn [_]
             {:seon.ai/text
              "(ns-unmap 'clojure.string 'upper-case)"})
           sci.eval/evaluate
           (fn [request]
             (reset! evaluated-ctx (:seon.sci.eval/ctx request))
             (original-evaluate request))]
          (drive! cluster 10)
          (is (nil?
               (semantic-result
                (d/q '[:find ?result .
                        :where
                        [?receipt :seon.cluster.eval/ordinal 0]
                        [?receipt :seon.cluster.eval/result-edn ?result]]
                     @connection)))
              "an absent program identity is still a successful REPL ns-unmap")
          (is (nil?
               (sci.core/eval-string*
                @evaluated-ctx
                "(resolve 'clojure.string/upper-case)"))
              "the committed deletion installs against the run-local ctx"))))))

(deftest import-only-ns-unmap-installs-exactly-after-its-context-commit
  (with-cluster
    (fn [cluster]
      (let [cluster (assoc cluster :seon.cluster.loop/evaluate
                           'seon.sci.eval/evaluate)
            connection (:seon.store/branch-connection cluster)
            original-evaluate sci.eval/evaluate
            evaluated-ctx (atom nil)]
        (with-redefs
          [ai/complete
           (fn [_]
             {:seon.ai/text
              (str "(clojure.core/ns-unmap *ns* (symbol \"String\"))\n"
                   "(resolve 'String)")})
           sci.eval/evaluate
           (fn [request]
             (reset! evaluated-ctx (:seon.sci.eval/ctx request))
             (original-evaluate request))]
          (drive! cluster 10)
          (is (nil?
               (semantic-result
                (d/q '[:find ?result .
                        :where
                        [?receipt :seon.cluster.eval/ordinal 1]
                        [?receipt :seon.cluster.eval/result-edn ?result]]
                     @connection)))
              "the next form sees the committed import removal")
          (is (nil?
               (:val
                (sci.core/eval-string+
                 @evaluated-ctx
                 "(resolve 'String)"
                 {:ns (sci.core/create-ns 'my.agents.agent-a)})))
              "the supplied run ctx receives the exact isolated state")
          (let [namespace-row
                (d/pull @connection
                        '[* {:seon.ns/imports [*]}]
                        [:seon.ns/name 'my.agents.agent-a])
                import-mask
                (some #(when (= 'String (:seon.ns.import/local %)) %)
                      (:seon.ns/imports namespace-row))
                fresh (sci.eval/cluster-ctx @connection)]
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
      (let [cluster (assoc cluster :seon.cluster.loop/evaluate
                           'seon.sci.eval/evaluate)
            original-evaluate sci.eval/evaluate
            evaluated-ctx (atom nil)
            transact! store/transact!]
        (with-redefs
          [ai/complete
           (fn [_]
             {:seon.ai/text
              "(clojure.core/ns-unmap *ns* (symbol \"String\"))"})
           sci.eval/evaluate
           (fn [request]
             (reset! evaluated-ctx (:seon.sci.eval/ctx request))
             (original-evaluate request))
           store/transact!
           (fn [target transaction]
             (let [tx-data (if (map? transaction)
                             (:tx-data transaction)
                             transaction)
                   namespace-mutation?
                   (some
                    (fn [operation]
                      (and (vector? operation)
                           (= :db.fn/call (first operation))
                           (= #'run/receipt-settle-call (second operation))
                           (some-> (get-in operation
                                         [2 :seon.sci.eval/program-row
                                          :seon.ns/source])
                                   (str/includes? "ns-unmap"))))
                    tx-data)]
               (if namespace-mutation?
                 {:seon.error/kind :seon.db/rejected
                  :seon.error/message "injected namespace refusal"
                  :seon.error/data {:error :transact/namespace}}
                 (transact! target transaction))))]
          (drive! cluster 10)
          (is (nil?
               (d/q '[:find ?import .
                      :in $ ?namespace ?local
                      :where
                      [?namespace-entity :seon.ns/name ?namespace]
                      [?namespace-entity :seon.ns/imports ?import]
                      [?import :seon.ns.import/local ?local]]
                    @(:seon.store/branch-connection cluster)
                    'my.agents.agent-a
                    'String))
              "a refused mask never reaches the database")
          (is (some?
               (:val
                (sci.core/eval-string+
                 @evaluated-ctx
                 "(resolve 'String)"
                 {:ns (sci.core/create-ns 'my.agents.agent-a)})))
              "the isolated import mask is discarded on refusal"))))))

(deftest import-addition-is-ordinary-data-and-reacquires-exactly
  (with-cluster
    (fn [cluster]
      (let [cluster (assoc cluster :seon.cluster.loop/evaluate
                           'seon.sci.eval/evaluate)
            connection (:seon.store/branch-connection cluster)]
        (with-redefs
          [ai/complete
           (fn [_]
             {:seon.ai/text
              "(import java.lang.String)"})]
          (drive! cluster 10)
          (let [namespace-row
                (d/pull @connection
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
  ;; refused event settles and closes atomically, records once, and generates
  ;; neither another pass nor another trigger. `ns-unmap` itself has ordinary
  ;; SCI REPL semantics and is not the source of this refusal.
  (doseq [[label cap next-turn?]
          [["below the episode cap" 2 true]
           ["at the episode cap" 1 false]]]
    (testing label
      (with-cluster
        (fn [cluster]
          (let [cluster (assoc cluster :seon.cluster.loop/evaluate
                               'seon.sci.eval/evaluate)
                connection (:seon.store/branch-connection cluster)
                calls (atom [])
                transact! store/transact!
                original-install! sci.eval/install-program-row!
                installations (atom [])]
            (d/transact
             connection
             [(agent-row "peer")
              {:seon.config/cluster "turn-test"
               :seon.config.run/max-episode-runs cap}
              {:seon.ns/name 'seon.config}
              {:seon.fn/sym "seon.config/defaults"
               :seon.fn/ns [:seon.ns/name 'seon.config]
               :seon.fn/source "(defn defaults [] {})"
               :seon.fn/spec "[:=> [:cat] :map]"}])
            ;; This test plants a pre-existing program row directly rather
            ;; than producing it through eval. Finish that cold fixture setup
            ;; before the first turn; live turns never reacquire facts.
            (sci.eval/acquire!
             {:seon.sci.eval/ctx (:seon.sci.eval/ctx cluster)
              :seon.db/db @connection})
            (with-redefs
              [ai/complete
               (fn [request]
                 (swap! calls conj request)
                 {:seon.ai/text
                  (if (= 1 (count @calls))
                    "(ns-unmap (quote seon.config) (quote defaults))"
                    "(my.run/complete \"recovered\")")})
               store/transact!
               (fn [target transaction]
                 (let [tx-data (if (map? transaction)
                                 (:tx-data transaction)
                                 transaction)
                       deletion?
                       (some
                        (fn [operation]
                          (and (vector? operation)
                               (= :db.fn/call (first operation))
                               (= #'run/receipt-settle-call
                                  (second operation))
                               (seq
                                (get-in operation
                                        [2 :seon.sci.eval/program-row
                                         :seon.program/delete-identities]))))
                        tx-data)]
                   (if deletion?
                     {:seon.error/kind :seon.db/rejected
                      :seon.error/message
                      "injected terminal program refusal"
                      :seon.error/data {:error :transact/program}}
                     (transact! target transaction))))
               sci.eval/install-program-row!
               (fn [request]
                 (swap! installations conj request)
                 (original-install! request))]
              (let [reports (drive-agent! cluster "agent-a" 10)
                    db @connection
                    receipts
                    (d/q '[:find [(pull ?receipt [*]) ...]
                           :where
                           [?receipt :seon.cluster.eval/id _]]
                         db)
                    error-facts
                    (d/q '[:find [?error ...]
                           :where
                           [?error :seon.error/id _]]
                         db)]
                (is (= [:open :call :resume]
                       (mapv :seon.cluster.work/situation reports))
                    "the refusal closes in its terminal pass")
                (is (= 1 (count receipts)))
                (is (run/terminal? (first receipts)))
                (is (= :seon.db/rejected
                       (:seon.error/kind (first receipts))))
                (is (= :seon.db/rejected
                       (:seon.error/kind
                        (edn/read-string
                         (:seon.cluster.eval/result-edn
                          (first receipts))))))
                (is (= 1 (count error-facts))
                    "one refusal records exactly one durable error")
                (is (empty? (drive-agent! cluster "agent-a" 10))
                    "the event derives zero further passes")
                (is (false?
                     (work/more-agent-work?
                      @connection (request connection "agent-a")))
                    "the production self-rewake predicate is false")
                (is (= 1 (count @calls))
                    "the event never re-calls the model")
                (is (empty?
                     (d/q '[:find ?message
                            :where
                            [?message :seon.cluster.message/about ?error]
                            [?error :seon.error/id _]]
                          @connection))
                    "a returned error value creates no delivery wake")
                (is (not-any?
                     #(seq (get-in % [:seon.sci.eval/program-row
                                      :seon.program/delete-identities]))
                     @installations)
                    "the refused deletion never installs")
                (is (= "(defn defaults [] {})"
                       (:seon.fn/source
                        (d/pull db
                                [:seon.fn/source]
                                [:seon.fn/sym "seon.config/defaults"])))
                    "commit-first leaves the program row unchanged")
                (d/transact
                 connection
                 [{:seon.cluster.message/id "peer-follow-up"
                   :seon.cluster.message/to
                   [:seon.cluster.agent/id "agent-a"]
                   :seon.cluster.message/from
                   [:seon.cluster.agent/id "peer"]
                   :seon.cluster.message/content "Try again after reading the error."
                   :seon.cluster.message/at now}])
                (let [next-reports (drive-agent! cluster "agent-a" 10)]
                  (if next-turn?
                    (do
                      (is (= [:open :call :resume]
                             (mapv :seon.cluster.work/situation next-reports)))
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
                             (mapv :seon.cluster.message/id
                                   (work/deferred-triggers
                                    @connection "agent-a"))))))
                (is (= 1
                       (count
                        (d/q '[:find ?error
                               :where [?error :seon.error/id _]]
                             @connection)))
                    "later work never re-records the original event"))))))))))

(deftest terminal-refusal-settlement-is-bounded-checked-and-recoverable
  ;; Attempt-5's exact hostile matrix. The first four values are valid
  ;; refusal sources after admission and must settle. The fifth cannot
  ;; produce a valid registered error fact, so it must become a core
  ;; fault before Datahike sees a transaction. A separately injected
  ;; commit refusal proves the returned outcome is checked too.
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.store/branch-connection cluster)
            passing
            [["ordinary"
              {:seon.error/kind :seon.db/rejected
               :seon.error/message "ordinary terminal program refusal."}]
             ["bounded-hostile-data"
              {:seon.error/kind :seon.db/rejected
               ;; Error messages remain strings in the durable schema. The
               ;; session-repair printer bounds structured data; it does not
               ;; turn a truncated string node into a string a second time.
               :seon.error/message (apply str (repeat 4000 "X"))
               :seon.error/data {:big (vec (range 20000))}}]
             ["empty-message"
              {:seon.error/kind :seon.db/unknown-failure
               :seon.error/message ""}]
             ["absent-message"
              {:seon.error/kind :seon.db/unknown-failure}]]]
        (doseq [[tag outcome] passing]
          (let [receipt (running-refusal-receipt! cluster tag)
                run-id (:seon.cluster.run/id receipt)]
            (is (true? (terminal-refused! cluster outcome receipt))
                (str tag " settled"))
            (let [settled
                  (d/pull @connection '[*]
                          [:seon.cluster.eval/id (pr-str [run-id 0])])
                  run (d/pull @connection '[*]
                              [:seon.cluster.run/id run-id])]
              (is (run/terminal? settled) (str tag " terminalized receipt"))
              (is (some? (:seon.cluster.run/closed-at run))
                  (str tag " closed run"))
              (is (nil? (:seon.cluster.run/process run))
                  (str tag " released custody")))))
        (testing "the bounded admission codec owns hostile payload size"
          (is (every?
               #(<= (count (:seon.error/message %)) 4096)
               (d/q '[:find [(pull ?error
                                   [:seon.error/message]) ...]
                      :where [?error :seon.error/id _]]
                    @connection))
              "no unbounded source string reaches the settlement"))

        (testing "attempt 5: a schema-invalid admitted error faults closed"
          (let [receipt
                (running-refusal-receipt! cluster "string-kind")
                run-id (:seon.cluster.run/id receipt)
                failure
                (try
                  (terminal-refused!
                   cluster
                   {:seon.error/kind "not-a-keyword"
                    :seon.error/message "hostile kind"}
                   receipt)
                  nil
                  (catch clojure.lang.ExceptionInfo exception
                    exception))]
            (is (= :seon.cluster.loop/terminal-refusal-settlement-refused
                   (:seon.error/kind (ex-data failure)))
                "invalid construction is a named core fault, not true")
            (is (false?
                 (run/terminal?
                  (d/pull @connection '[*]
                          [:seon.cluster.eval/id (pr-str [run-id 0])]))))
            (is (true?
                 (recover-terminal-refusal! cluster receipt))
                "boot-shape recovery marks interruption then closes the run")
            (is (some?
                 (:seon.cluster.eval/interrupted-at
                  (d/pull @connection '[*]
                          [:seon.cluster.eval/id (pr-str [run-id 0])]))))))

        (testing "instrumentation cannot preempt the named construction fault"
          (let [receipt
                (running-refusal-receipt! cluster "instrumented-string-kind")
                run-id (:seon.cluster.run/id receipt)
                failure
                (try
                  (instrument/apply! {:seon.config/on-core-error :panic})
                  (try
                    (terminal-refused!
                     cluster
                     {:seon.error/kind "not-a-keyword"
                      :seon.error/message "hostile kind"}
                     receipt)
                    nil
                    (catch clojure.lang.ExceptionInfo exception
                      exception))
                  (finally
                    (instrument/remove!)))]
            (is (= :seon.cluster.loop/terminal-refusal-settlement-refused
                   (:seon.error/kind (ex-data failure)))
                "armed and unarmed construction expose the same seam")
            (is (false?
                 (run/terminal?
                  (d/pull @connection '[*]
                          [:seon.cluster.eval/id (pr-str [run-id 0])]))))
            (is (true? (recover-terminal-refusal! cluster receipt)))))

        (testing "even a post-construction commit refusal cannot return true"
          (let [receipt
                (running-refusal-receipt! cluster "injected-commit")
                run-id (:seon.cluster.run/id receipt)
                transact! store/transact!
                failure
                (with-redefs
                  [store/transact!
                   (fn [target transaction]
                     (if (transition-transaction?
                          transaction #'run/receipt-refusal-call)
                       {:seon.error/kind :seon.db/rejected
                        :seon.error/message "injected settlement refusal"
                        :seon.error/data {:error :transact/schema}}
                       (transact! target transaction)))]
                  (try
                    (terminal-refused!
                     cluster
                     {:seon.error/kind :seon.cluster.run/refused
                      :seon.error/message "ordinary terminal refusal"}
                     receipt)
                    nil
                    (catch clojure.lang.ExceptionInfo exception
                      exception)))]
            (is (= :seon.cluster.loop/terminal-refusal-settlement-refused
                   (:seon.error/kind (ex-data failure)))
                "the settlement transaction's own outcome is checked")
            (is (= :seon.db/rejected
                   (:seon.error/kind
                    (:seon.cluster.loop/settlement (ex-data failure)))))
            (is (false?
                 (run/terminal?
                  (d/pull @connection '[*]
                          [:seon.cluster.eval/id (pr-str [run-id 0])]))))
            (is (true? (recover-terminal-refusal! cluster receipt)))
            (let [recovered
                  (d/pull @connection '[*]
                          [:seon.cluster.eval/id (pr-str [run-id 0])])
                  run (d/pull @connection '[*]
                              [:seon.cluster.run/id run-id])]
              (is (some? (:seon.cluster.eval/interrupted-at recovered)))
              (is (some? (:seon.cluster.run/closed-at run)))
              (is (nil? (:seon.cluster.run/process run))))))))))

(deftest evaluation-follows-the-readers-parse-time-namespace
  (with-cluster
    (fn [cluster]
      (let [cluster (assoc cluster :seon.cluster.loop/evaluate
                           'seon.sci.eval/evaluate)
            connection (:seon.store/branch-connection cluster)]
        (with-redefs
          [ai/complete
           (fn [_]
             {:seon.ai/text
              (str
               "(in-ns 'my.gen.alpha)\n"
               "(defn ^{:malli/schema [:=> [:cat :int] :int]} f "
               "[x] (inc x))\n"
               "(f 1)")})]
          (drive! cluster 10)
          (is (= 2
                 (semantic-result
                  (d/q '[:find ?result .
                        :where
                        [?receipt :seon.cluster.eval/ordinal 2]
                        [?receipt :seon.cluster.eval/result-edn ?result]]
                       @connection)))
              "in-ns governs the later definition and call")
          (is (= "my.gen.alpha/f"
                 (d/q '[:find ?sym .
                        :where
                        [_ :seon.fn/sym ?sym]]
                      @connection)))
          (is (empty?
               (d/q '[:find ?form
                      :where
                      [?form :seon.cluster.run.form/run ?run]
                      [?form :seon.cluster.run.form/ordinal ?ordinal]
                      [?form :seon.cluster.run.form/ns ?parsed-ns]
                      [?receipt :seon.cluster.eval/run ?run]
                      [?receipt :seon.cluster.eval/ordinal ?ordinal]
                      [?receipt :seon.cluster.eval/ns ?evaluated-ns]
                      [(not= ?parsed-ns ?evaluated-ns)]]
                    @connection))
              "parse/eval divergence is a direct fact query, never silent"))))))

(deftest contracted-redefinition-exactly-replaces-the-program-row
  (with-cluster
    (fn [cluster]
      (let [cluster (assoc cluster :seon.cluster.loop/evaluate
                           'seon.sci.eval/evaluate)
            connection (:seon.store/branch-connection cluster)]
        (with-redefs
          [ai/complete
           (fn [_]
             {:seon.ai/text
              (str
               "(defn ^{:malli/schema [:=> [:cat :int] :int] "
               ":seon.workload :compute} f \"old\" [x] (inc x))\n"
               "(defn ^{:malli/schema [:=> [:cat :int] :int]} f "
               "[x] (+ x 2))\n"
               "(f 1)")})]
          (drive! cluster 10)
          (is (= 3
                 (semantic-result
                  (d/q '[:find ?result .
                        :where
                        [?receipt :seon.cluster.eval/ordinal 2]
                        [?receipt :seon.cluster.eval/result-edn ?result]]
                       @connection))))
          (let [row (d/pull @connection
                            '[*]
                            [:seon.fn/sym "my.agents.agent-a/f"])]
            (is (= "(defn ^{:malli/schema [:=> [:cat :int] :int]} f [x] (+ x 2))"
                   (:seon.fn/source row)))
            (is (not (contains? row :seon.fn/doc)))
            (is (not (contains? row :seon.fn/workload)))))))))

(deftest a-refused-contract-commits-a-receipt-and-no-program-row
  (with-cluster
    (fn [cluster]
      (let [cluster (assoc cluster :seon.cluster.loop/evaluate
                           'seon.sci.eval/evaluate)
            connection (:seon.store/branch-connection cluster)]
        (with-redefs
          [ai/complete
           (fn [_]
             {:seon.ai/text
              "(defn ^{:malli/schema [:=> [:cat :missing/schema] :int]} bad [x] x)"})]
          (drive! cluster 10)
          (is (some?
               (d/q '[:find ?error .
                      :where
                      [?receipt :seon.cluster.eval/error ?error]]
                    @connection))
              "the failed form reaches a terminal receipt")
          (is (nil?
               (d/pull @connection
                       [:seon.fn/sym]
                       [:seon.fn/sym "my.agents.agent-a/bad"]))
              "the failed declaration commits no program fact"))))))

(deftest runtime-schema-registration-commits-the-evaluated-form-and-attribute
  (with-cluster
    (fn [cluster]
      (let [cluster (assoc cluster :seon.cluster.loop/evaluate
                           'seon.sci.eval/evaluate)
            connection (:seon.store/branch-connection cluster)
            persistent-key :my.agents.agent-a/nonnegative
            value-key :my.agents.agent-a/label]
        (with-redefs
          [ai/complete
           (fn [_]
             {:seon.ai/text
              (str
               "(require '[seon.schema :as schema])\n"
               "(schema/register! ::nonnegative "
               "(vector :int {:min 0 :seon.db/index true}))\n"
               "(schema/register! ::label (vector :string {:min 1}))\n"
               "(my.run/complete \"schemas committed\")")})]
          (drive! cluster 10)
          (let [db @connection
                persistent-row
                (d/pull db '[*] [:seon.schema/key persistent-key])
                value-row (d/pull db '[*] [:seon.schema/key value-key])
                tx-pairs
                (d/q '[:find ?schema-tx ?receipt-tx
                       :in $ ?schema-key
                       :where
                       [?schema :seon.schema/key ?schema-key]
                       [?schema :seon.schema/form _ ?schema-tx]
                       [?receipt :seon.cluster.eval/ordinal 1]
                       [?receipt :seon.cluster.eval/result-edn _ ?receipt-tx]]
                     db persistent-key)]
            (is (= "[:int {:min 0, :seon.db/index true}]"
                   (:seon.schema/form persistent-row))
                "the row contains evaluated canonical EDN, not `(vector ...)`")
            (is (= "[:string {:min 1}]"
                   (:seon.schema/form value-row)))
            (is (not (contains? persistent-row :seon.schema/ns))
                "schema identity is global, never namespace-owned")
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
              (d/transact connection [{persistent-key 7}])
              (is (= 7
                     (d/q '[:find ?value .
                            :in $ ?attribute
                            :where [?entity ?attribute ?value]]
                          @connection persistent-key))
                  "the committed attribute accepts a fact immediately"))))))))

(deftest runtime-schema-unregister-removes-one-unused-global-schema
  (with-cluster
    (fn [cluster]
      (let [cluster (assoc cluster :seon.cluster.loop/evaluate
                           'seon.sci.eval/evaluate)
            connection (:seon.store/branch-connection cluster)
            schema-key :shared.runtime/unregister-me]
        (with-redefs
          [ai/complete
           (fn [_]
             {:seon.ai/text
              (str
               "(require '[seon.schema :as schema])\n"
               "(schema/register! :shared.runtime/unregister-me "
               "(vector :int {:seon.db/index true}))\n"
               "(schema/unregister! :shared.runtime/unregister-me)\n"
               "(my.run/complete \"schema removed\")")})]
          (drive! cluster 10)
          (let [db @connection
                results
                (into {}
                      (map (fn [[ordinal result-edn]]
                             [ordinal (semantic-result result-edn)]))
                      (d/q '[:find ?ordinal ?result
                             :where
                             [?receipt :seon.cluster.eval/ordinal ?ordinal]
                             [?receipt :seon.cluster.eval/result-edn ?result]]
                           db))]
            (is (= schema-key (get results 2))
                "unregister has ordinary REPL return semantics")
            (is (nil? (d/pull db [:db/id]
                              [:seon.schema/key schema-key])))
            (is (not (contains? (:schema db) schema-key)))
            (is (not (contains?
                      (:seon.schema.projection/forms
                       (schema/projection-from-database db))
                      schema-key))
                "the run-local projection derives absence from db-after")))))))

(deftest refused-runtime-schema-registration-mutates-neither-row-nor-projection
  (with-cluster
    (fn [cluster]
      (let [cluster (assoc cluster :seon.cluster.loop/evaluate
                           'seon.sci.eval/evaluate)
            connection (:seon.store/branch-connection cluster)
            schema-key :my.agents.agent-a/refused
            global-projection (schema/current-projection)
            global-forms (schema/registered-schemas)]
        (with-redefs
          [ai/complete
           (fn [_]
             {:seon.ai/text
              (str
               "(require '[seon.schema :as schema])\n"
               "(schema/register! ::refused (vector :missing/schema))")})]
          (drive! cluster 10)
          (is (some?
               (d/q '[:find ?error .
                      :where [?receipt :seon.cluster.eval/error ?error]]
                    @connection))
              "the rejected form leaves a terminal error receipt")
          (is (nil? (d/pull @connection [:db/id]
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
      (let [cluster (assoc cluster :seon.cluster.loop/evaluate
                           'seon.sci.eval/evaluate)
            connection (:seon.store/branch-connection cluster)
            schema-key :my.agents.agent-a/not-committed
            transact! store/transact!
            install! sci.eval/install-program-row!
            installations (atom [])
            global-forms (schema/registered-schemas)]
        (with-redefs
          [ai/complete
           (fn [_]
             {:seon.ai/text
              (str
               "(require '[seon.schema :as schema])\n"
               "(schema/register! ::not-committed "
               "(vector :int {:seon.db/index true}))")})
           store/transact!
           (fn [target transaction]
             (let [tx-data (if (map? transaction)
                             (:tx-data transaction)
                             transaction)
                   declaration?
                   (some
                    (fn [operation]
                      (and (vector? operation)
                           (= :db.fn/call (first operation))
                           (= #'run/receipt-settle-call (second operation))
                           (= schema-key
                              (get-in operation
                                      [2 :seon.sci.eval/program-row
                                       :seon.schema/key]))))
                    tx-data)]
               (if declaration?
                 {:seon.error/kind :seon.db/rejected
                  :seon.error/message "injected declaration refusal"
                  :seon.error/data {:error :transact/schema}}
                 (transact! target transaction))))
           sci.eval/install-program-row!
           (fn [request]
             (swap! installations conj request)
             (install! request))]
          (drive! cluster 10)
          (is (nil? (d/pull @connection [:db/id]
                            [:seon.schema/key schema-key])))
          (is (not (contains? (:schema @connection) schema-key)))
          (is (not-any? #(= schema-key
                            (get-in % [:seon.sci.eval/program-row
                                       :seon.schema/key]))
                        @installations)
              "a rejected transaction report never reaches installation")
          (is (= global-forms (schema/registered-schemas))
              "a transaction refusal discards the evaluation overlay"))))))

(deftest runtime-tests-install-run-redefine-and-delete-exactly
  (with-cluster
    (fn [cluster]
      (let [cluster (assoc cluster :seon.cluster.loop/evaluate
                           'seon.sci.eval/evaluate)
            connection (:seon.store/branch-connection cluster)
            test-sym "my.agents.agent-a/versioned-test"]
        (with-redefs
          [ai/complete
           (fn [_]
             {:seon.ai/text
              (str
               "(require '[clojure.test :refer [deftest]])\n"
               "(deftest versioned-test :v1)\n"
               "((:test (meta (resolve 'versioned-test))))\n"
               "(deftest versioned-test :v2)\n"
               "((:test (meta (resolve 'versioned-test))))\n"
               "(ns-unmap 'my.agents.agent-a 'versioned-test)\n"
               "(resolve 'versioned-test)")})]
          (drive! cluster 12)
          (let [results
                (into {}
                      (map (fn [[ordinal result-edn]]
                             [ordinal (semantic-result result-edn)]))
                      (d/q '[:find ?ordinal ?result
                             :where
                             [?receipt :seon.cluster.eval/ordinal ?ordinal]
                             [?receipt :seon.cluster.eval/result-edn ?result]]
                           @connection))]
            (is (= :v1 (get results 2))
                "V1 resolves and its SCI :test function runs")
            (is (= :v2 (get results 4))
                "V2 replaces the live test exactly")
            (is (nil? (get results 6))
                "ns-unmap removes the live SCI binding")
            (is (nil? (d/pull @connection [:db/id]
                              [:seon.test/sym test-sym]))
                "ns-unmap removes the committed test row")))))))

(deftest incompatible-clusters-alternate-runtime-schema-validation-without-bleed
  (with-cluster
    (fn [cluster-a]
      (let [cluster-a (assoc cluster-a :seon.cluster.loop/evaluate
                             'seon.sci.eval/evaluate)
            connection-a (:seon.store/branch-connection cluster-a)
            shared-key :seon.runtime.registration/shared
            a-only-key :seon.runtime.registration/a-only
            global-projection (schema/current-projection)]
        (with-redefs
          [ai/complete
           (fn [_]
             {:seon.ai/text
              (str
               "(require '[seon.schema :as schema])\n"
               "(schema/register! :seon.runtime.registration/shared "
               "(vector :int {:min 0}))\n"
               "(schema/register! :seon.runtime.registration/a-only "
               ":keyword)\n"
               "(my.run/complete \"cluster A registered\")")})]
          (drive! cluster-a 10))
        (with-cluster
          (fn [cluster-b]
            (let [cluster-b (assoc cluster-b :seon.cluster.loop/evaluate
                                   'seon.sci.eval/evaluate)
                  connection-b (:seon.store/branch-connection cluster-b)]
              (with-redefs
                [ai/complete
                 (fn [_]
                   {:seon.ai/text
                    (str
                     "(require '[seon.schema :as schema])\n"
                     "(schema/register! :seon.runtime.registration/shared "
                     "(vector :string {:min 1}))\n"
                     "(my.run/complete \"cluster B registered\")")})]
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

(deftest another-agent-calls-the-live-cluster-definition-without-reinstall
  (with-cluster
    (fn [cluster]
      (let [cluster (assoc cluster :seon.cluster.loop/evaluate
                           'seon.sci.eval/evaluate)
            connection (:seon.store/branch-connection cluster)
            replies (atom
                     [(str
                       "(defn ^{:malli/schema [:=> [:cat :int] :int]} "
                       "persisted [x] (inc x))\n"
                       "(my.run/complete \"published\")")
                      (str
                       "(my.run/complete "
                       "(str (my.agents.agent-a/persisted 41)))")])]
        (with-redefs [ai/complete
                      (fn [_]
                        {:seon.ai/text
                         (let [reply (first @replies)]
                           (swap! replies subvec 1)
                           reply)})]
          (drive! cluster 10)
          (d/transact
           connection
           [{:seon.ns/name 'my.agents.agent-b}
            (assoc (agent-row "agent-b")
                   :seon.cluster.agent/namespace
                   [:seon.ns/name 'my.agents.agent-b])
            {:seon.cluster.message/id "m-agent-b"
             :seon.cluster.message/to [:seon.cluster.agent/id "agent-b"]
             :seon.cluster.message/content "call the published function"
             :seon.cluster.message/at now}])
          (drive! cluster 10)
          (is (some #(str/includes? % "42")
                    (d/q '[:find [?result ...]
                           :where
                           [_ :seon.cluster.eval/result-edn ?result]]
                         @connection))
              "the second agent used the same live cluster program graph"))))))

(deftest another-agent-sees-a-flat-contract-violation-after-live-install
  (with-cluster
    (fn [cluster]
      (let [cluster (assoc cluster
                           :seon.cluster/name "turn-test"
                           :seon.cluster.loop/evaluate
                           'seon.sci.eval/evaluate)
            connection (:seon.store/branch-connection cluster)
            replies
            (atom
             [(str
               "(defn ^{:malli/schema [:=> [:cat :int] :int]} "
               "strict [x] x)\n"
               "(my.run/complete \"published\")")
              "(my.agents.agent-a/strict \"wrong\")"])]
        (d/transact
         connection
         [(merge {:seon.config/cluster "turn-test"
                  :seon.config/on-core-error :panic
                  :seon.config.ai/endpoint "http://127.0.0.1:1/v1"
                  :seon.config.ai/model "probe"
                  :seon.config.ai/max-tokens 32
                  :seon.config.ai/api-key-variable "SEON_AI_TEST_KEY"
                  :seon.config.ai/timeout-ms 200
                  :seon.config.ai.retry/base-delay-ms 1
                  :seon.config.ai.retry/multiplier 2.0
                  :seon.config.ai.retry/jitter-fraction 0.0
                  :seon.config.ai.retry/maximum-delay-ms 1
                  :seon.config.ai.retry/maximum-retries 0
                  :seon.config.ai.retry/maximum-total-delay-ms 0}
                 (:seon.sci.admit/caps cluster))])
        (with-redefs [ai/complete
                      (fn [_]
                        (let [[before _]
                              (swap-vals! replies
                                          #(if (seq %) (subvec % 1) %))]
                          {:seon.ai/text
                           (or (first before)
                               "(my.run/complete \"recovered\")")}))]
          (drive-agent! cluster "agent-a" 10)
          (d/transact
           connection
           [{:seon.ns/name 'my.agents.agent-b}
            (assoc (agent-row "agent-b")
                   :seon.cluster.agent/namespace
                   [:seon.ns/name 'my.agents.agent-b])
            {:seon.cluster.message/id "m-contract-agent-b"
             :seon.cluster.message/to [:seon.cluster.agent/id "agent-b"]
             :seon.cluster.message/content "violate the published contract"
             :seon.cluster.message/at now}])
          (drive-agent! cluster "agent-b" 10)
          (let [receipts
                (d/q '[:find [(pull ?receipt
                                   [:seon.error/kind
                                    :seon.cluster.eval/result-edn]) ...]
                       :where
                       [?receipt :seon.cluster.eval/id _]
                       [?receipt :seon.error/kind
                        :seon.instrument/contract-violated]]
                     @connection)]
            (is (= 1 (count receipts)))
            (is (str/includes?
                 (:seon.cluster.eval/result-edn (first receipts))
                               "my.agents.agent-a/strict")
                "the second agent crossed the one live context-install seam")))))))

(deftest a-refused-definition-stays-live-for-another-agent
  (with-cluster
    (fn [cluster]
      (let [cluster (assoc cluster :seon.cluster.loop/evaluate
                           'seon.sci.eval/evaluate)
            connection (:seon.store/branch-connection cluster)
            function-sym "my.agents.agent-a/refused-live"
            replies (atom
                     [(str
                       "(defn ^{:malli/schema [:=> [:cat :int] :int]} "
                       "refused-live [x] (inc x))")
                      (str
                       "(my.run/complete "
                       "(str (my.agents.agent-a/refused-live 41)))")])
            transact! store/transact!]
        (with-redefs
          [ai/complete
           (fn [_]
             {:seon.ai/text
              (let [reply (first @replies)]
                (swap! replies subvec 1)
                reply)})
           store/transact!
           (fn [target transaction]
             (let [tx-data (if (map? transaction)
                             (:tx-data transaction)
                             transaction)
                   refused-definition?
                   (some
                    (fn [operation]
                      (and (vector? operation)
                           (= :db.fn/call (first operation))
                           (= #'run/receipt-settle-call (second operation))
                           (= function-sym
                              (get-in operation
                                      [2 :seon.sci.eval/program-row
                                       :seon.fn/sym]))))
                    tx-data)]
               (if refused-definition?
                 {:seon.error/kind :seon.db/rejected
                  :seon.error/message "injected definition refusal"
                  :seon.error/data {:error :transact/program}}
                 (transact! target transaction))))]
          (drive! cluster 10)
          (is (nil? (d/pull @connection [:db/id]
                            [:seon.fn/sym function-sym]))
              "the refused terminal transaction persists no function row")
          (d/transact
           connection
           [{:seon.ns/name 'my.agents.agent-b}
            (assoc (agent-row "agent-b")
                   :seon.cluster.agent/namespace
                   [:seon.ns/name 'my.agents.agent-b])
            {:seon.cluster.message/id "m-agent-b-after-refusal"
             :seon.cluster.message/to [:seon.cluster.agent/id "agent-b"]
             :seon.cluster.message/content "call the refused definition"
             :seon.cluster.message/at now}])
          (drive! cluster 10)
          (is (some #(str/includes? % "42")
                    (d/q '[:find [?result ...]
                           :where
                           [_ :seon.cluster.eval/result-edn ?result]]
                         @connection))
              "the refusal is session state; the live REPL definition remains"))))))

(deftest acquisition-orders-agent-authored-refer-targets-and-ignores-alias-cycles
  (test-support/with-database
    (fn [connection]
      (d/transact
       connection
       [{:seon.ns/name 'authored.target
         :seon.ns/source "(ns authored.target)"}
        {:seon.ns/name 'authored.consumer
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
         :seon.ns/source "(ns alias.cycle-a)"
         :seon.ns/aliases
         [{:seon.ns.alias/local 'b
           :seon.ns.alias/target-ns 'alias.cycle-b}
          {:seon.ns.alias/local 'ghost
           :seon.ns.alias/target-ns 'not.loaded}]}
        {:seon.ns/name 'alias.cycle-b
         :seon.ns/source "(ns alias.cycle-b)"
         :seon.ns/aliases
         [{:seon.ns.alias/local 'a
           :seon.ns.alias/target-ns 'alias.cycle-a}]}])
      (d/transact
       connection
       [{:seon.fn/sym "authored.target/increment"
         :seon.fn/ns [:seon.ns/name 'authored.target]
         :seon.fn/source
         (str "(defn ^{:malli/schema [:=> [:cat :int] :int]} "
              "increment [x] (inc x))")
         :seon.fn/arglists "([x])"
         :seon.fn/private? false
         :seon.fn/spec "[:=> [:cat :int] :int]"}
        {:seon.fn/sym "authored.consumer/call-plus"
         :seon.fn/ns [:seon.ns/name 'authored.consumer]
         :seon.fn/source
         (str "(defn ^{:malli/schema [:=> [:cat :int] :int]} "
              "call-plus [x] (plus (target/increment x)))")
         :seon.fn/arglists "([x])"
         :seon.fn/private? false
         :seon.fn/spec "[:=> [:cat :int] :int]"}])
      (let [ctx (sci.eval/build-base-ctx)
            acquired
            (sci.eval/acquire!
             {:seon.sci.eval/ctx ctx
              :seon.db/db @connection})]
        (is (= 43
               (sci.core/eval-string*
                ctx "(authored.consumer/call-plus 41)"))
            "refer and aliased target Vars exist before the consumer")
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
            "an effective as-alias target need not be loaded")
        (is (= 6 (:seon.sci.eval/installed acquired)))))))

(deftest a-settled-orphan-stops-wedging-the-agent
  ;; The crash drill's headline: a process died holding a claimed,
  ;; unplanned run. Boot recovery released the dead CUSTODY, but until
  ;; the turn proc settles the run the AGENT is still busy —
  ;; next-agent-work finds
  ;; nothing to do for it and every later trigger goes unanswered
  ;; forever. This is that whole sequence, end to end.
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.store/branch-connection cluster)
            now (Date.)]
        ;; the wreckage a crash leaves, AFTER boot recovery has released
        ;; the dead holder: open, unclaimed, unplanned
        (d/transact connection
                    [{:seon.cluster.run/id "run-crashed"
                      :seon.cluster.run/agent [:seon.cluster.agent/id "agent-a"]
                      :seon.cluster.run/opened-at (Date. 1000)}
                     {:seon.cluster.agent/id "agent-a"
                      :seon.cluster.agent/run [:seon.cluster.run/id "run-crashed"]}])
        (testing "the agent is WEDGED: it is busy, and nothing is work"
          (is (nil? (work/next-agent-work @connection (request connection))))
          (is (= "run-crashed"
                 (:seon.cluster.run/id
                  (work/interruption @connection "agent-a")))))

        ;; the loop's own pass settles it before deriving anything
        (with-redefs [ai/complete
                      (fn [_] {:seon.ai/text "(my.run/complete \"answered\")"})]
          (binding [*evaluation* {:seon.cluster.eval/result-edn
                                  (pr-str (my.run/complete "answered"))
                                  :seon.sci.admit/value (my.run/complete "answered")}]
            (drive-passes! cluster 8)))

        (testing "the orphan is settled — closed, and no longer an
                  interruption"
          (is (nil? (work/interruption @connection "agent-a")))
          (is (some? (d/q '[:find ?c . :in $ ?id :where
                            [?r :seon.cluster.run/id ?id]
                            [?r :seon.cluster.run/closed-at ?c]]
                          @connection "run-crashed"))))
        (testing "and the trigger that was waiting behind it is ANSWERED
                  by a new run that ran to completion"
          (is (empty? (work/unanswered-triggers @connection "agent-a")))
          (let [new-runs (d/q '[:find [?id ...] :where
                                [?r :seon.cluster.run/id ?id]
                                [?r :seon.cluster.run/plan-digest _]]
                              @connection)]
            (is (= 1 (count new-runs)))
            (is (not= "run-crashed" (first new-runs))
                "the crashed run was buried, never re-planned")))
        (testing "nothing re-executed: receipts belong only to the new run"
          (is (= 1 (count (d/q '[:find ?run-id (count ?e) :where
                                 [?e :seon.cluster.eval/run ?r]
                                 [?r :seon.cluster.run/id ?run-id]]
                               @connection)))))))))

(deftest a-lost-model-call-leaves-a-durable-readable-reason
  ;; the drive sat claimed-with-no-plan for 120 s and the operator had to
  ;; reproduce the call by hand to learn it was a missing credential.
  ;; The reason is now a fact, and the next prompt says it.
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.store/branch-connection cluster)]
        (with-redefs [ai/complete
                      (fn [_] {:seon.error/kind :seon.ai/no-credential
                               :seon.error/message
                               "The environment variable DEEPSEEK_API_KEY is not set."
                               :seon.error/data {}})]
          (drive! cluster 4))
        (testing "the run closed rather than sitting claimed"
          (is (some? (d/q '[:find ?c . :where
                            [_ :seon.cluster.run/closed-at ?c]] @connection)))
          (is (nil? (d/q '[:find ?p . :where
                           [_ :seon.cluster.run/process ?p]] @connection))))
        (testing "and WHY is readable from the database"
          (is (re-find #"DEEPSEEK_API_KEY"
                       (d/q '[:find ?e . :where
                              [_ :seon.cluster.run/error ?e]] @connection))))
        (testing "so the agent's next prompt tells it what happened"
          ;; the NEXT prompt belongs to the next held run: open it the
          ;; way the loop does — creating transaction carries the
          ;; trigger, agent pointer names the run
          (d/transact connection
                      {:tx-data [{:seon.cluster.run/id "run-next"
                                  :seon.cluster.run/agent
                                  [:seon.cluster.agent/id "agent-a"]
                                  :seon.cluster.run/opened-at (Date.)}
                                 {:seon.cluster.agent/id "agent-a"
                                  :seon.cluster.agent/run
                                  [:seon.cluster.run/id "run-next"]}]
                       :tx-meta {:seon.db/trigger
                                 [:seon.cluster.message/id "m-1"]}})
          (is (re-find #"DEEPSEEK_API_KEY"
                       (:seon.cluster.prompt/text
                        (prompt/prompt @connection
                                       {:seon.cluster.run/id "run-next"
                                        :seon.cluster.agent/id "agent-a"
                                        :seon.sci.admit/caps
                                        (:seon.sci.admit/caps cluster)})))))))))

(deftest a-real-evaluation-that-runs-away-is-stopped-and-recorded
  ;; the loop's honest failure path, end to end: an agent writes an
  ;; infinite loop, the boundary stops it, and the receipt says so
  (with-cluster
    (fn [cluster]
      (let [cluster (assoc cluster
                           :seon.cluster.loop/evaluate 'seon.sci.eval/evaluate
                           ;; a short leash for the runaway case
                           :seon.config.eval/time-limit-ms 300)
            connection (:seon.store/branch-connection cluster)]
        (with-redefs [ai/complete
                      (fn [_] {:seon.ai/text "(loop [] (recur))"})]
          (drive! cluster 6)
          (is (= 1 (count (d/q '[:find [?at ...] :where
                                 [_ :seon.cluster.eval/interrupted-at ?at]]
                               @connection)))
              "the receipt records its cut instant, and the fold moved on")
          (is (re-find #"(?i)time"
                       (d/q '[:find ?error . :where
                              [_ :seon.cluster.eval/error ?error]]
                            @connection))))))))

(deftest a-red-form-routes-to-its-namespace-owner-and-the-fold-continues
  (with-cluster
    (fn [cluster]
      (let [cluster (assoc cluster
                           :seon.cluster.loop/evaluate 'seon.sci.eval/evaluate)
            connection (:seon.store/branch-connection cluster)
            route-run "route-run"]
        (d/transact
         connection
         [{:seon.ns/name 'my.gen.planner}
          {:seon.ns/name 'my.gen.alpha}
          {:seon.cluster.agent/id "agent-b"
           :seon.cluster.agent/namespace [:seon.ns/name 'my.gen.alpha]}
          {:seon.cluster.agent/id "agent-a"
           :seon.cluster.agent/namespace [:seon.ns/name 'my.gen.planner]}
          {:seon.cluster.message/id "route-goal"
           :seon.cluster.message/to [:seon.cluster.agent/id "agent-a"]
           :seon.cluster.message/content "Generate the program."
           :seon.cluster.message/at now}])
        (d/transact
         connection
         {:tx-data
          [{:seon.cluster.run/id route-run
            :seon.cluster.run/agent [:seon.cluster.agent/id "agent-a"]
            :seon.cluster.run/opened-at now
            :seon.cluster.run/process process
            :seon.cluster.run/plan-digest "route-digest"}]
          :tx-meta
          {:seon.db/trigger [:seon.cluster.message/id "route-goal"]}})
        (d/transact
         connection
         [{:seon.cluster.agent/id "agent-a"
           :seon.cluster.agent/run [:seon.cluster.run/id route-run]}
          {:seon.cluster.run.form/id "route-form-0"
           :seon.cluster.run.form/run [:seon.cluster.run/id route-run]
           :seon.cluster.run.form/ordinal 0
           :seon.cluster.run.form/source "(do (declare zz) zz)"
           :seon.cluster.run.form/ns [:seon.ns/name 'my.gen.alpha]}
          {:seon.cluster.run.form/id "route-form-1"
           :seon.cluster.run.form/run [:seon.cluster.run/id route-run]
           :seon.cluster.run.form/ordinal 1
           :seon.cluster.run.form/source "42"
           :seon.cluster.run.form/ns [:seon.ns/name 'my.gen.alpha]}])
        (let [report
              (cluster.loop/turn
               {:seon.cluster.loop/cluster cluster
                :seon.cluster.work/next
                {:seon.cluster.work/situation :resume
                 :seon.cluster.run/id route-run
                 :seon.cluster.agent/id "agent-a"
                 :seon.cluster.run.form/ordinal 0}}
               now)
              settlement (work/plan-settlement @connection route-run)]
          (is (= 2 (:seon.cluster.loop/forms-run report))
              "the fold attempted the sibling after the red form")
          (is (= [:routed :succeeded]
                 (mapv :seon.cluster.work/form-state
                       (:seon.cluster.work/forms settlement))))
          (is (false? (:seon.cluster.work/settled? settlement)))
          (is (= #{["agent-a" "agent-b" 0]}
                 (d/q '[:find ?from-id ?to-id ?ordinal
                        :where
                        [?assignment :seon.cluster.message/about ?problem]
                        [?problem :seon.problems/id _]
                        [?assignment :seon.cluster.message/from ?from]
                        [?from :seon.cluster.agent/id ?from-id]
                        [?assignment :seon.cluster.message/to ?to]
                        [?to :seon.cluster.agent/id ?to-id]
                        [?problem :seon.cluster.eval/ordinal ?ordinal]]
                      @connection))
              "the red problem routes to the parse-time namespace owner")
          (is (= 1
                 (d/q '[:find (count ?tx) .
                        :where
                        [?assignment :seon.cluster.message/about ?problem ?tx]
                        [?problem :seon.problems/id _]
                        [?problem :seon.cluster.eval/error _ ?tx]]
                      @connection))
              "the terminal receipt and its E3 assignment land in one tx"))))))

(deftest a-whole-turn-runs-from-trigger-to-closed-run
  (with-cluster
    (fn [cluster]
      (with-redefs [ai/complete
                    (fn [_] {:seon.ai/text
                             "(+ 1 1)\n(my.run/complete \"two widgets\")"})]
        (binding [*evaluation* {:seon.cluster.eval/result-edn "2"
                               :seon.sci.admit/value 2}]
          (let [connection (:seon.store/branch-connection cluster)
                reports (drive! cluster 10)]
            (testing "the trigger was answered by exactly one run"
              (is (empty? (work/unanswered-triggers @connection "agent-a"))))
            (testing "and the pass sequence is open -> call -> fold -> close"
              (is (= [:open :call :resume :close]
                     (mapv :seon.cluster.work/situation reports))))
            (testing "every form got exactly one terminal receipt"
              (is (= 2 (count (d/q '[:find ?e :where
                                     [?e :seon.cluster.eval/ordinal _]]
                                   @connection)))))
            (testing "and the run is closed, so the agent is idle again"
              (is (nil? (work/next-agent-work @connection (request connection)))))))))))

(deftest a-completing-disposition-closes-in-the-terminal-transaction
  (with-cluster
    (fn [cluster]
      (with-redefs [ai/complete
                    (fn [_] {:seon.ai/text "(my.run/complete \"done\")"})]
        (binding [*evaluation* {:seon.cluster.eval/result-edn
                               (pr-str (my.run/complete "done"))
                               :seon.sci.admit/value (my.run/complete "done")}]
          (let [connection (:seon.store/branch-connection cluster)
                reports (drive! cluster 10)]
            (is (= [:open :call :resume]
                   (mapv :seon.cluster.work/situation reports))
                "no separate close pass — the disposition closed it")
            (is (some? (d/q '[:find ?closed .
                              :where [_ :seon.cluster.run/closed-at ?closed]]
                            @connection))
                "the run closed in the SAME transaction as its receipt")))))))

(deftest a-waiting-disposition-frees-the-agent-and-keeps-its-note
  ;; REVISED TWICE, each time toward one commit. First: a wait used to
  ;; leave the run open forever because the close refused (the measured
  ;; `:close` livelock — twelve passes, nine error facts). Then the F1
  ;; seal folded in the ruled `my.run/wait` revision (README
  ;; owner-decisions #4): the wait's terminal transaction settles the
  ;; receipt AND closes the run in ONE commit, so the
  ;; unheld-open-planned intermediate state — the P1 feeder — never
  ;; exists at any basis and no separate `:close` pass runs at all.
  ;; Nothing could ever have resumed that run: its plan was fully
  ;; executed, and what resumes is the AGENT, on its next trigger.
  (with-cluster
    (fn [cluster]
      (with-redefs [ai/complete
                    (fn [_] {:seon.ai/text "(my.run/wait \"need input\")"})]
        (binding [*evaluation* {:seon.cluster.eval/result-edn
                               (pr-str (my.run/wait "need input"))
                               :seon.sci.admit/value (my.run/wait "need input")}]
          (let [connection (:seon.store/branch-connection cluster)
                reports (drive! cluster 12)]
            (is (= [:open :call :resume]
                   (mapv :seon.cluster.work/situation reports))
                "three passes and then IDLE — no separate close pass")
            (is (= [:released :released :closed]
                   (mapv :seon.cluster.loop/outcome reports)))
            (is (nil? (work/next-agent-work @connection (request connection)))
                "and nothing is derivable afterwards: no spin")
            (is (empty? (d/q '[:find ?e :where [?e :seon.error/kind _]]
                             @connection))
                "no error facts — the old path committed one per pass")
            (is (nil? (d/q '[:find ?a . :where
                             [?a :seon.cluster.agent/run _]] @connection))
                "the agent is free: its pointer is retracted, so the next
                 trigger can open a new run")
            (is (str/includes?
                 (d/q '[:find ?edn . :where
                        [_ :seon.cluster.eval/result-edn ?edn]] @connection)
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
  [connection]
  (d/transact
   connection
   [{:seon.config/cluster "turn-test"
     :seon.config.ai.backup/endpoint (:seon.ai/endpoint backup-target)
     :seon.config.ai.backup/model (:seon.ai/model backup-target)
     :seon.config.ai.backup/api-key-variable
     (:seon.ai/api-key-variable backup-target)
     :seon.config.ai.backup/timeout-ms (:seon.ai/timeout-ms backup-target)}]))

(defn- failure
  "One model failure value carrying the evidence the leaf would record."
  [kind evidence]
  {:seon.error/kind kind
   :seon.error/message (str "probe failure: " (name kind))
   :seon.error/data evidence})

(def ^:private unpaid
  "A connection the JDK PROVED never left this machine — the one case
  the no-retry ruling leaves open."
  (failure :seon.ai/transport-failure
           {:seon.ai/error-class :transport-before-send
            :seon.ai/request-transmitted? false
            :seon.ai/response-started? false
            :seon.ai/output-observed? false}))

(defn- recording-completer
  "A stub `ai/complete` that records every request and answers in order.
  The recorded vector is the countable attempt log: one entry is one
  request built, which is one call made."
  [requests answers]
  (fn [request]
    (let [index (count @requests)]
      (swap! requests conj request)
      (nth answers index (last answers)))))

(defn- attempt-rows
  "Every attempt this database recorded, in chain order."
  [db]
  (->> (d/q '[:find [?attempt ...]
              :where [?attempt :seon.ai.attempt/ordinal _]]
            db)
       (map #(d/pull db '[*] %))
       (sort-by :seon.ai.attempt/ordinal)
       vec))

(defn- derived-disposition
  "Re-derive what the loop decided, from durable facts alone.
  The attempt's error fact carries the evidence, and the backup role is
  the `failover-from` connection — a stored disposition would only
  restate this derivation (owner ruling 2026-07-28)."
  [db row backup-configured?]
  (let [fact (d/pull db '[*] (:db/id (:seon.ai.attempt/error row)))
        value (semantic-result (:seon.error/data-edn fact))]
    (ai/disposition
     {:seon.error/value value
      :seon.ai/backup? (boolean
                        (and backup-configured?
                             (not (contains? row
                                             :seon.ai.attempt/failover-from))))})))

(defn- durable-fact
  "The committed error fact, read BACK OUT of the database.
  The two refs are restored to the lookup-ref shape `normalize` emitted,
  because the projection reads them that way — this is deliberately the
  DURABLE row rather than the value the loop happened to hold, so the
  assertion proves the fact was committed before the prose was derived."
  [db error-id]
  (let [pulled (d/pull db '[* {:seon.error/run [:seon.cluster.run/id]
                               :seon.error/agent [:seon.cluster.agent/id]}]
                       [:seon.error/id error-id])]
    (cond-> (dissoc pulled :db/id)
      (:seon.error/run pulled)
      (assoc :seon.error/run
             [:seon.cluster.run/id
              (:seon.cluster.run/id (:seon.error/run pulled))])
      (:seon.error/agent pulled)
      (assoc :seon.error/agent
             [:seon.cluster.agent/id
              (:seon.cluster.agent/id (:seon.error/agent pulled))]))))

(deftest one-successful-call-leaves-exactly-one-attempt-fact
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.store/branch-connection cluster)
            requests (atom [])]
        (with-redefs [ai/complete
                      (recording-completer
                       requests
                       [{:seon.ai/text "(my.run/complete \"one\")"}])]
          (binding [*evaluation* {:seon.cluster.eval/result-edn
                                  (pr-str (my.run/complete "one"))
                                  :seon.sci.admit/value (my.run/complete "one")}]
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
        (is (nil? (d/q '[:find ?e . :where [?e :seon.error/id _]] @connection))
            "and a call that worked committed no error fact")))))

(deftest successful-call-persists-the-providers-open-usage-document
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.store/branch-connection cluster)
            requests (atom [])
            usage {"prompt_tokens" 31
                   "completion_tokens" 7
                   "prompt_tokens_details" {"cached_tokens" 23}}]
        (with-redefs [ai/complete
                      (recording-completer
                       requests
                       [{:seon.ai/text "(my.run/complete \"one\")"
                         :seon.ai/reasoning-content "private reasoning"
                         :seon.ai/usage usage
                         :seon.ai/finish-reason "stop"}])]
          (binding [*evaluation* {:seon.cluster.eval/result-edn
                                  (pr-str (my.run/complete "one"))
                                  :seon.sci.admit/value (my.run/complete "one")}]
            (drive! cluster 10)))
        (let [[row :as rows] (attempt-rows @connection)]
          (is (= 1 (count rows)))
          (is (= usage
                 (edn/read-string (:seon.ai.attempt/usage-edn row)))
              "the provider-owned map survives the database round trip")
          (is (= "private reasoning"
                 (:seon.ai.attempt/reasoning row))
              "settled reasoning reaches the same durable attempt row")
          (is (= "stop" (:seon.ai.attempt/finish-reason row))
              "finish reason is its own fact, never inserted into usage"))))))

(deftest reasoning-starvation-persists-usage-finish-and-the-named-error
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.store/branch-connection cluster)
            requests (atom [])
            usage {"prompt_tokens" 104
                   "completion_tokens" 8
                   "completion_tokens_details" {"reasoning_tokens" 8}}
            failure {:seon.error/kind :seon.ai/token-starvation
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
        (with-redefs [ai/complete (recording-completer requests [failure])]
          (drive! cluster 10))
        (let [[row :as rows] (attempt-rows @connection)
              error-fact (d/pull @connection '[*]
                                 (:db/id (:seon.ai.attempt/error row)))]
          (is (= 1 (count rows)))
          (is (= usage
                 (edn/read-string (:seon.ai.attempt/usage-edn row))))
          (is (= "length" (:seon.ai.attempt/finish-reason row)))
          (is (= "all reasoning" (:seon.ai.attempt/reasoning row))
              "reasoning-only starvation still persists the settled trace")
          (is (= :seon.ai/token-starvation (:seon.error/kind error-fact))
              "the receipt points at the named starvation error fact"))))))

(deftest an-unpaid-failure-with-a-backup-makes-exactly-two-calls
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.store/branch-connection cluster)
            requests (atom [])]
        (configure-backup! connection)
        (with-redefs [ai/complete
                      (recording-completer
                       requests
                       [unpaid {:seon.ai/text "(my.run/complete \"backed up\")"}])]
          (binding [*evaluation*
                    {:seon.cluster.eval/result-edn
                     (pr-str (my.run/complete "backed up"))
                     :seon.sci.admit/value (my.run/complete "backed up")}]
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
                            (d/pull @connection '[:seon.error/id]
                                    (d/q '[:find ?e . :where
                                           [?e :seon.error/id _]]
                                         @connection)))
                  fact (durable-fact @connection error-id)]
              (is (= (:seon.render/output
                      (render/render
                       {:seon.render/unit
                        (error/notice {:seon.error/fact fact
                                       :seon.error/reason :failover})
                        :seon.render/kind :seon.render/ai}))
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
            (is (some? (d/q '[:find ?d . :where
                              [_ :seon.cluster.run/plan-digest ?d]]
                            @connection)))))))))

(def ^:private turn-evidence-partitions
  [{::error-class :credential ::kind :seon.ai/no-credential
    ::transmitted? false}
   {::error-class :transport-before-send ::kind :seon.ai/transport-failure
    ::transmitted? false}
   {::error-class :transport-unknown ::kind :seon.ai/transport-failure
    ::transmitted? true}
   {::error-class :timeout ::kind :seon.ai/timeout ::transmitted? true}
   {::error-class :rate-limit ::kind :seon.ai/provider-error
    ::transmitted? true}
   {::error-class :server ::kind :seon.ai/provider-error ::transmitted? true}
   {::error-class :authentication ::kind :seon.ai/provider-error
    ::transmitted? true}
   {::error-class :authorization ::kind :seon.ai/provider-error
    ::transmitted? true}
   {::error-class :model ::kind :seon.ai/provider-error ::transmitted? true}
   {::error-class :request ::kind :seon.ai/provider-error ::transmitted? true}
   {::error-class :response ::kind :seon.ai/unparseable-body
    ::transmitted? true}
   {::error-class :response ::kind :seon.ai/unparseable-body
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
  [{::keys [error-class kind transmitted? output?]}]
  (failure kind
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
    {:seon.ai/text "(my.run/complete \"generated\")"}
    (turn-failure-value generated)))

(defn- oracle-disposition
  [{::keys [error-class output?]} backup?]
  (cond
    output? :fail
    (contains? #{:rate-limit :server :transport-before-send} error-class)
    (if backup? :failover-now :backoff)
    (contains? #{:credential :authentication :authorization :model}
               error-class)
    (if backup? :failover-now :fail)
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
  [scenario]
  (with-cluster
    (fn [cluster]
      (let [{::keys [attempts succeeded?]} (expected-attempt-trace scenario)
            connection (:seon.store/branch-connection cluster)
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
        (d/transact
         connection
         [(cond-> {:seon.config/cluster "turn-test"
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
                   (:seon.ai/timeout-ms backup-target)))])
        (with-redefs [ai/complete complete!]
          (binding [*evaluation*
                    {:seon.cluster.eval/result-edn
                     (pr-str (my.run/complete "generated"))
                     :seon.sci.admit/value
                     (my.run/complete "generated")}]
            (drive! cluster 12)))
        (let [rows (attempt-rows @connection)
              actual (mapv actual-attempt-shape rows)
              run-row (d/q '[:find (pull ?run [*]) .
                             :where [?run :seon.cluster.run/id _]]
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
           (contains? run-row :seon.cluster.run/closed-at)
           (not (contains? run-row :seon.cluster.run/process))
           (= 1 (work/episode-runs @connection "agent-a"))
           (= succeeded?
              (contains? run-row :seon.cluster.run/plan-digest))))))))

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
  ;; THE COMPOSITION QUESTION, answered by the fold rather than by a
  ;; rule: a turn sends in one form and completes in another, because
  ;; the loop reads EVERY form's value, not only the last.
  (with-cluster
    (fn [cluster]
      (let [cluster (assoc cluster :seon.cluster.loop/evaluate
                           'seon.sci.eval/evaluate)
            connection (:seon.store/branch-connection cluster)]
        (d/transact connection [(agent-row "agent-b")])
        ;; ONE stub, two agents: the reply depends on WHOSE prompt it
        ;; is, so the delegate answers instead of forwarding the same
        ;; sentence back. (Without this the stub made agent-b message
        ;; itself — which the loop happily delivered, and which is the
        ;; cheapest possible proof that the wake really fires.)
        (with-redefs [ai/complete
                      (fn [{prompt :seon.ai/prompt}]
                        {:seon.ai/text
                         (if (str/includes? prompt "Agent agent-b")
                           "(my.run/complete \"there are three widgets\")"
                           (str "(my.message/send \"agent-b\" "
                                "\"please count the widgets\")\n"
                                "(my.run/complete \"asked agent-b\")"))})]
          (drive! cluster 10)
          (testing "the message is a durable fact addressed to the peer —
                    and the peer's completion answers back, derived from
                    the trigger rather than remembered by the delegate"
            (is (= #{["please count the widgets" "agent-b" "agent-a"]
                     ["there are three widgets" "agent-a" "agent-b"]}
                   (set (d/q '[:find ?content ?to-id ?from-id
                               :where
                               [?m :seon.cluster.message/content ?content]
                               [?m :seon.cluster.message/to ?to]
                               [?to :seon.cluster.agent/id ?to-id]
                               [?m :seon.cluster.message/from ?from]
                               [?from :seon.cluster.agent/id ?from-id]]
                             @connection)))))
          (testing "the run still completed — sending is not finishing"
            (is (some? (d/q '[:find ?c . :where
                              [_ :seon.cluster.run/closed-at ?c]]
                            @connection))))
          (testing "message and receipt rode ONE transaction"
            ;; asked from the MESSAGE's transaction rather than by
            ;; joining runs: agent-a has two runs by the time the
            ;; delegate has answered, and a join on "a done receipt at
            ;; ordinal 0" matches both of them.
            (let [message-tx
                  (d/q '[:find ?mtx . :where
                         [?m :seon.cluster.message/content
                          "please count the widgets" ?mtx]]
                       @connection)]
              (is (= [0]
                     (d/q '[:find [?ordinal ...]
                            :in $ ?tx
                            :where
                            [?r :seon.cluster.eval/result-edn _ ?tx]
                            [?r :seon.cluster.eval/ordinal ?ordinal]]
                          @connection message-tx))
                  "the message rode the terminal transaction of the very
                   form that asked for it — no window in which a message
                   exists and the receipt explaining it does not")))
          (testing "and that transaction names the trigger it answers"
            (is (= 1 (message/chain-depth
                      @connection
                      (d/q '[:find ?id . :where
                             [?m :seon.cluster.message/content
                              "please count the widgets"]
                             [?m :seon.cluster.message/id ?id]]
                           @connection)))
                "the conversation's depth is walkable from committed
                 transaction metadata alone — no hop counter anywhere")))))))

(deftest a-refused-delivery-becomes-a-durable-error-fact
  ;; The bound itself is proven exhaustively in the messaging suite's
  ;; ping-pong simulation. What this proves is the SEAM: a refusal the
  ;; delivery rule returns as a value reaches the error recorder and
  ;; commits, rather than evaporating in the fold — the D3 lesson
  ;; applied to the new value family. The dial is set to a value the
  ;; schema calls invalid on purpose, because fail-closed is the
  ;; behaviour under a misconfigured bound and it is the one refusal a
  ;; single turn can reach.
  (with-cluster
    (fn [cluster]
      (let [cluster (assoc cluster :seon.cluster.loop/evaluate
                           'seon.sci.eval/evaluate
                           ;; nothing may be delivered at all
                           :seon.config.message/max-chain 0)
            connection (:seon.store/branch-connection cluster)]
        (d/transact connection [(agent-row "agent-b")])
        (with-redefs [ai/complete
                      (fn [_] {:seon.ai/text
                               (str "(my.message/send \"agent-b\" \"hi\")\n"
                                    "(my.run/complete \"tried\")")})]
          (drive! cluster 10)
          (is (empty? (d/q '[:find ?c :where
                             [?m :seon.cluster.message/content ?c]
                             [?m :seon.cluster.message/from _]]
                           @connection))
              "nothing was delivered")
          (is (= #{:seon.cluster.message/no-limit}
                 (set (d/q '[:find [?kind ...] :where
                             [?e :seon.error/kind ?kind]]
                           @connection)))
              "and the refusal is a durable error fact with its own kind"))))))

;;; ---------------------------------------------------------------------------
;;; Custody precedes work — the surviving live-holder interleaving
;;; (custody-revision-contracts-2026-07-28; probe P2)
;;; ---------------------------------------------------------------------------

(deftest a-held-runs-paid-call-is-never-duplicated
  ;; P2, the lapsed-lease re-pay cycle, re-expressed as CUSTODY
  ;; MISMATCH now that no lease exists to lapse: across the whole
  ;; open→call→fold interleaving there is exactly ONE provider
  ;; dispatch, and a rewake seen by a DIFFERENT process derives no
  ;; second `:call` for the held run
  ;; (research/trigger-conservation-2026-07-28 §3.2).
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.store/branch-connection cluster)
            requests (atom [])]
        ;; pass 1: open + claim (the busy fence before the expensive part)
        (let [work (work/next-agent-work @connection (request connection))]
          (is (= :open (:seon.cluster.work/situation work)))
          (cluster.loop/turn {:seon.cluster.loop/cluster cluster
                              :seon.cluster.work/next work}
                             (Date.)))
        (testing "the held run derives :call for its holder ONLY"
          (is (= :call (:seon.cluster.work/situation
                        (work/next-agent-work @connection (request connection)))))
          (is (nil? (work/next-agent-work @connection
                                    {:seon.cluster.run/process
                                     "some-other-process"
                                     :seon.cluster.work/now (Date.)}))
              "custody mismatch: another process derives NO work for it"))
        ;; the rest of the interleaving, arbitrarily later — there is
        ;; no clock on custody, so the pass simply proceeds
        (with-redefs [ai/complete
                      (recording-completer
                       requests
                       [{:seon.ai/text "(my.run/complete \"one\")"}])]
          (binding [*evaluation* {:seon.cluster.eval/result-edn
                                  (pr-str (my.run/complete "one"))
                                  :seon.sci.admit/value
                                  (my.run/complete "one")}]
            (drive! cluster 6)))
        (is (= 1 (count @requests))
            "zero duplicate provider dispatches across the interleaving")
        (is (= 1 (count (attempt-rows @connection)))
            "and the durable attempt chain agrees")
        (is (some? (d/q '[:find ?c . :where
                          [_ :seon.cluster.run/closed-at ?c]] @connection))
            "the turn ran to completion")))))

;;; ---------------------------------------------------------------------------
;;; Nothing throws into the agent loop — the prompt refusal seam
;;; ---------------------------------------------------------------------------

(deftest a-prompt-refusal-is-a-recorded-error-value-never-a-throw
  ;; `seon.cluster.prompt/prompt` refuses by THROWING (`::no-trigger`,
  ;; `::missing-input`). \"Nothing throws into the agent loop\" is law:
  ;; the loop's one `:call` site catches it and records the flat error
  ;; value, the turn ends `:error`, and no provider call is made.
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.store/branch-connection cluster)
            requests (atom [])]
        ;; a held run whose creating transaction names NO trigger — the
        ;; caller-bug state `::no-trigger` seals
        (d/transact connection
                    [{:seon.cluster.run/id "run-untriggered"
                      :seon.cluster.run/agent [:seon.cluster.agent/id "agent-a"]
                      :seon.cluster.run/opened-at now
                      :seon.cluster.run/process process}
                     {:seon.cluster.agent/id "agent-a"
                      :seon.cluster.agent/run
                      [:seon.cluster.run/id "run-untriggered"]}])
        (with-redefs [ai/complete
                      (recording-completer requests [{:seon.ai/text "unused"}])]
          (let [report (cluster.loop/turn
                        {:seon.cluster.loop/cluster cluster
                         :seon.cluster.work/next
                         {:seon.cluster.work/situation :call
                          :seon.cluster.run/id "run-untriggered"
                          :seon.cluster.agent/id "agent-a"}}
                        (Date.))]
            (is (= :error (:seon.cluster.loop/outcome report))
                "the turn ends as a value — the throw never escapes")))
        (is (empty? @requests) "no provider call without a prompt")
        (is (empty? (attempt-rows @connection)) "and no attempt row")
        (testing "the refusal is a durable error fact naming its rule"
          (is (contains? (set (d/q '[:find [?kind ...] :where
                                     [?e :seon.error/kind ?kind]]
                                   @connection))
                         :seon.cluster.prompt/refused)))))))

;;; ---------------------------------------------------------------------------
;;; The prompt's cause is the run's recorded trigger
;;; ---------------------------------------------------------------------------

(deftest the-call-prompts-with-the-trigger-the-run-opened-on
  ;; simplification-catalog-2026-07-28 group 3's confirmed defect: the
  ;; :call pass re-asked `unanswered-triggers` for the prompt's cause,
  ;; but the run-opening transaction ANSWERS its trigger, so the re-ask
  ;; selected whatever message arrived NEXT (and only prompted the
  ;; right content when none had, because nil matched anything). The
  ;; trigger the run OPENED on is the trigger: recorded as tx-meta on
  ;; the opening transaction, derived back by `message/trigger`.
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.store/branch-connection cluster)
            requests (atom [])]
        ;; pass 1: the loop opens a run on m-1 ("count the widgets")
        (let [work (work/next-agent-work @connection (request connection))]
          (is (= :open (:seon.cluster.work/situation work)))
          (cluster.loop/turn {:seon.cluster.loop/cluster cluster
                              :seon.cluster.work/next work}
                             (Date.)))
        ;; message B arrives BETWEEN open and the :call pass
        (d/transact connection
                    [{:seon.cluster.message/id "m-2"
                      :seon.cluster.message/to
                      [:seon.cluster.agent/id "agent-a"]
                      :seon.cluster.message/content "ignore everything else"
                      :seon.cluster.message/at (Date.)}])
        ;; pass 2: the ONE paid call must carry m-1's content, not m-2's
        (with-redefs [ai/complete
                      (recording-completer
                       requests
                       [{:seon.ai/text "(my.run/complete \"counted\")"}])]
          (let [work (work/next-agent-work @connection (request connection))]
            (is (= :call (:seon.cluster.work/situation work)))
            (cluster.loop/turn {:seon.cluster.loop/cluster cluster
                                :seon.cluster.work/next work}
                               (Date.))))
        (is (= 1 (count @requests)))
        (let [prompt-text (:seon.ai/prompt (first @requests))]
          (is (str/includes? prompt-text "count the widgets")
              "the provider request carries the trigger the run OPENED
               on — the run's own recorded cause")
          ;; One walk honestly includes both connected messages. The cause
          ;; invariant is a database fact, not a special prompt block.
          (is (str/includes? prompt-text "ignore everything else")
              "the fresh walk also includes the later connected message")
          (let [run-id (d/q '[:find ?run-id .
                              :where
                              [?run :seon.cluster.run/id ?run-id]]
                            @connection)]
            (is (= "m-1" (message/trigger @connection run-id))
                "a message arriving between open and :call cannot displace
                 the run's recorded trigger")))))))

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
  (fn [request]
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
        graph (flow.core/create-flow
               {:procs
                {:seon.render.web/render
                 {:proc (seon.flow/var-process
                         #'web/render-step :io
                         {:seon.render.web/render-channel
                          render-channel
                          :seon.render.web/pages-channel
                          (async/chan (async/sliding-buffer 1))
                          :seon.render.web/registration (atom {})
                          :seon.render.web/completion completion
                          :seon.cluster.loop/cluster cluster})}}
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
  agents. Event-driven against the proc's own published state, with a
  loud backstop whose firing is itself the bug report."
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
            cluster (assoc cluster :seon.cluster.loop/stream-channel
                           stream-channel)
            connection (:seon.store/branch-connection cluster)
            proc (render-proc-for cluster)
            requests (atom [])
            chunks ["(my.run/complete " "\"streamed" " home\")"]]
        (try
          (with-redefs [ai/complete (streaming-completer requests chunks)]
            (let [basis-before (:max-tx @connection)
                  reports (drive! cluster 10)]
              (is (= [:open :call :resume]
                     (vec (take 3 (mapv :seon.cluster.work/situation
                                        reports))))
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
                       (d/q '[:find [?ident ...]
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
                (is (= 1 (count (d/q '[:find ?e :where
                                       [?e :seon.ai.attempt/ordinal _]]
                                     @connection)))
                    "ONE attempt row for the one paid call")
                (is (some? (d/q '[:find ?edn . :where
                                  [?e :seon.cluster.eval/result-edn ?edn]]
                                @connection))
                    "and the terminal receipt settled"))

              (testing "the settled reply's text equals the fold's final
                        snapshot text"
                (let [reply (d/q '[:find ?text . :where
                                   [?m :seon.cluster.message/content ?text]
                                   [?m :seon.cluster.message/from _]]
                                 @connection)]
                  (is (or (nil? reply)
                          (string? reply))
                      "the reply is a durable fact or the run closed
                       without one — either way the TEXT never came
                       from the channel")))

              (testing "the terminal fact is the stream terminal"
                ;; Production's one routing listener offers this
                ;; payload-free interest on the terminal transaction.
                ;; This turn fixture owns no listener, so publish the
                ;; already-observed fact wake at the same proc port.
                (async/offer! (:render-channel proc) ::terminal-fact)
                (await-streaming! proc 0 [:streaming-superseded])
                (is (= 0 (streaming-agents proc))
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
      (let [connection (:seon.store/branch-connection cluster)
            stream-channel (async/chan (async/sliding-buffer 1))
            cluster (assoc cluster :seon.cluster.loop/stream-channel
                           stream-channel)]
        ;; a second agent with a trigger of its own
        (d/transact connection
                    [(agent-row "agent-b")
                     {:seon.cluster.message/id "m-b"
                      :seon.cluster.message/to
                      [:seon.cluster.agent/id "agent-b"]
                      :seon.cluster.message/content "count the sprockets"
                      :seon.cluster.message/at (Date.)}])
        ;; NOBODY reads the conn for the whole run: every offer must
        ;; still return immediately, which is what sliding-1 buys
        (let [offers (atom [])
              texts {"agent-a" "(my.run/complete \"alpha\")"
                     "agent-b" "(my.run/complete \"beta\")"}
              completer
              (fn [request]
                (let [sink (:seon.ai/sink request)
                      ;; which agent this call belongs to is derivable
                      ;; from the prompt the turn captured
                      agent-id (if (str/includes? (:seon.ai/prompt request)
                                                  "sprockets")
                                 "agent-b"
                                 "agent-a")
                      text (get texts agent-id)]
                  (doseq [n (range 1 (inc (count text)))]
                    (let [started (System/nanoTime)]
                      (when sink
                        (sink {:seon.ai/text (subs text 0 n)
                               :seon.ai/tokens n}))
                      (swap! offers conj (- (System/nanoTime) started))))
                  {:seon.ai/text text}))]
          (try
            (with-redefs [ai/complete completer]
              (drive-passes! cluster 24))

            (testing "both agents settled at their EXACT texts, from facts"
              (doseq [[agent-id text] texts]
                (let [sources
                      (d/q '[:find [?source ...]
                             :in $ ?agent-id
                             :where
                             [?agent :seon.cluster.agent/id ?agent-id]
                             [?run :seon.cluster.run/agent ?agent]
                             [?form :seon.cluster.run.form/run ?run]
                             [?form :seon.cluster.run.form/source ?source]]
                           @connection agent-id)
                      receipts
                      (d/q '[:find [?edn ...]
                             :in $ ?agent-id
                             :where
                             [?agent :seon.cluster.agent/id ?agent-id]
                             [?run :seon.cluster.run/agent ?agent]
                             [?e :seon.cluster.eval/run ?run]
                             [?e :seon.cluster.eval/result-edn ?edn]]
                           @connection agent-id)]
                  (is (seq receipts)
                      (str agent-id " produced a terminal receipt"))
                  ;; the FROZEN PLAN is the durable record of what the
                  ;; provider settled on. It is a fact, committed once,
                  ;; and it is byte-identical to this agent's own text —
                  ;; while the channel, shared and lossy, carried only
                  ;; presentation that either arrived or did not
                  (is (= [text] sources)
                      (str agent-id "'s settled text came from FACTS: "
                           "the frozen plan, not the shared conn")))))

            (testing "the producers' fold threads were NEVER parked"
              (is (seq @offers) "the sinks really ran")
              ;; sliding-1 never parks a producer: it drops the older
              ;; value. The oracle is the offers' own durations — an
              ;; offer that parked on an unread channel would be orders
              ;; of magnitude slower than one that slid.
              (let [slowest (apply max @offers)]
                (is (< slowest 100000000)
                    (str "the slowest offer took " slowest
                         " ns — a parked producer would never return
                          while nothing reads the conn"))))

            (testing "a displaced snapshot is superseded, never lost work"
              ;; only ONE value is ever pending, and it is the newest
              (let [pending (async/poll! stream-channel)]
                (is (contains? #{"agent-a" "agent-b"}
                               (:seon.cluster.agent/id pending))
                    "exactly one newest snapshot, whichever agent won
                     the race — the other's next chunk repaired it")
                (is (string? (:seon.cluster.run/id pending))
                    "the partial names the run whose terminal facts
                     supersede it")
                (is (map? (:seon.ai/partial pending))
                    "only complete partial snapshots ride the conn;
                     there is no clear shape")
                (is (nil? (async/poll! stream-channel))
                    "and never a queue: sliding-1 holds exactly one")))
            (finally
              (async/close! stream-channel))))))))
