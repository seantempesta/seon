(ns seon.cluster.agent-test
  "The F1 sealed suite: agents are flows (seeds 2026072811-2026072820).

  Ten oracles from the sealed contract
  (docs/prds/sci-execution-runtime/plan/f1-agent-graph-contracts-2026-07-28.md
  §8), driven against REAL per-agent graphs wherever the claim is about
  the graphs, and against the real transitions where the claim is about
  the derivation. Per-trial in-memory databases through the canonical
  attribute population; a recorded stub provider (a ledger of calls —
  no paid call anywhere); the injected evaluator is source-driven so it
  needs no thread-local binding to reach a proc's own virtual thread."
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
            [my.run :as my.run]
            [seon.ai :as ai]
            [seon.bootstrap :as bootstrap]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.cluster.prompt :as prompt]
            [seon.cluster.run :as run]
            [seon.cluster.wake :as wake]
            [seon.cluster.work :as work]
            [seon.config :as config]
            [seon.flow :as seon.flow]
            [seon.problems :as problems]
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

(defn fake-evaluate
  "The injected evaluator, decided by the SOURCE it is handed — no
  dynamic binding, because a real graph evaluates on its proc's own
  virtual thread where a test-thread binding cannot reach."
  [request]
  (let [source (:seon.cluster.run.form/source request)]
    (cond
      (re-find #"my\.run/complete" source)
      (let [value (my.run/complete "done")]
        {:seon.cluster.eval/result-edn (pr-str value)
         :seon.sci.admit/value value})

      (re-find #"my\.run/wait" source)
      (let [value (my.run/wait "awaiting input")]
        {:seon.cluster.eval/result-edn (pr-str value)
         :seon.sci.admit/value value})

      :else
      {:seon.cluster.eval/result-edn "1"
       :seon.sci.admit/value 1})))

(defn- with-connection
  [body]
  (test-support/with-database
    (fn [connection]
      (let [ctx (test-support/fork-cluster-ctx connection)
            launcher
            (seon.flow/start-work-launcher!
             {:seon.env/environment @test-environment
              ::seon.flow/configuration
              {:seon.config.flow.compute/queue-depth 10
               :seon.config.flow.compute/concurrency 3
               :seon.config.flow.io/queue-depth 2
               :seon.config.flow.io/concurrency 2}})
            context-channel (async/chan)
            stream-channel (async/chan (async/sliding-buffer 1))
            render-channel (async/chan (async/sliding-buffer 1))
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
                  :seon.render/context-channel context-channel
                  :seon.render.web/pages-channel pages-channel
                  :seon.render.web/registration (atom {})
                  :seon.render.web/latest-packages (atom {})
                  :seon.render.web/interest (atom :all)
                  :seon.render.web/completion completion
                  :seon.render.web/root-agent-id "root"
                  :seon.cluster.loop/cluster
                  {:seon.db/connection connection
                   :seon.cluster.loop/stream-channel stream-channel
                   :seon.sci.admit/caps
                   {:seon.config.eval.result/max-depth 6
                    :seon.config.eval.result/max-collection 8
                    :seon.config.eval.result/max-string 4096
                    :seon.config.eval.result/max-nodes 256}
                   :seon.sci.eval/ctx ctx
                   :seon.config.eval/time-limit-ms
                   @shipped-eval-time-limit-ms
                   :seon.config/on-core-error :panic
                  :seon.cluster.run/process process}})}}
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
            (async/<!! completion)
            (seon.flow/stop-work-launcher! launcher)))))))

(defn- handle
  [connection ctx]
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
   :seon.cluster.loop/stream-channel *stream-channel*
   :seon.cluster.run/process process
   ;; replaced per agent by arm! — present so the handle validates
   :seon.cluster.wake/channel (async/chan (async/sliding-buffer 1))
   :seon.cluster.loop/completion (async/promise-chan)
   :seon.cluster.loop/evaluate 'seon.cluster.agent-test/fake-evaluate
   :seon.sci.admit/caps {:seon.config.eval.result/max-depth 6
                         :seon.config.eval.result/max-collection 8
                         :seon.config.eval.result/max-string 4096
                         :seon.config.eval.result/max-nodes 256}
   :seon.config.eval/time-limit-ms @shipped-eval-time-limit-ms
   :seon.config/on-core-error :panic
   :seon.config.error/recurrence-limit 3
   :seon.config.message/max-chain 16})

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
  (agent/arm! {:seon.cluster.loop/cluster (handle connection ctx)
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
  "Bounded poll — the test-side clock, failing loudly by returning nil.
  Production is event-driven; a TEST must decide when to give up."
  [probe]
  (loop [attempt 0]
    (or (probe)
        (when (< attempt 200)
          (Thread/sleep 25)
          (recur (inc attempt))))))

(defn- await-database-state!
  "Return the first observed database value satisfying `accept?`.

  The caller registers `event-source` before its initial derivation. A commit
  between the connection read and the channel take is therefore queued, and
  no wall clock can turn merely pending work into a false invariant verdict.
  The test runner owns the outer loud backstop for a genuinely missing event."
  [connection event-source accept?]
  (loop [database @connection]
    (if (accept? database)
      database
      (if-some [report (async/<!! event-source)]
        (recur (:db-after report))
        (throw
         (ex-info "The database event source closed before the required state."
                  {:seon.error/kind ::database-event-source-closed}))))))

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
         [?run :seon.cluster.run/id ?id]
         (not [?run :seon.cluster.run/closed-at _])]
       db))

(defn- answers-by-trigger
  "Message id to the number of runs recording it as trigger."
  [db]
  (into {}
        (db/q '[:find ?message-id (count ?run)
               :where
               [?run :seon.cluster.run/trigger ?message]
               [?message :seon.cluster.message/id ?message-id]]
             db)))

(defn- quiescent?
  [db agent-ids]
  (and (every? #(empty? (work/unanswered-triggers db %)) agent-ids)
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

(deftest graph-definition-inherits-the-cluster-io-executor
  (with-connection
    (fn [connection ctx]
      (let [executor (reify Executor (execute [_ _]))
            definition
            (agent/graph-definition
             {:seon.cluster.loop/cluster
              (assoc (handle connection ctx) :seon.flow/executor executor)
              :seon.cluster.agent/id "executor-proof"})]
        (is (identical? executor (:io-exec definition)))))))

(deftest prompt-request-without-context-channel-is-a-flat-refusal
  (with-connection
    (fn [connection ctx]
      (db/transact!
       connection
       [{:seon.cluster.agent/id "missing-context"}
        {:seon.cluster.message/id "missing-context-message"
         :seon.cluster.message/to
         [:seon.cluster.agent/id "missing-context"]
         :seon.cluster.message/content "derive context"
         :seon.cluster.message/at now}
        {:seon.cluster.run/id "missing-context-run"
         :seon.cluster.run/agent
         [:seon.cluster.agent/id "missing-context"]
         :seon.cluster.run/trigger
         [:seon.cluster.message/id "missing-context-message"]
         :seon.cluster.run/process process
         :seon.cluster.run/opened-at now}
        {:seon.cluster.agent/id "missing-context"
         :seon.cluster.agent/run
         [:seon.cluster.run/id "missing-context-run"]}])
      (let [failure
            (test-support/refusal-data
             #(prompt/prompt
               @connection
               {:seon.cluster.run/id "missing-context-run"
                :seon.cluster.agent/id "missing-context"
                :seon.sci.admit/caps
                {:seon.config.eval.result/max-depth 6
                 :seon.config.eval.result/max-collection 8
                 :seon.config.eval.result/max-string 4096
                 :seon.config.eval.result/max-nodes 256}
                :seon.sci.eval/ctx ctx
                :seon.sci.eval/time-limit-ms @shipped-eval-time-limit-ms
                :seon.config/on-core-error :panic}))]
        (is (= :seon.cluster.prompt/refused
               (:seon.error/kind failure)))
        (is (= :seon.cluster.prompt/missing-input
               (:seon.cluster.prompt/rule failure)))
        (is (str/includes? (:seon.error/message failure)
                           ":seon.render/context-channel"))
        (is (schema/valid-candidate-value? :seon.error/value failure)
            "the missing input is already the loop's admitted error shape")))))

(deftest prompt-refusal-answers-its-trigger-once
  (with-connection
    (fn [connection ctx]
      (let [routing (armory)
            requests (atom [])
            events (database-events connection)]
        (db/transact!
         connection
         [(agent-row "prompt-refusal-cap")
          (config-row "prompt-refusal-cap"
                      {:seon.config.run/max-episode-runs 3})])
        (try
          (with-redefs [ai/complete
                        (recording-completer
                         requests
                         (constantly "unused"))]
            (let [cluster (dissoc (handle connection ctx)
                                  :seon.render/context-channel)
                  entry
                  (agent/arm!
                   {:seon.cluster.loop/cluster cluster
                    :seon.cluster.agent/id "prompt-refusal-cap"
                    :seon.cluster.agent/routing routing})]
              (while (async/poll! (:seon.cluster.agent-test/events events)))
              (outside-trigger! connection "prompt-refusal-cap"
                                "prompt-refusal-message" "derive context")
              (async/offer! (:seon.cluster.wake/channel entry) ::wake)
              (test-support/await-event!
               (:seon.cluster.agent-test/events events)
               ::prompt-refusal-terminal
               #(let [db (:db-after %)]
                  (and (= 1 (work/episode-runs db
                                               "prompt-refusal-cap"))
                       (quiescent? db ["prompt-refusal-cap"]))))
              (is (empty? @requests)
                  "no provider call occurs without a valid prompt")
              (is (= 1 (work/episode-runs @connection
                                          "prompt-refusal-cap")))
              (is (= 1 (or (db/q '[:find (count ?error) .
                                   :where
                                   [?error :seon.error/kind
                                    :seon.cluster.prompt/refused]]
                                 @connection)
                           0))
                  "the answering run records one flat prompt refusal")
              (is (nil? (work/next-agent-work
                         @connection
                         {:seon.cluster.agent/id "prompt-refusal-cap"
                          :seon.cluster.run/process process
                          :seon.cluster.work/now (Date.)}))
                  "the refused answering run derives no retry")))
          (finally
            (stop-database-events! connection events)
            (disarm-all! routing)))))))

;;; ---------------------------------------------------------------------------
;;; 1. n-agent-parallel-turns-property — seed 2026072811
;;; ---------------------------------------------------------------------------

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
                       ledger (fn [_] "(my.run/complete \"done\")"))]
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
                       #(and (= (count triggers)
                                (count (answers-by-trigger %)))
                             (quiescent? % agent-ids)))
                      db terminal
                      answers (answers-by-trigger db)
                      run-count (or (db/q '[:find (count ?run) . :where
                                           [?run :seon.cluster.run/id _]]
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
                      ;; from the generated spec: each trigger is answered
                      ;; by exactly one run that closes after one form
                      per-agent-serial?
                      (every? (fn [[index agent-id]]
                                (= (nth triggers-per-agent index)
                                   (or (db/q '[:find (count ?run) .
                                              :in $ ?agent-id
                                              :where
                                              [?agent :seon.cluster.agent/id
                                               ?agent-id]
                                              [?run :seon.cluster.run/agent
                                               ?agent]
                                              [?run :seon.cluster.run/closed-at
                                               _]]
                                            db agent-id)
                                       0)))
                              (map-indexed vector agent-ids))]
                  {:settled? true
                   :answered-once?
                   (and (= (count triggers) (count answers))
                        (every? #(= 1 (val %)) answers))
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

(deftest answered-trigger-is-a-terminal-work-verdict
  (with-connection
    (fn [connection ctx]
      (let [agent-id "answered-trigger"
            trigger-id "answered-trigger-message"
            run-id "answered-trigger-run"
            wake-channel (async/chan (async/sliding-buffer 1))
            completion (async/chan 1)
            request {:seon.cluster.agent/id agent-id
                     :seon.cluster.run/process process}
            stale-work {:seon.cluster.work/situation :open
                        :seon.cluster.agent/id agent-id
                        :seon.cluster.message/id trigger-id}
            cluster (assoc (handle connection ctx)
                           :seon.cluster.wake/channel wake-channel
                           :seon.cluster.loop/completion completion)
            refused-attempts (atom 0)
            transact! db/transact!]
        (db/transact!
         connection
         [{:seon.cluster.agent/id agent-id}
          {:seon.cluster.message/id trigger-id
           :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
           :seon.cluster.message/content "already answered"
           :seon.cluster.message/at now}
          {:seon.cluster.run/id run-id
           :seon.cluster.run/agent [:seon.cluster.agent/id agent-id]
           :seon.cluster.run/trigger
           [:seon.cluster.message/id trigger-id]
           :seon.cluster.run/opened-at now
           :seon.cluster.run/closed-at now}
          {:seon.error/id (str (random-uuid))
           :seon.error/kind :seon.cluster.prompt/refused
           :seon.error/message "the answered run closed on a refusal"
           :seon.error/run [:seon.cluster.run/id run-id]}])
        (is (nil? (work/next-agent-work @connection request))
            "a closed refusal still answers its trigger")
        (async/>!! completion ::agent/ready)
        (with-redefs [work/next-agent-work (constantly stale-work)
                      work/more-agent-work? (constantly true)
                      db/transact!
                      (fn [& args]
                        (let [result (apply transact! args)]
                          (when (= :seon.cluster.loop/trigger-already-answered
                                   (:seon.error/kind result))
                            (swap! refused-attempts inc))
                          result))]
          (let [[_ outputs]
                (agent/turn-step
                 {:seon.cluster.loop/cluster cluster
                  :seon.cluster.agent/id agent-id}
                 ::agent/episode
                 ::agent/wake)
                report (first (::flow/report outputs))]
            (is (true?
                 (:seon.cluster.loop/trigger-already-answered report)))
            (is (= 1 @refused-attempts)
                "one stale wake makes at most one refused open attempt")
            (is (nil? (async/poll! wake-channel))
                "the terminal refusal does not manufacture another wake")))))))

;;; ---------------------------------------------------------------------------
;;; 2. park-wake-test — seed 2026072812
;;; ---------------------------------------------------------------------------

(deftest fenced-is-the-derived-quarantine-state
  (with-connection
    (fn [connection ctx]
      (let [routing (armory)]
        (db/transact! connection [{:seon.cluster.agent/id "agent-a"}])
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
            _ (db/transact! connection [{:seon.cluster.agent/id "agent-a"}])
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
            completion (:seon.cluster.loop/completion entry)
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
                     [::agent/armed agent-id :seon.cluster.loop/completion]
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
                    (mapv (fn [agent-id]
                            {:seon.cluster.agent/id agent-id})
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

(deftest disarm-has-a-provider-derived-loud-backstop
  (with-connection
    (fn [connection ctx]
      (let [routing (armory)
            provider-timeout-ms 100
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
           {:seon.config.ai/timeout-ms provider-timeout-ms
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
              (let [stopped
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
                     ::provider-derived-stop-backstop)
                    fault
                    (test-support/await-event!
                     fault-channel
                     ::provider-stop-core-fault)]
                (is (= ::agent/turn-completion-backstop
                       (:seon.error/kind (ex-data failure))))
                (is (= provider-timeout-ms
                       (:seon.ai/timeout-ms (ex-data failure))))
                (is (= failure (::flow/ex fault)))
                (is (= agent-id (:seon.cluster.agent/id fault)))
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
                   (:seon.cluster.loop/completion entry)))
              (is (nil? (agent/disarm!
                         {:seon.cluster.agent/id agent-id
                          :seon.cluster.agent/routing routing})))
              (is (nil? (agent/armed routing agent-id))
                  "a successful retry removes the route exactly once")))
          (finally
            (.countDown release-provider)
            (.close server)
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
                         ledger (fn [_] "(my.run/complete \"done\")"))]
            (let [entry (arm-one! connection ctx routing "parked")]
              (testing "armed and idle: the arm prime's pass ran and
              spent nothing — the window is bounded by ping counts,
              never a sleep standing for proof"
                (is (await-until #(pos? (::flow/count
                                         (turn-ping entry)))))
                (is (zero? (count @ledger)))
                (is (empty? (db/q '[:find ?run :where
                                   [?run :seon.cluster.run/id _]]
                                 @connection))))
              (testing "one committed trigger → exactly one run, one
              provider call"
                (outside-trigger! connection "parked"
                                  "m-2026072812" "one unit of work")
                (async/offer! (:seon.cluster.wake/channel entry) ::wake)
                (is (await-until
                     #(some? (db/q '[:find ?c . :where
                                    [_ :seon.cluster.run/closed-at ?c]]
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
                          (.await release-provider)
                          {:seon.ai/text "(my.run/complete \"done\")"})]
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
                                    [_ :seon.cluster.run/plan-digest ?digest]]
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
                                         (work/unanswered-triggers
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
  "Open, claim, and close one run answering `message-id`."
  [connection agent-id run-id message-id at]
  (db/transact! connection
              {:tx-data (into (run/open-tx {:seon.cluster.run/id run-id
                                            :seon.cluster.run/agent
                                            [:seon.cluster.agent/id agent-id]
                                            :seon.cluster.run/trigger
                                            [:seon.cluster.message/id message-id]
                                            :seon.cluster.run/opened-at at})
                              (run/claim-tx {:seon.cluster.run/id run-id
                                             :seon.cluster.run/process process
                                             :seon.cluster.run/live-processes
                                             #{process}
                                             :seon.cluster.run/now at}))})
  (db/transact! connection
              (run/close-tx {:seon.cluster.run/id run-id
                             :seon.cluster.run/process process
                             :seon.cluster.run/closed-at at})))

(deftest episode-cap-refusal-test
  ;; seed 2026072814 — the derivation is asserted DIRECTLY (the cited
  ;; query), because the refusal's whole contract is that no consumer
  ;; ever sees a decision: the work simply does not derive.
  (with-connection
    (fn [connection _ctx]
      (let [request {:seon.cluster.agent/id "alice"
                     :seon.cluster.run/process process
                     :seon.cluster.work/now (Date.)}]
        (db/transact! connection
                    [{:seon.cluster.agent/id "alice"}
                     {:seon.cluster.agent/id "bob"}
                     (config-row "cap-2026072814"
                                 {:seon.config.run/max-episode-runs 3})])
        ;; episode 1: a human asks
        (outside-trigger! connection "alice" "h1" "human asks")
        (opened-run! connection "alice" "e1" "h1" now)
        (is (= 1 (work/episode-runs @connection "alice")))
        ;; R3: a recorder message (carries `about`) does NOT reset
        (db/transact! connection [{:seon.error/id "fault-2026072814"
                                 :seon.error/at now
                                 :seon.error/signature
                                 (apply str (repeat 64 "c"))}])
        (db/transact! connection
                    [{:seon.cluster.message/id "r1"
                      :seon.cluster.message/to
                      [:seon.cluster.agent/id "alice"]
                      :seon.cluster.message/about
                      [:seon.error/id "fault-2026072814"]
                      :seon.cluster.message/content "about a fault"
                      :seon.cluster.message/at (Date.)}])
        (opened-run! connection "alice" "e2" "r1" now)
        (is (= 2 (work/episode-runs @connection "alice"))
            "the recorder's message did not reset the episode (R3)")
        ;; a peer's message brings the count to the cap
        (agent-trigger! connection "bob" "alice" "b1" "bob asks")
        (opened-run! connection "alice" "e3" "b1" now)
        (is (= 3 (work/episode-runs @connection "alice")))
        ;; the cap is hit: a further self-trigger derives NOTHING
        (agent-trigger! connection "bob" "alice" "b2" "bob again")
        (let [max-tx-before (:max-tx @connection)
              derived (work/next-agent-work @connection request)]
          (is (nil? derived) "the deferred trigger derives no work")
          (is (= max-tx-before (:max-tx @connection))
              "datom census: the refusal wrote NOTHING")
          (is (= ["b2"] (mapv :seon.cluster.message/id
                              (work/deferred-triggers @connection
                                                      "alice")))))
        (testing "the derived problems family and prompt line are
        present under `get`, from facts alone"
          (let [found (problems/problems
                       @connection
                       {:seon.cluster.run/live-processes #{process}})
                deferred (get found :seon.problems/deferred-agents)]
            (is (= [{:seon.cluster.agent/id "alice"
                     :seon.cluster.work/episode-runs 3
                     :seon.problems/deferred-count 1}]
                   deferred))
            (is (str/includes?
                 (problems/ai-prose found)
                 "3 self-triggered runs since the last outside trigger"))
            (is (str/includes? (problems/ai-prose found)
                               "1 triggers are deferred"))))
        (testing "SEAL CORRECTION: a fresh OUTSIDE trigger opens even
        though an OLDER deferred self-trigger is pending — the deferred
        trigger is skipped, never a selection blocker"
          (outside-trigger! connection "alice" "h2" "human again")
          (let [derived (work/next-agent-work @connection request)]
            (is (= :open (:seon.cluster.work/situation derived)))
            (is (= "h2" (:seon.cluster.message/id derived))))
          (opened-run! connection "alice" "e4" "h2" (Date.))
          (is (= 1 (work/episode-runs @connection "alice"))
              "the outside trigger's own run IS the reset"))
        (testing "and the deferred trigger is answered on the following
        pass, oldest first, now below the cap"
          (let [derived (work/next-agent-work @connection request)]
            (is (= "b2" (:seon.cluster.message/id derived))))
          (opened-run! connection "alice" "e5" "b2" (Date.))
          (is (empty? (work/deferred-triggers @connection "alice")))
          (is (empty? (get (problems/problems
                            @connection
                            {:seon.cluster.run/live-processes #{process}})
                           :seon.problems/deferred-agents [])))
          (is (= 2 (work/episode-runs @connection "alice"))))))))

;;; ---------------------------------------------------------------------------
;;; 5. hot-reload-var-test — seed 2026072815
;;; ---------------------------------------------------------------------------

(deftest hot-reload-var-test
  ;; seed 2026072815 — composing F0(a): the blueprint builds procs from
  ;; VARS, so redefining `turn-step` changes a RUNNING graph's next
  ;; pass with no rebuild; a control proc built from the captured fn
  ;; VALUE keeps running v1. The v2 evidence is an atom only v2 bumps —
  ;; a pass that increments it ran v2, and a pass that does not ran v1.
  (with-connection
    (fn [connection ctx]
      (let [original @#'agent/turn-step
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
                                      :seon.cluster.loop/completion
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
                            :args {:seon.cluster.loop/cluster
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
              (alter-var-root #'agent/turn-step (constantly
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
                (alter-var-root #'agent/turn-step (constantly original))
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
            evaluate fake-evaluate]
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
                    {:tx-data (into (run/open-tx
                                     {:seon.cluster.run/id "run-dead"
                                      :seon.cluster.run/agent
                                      [:seon.cluster.agent/id "midfold"]
                                      :seon.cluster.run/trigger
                                      [:seon.cluster.message/id "m-dead"]
                                      :seon.cluster.run/opened-at now})
                                    (run/claim-tx
                                     {:seon.cluster.run/id "run-dead"
                                      :seon.cluster.run/process dead
                                      :seon.cluster.run/live-processes
                                      #{dead}
                                      :seon.cluster.run/now now}))})
        (db/transact! connection
                    (run/plan-tx {:seon.cluster.run/id "run-dead"
                                  :seon.cluster.run/process dead
                                  :seon.cluster.run/plan-digest
                                  (apply str (repeat 64 "d"))
                                  :seon.cluster.run/sources
                                  [{:seon.cluster.run.form/source "(+ 1 2)"}
                                   {:seon.cluster.run.form/source
                                    "(+ 3 4)"}
                                   {:seon.cluster.run.form/source
                                    "(my.message/send \"waiting\" \"must not run\")"}]}))
        (db/transact! connection
                    (run/receipt-start-tx {:seon.cluster.run/id "run-dead"
                                           :seon.cluster.eval/ordinal 0
                                           :seon.cluster.eval/at now}))
        (db/transact! connection
                    (run/receipt-settle-tx {:seon.cluster.run/id "run-dead"
                                            :seon.cluster.eval/ordinal 0
                                            :seon.cluster.eval/result-edn
                                            "3"}))
        (db/transact! connection
                    (run/receipt-start-tx {:seon.cluster.run/id "run-dead"
                                           :seon.cluster.eval/ordinal 1
                                           :seon.cluster.eval/at now}))
        ;; Messages committed before the crash and never answered remain
        ;; triggers. One belongs to the interrupted agent itself, proving
        ;; recovery ends only the old WORK rather than dropping mail.
        (outside-trigger! connection "midfold" "m-unanswered"
                          "still needed after crash")
        (outside-trigger! connection "waiting" "m-waiting" "still here")
        (try
          (with-redefs [ai/complete
                        (recording-completer
                         ledger (fn [_] "(my.run/complete \"done\")"))
                        fake-evaluate
                        (fn [request]
                          (swap! evaluation-sources conj
                                 (:seon.cluster.run.form/source request))
                          (evaluate request))]
            (let [events (database-events connection)
                  ;; BOOT-SHAPE RE-ARM: recover, then re-stamp + prime. The
                  ;; listener stands before either action, so terminal facts
                  ;; cannot cross a read/take gap and pending work cannot be
                  ;; misclassified by a test-local clock.
                  db
                  (try
                    (db/transact! connection
                                  (run/recover-tx
                                   {:seon.cluster.run/id "run-dead"
                                    :seon.cluster.run/live-processes #{process}
                                    :seon.cluster.run/now (Date.)}))
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
                         [?run :seon.cluster.run/id "run-dead"]
                         [?receipt :seon.cluster.eval/run ?run]]
                       db)]
              (is (quiescent? db ["midfold" "waiting"]))
              (testing "recovery ends the interrupted run atomically"
                (is (some? (db/q '[:find ?at .
                                  :where
                                  [?run :seon.cluster.run/id "run-dead"]
                                  [?receipt :seon.cluster.eval/run ?run]
                                  [?receipt :seon.cluster.eval/ordinal 1]
                                  [?receipt
                                   :seon.cluster.eval/interrupted-at ?at]]
                                db)))
                (is (nil? (db/q '[:find ?process .
                                 :where
                                 [?run :seon.cluster.run/id "run-dead"]
                                 [?run :seon.cluster.run/process ?process]]
                               db)))
                (is (some? (db/q '[:find ?at .
                                  :where
                                  [?run :seon.cluster.run/id "run-dead"]
                                  [?run :seon.cluster.run/closed-at ?at]]
                                db)))
                (is (nil? (db/q '[:find ?run .
                                 :where
                                 [?agent :seon.cluster.agent/id "midfold"]
                                 [?agent :seon.cluster.agent/run ?run]]
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
                                 [?run :seon.cluster.run/id "run-dead"]
                                 [?receipt :seon.cluster.eval/run ?run]
                                 [?receipt :seon.cluster.eval/ordinal 1]
                                 [?receipt
                                  :seon.cluster.eval/result-edn ?result]]
                               db)))
                (is (nil? (db/q '[:find ?receipt .
                                 :where
                                 [?run :seon.cluster.run/id "run-dead"]
                                 [?receipt :seon.cluster.eval/run ?run]
                                 [?receipt :seon.cluster.eval/ordinal 2]]
                               db))
                    "the unstarted capability-shaped suffix has no receipt")
                (is (not-any? #(str/includes? % "my.message/send")
                              @evaluation-sources)
                    "and it never reached the evaluator"))
              (testing "unanswered pre-crash messages start new episodes"
                (is (= 2 (count @ledger))
                    "one fresh provider call for each unanswered message")
                (is (= 1 (get answers "m-unanswered")))
                (is (= 1 (get answers "m-waiting"))))
              (testing "the recovered facts derive one interruption value"
                (let [forms
                      (mapv (fn [ordinal]
                              {:seon.cluster.run.form/ordinal ordinal})
                            (db/q '[:find [?ordinal ...]
                                   :where
                                   [?run :seon.cluster.run/id "run-dead"]
                                   [?form
                                    :seon.cluster.run.form/run ?run]
                                   [?form
                                    :seon.cluster.run.form/ordinal ?ordinal]]
                                 db))
                      receipts (mapv #(db/pull db '[*] %) run-receipts)
                      warning (run/interrupted-warning forms receipts)
                      rendered
                      (run/render-ai
                       (assoc (db/pull db '[*]
                                       [:seon.cluster.run/id "run-dead"])
                              :seon.db/db db))]
                  (is (= {:seon.cluster.eval/ordinal 1
                          :seon.cluster.run/missing-results 2}
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

(deftest custody-mismatch-regression
  ;; seed 2026072818 — a run held by ANOTHER live process rewakes its
  ;; agent here: this process derives no work for it and dispatches
  ;; nothing. With leases deleted, P2's scenario is a custody mismatch.
  (with-connection
    (fn [connection ctx]
      (let [other "77777-1"
            routing (armory)
            ledger (atom [])]
        (db/transact! connection
                    [{:seon.cluster.agent/id "held"}
                     (config-row "p2-2026072818"
                                 {:seon.config.run/max-episode-runs 100})])
        (outside-trigger! connection "held" "m-held" "busy elsewhere")
        (db/transact! connection
                    {:tx-data (into (run/open-tx
                                     {:seon.cluster.run/id "run-held"
                                      :seon.cluster.run/agent
                                      [:seon.cluster.agent/id "held"]
                                      :seon.cluster.run/trigger
                                      [:seon.cluster.message/id "m-held"]
                                      :seon.cluster.run/opened-at now})
                                    (run/claim-tx
                                     {:seon.cluster.run/id "run-held"
                                      :seon.cluster.run/process other
                                      :seon.cluster.run/live-processes
                                      #{other}
                                      :seon.cluster.run/now now}))})
        (is (nil? (work/next-agent-work
                   @connection
                   {:seon.cluster.agent/id "held"
                    :seon.cluster.run/process process
                    :seon.cluster.work/now (Date.)}))
            "another process's held run derives NO work here")
        (try
          (with-redefs [ai/complete
                        (recording-completer
                         ledger (fn [_] "(my.run/complete \"done\")"))]
            (arm-one! connection ctx routing "held")
            (let [entry (agent/armed routing "held")]
              ;; the arm prime plus an explicit rewake both pass over
              ;; the held run without touching it
              (async/offer! (:seon.cluster.wake/channel entry) ::wake)
              (is (await-until #(>= (::flow/count (turn-ping entry)) 1)))
              (is (zero? (count @ledger))
                  "zero duplicate provider dispatches across the
                   interleaving")
              (is (empty? (db/q '[:find ?receipt
                                  :where [?receipt :seon.cluster.eval/id _]]
                                @connection)))
              (is (= other (db/q '[:find ?p . :where
                                  [?run :seon.cluster.run/id "run-held"]
                                  [?run :seon.cluster.run/process ?p]]
                                @connection))
                  "custody untouched")))
          (finally
            (disarm-all! routing)))))))

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
                                :seon.cluster.loop/completion
                                (async/promise-chan))
            armer-graph (flow/create-flow
                         {:procs
                          {::agent/armer
                           {:proc (flow/process #'agent/armer-step
                                                {:workload :io})
                            :args {:seon.cluster.loop/cluster armer-handle
                                   :seon.cluster.agent/routing routing}}}
                          :conns []})
            _ (flow/start armer-graph)
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
                         ledger (fn [_] "(my.run/complete \"done\")"))
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
                (when (nil? (async/<!! armed-event))
                  (throw
                   (ex-info "The routing watch closed before every agent armed."
                            {:seon.error/kind ::routing-watch-closed})))
                (let [db
                      (await-database-state!
                       connection
                       (:seon.cluster.agent-test/events events)
                       #(and (= (+ @message-count (count agent-ids))
                                (count (answers-by-trigger %)))
                             (quiescent? % agent-ids)))
                      answers (answers-by-trigger db)]
                  {:settled? true
                   :armed-once?
                   (= (count agent-ids)
                      (count (:seon.cluster.agent/armed @routing)))
                   :no-unarmed-with-triggers?
                   (every? (fn [agent-id]
                             (or (contains? (:seon.cluster.agent/armed
                                             @routing) agent-id)
                                 (empty? (work/unanswered-triggers
                                          db agent-id))))
                           agent-ids)
                   :every-message-answered-once?
                   (and (= (+ @message-count (count agent-ids))
                           (count answers))
                        (every? #(= 1 (val %)) answers))})
                (finally
                  (remove-watch routing watch-key)
                  (stop-database-events! connection events)))))
          (finally
            (wake/unlisten! {:seon.cluster.wake/connection connection
                             :seon.cluster.wake/key ::route-trial})
            (flow/stop armer-graph)
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
              (.await release-provider)
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
        (.countDown release-provider)))))

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
                         ledger (fn [_] "(my.run/wait \"need input\")"))]
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
                     #(quiescent? % ["waiter"]))
                    (finally
                      (stop-database-events! connection events)))
                  run-id (db/q '[:find ?id . :where
                                [?run :seon.cluster.run/id ?id]]
                              db)
                  settle-tx (db/q '[:find ?tx . :where
                                   [?receipt
                                    :seon.cluster.eval/result-edn _ ?tx]]
                                 db)
                  close-tx (db/q '[:find ?tx . :where
                                  [_ :seon.cluster.run/closed-at _ ?tx]]
                                db)]
              (is (quiescent? db ["waiter"]))
              (testing "settle and close share ONE transaction"
                (is (some? settle-tx))
                (is (= settle-tx close-tx)))
              (testing "no basis carries an unheld open planned run —
              the P1 feeder state is unrepresentable"
                (let [txs (sort (db/q '[:find [?tx ...] :where
                                       [_ _ _ ?tx]]
                                     db))]
                  (is (not-any?
                       (fn [tx]
                         (let [basis (db/as-of db tx)
                               run (db/pull basis '[*]
                                           [:seon.cluster.run/id run-id])]
                           (and (some? (:db/id run))
                                (nil? (:seon.cluster.run/closed-at run))
                                (nil? (:seon.cluster.run/process run))
                                (some? (:seon.cluster.run/plan-digest
                                        run)))))
                       txs))))
              (testing "the note survives in the receipt"
                (is (str/includes?
                     (db/q '[:find ?edn . :where
                            [_ :seon.cluster.eval/result-edn ?edn]]
                          db)
                     "awaiting input")))
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
                         #(quiescent? % ["waiter"]))
                        (finally
                          (stop-database-events! connection events)))]
                  (is (quiescent? terminal-db ["waiter"]))
                  (is (= 2 (or (db/q '[:find (count ?run) . :where
                                      [?run :seon.cluster.run/id _]]
                                    terminal-db)
                               0)))
                  (is (= {"m-wait" 1 "m-next" 1}
                         (answers-by-trigger terminal-db)))))))
          (finally
            (disarm-all! routing)))))))
