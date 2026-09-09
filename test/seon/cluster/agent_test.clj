(ns seon.cluster.agent-test
  "The F1 sealed suite: agents are flows (seeds 2026072811-2026072820).

  Ten oracles from the sealed contract
  (docs/prds/sci-execution-runtime/plan/f1-agent-graph-contracts-2026-07-28.md
  §8), driven against REAL per-agent graphs wherever the claim is about
  the graphs, and against the real transitions where the claim is about
  the derivation. Per-trial in-memory databases through the canonical
  attribute population; a recorded provider reply (no paid call anywhere), and real SCI
  evaluation. Temporary evaluator wrappers observe execution without
  replacing its semantics."
  (:require [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as async.impl]
            [clojure.core.async.flow :as flow]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.api :as d]
            [seon.db :as db]
            [seon.run :as my.run]
            [seon.ai :as ai]
            [seon.bootstrap :as bootstrap]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.turn :as turn]
            [seon.cluster.prompt :as prompt]

            [seon.cluster.wake :as wake]

            [seon.config :as config]
            [seon.flow :as seon.flow]
            [seon.id :as id]
            [seon.problems :as problems]
            [seon.render :as render]
            [seon.repl :as repl]
            [sci.core :as sci]
            [seon.sci.eval :as sci.eval]
            [seon.render.web :as web]
            [seon.schema :as schema]
            [seon.test-support :as test-support])
  (:import [java.net ServerSocket Socket]
           [java.util Date]
           [java.util.concurrent CountDownLatch Executor]))

(def ^:private test-environment
  ;; The subset environment (store layer only) every crossing this
  ;; namespace constructs names; boot's own constructor, fewer layers.
  (delay (test-support/environment "seon.cluster.agent-test")))

(def ^:private shipped-eval-time-limit-ms
  ;; Ordinary finite evaluation must stay ordinary under scheduler load.
  ;; A shorter fixture-only limit changes the tested disposition.
  (delay (:seon.config.eval/time-limit-ms (config/defaults))))

(set! *warn-on-reflection* true)

(def ^:dynamic *work-launcher* nil)
(def ^:dynamic *context-channel* nil)
(def ^:dynamic *stream-channel* nil)

;;; ---------------------------------------------------------------------------
;;; Fixture — canonical attributes, the handle, the source-driven evaluator
;;; ---------------------------------------------------------------------------

(def ^:private process
  (cluster/process-identity {:seon.boot/pid 8111
                             :seon.boot/start-instant (Date. 1700000000000)}))

(def ^:private now (Date. 1700000000000))

(def ^:private real-evaluate
  "The shipped evaluator, captured before any stand-in replaces its Var."
  sci.eval/evaluate)

(defn fixture-evaluate
  "The real SCI evaluator, exposed as a Var for execution observations."
  [request]
  (real-evaluate request))

(defn- with-connection
  "Drive `body` against a canonical database, real SCI and per-agent graphs.

  THE EVALUATOR IS A VAR, NOT A CONFIG FACT. `seon.turn/evaluate-sources`
  calls `seon.sci.eval/evaluate` directly so the program graph carries the edge,
  so an observation wrapper is installed by replacing that Var's root value — which every
  proc thread sees, unlike a dynamic binding. The evaluator is passed as a Var
  so a test may observe calls and still execute the actual source."
  ([body] (with-connection #'fixture-evaluate body))
  ([evaluator body]
  (test-support/with-database
    (fn [connection]
      (let [ctx (test-support/fork-cluster-ctx connection)
            launcher
            (seon.flow/start-work-launcher!
             {:seon.env/environment @test-environment
              ::seon.flow/configuration
              (assoc (select-keys (test-support/effective-config)
                                  seon.flow/flow-workload-attributes)
                     :seon.config.flow.compute/queue-depth 10
                     :seon.config.flow.compute/concurrency 3
                     :seon.config.flow.io/queue-depth 2
                     :seon.config.flow.io/concurrency 2)})
            context-channel
            (test-support/render-context-channel
             (render/agent-render-profile (config/defaults)))
            stream-channel (async/chan (async/sliding-buffer 1))
            render-channel (async/chan (async/sliding-buffer 1))
            runtime-eval-channel (async/chan (async/sliding-buffer 1))
            pages-channel (async/chan (async/sliding-buffer 1))
            completion (async/promise-chan)
            graph
            (flow/create-flow
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
                  :seon.render.web/root-agent-id "root"
                  :seon.turn.loop/cluster
                  {:seon.db/connection connection
                   :seon.turn.loop/stream-channel stream-channel
                   :seon.sci.admit/caps
                   (assoc
                    (config/result-caps
                     (test-support/effective-config))
                    :seon.config.eval.result/max-depth 6
                    :seon.config.eval.result/max-collection 8
                    :seon.config.eval.result/max-string 4096
                    :seon.config.eval.result/max-nodes 256)
                   :seon.sci.eval/ctx ctx
                   :seon.config.eval/time-limit-ms
                   @shipped-eval-time-limit-ms
                   :seon.config/on-core-error :panic
                  :seon.db.process/id process}})}}
              :conns []
              :io-exec
              (cluster/projection-executor
               (:seon.sci.eval/projection-state ctx))})
            {:keys [report-chan error-chan]} (flow/start graph)]
        (async/go-loop [] (when (async/<! report-chan) (recur)))
        (async/go-loop [] (when (async/<! error-chan) (recur)))
        (try
          (flow/resume graph)
          ;; THE EVALUATOR IS A VAR, NOT A CONFIG FACT. `evaluate-sources`
          ;; calls `seon.sci.eval/evaluate` directly so the program graph
          ;; carries the edge; a stand-in is installed by replacing that
          ;; Var's root value, which every proc thread sees.
          (let [armed-evaluate sci.eval/evaluate
                selected-evaluate (if (identical? evaluator real-evaluate)
                                    armed-evaluate evaluator)]
           (with-redefs [real-evaluate armed-evaluate
                         sci.eval/evaluate selected-evaluate]
            (binding [*work-launcher* launcher
                      *context-channel* context-channel
                      *stream-channel* stream-channel]
              (body connection ctx))))
          (finally
            (flow/stop graph)
            (test-support/await-event! completion ::render-stopped)
            (seon.flow/stop-work-launcher! launcher))))))))

(defn- handle
  "The cluster handle `arm!` declares, from `test-support/cluster-handle`.

  Every structural member and dial the declaration names is defaulted there
  from the shipped decisions; this adds only what this suite's world supplies
  or varies."
  [connection ctx]
  (test-support/cluster-handle
  {:seon.env/environment @test-environment
   :seon.db/connection connection
   :seon.cluster/name
   (db/q '[:find ?cluster . :where [_ :seon.config/cluster ?cluster]]
        @connection)
   :seon.flow/work-launcher *work-launcher*
   :seon.flow/executor
   (cluster/projection-executor
    (:seon.sci.eval/projection-state ctx))
   :seon.sci.eval/ctx ctx
   :seon.render/context-channel *context-channel*
   :seon.turn.loop/stream-channel *stream-channel*
   :seon.db.process/id process
   ;; replaced per agent by arm! — present so the handle validates
   :seon.cluster.wake/channel (async/chan (async/sliding-buffer 1))
   :seon.turn.loop/completion (async/promise-chan)
   :seon.sci.admit/caps
   (assoc (config/result-caps (test-support/effective-config))
          :seon.config.eval.result/max-depth 6
          :seon.config.eval.result/max-collection 8
          :seon.config.eval.result/max-string 4096
          :seon.config.eval.result/max-nodes 256)
   :seon.config.eval/time-limit-ms @shipped-eval-time-limit-ms
   :seon.config.agent/turn-completion-backstop-ms
   (:seon.config.agent/turn-completion-backstop-ms (config/defaults))
   :seon.config/on-core-error :panic
   :seon.config.error/recurrence-limit 3
   :seon.config.message/max-chain 16}))

(defn- config-row
  [cluster-name overlay]
  (:seon.config/desired-row
   (config/compile-manifest {:seon.boot/cluster-name cluster-name
                             :seon.config/manifest overlay})))

(defn- armory
  "A routing entry with a test fault channel already joined."
  []
  (let [routing (agent/routing)]
    (swap! routing assoc :seon.cluster.agent/fault-channel
           (async/chan (async/sliding-buffer 16)))
    routing))

(defn- arm-one!
  [connection ctx routing agent-id]
  (agent/arm! {:seon.turn.loop/cluster (handle connection ctx)
               :seon.cluster.agent/id agent-id
               :seon.cluster.agent/routing routing}))

(defn- agent-row
  [agent-id]
  {:seon.cluster.agent/id agent-id
   :seon.cluster.agent/namespace
   {:seon.ns/name (symbol (str "my.agents." agent-id))}})

(defn- create-generated-agent!
  [connection cluster-name agent-id]
  (cluster/ensure-entity!
   connection process
   {:seon.cluster.agent/id agent-id
    :seon.cluster/name cluster-name
    :seon.ns/name (symbol (str "my.agents." agent-id))}))

(defn- disarm-all!
  [routing]
  (doseq [agent-id (sort (keys (:seon.cluster.agent/armed @routing)))]
    (agent/disarm! {:seon.cluster.agent/id agent-id
                    :seon.cluster.agent/routing routing})))

(defn- await-until
  "Await a probe through the shared loud test-event backstop."
  [probe]
  (test-support/await-event!
   (future
     (loop []
       (or (probe)
           (do
             (Thread/sleep 25)
             (recur)))))
   ::probe-satisfied))

(defn- await-database-state!
  "Return the first observed database value satisfying `accept?`.

  The caller registers `event-source` before its initial derivation. A commit
  between the connection read and the channel take is therefore queued. The
  shared test-event backstop fails loudly when the exact state never lands."
  [connection event-source accept?]
  (let [database @connection]
    (if (accept? database)
      database
      (try
        (:db-after
         (test-support/await-event!
          event-source ::database-state #(accept? (:db-after %))))
        (catch Throwable failure
          (throw (ex-info "The expected terminal database fact did not arrive."
                          {:seon.test/turns
                           (db/q '[:find [(pull ?turn [:seon.turn/id :seon.turn/closed-at
                                                     :seon.turn/reply]) ...]
                                   :where [?turn :seon.turn/id]] @connection)
                           :seon.test/evaluations
                           (db/q '[:find [(pull ?e [:seon.cluster.eval/source
                                                  :seon.cluster.eval/error
                                                  :seon.eval/value]) ...]
                                   :where [?e :seon.cluster.eval/id]] @connection)}
                          failure)))))))

(defn- terminal-receipt-count
  [database]
  (or (db/q '[:find (count ?receipt) .
              :where [?receipt :seon.eval/value _]]
            database)
      0))

(defn- turn-ping
  [entry]
  (flow/ping-proc (:seon.flow/graph entry) ::agent/turn))

(defn- mailbox-ping
  [entry]
  (:clojure.core.async.flow/state
   (flow/ping-proc (:seon.flow/graph entry) ::agent/mailbox)))

(defn- recording-completer
  "A stub `ai/complete`: the ledger of calls is the countable oracle."
  [ledger text-fn]
  (fn [request]
    (swap! ledger conj request)
    {:seon.ai/text (text-fn request)}))

(defn- outside-trigger!
  [connection agent-id message-id content]
  (db/transact! connection
              [{:seon.cluster.message/id message-id
                :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
                :seon.cluster.message/content content
                :seon.cluster.message/at (Date.)}]))

(defn- agent-trigger!
  [connection from-id to-id message-id content]
  (db/transact! connection
              [{:seon.cluster.message/id message-id
                :seon.cluster.message/to [:seon.cluster.agent/id to-id]
                :seon.cluster.message/from [:seon.cluster.agent/id from-id]
                :seon.cluster.message/content content
                :seon.cluster.message/at (Date.)}]))

(defn- open-runs
  [db]
  (db/q '[:find [?id ...]
         :where
         [?run :seon.turn/id ?id]
         (not [?run :seon.turn/closed-at _])]
       db))

(defn- answers-by-trigger
  "Message id to the number of reply-bearing turns recording it as provenance."
  [db]
  (into {}
        (db/q '[:find ?message-id (count ?run)
               :where
               [?run :seon.turn/trigger ?message]
               [?run :seon.turn/reply]
               [?message :seon.cluster.message/id ?message-id]]
             db)))

(defn- quiescent?
  [db agent-ids]
  (and (every? #(empty? (turn/unanswered-triggers db %)) agent-ids)
       (empty? (open-runs db))))

(defn- database-events
  ([connection]
   (database-events connection (random-uuid)))
  ([connection listener-key]
   (let [events (async/chan 64)]
     (d/listen connection listener-key #(async/put! events %))
     {:seon.cluster.agent-test/events events
      :seon.cluster.agent-test/listener-key listener-key})))

(defn- stop-database-events!
  [connection event-source]
  (d/unlisten connection
              (:seon.cluster.agent-test/listener-key event-source))
  (async/close! (:seon.cluster.agent-test/events event-source)))

(deftest a-function-without-a-contract-never-enters-the-program
  (with-connection real-evaluate
    (fn [connection ctx]
      (let [routing (armory)
            events (database-events connection)
            source "(defn uncontracted [] 42)"
            function-symbol 'my.agents.contract-probe/uncontracted]
        (db/transact! connection [(config-row "contract-probe" {}) (agent-row "contract-probe")])
        (try
          (arm-one! connection ctx routing "contract-probe")
          (let [submission
                (agent/submit-source!
                 {:seon.turn.loop/cluster (handle connection ctx)
                  :seon.cluster.agent/routing routing
                  :seon.cluster.agent/id "contract-probe"
                  :seon.cluster.reply/text
                  (str source "\n(seon.run/complete \"Checked.\")")})
                run-id (:seon.turn/id submission)
                database
                (await-database-state!
                 connection (:seon.cluster.agent-test/events events)
                 #(some? (:seon.turn/closed-at
                          (db/pull % [:seon.turn/closed-at]
                                   [:seon.turn/id run-id]))))
                evaluation
                (db/q '[:find (pull ?evaluation [*]) .
                        :in $ ?run-id
                        :where [?run :seon.turn/id ?run-id]
                        [?evaluation :seon.cluster.eval/run ?run]
                        [?evaluation :seon.cluster.eval/ordinal 0]]
                      database run-id)]
            (is (string? run-id))
            (is (= source (:seon.cluster.eval/source evaluation)))
            (is (nil? (db/entity database [:seon.fn/sym (str function-symbol)])))
            (is (nil? (sci/resolve ctx function-symbol)))
            (is (str/includes?
                 (repl/response (assoc evaluation :seon.ns/name
                                       'my.agents.contract-probe))
                 "uncontracted was not installed: every function needs a :malli/schema contract to become part of the program.")))
          (finally
            (stop-database-events! connection events)
            (disarm-all! routing)))))))

(deftest system-source-submission-uses-the-ordinary-durable-run
  (with-connection real-evaluate
    (fn [connection ctx]
      (let [routing (armory)
            armer-channel (async/chan (async/sliding-buffer 1))
            armer-completion (async/promise-chan)
            ;; A HANDLE IS THE STORED EVALUATION'S OWN ENTITY ID, which does
            ;; not exist when this source is authored: an agent names an
            ;; earlier value from the handle its context showed it, never from
            ;; an ordinal it can predict. This run proves the durable path.
            text (str "; Read one value.\n(+ 1 1)\n"
                      "; Read a second value.\n(identity (+ 1 1))")]
        (db/transact!
         connection
         [(agent-row "source-agent")
          (config-row "source-submission"
                      {:seon.config.run/max-episode-runs 100})])
        (let [cluster-handle
              (assoc (handle connection ctx)
                     :seon.cluster.wake/channel armer-channel
                     :seon.turn.loop/completion armer-completion)
              armer-graph
              (flow/create-flow
               {:procs
                {::agent/armer
                 {:proc (flow/process #'agent/armer-step {:workload :io})
                  :args {:seon.turn.loop/cluster cluster-handle
                         :seon.cluster.agent/routing routing}}}
                :conns []
                :io-exec (cluster/projection-executor
                          (:seon.sci.eval/projection-state ctx))})]
          (db/transact!
           connection
           [{:seon.ns/name 'my.agents.source-agent-moved}
            {:seon.cluster.agent/id "source-agent"
             :seon.cluster.agent/namespace
             [:seon.ns/name 'my.agents.source-agent-moved]}])
          (let [refusal
                (agent/submit-source!
                 {:seon.turn.loop/cluster cluster-handle
                  :seon.cluster.agent/routing routing
                  :seon.cluster.agent/id "source-agent"
                  :seon.turn/starting-ns
                  [:seon.ns/name 'my.agents.source-agent]
                  :seon.cluster.reply/text text})]
            (is (= :seon.turn/starting-namespace-changed (:seon.turn/rule refusal))
                "the rendered namespace wins even when assignment changed before submission")
            (is (nil? (:seon.turn/id refusal)))
            (is (nil? (async/poll! armer-channel))
                "a refused submission publishes no wake")
            (is (empty? (db/q '[:find [?run ...]
                               :where [?run :seon.turn/id]]
                             @connection))
                "the writer rolls back the complete source run"))
          (flow/start armer-graph)
          (flow/resume armer-graph)
          (try
            (let [events (database-events connection)]
              (try
                (is (nil? (agent/armed routing "source-agent")))
                (let [submission
                      (agent/submit-source!
                       {:seon.turn.loop/cluster cluster-handle
                        :seon.cluster.agent/routing routing
                        :seon.cluster.agent/id "source-agent"
                        :seon.cluster.reply/text text})
                      run-id (:seon.turn/id submission)
                      terminal-db
                      (await-database-state!
                       connection
                       (:seon.cluster.agent-test/events events)
                       #(some?
                         (:seon.turn/closed-at
                          (db/pull % [:seon.turn/closed-at]
                                   [:seon.turn/id run-id]))))
                      sources
                      (db/q '[:find ?ordinal ?source
                              :keys seon.cluster.eval/ordinal
                                    seon.cluster.eval/source
                              :in $ ?run-id
                              :where
                              [?run :seon.turn/id ?run-id]
                              [?form :seon.cluster.eval/run ?run]
                              [?form :seon.cluster.eval/ordinal ?ordinal]
                              [?form :seon.cluster.eval/source ?source]]
                            terminal-db run-id)
                      results
                      (db/q '[:find ?ordinal ?result
                              :in $ ?run-id
                              :where
                              [?run :seon.turn/id ?run-id]
                              [?evaluation :seon.cluster.eval/run ?run]
                              [?evaluation :seon.cluster.eval/ordinal ?ordinal]
                              [?evaluation :seon.eval/value ?result]]
                            terminal-db run-id)]
                  (is (string? run-id))
                  (is (= text
                         (:seon.turn/reply
                          (db/pull terminal-db [:seon.turn/reply]
                                   [:seon.turn/id run-id])))
                      "the run retains exact submitted source, not reconstructed forms")
                  (is (some? (agent/armed routing "source-agent"))
                      "the existing armer owns unarmed delivery")
                  ;; THE COMMENT IS ITS OWN FACT beside the form it
                  ;; introduces, so a prompt line holds exactly one form.
                  (is (= [{:seon.cluster.eval/ordinal 0
                           :seon.cluster.eval/source "(+ 1 1)"}
                          {:seon.cluster.eval/ordinal 1
                           :seon.cluster.eval/source "(identity (+ 1 1))"}]
                         (sort-by :seon.cluster.eval/ordinal sources))
                      "exact forms pass through the ordinary parser")
                  (is (= ["; Read one value." "; Read a second value."]
                         (mapv second
                               (sort-by
                                first
                                (db/q '[:find ?ordinal ?comment
                                        :in $ ?run-id
                                        :where
                                        [?run :seon.turn/id ?run-id]
                                        [?evaluation :seon.cluster.eval/run ?run]
                                        [?evaluation :seon.cluster.eval/ordinal ?ordinal]
                                        [?evaluation :seon.cluster.eval/comment ?comment]]
                                      terminal-db run-id))))
                      "and each agent comment is stored beside its own form")
                  (is (= [[0 "2"] [1 "2"]]
                         (vec (sort-by first results)))
                      "both ordinary evaluation results settle durably")
                  (is (inst? (:seon.turn/closed-at
                              (db/pull terminal-db
                                       [:seon.turn/closed-at]
                                       [:seon.turn/id run-id]))))
                  (is (empty?
                       (db/q '[:find [?attempt ...]
                               :in $ ?run-id
                               :where
                               [?run :seon.turn/id ?run-id]
                               [?run :seon.turn/attempts ?attempt]]
                             terminal-db run-id))
                      "the system-authored run makes no provider attempt"))
                (finally
                  (stop-database-events! connection events))))
            (finally
              (flow/stop armer-graph)
              (test-support/await-event! armer-completion ::armer-stopped)
              (disarm-all! routing)
              (async/close! armer-channel)))
          (db/transact!
           connection
           (turn/open-tx {:seon.turn/id "already-open"
                         :seon.turn/agent
                         [:seon.cluster.agent/id "source-agent"]
                         :seon.turn/opened-at now}))
          (let [refusal
                (agent/submit-source!
                 {:seon.turn.loop/cluster cluster-handle
                  :seon.cluster.agent/routing routing
                  :seon.cluster.agent/id "source-agent"
                  :seon.cluster.reply/text "(+ 2 2)"})]
            (is (= :seon.turn/agent-already-running (:seon.turn/rule refusal))
                "the transaction authority refuses a second open run")))))))

(deftest graph-definition-inherits-the-cluster-io-executor
  (with-connection
    (fn [connection ctx]
      (let [_ (db/transact! connection [(config-row "executor-proof" {})])
            executor (reify Executor (execute [_ _]))
            definition
            (agent/graph-definition
             {:seon.turn.loop/cluster
              (assoc (handle connection ctx) :seon.flow/executor executor)
              :seon.cluster.agent/id "executor-proof"})]
        (is (identical? executor (:io-exec definition)))))))

(deftest prompt-refusal-closes-without-answering-and-stops-at-the-agent-bound
  (with-connection
    (fn [connection ctx]
      (let [routing (armory)
            requests (atom [])
            events (database-events connection)]
        (db/transact!
         connection
         [(assoc (agent-row "prompt-refusal-cap")
                 :seon.agent/settings {:seon.config.run/max-episode-runs 1})
          (config-row "prompt-refusal-cap" {})])
        (try
          (with-redefs [prompt/prompt
                        (fn [& _]
                          (throw (ex-info "Prompt rendering refused."
                                          {:seon.error/kind :seon.cluster.prompt/refused
                                           :seon.cluster.prompt/refused
                                           :seon.cluster.prompt/missing-input
                                           :seon.error/message "Prompt rendering refused."})))
                        ai/complete
                        (recording-completer requests (constantly "unused"))]
            (let [entry (arm-one! connection ctx routing "prompt-refusal-cap")]
              (outside-trigger! connection "prompt-refusal-cap"
                                "prompt-refusal-message" "derive context")
              (async/offer! (:seon.cluster.wake/channel entry) ::wake)
              (let [database
                    (await-database-state!
                     connection (:seon.cluster.agent-test/events events)
                     #(and (= 1 (turn/episode-runs % "prompt-refusal-cap"))
                           (empty? (open-runs %))))]
                (is (empty? @requests))
                (is (= 1 (db/q '[:find (count ?error) . :where
                                  [?error :seon.error/id]
                                  [?error :seon.error/kind :seon.cluster.prompt/refused]]
                                database)))
                (is (seq (turn/unanswered-triggers database "prompt-refusal-cap"))
                    "a refused prompt has not observed the wake")
                (is (nil? (turn/next-agent-work
                           database {:seon.cluster.agent/id "prompt-refusal-cap"})))
                (is (false? (turn/more-agent-work?
                             database {:seon.cluster.agent/id "prompt-refusal-cap"}))))))
          (finally
            (stop-database-events! connection events)
            (disarm-all! routing)))))))

(defn- parallel-trial
  "One trial: N agents × outside triggers through concurrent per-agent
  graphs. Returns a map of named booleans so a shrunk counterexample
  says WHICH oracle broke."
  [n-agents triggers-per-agent]
  (with-connection
    (fn [connection ctx]
      (let [agent-ids (mapv #(str "agent-" %) (range n-agents))
            triggers (vec (for [[index agent-id]
                                (map-indexed vector agent-ids)
                                k (range (nth triggers-per-agent index))]
                            [agent-id (str "m-" agent-id "-" k)]))
            routing (armory)
            armer-channel (async/chan (async/sliding-buffer 1))
            ledger (atom [])]
        (db/transact! connection
                    (into [(config-row
                            "trial"
                            {:seon.config.run/max-episode-runs 100})]
                          (map agent-row)
                          agent-ids))
        (with-redefs [ai/complete
                      (recording-completer
                       ledger (fn [_] "(seon.run/complete \"done\")"))]
          (try
            (doseq [agent-id agent-ids]
              (arm-one! connection ctx routing agent-id))
            (wake/route! {:seon.cluster.wake/connection connection
                          :seon.cluster.wake/channels
                          (fn [] (agent/channels routing))
                          :seon.cluster.wake/fenced?
                          (fn [agent-eid channel]
                            (agent/fenced-route? routing agent-eid channel))
                          :seon.cluster.wake/armer-channel armer-channel
                          :seon.cluster.wake/render-channel
                          (async/chan (async/sliding-buffer 1))
                          :seon.render.web/interest (atom :all)
                          :seon.cluster.wake/fault-channel
                          (:seon.cluster.agent/fault-channel @routing)
                          :seon.cluster.wake/key ::route})
            (let [events (database-events connection ::parallel-turns)]
              (try
                ;; Every commit below is delivered by the routing listener,
                ;; concurrently across the agents' independent graphs. The
                ;; terminal transaction is the observable completion event;
                ;; Datahike's one writer may legitimately serialize several
                ;; agents beyond a polling window under suite load.
                (doseq [[agent-id message-id] triggers]
                  (outside-trigger! connection agent-id message-id "work"))
                (let [terminal
                      (await-database-state!
                       connection
                       (:seon.cluster.agent-test/events events)
                       #(and (seq (answers-by-trigger %))
                             (quiescent? % agent-ids)))
                      db terminal
                      answers (answers-by-trigger db)
                      run-count (or (db/q '[:find (count ?run) . :where
                                           [?run :seon.turn/id _]]
                                         db)
                                    0)
                      duplicate-receipts
                      (->> (db/q '[:find ?run ?ordinal (count ?receipt)
                                  :where
                                  [?receipt :seon.cluster.eval/run ?run]
                                  [?receipt :seon.cluster.eval/ordinal
                                   ?ordinal]]
                                db)
                           (remove (fn [[_ _ n]] (= 1 n))))
                      ;; the per-agent serial oracle's outcome, computed
                      ;; from the generated spec: pending wakes may coalesce;
                      ;; each agent closes at least one turn, at most one per wake.
                      per-agent-serial?
                      (every? (fn [[index agent-id]]
                                (<= 1
                                    (or (db/q '[:find (count ?run) .
                                              :in $ ?agent-id
                                              :where
                                              [?agent :seon.cluster.agent/id
                                               ?agent-id]
                                              [?run :seon.turn/agent
                                               ?agent]
                                              [?run :seon.turn/closed-at
                                               _]]
                                            db agent-id)
                                        0)
                                    (nth triggers-per-agent index)))
                              (map-indexed vector agent-ids))]
                  {:settled? true
                   :answered-once?
                   (and (<= (count agent-ids) (count answers) (count triggers))
                        (every? #(= 1 (val %)) answers)
                        (every? #(empty? (turn/unanswered-triggers db %)) agent-ids))
                   :ledger-equals-runs? (= (count @ledger) run-count)
                   :receipts-unique? (empty? duplicate-receipts)
                   :fences-quiet?
                   (empty? (db/q '[:find ?error :where
                                  [?error :seon.error/id _]]
                                db))
                   :per-agent-serial? per-agent-serial?})
                (finally
                  (stop-database-events! connection events))))
            (finally
              (wake/unlisten! {:seon.cluster.wake/connection connection
                               :seon.cluster.wake/key ::route})
              (disarm-all! routing))))))))

(deftest n-agent-parallel-turns-property
  (let [result
        (tc/quick-check
         12
         (prop/for-all
          [n-agents (gen/choose 1 3)
           counts (gen/vector (gen/choose 1 2) 3)]
          (let [verdict (parallel-trial n-agents counts)]
            (every? val verdict)))
         :seed 2026072811)]
    (is (:pass? result)
        (str "shrunk counterexample: " (pr-str (:shrunk result))))))

(deftest fenced-is-the-derived-quarantine-state
  (with-connection
    (fn [connection ctx]
      (let [routing (armory)]
        (db/transact! connection [(config-row "agent-a" {}) {:seon.cluster.agent/id "agent-a"}])
        (try
          (is (false? (agent/fenced? routing "agent-a"))
              "an unarmed agent has no fence")
          (let [entry (arm-one! connection ctx routing "agent-a")
                eid (:seon.cluster.agent/eid entry)
                mailbox (:seon.cluster.wake/channel entry)
                stale (async/chan)]
            (is (false? (agent/fenced? routing "agent-a")))
            (is (false? (agent/fenced-route? routing eid mailbox)))
            (async/close! mailbox)
            (is (true? (agent/fenced? routing "agent-a"))
                "armed + closed in place is the management view")
            (is (true? (agent/fenced-route? routing eid mailbox))
                "the exact current route is recognizable by the router")
            (async/close! stale)
            (is (false? (agent/fenced-route? routing eid stale))
                "closedness alone does not bless a stale route"))
          (finally
            (disarm-all! routing)))
        (is (false? (agent/fenced? routing "agent-a"))
            "ordinary teardown drops the entry before close")))))

(deftest disarm-drops-the-route-before-closing-it
  (with-connection
    (fn [connection ctx]
      (let [routing (armory)
            _ (db/transact! connection [(config-row "agent-a" {}) {:seon.cluster.agent/id "agent-a"}])
            entry (arm-one! connection ctx routing "agent-a")
            eid (:seon.cluster.agent/eid entry)
            channel (:seon.cluster.wake/channel entry)
            observed (atom nil)
            real-close async/close!]
        (with-redefs [async/close!
                      (fn [candidate]
                        (when (identical? candidate channel)
                          (reset! observed
                                  {:routed?
                                   (contains? (agent/channels routing) eid)
                                   :armed?
                                   (some? (agent/armed routing "agent-a"))}))
                        (real-close candidate))]
          (agent/disarm! {:seon.cluster.agent/id "agent-a"
                          :seon.cluster.agent/routing routing}))
        (is (= {:routed? false :armed? false} @observed)
            "the route is already absent when orderly teardown closes")))))

(defn- withheld-turn-trial
  [connection ctx routing original-definition agent-id]
  (let [tasks (atom [])
        executor
        (reify Executor
          (execute [_ task]
            (swap! tasks conj task)))
        take-result (async/promise-chan)]
    (with-redefs
      [agent/graph-definition
       (fn [request]
         (let [definition (original-definition request)]
           (assoc definition
                  :io-exec executor
                  :procs (select-keys (:procs definition) [::agent/turn])
                  :conns [])))]
      (let [entry (arm-one! connection ctx routing agent-id)
            completion (:seon.turn.loop/completion entry)
            observed-completion
            (reify
              async.impl/ReadPort
              (take! [_ handler]
                (let [result (async.impl/take! completion handler)]
                  (async/put! take-result (some? result))
                  result))

              async.impl/WritePort
              (put! [_ value handler]
                (async.impl/put! completion value handler))

              async.impl/Channel
              (close! [_]
                (async.impl/close! completion))
              (closed? [_]
                (async.impl/closed? completion)))
            _ (swap! routing assoc-in
                     [::agent/armed agent-id :seon.turn.loop/completion]
                     observed-completion)
            stopped
            (future
              (agent/disarm! {:seon.cluster.agent/id agent-id
                              :seon.cluster.agent/routing routing}))]
        (try
          {:seon.cluster.agent-test/runnable-count (count @tasks)
           :seon.cluster.agent-test/completion-ready?
           (test-support/await-event!
            take-result ::parked-turn-completion-ready)}
          (finally
            (doseq [^Runnable task @tasks]
              (.run task))
            (test-support/await-event! stopped ::withheld-turn-disarmed)))))))

(deftest disarm-does-not-depend-on-the-turn-proc-starting
  (with-connection
    (fn [connection ctx]
      (let [routing (armory)
            agent-ids (mapv #(str "withheld-turn-" %) (range 100))
            original-definition agent/graph-definition]
        (db/transact! connection
                    (into [(config-row "withheld-turn" {})]
                          (map (fn [agent-id]
                                 {:seon.cluster.agent/id agent-id}))
                          agent-ids))
        (let [results
              (mapv #(withheld-turn-trial
                      connection ctx routing original-definition %)
                    agent-ids)
              ready-count
              (count (filter :seon.cluster.agent-test/completion-ready?
                             results))]
          (is (every? #(= 1 (:seon.cluster.agent-test/runnable-count %))
                      results)
              "Flow accepted every turn runnable without starting it")
          (is (= 100 ready-count)
              (str "arming published parked completion in " ready-count
                   "/100 controlled stop interleavings")))))))

(deftest disarm-has-a-declared-loud-turn-completion-backstop
  (with-connection
    (fn [connection ctx]
      (let [routing (armory)
            turn-completion-backstop-ms 100
            provider-entered (CountDownLatch. 1)
            release-provider (CountDownLatch. 1)
            server (ServerSocket. 0)
            server-finished
            (future
              (with-open [_peer (.accept server)]
                (test-support/await-event!
                 release-provider
                 ::release-never-answering-provider)))
            agent-id "provider-backstop"]
        (db/transact!
         connection
         [{:seon.cluster.agent/id agent-id}
          (config-row
           "provider-backstop"
           {:seon.config.agent/turn-completion-backstop-ms
            turn-completion-backstop-ms
            :seon.config.ai/timeout-ms 30000
            :seon.config.ai.retry/maximum-retries 0
            :seon.config.ai.retry/maximum-total-delay-ms 0
            :seon.config.run/max-episode-runs 1})])
        (try
          (with-redefs
            [ai/complete
             (fn [_request]
               (with-open [client (Socket. "127.0.0.1"
                                           (.getLocalPort server))]
                 (.countDown provider-entered)
                 (.read (.getInputStream client))
                 {:seon.error/kind ::provider-released
                  :seon.error/message "The local provider released."}))]
            (let [entry (arm-one! connection ctx routing agent-id)
                  fault-channel
                  (:seon.cluster.agent/fault-channel @routing)]
              (outside-trigger! connection agent-id
                                "provider-backstop-message" "block")
              (async/offer! (:seon.cluster.wake/channel entry) ::wake)
              (test-support/await-event!
               provider-entered
               ::never-answering-provider-entered)
              (let [run-id
                    (db/q '[:find ?run-id .
                            :in $ ?agent-id
                            :where
                            [?agent :seon.cluster.agent/id ?agent-id]
                            [?run :seon.turn/agent ?agent]
                            [?run :seon.turn/id ?run-id]
                            (not [?run :seon.turn/closed-at])]
                          @connection agent-id)
                    started-at (System/nanoTime)
                    stopped
                    (future
                      (try
                        (agent/disarm!
                         {:seon.cluster.agent/id agent-id
                          :seon.cluster.agent/routing routing})
                        ::unexpected-orderly-stop
                        (catch clojure.lang.ExceptionInfo failure
                          failure)))
                    failure
                    (test-support/await-event!
                     stopped
                     ::declared-turn-completion-backstop)
                    elapsed-ms
                    (/ (- (System/nanoTime) started-at) 1000000.0)
                    fault
                    (test-support/await-event!
                     fault-channel
                     ::provider-stop-core-fault)]
                (is (= ::agent/turn-completion-backstop
                       (:seon.error/kind (ex-data failure))))
                (is (= turn-completion-backstop-ms
                       (:seon.config.agent/turn-completion-backstop-ms
                        (ex-data failure))))
                (is (= agent-id
                       (:seon.cluster.agent/id (ex-data failure))))
                (is (= run-id
                       (:seon.turn/id (ex-data failure))))
                (is (< elapsed-ms 1000.0)
                    (str "the 100 ms declared bound returned in "
                         elapsed-ms " ms"))
                (is (= failure (::flow/ex fault)))
                (is (= agent-id (:seon.cluster.agent/id fault)))
                (is (= run-id (:seon.turn/id fault)))
                (is (some? (agent/armed routing agent-id))
                    "a fired backstop fails closed and leaves stop retryable"))
              (.countDown release-provider)
              (test-support/await-event!
               server-finished
               ::never-answering-provider-released)
              (test-support/await-event!
               (:seon.cluster.agent/turn-stopped entry)
               ::released-provider-turn-stopped)
              (agent/disarm! {:seon.cluster.agent/id agent-id
                              :seon.cluster.agent/routing routing})
              (is (nil? (agent/armed routing agent-id)))
              (is (not (contains? (agent/channels routing)
                                  (:seon.cluster.agent/eid entry))))
              (is (async.impl/closed?
                   (:seon.cluster.wake/channel entry)))
              (is (async.impl/closed?
                   (:seon.turn.loop/completion entry)))
              (is (nil? (agent/disarm!
                         {:seon.cluster.agent/id agent-id
                          :seon.cluster.agent/routing routing})))
              (is (nil? (agent/armed routing agent-id))
                  "a successful retry removes the route exactly once")))
          (finally
            (.countDown release-provider)
            (.close server)
            (disarm-all! routing)))))))

(deftest turn-start-has-the-same-declared-loud-completion-backstop
  (with-connection
    (fn [connection ctx]
      (let [agent-id "lost-turn-permit"
            timeout-ms 100]
        (db/transact!
         connection
         [{:seon.cluster.agent/id agent-id}
          (config-row
           "lost-turn-permit"
           {:seon.config.agent/turn-completion-backstop-ms timeout-ms})])
        (let [cluster (assoc (handle connection ctx)
                             :seon.turn.loop/completion (async/chan)
                             :seon.config.agent/turn-completion-backstop-ms
                             timeout-ms)
              started-at (System/nanoTime)
              failure
              (try
                (seon.turn/step
                 {:seon.cluster.agent/id agent-id
                  :seon.turn.loop/cluster cluster}
                 ::agent/episode ::wake)
                nil
                (catch clojure.lang.ExceptionInfo caught caught))
              elapsed-ms (/ (- (System/nanoTime) started-at) 1000000.0)]
          (is (= ::agent/turn-completion-backstop
                 (:seon.error/kind (ex-data failure))))
          (is (= ::agent/turn-start
                 (get-in (ex-data failure)
                         [:seon.error/data
                          :seon.error/diagnostic-operation])))
          (is (= agent-id (:seon.cluster.agent/id (ex-data failure))))
          (is (= timeout-ms
                 (:seon.config.agent/turn-completion-backstop-ms
                  (ex-data failure))))
          (is (< elapsed-ms 1000.0)
              (str "the lost permit failed within its declared 100 ms bound in "
                   elapsed-ms " ms")))))))

(deftest failed-turn-transform-keeps-a-live-backstop-after-releasing-its-permit
  (with-connection
    (fn [connection ctx]
      (let [agent-id "quiescent-failed-transform"
            timeout-ms 100
            completion (async/chan 1)
            fault-channel (async/chan 1)
            cluster (assoc (handle connection ctx)
                           :seon.turn.loop/completion completion
                           :seon.cluster.agent/fault-channel fault-channel
                           :seon.cluster.agent/turn-backstop-state (atom nil)
                           :seon.config.agent/turn-completion-backstop-ms
                           timeout-ms)]
        (db/transact!
         connection
         [{:seon.cluster.agent/id agent-id}
          (config-row
           "quiescent-failed-transform"
           {:seon.config.agent/turn-completion-backstop-ms timeout-ms})])
        (async/>!! completion ::ready)
        (let [escaped
              (with-redefs [
                            turn/next-agent-work
                            (fn [& _]
                              {:seon.turn.work/situation :resume
                               :seon.cluster.agent/id agent-id
                               :seon.turn/id "failed-transform-run"})
                            turn/turn
                            (fn [& _]
                              (throw
                               (ex-info "turn transform escaped"
                                        {:seon.test/turn-escaped true})))]
                (try
                  (seon.turn/step
                   {:seon.cluster.agent/id agent-id
                    :seon.turn.loop/cluster cluster}
                   ::agent/episode ::wake)
                  nil
                  (catch clojure.lang.ExceptionInfo failure failure)))
              _ (is (= "turn transform escaped" (ex-message escaped)))
              _ (is (= ::agent/ready (async/poll! completion))
                    "the failed transform released its lifecycle permit")
              fault
              (test-support/await-event!
               fault-channel ::quiescent-transform-backstop)]
          (is (= ::agent/turn-completion-backstop
                 (:seon.error/kind (ex-data (::flow/ex fault)))))
          (is (= ::agent/turn-transform
                 (get-in (ex-data (::flow/ex fault))
                         [:seon.error/data
                          :seon.error/diagnostic-operation])))
          (is (= agent-id (:seon.cluster.agent/id fault))))))))

(deftest install-gate-core-fault-reaches-flow-with-a-live-backstop
  (with-connection
    (fn [connection ctx]
      (let [routing (armory)
            agent-id "install-gate-chain"
            run-id "install-gate-chain-run"
            message-id "install-gate-chain-message"
            namespace-name 'my.agents.install-gate-chain
            timeout-ms (:seon.config.agent/turn-completion-backstop-ms
                        (config/defaults))
            gate-var (ns-resolve 'seon.turn 'gate-function-install)
            events (database-events connection)]
        (db/transact!
         connection
         [(agent-row agent-id)
          (config-row
           "install-gate-chain"
           {:seon.config.agent/turn-completion-backstop-ms timeout-ms})
          {:seon.cluster.message/id message-id
           :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
           :seon.cluster.message/content "finish generated opening"
           :seon.cluster.message/at now}])
        (db/transact!
         connection
         (turn/open-tx
          {:seon.turn/id run-id
           :seon.turn/agent [:seon.cluster.agent/id agent-id]
           :seon.turn/trigger
           [:seon.cluster.message/id message-id]
           :seon.turn/opened-at now}))

        (db/transact!
         connection
         (turn/plan-tx
          {:seon.turn/id run-id
           :seon.db.process/id process
           :seon.turn/starting-ns [:seon.ns/name namespace-name]
           :seon.turn/plan-digest (apply str (repeat 64 "a"))
           :seon.turn/sources
           [{:seon.cluster.eval/source
             "(defn ^{:malli/schema [:=> [:cat] :int]} gate-chain [] 1)"
             :seon.ns/name namespace-name}]}))
        (try
          (with-redefs-fn
            {gate-var (fn [& _]
                        (throw (ex-info "install gate broke mid-opening"
                                        {:seon.test/install-gate-broke true})))}
            (fn []
              (let [entry (arm-one! connection ctx routing agent-id)
                    fault (test-support/await-event!
                           (:seon.cluster.agent/fault-channel @routing)
                           ::install-gate-core-fault
                           #(= "install gate broke mid-opening"
                               (ex-message (::flow/ex %))))
                    database @connection
                    evaluation (db/pull database '[*]
                                        [:seon.cluster.eval/id
                                         (turn/receipt-identity run-id 0)])]
                (is (= agent-id (:seon.cluster.agent/id fault)))
                (is (true? (:seon.test/install-gate-broke
                             (ex-data (::flow/ex fault)))))
                (is (some? evaluation))
                (is (nil? (:seon.eval/value evaluation))
                    "a core failure does not impersonate an evaluated result")
                (is (nil? (:seon.turn/closed-at
                            (db/pull database [:seon.turn/closed-at]
                                     [:seon.turn/id run-id]))))
                (is (some? @(:seon.cluster.agent/turn-backstop-state entry)))
                ;; The fixture observed the live fault. It owns teardown of
                ;; the remaining diagnostic timer, just as its proc graph.
                (async/offer!
                 (:seon.cluster.agent/cancel
                  @(:seon.cluster.agent/turn-backstop-state entry))
                 ::fixture-stopped))))
          (finally
            (stop-database-events! connection events)
            (disarm-all! routing)))))))

(deftest park-wake-test
  (with-connection
    (fn [connection ctx]
      (let [routing (armory)
            ledger (atom [])]
        (db/transact! connection
                    [(agent-row "parked")
                     (config-row "park-2026072812"
                                 {:seon.config.run/max-episode-runs 100})])
        (try
          (with-redefs [ai/complete
                        (recording-completer
                         ledger (fn [_] "(seon.run/complete \"done\")"))]
            (let [entry (arm-one! connection ctx routing "parked")]
              (testing "armed and idle: the arm prime's pass ran and
              spent nothing — the window is bounded by ping counts,
              never a sleep standing for proof"
                (is (await-until #(pos? (::flow/count
                                         (turn-ping entry)))))
                (is (zero? (count @ledger)))
                (is (empty? (db/q '[:find ?run :where
                                   [?run :seon.turn/id _]]
                                 @connection))))
              (testing "one committed trigger → exactly one run, one
              provider call"
                (outside-trigger! connection "parked"
                                  "m-2026072812" "one unit of work")
                (async/offer! (:seon.cluster.wake/channel entry) ::wake)
                (is (await-until
                     #(some? (db/q '[:find ?c . :where
                                    [_ :seon.turn/closed-at ?c]]
                                  @connection))))
                (is (= 1 (count @ledger)))
                (is (= {"m-2026072812" 1} (answers-by-trigger @connection))))
              (testing "idle again with ping counts flat: a probe wake
              runs a pass that does no work and calls nothing"
                (let [passes (::flow/count (turn-ping entry))]
                  (async/offer! (:seon.cluster.wake/channel entry)
                                ::probe)
                  (is (await-until #(> (::flow/count (turn-ping entry))
                                       passes)))
                  (is (= 1 (count @ledger)))))))
          (finally
            (disarm-all! routing)))))))

;;; ---------------------------------------------------------------------------
;;; 3. pause-during-in-flight-call-test — seed 2026072813
;;; ---------------------------------------------------------------------------

(deftest pause-during-in-flight-call-test
  (with-connection
    (fn [connection ctx]
      (let [routing (armory)
            ledger (atom [])
            release-provider (CountDownLatch. 1)
            provider-entered (CountDownLatch. 1)
            events (database-events connection)]
        (db/transact! connection
                    [(agent-row "pausable")
                     (config-row "pause-2026072813"
                                 {:seon.config.run/max-episode-runs 100})])
        (try
          (with-redefs [ai/complete
                        (fn [request]
                          (swap! ledger conj request)
                          (.countDown provider-entered)
                          (test-support/await-event! release-provider ::release-provider)
                          {:seon.ai/text "(seon.run/complete \"done\")"})]
            (let [entry (arm-one! connection ctx routing "pausable")
                  graph (:seon.flow/graph entry)]
              (outside-trigger! connection "pausable"
                                "m-2026072813-a" "slow work")
              (async/offer! (:seon.cluster.wake/channel entry) ::wake)
              (is (test-support/await-event! provider-entered
                                             ::provider-entered)
                  "the provider call is in flight")
              (testing "pause returns immediately — fire-and-forget"
                (is (test-support/await-event! (future (flow/pause graph))
                                               ::pause-returned))
                (is (= :paused
                       (:clojure.core.async.flow/status
                        (flow/ping-proc graph ::agent/mailbox)))
                    "the mailbox acknowledged pause before work continued"))
              (testing "the in-flight call completes and its terminal
              facts COMMIT while paused"
                (.countDown release-provider)
                (is (test-support/await-event!
                     (:seon.cluster.agent-test/events events)
                     ::plan-frozen
                     #(some? (db/q '[:find ?digest . :where
                                    [_ :seon.turn/plan-digest ?digest]]
                                  (:db-after %))))
                    "the plan freeze landed")
                (is (= :paused
                       (:clojure.core.async.flow/status
                        (flow/ping-proc graph ::agent/mailbox)))
                    "and the mailbox then parked paused"))
              (testing "a second trigger committed while paused stays an
              unanswered row — no run opens"
                (outside-trigger! connection "pausable"
                                  "m-2026072813-b" "queued work")
                (async/offer! (:seon.cluster.wake/channel entry) ::wake)
                (let [deliveries (::agent/deliveries (mailbox-ping entry))]
                  (is (= deliveries (::agent/deliveries
                                     (mailbox-ping entry)))
                      "a later acknowledged ping finds no paused delivery"))
                (is (contains? (set (map :seon.cluster.message/id
                                         (turn/unanswered-triggers
                                          @connection "pausable")))
                               "m-2026072813-b")))
              (testing "resume answers everything exactly once"
                (flow/resume graph)
                (is (test-support/await-event!
                     (:seon.cluster.agent-test/events events)
                     ::resumed-quiescence
                     #(quiescent? (:db-after %) ["pausable"])))
                (is (= {"m-2026072813-a" 1 "m-2026072813-b" 1}
                       (answers-by-trigger @connection)))
                (is (= 2 (count @ledger))
                    "one provider call per run — the pause added none"))))
          (finally
            (.countDown release-provider)
            (stop-database-events! connection events)
            (disarm-all! routing)))))))

;;; ---------------------------------------------------------------------------
;;; 4. episode-cap-refusal-test — seed 2026072814
;;; ---------------------------------------------------------------------------

(defn- opened-run!
  "Open and close one turn with `message-id` as provenance."
  [connection agent-id run-id message-id at]
  (db/transact! connection
              {:tx-data (into (turn/open-tx {:seon.turn/id run-id
                                            :seon.turn/agent
                                            [:seon.cluster.agent/id agent-id]
                                            :seon.turn/trigger
                                            [:seon.cluster.message/id message-id]
                                            :seon.turn/opened-at at})
                              [])})
  (db/transact! connection
                [{:seon.turn/id run-id :seon.turn/reply "(identity nil)"}
                 {:seon.ai.attempt/id (id/digest 12 [:seon.ai.attempt/id run-id 0])
                  :seon.turn/_attempts [:seon.turn/id run-id]
                  :seon.ai.attempt/ordinal 0
                  :seon.ai.attempt/at at
                  :seon.ai/endpoint "https://fixture.invalid/v1/chat"
                  :seon.ai/model "fixture-model"
                  :seon.ai.attempt/settings-edn "{}"}])
  (db/transact! connection
              (turn/close-tx {:seon.turn/id run-id
                             :seon.db.process/id process
                             :seon.turn/closed-at at})))

(deftest episode-cap-refusal-test
  ;; seed 2026072814 — the derivation is asserted DIRECTLY (the cited
  ;; query), because the refusal's whole contract is that no consumer
  ;; ever sees a decision: the work simply does not derive.
  (with-connection
    (fn [connection _ctx]
      (let [request {:seon.cluster.agent/id "alice"
                     :seon.db.process/id process
                     :seon.turn.work/now (Date.)}]
        (db/transact! connection
                    [{:seon.cluster.agent/id "alice"}
                     {:seon.cluster.agent/id "bob"}
                     (config-row "cap-2026072814"
                                 {:seon.config.run/max-episode-runs 3})])
        ;; episode 1: a human asks
        (outside-trigger! connection "alice" "h1" "human asks")
        (opened-run! connection "alice" "e1" "h1" now)
        (is (= 1 (turn/episode-runs @connection "alice")))
        ;; A message about an earlier entity is an inside wake. The
        ;; classification depends on the about ref, not the target's family.
        (db/transact! connection
                    [{:seon.cluster.message/id "r1"
                      :seon.cluster.message/to
                      [:seon.cluster.agent/id "alice"]
                      :seon.cluster.message/about
                      [:seon.cluster.message/id "h1"]
                      :seon.cluster.message/content "about a fault"
                      :seon.cluster.message/at (Date.)}])
        (opened-run! connection "alice" "e2" "r1" now)
        (is (= 2 (turn/episode-runs @connection "alice"))
            "the recorder's message did not reset the episode (R3)")
        ;; a peer's message brings the count to the cap
        (agent-trigger! connection "bob" "alice" "b1" "bob asks")
        (opened-run! connection "alice" "e3" "b1" now)
        (is (= 3 (turn/episode-runs @connection "alice")))
        ;; the cap is hit: a further self-trigger derives NOTHING
        (agent-trigger! connection "bob" "alice" "b2" "bob again")
        (let [max-tx-before (:max-tx @connection)
              derived (turn/next-agent-work @connection request)]
          (is (nil? derived) "the deferred trigger derives no work")
          (is (= max-tx-before (:max-tx @connection))
              "datom census: the refusal wrote NOTHING")
          (is (= ["b2"] (mapv :seon.cluster.message/id
                              (turn/deferred-triggers @connection
                                                      "alice")))))
        (testing "the derived problems family and prompt line are
        present under `get`, from facts alone"
          (let [found (problems/problems
                       @connection
                       {})
                deferred (get found :seon.problems/deferred-agents)]
            (is (= [{:seon.cluster.agent/id "alice"
                     :seon.turn.work/episode-runs 3
                     :seon.problems/deferred-count 1}]
                   deferred))
            (is (str/includes?
                 (problems/ai-prose found)
                 "3 self-triggered runs since the last outside trigger"))
            (is (str/includes? (problems/ai-prose found)
                               "1 triggers are deferred"))))
        (testing "a fresh outside wake refills the bound for every pending wake"
          (outside-trigger! connection "alice" "h2" "human again")
          (let [derived (turn/next-agent-work @connection request)]
            (is (= :open (:seon.turn.work/situation derived)))
            (is (= "b2" (:seon.cluster.message/id derived))
                "the oldest pending message remains provenance"))
          (opened-run! connection "alice" "e4" "b2" (Date.))
          (is (= 1 (turn/episode-runs @connection "alice"))
              "the outside wake refilled the bound before this turn"))
        (testing "the new turn also answers the older deferred wake"
          (is (nil? (turn/next-agent-work @connection request)))
          (is (empty? (turn/deferred-triggers @connection "alice")))
          (is (empty? (get (problems/problems @connection {})
                           :seon.problems/deferred-agents [])))
          (is (= 1 (turn/episode-runs @connection "alice"))))))))

;;; ---------------------------------------------------------------------------
;;; 5. hot-reload-var-test — seed 2026072815
;;; ---------------------------------------------------------------------------

(deftest hot-reload-var-test
  ;; seed 2026072815 — composing F0(a): the blueprint builds procs from
  ;; VARS, so redefining `seon.turn/step` changes a RUNNING graph's next
  ;; pass with no rebuild; a control proc built from the captured fn
  ;; VALUE keeps running v1. The v2 evidence is an atom only v2 bumps —
  ;; a pass that increments it ran v2, and a pass that does not ran v1.
  (with-connection
    (fn [connection ctx]
      (let [original @#'seon.turn/step
            routing (armory)
            base (handle connection ctx)
            v2-ran (atom 0)
            wrap (fn [step]
                   (fn
                     ([] (step))
                     ([args] (step args))
                     ([state transition] (step state transition))
                     ([state input message]
                      (swap! v2-ran inc)
                      (step state input message))))]
        (db/transact! connection
                    [{:seon.cluster.agent/id "reloaded"}
                     (config-row "hot-2026072815"
                                 {:seon.config.run/max-episode-runs 100})])
        (try
          (let [entry (arm-one! connection ctx routing "reloaded")
                ;; the CONTROL: an identically-shaped graph whose turn
                ;; proc is built from the captured fn VALUE — hot
                ;; reload must NOT reach it
                control-channel (async/chan (async/sliding-buffer 1))
                control-completion (async/chan 1)
                _ (async/>!! control-completion ::ready)
                control-handle (assoc base
                                      :seon.cluster.wake/channel
                                      control-channel
                                      :seon.turn.loop/completion
                                      control-completion)
                control (flow/create-flow
                         {:procs
                          {::agent/mailbox
                           {:proc (flow/process
                                   #'agent/mailbox-step
                                   {:workload :io})
                            :args {:seon.cluster.wake/channel
                                   control-channel}}
                           ::agent/turn
                           {:proc (flow/process
                                   original
                                   {:workload :io})
                            :args {:seon.turn.loop/cluster
                                   control-handle
                                   :seon.cluster.agent/id "reloaded"}
                            :chan-opts {::agent/episode
                                        {:buf-or-n
                                         (async/sliding-buffer 1)}}}}
                          :conns [[[::agent/mailbox ::agent/episode]
                                   [::agent/turn ::agent/episode]]]})
                _ (flow/start control)
                _ (flow/resume control)]
            (try
              ;; let both graphs finish any prime pass under v1 first
              (is (await-until
                   #(some-> (::flow/count (turn-ping entry)) pos?)))
              (alter-var-root #'seon.turn/step (constantly
                                                 (wrap original)))
              (testing "the armed graph's next pass observably runs v2,
              with no rebuild"
                (is (zero? @v2-ran))
                (async/offer! (:seon.cluster.wake/channel entry) ::wake)
                (is (await-until #(pos? @v2-ran))
                    "the var-built proc picked up v2 immediately"))
              (testing "the fn-value control proc still runs v1"
                (let [before @v2-ran
                      control-passes
                      (await-until
                       #(some-> (flow/ping-proc control ::agent/turn)
                                ::flow/count))]
                  (async/offer! control-channel ::wake)
                  (is (await-until
                       #(some-> (flow/ping-proc control ::agent/turn)
                                ::flow/count
                                (> control-passes)))
                      "the control proc ran a pass")
                  (is (= before @v2-ran)
                      "and it never touched v2 — closures captured at
                       construction do not hot reload")))
              (finally
                (alter-var-root #'seon.turn/step (constantly original))
                (flow/stop control))))
          (finally
            (disarm-all! routing)))))))

;;; ---------------------------------------------------------------------------
;;; 6. restamp-recovery-test — seed 2026072816
;;; ---------------------------------------------------------------------------

(deftest restamp-recovery-test
  ;; seed 2026072816 — the IN-PROCESS kill -9 projection: by the
  ;; transport law a killed process leaves exactly its committed facts
  ;; (every channel's contents are discarded), so the dead process's
  ;; wreckage is built from the REAL transitions and the re-arm is the
  ;; boot shape: recover → re-stamp → prime. The real process-death
  ;; kill -9 proof stays owned by F4.
  (with-connection
    (fn [connection ctx]
      (let [dead "99999-1"
            routing (armory)
            ledger (atom [])
            evaluation-sources (atom [])
            evaluate fixture-evaluate]
        (db/transact! connection
                    [(agent-row "midfold")
                     (agent-row "waiting")
                     (config-row "restamp-2026072816"
                                 {:seon.config.run/max-episode-runs 100})])
        ;; the dead process's history: open+claim on an outside
        ;; trigger, a three-form plan, form 0 settled, form 1 STARTED
        ;; and never settled, and a capability-shaped form 2 that had
        ;; never started — killed mid-fold
        (outside-trigger! connection "midfold" "m-dead" "count things")
        (db/transact! connection
                    {:tx-data (into (turn/open-tx
                                     {:seon.turn/id "run-dead"
                                      :seon.turn/agent
                                      [:seon.cluster.agent/id "midfold"]
                                      :seon.turn/trigger
                                      [:seon.cluster.message/id "m-dead"]
                                      :seon.turn/opened-at now})
                                    [])})
        (db/transact! connection
                    (turn/plan-tx {:seon.turn/id "run-dead"
                                  :seon.db.process/id dead
                                  :seon.turn/plan-digest
                                  (apply str (repeat 64 "d"))
                                  :seon.turn/sources
                                  [{:seon.cluster.eval/source "(+ 1 2)"}
                                   {:seon.cluster.eval/source
                                    "(+ 3 4)"}
                                   {:seon.cluster.eval/source
                                    "(seon.cluster.message/send \"waiting\" \"must not run\")"}]}))
        ;; ONE ENTITY PER (run, ordinal): the freeze minted all three
        ;; evaluations with their start instant, exactly as the turn's one
        ;; intent transaction does. Ordinal 0 settles; 1 and 2 stay running.
        (db/transact! connection
                    (turn/receipt-settle-tx {:seon.turn/id "run-dead"
                                            :seon.cluster.eval/ordinal 0
                                            :seon.eval/value
                                            "3"}))
        ;; Messages committed before the crash and never answered remain
        ;; triggers. One belongs to the interrupted agent itself, proving
        ;; recovery ends only the old WORK rather than dropping mail.
        (outside-trigger! connection "midfold" "m-unanswered"
                          "still needed after crash")
        (outside-trigger! connection "waiting" "m-waiting" "still here")
        (try
          (with-redefs [ai/complete
                        (recording-completer
                         ledger (fn [_] "(seon.run/complete \"done\")"))
                        fixture-evaluate
                        (fn [request]
                          (swap! evaluation-sources conj
                                 (:seon.cluster.eval/source request))
                          (evaluate request))]
            (let [events (database-events connection)
                  ;; BOOT-SHAPE RE-ARM: recover, then re-stamp + prime. The
                  ;; listener stands before either action, so terminal facts
                  ;; cannot cross a read/take gap and pending work cannot be
                  ;; misclassified by a test-local clock.
                  db
                  (try
                    (db/transact! connection
                                  (turn/recover-tx
                                   {:seon.turn/id "run-dead"

                                    :seon.turn/now (Date.)}))
                    (doseq [agent-id ["midfold" "waiting"]]
                      (arm-one! connection ctx routing agent-id))
                    (await-database-state!
                     connection
                     (:seon.cluster.agent-test/events events)
                     #(quiescent? % ["midfold" "waiting"]))
                    (finally
                      (stop-database-events! connection events)))
                  answers (answers-by-trigger db)
                  run-receipts
                  (db/q '[:find [?receipt ...]
                         :where
                         [?run :seon.turn/id "run-dead"]
                         [?receipt :seon.cluster.eval/run ?run]]
                       db)]
              (is (quiescent? db ["midfold" "waiting"]))
              (testing "recovery ends the interrupted run atomically"
                (is (some? (db/q '[:find ?at .
                                  :where
                                  [?run :seon.turn/id "run-dead"]
                                  [?receipt :seon.cluster.eval/run ?run]
                                  [?receipt :seon.cluster.eval/ordinal 1]
                                  [?receipt
                                   :seon.cluster.eval/interrupted-at ?at]]
                                db)))

                (is (some? (db/q '[:find ?at .
                                  :where
                                  [?run :seon.turn/id "run-dead"]
                                  [?run :seon.turn/closed-at ?at]]
                                db)))
                (is (nil? (db/q '[:find ?run .
                                 :where
                                 [?agent :seon.cluster.agent/id "midfold"]
                                 [?run :seon.turn/agent ?agent]
                                 (not [?run :seon.turn/closed-at])]
                               db))))
              (testing "the interrupted plan never continues"
                (is (every? (fn [[_ _ n]] (= 1 n))
                            (db/q '[:find ?run ?ordinal (count ?receipt)
                                   :where
                                   [?receipt :seon.cluster.eval/run ?run]
                                   [?receipt
                                    :seon.cluster.eval/ordinal ?ordinal]]
                                 db)))
                (is (nil? (db/q '[:find ?result .
                                 :where
                                 [?run :seon.turn/id "run-dead"]
                                 [?receipt :seon.cluster.eval/run ?run]
                                 [?receipt :seon.cluster.eval/ordinal 1]
                                 [?receipt
                                  :seon.eval/value ?result]]
                               db)))
                ;; ONE ENTITY PER (run, ordinal): the capability-shaped
                ;; suffix HAS its frozen evaluation — the intent transaction
                ;; made it durable — and recovery settled it with no result
                ;; and no error, only the interruption stamp. "It never ran"
                ;; is the absence of a RESULT, not the absence of the row.
                (let [suffix (db/q '[:find (pull ?receipt [*]) .
                                     :where
                                     [?run :seon.turn/id "run-dead"]
                                     [?receipt :seon.cluster.eval/run ?run]
                                     [?receipt :seon.cluster.eval/ordinal 2]]
                                   db)]
                  (is (some? suffix)
                      "the frozen suffix is durable intent")
                  (is (nil? (:seon.eval/value suffix))
                      "the unstarted capability-shaped suffix settled no result")
                  (is (nil? (:seon.cluster.eval/error suffix))
                      "and recorded no evaluation error"))
                (is (not-any? #(str/includes? % "my.message/send")
                              @evaluation-sources)
                    "and it never reached the evaluator"))
              (testing "unanswered pre-crash messages start new episodes"
                (is (= 2 (count @ledger))
                    "one fresh provider call for each unanswered message")
                (is (empty? (turn/unanswered-triggers db "midfold"))
                    "the new turn observes both pre-crash wakes")
                (is (= 1 (get answers "m-waiting"))))
              (testing "the recovered facts derive one interruption value"
                (let [receipts (mapv #(db/pull db '[*] %) run-receipts)
                      warning (turn/interrupted-warning receipts)
                      rendered
                      (turn/render-ai
                       (assoc (db/pull db '[*]
                                       [:seon.turn/id "run-dead"])
                              :seon.db/db db))]
                  (is (= {:seon.cluster.eval/ordinal 1
                          :seon.turn/missing-results 2}
                         warning))
                  (is (str/includes? rendered
                                     "It was interrupted at form 1")
                      "the surviving run render narrates the derived cut")))))
          (finally
            (disarm-all! routing)))))))

;;; ---------------------------------------------------------------------------
;;; 7. unheld-resume-regression (audit P1) — seed 2026072817
;;; ---------------------------------------------------------------------------

;;; ---------------------------------------------------------------------------
;;; 8. custody-mismatch-regression (audit P2) — seed 2026072818
;;; ---------------------------------------------------------------------------



;;; ---------------------------------------------------------------------------
;;; 9. wake-routing-conservation-property — seed 2026072819
;;; ---------------------------------------------------------------------------

(defn- routing-trial
  "One generated interleaving of agent-create / message-to-new-agent /
  ordinary message, with the FULL production wiring: the routing
  listener plus the armer proc in its own graph — no agent is
  pre-armed; the armer does all arming."
  [operations]
  (with-connection
    (fn [connection ctx]
      (let [routing (armory)
            armer-channel (async/chan (async/sliding-buffer 1))
            armer-handle (assoc (handle connection ctx)
                                :seon.cluster/name "route-trial"
                                :seon.cluster.wake/channel armer-channel
                                :seon.turn.loop/completion
                                (async/promise-chan))
            armer-graph (flow/create-flow
                         {:procs
                          {::agent/armer
                           {:proc (flow/process #'agent/armer-step
                                                {:workload :io})
                            :args {:seon.turn.loop/cluster armer-handle
                                   :seon.cluster.agent/routing routing}}}
                          :conns []
                          :io-exec (cluster/projection-executor
                                    (:seon.sci.eval/projection-state ctx))})
            armer-started (flow/start armer-graph)
            _ (seon.flow/join-error-fanout!
               {:seon.flow/started armer-started
                :seon.flow/fault-channel (:seon.cluster.agent/fault-channel @routing)
                :seon.flow/tag {}})
            _ (flow/resume armer-graph)
            ledger (atom [])
            created (atom [])
            message-count (atom 0)
            agent-ids
            (mapv #(str "ra-" %)
                  (range (count (filter #{:create :create-and-message}
                                        operations))))
            expected-agent-ids (set agent-ids)
            armed-event (async/promise-chan)
            watch-key (random-uuid)
            publish-armed!
            (fn [state]
              (when (= expected-agent-ids
                       (set (keys (:seon.cluster.agent/armed state))))
                (async/offer! armed-event state)))]
        (db/transact! connection
                      [{:seon.db.process/id process}
                       {:seon.cluster/name "route-trial"}
                       (config-row
                        "route-trial"
                        {:seon.config.run/max-episode-runs 100})])
        (try
          (with-redefs [ai/complete
                        (recording-completer
                         ledger (fn [_] "(seon.run/complete \"done\")"))
                        bootstrap/next-entry (constantly nil)]
            (wake/route! {:seon.cluster.wake/connection connection
                          :seon.cluster.wake/channels
                          (fn [] (agent/channels routing))
                          :seon.cluster.wake/fenced?
                          (fn [agent-eid channel]
                            (agent/fenced-route? routing agent-eid channel))
                          :seon.cluster.wake/armer-channel armer-channel
                          :seon.cluster.wake/render-channel
                          (async/chan (async/sliding-buffer 1))
                          :seon.render.web/interest (atom :all)
                          :seon.cluster.wake/fault-channel
                          (:seon.cluster.agent/fault-channel @routing)
                          :seon.cluster.wake/key ::route-trial})
            (let [events (database-events connection ::routing-conservation)]
              (add-watch routing watch-key
                         (fn [_ _ _ current] (publish-armed! current)))
              ;; Register before deriving current state: the empty-agent case
              ;; is already complete, while every later arm swap publishes.
              (publish-armed! @routing)
              (try
                (doseq [[op index] (map vector operations (range))]
                  (let [agent-id (str "ra-" (count @created))]
                    (case op
                      :create
                      (do (create-generated-agent!
                           connection "route-trial" agent-id)
                          (swap! created conj agent-id))

                      :create-and-message
                      ;; the one-commit window the armer belt exists for:
                      ;; the recipient's graph cannot exist yet
                      (do (db/transact!
                           connection
                           {:tx-data
                            [[:db.fn/call
                              #'cluster/ensure-entity-call
                              process
                              (Date.)
                              {:seon.cluster.agent/id agent-id
                               :seon.cluster/name "route-trial"
                               :seon.ns/name
                               (symbol (str "my.agents." agent-id))}]
                            {:seon.cluster.message/id
                             (str "rm-" index)
                             :seon.cluster.message/to
                             {:seon.cluster.agent/id agent-id}
                             :seon.cluster.message/content "hello, newborn"
                             :seon.cluster.message/at (Date.)}]})
                          (swap! created conj agent-id)
                          (swap! message-count inc))

                      :message
                      (when-let [target (first @created)]
                        (outside-trigger! connection target
                                          (str "rm-" index) "more work")
                        (swap! message-count inc)))))
                (when (nil? (try
                                  (test-support/await-event! armed-event ::all-agents-armed)
                                  (catch Throwable failure
                                    (throw (ex-info "Agent creation did not arm."
                                                    {:seon.test/agents
                                                     (db/q '[:find ?id :where [_ :seon.cluster.agent/id ?id]] @connection)
                                                     :seon.test/armed
                                                     (vec (keys (:seon.cluster.agent/armed @routing)))
                                                     :seon.test/created @created
                                                     :seon.test/armer
                                                     (flow/ping-proc armer-graph ::agent/armer)
                                                     :seon.test/fault
                                                     (some-> (async/poll! (:seon.cluster.agent/fault-channel @routing))
                                                             ::flow/ex ex-data
                                                             (select-keys [:seon.error/kind :seon.error/message]))}
                                                    failure)))))
                  (throw
                   (ex-info "The routing watch closed before every agent armed."
                            {:seon.error/kind ::routing-watch-closed})))
                (let [db
                      (await-database-state!
                       connection
                       (:seon.cluster.agent-test/events events)
                       #(quiescent? % agent-ids))
                      answers (answers-by-trigger db)]
                  {:settled? true
                   :armed-once?
                   (= (count agent-ids)
                      (count (:seon.cluster.agent/armed @routing)))
                   :no-unarmed-with-triggers?
                   (every? (fn [agent-id]
                             (or (contains? (:seon.cluster.agent/armed
                                             @routing) agent-id)
                                 (empty? (turn/unanswered-triggers
                                          db agent-id))))
                           agent-ids)
                   :every-message-answered-once?
                   (and (<= (count answers) (+ @message-count (count agent-ids)))
                        (every? #(= 1 (val %)) answers)
                        (every? #(empty? (turn/unanswered-triggers db %)) agent-ids))})
                (finally
                  (remove-watch routing watch-key)
                  (stop-database-events! connection events)))))
          (finally
            (wake/unlisten! {:seon.cluster.wake/connection connection
                             :seon.cluster.wake/key ::route-trial})
            (flow/stop armer-graph)
            (test-support/await-event! (:seon.turn.loop/completion armer-handle)
                                      ::routing-armer-stopped)
            (disarm-all! routing)
            (async/close! armer-channel)))))))

(deftest wake-routing-conservation-property
  (let [result
        (tc/quick-check
         12
         (prop/for-all
          [operations (gen/vector
                       (gen/elements [:create :create-and-message
                                      :message])
                       1 6)]
          (let [verdict (routing-trial operations)]
            (every? val verdict)))
         :seed 2026072819)]
    (is (:pass? result)
        (str "shrunk counterexample: " (pr-str (:shrunk result))))))

(deftest routing-conservation-waits-for-terminal-evidence
  (let [provider-entered (CountDownLatch. 1)
        release-provider (CountDownLatch. 1)
        original recording-completer
        held-completer
        (fn [ledger text-fn]
          (let [complete (original ledger text-fn)]
            (fn [request]
              (.countDown provider-entered)
              (test-support/await-event! release-provider ::release-provider)
              (complete request))))
        verdict
        (future
          (with-redefs [recording-completer held-completer]
            (routing-trial [:create-and-message])))]
    (try
      (test-support/await-event! provider-entered ::provider-entered)
      (is (false? (realized? verdict))
          "pending routed work is not classified as a conservation failure")
      (.countDown release-provider)
      (is (every? val
                  (test-support/await-event! verdict ::terminal-evidence)))
      (finally
        (.countDown release-provider)
        (test-support/await-event! verdict ::routing-trial-stopped)))))

;;; ---------------------------------------------------------------------------
;;; 10. wait-closes-in-terminal-tx-test — seed 2026072820
;;; ---------------------------------------------------------------------------

(deftest wait-closes-in-terminal-tx-test
  ;; seed 2026072820 — the ruled `my.run/wait` revision folded into F1
  ;; (README owner-decisions #4): the wait's terminal transaction
  ;; settles the receipt AND closes the run in ONE commit, so the
  ;; unheld-open-planned intermediate state — the P1 feeder — exists at
  ;; NO basis; the agent's next trigger opens a NEW run.
  (with-connection
    (fn [connection ctx]
      (let [routing (armory)
            ledger (atom [])]
        (db/transact! connection
                    [(agent-row "waiter")
                     (config-row "wait-2026072820"
                                 {:seon.config.run/max-episode-runs 100})])
        (try
          (with-redefs [ai/complete
                        (recording-completer
                         ledger (fn [_] "(seon.run/wait \"need input\")"))]
            (let [events (database-events connection)
                  db
                  (try
                    (arm-one! connection ctx routing "waiter")
                    (outside-trigger! connection "waiter" "m-wait"
                                      "hold on")
                    (let [entry (agent/armed routing "waiter")]
                      (async/offer! (:seon.cluster.wake/channel entry)
                                    ::wake))
                    (await-database-state!
                     connection
                     (:seon.cluster.agent-test/events events)
                     #(and (= 1 (terminal-receipt-count %))
                            (quiescent? % ["waiter"])))
                    (finally
                      (stop-database-events! connection events)))
                  run-id (db/q '[:find ?id . :where
                                [?run :seon.turn/id ?id]]
                              db)
                  settle-tx (db/q '[:find ?tx . :where
                                   [?receipt
                                    :seon.eval/value _ ?tx]]
                                 db)
                  close-tx (db/q '[:find ?tx . :where
                                  [_ :seon.turn/closed-at _ ?tx]]
                                db)]
              (is (quiescent? db ["waiter"]))
              (testing "settle and close share ONE transaction"
                (is (some? settle-tx))
                (is (= settle-tx close-tx)))

              (testing "the note survives in the receipt"
                (is (str/includes?
                     (db/q '[:find ?edn . :where
                            [_ :seon.eval/value ?edn]]
                          db)
                     "need input")))
              (testing "the agent's next trigger opens a NEW run"
                (let [events (database-events connection)
                      terminal-db
                      (try
                        (outside-trigger! connection "waiter" "m-next"
                                          "resume")
                        (let [entry (agent/armed routing "waiter")]
                          (async/offer! (:seon.cluster.wake/channel entry)
                                        ::wake))
                        (await-database-state!
                         connection
                         (:seon.cluster.agent-test/events events)
                         #(and (= 2 (terminal-receipt-count %))
                                (quiescent? % ["waiter"])))
                        (finally
                          (stop-database-events! connection events)))]
                  (is (quiescent? terminal-db ["waiter"]))
                  (is (= 2 (or (db/q '[:find (count ?run) . :where
                                      [?run :seon.turn/id _]]
                                    terminal-db)
                               0)))
                  (is (= {"m-wait" 1 "m-next" 1}
                         (answers-by-trigger terminal-db)))))))
          (finally
            (disarm-all! routing)))))))
