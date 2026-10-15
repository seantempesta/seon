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
            [seon.context :as context]
            [seon.ref :as ref]

            [seon.cluster.wake :as wake]

            [seon.config :as config]
            [seon.flow :as seon.flow]
            [seon.problems :as problems]
            [seon.profile :as profile]
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
  "The cluster handle `start-flow!` declares, from `test-support/cluster-handle`.

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
   ;; replaced per agent by start-flow! — present so the handle validates
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

(defn- start-db<->flow!
  "db<->flow graph on its own executor, as `seon.cluster.host` starts
  it, so `stop-db<->flow!` joins it at that executor's termination."
  [ctx db<->flow-handle routing]
  (seon.flow/start-graph!
   {:seon.flow/projection-state (:seon.sci.eval/projection-state ctx)
    :seon.flow/graph-definition
    {:procs {:seon.flow/db<->flow
             ;; the production seam: its loop failures are recorded by the
             ;; proc's own fault port
             {:proc (seon.flow/var-process
                     #'agent/db<->flow :io
                     {:seon.env/environment @test-environment
                      :seon.turn.loop/cluster db<->flow-handle
                      :seon.agent/routing routing})}}
     :conns []}}))

(defn- stop-db<->flow!
  "Stop db<->flow and join it: `flow/stop` is a command, and the graph's exit
  is its executor's termination (`seon.flow/join-graph!`). No proc publishes a
  completion event on stop since 4b6834f7d."
  [db<->flow operation]
  (flow/stop (:seon.flow/graph db<->flow))
  (is (nil? (seon.flow/join-graph!
             {:seon.flow/executor (:seon.flow/executor db<->flow)
              :seon.await/bound
              {:seon.await/config-attribute :seon.config.agent/turn-completion-backstop-ms
               :seon.await/config-value 5000
               :seon.await/deadline-nanos (+ (System/nanoTime) (* 5000 1000000))}
              :seon.await/diagnostic {:seon.error/layer ::db<->flow
                                      :seon.error/operation operation
                                      :seon.error/expected ::db<->flow-exited}}))
      "db<->flow graph exits within its bound"))

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

(defn- start-one!
  [connection ctx routing cluster-name agent-id]
  (agent/start-flow! {:seon.turn.loop/cluster (handle connection ctx cluster-name)
               :seon.agent/id agent-id
               :seon.agent/routing routing}))

(defn- created-agent-tx
  "The canonical creation of `agent-id` for `cluster-name`, live on the fixture branch."
  [connection cluster-name agent-id]
  (agent/creation-tx {:seon.agent/id agent-id
                      :seon.agent/branch (live-branch connection)
                      :seon.ns/name (symbol (str "my.agents." agent-id))
                      :seon.cluster/name cluster-name}))


(defn- create-working-agents!
  "Create `agent-ids` in `cluster-name`, whose config carries `overlay`, and assign each a task with a red
  proof: a model turn needs an assignment (`seon.turn/next-agent-work`)."
  [connection cluster-name overlay agent-ids]
  (test-support/transacted! connection
                            (into [(config-entity cluster-name overlay)]
                                  (mapcat #(created-agent-tx connection cluster-name %))
                                  agent-ids))
  (doseq [agent-id agent-ids]
    (test-support/assign-task! connection agent-id)))

(defn- agent-entity
  [connection agent-id]
  {:seon.agent/id agent-id
   :seon.agent/branch (live-branch connection)
   :seon.agent/namespace
   (test-support/namespace-entity @connection (symbol (str "my.agents." agent-id)))})

(defn- stop-all-flows!
  [routing]
  (doseq [agent-id (sort (keys (:seon.agent/started @routing)))]
    (agent/stop-flow! {:seon.agent/id agent-id
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
          (start-one! connection ctx routing "contract-probe" "contract-probe")
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
            (stop-all-flows! routing)))))))

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
          (start-one! connection ctx routing "alias-batch" "alias-batch")
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
            (stop-all-flows! routing)))))))

(deftest system-source-submission-uses-the-ordinary-durable-run
  (with-connection
    (fn [connection ctx]
      (let [routing (armory connection)
            plan-channel (async/chan (async/sliding-buffer 1))
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
                     :seon.cluster.wake/channel plan-channel)]
          (is (nil? (agent/started routing "source-agent"))
              "the source agent is unstarted before db<->flow starts")
          (let [db<->flow (start-db<->flow! ctx cluster-handle routing)]
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
                  (is (some? (agent/started routing "source-agent"))
                      "submission starts an unstarted agent (start-flow! is idempotent)")
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
              (stop-db<->flow! db<->flow `system-source-submission-uses-the-ordinary-durable-run)
              (stop-all-flows! routing)
              (async/close! plan-channel))))
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
            (finally (stop-all-flows! routing))))))))

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
            plan-channel (async/chan (async/sliding-buffer 1))
            ledger (atom [])]
        (test-support/with-provider ledger (constantly "(seon.run/complete \"done\")")
          (fn [provider]
            (create-working-agents! connection "trial"
                                    (merge {:seon.config.run/max-episode-runs 100}
                                           (provider-config provider))
                                    agent-ids)
            (try
              (doseq [agent-id agent-ids]
                (start-one! connection ctx routing "trial" agent-id))
              (wake/route! {:seon.cluster.wake/connection connection
                            :seon.cluster.wake/channels
                            (fn [] (agent/channels routing))
                            :seon.cluster.wake/fenced?
                            (fn [agent-eid channel]
                              (agent/fenced-route? routing agent-eid channel))
                            :seon.flow/plan-channel plan-channel
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
                (stop-all-flows! routing)))))))))

(deftest ^{:seon.test/long "Twelve generated trials, each its own fixture branch, render graph, work launcher and one to three assigned agent graphs answering one or two wakes: 8,001 ms in one check JVM (2026-10-12), about 0.67 s per trial."
           :seon.test/long-ms 12000}
  n-agent-parallel-turns-property
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
              "an unstarted agent has no fence")
          (let [entry (start-one! connection ctx routing "agent-a" "agent-a")
                eid (:seon.agent/eid entry)
                mailbox (:seon.cluster.wake/channel entry)
                stale (async/chan)]
            (is (false? (agent/fenced? routing "agent-a")))
            (is (false? (agent/fenced-route? routing eid mailbox)))
            (async/close! mailbox)
            (is (true? (agent/fenced? routing "agent-a"))
                "started + closed in place is the management view")
            (is (true? (agent/fenced-route? routing eid mailbox))
                "the exact current route is recognizable by the router")
            (async/close! stale)
            (is (false? (agent/fenced-route? routing eid stale))
                "closedness alone does not bless a stale route"))
          (finally
            (stop-all-flows! routing)))
        (is (false? (agent/fenced? routing "agent-a"))
            "ordinary teardown drops the entry before close")))))

(deftest stop-flow-drops-the-route-before-closing-it
  (with-connection
    (fn [connection ctx]
      (let [routing (armory connection)
            _ (test-support/transacted! connection [(config-entity "agent-a" {}) {:seon.agent/id "agent-a" :seon.agent/branch (live-branch connection)}])
            entry (start-one! connection ctx routing "agent-a" "agent-a")
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
                                   (some? (agent/started routing "agent-a"))}))
                        (real-close candidate))]
          (agent/stop-flow! {:seon.agent/id "agent-a"
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
    ;; observed bound is stop's declared one. The machine's manifest may
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

(deftest ^{:seon.test/long "Measured 7,431-8,373 ms on a HEAD snapshot (2026-09-22): starting 1,495 ms, the turn's prelude before prompt acquisition 5,400 ms (docs/seon/issues/a-turn-spends-seconds-before-its-provider-call.md), the interrupted stop itself 1,405 ms; the stop now runs inside one nested fixture branch: isolated acquisition 51-59 ms plus release 8-9 ms (three warm runs on default, 2026-09-25)."
           :seon.test/long-ms 12000
           :seon/tasks #{'seon.turn/a-stop-during-model-context-assembly-records-errors}}
  stop-flow-interrupts-an-in-flight-turn-and-records-it-interrupted
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
        (test-support/assign-task! connection agent-id)
        (try
          (with-redefs
            [context/model-context
             (fn [_database _request _agent-id]
               (.countDown prelude-entered)
               (try
                 (.await never-released)
                 {:seon.error/message "The prelude stand-in was released."}
                 (catch InterruptedException failure
                   (deliver interrupted (.getName (Thread/currentThread)))
                   ;; as Datahike's async layer delivers it: wrapped in an
                   ;; ex-info, which is still the stop's interrupt
                   (throw (ex-info (str failure) {} failure)))))]
            (let [entry (start-one! connection ctx routing
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
                             result (agent/stop-flow! {:seon.agent/id agent-id
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
                (is (nil? (agent/started routing agent-id)))
                (is (async.impl/closed?
                     (:seon.cluster.wake/channel entry))))))
          (finally
            (.countDown never-released)
            (stop-all-flows! routing)))))))

(deftest ^{:seon.test/long "Measured 7,898 ms on a HEAD snapshot (2026-09-22), dominated by the turn's prelude before prompt acquisition (docs/seon/issues/a-turn-spends-seconds-before-its-provider-call.md); the declared stop bound here is 100 ms."
           :seon.test/long-ms 12000}
  stop-flow-has-a-declared-loud-turn-completion-backstop
  ;; Host work that ignores the interrupt keeps its thread; stop then
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
        (test-support/assign-task! connection agent-id)
        (try
          (with-redefs
            [context/model-context
             (fn [_database _request _agent-id]
               (.countDown provider-entered)
               (.acquireUninterruptibly release-provider)
               {:seon.error/message "The prelude stand-in released."})]
            (let [entry (start-one! connection ctx routing
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
                        (agent/stop-flow!
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
                    ;; receipt (0cbf4336f deleted the :panic policy); stop-flow!
                    ;; throws the declared failure itself
                    failure thrown]
                (is (nil? (:seon.fault/recorded (ex-data thrown))))
                (is (= :seon.agent/stop (get-in (ex-data failure) [:seon.error/data :seon.error/operation])))
                (is (string? (:seon.agent/turn-completion-backstop (ex-data failure))))
                (is (= turn-completion-backstop-ms
                       (:seon.config.agent/turn-completion-backstop-ms
                        (ex-data failure))))
                (is (= agent-id
                       (:seon.agent/id (ex-data failure))))
                (is (= run-id
                       (:seon.turn/id (ex-data failure))))
                (is (some? (agent/started routing agent-id))
                    "a fired backstop fails closed and leaves stop retryable")
                (is (= :seon.agent/graph-exited
                       (:seon.error/expected
                        (test-support/refusal-data
                         #(start-one! connection ctx routing
                                    "provider-backstop" agent-id))))
                    "a restart refuses by name while the previous graph lives"))
              ;; enough permits that no later turn of the restart blocks
              (.release release-provider 1000)
              (is (.awaitTermination ^java.util.concurrent.ExecutorService
                                     (:seon.flow/executor entry)
                                     5000 java.util.concurrent.TimeUnit/MILLISECONDS)
                  "the released proc loops exit")
              (let [restarted (start-one! connection ctx routing
                                        "provider-backstop" agent-id)]
                (is (not (identical? entry restarted))
                    "after exit a restart finishes teardown and starts a fresh graph"))
              (agent/stop-flow! {:seon.agent/id agent-id
                              :seon.agent/routing routing})
              (is (nil? (agent/started routing agent-id)))
              (is (not (contains? (agent/channels routing)
                                  (:seon.agent/eid entry))))
              (is (async.impl/closed?
                   (:seon.cluster.wake/channel entry)))
              (is (async.impl/closed?
                   (:seon.turn.loop/completion entry)))
              (is (nil? (agent/stop-flow!
                         {:seon.agent/id agent-id
                          :seon.agent/routing routing})))
              (is (nil? (agent/started routing agent-id))
                  "a successful retry removes the route exactly once")))
          (finally
            (.release release-provider)
            (stop-all-flows! routing)))))))

(deftest ^{:seon.test/long "Two starts plus one blocked turn prelude, as the stop backstop test above (7,898 ms measured there); the release's own declared join bound is 100 ms."
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
            start #(agent/start-flow! {:seon.turn.loop/cluster
                              (handle child (test-support/fork-cluster-ctx child "release-join")
                                      "release-join")
                              :seon.agent/id % :seon.agent/routing routing})]
        ;; Release runs under the parent's custody: it owns its graphs' writes.
        (test-support/transacted!
         child (-> (created-agent-tx child "release-join" "stuck")
                   (into (created-agent-tx child "release-join" "idle"))
                   (conj (provider-backstop-config "release-join" 100))))
        (test-support/assign-task! child "stuck")
        (try
          (with-redefs [context/model-context (fn [_database _request _agent-id]
                                        (.countDown entered)
                                        (.acquireUninterruptibly release-provider)
                                        {:seon.error/message "The prelude stand-in released."})]
            (let [_ (start "idle")
                  stuck (start "stuck")]
              (await-idle! (:seon.flow/started stuck))
              (outside-trigger! child "stuck" "release-join-message" "block")
              (async/offer! (:seon.cluster.wake/channel stuck) ::wake)
              (test-support/await-event! entered ::stuck-prelude-entered)
              (let [refused (test-support/refusal-data #(agent/release-context! attempt))]
                (is (some? refused) "a graph that does not exit refuses the release")
                (is (nil? (agent/started routing "idle")) "every other owned graph was still joined")
                (is (some? (agent/started routing "stuck")) "the refusing graph keeps its routing")
                (is (string? (:seon.agent/id (db/pull @child [:seon.agent/id] [:seon.agent/id "stuck"])))
                    "the branch connection stays open for diagnosis")
                (is (contains? (registry/branches (:seon.store/store attempt)) (:seon.agent/branch attempt)))
                (is (= :seon.agent/routing
                       (:seon.error/expected (test-support/refusal-data #(start "idle"))))
                    "a releasing attempt starts no new graph"))
              (.release release-provider 1000)
              (is (.awaitTermination ^java.util.concurrent.ExecutorService (:seon.flow/executor stuck)
                                     5000 java.util.concurrent.TimeUnit/MILLISECONDS))
              (is (nil? (agent/release-context! attempt)) "a later release finishes")
              (is (nil? (agent/started routing "stuck")))
              (is (not (contains? (registry/branches (:seon.store/store attempt))
                                  (:seon.agent/branch attempt))))))
          (finally
            (.release release-provider 1000)
            (when (contains? (registry/branches (:seon.store/store attempt)) (:seon.agent/branch attempt))
              (agent/release-context! attempt))))))))

(deftest ^{:seon.test/long "Two isolated-attempt starts as the release test above (starting measured 1,495 ms there), then a release that joins both idle graphs."
           :seon.test/long-ms 15000}
  concurrent-start-and-release-never-deadlock
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
            begin (CountDownLatch. 1)
            start #(agent/start-flow! {:seon.turn.loop/cluster cluster-handle
                              :seon.agent/id % :seon.agent/routing routing})
            outcome (fn [agent-id]
                      (future (test-support/await-event! begin ::start)
                              (try (start agent-id)
                                   (catch clojure.lang.ExceptionInfo refusal
                                     (:seon.error/expected (ex-data refusal))))))]
        (test-support/transacted!
         child (into (created-agent-tx child "arm-release" "racer-a")
                     (created-agent-tx child "arm-release" "racer-b")))
        (try
        (let [starts (mapv outcome ["racer-a" "racer-b" "racer-a"])
              release (future (test-support/await-event! begin ::start)
                              (agent/release-context! attempt))]
          (.countDown begin)
          (let [started (mapv #(test-support/await-event! % ::arm-settled) starts)]
            (is (nil? (test-support/await-event! release ::released))
                "release completes while starts race it")
            (doseq [result started]
              (is (or (= :seon.agent/routing result) (map? result))
                  "an start either acquired its graph or refused the released routing by name")
              (when (map? result)
                (is (.isTerminated ^java.util.concurrent.ExecutorService (:seon.flow/executor result))
                    "every graph an start started was joined by the release"))))
          (is (= {} (:seon.agent/started @routing)))
          (is (= :seon.agent/routing (:seon.error/expected (test-support/refusal-data #(start "racer-b"))))
              "a late start refuses the released routing by name")
          (is (not (contains? (registry/branches (:seon.store/store attempt))
                              (:seon.agent/branch attempt)))))
          (finally
            (when (contains? (registry/branches (:seon.store/store attempt)) (:seon.agent/branch attempt))
              (agent/release-context! attempt))))))))

(deftest releasing-an-execution-releases-the-executions-acquired-through-it
  ;; Astra registry E2: release detached A's registry while B, acquired through A,
  ;; kept its connection and branch outside every reachable registry.
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
         (is (not-any? (registry/branches store) [(:seon.agent/branch a) (:seon.agent/branch b)])
             "both owned branches are retired")
         (is (nil? (registry/active-branch-connection {:seon.store/store store :seon.store/branch (:seon.agent/branch b)}))
             "the descendant's connection is released")
         (is (= ::agent/registry-released
                (::agent/refused (test-support/refusal-data
                                  #(agent/acquire-context! a nil {:seon.agent/branch (:seon.agent/branch a)}))))
             "a released execution claims nothing more")
         (finally
           (doseq [execution [b a]
                   :when (contains? (registry/branches store) (:seon.agent/branch execution))]
             (agent/release-context! execution))))))))

(deftest concurrent-acquisitions-of-one-key-build-once
  ;; The first caller is held just after it reserves the key until the second
  ;; caller has observed that reservation, so the overlap is certain, not timed.
  (test-support/with-database
   (fn [connection]
     (test-support/transacted! connection (created-agent-tx connection "shared-acquisition" "shared"))
     (let [parent (test-support/execution-handle connection)
           registry (:seon.agent/routing parent)
           key [(live-branch connection) "shared"]
           reservations (atom 0)
           both-reserved (CountDownLatch. 1)
           watch-key (Object.)
           _ (add-watch registry watch-key
                        (fn [_ _ _ held]
                          (when (:seon.cluster.agent/acquiring (get-in held [:seon.agent/contexts key]))
                            (case (swap! reservations inc)
                              1 (test-support/await-event! both-reserved ::second-reserved)
                              2 (.countDown both-reserved)
                              nil))))
           acquisitions (try (mapv deref (mapv (fn [_] (future (agent/acquire-context! parent "shared")))
                                               (range 2)))
                             (finally (remove-watch registry watch-key)))]
       (is (identical? (first acquisitions) (second acquisitions))
           "same-key callers share one acquisition")
       (is (identical? (first acquisitions) (get-in @registry [:seon.agent/contexts key]))
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
            started (async/promise-chan)
            _ (add-watch backstop-state ::started (fn [_ _ _ backstop] (when backstop (async/offer! started backstop))))
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
          (let [failure-channel (:seon.agent/failure-channel (async/poll! started))]
            (is (some? failure-channel) "the transform started its bound")
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

(deftest a-changed-core-entity-refuses-by-name-and-every-agent-still-starts
  ;; Owner ruling 2026-09-23 (AGENTS.md error policy): a core entity the program
  ;; cannot install never throws out of start-flow!. acquire-context! threw
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
                                  ;; an edit beyond the docstring: a docstring-only
                                  ;; change keeps the compiled Var and refuses nothing
                                  [(test-support/revision
                                    @connection [:seon.fn/sym subject]
                                    {:seon.fn/source (str/replace-first
                                                      (:seon.fn/source (db/pull @connection [:seon.fn/source]
                                                                                [:seon.fn/sym subject]))
                                                      #"^(\(defn-?\s+\S+)" "$1 ;; A changed host-bound entity.\n")
                                     :seon.schema.admission/source :agent})])
        (try
          (let [first-agent (start-one! connection ctx routing "core-fault" "fault-a")
                second-agent (start-one! connection ctx routing "core-fault" "fault-b")]
            (is (some? (:seon.flow/started first-agent)))
            (is (some? (:seon.flow/started second-agent)) "the next agent starts too")
            (doseq [entry [first-agent second-agent]]
              (is (= subject
                     (:seon.sci.eval/refused-function
                      (refusal-of (get-in entry [:seon.turn.loop/cluster :seon.sci.eval/agent-ctx]))))
                  "the changed host-bound entity refuses by name when called")))
          (finally (stop-all-flows! routing)))))))

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
          (let [entry (start-one! connection ctx routing "declared-agent-inputs" "declared-agent-inputs")
                report (await-idle! (:seon.flow/started entry))]
            (is (= "declared-agent-inputs" (:seon.agent/id report)))
            (is (true? (:seon.turn.loop/idle? report)))
            (is (some? (db/entity @connection
                                  [:seon.db.process/id process])))
            (is (satisfies? async.impl/ReadPort
                            (get-in entry [:seon.flow/started :report-chan]))))
          (finally (stop-all-flows! routing)))))))

(deftest park-wake-test
  (with-connection
    (fn [connection ctx]
      (let [routing (armory connection)
            ledger (atom [])]
        (test-support/with-provider ledger (constantly "(seon.run/complete \"done\")")
          (fn [provider]
            (create-working-agents! connection "park-2026072812"
                                    (merge {:seon.config.run/max-episode-runs 100}
                                           (provider-config provider))
                                    ["parked"])
            (try
              (let [entry (start-one! connection ctx routing "park-2026072812" "parked")]
                (testing "the start prime reports its completed idle pass"
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
                (stop-all-flows! routing)))))))))

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
                         "(seon.run/wait \"done\")")
          (fn [provider]
            (create-working-agents! connection "pause-2026072813"
                                    (merge {:seon.config.run/max-episode-runs 100}
                                           (provider-config provider))
                                    ["pausable"])
            (try
              (let [entry (start-one! connection ctx routing "pause-2026072813" "pausable")
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
                (stop-all-flows! routing)))))))))

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
        (test-support/assign-task! connection "alice")
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

(deftest ^{:seon/tasks #{'seon.cluster.agent-test/hot-reload-observes-var-built-procs-without-redefining-the-turn-step}}
  hot-reload-var-test
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
               (conj (created-agent-tx connection "hot-2026072815" "reloaded")
                     (config-entity "hot-2026072815"
                                    {:seon.config.run/max-episode-runs 100})))
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
          (let [entry (start-one! connection ctx routing "hot-2026072815" "reloaded")
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
              (testing "the started graph's next pass observably runs v2,
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
            (stop-all-flows! routing)))))))

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
            (create-working-agents! connection "restamp-2026072816"
                                    (merge {:seon.config.run/max-episode-runs 100}
                                           (provider-config provider))
                                    ["midfold" "waiting"])
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
                        (start-one! connection ctx routing "restamp-2026072816" agent-id))
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
                (stop-all-flows! routing)))))))))

;;; ---------------------------------------------------------------------------
;;; 7. unheld-resume-regression (audit P1) — seed 2026072817
;;; ---------------------------------------------------------------------------

;;; ---------------------------------------------------------------------------
;;; 8. custody-mismatch-regression (audit P2) — seed 2026072818
;;; ---------------------------------------------------------------------------



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
            (create-working-agents! connection "wait-2026072820"
                                    (merge {:seon.config.run/max-episode-runs 100}
                                           (provider-config provider))
                                    ["waiter"])
            (try
              (let [events (database-events connection)
                    db
                    (try
                      (start-one! connection ctx routing "wait-2026072820" "waiter")
                      (outside-trigger! connection "waiter" "m-wait"
                                        "hold on")
                      (let [entry (agent/started routing "waiter")]
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
                          (let [entry (agent/started routing "waiter")]
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
                (stop-all-flows! routing)))))))))

(deftest ^{:seon.test/long "Five ordinary agent runs through evaluate-source!, each a run transaction, one Flow turn and its settlement (184-229 ms warm each on default, 2026-09-26, evaluate_source_test), plus starting; the alias batch also analyzes a declaration."
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
        (agent/start-flow! request)
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
            (agent/stop-flow! request)
            (doseq [channel [faults (:seon.cluster.wake/channel handle)
                             (:seon.render/context-channel handle) (:seon.turn.loop/completion handle)]]
              (async/close! channel))))))))

(deftest ^{:seon/tasks #{'seon.cluster.agent/a-fresh-agents-environment-costs-one-fork}}
  a-fresh-agents-environment-costs-two-forks
  (test-support/with-database
   (fn [connection]
     (test-support/transacted! connection (test-support/agent-tx (db/db connection) "two-forks"))
     (let [parent (test-support/execution-handle connection)
           from {:seon.agent/isolate? true :seon.cluster.registry/from (db/commit-id @connection)}
           ;; The commit's program is acquired once, by an earlier branch of it.
           _ (agent/release-context! (agent/acquire-context! parent nil from))
           measured (profile/measure #(agent/acquire-context! parent "two-forks" from))
           handle (:seon.profile/value measured)
           called (:seon.profile/called measured)]
       (try
         ;; The whole acquisition, branch and its first transaction included:
         ;; 4 forks cost 25.6 ms / 16.6 MB, the copy and the agent's fork 14.5 ms / 9.1 MB.
         (is (< (:seon.profile/ms measured) 25))
         (is (< (:seon.profile/alloc-bytes measured) 15000000))
         (is (= 2 (get called 'seon.sci.eval/inherit-var-bindings! 0))
             "the branch's program copy and the agent's fork: every SCI fork inherits its bindings once")
         (is (zero? (get called 'seon.sci.eval/fork-cluster-ctx 0)))
         (is (zero? (get called 'seon.sci.eval/acquire! 0)))
         (is (zero? (get called 'seon.sci.eval/settled-results 0))
             "an agent that never ran reads no history")
         (is (identical? (:seon.db/connection handle)
                         (:seon.db/connection (:seon.sci.eval/custody (:seon.sci.eval/ctx handle))))
             "the context's custody is the branch connection")
         (finally (agent/release-context! handle)))))))

(deftest a-merge-steps-refusal-names-the-declarations-its-refusal-offends
  ;; MERGE-STEP 3: a coverage refusal (`seon.cluster.source/results-verdict`) names its uncovered functions only in
  ;; `:seon.error/offending`; the held step's instructions cite each, so its agent and root see which
  (let [refusal {:seon.error/type :seon.source/test-evidence-error :seon.error/at (java.util.Date.)
                 :seon.error/layer :seon.source/merge :seon.error/operation 'seon.cluster.source/results-verdict
                 :seon.error/message "A replaced function reaches no green test of the named run."
                 :seon.error/offending ['seon.ref/names 'seon.ref/references]
                 :seon.source/candidate :merge-test}]
    (is (= #{'seon.ref/names 'seon.ref/references}
           (ref/names (#'agent/refusal-section refusal)))
        "each offending declaration is a reference of the section")))
