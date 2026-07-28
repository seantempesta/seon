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
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [my.run :as my.run]
            [seon.ai :as ai]
            [seon.cluster :as cluster]
            [seon.cluster.loop :as cluster.loop]
            [seon.error :as error]
            [seon.render :as render]
            [seon.cluster.message :as message]
            [seon.cluster.prompt :as prompt]
            [seon.cluster.run :as run]
            [seon.cluster.work :as work]
            [sci.core :as sci.core]
            [seon.sci.eval :as sci.eval]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike])
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

;;; THE REAL EVALUATOR, injected through the same seam. The one thing it
;;; adds is the deadline: `turn` passes source + caps only, so the time
;;; limit — the ONE limit — has nowhere to come from at that call site.
;;; That is a seam defect in the loop's call, reported rather than
;;; papered over; this adapter supplies it so the injection can be
;;; proven against a real sci evaluation today.
;;; The seam needs no adapter any more: the cluster handle carries the
;;; deadline dial and the error disposition, and `turn` forks ONE ctx
;;; per run and threads it through the fold. This is the real evaluator,
;;; injected exactly as production injects it.

(def ^:private seed-blocks
  "The intended core seed membership (context-blocks contract §3.5).
  Owned by the seed-and-membership package in production; planted here
  because a membership is the fixture's own data — the prompt derives
  from the agent's blocks now, and an agent with no AI blocks has no
  prompt."
  [{:seon.render.block/name :identity :seon.render.block/band :anchor
    :seon.render.block/priority 0
    :seon.render/ai 'seon.context/identity-ai}
   {:seon.render.block/name :execution :seon.render.block/band :anchor
    :seon.render.block/priority 10
    :seon.render/ai 'seon.context/execution-ai}
   {:seon.render.block/name :peers :seon.render.block/band :anchor
    :seon.render.block/priority 20
    :seon.render/ai 'seon.context/peers-ai}
   {:seon.render.block/name :interruption :seon.render.block/band :continuity
    :seon.render.block/priority 30
    :seon.render/ai 'seon.context/interruption-ai}
   {:seon.render.block/name :continuity :seon.render.block/band :continuity
    :seon.render.block/priority 40
    :seon.render/ai 'seon.context/continuity-ai}
   {:seon.render.block/name :trigger :seon.render.block/band :dynamic
    :seon.render.block/priority 90
    :seon.render/ai 'seon.context/trigger-ai}])

(defn- agent-row
  "One agent with the core seed membership installed."
  [agent-id]
  {:seon.cluster.agent/id agent-id
   :seon.cluster.agent/blocks seed-blocks})

(defn- with-cluster [body]
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :schema-flexibility :write}
        _ (d/create-database configuration)
        connection (d/connect configuration)]
    (try
      (d/transact connection
                  (schema.datahike/malli->datahike-schema
                   (schema/canonical-database-attributes)))
      (d/transact connection
                  [(agent-row "agent-a")
                   ;; the episode dial, planted the way production
                   ;; ships it (config/default.edn): an ABSENT dial is
                   ;; FAIL-CLOSED for agent-sent triggers, so the
                   ;; delegation fixtures need the fact the defaults
                   ;; document provides
                   {:seon.config/cluster "turn-test"
                    :seon.config.run/max-episode-runs 100}
                   {:seon.cluster.message/id "m-1"
                    :seon.cluster.message/to [:seon.cluster.agent/id "agent-a"]
                    :seon.cluster.message/content "count the widgets"
                    :seon.cluster.message/at now}])
      (body {:seon.store/branch-connection connection
             :seon.cluster.run/process process
             :seon.cluster.wake/channel
             (clojure.core.async/chan (clojure.core.async/sliding-buffer 1))
             ;; ONE target and NO backup: the default shape, where
             ;; `disposition` can never return :failover-now. The
             ;; failover fixtures add :seon.ai/backup explicitly.
             :seon.ai/primary
             {:seon.ai/endpoint "http://127.0.0.1:1/v1"
              :seon.ai/model "probe"
              :seon.ai/api-key-variable "SEON_AI_TEST_KEY"
              :seon.ai/timeout-ms 200}
             ;; a strategy with ZERO retries: the fixtures that want
             ;; backoff configure it, and every other test proves the
             ;; no-wait path without sleeping
             :seon.ai.retry/strategy
             {:seon.ai.retry/base-delay-ms 1
              :seon.ai.retry/multiplier 2.0
              :seon.ai.retry/jitter-fraction 0.0
              :seon.ai.retry/maximum-delay-ms 1
              :seon.ai.retry/maximum-retries 0
              :seon.ai.retry/maximum-total-delay-ms 0}
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
              :seon.config.eval.result/max-nodes 256}})
      (finally
        (d/release connection)
        (d/delete-database configuration)))))

(defn- request [connection]
  {:seon.cluster.run/process process :seon.cluster.work/now (Date.)})

(defn- drive-passes!
  "Run the loop's own pass — settle, then derive, then turn — until idle.
  This drives what `step` drives, so a test sees what production sees."
  [cluster limit]
  (let [connection (:seon.store/branch-connection cluster)]
    (loop [passes 0]
      (let [now (Date.)]
        (doseq [orphan (work/interruptions @connection)]
          (cluster.loop/settle-interruption! cluster
                                             (:seon.cluster.run/id orphan)
                                             now))
        (when-let [work (work/next-work @connection (request connection))]
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
            work (work/next-work @connection request)]
        (if (or (nil? work) (>= passes limit))
          reports
          (recur (inc passes)
                 (conj reports
                       (cluster.loop/turn
                        {:seon.cluster.loop/cluster cluster
                         :seon.cluster.work/next work}
                        (:seon.cluster.work/now request)))))))))

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
                                  (d/q '[:find ?ordinal ?edn
                                         :where
                                         [?e :seon.cluster.eval/ordinal ?ordinal]
                                         [?e :seon.cluster.eval/result-edn ?edn]]
                                       @connection))]
                (is (= (str "#:seon.sci.admit{:reference \"sci.lang.Var\", "
                            ":name \"#'my.agents.agent-a/widgets\"}")
                       (get results 0))
                    "a def evaluates to a VAR, admission names it without
                     dereferencing it, and it landed in the AGENT'S
                     namespace — no in-ns anywhere")
                (is (= "[1 2 3]" (get results 1))
                    "form 1 SAW form 0's def — one ctx per run, not per
                     form — and its lazy sequence came back REALIZED")
                (is (= (pr-str (my.run/complete "counted 6"))
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

(deftest a-settled-orphan-stops-wedging-the-agent
  ;; The crash drill's headline: a process died holding a claimed,
  ;; unplanned run. Boot recovery released the dead CUSTODY, but until
  ;; the loop settles the run the AGENT is still busy — next-work finds
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
          (is (nil? (work/next-work @connection (request connection))))
          (is (= ["run-crashed"]
                 (mapv :seon.cluster.run/id
                       (work/interruptions @connection)))))

        ;; the loop's own pass settles it before deriving anything
        (with-redefs [ai/complete
                      (fn [_] {:seon.ai/text "(my.run/complete \"answered\")"})]
          (binding [*evaluation* {:seon.cluster.eval/result-edn
                                  (pr-str (my.run/complete "answered"))
                                  :seon.sci.admit/value (my.run/complete "answered")}]
            (drive-passes! cluster 8)))

        (testing "the orphan is settled — closed, and no longer an
                  interruption"
          (is (empty? (work/interruptions @connection)))
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
              (is (nil? (work/next-work @connection (request connection)))))))))))

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
            (is (nil? (work/next-work @connection (request connection)))
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

(def ^:private transient-rejection
  "A provider saying \"not now\" and nothing else: transmitted, but it
  told us it did no work."
  (failure :seon.ai/provider-error
           {:seon.ai/error-class :server
            :seon.ai/http-status 503
            :seon.ai/request-transmitted? true
            :seon.ai/response-started? true
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
        value (edn/read-string (:seon.error/data-edn fact))]
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
          (is (not (contains? row :seon.ai/disposition))
              "and no disposition is stored on any row — it is derived
               at read from the durable evidence"))
        (is (nil? (d/q '[:find ?e . :where [?e :seon.error/id _]] @connection))
            "and a call that worked committed no error fact")))))

(deftest an-unpaid-failure-with-a-backup-makes-exactly-two-calls
  (with-cluster
    (fn [cluster]
      (let [cluster (assoc cluster :seon.ai/backup backup-target)
            connection (:seon.store/branch-connection cluster)
            requests (atom [])]
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

(deftest a-request-that-may-have-been-paid-for-never-fails-over
  ;; the strictest reading of the ruling, with a backup sitting right
  ;; there unused: ambiguity is terminal
  (doseq [[label value]
          [["a deadline that fired after transmission"
            (failure :seon.ai/timeout
                     {:seon.ai/error-class :timeout
                      :seon.ai/request-transmitted? true
                      :seon.ai/response-started? false
                      :seon.ai/output-observed? false})]
           ["a transport loss of unknown phase"
            (failure :seon.ai/transport-failure
                     {:seon.ai/error-class :transport-unknown
                      :seon.ai/request-transmitted? true
                      :seon.ai/response-started? false
                      :seon.ai/output-observed? false})]
           ["a 2xx body we could not read — somebody paid for that text"
            (failure :seon.ai/unparseable-body
                     {:seon.ai/error-class :response
                      :seon.ai/http-status 200
                      :seon.ai/request-transmitted? true
                      :seon.ai/response-started? true
                      :seon.ai/output-observed? true})]
           ["a request the provider rejected on its merits"
            (failure :seon.ai/provider-error
                     {:seon.ai/error-class :request
                      :seon.ai/http-status 400
                      :seon.ai/request-transmitted? true
                      :seon.ai/response-started? true
                      :seon.ai/output-observed? false})]]]
    (with-cluster
      (fn [cluster]
        (let [cluster (assoc cluster :seon.ai/backup backup-target)
              connection (:seon.store/branch-connection cluster)
              requests (atom [])]
          (with-redefs [ai/complete (recording-completer requests [value])]
            (drive! cluster 4))
          (is (= 1 (count @requests)) (str label ": exactly one call"))
          (let [rows (attempt-rows @connection)]
            (is (= 1 (count rows)) (str label ": exactly one attempt row"))
            (is (= :fail (derived-disposition @connection
                                              (first rows) true))
                (str label ": the terminal decision is derivable from"
                     " the evidence")))
          (is (nil? (d/q '[:find ?d . :where
                           [_ :seon.cluster.run/plan-digest ?d]] @connection))
              (str label ": no plan"))
          (is (some? (d/q '[:find ?c . :where
                            [_ :seon.cluster.run/closed-at ?c]] @connection))
              (str label ": the run closed with the error")))))))

(deftest with-no-backup-an-unpaid-transient-failure-backs-off-a-bounded-number-of-times
  (with-cluster
    (fn [cluster]
      (let [cluster (assoc cluster :seon.ai.retry/strategy
                           {:seon.ai.retry/base-delay-ms 1
                            :seon.ai.retry/multiplier 2.0
                            :seon.ai.retry/jitter-fraction 0.0
                            :seon.ai.retry/maximum-delay-ms 4
                            :seon.ai.retry/maximum-retries 2
                            :seon.ai.retry/maximum-total-delay-ms 1000})
            connection (:seon.store/branch-connection cluster)
            requests (atom [])]
        (with-redefs [ai/complete
                      (recording-completer requests [transient-rejection])]
          (drive! cluster 4))
        (testing "the SCHEDULE's count, not a number this branch chose:
        one attempt plus exactly the two configured retries"
          (is (= 3 (count @requests))))
        (let [rows (attempt-rows @connection)]
          (is (= 3 (count rows)))
          (is (= [:backoff :backoff :backoff]
                 (mapv #(derived-disposition @connection % false) rows))
              "every one of them is derivable from its own evidence,
               and the last one had no wait left to spend")
          (is (= [1 2] (keep :seon.ai.attempt/delay-ms rows))
              "each retry records the wait that preceded it — the
               doubling is readable back out of the facts")
          (is (every? #(not (contains? % :seon.ai.attempt/failover-from)) rows)
              "and nothing failed over: a retry is not a failover"))
        (is (some? (d/q '[:find ?c . :where
                          [_ :seon.cluster.run/closed-at ?c]] @connection))
            "an exhausted schedule ends where :fail ends")))))

(deftest a-configured-backup-turns-the-backoff-path-off-entirely
  ;; the no-backup rule, held by DATA: with a backup the schedule is
  ;; empty, so a transient rejection fails over once and stops — it
  ;; never falls through into retrying the backup
  (with-cluster
    (fn [cluster]
      (let [cluster (assoc cluster
                           :seon.ai/backup backup-target
                           :seon.ai.retry/strategy
                           {:seon.ai.retry/base-delay-ms 1
                            :seon.ai.retry/multiplier 2.0
                            :seon.ai.retry/jitter-fraction 0.0
                            :seon.ai.retry/maximum-delay-ms 4
                            :seon.ai.retry/maximum-retries 5
                            :seon.ai.retry/maximum-total-delay-ms 1000})
            connection (:seon.store/branch-connection cluster)
            requests (atom [])]
        (with-redefs [ai/complete
                      (recording-completer requests [transient-rejection])]
          (drive! cluster 4))
        (is (= 2 (count @requests))
            "primary, backup, stop — five configured retries bought
             nothing, because the backoff path is the NO-backup path")
        (let [rows (attempt-rows @connection)]
          (is (= 2 (count rows)))
          (is (= [:failover-now :backoff]
                 (mapv #(derived-disposition @connection % true) rows))
              "and the SECOND row is the interesting one. `disposition`
               is pure and says what it always says about a 503 with no
               further target (the backup row's failover-from connection
               removes the backup from ITS decision): waiting could
               help. What decides that nothing waits is the SCHEDULE,
               which is empty because this cluster configured a backup
               — the two facts are separate on purpose, and both remain
               derivable from the evidence rather than laundered into a
               stored label")
          (is (every? #(not (contains? % :seon.ai.attempt/delay-ms)) rows)
              "so no wait was ever spent, and the rows say so"))))))

(deftest the-whole-failover-story-is-one-query
  ;; the operator falsifier: which target ran, why the second call was
  ;; allowed, and what failure text the backup saw — from facts, with no
  ;; logs and no reproduction by hand
  (with-cluster
    (fn [cluster]
      (let [cluster (assoc cluster :seon.ai/backup backup-target)
            connection (:seon.store/branch-connection cluster)
            requests (atom [])]
        (with-redefs [ai/complete
                      (recording-completer
                       requests
                       [unpaid {:seon.ai/text "(my.run/complete \"ok\")"}])]
          (binding [*evaluation* {:seon.cluster.eval/result-edn
                                  (pr-str (my.run/complete "ok"))
                                  :seon.sci.admit/value (my.run/complete "ok")}]
            (drive! cluster 10)))
        (let [story (d/q '[:find ?model ?message
                           :where
                           [?backup :seon.ai.attempt/failover-from ?primary]
                           [?backup :seon.ai/model ?model]
                           [?primary :seon.ai.attempt/error ?error]
                           [?error :seon.error/message ?message]]
                         @connection)
              [primary-row] (attempt-rows @connection)]
          (is (= #{["backup-probe" "probe failure: transport-failure"]}
                 story))
          (is (= :failover-now
                 (derived-disposition @connection primary-row true))
              "and WHY is one derivation over the same facts — no
               stored label required to tell the story"))))))

(deftest a-failed-model-call-ends-the-turn-without-a-plan
  (with-cluster
    (fn [cluster]
      (with-redefs [ai/complete
                    (fn [_] {:seon.error/kind :seon.ai/timeout
                             :seon.error/message "slow"
                             :seon.error/data {}})]
        (let [connection (:seon.store/branch-connection cluster)
              reports (drive! cluster 4)]
          (is (= :error (:seon.cluster.loop/outcome (last reports))))
          (is (nil? (d/q '[:find ?d . :where
                           [_ :seon.cluster.run/plan-digest ?d]] @connection))
              "no plan was frozen — and NOTHING re-called the model"))))))

(deftest an-unreadable-reply-ends-the-turn-without-a-plan
  (with-cluster
    (fn [cluster]
      (with-redefs [ai/complete (fn [_] {:seon.ai/text "I'd rather not."})]
        (let [connection (:seon.store/branch-connection cluster)
              reports (drive! cluster 4)]
          (is (= :error (:seon.cluster.loop/outcome (last reports))))
          (is (nil? (d/q '[:find ?d . :where
                           [_ :seon.cluster.run/plan-digest ?d]] @connection))))))))

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
                         (if (str/includes? prompt "You are agent agent-b")
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
;;; Custody precedes work — the two audit interleavings as regressions
;;; (custody-revision-contracts-2026-07-28; probes P1 and P2)
;;; ---------------------------------------------------------------------------

(deftest a-recovered-unheld-planned-run-completes-without-error-facts
  ;; P1, the unheld-resume disposition livelock, unrepresentable: a
  ;; process died mid-fold, boot recovery released its custody, and the
  ;; next pass derives `:resume` for the unheld planned run. The pass
  ;; CLAIMS FIRST, so the disposition's terminal transaction commits,
  ;; the run closes, and no error-fact storm exists. Before the custody
  ;; revision this exact state hot-livelocked forever
  ;; (`::not-the-holder` aborting the settle, then `::receipt-exists`
  ;; on every rewake — research/zombie-constructibility-2026-07-28 §3).
  (with-cluster
    (fn [cluster]
      (let [connection (:seon.store/branch-connection cluster)
            dead "99999-1"]
        ;; the dead process's history, built from the real transitions:
        ;; open+claim on the trigger, a two-form plan whose SECOND form
        ;; carries the disposition, form 0 settled — then it died
        (d/transact connection
                    {:tx-data (into (run/open-tx
                                     {:seon.cluster.run/id "run-p1"
                                      :seon.cluster.run/agent
                                      [:seon.cluster.agent/id "agent-a"]
                                      :seon.cluster.run/opened-at now})
                                    (run/claim-tx
                                     {:seon.cluster.run/id "run-p1"
                                      :seon.cluster.run/process dead
                                      :seon.cluster.run/live-processes #{dead}
                                      :seon.cluster.run/now now}))
                     :tx-meta {:seon.db/trigger
                               [:seon.cluster.message/id "m-1"]}})
        (d/transact connection
                    (run/plan-tx {:seon.cluster.run/id "run-p1"
                                  :seon.cluster.run/process dead
                                  :seon.cluster.run/plan-digest
                                  (apply str (repeat 64 "b"))
                                  :seon.cluster.run/sources
                                  ["(+ 1 2)" "(my.run/complete \"3\")"]}))
        (d/transact connection
                    (run/receipt-start-tx {:seon.cluster.run/id "run-p1"
                                           :seon.cluster.eval/ordinal 0
                                           :seon.cluster.eval/at now}))
        (d/transact connection
                    (run/receipt-settle-tx {:seon.cluster.run/id "run-p1"
                                            :seon.cluster.eval/ordinal 0
                                            :seon.cluster.eval/result-edn "3"}))
        ;; boot recovery on the surviving process releases dead custody
        (d/transact connection
                    (run/recover-tx {:seon.cluster.run/id "run-p1"
                                     :seon.cluster.run/live-processes
                                     #{process}
                                     :seon.cluster.run/now (Date.)}))
        (is (= :resume (:seon.cluster.work/situation
                        (work/next-work @connection (request connection))))
            "the recovered planned run is ordinary :resume work")
        (binding [*evaluation* {:seon.cluster.eval/result-edn
                                (pr-str (my.run/complete "3"))
                                :seon.sci.admit/value (my.run/complete "3")}]
          (drive! cluster 6))
        (testing "the disposition committed and the run closed"
          (is (some? (d/q '[:find ?c . :in $ ?id :where
                            [?r :seon.cluster.run/id ?id]
                            [?r :seon.cluster.run/closed-at ?c]]
                          @connection "run-p1")))
          (is (nil? (work/next-work @connection (request connection)))
              "and nothing re-derives — no livelock"))
        (testing "no error facts — the old path committed one per pass"
          (is (empty? (d/q '[:find ?e :where [?e :seon.error/id _]]
                           @connection))))
        (testing "form 1 was attempted exactly once, under (run, ordinal)
                  identity"
          (is (= 1 (count (d/q '[:find ?e :where
                                 [?e :seon.cluster.eval/ordinal 1]]
                               @connection)))))))))

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
        (let [work (work/next-work @connection (request connection))]
          (is (= :open (:seon.cluster.work/situation work)))
          (cluster.loop/turn {:seon.cluster.loop/cluster cluster
                              :seon.cluster.work/next work}
                             (Date.)))
        (testing "the held run derives :call for its holder ONLY"
          (is (= :call (:seon.cluster.work/situation
                        (work/next-work @connection (request connection)))))
          (is (nil? (work/next-work @connection
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
        (let [work (work/next-work @connection (request connection))]
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
          (let [work (work/next-work @connection (request connection))]
            (is (= :call (:seon.cluster.work/situation work)))
            (cluster.loop/turn {:seon.cluster.loop/cluster cluster
                                :seon.cluster.work/next work}
                               (Date.))))
        (is (= 1 (count @requests)))
        (let [prompt-text (:seon.ai/prompt (first @requests))]
          (is (str/includes? prompt-text "count the widgets")
              "the provider request carries the trigger the run OPENED
               on — the run's own recorded cause")
          (is (not (str/includes? prompt-text "ignore everything else"))
              "a message arriving between open and :call cannot
               displace it"))))))
