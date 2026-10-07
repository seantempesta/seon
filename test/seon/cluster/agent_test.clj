(ns seon.cluster.agent-test
  "The F1 sealed suite: agents are flows (seeds 2026072811-2026072820).

  Ten oracles from the sealed contract
  (docs/prds/sci-execution-runtime/plan/f1-agent-graph-contracts-2026-07-28.md
  §8), driven against REAL per-agent graphs wherever the claim is about
  the graphs, and against the real transitions where the claim is about
  the derivation. Per-trial in-memory databases through the canonical
  attribute population; a recorded provider reply (no paid call anywhere), and real SCI
  evaluation. The shipped evaluator runs unreplaced: no fixture redefines
  `seon.sci.eval/evaluate`."
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
            [seon.cluster.registry :as registry]
            [seon.ai :as ai]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.turn :as turn]
            [seon.cluster.prompt :as prompt]

            [seon.cluster.wake :as wake]

            [seon.config :as config]
            [seon.flow :as seon.flow]
            [seon.problems :as problems]
            [seon.render :as render]
            [sci.core :as sci]
            [seon.id :as id]
            [seon.render.web :as web]
            [seon.test-support :as test-support])
  (:import [java.util Date]
           [java.util.concurrent CountDownLatch]))

(def ^:private test-environment
  ;; The subset environment (store layer only) every crossing this
  ;; namespace constructs names; boot's own constructor, fewer layers.
  (delay (test-support/environment "seon.cluster.agent-test")))

(def ^:private shipped-eval-time-limit-ms
  ;; Ordinary finite evaluation must stay ordinary under scheduler load.
  ;; A shorter fixture-only limit changes the tested disposition.
  (delay (:seon.config.eval/time-limit-ms config/defaults)))

(set! *warn-on-reflection* true)

(def ^:dynamic *work-launcher* nil)
(def ^:dynamic *context-channel* nil)
(def ^:dynamic *stream-channel* nil)

;;; ---------------------------------------------------------------------------
;;; Fixture — canonical attributes, the handle, the source-driven evaluator
;;; ---------------------------------------------------------------------------

(def ^:private process
  cluster/boot-process-identity)

(def ^:private now (Date. 1700000000000))

(defn- with-connection
  "Drive `body` against a canonical database, real SCI and per-agent graphs.

  The shipped `seon.sci.eval/evaluate` runs as installed. A root redefinition
  here was JVM-global: a body that never exited left it in place, the next
  fixture captured it as the real evaluator, and it invoked itself (census
  2026-09-25c, StackOverflowError x11)."
  [body]
  (test-support/with-database
    (fn [connection]
      (let [ctx (test-support/fork-cluster-ctx connection)
            launcher
            (seon.flow/start-work-launcher!
             {:seon.env/environment @test-environment
              ::seon.flow/configuration
              (assoc (select-keys (test-support/effective-config)
                                  seon.flow/flow-workload-attributes)
                     :seon.config.flow.compute/concurrency 3
                     :seon.config.flow.io/queue-depth 2
                     :seon.config.flow.io/concurrency 2)})
            context-channel
            (test-support/render-context-channel
             (render/agent-render-profile config/defaults))
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
          (binding [*work-launcher* launcher
                    *context-channel* context-channel
                    *stream-channel* stream-channel]
            (body connection ctx))
          (finally
            (flow/stop graph)
            (test-support/await-event! completion ::render-stopped)
            (seon.flow/stop-work-launcher! launcher)))))))

(defn- handle
  "The cluster handle `arm!` declares, from `test-support/cluster-handle`.

  Every structural member and dial the declaration names is defaulted there
  from the shipped decisions; this adds only what this suite's world supplies
  or varies. The fixture branch inherits the executing cluster's config rows,
  so the test names its own cluster."
  [connection ctx cluster-name]
  (test-support/cluster-handle
  {:seon.env/environment @test-environment
   :seon.db/connection connection
   :seon.cluster/name cluster-name
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
   (:seon.config.agent/turn-completion-backstop-ms config/defaults)
   :seon.config.error/recurrence-limit 3
   :seon.config.message/max-chain 16}))

(defn- config-entity
  [cluster-name overlay]
  (:seon.config/desired-row
   (config/compile-manifest {:seon.boot/cluster-name cluster-name
                             :seon.config/manifest
                             (merge
                              {:seon.config.agent/turn-completion-backstop-ms
                               (* 1000 test-support/event-backstop-seconds)}
                              overlay)})))

(defn- start-armer!
  "The cluster's armer graph on its own executor, as `seon.cluster.host` starts
  it, so `stop-armer!` joins it at that executor's termination."
  [ctx armer-handle routing]
  (seon.flow/start-graph!
   {:seon.flow/projection-state (:seon.sci.eval/projection-state ctx)
    :seon.flow/graph-definition
    {:procs {:seon.agent/armer
             ;; the production seam: its loop failures are recorded by the
             ;; proc's own fault port
             {:proc (seon.flow/var-process
                     #'agent/armer-step :io
                     {:seon.env/environment @test-environment
                      :seon.turn.loop/cluster armer-handle
                      :seon.agent/routing routing})}}
     :conns []}}))

(defn- stop-armer!
  "Stop the armer and join it: `flow/stop` is a command, and the graph's exit
  is its executor's termination (`seon.flow/join-graph!`). No proc publishes a
  completion event on stop since 4b6834f7d."
  [armer operation]
  (flow/stop (:seon.flow/graph armer))
  (is (nil? (seon.flow/join-graph!
             {:seon.flow/executor (:seon.flow/executor armer)
              :seon.await/bound
              {:seon.await/config-attribute :seon.config.agent/turn-completion-backstop-ms
               :seon.await/config-value 5000
               :seon.await/deadline-nanos (+ (System/nanoTime) (* 5000 1000000))}
              :seon.await/diagnostic {:seon.error/layer ::armer
                                      :seon.error/operation operation
                                      :seon.error/expected ::armer-exited}}))
      "the armer graph exits within its bound"))

(defn- armory
  "The attempt's routing entry with a test fault channel already joined."
  [connection]
  (let [routing (test-support/attempt-routing connection)]
    (swap! routing assoc :seon.agent/fault-channel
           (async/chan (async/sliding-buffer 16)))
    routing))

(defn- live-branch
  "The branch the fixture connection holds: the live branch for its agents."
  [connection]
  (get-in (db/db connection) [:config :branch]))

(defn- arm-one!
  [connection ctx routing cluster-name agent-id]
  (agent/arm! {:seon.turn.loop/cluster (handle connection ctx cluster-name)
               :seon.agent/id agent-id
               :seon.agent/routing routing}))

(defn- created-agent-tx
  "The canonical creation of `agent-id` for `cluster-name`, live on the fixture branch."
  [connection cluster-name agent-id]
  (agent/creation-tx {:seon.agent/id agent-id
                      :seon.agent/branch (live-branch connection)
                      :seon.ns/name (symbol (str "my.agents." agent-id))
                      :seon.cluster/name cluster-name}))


(defn- agent-entity
  [connection agent-id]
  {:seon.agent/id agent-id
   :seon.agent/branch (live-branch connection)
   :seon.agent/namespace
   (test-support/namespace-entity @connection (symbol (str "my.agents." agent-id)))})

(defn- create-generated-agent!
  [connection cluster-name agent-id]
  (cluster/ensure-entity!
   connection process
   {:seon.agent/id agent-id
    :seon.agent/branch (live-branch connection)
    :seon.cluster/name cluster-name
    :seon.ns/name (symbol (str "my.agents." agent-id))}))

(defn- disarm-all!
  [routing]
  (doseq [agent-id (sort (keys (:seon.agent/armed @routing)))]
    (agent/disarm! {:seon.agent/id agent-id
                    :seon.agent/routing routing})))

(defn- await-idle!
  "Observe the completed idle pass through Flow's own report channel."
  [started]
  (test-support/await-event!
   (:report-chan started) ::idle-pass :seon.turn.loop/idle?))

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
                           (db/q '[:find [(pull ?turn [:seon.turn/id :seon.turn/closed-tx
                                                     :seon.turn/reply]) ...]
                                   :where [?turn :seon.turn/id]] @connection)
                           :seon.test/evaluations
                           (db/q '[:find [(pull ?e [:seon.cluster.eval/source
                                                  :seon.cluster.eval/error
                                                  :seon.eval/shown]) ...]
                                   :where [?e :seon.cluster.eval/id]] @connection)}
                          failure)))))))

(defn- terminal-receipt-count
  [database]
  (or (db/q '[:find (count ?receipt) .
              :where [?receipt :seon.eval/shown _]
              [?receipt :seon.cluster.eval/run ?run]
              [?run :seon.turn/attempts _]]
            database)
      0))

(defn- mailbox-ping
  [entry]
  (:clojure.core.async.flow/state
   (flow/ping-proc (:seon.flow/graph entry) :seon.agent/mailbox)))

(defn- recording-completer
  "A stub `ai/complete`: the ledger of calls is the countable oracle."
  [ledger text-fn]
  (fn [_projection request]
    (swap! ledger conj request)
    {:seon.ai/text (text-fn request)}))

(defn- provider-config
  "Cluster dials selecting the fixture provider `server`, with no-provider removed."
  [server]
  (assoc (test-support/provider-config server) :seon.config.ai/no-provider :seon.config/absent))

(defn- outside-trigger!
  [connection agent-id message-id content]
  (test-support/transacted! connection
                          [{:seon.message/id message-id :seon.message/to [:seon.agent/id agent-id] :seon.message/content content}]))

(defn- agent-trigger!
  [connection from-id to-id message-id content]
  (test-support/transacted! connection
                          [{:seon.message/id message-id :seon.message/to [:seon.agent/id to-id] :seon.message/from [:seon.agent/id from-id] :seon.message/content content}]))

(defn- open-runs
  [db]
  (db/q '[:find [?id ...]
         :where
         [?run :seon.turn/id ?id]
         (not [?run :seon.turn/closed-tx _])]
       db))

(defn- answers-by-trigger
  "Message id to the number of reply-bearing turns recording it as provenance."
  [db]
  (into {}
        (db/q '[:find ?message-id (count ?run)
               :where
               [?run :seon.turn/trigger ?message]
               [?run :seon.turn/reply]
               [?message :seon.message/id ?message-id]]
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

(deftest a-function-without-a-contract-is-admitted-and-stored
  ;; Owner, rev 5: the REPL always admits; the Var render shows the missing
  ;; contract, and merge acceptance is the one refusal point.
  (with-connection
    (fn [connection ctx]
      (let [routing (armory connection)
            events (database-events connection)
            source "(defn uncontracted [] 42)"
            function-symbol 'my.agents.contract-probe/uncontracted]
        (test-support/transacted! connection [(config-entity "contract-probe" {}) (agent-entity connection "contract-probe")])
        (try
          (arm-one! connection ctx routing "contract-probe" "contract-probe")
          (let [submission
                (agent/submit-source!
                 {:seon.turn.loop/cluster (handle connection ctx "contract-probe")
                  :seon.agent/routing routing
                  :seon.agent/id "contract-probe"
                  :seon.cluster.reply/text
                  (str source "\n(seon.run/complete \"Checked.\")")})
                run-id (:seon.turn/id submission)
                database
                (await-database-state!
                 connection (:seon.cluster.agent-test/events events)
                 #(some? (:seon.turn/closed-tx
                          (db/pull % [:seon.turn/closed-tx]
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
            (is (some? (db/entity database [:seon.fn/sym function-symbol])) "stored without a contract")
            (is (nil? (:seon.fn/spec (db/pull database [:seon.fn/spec] [:seon.fn/sym function-symbol]))))
            ;; the reply is the Var's declared render, not the bare Var
            (is (re-find #"seon\.render\.var/contract" (str (:seon.eval/shown evaluation)))
                "the reply of an uncontracted defn says its contract is missing"))
          (finally
            (stop-database-events! connection events)
            (disarm-all! routing)))))))

(deftest a-run-defining-with-an-alias-its-own-ns-form-required-settles
  ;; Issue an-alias-required-by-an-earlier-form-of-a-batch: settlement analyzed
  ;; the batch against the run's starting namespace entity, refused `str/` as an
  ;; unanalyzed dependency, and routed that refusal as a panicking core fault
  ;; that could not be stored: the run never closed and the agent stayed busy.
  (with-connection
    (fn [connection ctx]
      (let [routing (armory connection)
            events (database-events connection)]
        (test-support/transacted! connection [(config-entity "alias-batch" {}) (agent-entity connection "alias-batch")])
        (try
          (arm-one! connection ctx routing "alias-batch" "alias-batch")
          (let [submit! (fn [text]
                          (let [run-id (:seon.turn/id
                                        (agent/submit-source!
                                         {:seon.turn.loop/cluster (handle connection ctx "alias-batch")
                                          :seon.agent/routing routing
                                          :seon.agent/id "alias-batch"
                                          :seon.cluster.reply/text text}))
                                database (await-database-state!
                                          connection (:seon.cluster.agent-test/events events)
                                          #(some? (:seon.turn/closed-tx
                                                   (db/pull % [:seon.turn/closed-tx]
                                                            [:seon.turn/id run-id]))))]
                            (db/q '[:find [?shown ...]
                                    :in $ ?run-id
                                    :where [?run :seon.turn/id ?run-id]
                                    [?evaluation :seon.cluster.eval/run ?run]
                                    [?evaluation :seon.cluster.eval/ordinal 3]
                                    [?evaluation :seon.eval/shown ?shown]]
                                  database run-id)))]
            (is (= ["\"Q\""]
                   (submit! (str "(ns alias.batch (:require [clojure.string :as str]))\n"
                                 "(defn a1 {:malli/schema [:=> [:cat :string] :string]} [x] (str/upper-case x))\n"
                                 "(defn b1 {:malli/schema [:=> [:cat :string] :string]} [x] (a1 x))\n"
                                 "(b1 \"q\")")))
                "the run settles and closes with the caller's value")
            (is (= ["\"alias.batch\""] (submit! "(+ 1 1)\n(+ 1 1)\n(+ 1 1)\n(str *ns*)"))
                "the agent admits its next run, which starts where the last one ended"))
          (finally
            (stop-database-events! connection events)
            (disarm-all! routing)))))))

(deftest system-source-submission-uses-the-ordinary-durable-run
  (with-connection
    (fn [connection ctx]
      (let [routing (armory connection)
            armer-channel (async/chan (async/sliding-buffer 1))
            ;; A HANDLE IS THE STORED EVALUATION'S OWN ENTITY ID, which does
            ;; not exist when this source is authored: an agent names an
            ;; earlier value from the handle its context showed it, never from
            ;; an ordinal it can predict. This run proves the durable path.
            text (str "; Read one value.\n(+ 1 1)\n"
                      "; Read a second value.\n(identity (+ 1 1))")]
        (test-support/transacted!
                     connection
                     [(agent-entity connection "source-agent")
                      (config-entity "source-submission"
                                  {:seon.config.run/max-episode-runs 100})])
        (let [cluster-handle
              (assoc (handle connection ctx "source-submission")
                     :seon.cluster.wake/channel armer-channel)]
          (is (nil? (agent/armed routing "source-agent"))
              "the source agent is unarmed before the armer starts")
          (let [armer (start-armer! ctx cluster-handle routing)]
          (try
            (let [events (database-events connection)]
              (try
                (let [submission
                      (agent/submit-source!
                       {:seon.turn.loop/cluster cluster-handle
                        :seon.agent/routing routing
                        :seon.agent/id "source-agent"
                        :seon.cluster.reply/text text})
                      run-id (:seon.turn/id submission)
                      terminal-db
                      (await-database-state!
                       connection
                       (:seon.cluster.agent-test/events events)
                       #(some?
                         (:seon.turn/closed-tx
                          (db/pull % [:seon.turn/closed-tx]
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
                              [?evaluation :seon.eval/shown ?result]]
                            terminal-db run-id)]
                  (is (string? run-id))
                  (is (= text
                         (:seon.turn/reply
                          (db/pull terminal-db [:seon.turn/reply]
                                   [:seon.turn/id run-id])))
                      "the run retains exact submitted source, not reconstructed forms")
                  (is (some? (agent/armed routing "source-agent"))
                      "submission arms an unarmed agent (arm! is idempotent)")
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
                  (is (some? (:db/id (:seon.turn/closed-tx
                              (db/pull terminal-db
                                       [:seon.turn/closed-tx]
                                       [:seon.turn/id run-id])))))
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
              (stop-armer! armer `system-source-submission-uses-the-ordinary-durable-run)
              (disarm-all! routing)
              (async/close! armer-channel))))
          (test-support/transacted!
                       connection
                       (turn/open-tx {:seon.turn/id "already-open" :seon.turn/agent [:seon.agent/id "source-agent"] :seon.turn/opened-tx "datomic.tx"}))
          (try
            (let [refusal
                  (test-support/await-event!
                   (:seon.agent/reply
                    (agent/submit-source!
                     {:seon.turn.loop/cluster cluster-handle
                      :seon.agent/routing routing
                      :seon.agent/id "source-agent"
                      :seon.cluster.reply/text "(+ 2 2)"}))
                   ::refused-open)]
              (is (= :seon.turn/agent-already-running (:seon.turn/rule refusal))
                  "the writer's own open refuses a second open run at the pass, answered on the reply"))
            (finally (disarm-all! routing))))))))

(deftest prompt-refusal-closes-without-answering-and-stops-at-the-agent-bound
  (with-connection
    (fn [connection ctx]
      (let [routing (armory connection)
            requests (atom [])
            events (database-events connection)]
        (test-support/transacted!
                     connection
                     [(assoc (agent-entity connection "prompt-refusal-cap")
                             :seon.agent/settings {:seon.config.run/max-episode-runs 1})
                      (config-entity "prompt-refusal-cap" {})])
        (try
          (with-redefs [prompt/prompt
                        (fn [& _]
                          (throw (ex-info "Prompt rendering refused."
                                          {
                                           :seon.cluster.prompt/refused
                                           :seon.cluster.prompt/missing-input
                                           :seon.error/message "Prompt rendering refused."})))
                        ai/complete
                        (recording-completer requests (constantly "unused"))]
            (let [entry (arm-one! connection ctx routing "prompt-refusal-cap" "prompt-refusal-cap")]
              (outside-trigger! connection "prompt-refusal-cap"
                                "prompt-refusal-message" "derive context")
              (async/offer! (:seon.cluster.wake/channel entry) ::wake)
              (let [database
                    (await-database-state!
                     connection (:seon.cluster.agent-test/events events)
                     #(and (= 1 (turn/episode-runs % "prompt-refusal-cap"))
                           (empty? (open-runs %))))]
                (is (empty? @requests))
                ;; the refusal is recorded once under the phase that caught
                ;; it; `:seon.cluster.prompt/refused` is a refusal-map member,
                ;; never a stored attribute (19827d2b0)
                (is (= 1 (db/q '[:find (count ?error) . :where
                                  [?error :seon.error/declared-schema :seon.turn.loop/phase-failed-error]
                                  [?error :seon.error/occurrences ?occurrence]
                                  [?occurrence :seon.error.occurrence/message "Prompt rendering refused."]]
                                database)))
                (is (seq (turn/unanswered-triggers database "prompt-refusal-cap"))
                    "a refused prompt has not observed the wake")
                (is (nil? (turn/next-agent-work
                           database {:seon.agent/id "prompt-refusal-cap"})))
                (is (false? (turn/more-agent-work?
                             database {:seon.agent/id "prompt-refusal-cap"}))))))
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
            routing (armory connection)
            armer-channel (async/chan (async/sliding-buffer 1))
            ledger (atom [])]
        (test-support/with-provider ledger (constantly "(seon.run/complete \"done\")")
          (fn [provider]
            (test-support/transacted! connection
                                    (into [(config-entity
                                            "trial"
                                            (merge {:seon.config.run/max-episode-runs 100}
                                                   (provider-config provider)))]
                                          (map (partial agent-entity connection))
                                          agent-ids))
            (try
              (doseq [agent-id agent-ids]
                (arm-one! connection ctx routing "trial" agent-id))
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
                            (:seon.agent/fault-channel @routing)
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
                                             [?run :seon.turn/attempts _]]
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
                                                [?agent :seon.agent/id
                                                 ?agent-id]
                                                [?run :seon.turn/agent
                                                 ?agent]
                                                [?run :seon.turn/attempts _]
                                                [?run :seon.turn/closed-tx
                                                 _]]
                                              db agent-id)
                                          0)
                                      (nth triggers-per-agent index)))
                                (map-indexed vector agent-ids))]
                    {::subjects-present? (boolean (seq agent-ids))
                     :settled? true
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
                (disarm-all! routing)))))))))

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
    (test-support/assert-check! result "Concurrent agents conserve their provider turns.")))

(deftest fenced-is-the-derived-quarantine-state
  (with-connection
    (fn [connection ctx]
      (let [routing (armory connection)]
        (test-support/transacted! connection [(config-entity "agent-a" {}) {:seon.agent/id "agent-a" :seon.agent/branch (live-branch connection)}])
        (try
          (is (false? (agent/fenced? routing "agent-a"))
              "an unarmed agent has no fence")
          (let [entry (arm-one! connection ctx routing "agent-a" "agent-a")
                eid (:seon.agent/eid entry)
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
      (let [routing (armory connection)
            _ (test-support/transacted! connection [(config-entity "agent-a" {}) {:seon.agent/id "agent-a" :seon.agent/branch (live-branch connection)}])
            entry (arm-one! connection ctx routing "agent-a" "agent-a")
            eid (:seon.agent/eid entry)
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
          (agent/disarm! {:seon.agent/id "agent-a"
                          :seon.agent/routing routing}))
        (is (= {:routed? false :armed? false} @observed)
            "the route is already absent when orderly teardown closes")))))

(defn- provider-backstop-config
  [cluster-name turn-completion-backstop-ms]
  (config-entity
   cluster-name
   {:seon.config.agent/turn-completion-backstop-ms
    turn-completion-backstop-ms
    ;; The provider stand-in never reaches a network; a long provider
    ;; allowance keeps the turn's own active bound out of the race, so the
    ;; observed bound is disarm's declared one. The machine's manifest may
    ;; assert no-provider, which skips the prompt prelude these tests block
    ;; in; the stand-in refuses before any provider call, so none is paid.
    :seon.config.ai/no-provider :seon.config/absent
    :seon.config.ai/timeout-ms 60000
    :seon.config.run/max-episode-runs 1}))

(defn- open-run-id
  [connection agent-id]
  (db/q '[:find ?run-id .
          :in $ ?agent-id
          :where
          [?agent :seon.agent/id ?agent-id]
          [?run :seon.turn/agent ?agent]
          [?run :seon.turn/id ?run-id]
          (not [?run :seon.turn/closed-tx])]
        @connection agent-id))

(deftest ^{:seon.test/long "Measured 7,431-8,373 ms on a HEAD snapshot (2026-09-22): arming 1,495 ms, the turn's prelude before prompt acquisition 5,400 ms (docs/seon/issues/a-turn-spends-seconds-before-its-provider-call.md), the interrupted disarm itself 1,405 ms; the disarm now runs inside one nested fixture branch: isolated acquisition 51-59 ms plus release 8-9 ms (three warm runs on default, 2026-09-25)."
           :seon.test/long-ms 12000}
  disarm-interrupts-an-in-flight-turn-and-records-it-interrupted
  ;; Interrupted execution never resumes: an orderly stop while the turn is
  ;; inside its prelude (prompt acquisition, where a live root turn spends
  ;; most of its time before the provider call) interrupts the turn's
  ;; thread, observes the proc's own stop transition well inside the
  ;; declared bound, and leaves no open turn behind.
  (with-connection
    (fn [connection ctx]
      (let [routing (armory connection)
            turn-completion-backstop-ms 5000
            prelude-entered (CountDownLatch. 1)
            never-released (CountDownLatch. 1)
            interrupted (promise)
            agent-id "prelude-interrupt"]
        (test-support/transacted!
         connection
         (conj (created-agent-tx connection "prelude-interrupt" agent-id)
               (provider-backstop-config "prelude-interrupt"
                                         turn-completion-backstop-ms)))
        (try
          (with-redefs
            [prompt/prompt
             (fn [_database _request]
               (.countDown prelude-entered)
               (try
                 (.await never-released)
                 {:seon.error/message "The prelude stand-in was released."}
                 (catch InterruptedException failure
                   (deliver interrupted (.getName (Thread/currentThread)))
                   ;; as Datahike's async layer delivers it: wrapped in an
                   ;; ex-info, which is still the stop's interrupt
                   (throw (ex-info (str failure) {} failure)))))]
            (let [entry (arm-one! connection ctx routing
                                         "prelude-interrupt" agent-id)]
              (await-idle! (:seon.flow/started entry))
              (outside-trigger! connection agent-id
                                "prelude-interrupt-message" "block")
              (async/offer! (:seon.cluster.wake/channel entry) ::wake)
              (test-support/await-event!
               prelude-entered ::interruptible-prelude-entered)
              (let [run-id (open-run-id connection agent-id)
                    _ (is (string? run-id) "the turn is open before stop")
                    faults #(count (db/q '[:find ?e :where [?e :seon.error/id _]] @connection))
                    faults-before (faults)
                    ;; Stopped under another branch's custody: recording the
                    ;; interruption is the graph's own write, not the caller's.
                    [result elapsed-ms]
                    (test-support/with-database
                     (fn [other]
                       (let [before (db/basis-t (db/db other))
                             started (System/nanoTime)
                             result (agent/disarm! {:seon.agent/id agent-id
                                                    :seon.agent/routing routing})
                             elapsed-ms (/ (- (System/nanoTime) started) 1e6)]
                         (is (= before (db/basis-t (db/db other))) "the caller's branch is unchanged")
                         [result elapsed-ms])))]
                (is (nil? result))
                (is (< elapsed-ms turn-completion-backstop-ms)
                    "stop observes exit inside its declared bound")
                (is (string? (deref interrupted 0 nil))
                    "the in-flight prelude was interrupted, not awaited")
                (is (nil? (open-run-id connection agent-id))
                    "no turn is left open")
                (is (= faults-before (faults))
                    "a stop's wrapped interrupt is no fault (sweep 2026-09-27 item 4)")
                (is (some?
                     (db/q '[:find ?closed .
                             :in $ ?run-id
                             :where [?run :seon.turn/id ?run-id]
                             [?run :seon.turn/closed-tx ?closed]]
                           @connection run-id)))
                (is (nil? (agent/armed routing agent-id)))
                (is (async.impl/closed?
                     (:seon.cluster.wake/channel entry))))))
          (finally
            (.countDown never-released)
            (disarm-all! routing)))))))

(deftest ^{:seon.test/long "Measured 7,898 ms on a HEAD snapshot (2026-09-22), dominated by the turn's prelude before prompt acquisition (docs/seon/issues/a-turn-spends-seconds-before-its-provider-call.md); the declared disarm bound here is 100 ms."
           :seon.test/long-ms 12000}
  disarm-has-a-declared-loud-turn-completion-backstop
  ;; Host work that ignores the interrupt keeps its thread; disarm then
  ;; fails loudly at its declared bound and stays retryable.
  (with-connection
    (fn [connection ctx]
      (let [routing (armory connection)
            turn-completion-backstop-ms 100
            provider-entered (CountDownLatch. 1)
            release-provider (java.util.concurrent.Semaphore. 0)
            agent-id "provider-backstop"]
        (test-support/transacted!
         connection
         (conj (created-agent-tx connection "provider-backstop" agent-id)
          (provider-backstop-config "provider-backstop"
                                    turn-completion-backstop-ms)))
        (try
          (with-redefs
            [prompt/prompt
             (fn [_database _request]
               (.countDown provider-entered)
               (.acquireUninterruptibly release-provider)
               {:seon.error/message "The prelude stand-in released."})]
            (let [entry (arm-one! connection ctx routing
                                         "provider-backstop" agent-id)]
              (await-idle! (:seon.flow/started entry))
              (outside-trigger! connection agent-id
                                "provider-backstop-message" "block")
              (async/offer! (:seon.cluster.wake/channel entry) ::wake)
              (test-support/await-event!
               provider-entered
               ::never-answering-provider-entered)
              (let [run-id (open-run-id connection agent-id)
                    stopped
                    (future
                      (try
                        (agent/disarm!
                         {:seon.agent/id agent-id
                          :seon.agent/routing routing})
                        ::unexpected-orderly-stop
                        (catch clojure.lang.ExceptionInfo failure
                          failure)))
                    thrown
                    (test-support/await-event!
                     stopped
                     ::declared-turn-completion-backstop)
                    ;; `seon.fault/fault!` stores the core fault and returns its
                    ;; receipt (0cbf4336f deleted the :panic policy); disarm!
                    ;; throws the declared failure itself
                    failure thrown]
                (is (nil? (:seon.fault/recorded (ex-data thrown))))
                (is (= :seon.agent/disarm (get-in (ex-data failure) [:seon.error/data :seon.error/operation])))
                (is (string? (:seon.agent/turn-completion-backstop (ex-data failure))))
                (is (= turn-completion-backstop-ms
                       (:seon.config.agent/turn-completion-backstop-ms
                        (ex-data failure))))
                (is (= agent-id
                       (:seon.agent/id (ex-data failure))))
                (is (= run-id
                       (:seon.turn/id (ex-data failure))))
                (is (some? (agent/armed routing agent-id))
                    "a fired backstop fails closed and leaves stop retryable")
                (is (= :seon.agent/graph-exited
                       (:seon.error/expected
                        (test-support/refusal-data
                         #(arm-one! connection ctx routing
                                    "provider-backstop" agent-id))))
                    "a restart refuses by name while the previous graph lives"))
              ;; enough permits that no later turn of the restart blocks
              (.release release-provider 1000)
              (is (.awaitTermination ^java.util.concurrent.ExecutorService
                                     (:seon.flow/executor entry)
                                     5000 java.util.concurrent.TimeUnit/MILLISECONDS)
                  "the released proc loops exit")
              (let [restarted (arm-one! connection ctx routing
                                        "provider-backstop" agent-id)]
                (is (not (identical? entry restarted))
                    "after exit a restart finishes teardown and arms a fresh graph"))
              (agent/disarm! {:seon.agent/id agent-id
                              :seon.agent/routing routing})
              (is (nil? (agent/armed routing agent-id)))
              (is (not (contains? (agent/channels routing)
                                  (:seon.agent/eid entry))))
              (is (async.impl/closed?
                   (:seon.cluster.wake/channel entry)))
              (is (async.impl/closed?
                   (:seon.turn.loop/completion entry)))
              (is (nil? (agent/disarm!
                         {:seon.agent/id agent-id
                          :seon.agent/routing routing})))
              (is (nil? (agent/armed routing agent-id))
                  "a successful retry removes the route exactly once")))
          (finally
            (.release release-provider)
            (disarm-all! routing)))))))

(deftest ^{:seon.test/long "Two arms plus one blocked turn prelude, as the disarm backstop test above (7,898 ms measured there); the release's own declared join bound is 100 ms."
           :seon.test/long-ms 15000}
  release-joins-every-owned-graph-before-it-closes-the-branch
  (with-connection
    (fn [connection _ctx]
      (let [parent (test-support/execution-handle connection)
            attempt (agent/acquire-context!
                     parent nil {:seon.agent/isolate? true
                                 :seon.cluster.registry/from (db/commit-id @connection)})
            child (:seon.db/connection attempt)
            routing (armory child)
            entered (CountDownLatch. 1)
            release-provider (java.util.concurrent.Semaphore. 0)
            arm #(agent/arm! {:seon.turn.loop/cluster
                              (handle child (test-support/fork-cluster-ctx child "release-join")
                                      "release-join")
                              :seon.agent/id % :seon.agent/routing routing})]
        ;; Release runs under the parent's custody: it owns its graphs' writes.
        (test-support/transacted!
         child (-> (created-agent-tx child "release-join" "stuck")
                   (into (created-agent-tx child "release-join" "idle"))
                   (conj (provider-backstop-config "release-join" 100))))
        (try
          (with-redefs [prompt/prompt (fn [_database _request]
                                        (.countDown entered)
                                        (.acquireUninterruptibly release-provider)
                                        {:seon.error/message "The prelude stand-in released."})]
            (let [_ (arm "idle")
                  stuck (arm "stuck")]
              (await-idle! (:seon.flow/started stuck))
              (outside-trigger! child "stuck" "release-join-message" "block")
              (async/offer! (:seon.cluster.wake/channel stuck) ::wake)
              (test-support/await-event! entered ::stuck-prelude-entered)
              (let [refused (test-support/refusal-data #(agent/release-context! attempt))]
                (is (some? refused) "a graph that does not exit refuses the release")
                (is (nil? (agent/armed routing "idle")) "every other owned graph was still joined")
                (is (some? (agent/armed routing "stuck")) "the refusing graph keeps its routing")
                (is (string? (:seon.agent/id (db/pull @child [:seon.agent/id] [:seon.agent/id "stuck"])))
                    "the branch connection stays open for diagnosis")
                (is (contains? (registry/roster (:seon.store/store attempt)) (:seon.agent/branch attempt)))
                (is (= :seon.agent/routing
                       (:seon.error/expected (test-support/refusal-data #(arm "idle"))))
                    "a releasing attempt arms no new graph"))
              (.release release-provider 1000)
              (is (.awaitTermination ^java.util.concurrent.ExecutorService (:seon.flow/executor stuck)
                                     5000 java.util.concurrent.TimeUnit/MILLISECONDS))
              (is (nil? (agent/release-context! attempt)) "a later release finishes")
              (is (nil? (agent/armed routing "stuck")))
              (is (not (contains? (registry/roster (:seon.store/store attempt))
                                  (:seon.agent/branch attempt))))))
          (finally
            (.release release-provider 1000)
            (when (contains? (registry/roster (:seon.store/store attempt)) (:seon.agent/branch attempt))
              (agent/release-context! attempt))))))))

(deftest ^{:seon.test/long "Two isolated-attempt arms as the release test above (arming measured 1,495 ms there), then a release that joins both idle graphs."
           :seon.test/long-ms 15000}
  concurrent-arm-and-release-never-deadlock
  (with-connection
    (fn [connection _ctx]
      (let [parent (test-support/execution-handle connection)
            attempt (agent/acquire-context!
                     parent nil {:seon.agent/isolate? true
                                 :seon.cluster.registry/from (db/commit-id @connection)})
            child (:seon.db/connection attempt)
            routing (armory child)
            ;; the cluster's config entity before the handle reads it (4d163db4b)
            _ (test-support/transacted! child [(config-entity "arm-release" {})])
            cluster-handle (handle child (test-support/fork-cluster-ctx child "arm-release") "arm-release")
            start (CountDownLatch. 1)
            arm #(agent/arm! {:seon.turn.loop/cluster cluster-handle
                              :seon.agent/id % :seon.agent/routing routing})
            outcome (fn [agent-id]
                      (future (test-support/await-event! start ::start)
                              (try (arm agent-id)
                                   (catch clojure.lang.ExceptionInfo refusal
                                     (:seon.error/expected (ex-data refusal))))))]
        (test-support/transacted!
         child (into (created-agent-tx child "arm-release" "racer-a")
                     (created-agent-tx child "arm-release" "racer-b")))
        (try
        (let [arms (mapv outcome ["racer-a" "racer-b" "racer-a"])
              release (future (test-support/await-event! start ::start)
                              (agent/release-context! attempt))]
          (.countDown start)
          (let [armed (mapv #(test-support/await-event! % ::arm-settled) arms)]
            (is (nil? (test-support/await-event! release ::released))
                "release completes while arms race it")
            (doseq [result armed]
              (is (or (= :seon.agent/routing result) (map? result))
                  "an arm either acquired its graph or refused the released routing by name")
              (when (map? result)
                (is (.isTerminated ^java.util.concurrent.ExecutorService (:seon.flow/executor result))
                    "every graph an arm started was joined by the release"))))
          (is (= {} (:seon.agent/armed @routing)))
          (is (= :seon.agent/routing (:seon.error/expected (test-support/refusal-data #(arm "racer-b"))))
              "a late arm refuses the released routing by name")
          (is (not (contains? (registry/roster (:seon.store/store attempt))
                              (:seon.agent/branch attempt)))))
          (finally
            (when (contains? (registry/roster (:seon.store/store attempt)) (:seon.agent/branch attempt))
              (agent/release-context! attempt))))))))

(deftest releasing-an-execution-releases-the-executions-acquired-through-it
  ;; Astra roster E2: release detached A's roster while B, acquired through A,
  ;; kept its connection and branch outside every reachable roster.
  (test-support/with-database
   (fn [connection]
     (let [parent (test-support/execution-handle connection)
           store (:seon.store/store parent)
           a (agent/acquire-context! parent nil {:seon.agent/isolate? true
                                                 :seon.cluster.registry/from (db/commit-id @connection)})
           b (agent/acquire-context! a nil {:seon.agent/isolate? true
                                            :seon.cluster.registry/from (db/commit-id @connection)})]
       (try
         (is (identical? (:seon.agent/routing b)
                         (get-in @(:seon.agent/routing a) [:seon.agent/children (:seon.agent/branch b)])))
         (is (nil? (agent/release-context! a)))
         (is (not-any? (registry/roster store) [(:seon.agent/branch a) (:seon.agent/branch b)])
             "both owned branches are retired")
         (is (nil? (registry/active-branch-connection {:seon.store/store store :seon.store/branch (:seon.agent/branch b)}))
             "the descendant's connection is released")
         (is (= ::agent/roster-released
                (::agent/refused (test-support/refusal-data
                                  #(agent/acquire-context! a nil {:seon.agent/branch (:seon.agent/branch a)}))))
             "a released execution claims nothing more")
         (finally
           (doseq [execution [b a]
                   :when (contains? (registry/roster store) (:seon.agent/branch execution))]
             (agent/release-context! execution))))))))

(deftest concurrent-acquisitions-of-one-key-build-once
  ;; The first caller is held just after it reserves the key until the second
  ;; caller has observed that reservation, so the overlap is certain, not timed.
  (test-support/with-database
   (fn [connection]
     (test-support/transacted! connection (created-agent-tx connection "shared-acquisition" "shared"))
     (let [parent (test-support/execution-handle connection)
           roster (:seon.agent/routing parent)
           key [(live-branch connection) "shared"]
           reservations (atom 0)
           both-reserved (CountDownLatch. 1)
           watch-key (Object.)
           _ (add-watch roster watch-key
                        (fn [_ _ _ held]
                          (when (:seon.cluster.agent/acquiring (get-in held [:seon.agent/contexts key]))
                            (case (swap! reservations inc)
                              1 (test-support/await-event! both-reserved ::second-reserved)
                              2 (.countDown both-reserved)
                              nil))))
           acquisitions (try (mapv deref (mapv (fn [_] (future (agent/acquire-context! parent "shared")))
                                               (range 2)))
                             (finally (remove-watch roster watch-key)))]
       (is (identical? (first acquisitions) (second acquisitions))
           "same-key callers share one acquisition")
       (is (identical? (first acquisitions) (get-in @roster [:seon.agent/contexts key]))
           "the settled entry is the shared handle, with no reservation left")))))

(deftest turn-start-has-the-same-declared-loud-completion-backstop
  (with-connection
    (fn [connection ctx]
      (let [agent-id "lost-turn-permit"
            timeout-ms 100]
        (test-support/transacted!
                     connection
                     [{:seon.agent/id agent-id :seon.agent/branch (live-branch connection)}
                      (config-entity
                       "lost-turn-permit"
                       {:seon.config.agent/turn-completion-backstop-ms timeout-ms})])
        (let [cluster (assoc (handle connection ctx "lost-turn-permit")
                             :seon.turn.loop/completion (async/chan)
                             :seon.config.agent/turn-completion-backstop-ms
                             timeout-ms)
              failure
              (test-support/await-event!
               (future
                 (try
                   (seon.turn/step
                    {:seon.agent/id agent-id
                     :seon.turn.loop/cluster cluster}
                    :seon.agent/episode ::wake)
                   nil
                   (catch clojure.lang.ExceptionInfo caught caught)))
               ::missing-permit-refused)]
          (is (string? (:seon.agent/turn-completion-backstop (ex-data failure))))
          ;; the diagnostic's operation is its producer; the waiting
          ;; operation is data (55f2a989a)
          (is (= :seon.agent/turn-start
                 (get-in (ex-data failure)
                         [:seon.error/data :seon.error/operation])))
          (is (= agent-id (:seon.agent/id (ex-data failure))))
          (is (= timeout-ms
                 (:seon.config.agent/turn-completion-backstop-ms
                  (ex-data failure)))))))))

(deftest a-failed-turn-transform-releases-its-permit-and-retires-its-backstop
  (with-connection
    (fn [connection ctx]
      (let [agent-id "quiescent-failed-transform"
            timeout-ms 100
            completion (async/chan 1)
            backstop-state (atom nil)
            armed (async/promise-chan)
            _ (add-watch backstop-state ::armed (fn [_ _ _ backstop] (when backstop (async/offer! armed backstop))))
            ;; the cluster's config entity exists before its handle reads
            ;; `config/effective` of that cluster (4d163db4b)
            _ (test-support/transacted!
               connection
               [{:seon.agent/id agent-id :seon.agent/branch (live-branch connection)}
                (config-entity
                 "quiescent-failed-transform"
                 {:seon.config.agent/turn-completion-backstop-ms timeout-ms})])
            cluster (assoc (handle connection ctx "quiescent-failed-transform")
                           ;; the bound records its fault in this world
                           :seon.env/environment
                           (test-support/environment "quiescent-failed-transform" connection)
                           :seon.turn.loop/completion completion
                           :seon.agent/turn-backstop-state backstop-state
                           :seon.config.agent/turn-completion-backstop-ms
                           timeout-ms)]
        (async/>!! completion ::ready)
        (let [escaped
              (with-redefs [
                            turn/next-agent-work
                            (fn [& _]
                              {:seon.turn.work/situation :resume
                               :seon.agent/id agent-id
                               :seon.turn/id "failed-transform-run"})
                            turn/turn
                            (fn [& _]
                              (throw
                               (ex-info "turn transform escaped"
                                        {:seon.test/turn-escaped true})))]
                (try
                  (seon.turn/step
                   {:seon.agent/id agent-id
                    :seon.turn.loop/cluster cluster}
                   :seon.agent/episode ::wake)
                  nil
                  (catch clojure.lang.ExceptionInfo failure failure)))
              _ (is (= "turn transform escaped" (ex-message escaped)))
              _ (is (= :seon.agent/ready (async/poll! completion))
                    "the failed transform released its lifecycle permit")]
          ;; Since cc1f681b5 the escaped throw is the turn's one fault (it
          ;; rides Flow's fault route), so its bound retires with the
          ;; permit and never fires a second, false fault after it.
          (is (nil? @backstop-state) "the escaped transform's bound is cancelled")
          (let [failure-channel (:seon.agent/failure-channel (async/poll! armed))]
            (is (some? failure-channel) "the transform armed its bound")
            (when failure-channel
              (is (thrown-with-msg? clojure.lang.ExceptionInfo #"closed before"
                                    (test-support/await-event! failure-channel ::backstop-retired
                                                               (constantly true) (* 10 timeout-ms)))
                  "the bound's failure channel closes without a failure")))
          (is (empty? (db/q '[:find ?o :in $ ?agent
                              :where [?o :seon.error.occurrence/message ?m]
                                     [(clojure.string/includes? ?m ?agent)]]
                            (db/db connection) agent-id))
              "no backstop fault follows the escaped transform"))))))

(deftest a-changed-core-entity-refuses-by-name-and-every-agent-still-arms
  ;; Owner ruling 2026-09-23 (AGENTS.md error policy): a core entity the program
  ;; cannot install never throws out of arm!. acquire-context! threw
  ;; "Program acquisition refused." and stopped every agent after it. The
  ;; changed host-bound entity is admitted as `:agent`:
  ;; only an agent-authored entity overrides the loaded program (8cf62cd13).
  ;; WANTED since be96a64ba/c4dbc48c5: that entity refuses by name when called
  ;; in each agent's context, never the acquisition, so no acquisition fault
  ;; is recorded for it.
  (with-connection
    (fn [connection ctx]
      (let [routing (armory connection)
            ;; the indexer marks it host-bound (its own log/warn macro call), as
            ;; branch_execution_test's refuse-by-name regressions use it
            subject 'seon.cluster/warn-low-space!
            refusal-of (fn [agent-ctx]
                         (try (sci/eval-string* agent-ctx (str "(" subject " \"x\")")) nil
                              (catch Exception failure
                                (some #(when (:seon.sci.eval/refused-function (ex-data %)) (ex-data %))
                                      (take-while some? (iterate ex-cause failure))))))]
        (test-support/transacted! connection
                                  [(config-entity "core-fault" {})
                                   (agent-entity connection "fault-a")
                                   (agent-entity connection "fault-b")])
        (test-support/transacted! connection
                                  [(test-support/revision @connection [:seon.fn/sym subject]
                                                          {:seon.fn/doc "A changed host-bound entity."
                                                           :seon.schema.admission/source :agent})])
        (try
          (let [first-agent (arm-one! connection ctx routing "core-fault" "fault-a")
                second-agent (arm-one! connection ctx routing "core-fault" "fault-b")]
            (is (some? (:seon.flow/started first-agent)))
            (is (some? (:seon.flow/started second-agent)) "the next agent arms too")
            (doseq [entry [first-agent second-agent]]
              (is (= subject
                     (:seon.sci.eval/refused-function
                      (refusal-of (get-in entry [:seon.turn.loop/cluster :seon.sci.eval/agent-ctx]))))
                  "the changed host-bound entity refuses by name when called")))
          (finally (disarm-all! routing)))))))

(deftest canonical-agent-handle-supplies-declared-producer-outputs
  (with-connection
    (fn [connection ctx]
      (let [routing (armory connection)]
        (test-support/transacted! connection
                                  [(config-entity "declared-agent-inputs" {})])
        (test-support/transacted! connection
                                  (agent/creation-tx
                                   {:seon.agent/id "declared-agent-inputs"
                                    :seon.agent/branch (live-branch connection)
                                    :seon.cluster/name "declared-agent-inputs"
                                    :seon.ns/name 'my.agents.declared-agent-inputs}))
        (try
          (let [entry (arm-one! connection ctx routing "declared-agent-inputs" "declared-agent-inputs")
                report (await-idle! (:seon.flow/started entry))]
            (is (= "declared-agent-inputs" (:seon.agent/id report)))
            (is (true? (:seon.turn.loop/idle? report)))
            (is (some? (db/entity @connection
                                  [:seon.db.process/id process])))
            (is (satisfies? async.impl/ReadPort
                            (get-in entry [:seon.flow/started :report-chan]))))
          (finally (disarm-all! routing)))))))

(deftest park-wake-test
  (with-connection
    (fn [connection ctx]
      (let [routing (armory connection)
            ledger (atom [])]
        (test-support/with-provider ledger (constantly "(seon.run/complete \"done\")")
          (fn [provider]
            (test-support/transacted! connection
                                    [(agent-entity connection "parked")
                                     (config-entity "park-2026072812"
                                                 (merge {:seon.config.run/max-episode-runs 100}
                                                        (provider-config provider)))])
            (try
              (let [entry (arm-one! connection ctx routing "park-2026072812" "parked")]
                (testing "the arm prime reports its completed idle pass"
                  (is (await-idle! (:seon.flow/started entry)))
                  (is (zero? (count @ledger)))
                  (is (empty? (db/q '[:find ?run :where
                                     [?run :seon.turn/id _]]
                                   @connection))))
                (testing "one committed trigger → exactly one run, one
                provider call"
                  (outside-trigger! connection "parked"
                                    "m-2026072812" "one unit of work")
                  (async/offer! (:seon.cluster.wake/channel entry) ::wake)
                  (is (test-support/await-event!
                       (get-in entry [:seon.flow/started :report-chan])
                       ::closed-turn
                       #(= :closed (:seon.turn.loop/outcome %))))
                  (is (= 1 (count @ledger)))
                  (is (= {"m-2026072812" 1} (answers-by-trigger @connection))))
                (testing "a probe wake reports an idle pass and calls nothing"
                  (async/offer! (:seon.cluster.wake/channel entry)
                                ::probe)
                  (is (await-idle! (:seon.flow/started entry)))
                  (is (= 1 (count @ledger)))))
              (finally
                (disarm-all! routing)))))))))

;;; ---------------------------------------------------------------------------
;;; 3. pause-during-in-flight-call-test — seed 2026072813
;;; ---------------------------------------------------------------------------

(deftest pause-during-in-flight-call-test
  (with-connection
    (fn [connection ctx]
      (let [routing (armory connection)
            ledger (atom [])
            release-provider (CountDownLatch. 1)
            provider-entered (CountDownLatch. 1)
            events (database-events connection)]
        (test-support/with-provider ledger
                       (fn [_request]
                         (.countDown provider-entered)
                         (test-support/await-event! release-provider ::release-provider)
                         "(seon.run/complete \"done\")")
          (fn [provider]
            (test-support/transacted! connection
                                    [(agent-entity connection "pausable")
                                     (config-entity "pause-2026072813"
                                                 (merge {:seon.config.run/max-episode-runs 100}
                                                        (provider-config provider)))])
            (try
              (let [entry (arm-one! connection ctx routing "pause-2026072813" "pausable")
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
                          (flow/ping-proc graph :seon.agent/mailbox)))
                      "the mailbox acknowledged pause before work continued"))
                (testing "the in-flight call completes and its terminal
                facts COMMIT while paused"
                  (.countDown release-provider)
                  (is (test-support/await-event!
                       (:seon.cluster.agent-test/events events)
                       ::plan-frozen
                       #(some? (db/q '[:find ?digest . :where
                                      [_ :seon.turn/reply-size ?digest]]
                                    (:db-after %))))
                      "the plan freeze landed")
                  (is (= :paused
                         (:clojure.core.async.flow/status
                          (flow/ping-proc graph :seon.agent/mailbox)))
                      "and the mailbox then parked paused"))
                (testing "a second trigger committed while paused stays an
                unanswered row — no run opens"
                  (outside-trigger! connection "pausable"
                                    "m-2026072813-b" "queued work")
                  (async/offer! (:seon.cluster.wake/channel entry) ::wake)
                  (let [deliveries (:seon.agent/deliveries (mailbox-ping entry))]
                    (is (= deliveries (:seon.agent/deliveries
                                       (mailbox-ping entry)))
                        "a later acknowledged ping finds no paused delivery"))
                  (is (contains? (set (map :seon.message/id
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
                      "one provider call per run — the pause added none")))
              (finally
                (.countDown release-provider)
                (stop-database-events! connection events)
                (disarm-all! routing)))))))))

;;; ---------------------------------------------------------------------------
;;; 4. episode-cap-refusal-test — seed 2026072814
;;; ---------------------------------------------------------------------------

(defn- opened-run!
  "Open and close one turn with `message-id` as provenance."
  [connection agent-id run-id message-id _at]
  (test-support/transacted! connection
                          {:tx-data (into (turn/open-tx {:seon.turn/id run-id :seon.turn/agent [:seon.agent/id agent-id] :seon.turn/trigger [:seon.message/id message-id] :seon.turn/opened-tx "datomic.tx"})
                                          [])})
  (let [namespace-name (symbol (str "my.agents." agent-id))
        result (db/transact!
                connection
                (turn/plan-tx
                 {:seon.turn/id run-id
                  :seon.db.process/id process
                  :seon.turn/starting-ns [:seon.ns/name namespace-name]
                  :seon.turn/reply "(identity nil)"
                  :seon.turn/sources
                  [{:seon.cluster.eval/source "(identity nil)"
                    :seon.ns/name namespace-name}]}))]
    (is (some? (:db-after result))))
  (test-support/transacted! connection
                          (turn/close-tx {:seon.turn/id run-id :seon.db.process/id process :seon.turn/closed-tx "datomic.tx"})))

(deftest episode-cap-refusal-test
  ;; seed 2026072814 — the derivation is asserted DIRECTLY (the cited
  ;; query), because the refusal's whole contract is that no consumer
  ;; ever sees a decision: the work simply does not derive.
  (with-connection
    (fn [connection _ctx]
      (let [request {:seon.agent/id "alice"
                     :seon.db.process/id process
                     :seon.turn.work/now (Date.)}]
        (test-support/transacted! connection
                                [(agent-entity connection "alice")
                                 (agent-entity connection "bob")
                                 ;; Since fe5dcc072 the member's world carries its
                                 ;; cluster's config; the cap goes on that one config
                                 ;; (a second config entity left the dial ambiguous).
                                 (config-entity (or (db/q '[:find ?cluster . :where [_ :seon.config/cluster ?cluster]]
                                                          @connection)
                                                    "cap-2026072814")
                                             {:seon.config.run/max-episode-runs 3})])
        ;; episode 1: a human asks
        (outside-trigger! connection "alice" "h1" "human asks")
        (opened-run! connection "alice" "e1" "h1" now)
        (is (= 1 (turn/episode-runs @connection "alice")))
        ;; The recorder attributes population activity to its sending agent.
        (test-support/transacted! connection
                                [{:seon.message/id "r1" :seon.message/from [:seon.agent/id "alice"] :seon.message/to [:seon.agent/id "alice"] :seon.message/about "h1" :seon.message/content "about a fault"}])
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
          (is (= ["b2"] (mapv :seon.message/id
                              (turn/deferred-triggers @connection
                                                      "alice")))))
        (testing "the derived problems family and prompt line are
        present under `get`, from facts alone"
          (let [found (problems/problems
                       @connection
                       {})
                deferred (get found :seon.problems/deferred-agents)]
            (is (= [{:seon.agent/id "alice"
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
            (is (= "b2" (:seon.message/id derived))
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
            routing (armory connection)
            ;; the cluster's config entity exists before its handle reads
            ;; `config/effective` of that cluster (4d163db4b)
            _ (test-support/transacted!
               connection
               [{:seon.agent/id "reloaded" :seon.agent/branch (live-branch connection)}
                (config-entity "hot-2026072815"
                            {:seon.config.run/max-episode-runs 100})])
            base (handle connection ctx "hot-2026072815")
            v2-ran (atom 0)
            wrap (fn [step]
                   (fn
                     ([] (step))
                     ([args] (step args))
                     ([state transition] (step state transition))
                     ([state input message]
                      (swap! v2-ran inc)
                      (step state input message))))]
        (try
          (let [entry (arm-one! connection ctx routing "hot-2026072815" "reloaded")
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
                          {:seon.agent/mailbox
                           {:proc (flow/process
                                   #'agent/mailbox-step
                                   {:workload :io})
                            :args {:seon.cluster.wake/channel
                                   control-channel}}
                           :seon.agent/turn
                           {:proc (flow/process
                                   original
                                   {:workload :io})
                            :args {:seon.turn.loop/cluster
                                   control-handle
                                   :seon.agent/id "reloaded"}
                            :chan-opts {:seon.agent/episode
                                        {:buf-or-n
                                         (async/sliding-buffer 1)}}}}
                          :conns [[[:seon.agent/mailbox :seon.agent/episode]
                                   [:seon.agent/turn :seon.agent/episode]]]})
                control-started (flow/start control)
                _ (flow/resume control)]
            (try
              (is (await-idle! (:seon.flow/started entry)))
              (alter-var-root #'seon.turn/step (constantly
                                                 (wrap original)))
              (testing "the armed graph's next pass observably runs v2,
              with no rebuild"
                (is (zero? @v2-ran))
                (async/offer! (:seon.cluster.wake/channel entry) ::wake)
                (is (await-idle! (:seon.flow/started entry)))
                (is (pos? @v2-ran)
                    "the completed var-built pass ran v2"))
              (testing "the fn-value control proc still runs v1"
                (let [before @v2-ran]
                  (async/offer! control-channel ::wake)
                  (is (await-idle! control-started)
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
            routing (armory connection)
            ledger (atom [])
]
        (test-support/with-provider ledger (constantly "(seon.run/complete \"done\")")
          (fn [provider]
            (test-support/transacted! connection
                                    [(agent-entity connection "midfold")
                                     (agent-entity connection "waiting")
                                     (config-entity "restamp-2026072816"
                                                 (merge {:seon.config.run/max-episode-runs 100}
                                                        (provider-config provider)))])
            ;; the dead process's history: open+claim on an outside
            ;; trigger, a three-form plan, form 0 settled, form 1 STARTED
            ;; and never settled, and a capability-shaped form 2 that had
            ;; never started — killed mid-fold
            (outside-trigger! connection "midfold" "m-dead" "count things")
            (test-support/transacted! connection
                                    {:tx-data (into (turn/open-tx
                                                     {:seon.turn/id "run-dead" :seon.turn/agent [:seon.agent/id "midfold"] :seon.turn/trigger [:seon.message/id "m-dead"] :seon.turn/opened-tx "datomic.tx"})
                                                    [])})
            (test-support/transacted! connection
                                    (turn/plan-tx {:seon.turn/id "run-dead" :seon.db.process/id dead :seon.turn/sources [{:seon.cluster.eval/source "(+ 1 2)"}
                                                   {:seon.cluster.eval/source
                                                    "(+ 3 4)"}
                                                   {:seon.cluster.eval/source
                                                    "(seon.cluster.message/send \"waiting\" \"must not run\")"}]}))
            ;; ONE ENTITY PER (run, ordinal): the freeze minted all three
            ;; evaluations with their start instant, exactly as the turn's one
            ;; intent transaction does. Ordinal 0 settles; 1 and 2 stay running.
            (test-support/transacted! connection
                                    (turn/receipt-settle-tx {:seon.turn/id "run-dead"
                                                            :seon.cluster.eval/ordinal 0
                                                            :seon.eval/shown
                                                            "3"}))
            ;; Messages committed before the crash and never answered remain
            ;; triggers. One belongs to the interrupted agent itself, proving
            ;; recovery ends only the old WORK rather than dropping mail.
            (outside-trigger! connection "midfold" "m-unanswered"
                              "still needed after crash")
            (outside-trigger! connection "waiting" "m-waiting" "still here")
            (try
              (let [events (database-events connection)
                    ;; BOOT-SHAPE RE-ARM: recover, then re-stamp + prime. The
                    ;; listener stands before either action, so terminal facts
                    ;; cannot cross a read/take gap and pending work cannot be
                    ;; misclassified by a test-local clock.
                    db
                    (try
                      (test-support/transacted! connection
                                                (turn/recover-tx
                                                 {:seon.turn/id "run-dead"

                                                  :seon.turn/now (Date.)}))
                      (doseq [agent-id ["midfold" "waiting"]]
                        (arm-one! connection ctx routing "restamp-2026072816" agent-id))
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
                                    [?run :seon.turn/closed-tx ?at]]
                                  db)))
                  (is (nil? (db/q '[:find ?run .
                                   :where
                                   [?agent :seon.agent/id "midfold"]
                                   [?run :seon.turn/agent ?agent]
                                   (not [?run :seon.turn/closed-tx])]
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
                                    :seon.eval/shown ?result]]
                                 db)))
                  ;; ONE ENTITY PER (run, ordinal): the capability-shaped
                  ;; suffix HAS its frozen evaluation — the intent transaction
                  ;; made it durable — and recovery settled it with no result
                  ;; and no error, only the interruption stamp. "It never ran"
                  ;; is the absence of a RESULT, not the absence of the entity.
                  (let [suffix (db/q '[:find (pull ?receipt [*]) .
                                       :where
                                       [?run :seon.turn/id "run-dead"]
                                       [?receipt :seon.cluster.eval/run ?run]
                                       [?receipt :seon.cluster.eval/ordinal 2]]
                                     db)]
                    (is (some? suffix)
                        "the frozen suffix is durable intent")
                    (is (nil? (:seon.eval/shown suffix))
                        "the unstarted capability-shaped suffix settled no result")
                    (is (nil? (:seon.cluster.eval/error suffix))
                        "and recorded no evaluation error")))
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
                        "the surviving run render narrates the derived cut"))))
              (finally
                (disarm-all! routing)))))))))

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
  pre-armed; the armer does all arming. `reply` is the local provider's
  answer to each request document."
  {:malli/schema [:function
                  [:=> [:cat [:sequential :keyword]] :map]
                  [:=> [:cat [:sequential :keyword] [:fn ifn?]] :map]]}
  ([operations]
   (routing-trial operations (constantly "(seon.run/complete \"done\")")))
  ([operations reply]
   (with-connection
     (fn [connection ctx]
       (test-support/with-provider (atom []) reply
         (fn [provider]
          (test-support/seed-cluster! connection "route-trial")
          ;; The local provider is this trial's provider; its dials also retract
          ;; the machine's no-provider dial, under which no turn reaches it.
          (test-support/transacted! connection
                                    [(config-entity "route-trial"
                                                 (merge {:seon.config.run/max-episode-runs 100}
                                                        (provider-config provider)))])
          (let [routing (armory connection)
                armer-channel (async/chan (async/sliding-buffer 1))
                armer-handle (assoc (handle connection ctx "route-trial")
                                    :seon.cluster/name "route-trial"
                                    :seon.cluster.wake/channel armer-channel)
                armer (start-armer! ctx armer-handle routing)
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
                           (set (keys (:seon.agent/armed state))))
                    (async/offer! armed-event state)))]
            (try
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
                            (:seon.agent/fault-channel @routing)
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
                        (do (test-support/transacted!
                                         connection
                                         {:tx-data
                                          [[:db.fn/call
                                            #'cluster/ensure-entity-call
                                            process
                                            (Date.)
                                            {:seon.agent/id agent-id
                                             :seon.cluster/name "route-trial"
                                             :seon.ns/name
                                             (symbol (str "my.agents." agent-id))}]
                                          {:seon.message/id (str "rm-" index) :seon.message/to {:seon.agent/id agent-id} :seon.message/content "hello, newborn"}]})
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
                                                       (db/q '[:find ?id :where [_ :seon.agent/id ?id]] @connection)
                                                       :seon.test/armed
                                                       (vec (keys (:seon.agent/armed @routing)))
                                                       :seon.test/created @created
                                                       :seon.test/armer
                                                       (flow/ping-proc (:seon.flow/graph armer) :seon.agent/armer)
                                                       :seon.test/faults
                                                       (db/q '[:find [?m ...] :where [_ :seon.error.occurrence/message ?m]]
                                                             @connection)}
                                                      failure)))))
                    (throw
                     (ex-info "The routing watch closed before every agent armed."
                              {})))
                  (let [db
                        (await-database-state!
                         connection
                         (:seon.cluster.agent-test/events events)
                         #(quiescent? % agent-ids))
                        answers (answers-by-trigger db)]
                    {::subjects-present? (boolean (seq agent-ids))
                     :settled? true
                     :armed-once?
                     (= (count agent-ids)
                        (count (:seon.agent/armed @routing)))
                     :no-unarmed-with-triggers?
                     (every? (fn [agent-id]
                               (or (contains? (:seon.agent/armed
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
                    (stop-database-events! connection events))))
              (finally
                (wake/unlisten! {:seon.cluster.wake/connection connection
                                 :seon.cluster.wake/key ::route-trial})
                (stop-armer! armer `routing-trial)
                (disarm-all! routing)
                (async/close! armer-channel))))))))))

(deftest wake-routing-conservation-property
  ;; BOUND: a passing run is 12 trials. A failing trial costs at least the
  ;; 20 s event backstop (test-support/event-backstop-seconds), and
  ;; test.check 1.1.1's shrink loop has no time limit, so a red here repeats
  ;; that cost per shrink step until the request's check limit leaves the
  ;; body live (census 2026-09-25c: 115.9 s remainder, thread 1974).
  (let [result
        (tc/quick-check
         12
         (prop/for-all
          [operations (gen/fmap
                       #(into [:create-and-message] %)
                       (gen/vector
                        (gen/elements [:create :create-and-message :message])
                        0 5))]
          (let [verdict (routing-trial operations)]
            (every? val verdict)))
         :seed 2026072819)]
    (test-support/assert-check! result "Routing conserves every generated delivery.")))

(deftest routing-proof-rejects-an-empty-production-subject
  (let [verdict (routing-trial [:message])]
    (is (false? (::subjects-present? verdict)))
    (is (false? (every? val verdict))
        "retained counterexample: a message operation before any agent exists")))

(deftest routing-conservation-waits-for-terminal-evidence
  ;; A held reply, never a redefinition, so a trial that outlives the finally's
  ;; bounded await leaks no JVM-global Var (census 2026-09-25d N2).
  (let [provider-entered (CountDownLatch. 1)
        release-provider (CountDownLatch. 1)
        held-reply
        (fn [_request]
          (.countDown provider-entered)
          (test-support/await-event! release-provider ::release-provider)
          "(seon.run/complete \"done\")")
        verdict (future (routing-trial [:create-and-message] held-reply))]
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
  ;; seed 2026072820 — the ruled `my.turn/wait` revision folded into F1
  ;; (README owner-decisions #4): the wait's terminal transaction
  ;; settles the receipt AND closes the run in ONE commit, so the
  ;; unheld-open-planned intermediate state — the P1 feeder — exists at
  ;; NO basis; the agent's next trigger opens a NEW run.
  (with-connection
    (fn [connection ctx]
      (let [routing (armory connection)
            ledger (atom [])]
        (test-support/with-provider ledger (constantly "(seon.run/wait \"need input\")")
          (fn [provider]
            (test-support/transacted! connection
                                    [(agent-entity connection "waiter")
                                     (config-entity "wait-2026072820"
                                                 (merge {:seon.config.run/max-episode-runs 100}
                                                        (provider-config provider)))])
            (try
              (let [events (database-events connection)
                    db
                    (try
                      (arm-one! connection ctx routing "wait-2026072820" "waiter")
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
                                  [?run :seon.turn/id ?id]
                                  [?run :seon.turn/attempts _]]
                                db)
                    settle-tx (db/q '[:find ?tx . :in $ ?run-id :where
                                     [?run :seon.turn/id ?run-id]
                                     [?receipt :seon.cluster.eval/run ?run]
                                     [?receipt
                                      :seon.eval/shown _ ?tx]]
                                   db run-id)
                    close-tx (db/q '[:find ?tx . :in $ ?run-id :where
                                    [?run :seon.turn/id ?run-id]
                                    [?run :seon.turn/closed-tx _ ?tx]]
                                  db run-id)]
                (is (quiescent? db ["waiter"]))
                (testing "settle and close share ONE transaction"
                  (is (some? settle-tx))
                  (is (= settle-tx close-tx)))

                (testing "the note survives in the receipt"
                  (is (str/includes?
                       (db/q '[:find ?shown . :in $ ?run-id :where
                              [?run :seon.turn/id ?run-id]
                              [?evaluation :seon.cluster.eval/run ?run]
                              [?evaluation :seon.eval/shown ?shown]]
                            db run-id)
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
                                        [?run :seon.turn/attempts _]]
                                      terminal-db)
                                 0)))
                    (is (= {"m-wait" 1 "m-next" 1}
                           (answers-by-trigger terminal-db))))))
              (finally
                (disarm-all! routing)))))))))

(deftest ^{:seon/tasks #{'seon.turn/failed-run-left-unsettled}
           :seon.test/long "Five ordinary agent runs through evaluate-source!, each a run transaction, one Flow turn and its settlement (184-229 ms warm each on default, 2026-09-26, evaluate_source_test), plus arming; the alias batch also analyzes a declaration."
           :seon.test/long-ms 20000}
  every-run-reaches-one-terminal-and-the-next-run-works
  ;; THE CLASS (audit 2026-09-26 M4 L1/L2): a form evaluated, then a later
  ;; stage threw, and the run stayed open, so every later run of the agent
  ;; refused `agent-already-running`. `(sorted-map 'a 1)` did it: the
  ;; disposition classifier looked a keyword up in a symbol-keyed sorted map.
  ;; Each run here must close with its forms settled, and the next run works.
  (let [target (test-support/execution-handle nil)
        connection (:seon.db/connection target)
        cluster-name (:seon.cluster/name target)
        agent-id (str "terminal-" (id/id))
        environment (test-support/environment cluster-name connection)
        ctx (test-support/fork-cluster-ctx connection cluster-name)
        routing (test-support/attempt-routing connection)
        faults (async/chan (async/sliding-buffer 16))]
    (with-open [launcher (test-support/closeable
                          (seon.flow/start-work-launcher!
                           {:seon.env/environment environment
                            :seon.flow/configuration (select-keys (config/effective (db/db connection) cluster-name)
                                                                  seon.flow/flow-workload-attributes)})
                          seon.flow/stop-work-launcher!)]
      (let [handle (test-support/cluster-handle
                    {:seon.env/environment environment :seon.db/connection connection :seon.cluster/name cluster-name
                     :seon.sci.eval/ctx ctx :seon.flow/work-launcher @launcher
                     :seon.flow/executor (cluster/projection-executor (:seon.sci.eval/projection-state ctx))
                     :seon.db.process/id cluster/boot-process-identity})
            request {:seon.turn.loop/cluster handle :seon.agent/routing routing :seon.agent/id agent-id}
            settle-run (fn [text]
                   (let [result (agent/evaluate-source! (assoc request :seon.agent/segmenter 'seon.turn/code-sources
                                                               :seon.cluster.reply/text text))
                         run-id (:seon.turn/id result)
                         ;; the run on the reply's own value (answered when applied)
                         database (or (:seon.db/db result) (db/db connection))
                         receipts (when run-id
                                    (sort-by :seon.cluster.eval/ordinal
                                             (db/q '[:find [(pull ?e [:seon.cluster.eval/ordinal :seon.eval/shown
                                                                      :seon.cluster.eval/error :seon.cluster.eval/interrupted-at]) ...]
                                                     :in $ ?id
                                                     :where [?r :seon.turn/id ?id] [?e :seon.cluster.eval/run ?r]]
                                                   database run-id)))]
                     (is (string? run-id))
                     (is (some? (:seon.turn/closed-tx (db/pull database [:seon.turn/closed-tx] [:seon.turn/id run-id])))
                         "the run is terminal")
                     (is (every? #(some % [:seon.eval/shown :seon.cluster.eval/error :seon.cluster.eval/interrupted-at])
                                 receipts)
                         "every form carries a terminal fact")
                     (mapv :seon.eval/shown receipts)))]
        (swap! routing assoc :seon.agent/fault-channel faults)
        (test-support/transacted! connection (test-support/agent-tx @connection agent-id))
        (agent/arm! request)
        (try
          (testing "a sorted map is a value: it prints, and classification never compares its keys"
            (is (= ["{a 1}"] (settle-run "(sorted-map 'a 1)")))
            (is (= ["{\"a\" 1}"] (settle-run "(sorted-map \"a\" 1)"))))
          (testing "an evaluation error settles on its form"
            (let [[shown] (settle-run "(/ 1 0)")]
              (is (str/includes? (str shown) "Divide by zero"))))
          (testing "an alias required earlier in the batch, then a declaration using it, reaches a terminal"
            (settle-run "(require '[clojure.string :as s])\n(defn shout {:malli/schema [:=> [:cat :string] :string]} [x] (s/upper-case x))"))
          (testing "after all of them, the agent's next run works"
            (is (= ["2"] (settle-run "(+ 1 1)"))))
          (finally
            (agent/disarm! request)
            (doseq [channel [faults (:seon.cluster.wake/channel handle)
                             (:seon.render/context-channel handle) (:seon.turn.loop/completion handle)]]
              (async/close! channel))))))))
