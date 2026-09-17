(ns seon.gen.loop-test
  "Whole-turn execution over a real cluster, with only provider text supplied.

  Ruling 67 settles the whole reply as one batch. A failed form leaves
  its error on its evaluation and later forms still run. Automatic owner
  routing was deferred by the 2026-09-05 owner ruling; assignment and
  declination are explicit message protocols, covered by their own tests.
  Completion prose does not erase failed evaluation evidence."
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow.core]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.core :as datahike]
            [seon.db :as db]
            [seon.ai :as ai]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.turn :as turn]

            [seon.config :as config]
            [seon.flow :as seon.flow]
            [seon.render :as render]
            [seon.render.web :as web]
            [seon.test-support :as test-support])
  (:import [java.util Date]))

(def ^:private test-environment
  ;; The subset environment (store layer only) every crossing this
  ;; namespace constructs names; boot's own constructor, fewer layers.
  (delay (test-support/environment "seon.gen.loop-test")))

(def ^:private process
  (cluster/process-identity {:seon.boot/pid 4242
                             :seon.boot/start-instant (Date. 1700000000000)}))

(def ^:private now (Date. 1700000000000))

;;; ---------------------------------------------------------------------------
;;; The cast, created through the ONE formal path
;;; ---------------------------------------------------------------------------

;;; S3's lesson: there is no namespace-less agent. The planner owns
;;; `my.gen.planner` exactly as alpha and beta own theirs, and it is
;;; that ownership — not a table in this file — that decides where a
;;; red form goes.
(def ^:private cast-spec
  [["root" 'my.agents.root]
   ["planner" 'my.gen.planner]
   ["alpha" 'my.gen.alpha]
   ["beta" 'my.gen.beta]])

(def ^:private cast-rows
  (into []
        (mapcat (fn [[agent-id namespace-name]]
                  (agent/creation-tx {:seon.agent/id agent-id
                                      :seon.cluster/name "generate-code-v0"
                                      :seon.ns/name namespace-name})))
        cast-spec))

(defn- with-render-context-proc
  "Run the production render proc that answers prompt context requests."
  [cluster body]
  (let [context-channel
        (test-support/render-context-channel
         (render/agent-render-profile (config/defaults)))
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

(defn- with-gen-cluster
  "One in-process cluster with the v0 cast and the production dials."
  [body]
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
       (test-support/seed-cluster! connection "generate-code-v0")
       (test-support/transacted! connection
                               (conj cast-rows
                                     (:seon.config/desired-row
                                      (config/compile-manifest
                                       {:seon.boot/cluster-name "generate-code-v0"
                                        :seon.config/manifest
                                        {:seon.config.run/max-episode-runs 100}}))))
       ;; Unresolved calls cross the ordinary evaluator boundary; the
       ;; run loop's second static-admission pass was deleted with the
       ;; minimal turn, so no lint bypass is needed.
       (with-render-context-proc
         (test-support/cluster-handle
         {:seon.db/connection connection
          :seon.cluster/name "generate-code-v0"
          :seon.flow/work-launcher launcher
          :seon.db.process/id process
          :seon.sci.eval/ctx (test-support/fork-cluster-ctx connection)
          :seon.cluster.wake/channel
          (async/chan (async/sliding-buffer 1))
          :seon.config.eval/time-limit-ms 2000
          :seon.config/on-core-error :panic
          :seon.config.error/recurrence-limit 3
          :seon.config.message/max-chain 4
          :seon.sci.admit/caps
          (assoc (config/result-caps (test-support/effective-config))
                 :seon.config.eval.result/max-depth 6
                 :seon.config.eval.result/max-collection 8
                 :seon.config.eval.result/max-string 4096
                 :seon.config.eval.result/max-nodes 256)})
         body)
       (finally
         (seon.flow/stop-work-launcher! launcher)))))))

(defn- agent-ids
  [db]
  (sort (db/q '[:find [?id ...] :where [?e :seon.agent/id ?id]] db)))

(defn- planner-census
  [database]
  (let [run-id
        (->> (db/q '[:find ?id ?opened
                    :where
                    [?run :seon.turn/id ?id]
                    [?run :seon.turn/opened-tx ?opened]
                    [?run :seon.turn/attempts ?attempt]
                    [?run :seon.turn/agent ?agent]
                    [?agent :seon.agent/id "planner"]]
                  database)
             (sort-by second)
             ffirst)
        count-run-members
        (fn [attribute]
          (count
           (db/q '[:find ?member
                   :in $ ?run-id ?attribute
                   :where
                   [?run :seon.turn/id ?run-id]
                   [?member ?attribute ?run]]
                 database run-id attribute)))
        terminal-receipt-count
        (count
         (db/q '[:find ?receipt
                 :in $ ?run-id
                 :where
                 [?run :seon.turn/id ?run-id]
                 [?receipt :seon.cluster.eval/run ?run]
                 (or [?receipt :seon.eval/shown _]
                     [?receipt :seon.cluster.eval/error _]
                     [?receipt :seon.cluster.eval/interrupted-at _])]
               database run-id))]
    {:seon.gen.loop/run-id run-id
     :seon.gen.loop/form-count
     (count-run-members :seon.cluster.eval/run)
     :seon.gen.loop/receipt-count
     (count-run-members :seon.cluster.eval/run)
     :seon.gen.loop/terminal-receipt-count terminal-receipt-count}))

(defn- complete-planner-census?
  [expected-form-count census]
  (and (some? (:seon.gen.loop/run-id census))
       (= expected-form-count
          (:seon.gen.loop/form-count census)
          (:seon.gen.loop/receipt-count census)
          (:seon.gen.loop/terminal-receipt-count census))))

(defn- drive!
  "Drive agent passes through the planner's exact terminal receipt census."
  [cluster limit expected-form-count]
  (let [connection (:seon.db/connection cluster)
        events (async/chan (async/sliding-buffer 1))
        listener-key (random-uuid)]
    (datahike/listen! connection listener-key #(async/put! events %))
    (try
      (loop [passes 0]
        (when (< passes limit)
          ;; a deterministic clock that still advances: run order is a
          ;; fact this suite reads, and a wall clock makes two runs in the
          ;; same millisecond order themselves at random
          (let [at (Date. (+ (inst-ms now) (* 1000 (long passes))))
                work (some (fn [agent-id]

                             (turn/next-agent-work
                              @connection
                              {:seon.agent/id agent-id
                               :seon.db.process/id process
                               :seon.turn.work/now at}))
                           (agent-ids @connection))]
            (when work
              (turn/turn {:seon.turn.loop/cluster cluster
                                  :seon.turn.work/next work}
                                 at)
              (recur (inc passes))))))
      (let [event
            (test-support/await-event!
             events
             {:seon.gen.loop/event ::planner-terminal-census
              :seon.gen.loop/expected-form-count expected-form-count
              :seon.gen.loop/observed (planner-census @connection)}
             #(complete-planner-census?
               expected-form-count
               (planner-census (:db-after %))))]
        (:seon.gen.loop/run-id (planner-census (:db-after event))))
      (finally
        (datahike/unlisten! connection listener-key)
        (async/close! events)))))

;;; ---------------------------------------------------------------------------
;;; The staged attempt — failure is INJECTED, so the design is what is
;;; being measured rather than the model's mood
;;; ---------------------------------------------------------------------------

(def ^:private program
  "Two namespaces in one reply, each with one form that fails.
  The failures are INJECTED so the observable is the design and not the
  model's mood: an unresolved symbol is red through the ordinary
  evaluator, receipt and admission path, with nothing stubbed."
  (str "I will set up both namespaces.\n"
       "(in-ns 'my.gen.alpha)\n"
       "(defn widget-total [n] (* n 3))\n"
       "(alpha-helper-missing)\n"
       "(in-ns 'my.gen.beta)\n"
       "(defn beta-label [] \"beta\")\n"
       "(beta-helper-missing)\n"))

(def ^:private planner-attempt
  (str program "(seon.run/wait \"asked the namespace owners\")"))

(defn- prompt-agent-id
  "The focal agent owns the namespace in the first REPL prompt."
  [prompt]
  (when-let [prompt-end (str/index-of prompt "=>")]
    (let [namespace-name (subs prompt 0 prompt-end)]
      (some (fn [[agent-id owned-namespace]]
              (when (= namespace-name (str owned-namespace))
                agent-id))
            cast-spec))))

(defn- staged-reply
  "One provider stub for the whole cast, keyed on WHOSE prompt it is."
  [{prompt :seon.ai/prompt}]
  {:seon.ai/text
   (case (prompt-agent-id prompt)
     "planner" planner-attempt

     "(seon.run/complete \"nothing to do\")")})

;;; ---------------------------------------------------------------------------
;;; Queries — every milestone is a fact
;;; ---------------------------------------------------------------------------

(defn- form-namespaces
  "Ordinal → parse-time namespace, for the forms that carry one."
  [db run-id]
  (into {}
        (db/q '[:find ?ordinal ?namespace-name
               :in $ ?run-id
               :where
               [?run :seon.turn/id ?run-id]
               [?form :seon.cluster.eval/run ?run]
               [?form :seon.cluster.eval/ordinal ?ordinal]
               [?form :seon.cluster.eval/ns ?namespace]
               [?namespace :seon.ns/name ?namespace-name]]
             db run-id)))

(defn- assignments
  "Messages about one run's red receipts: #{[recipient receipt-id]}."
  [db run-id]
  (set (db/q '[:find ?to-id ?receipt-id
              :in $ ?run-id
              :where
              [?run :seon.turn/id ?run-id]
              [?receipt :seon.cluster.eval/run ?run]
              [?receipt :seon.cluster.eval/id ?receipt-id]
              [?m :seon.message/assignment ?receipt-id]
              [?m :seon.message/to ?to]
              [?to :seon.agent/id ?to-id]]
            db run-id)))

(defn- states
  "The derived state of every form of the planner's plan, by ordinal."
  [db run-id]
  (into {}
        (map (juxt :seon.cluster.eval/ordinal
                   :seon.turn.work/form-state))
        (:seon.turn.work/forms (turn/plan-settlement db run-id))))

;;; ---------------------------------------------------------------------------
;;; The loop
;;; ---------------------------------------------------------------------------

(deftest a-goal-is-a-message-and-the-attempt-retains-its-own-failures
  (with-gen-cluster
   (fn [cluster]
     (let [connection (:seon.db/connection cluster)]
       ;; THE SURFACE: no new agent-facing construct — root's goal is an
       ;; ordinary message, and everything after it is the system's own
       ;; doing.
       (test-support/transacted! connection
                               [{:seon.message/id "goal-1" :seon.message/to [:seon.agent/id "planner"] :seon.message/from [:seon.agent/id "root"] :seon.message/content (str "Build the widget helpers: my.gen.alpha owns "
                                      "the arithmetic and my.gen.beta owns the label.")}])
       (let [run-id (with-redefs [ai/complete staged-reply]
                      (drive! cluster 12 7))
             db @connection]

         (testing "the attempt froze one plan whose forms carry the
                   namespace each was WRITTEN under"
           (is (= {0 'my.gen.planner
                   1 'my.gen.alpha
                   2 'my.gen.alpha
                   3 'my.gen.alpha
                   4 'my.gen.beta
                   5 'my.gen.beta
                   6 'my.gen.beta}
                  (form-namespaces db run-id))
               "REPL semantics across two declarations in ONE reply.
                The agent namespace is the default until a namespace
                declaration governs; from there the last EXPLICIT
                declaration holds, so `(alpha-helper-missing)` runs in
                my.gen.alpha where it was written. Presuming an ordinary
                call moved the namespace is what erased most of the
                program graph (2026-07-29); only a form that mentions
                `ns`/`in-ns` below its head clears attribution.")
           (is (= 7 (count (db/q '[:find ?f
                                  :in $ ?run-id
                                  :where
                                  [?run :seon.turn/id ?run-id]
                                  [?f :seon.cluster.eval/run ?run]]
                                db run-id)))
               "seven forms froze and every form has evaluation truth"))

         (testing "execution honours parse-time attribution"
           (is (str/includes?
                (db/q '[:find ?edn .
                       :in $ ?run-id
                       :where
                       [?run :seon.turn/id ?run-id]
                       [?r :seon.cluster.eval/run ?run]
                       [?r :seon.cluster.eval/ordinal 1]
                       [?r :seon.eval/shown ?edn]]
                     db run-id)
                "my.gen.alpha/widget-total")
               "the definition is installed in the namespace the reader
                attributed to its form"))

         (testing "the fold CONTINUES past a red form — nothing halts"
           (is (= 7 (count (db/q '[:find ?r
                                  :in $ ?run-id
                                  :where
                                  [?run :seon.turn/id ?run-id]
                                  [?r :seon.cluster.eval/run ?run]]
                                db run-id)))
               "one receipt per ordinal, including the ones after the
                first failure"))

         (testing "errors remain evaluation facts without automatic assignments"
           (is (empty? (assignments db run-id)))
           (is (= #{:unrouted-red}
                  (set (vals (select-keys (states db run-id) [2 5]))))))

         (testing "the whole reply settles in one transaction"
           (is (= 1
                  (count (db/q '[:find ?tx
                                 :in $ ?run-id
                                 :where
                                 [?run :seon.turn/id ?run-id]
                                 [?evaluation :seon.cluster.eval/run ?run]
                                 [?evaluation :seon.eval/shown _ ?tx]]
                               db run-id)))))

         (testing "the red evidence survives the settlement"
           (is (some? (db/q '[:find ?error .
                             :in $ ?run-id
                             :where
                             [?run :seon.turn/id ?run-id]
                             [?r :seon.cluster.eval/run ?run]
                             [?r :seon.cluster.eval/ordinal 5]
                             [?r :seon.cluster.eval/error ?error]]
                           db run-id))))

         (testing "the plan is NOT settled, and no agent's completion
                   can make it so"
           (is (false? (:seon.turn.work/settled?
                        (turn/plan-settlement db run-id))))
           (is (some? (db/q '[:find ?closed .
                             :in $ ?run-id
                             :where
                             [?run :seon.turn/id ?run-id]
                             [?run :seon.turn/closed-tx ?closed]]
                           db run-id))
               "the planner's run closed normally — settlement is a
                derivation over forms, never a run state"))

         (testing "the derivation transacts nothing"
           ;; The live drive cannot settle this — its cluster is still
           ;; taking turns while the derivation runs, so `max-tx` moves
           ;; for reasons that have nothing to do with it. Here the
           ;; drive has stopped, so the basis is decisive.
           (let [before (:max-tx @connection)]
             (turn/plan-settlement @connection run-id)
             (is (= before (:max-tx @connection))
                 "plan settlement is a pure function of a database
                  value; deriving it can never commit")))

         (testing "settlement preserves the subjectless message and answers its wake"
           (is (= "goal-1"
                  (:seon.message/id
                   (db/pull db [:seon.message/id] [:seon.message/id "goal-1"]))))
           (is (some? (db/q '[:find ?turn . :in $ ?run-id :where
                             [?turn :seon.turn/id ?run-id]
                             [?turn :seon.turn/handled ?message]
                             [?message :seon.message/id "goal-1"]] db run-id)))
           (is (empty? (turn/unanswered-triggers db "planner")))))))))

(deftest a-computation-using-a-failed-definition-retains-its-error
  ;; A deterministic failed definition followed by a computation using it:
  ;; both errors must remain visible after the batch has settled.
  (with-gen-cluster
   (fn [cluster]
     (let [connection (:seon.db/connection cluster)]
       (test-support/transacted! connection
                               [{:seon.message/id "goal-1" :seon.message/to [:seon.agent/id "planner"] :seon.message/from [:seon.agent/id "root"] :seon.message/content "Count the primes."}])
       (let [run-id
             (with-redefs [ai/complete
                           (fn [{prompt :seon.ai/prompt}]
                             {:seon.ai/text
                              (if (= "planner" (prompt-agent-id prompt))
                                (str "(in-ns 'my.gen.alpha)\n"
                                     ;; The failure is explicit, independent of Java admission.
                                     "(def primes (throw (ex-info \"injected failure\" {})))\n"
                                     ;; Computing with the failed definition must also fail.
                                     "(+ primes 1)\n")
                                "(seon.run/wait \"nothing\")")})]
               (drive! cluster 6 3))
             db @connection
             state (states db run-id)]
         (is (= :unrouted-red (get state 1))
             "the failed definition retains its error")
         (is (= :unrouted-red (get state 2))
             "the later computation also retains its error")
         (is (false? (:seon.turn.work/settled?
                      (turn/plan-settlement db run-id)))))))))

(deftest completion-prose-does-not-erase-failed-evaluations
  ;; A completion reply is delivered, but it does not rewrite prior errors.
  (with-gen-cluster
   (fn [cluster]
     (let [connection (:seon.db/connection cluster)]
       (test-support/transacted! connection
                               [{:seon.message/id "goal-1" :seon.message/to [:seon.agent/id "planner"] :seon.message/from [:seon.agent/id "root"] :seon.message/content "Build the helpers."}])
       ;; every owner is mute; only the planner ever answers, and it
       ;; answers by claiming it is done
       (let [run-id
             (with-redefs [ai/complete
                           (fn [{prompt :seon.ai/prompt}]
                             {:seon.ai/text
                              (if (= "planner" (prompt-agent-id prompt))
                                (str program
                                     "(seon.run/complete \"the program is built\")")
                                "(seon.run/wait \"saying nothing\")")})]
               (drive! cluster 12 7))
             db @connection]
         (is (= "the program is built"
                (db/q '[:find ?content .
                       :where
                       [?m :seon.message/to ?to]
                       [?to :seon.agent/id "root"]
                       [?m :seon.message/content ?content]]
                     db))
             "the planner said it was finished")
         (is (false? (:seon.turn.work/settled?
                      (turn/plan-settlement db run-id)))
             "and the facts contradict it — an unsettled evaluation
              keeps the plan open no matter what the reply says")
         (is (= #{:unrouted-red}
                (set (vals (select-keys (states db run-id) [2 5]))))
             "both failures remain on their original evaluations"))))))
