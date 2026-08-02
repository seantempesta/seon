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
            [my.run :as my.run]
            [seon.ai :as ai]
            [seon.cluster :as cluster]
            [seon.cluster.agent :as agent]
            [seon.cluster.run :as run]
            [seon.cluster.wake :as wake]
            [seon.cluster.work :as work]
            [seon.config :as config]
            [seon.flow :as seon.flow]
            [seon.problems :as problems]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as test-support])
  (:import [java.util Date]
           [java.util.concurrent CountDownLatch Executor]))

(set! *warn-on-reflection* true)

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
      (str/includes? source ":seon.cluster.loop/lint-rejected")
      (let [value (second (read-string source))]
        {:seon.cluster.eval/result-edn (pr-str value)
         :seon.sci.admit/value value})

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

(defn- fresh-connection
  []
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :schema-flexibility :write}]
    (d/create-database configuration)
    (let [connection (d/connect configuration)]
      (d/transact connection
                  (schema.datahike/malli->datahike-schema
                   (schema/canonical-database-attributes)))
      connection)))

(defn- with-connection
  [body]
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :schema-flexibility :write}]
    (d/create-database configuration)
    (let [connection (d/connect configuration)]
      (try
        (seon.flow/install-work-launcher!
         {::seon.flow/configuration
          {:seon.config.flow.compute/queue-depth 10
           :seon.config.flow.compute/concurrency 3}})
        (d/transact connection
                    (schema.datahike/malli->datahike-schema
                     (schema/canonical-database-attributes)))
        (body connection (sci.eval/cluster-ctx @connection))
        (finally
          (seon.flow/stop-installed-work-launcher!)
          (d/release connection)
          (d/delete-database configuration))))))

(defn- handle
  [connection ctx]
  {:seon.store/branch-connection connection
   :seon.cluster/name
   (d/q '[:find ?cluster . :where [_ :seon.config/cluster ?cluster]]
        @connection)
   :seon.sci.eval/ctx ctx
   :seon.cluster.run/process process
   ;; replaced per agent by arm! — present so the handle validates
   :seon.cluster.wake/channel (async/chan (async/sliding-buffer 1))
   :seon.cluster.loop/completion (async/promise-chan)
   :seon.cluster.loop/evaluate 'seon.cluster.agent-test/fake-evaluate
   :seon.sci.admit/caps {:seon.config.eval.result/max-depth 6
                         :seon.config.eval.result/max-collection 8
                         :seon.config.eval.result/max-string 4096
                         :seon.config.eval.result/max-nodes 256}
   :seon.config.eval/time-limit-ms 2000
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

(defn- turn-ping
  [entry]
  (:clojure.core.async.flow/state
   (flow/ping-proc (:seon.flow/graph entry) ::agent/turn)))

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
  (d/transact connection
              [{:seon.cluster.message/id message-id
                :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
                :seon.cluster.message/content content
                :seon.cluster.message/at (Date.)}]))

(defn- agent-trigger!
  [connection from-id to-id message-id content]
  (d/transact connection
              [{:seon.cluster.message/id message-id
                :seon.cluster.message/to [:seon.cluster.agent/id to-id]
                :seon.cluster.message/from [:seon.cluster.agent/id from-id]
                :seon.cluster.message/content content
                :seon.cluster.message/at (Date.)}]))

(defn- open-runs
  [db]
  (d/q '[:find [?id ...]
         :where
         [?run :seon.cluster.run/id ?id]
         (not [?run :seon.cluster.run/closed-at _])]
       db))

(defn- answers-by-trigger
  "message-id → how many run-opening transactions named it as trigger."
  [db]
  (into {}
        (d/q '[:find ?message-id (count ?run)
               :where
               [?run :seon.cluster.run/id _ ?tx]
               [?tx :seon.db/trigger ?message]
               [?message :seon.cluster.message/id ?message-id]]
             db)))

(defn- quiescent?
  [db agent-ids]
  (and (every? #(empty? (work/unanswered-triggers db %)) agent-ids)
       (empty? (open-runs db))))

(defn- scripted-completer
  "Record each provider request and return the corresponding reply."
  [ledger calls replies]
  (fn [request]
    (let [call-count (count (swap! ledger conj request))]
      (async/put! calls request)
      {:seon.ai/text
       (nth replies
            (dec call-count)
            "(my.run/complete \"unexpected extra provider call\")")})))

(defn- database-events
  [connection]
  (let [events (async/chan 64)
        listener-key (random-uuid)]
    (d/listen connection listener-key #(async/put! events %))
    {:seon.cluster.agent-test/events events
     :seon.cluster.agent-test/listener-key listener-key}))

(defn- stop-database-events!
  [connection event-source]
  (d/unlisten connection
              (:seon.cluster.agent-test/listener-key event-source))
  (async/close! (:seon.cluster.agent-test/events event-source)))

(defn- await-quiescence!
  [event-source agent-ids receipt-count]
  (test-support/await-event!
   (:seon.cluster.agent-test/events event-source)
   ::quiescent
   #(let [db (:db-after %)]
      (and (quiescent? db agent-ids)
           (= receipt-count
              (or (d/q '[:find (count ?receipt) .
                         :where
                         [?receipt :seon.cluster.eval/id _]]
                       db)
                  0))))))

(defn- lint-refusal-results
  [db]
  (->> (d/q '[:find [?serialized ...]
              :where
              [_ :seon.cluster.eval/result-edn ?serialized]]
            db)
       (keep (fn [serialized]
               (try
                 (let [value (read-string serialized)]
                   (when (= :seon.cluster.loop/lint-rejected
                            (:seon.error/kind value))
                     value))
                 (catch Exception _ nil))))
       vec))

;;; ---------------------------------------------------------------------------
;;; Ruling #22 — lint refusals continue the episode without a message
;;; ---------------------------------------------------------------------------

(deftest lint-refusals-continue-the-episode-until-the-cap
  (let [refused-source
        "(my.message/send \"nobody\" \"body\" \"about\" \"extra\")"
        completed-source "(my.run/complete \"corrected\")"]
    (testing "an all-refused turn opens the next turn with its finding"
      (with-connection
        (fn [connection ctx]
          (let [routing (armory)
                ledger (atom [])
                calls (async/chan 4)
                events (database-events connection)]
            (d/transact connection
                        [{:seon.cluster.agent/id "all-refused"}
                         (config-row "r22-all-refused"
                                     {:seon.config.run/max-episode-runs 3})])
            (try
              (with-redefs [ai/complete
                            (scripted-completer
                             ledger calls
                             [refused-source completed-source])]
                (let [entry (arm-one! connection ctx routing "all-refused")]
                  (outside-trigger! connection "all-refused"
                                    "r22-all-message" "do the work")
                  (async/offer! (:seon.cluster.wake/channel entry) ::wake)
                  (test-support/await-event! calls ::first-provider-call)
                  (test-support/await-event! calls
                                             ::refusal-continuation-call)
                  (await-quiescence! events ["all-refused"] 2)
                  (is (= 2 (count @ledger))
                      "the refusal itself derives exactly one next turn")
                  (let [refusals (lint-refusal-results @connection)
                        second-prompt (:seon.ai/prompt (second @ledger))]
                    (is (= 1 (count refusals))
                        "production linting committed one flat refusal")
                    (is (and (string? second-prompt)
                             (str/includes? second-prompt "expects 2 or 3"))
                        "the next turn sees the exact arity finding")
                    (is (= 1 (or (d/q '[:find (count ?message) .
                                         :where
                                         [?message :seon.cluster.message/id _]]
                                       @connection)
                                 0))
                        "no self-message or second external message continued it")
                    (is (= 2 (count (d/q '[:find [?run ...]
                                           :where
                                           [?run :seon.cluster.run/id _]]
                                         @connection)))
                        "the corrective turn opened exactly one new run")
                    (is (nil? (work/next-agent-work
                               @connection
                               {:seon.cluster.agent/id "all-refused"
                                :seon.cluster.run/process process
                                :seon.cluster.work/now (Date.)}))
                        "the clean correction prevents the old refusal resurfacing"))))
              (finally
                (stop-database-events! connection events)
                (async/close! calls)
                (disarm-all! routing)))))))

    (testing "one successful form does not hide a refusal"
      (with-connection
        (fn [connection ctx]
          (let [routing (armory)
                ledger (atom [])
                calls (async/chan 4)
                events (database-events connection)]
            (d/transact connection
                        [{:seon.cluster.agent/id "mixed-refusal"}
                         (config-row "r22-mixed-refusal"
                                     {:seon.config.run/max-episode-runs 3})])
            (try
              (with-redefs [ai/complete
                            (scripted-completer
                             ledger calls
                             [(str "(+ 1 1)\n" refused-source)
                              completed-source])]
                (let [entry (arm-one! connection ctx routing "mixed-refusal")]
                  (outside-trigger! connection "mixed-refusal"
                                    "r22-mixed-message" "do both forms")
                  (async/offer! (:seon.cluster.wake/channel entry) ::wake)
                  (test-support/await-event! calls ::first-provider-call)
                  (test-support/await-event! calls
                                             ::mixed-refusal-continuation-call)
                  (await-quiescence! events ["mixed-refusal"] 3)
                  (is (= 2 (count @ledger))
                      "a mixed turn derives exactly one next turn")
                  (let [refusals (lint-refusal-results @connection)
                        second-prompt (:seon.ai/prompt (second @ledger))]
                    (is (= 1 (count refusals)))
                    (is (and (string? second-prompt)
                             (str/includes? second-prompt "expects 2 or 3"))
                        "the mixed turn's refusal finding reaches context")
                    (is (= 3 (or (d/q '[:find (count ?receipt) .
                                         :where
                                         [?receipt :seon.cluster.eval/id _]]
                                       @connection)
                                 0))
                        "both first-turn forms and the corrected form settled")
                    (is (nil? (work/next-agent-work
                               @connection
                               {:seon.cluster.agent/id "mixed-refusal"
                                :seon.cluster.run/process process
                                :seon.cluster.work/now (Date.)}))
                        "the successful correction retires the mixed refusal"))))
              (finally
                (stop-database-events! connection events)
                (async/close! calls)
                (disarm-all! routing)))))))

    (testing "the existing episode cap remains the only bound"
      (with-connection
        (fn [connection ctx]
          (let [routing (armory)
                ledger (atom [])
                calls (async/chan 4)
                events (database-events connection)
                request {:seon.cluster.agent/id "capped-refusal"
                         :seon.cluster.run/process process
                         :seon.cluster.work/now (Date.)}]
            (d/transact connection
                        [{:seon.cluster.agent/id "capped-refusal"}
                         (config-row "r22-capped-refusal"
                                     {:seon.config.run/max-episode-runs 1})])
            (try
              (with-redefs [ai/complete
                            (scripted-completer
                             ledger calls
                             [refused-source completed-source])]
                (let [entry (arm-one! connection ctx routing "capped-refusal")]
                  (outside-trigger! connection "capped-refusal"
                                    "r22-capped-message" "do the work")
                  (async/offer! (:seon.cluster.wake/channel entry) ::wake)
                  (test-support/await-event! calls ::capped-provider-call)
                  (await-quiescence! events ["capped-refusal"] 1)
                  (is (seq (lint-refusal-results @connection))
                      "the first turn closed with its refusal")
                  (is (nil? (work/next-agent-work @connection request))
                      "a refusal at the cap derives no continuation")
                  (is (nil? (async/poll! calls))
                      "the capped refusal published no second call event")
                  (is (= 1 (count @ledger))
                      "the capped refusal made no second provider call")))
              (finally
                (stop-database-events! connection events)
                (async/close! calls)
                (disarm-all! routing)))))))))

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
        (d/transact connection
                    (into [(config-row
                            "trial"
                            {:seon.config.run/max-episode-runs 100})]
                          (map (fn [agent-id]
                                 {:seon.cluster.agent/id agent-id}))
                          agent-ids))
        (try
          (with-redefs [ai/complete
                        (recording-completer
                         ledger (fn [_] "(my.run/complete \"done\")"))]
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
                          :seon.cluster.wake/fault-channel
                          (:seon.cluster.agent/fault-channel @routing)
                          :seon.cluster.wake/key ::route})
            ;; every commit below is delivered by the ROUTING listener,
            ;; concurrently across the agents' independent graphs
            (doseq [[agent-id message-id] triggers]
              (outside-trigger! connection agent-id message-id "work"))
            (let [settled? (await-until
                            #(quiescent? @connection agent-ids))
                  db @connection
                  answers (answers-by-trigger db)
                  run-count (or (d/q '[:find (count ?run) . :where
                                       [?run :seon.cluster.run/id _]]
                                     db)
                                0)
                  duplicate-receipts
                  (->> (d/q '[:find ?run ?ordinal (count ?receipt)
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
                               (or (d/q '[:find (count ?run) .
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
              {:settled? (boolean settled?)
               :answered-once?
               (and (= (count triggers) (count answers))
                    (every? #(= 1 (val %)) answers))
               :ledger-equals-runs? (= (count @ledger) run-count)
               :receipts-unique? (empty? duplicate-receipts)
               :fences-quiet?
               (empty? (d/q '[:find ?error :where
                              [?error :seon.error/id _]]
                            db))
               :per-agent-serial? per-agent-serial?}))
          (finally
            (wake/unlisten! {:seon.cluster.wake/connection connection
                             :seon.cluster.wake/key ::route})
            (disarm-all! routing)))))))

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

;;; ---------------------------------------------------------------------------
;;; 2. park-wake-test — seed 2026072812
;;; ---------------------------------------------------------------------------

(deftest fenced-is-the-derived-quarantine-state
  (with-connection
    (fn [connection ctx]
      (let [routing (armory)]
        (d/transact connection [{:seon.cluster.agent/id "agent-a"}])
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
            _ (d/transact connection [{:seon.cluster.agent/id "agent-a"}])
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

(deftest disarm-does-not-depend-on-the-turn-proc-starting
  (with-connection
    (fn [connection ctx]
      (let [routing (armory)
            tasks (atom [])
            executor
            (reify Executor
              (execute [_ task]
                (swap! tasks conj task)))
            original-definition agent/graph-definition
            take-result (async/promise-chan)]
        (d/transact connection [{:seon.cluster.agent/id "withheld-turn"}])
        (with-redefs
          [agent/graph-definition
           (fn [request]
             (let [definition (original-definition request)]
               (assoc definition
                      :io-exec executor
                      :procs (select-keys (:procs definition) [::agent/turn])
                      :conns [])))]
          (let [entry (arm-one! connection ctx routing "withheld-turn")
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
                         [::agent/armed "withheld-turn"
                          :seon.cluster.loop/completion]
                         observed-completion)
                stopped
                (future
                  (agent/disarm! {:seon.cluster.agent/id "withheld-turn"
                                  :seon.cluster.agent/routing routing}))]
            (try
              (is (= 1 (count @tasks))
                  "Flow accepted the turn runnable without starting it")
              (is (true? (test-support/await-event!
                          take-result ::parked-turn-completion-ready))
                  "arming publishes parked completion before the turn runs")
              (finally
                (doseq [^Runnable task @tasks]
                  (.run task))
                (test-support/await-event! stopped ::withheld-turn-disarmed)))))))))

(deftest park-wake-test
  (with-connection
    (fn [connection ctx]
      (let [routing (armory)
            ledger (atom [])]
        (d/transact connection
                    [{:seon.cluster.agent/id "parked"}
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
                (is (await-until #(pos? (::agent/passes
                                         (turn-ping entry)))))
                (is (zero? (count @ledger)))
                (is (empty? (d/q '[:find ?run :where
                                   [?run :seon.cluster.run/id _]]
                                 @connection))))
              (testing "one committed trigger → exactly one run, one
              provider call"
                (outside-trigger! connection "parked"
                                  "m-2026072812" "one unit of work")
                (async/offer! (:seon.cluster.wake/channel entry) ::wake)
                (is (await-until
                     #(some? (d/q '[:find ?c . :where
                                    [_ :seon.cluster.run/closed-at ?c]]
                                  @connection))))
                (is (= 1 (count @ledger)))
                (is (= {"m-2026072812" 1} (answers-by-trigger @connection))))
              (testing "idle again with ping counts flat: a probe wake
              runs a pass that does no work and calls nothing"
                (let [turns (::agent/turns (turn-ping entry))
                      passes (::agent/passes (turn-ping entry))]
                  (async/offer! (:seon.cluster.wake/channel entry)
                                ::probe)
                  (is (await-until #(> (::agent/passes (turn-ping entry))
                                       passes)))
                  (is (= turns (::agent/turns (turn-ping entry))))
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
        (d/transact connection
                    [{:seon.cluster.agent/id "pausable"}
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
                     #(some? (d/q '[:find ?digest . :where
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
  "Open+claim+close one run answering `message-id`, via the REAL
  transitions with the trigger as tx-meta — the loop's own shape."
  [connection agent-id run-id message-id at]
  (d/transact connection
              {:tx-data (into (run/open-tx {:seon.cluster.run/id run-id
                                            :seon.cluster.run/agent
                                            [:seon.cluster.agent/id agent-id]
                                            :seon.cluster.run/opened-at at})
                              (run/claim-tx {:seon.cluster.run/id run-id
                                             :seon.cluster.run/process process
                                             :seon.cluster.run/live-processes
                                             #{process}
                                             :seon.cluster.run/now at}))
               :tx-meta {:seon.db/trigger
                         [:seon.cluster.message/id message-id]}})
  (d/transact connection
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
        (d/transact connection
                    [{:seon.cluster.agent/id "alice"}
                     {:seon.cluster.agent/id "bob"}
                     (config-row "cap-2026072814"
                                 {:seon.config.run/max-episode-runs 3})])
        ;; episode 1: a human asks
        (outside-trigger! connection "alice" "h1" "human asks")
        (opened-run! connection "alice" "e1" "h1" now)
        (is (= 1 (work/episode-runs @connection "alice")))
        ;; R3: a recorder message (carries `about`) does NOT reset
        (d/transact connection [{:seon.error/id "fault-2026072814"
                                 :seon.error/at now
                                 :seon.error/signature
                                 (apply str (repeat 64 "c"))}])
        (d/transact connection
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
        (d/transact connection
                    [{:seon.cluster.agent/id "reloaded"}
                     (config-row "hot-2026072815"
                                 {:seon.config.run/max-episode-runs 100})])
        (try
          (let [entry (arm-one! connection ctx routing "reloaded")
                ;; the CONTROL: an identically-shaped graph whose turn
                ;; proc is built from the captured fn VALUE — hot
                ;; reload must NOT reach it
                control-channel (async/chan (async/sliding-buffer 1))
                control-handle (assoc base
                                      :seon.cluster.wake/channel
                                      control-channel
                                      :seon.cluster.loop/completion
                                      (async/promise-chan))
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
              (is (await-until #(pos? (::agent/passes (turn-ping entry)))))
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
                      (::agent/passes
                       (:clojure.core.async.flow/state
                        (flow/ping-proc control ::agent/turn)))]
                  (async/offer! control-channel ::wake)
                  (is (await-until
                       #(> (::agent/passes
                            (:clojure.core.async.flow/state
                             (flow/ping-proc control ::agent/turn)))
                           control-passes))
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
        (d/transact connection
                    [{:seon.cluster.agent/id "midfold"}
                     {:seon.cluster.agent/id "waiting"}
                     (config-row "restamp-2026072816"
                                 {:seon.config.run/max-episode-runs 100})])
        ;; the dead process's history: open+claim on an outside
        ;; trigger, a three-form plan, form 0 settled, form 1 STARTED
        ;; and never settled, and a capability-shaped form 2 that had
        ;; never started — killed mid-fold
        (outside-trigger! connection "midfold" "m-dead" "count things")
        (d/transact connection
                    {:tx-data (into (run/open-tx
                                     {:seon.cluster.run/id "run-dead"
                                      :seon.cluster.run/agent
                                      [:seon.cluster.agent/id "midfold"]
                                      :seon.cluster.run/opened-at now})
                                    (run/claim-tx
                                     {:seon.cluster.run/id "run-dead"
                                      :seon.cluster.run/process dead
                                      :seon.cluster.run/live-processes
                                      #{dead}
                                      :seon.cluster.run/now now}))
                     :tx-meta {:seon.db/trigger
                               [:seon.cluster.message/id "m-dead"]}})
        (d/transact connection
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
        (d/transact connection
                    (run/receipt-start-tx {:seon.cluster.run/id "run-dead"
                                           :seon.cluster.eval/ordinal 0
                                           :seon.cluster.eval/at now}))
        (d/transact connection
                    (run/receipt-settle-tx {:seon.cluster.run/id "run-dead"
                                            :seon.cluster.eval/ordinal 0
                                            :seon.cluster.eval/result-edn
                                            "3"}))
        (d/transact connection
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
            ;; BOOT-SHAPE RE-ARM: recover, then re-stamp + prime
            (d/transact connection
                        (run/recover-tx
                         {:seon.cluster.run/id "run-dead"
                          :seon.cluster.run/live-processes #{process}
                          :seon.cluster.run/now (Date.)}))
            (doseq [agent-id ["midfold" "waiting"]]
              (arm-one! connection ctx routing agent-id))
            (is (await-until #(quiescent? @connection
                                          ["midfold" "waiting"])))
            (let [db @connection
                  answers (answers-by-trigger db)
                  run-receipts
                  (d/q '[:find [?receipt ...]
                         :where
                         [?run :seon.cluster.run/id "run-dead"]
                         [?receipt :seon.cluster.eval/run ?run]]
                       db)]
              (testing "recovery ends the interrupted run atomically"
                (is (some? (d/q '[:find ?at .
                                  :where
                                  [?run :seon.cluster.run/id "run-dead"]
                                  [?receipt :seon.cluster.eval/run ?run]
                                  [?receipt :seon.cluster.eval/ordinal 1]
                                  [?receipt
                                   :seon.cluster.eval/interrupted-at ?at]]
                                db)))
                (is (nil? (d/q '[:find ?process .
                                 :where
                                 [?run :seon.cluster.run/id "run-dead"]
                                 [?run :seon.cluster.run/process ?process]]
                               db)))
                (is (some? (d/q '[:find ?at .
                                  :where
                                  [?run :seon.cluster.run/id "run-dead"]
                                  [?run :seon.cluster.run/closed-at ?at]]
                                db)))
                (is (nil? (d/q '[:find ?run .
                                 :where
                                 [?agent :seon.cluster.agent/id "midfold"]
                                 [?agent :seon.cluster.agent/run ?run]]
                               db))))
              (testing "the interrupted plan never continues"
                (is (every? (fn [[_ _ n]] (= 1 n))
                            (d/q '[:find ?run ?ordinal (count ?receipt)
                                   :where
                                   [?receipt :seon.cluster.eval/run ?run]
                                   [?receipt
                                    :seon.cluster.eval/ordinal ?ordinal]]
                                 db)))
                (is (nil? (d/q '[:find ?result .
                                 :where
                                 [?run :seon.cluster.run/id "run-dead"]
                                 [?receipt :seon.cluster.eval/run ?run]
                                 [?receipt :seon.cluster.eval/ordinal 1]
                                 [?receipt
                                  :seon.cluster.eval/result-edn ?result]]
                               db)))
                (is (nil? (d/q '[:find ?receipt .
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
              (testing "the next episode sees honest interruption evidence"
                (let [interrupt-prompts
                      (->> @ledger
                           (map :seon.ai/prompt)
                           (filter string?)
                           (filter #(str/includes? % "interrupted"))
                           vec)
                      prompt (first interrupt-prompts)
                      forms
                      (mapv (fn [ordinal]
                              {:seon.cluster.run.form/ordinal ordinal})
                            (d/q '[:find [?ordinal ...]
                                   :where
                                   [?run :seon.cluster.run/id "run-dead"]
                                   [?form
                                    :seon.cluster.run.form/run ?run]
                                   [?form
                                    :seon.cluster.run.form/ordinal ?ordinal]]
                                 db))
                      receipts (mapv #(d/pull db '[*] %) run-receipts)]
                  (is (= 1 (count interrupt-prompts))
                      (str "only the interrupted agent sees recovery evidence: "
                           (pr-str (mapv :seon.ai/prompt @ledger))))
                  (is (and (string? prompt)
                           (str/includes? prompt "result(s) are missing")))
                  (is (some?
                       (run/interrupted-warning forms receipts)))))))
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
        (d/transact connection
                    [{:seon.cluster.agent/id "held"}
                     (config-row "p2-2026072818"
                                 {:seon.config.run/max-episode-runs 100})])
        (outside-trigger! connection "held" "m-held" "busy elsewhere")
        (d/transact connection
                    {:tx-data (into (run/open-tx
                                     {:seon.cluster.run/id "run-held"
                                      :seon.cluster.run/agent
                                      [:seon.cluster.agent/id "held"]
                                      :seon.cluster.run/opened-at now})
                                    (run/claim-tx
                                     {:seon.cluster.run/id "run-held"
                                      :seon.cluster.run/process other
                                      :seon.cluster.run/live-processes
                                      #{other}
                                      :seon.cluster.run/now now}))
                     :tx-meta {:seon.db/trigger
                               [:seon.cluster.message/id "m-held"]}})
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
              (is (await-until #(>= (::agent/passes (turn-ping entry)) 1)))
              (is (zero? (count @ledger))
                  "zero duplicate provider dispatches across the
                   interleaving")
              (is (zero? (::agent/turns (turn-ping entry))))
              (is (= other (d/q '[:find ?p . :where
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
            message-count (atom 0)]
        (d/transact connection
                    [(config-row "route-trial"
                                 {:seon.config.run/max-episode-runs 100})])
        (try
          (with-redefs [ai/complete
                        (recording-completer
                         ledger (fn [_] "(my.run/complete \"done\")"))]
            (wake/route! {:seon.cluster.wake/connection connection
                          :seon.cluster.wake/channels
                          (fn [] (agent/channels routing))
                          :seon.cluster.wake/fenced?
                          (fn [agent-eid channel]
                            (agent/fenced-route? routing agent-eid channel))
                          :seon.cluster.wake/armer-channel armer-channel
                          :seon.cluster.wake/render-channel
                          (async/chan (async/sliding-buffer 1))
                          :seon.cluster.wake/fault-channel
                          (:seon.cluster.agent/fault-channel @routing)
                          :seon.cluster.wake/key ::route-trial})
            (doseq [[op index] (map vector operations (range))]
              (let [agent-id (str "ra-" (count @created))]
                (case op
                  :create
                  (do (d/transact connection
                                  [{:seon.cluster.agent/id agent-id}])
                      (swap! created conj agent-id))

                  :create-and-message
                  ;; the one-commit window the armer belt exists for:
                  ;; the recipient's graph cannot exist yet
                  (do (d/transact
                       connection
                       [{:seon.cluster.agent/id agent-id}
                        {:seon.cluster.message/id
                         (str "rm-" index)
                         :seon.cluster.message/to
                         {:seon.cluster.agent/id agent-id}
                         :seon.cluster.message/content "hello, newborn"
                         :seon.cluster.message/at (Date.)}])
                      (swap! created conj agent-id)
                      (swap! message-count inc))

                  :message
                  (when-let [target (first @created)]
                    (outside-trigger! connection target
                                      (str "rm-" index) "more work")
                    (swap! message-count inc)))))
            (let [agent-ids @created
                  settled? (await-until
                            #(and (= (set agent-ids)
                                     (set (keys (:seon.cluster.agent/armed
                                                 @routing))))
                                  (quiescent? @connection agent-ids)))
                  db @connection
                  answers (answers-by-trigger db)]
              {:settled? (boolean settled?)
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
               (and (= @message-count (count answers))
                    (every? #(= 1 (val %)) answers))}))
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
        (d/transact connection
                    [{:seon.cluster.agent/id "waiter"}
                     (config-row "wait-2026072820"
                                 {:seon.config.run/max-episode-runs 100})])
        (try
          (with-redefs [ai/complete
                        (recording-completer
                         ledger (fn [_] "(my.run/wait \"need input\")"))]
            (arm-one! connection ctx routing "waiter")
            (outside-trigger! connection "waiter" "m-wait" "hold on")
            (let [entry (agent/armed routing "waiter")]
              (async/offer! (:seon.cluster.wake/channel entry) ::wake))
            (is (await-until #(quiescent? @connection ["waiter"])))
            (let [db @connection
                  run-id (d/q '[:find ?id . :where
                                [?run :seon.cluster.run/id ?id]]
                              db)
                  settle-tx (d/q '[:find ?tx . :where
                                   [?receipt
                                    :seon.cluster.eval/result-edn _ ?tx]]
                                 db)
                  close-tx (d/q '[:find ?tx . :where
                                  [_ :seon.cluster.run/closed-at _ ?tx]]
                                db)]
              (testing "settle and close share ONE transaction"
                (is (some? settle-tx))
                (is (= settle-tx close-tx)))
              (testing "no basis carries an unheld open planned run —
              the P1 feeder state is unrepresentable"
                (let [txs (sort (d/q '[:find [?tx ...] :where
                                       [_ _ _ ?tx]]
                                     db))]
                  (is (not-any?
                       (fn [tx]
                         (let [basis (d/as-of db tx)
                               run (d/pull basis '[*]
                                           [:seon.cluster.run/id run-id])]
                           (and (some? (:db/id run))
                                (nil? (:seon.cluster.run/closed-at run))
                                (nil? (:seon.cluster.run/process run))
                                (some? (:seon.cluster.run/plan-digest
                                        run)))))
                       txs))))
              (testing "the note survives in the receipt"
                (is (str/includes?
                     (d/q '[:find ?edn . :where
                            [_ :seon.cluster.eval/result-edn ?edn]]
                          db)
                     "awaiting input")))
              (testing "the agent's next trigger opens a NEW run"
                (outside-trigger! connection "waiter" "m-next" "resume")
                (let [entry (agent/armed routing "waiter")]
                  (async/offer! (:seon.cluster.wake/channel entry)
                                ::wake))
                (is (await-until #(quiescent? @connection ["waiter"])))
                (is (= 2 (or (d/q '[:find (count ?run) . :where
                                    [?run :seon.cluster.run/id _]]
                                  @connection)
                             0)))
                (is (= {"m-wait" 1 "m-next" 1}
                       (answers-by-trigger @connection))))))
          (finally
            (disarm-all! routing)))))))
