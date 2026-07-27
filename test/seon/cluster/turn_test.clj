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
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [my.run :as my.run]
            [seon.ai :as ai]
            [seon.cluster :as cluster]
            [seon.cluster.loop :as cluster.loop]
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
  {:seon.cluster.eval/status :done
   :seon.cluster.eval/result-edn "1"
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
                  [{:seon.cluster.agent/id "agent-a"}
                   {:seon.cluster.message/id "m-1"
                    :seon.cluster.message/to [:seon.cluster.agent/id "agent-a"]
                    :seon.cluster.message/content "count the widgets"
                    :seon.cluster.message/at now}])
      (body {:seon.store/branch-connection connection
             :seon.cluster.run/process process
             :seon.cluster.wake/channel
             (clojure.core.async/chan (clojure.core.async/sliding-buffer 1))
             :seon.cluster.loop/provider
             {:seon.ai/endpoint "http://127.0.0.1:1/v1"
              :seon.ai/model "probe"
              :seon.ai/api-key-variable "SEON_AI_TEST_KEY"
              :seon.ai/timeout-ms 200}
             :seon.cluster.loop/evaluate 'seon.cluster.turn-test/fake-evaluate
             :seon.config.eval/time-limit-ms 2000
             :seon.config/on-core-error :panic
             :seon.sci.admit/caps
             {:seon.config.eval.result/max-depth 6
              :seon.config.eval.result/max-collection 8
              :seon.config.eval.result/max-string 32
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
            (testing "every receipt is done — no interrupt, no error"
              (is (= #{:done}
                     (set (d/q '[:find [?status ...] :where
                                 [_ :seon.cluster.eval/status ?status]]
                               @connection)))))
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
          (testing "every form ran"
            (is (= #{:done}
                   (set (d/q '[:find [?s ...] :where
                               [_ :seon.cluster.eval/status ?s]] @connection)))))
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
                      :seon.cluster.run/opened-at (Date. 1000)
                      :seon.cluster.run/claim-epoch 1}
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
          (binding [*evaluation* {:seon.cluster.eval/status :done
                                  :seon.cluster.eval/result-edn
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
          (is (re-find #"DEEPSEEK_API_KEY"
                       (prompt/prompt @connection
                                      {:seon.cluster.agent/id "agent-a"
                                       :seon.cluster.message/id "m-1"}))))))))

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
          (is (= [:interrupted]
                 (d/q '[:find [?status ...] :where
                        [_ :seon.cluster.eval/status ?status]]
                      @connection))
              "the receipt records the time limit, and the fold moved on")
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
        (binding [*evaluation* {:seon.cluster.eval/status :done
                               :seon.cluster.eval/result-edn "2"
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
        (binding [*evaluation* {:seon.cluster.eval/status :done
                               :seon.cluster.eval/result-edn
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

(deftest a-waiting-disposition-releases-custody-and-leaves-the-run-open
  (with-cluster
    (fn [cluster]
      (with-redefs [ai/complete
                    (fn [_] {:seon.ai/text "(my.run/wait \"need input\")"})]
        (binding [*evaluation* {:seon.cluster.eval/status :done
                               :seon.cluster.eval/result-edn
                               (pr-str (my.run/wait "need input"))
                               :seon.sci.admit/value (my.run/wait "need input")}]
          (let [connection (:seon.store/branch-connection cluster)]
            (drive! cluster 10)
            (is (nil? (d/q '[:find ?p . :where
                             [_ :seon.cluster.run/process ?p]] @connection))
                "custody released")
            (is (nil? (d/q '[:find ?c . :where
                             [_ :seon.cluster.run/closed-at ?c]] @connection))
                "and the run is still open, waiting")))))))

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
