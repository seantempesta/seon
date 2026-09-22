(ns ^{:seon.test/long
      "48.642 s slowest pool member: every test boots a real armed cluster; fault-to-fact delivery is the proof."}
  seon.cluster.armed-test
  "The armed layers of a booted cluster: root, the loop, the fault path.

  These are the falsifiers for the wiring whose ABSENCE was the highest
  -value defect in the error grounding (D4): `start!` completed boot and
  then stopped, so the run loop existed only inside drive scripts and
  every core fault in a live cluster went into a
  `(sliding-buffer 100)` that nobody read
  (`reference-code/core.async/.../flow/impl.clj:99-102`). A sliding
  buffer never blocks and never rejects — it silently discards the
  oldest — so the failure mode was not an exception anywhere, it was
  errors ceasing to exist.

  EVERY TEST HERE BOOTS A REAL CLUSTER. That is the point: the class of
  defect being fenced is fixture-vs-live-boot, and a fixture that
  installs its own attributes and wires its own channels cannot see it.
  Each test uses its own cluster name and its own root directory, so
  they share nothing but the JVM.

  NO MODEL CALL EVER HAPPENS IN THIS SUITE, and one test asserts exactly
  that about boot itself: `seon.ai/complete` is redefined to count and
  throw, so a call would both fail the count and fail loudly."
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.ai :as ai]
            [seon.bootstrap :as bootstrap]
            [seon.cluster :as cluster]
            [seon.cluster.boot :as boot]
            [seon.cluster.agent :as agent]
            [seon.turn :as turn]
            [seon.config :as config]
            [seon.db :as db]
            [seon.schema]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as test-support])
  (:import [java.util Date]
           [java.util.concurrent CountDownLatch]))

(set! *warn-on-reflection* true)

(defn- await-fact
  "Return the first truthy `probe` result published by a database value."
  [connection probe]
  (let [events (async/promise-chan)
        key (keyword (str (ns-name *ns*)) (str (gensym "fact-")))]
    (d/listen connection key
              (fn [report]
                (when-let [value (probe (:db-after report))]
                  (async/offer! events value))))
    (try
      ;; Register interest first, then derive current state. A commit
      ;; between these operations is observed by one side or the other.
      (when-let [value (probe @connection)]
        (async/offer! events value))
      (test-support/await-event! events "database fact")
      (finally
        (d/unlisten connection key)))))

(defn- with-cluster
  "Boot one real cluster, await its bootstrap plan, and always stop it."
  [name body]
  (let [root (str "tmp/armed-test/" name)]
    (test-support/delete-recursively! root)
    (test-support/populate-published-root! root)
    (let [instance (boot/start! {:seon.boot/cluster-name name
                                    :seon.boot/root root})]
      (try
        (await-fact
         (:seon.boot/cluster-connection instance)
         (fn [db]
           (db/q '[:find ?closed-at .
                  :in $ ?run-id
                  :where
                  [?run :seon.turn/id ?run-id]
                  [?run :seon.turn/closed-tx ?closed-at]]
                db (bootstrap/run-id "root"))))
        (body instance)
        (finally
          (boot/stop! instance))))))

(defn- errors
  [db]
  (db/q '[:find [(pull ?error [*]) ...]
         :where [?error :seon.error/id _]]
       db))

(defn- messages-to
  [db agent-id]
  (db/q '[:find [(pull ?message [*]) ...]
         :in $ ?agent-id
         :where
         [?agent :seon.agent/id ?agent-id]
         [?message :seon.message/to ?agent]]
       db agent-id))

;;; ---------------------------------------------------------------------------
;;; What boot leaves standing
;;; ---------------------------------------------------------------------------

(deftest ^{:seon.test/fixture-observation "The assertions observe boot-created root agents, maintenance tasks and armed graphs, absent from an ordinary branch fixture."} boot-seeds-the-root-agent-and-arms-the-loop
  (with-cluster
    "armed"
    (fn [instance]
      (let [connection (:seon.boot/cluster-connection instance)]
        (testing "fresh boot reaches READY"
          (is (some? (:seon.boot/ready-ms (boot/readiness instance)))))
        (testing "the root agent exists, so the escalation dial names
        something real rather than something hoped for"
          (is (= "root"
                 (db/q '[:find ?id . :in $ ?id
                        :where [?agent :seon.agent/id ?id]]
                      @connection "root"))))
        (testing "root owns the five queryable maintenance tasks"
          (is (= #{["root/maintenance/footprint"
                    "root/maintenance/footprint-schedule"]
                   ["root/maintenance/reap-dead-roots"
                    "root/maintenance/reap-dead-roots-schedule"]
                   ["root/maintenance/rotate-logs"
                    "root/maintenance/rotate-logs-schedule"]
                   ["root/maintenance/process-census"
                    "root/maintenance/process-census-schedule"]
                   ["root/maintenance/compact"
                    "root/maintenance/compact-schedule"]}
                 (db/q '[:find ?task-id ?schedule-id
                         :where
                         [?owner :seon.agent/id "root"]
                         [?task :seon.schedule.task/owner ?owner]
                         [?task :seon.schedule.task/id ?task-id]
                         [?task :seon.schedule.task/schedule ?schedule]
                         [?schedule :seon.schedule/id ?schedule-id]]
                       @connection))))
        (testing "the ARMER proc is running on the cluster's own graph"
          (is (= :running
                 (:clojure.core.async.flow/status
                  (flow/ping-proc (:seon.flow/graph instance)
                                  :seon.agent/armer)))))
        (testing "and the root agent has its OWN armed graph (F1): the
        armer's boot prime derived the agent set from facts and armed
        one graph per agent, mailbox and turn procs both running"
          (let [routing (:seon.agent/routing instance)
                entry (seon.cluster.agent/armed routing "root")]
            (is (some? entry))
            (is (= :running
                   (:clojure.core.async.flow/status
                    (flow/ping-proc (:seon.flow/graph entry)
                                    :seon.agent/mailbox))))
            (is (= :running
                   (:clojure.core.async.flow/status
                    (flow/ping-proc (:seon.flow/graph entry)
                                    :seon.agent/turn))))))
        (testing "and the fault path exists — this is D4"
          (is (some? (:seon.flow/error-fanout instance)))
          (is (some? (:seon.flow/fault-channel
                      (:seon.flow/error-fanout instance)))))
        (testing "the handle retains identity while AI settings stay live"
          (let [handle (:seon.turn.loop/cluster instance)]
            (is (seon.schema/valid-candidate-value? (seon.schema/handed-projection) :seon.turn.loop/cluster
                                                    handle))
            (is (= "armed" (:seon.cluster/name handle)))
            (is (= "root" (:seon.config.error/escalate-to handle)))
            (is (empty? (select-keys handle
                                     [:seon.ai/primary
                                      :seon.ai/backup
                                      :seon.ai.retry/strategy])))
            (let [settings (ai/settings
                            (config/effective @connection "armed")
                            (ai/agent-overlay @connection "root"))
                  targets (ai/targets (seon.schema/handed-projection) settings)]
              (is (str/starts-with?
                   (:seon.ai/endpoint (:seon.ai/primary targets))
                   "https://"))
              ;; the shipped config carries the OpenRouter backup since
              ;; the 402-failover work — a boot derives BOTH targets
              (is (str/starts-with?
                   (:seon.ai/endpoint (:seon.ai/backup targets))
                   "https://"))
              (is (seon.schema/valid-candidate-value? (seon.schema/handed-projection)
                   :seon.ai.retry/strategy
                   (ai/retry-strategy settings))))))))))

(deftest ^{:seon.test/fixture-observation "Two real cluster boots must acquire distinct live program contexts over the same published root."} two-clusters-in-one-jvm-own-distinct-live-program-contexts
  (let [root "tmp/armed-test/live-program-boundary"]
    (test-support/delete-recursively! root)
    (test-support/populate-published-root! root)
    (let [left (boot/start! {:seon.boot/cluster-name "live-left"
                                :seon.boot/root root})
          right (boot/start! {:seon.boot/cluster-name "live-right"
                                 :seon.boot/root root})]
      (try
        (doseq [instance [left right]]
          (await-fact
           (:seon.boot/cluster-connection instance)
           (fn [db]
             (db/q '[:find ?closed-at .
                    :in $ ?run-id
                    :where
                    [?run :seon.turn/id ?run-id]
                    [?run :seon.turn/closed-tx ?closed-at]]
                  db (bootstrap/run-id "root")))))
        (let [left-handle (:seon.turn.loop/cluster left)
              right-handle (:seon.turn.loop/cluster right)
              left-ctx (:seon.sci.eval/ctx left-handle)
              right-ctx (:seon.sci.eval/ctx right-handle)
              request
              (fn [handle agent-id source]
                (sci.eval/evaluate
                 {:seon.cluster.eval/source source
                  :seon.sci.admit/caps (:seon.sci.admit/caps handle)
                  :seon.sci.eval/time-limit-ms
                  (:seon.config.eval/time-limit-ms handle)
                  :seon.config/on-core-error
                  (:seon.config/on-core-error handle)
                  :seon.sci.eval/ctx (:seon.sci.eval/ctx handle)
                  :seon.agent/id agent-id}))
              definition
              (request left-handle "root"
                       (str
                        "(defn ^{:malli/schema [:=> [:cat :int] :int]} "
                        "shared-live [x] (inc x))"))
              shared-call
              (request left-handle "root"
                       "(my.agents.root/shared-live 41)")
              isolated-call
              (request right-handle "root"
                       "(my.agents.root/shared-live 41)")]
          (is (not (identical? (:env left-ctx) (:env right-ctx)))
              "cluster construction allocates distinct SCI env atoms")
          (is (nil? (:seon.cluster.eval/error definition)))
          (is (= 42 (:seon.sci.admit/value shared-call))
              "the committed agent resolves its left-cluster definition")
          (is (some? (:seon.sci.kernel/guard-observation (:seon.sci.admit/value isolated-call)))
              "the right cluster cannot see the left cluster's definition"))
        (finally
          (boot/stop! right)
          (boot/stop! left))))))

(deftest ^{:seon.test/fixture-observation "The observation is that the complete boot and initial wake spend no model call, not merely that database facts exist."} booting-spends-no-model-call
  ;; The system-authored bootstrap plan evaluates locally; boot must not
  ;; spend a provider call. After that one run, an empty wake stays free.
  (let [calls (atom 0)]
    (with-redefs [ai/complete (fn [_projection _]
                                (swap! calls inc)
                                (throw (ex-info "boot made a model call" {})))]
      (with-cluster
        "idle"
        (fn [instance]
          (let [routing (:seon.agent/routing instance)
                entry (seon.cluster.agent/armed routing "root")
                derived (CountDownLatch. 1)
                next-agent-work turn/next-agent-work
                connection (:seon.boot/cluster-connection instance)
                run-count (fn []
                            (db/q '[:find (count ?run) .
                                   :in $ ?agent-id
                                   :where
                                   [?agent :seon.agent/id ?agent-id]
                                   [?run :seon.turn/agent ?agent]]
                                 @connection "root"))]
            (is (= 1 (run-count))
                "the one local bootstrap plan is the only durable run")
            (with-redefs [turn/next-agent-work
                          (fn [& arguments]
                            (let [result (apply next-agent-work arguments)]
                              (.countDown derived)
                              result))]
              (async/offer! (:seon.cluster.wake/channel entry) ::probe)
              (test-support/await-event! derived
                                         "empty-wake work derivation")
              (is (= 1 (run-count))
                  "a wake with no facts to act on is not work"))
            (is (zero? @calls)
                "and nothing anywhere called the model")))))))

(deftest ^{:seon.test/fixture-observation "The observation is listener installation during real boot arming, which an already-populated branch never executes."} a-message-committed-during-boot-arming-is-conserved
  (let [name "arming-window"
        root (str "tmp/armed-test/" name)
        primed (CountDownLatch. 1)
        next-agent-work turn/next-agent-work
        arm! agent/arm!]
    (test-support/delete-recursively! root)
    (test-support/populate-published-root! root)
    (with-redefs
      [turn/next-agent-work
       (fn [& arguments]
         (let [result (apply next-agent-work arguments)]
           (.countDown primed)
           result))
       agent/arm!
       (fn [request]
         (let [entry (arm! request)]
           ;; This is the historical loss window: the arm prime has
           ;; completed, then a message commits before arm! returns.
           ;; Boot must already have registered the routing listener.
           (test-support/await-event! primed "boot arm prime")
           (test-support/transacted!
                        (:seon.db/connection
                         (:seon.turn.loop/cluster request))
                        [{:seon.message/id "boot-window-message" :seon.message/to [:seon.agent/id "root"] :seon.message/content "answer during boot"}])
           entry))
       ai/complete
       (fn [_projection _request]
         {:seon.ai/text "(seon.run/complete \"answered\")"})]
      (let [instance (boot/start! {:seon.boot/cluster-name name
                                      :seon.boot/root root})]
        (try
          (let [run
                (await-fact
                 (:seon.boot/cluster-connection instance)
                 (fn [database]
                   (db/q
                    '[:find (pull ?run
                                  [:seon.turn/id
                                   {:seon.turn/trigger
                                    [:seon.message/id]}]) .
                      :in $ ?message-id
                      :where
                      [?message :seon.message/id ?message-id]
                      [?run :seon.turn/trigger ?message]]
                    database "boot-window-message")))]
            (is (= "boot-window-message"
                   (get-in run [:seon.turn/trigger
                                :seon.message/id]))
                "the committed message opened a run without a later wake"))
          (finally
            (boot/stop! instance)))))))

;;; ---------------------------------------------------------------------------
;;; THE VISIBILITY PROPERTY — an escaped Throwable becomes facts
;;; ---------------------------------------------------------------------------

(deftest ^{:seon.test/fixture-observation "The observation requires the booted agent graph and fault committer to persist and route a proc fault."} an-escaped-throwable-becomes-a-fact-and-a-message
  (with-cluster
    "faulting"
    (fn [instance]
      (let [connection (:seon.boot/cluster-connection instance)
            handle (:seon.turn.loop/cluster instance)
            routing (:seon.agent/routing instance)
            entry (seon.cluster.agent/armed routing "root")
            graph (:seon.flow/graph entry)]
        ;; INJECTED AT THE REAL SEAM: the turn proc's transform calls
        ;; `next-agent-work`, so a throw there is a throw inside a
        ;; running flow proc — the exact path §1.2's report shapes come
        ;; from. Nothing here touches the error channel by hand.
        (with-redefs [turn/next-agent-work
                      (fn [& _]
                        (throw (ex-info "injected core fault"
                                        {})))]
          (async/offer! (:seon.cluster.wake/channel entry) ::fault)
          (let [fact (first (await-fact connection (comp seq errors)))]
            (testing "exactly one error fact, carrying what happened"
              (is (some? fact))
              (is (= "injected core fault" (:seon.error/message fact)))
              (is (= "clojure.lang.ExceptionInfo"
                     (:seon.error/throwable-class fact)))
              (is (= :seon.agent/turn (:seon.error/proc fact)))
              (is (= "root"
                     (db/q '[:find ?agent-id .
                            :in $ ?error-id
                            :where
                            [?error :seon.error/id ?error-id]
                            [?error :seon.error/agent ?agent]
                            [?agent :seon.agent/id ?agent-id]]
                          @connection (:seon.error/id fact)))
                  "the fault arrived TAGGED with its agent — structural
                   provenance from the error-channel join, never a
                   global attribution query")
              (is (= (:seon.db.process/id handle)
                     (:seon.error/process fact)))
              (is (re-matches #"^[0-9a-f]{64}$" (:seon.error/signature fact))))
            (testing "whose data-edn READS BACK — which is what proves the
            one codec ran and the proc's live state did not escape"
              (is (some? (:seon.error/data-edn fact)))
              (is (map? (edn/read-string (:seon.error/data-edn fact)))))
            (testing "and root was told, because nobody else could be"
              ;; WAIT FOR THE MESSAGE THAT NAMES THIS FACT, never for a
              ;; COUNT. Counting here was the one-in-three flake: the
              ;; injected fault storms until the fence bounds it, so
              ;; "exactly one message" is true only in the instant
              ;; between the first commit and the second. What is
              ;; actually being claimed is that the fault reached root
              ;; with its evidence; the BOUND on how many is the storm
              ;; test below, and an upper bound is monotone-safe where
              ;; an equality is a race.
              (let [message (await-fact
                             connection
                             (fn [db]
                               (first
                                (filter
                                 (fn [candidate]
                                   (str/includes?
                                    (:seon.message/content candidate)
                                    (:seon.error/id fact)))
                                 (messages-to db "root")))))]
                (is (some? message) "the message names the evidence")
                (is (some? (:seon.message/about message)) "the notification names its evidence")
                (is (some? (:seon.message/from message))
                    "the population sender marks the notification inside")))))
        (testing "THE STORM IS BOUNDED, and this is the falsifier for the
        cycle the live probe found: an explanation message is a commit,
        a commit wakes the loop through :seon.message/to, and a
        woken loop hits the same broken code. One injected throw made
        six faults in 1.5s before the fence bounded the MESSAGES."
          (let [limit (:seon.config.error/recurrence-limit handle)
                told (await-fact connection
                                 (fn [db]
                                   (let [messages (messages-to db "root")]
                                     (when (>= (count messages) 2) messages))))]
            (is (<= (count told) (inc limit))
                (str "root was told " (count told) " times for one signature"))
            (is (apply = (map :seon.error/signature (errors @connection)))
                "and every fact of the storm shares one signature, which is
                 what makes the count a fence rather than a guess")))

        (testing "FAIL LOUD IS NOT FALL DOWN: the proc survived its own
        throw with pre-step state, and the next wake is an ordinary pass"
          (async/offer! (:seon.cluster.wake/channel entry) ::after)
          (is (= :running
                 (:clojure.core.async.flow/status
                  (flow/ping-proc graph :seon.agent/turn
                                  :timeout-ms 5000))))
          (is (empty? (filter #(= :seon.flow/fault-channel-overflow
                                  (:clojure.core.async.flow/op %))
                              (errors @connection)))
              "and no overflow fact was needed on the way"))))))

(deftest ^{:seon.test/fixture-observation "The failure is injected at the first real boot resume transition, before an ordinary branch fixture has any running proc."} the-first-cluster-proc-fault-at-resume-becomes-a-fact
  (let [name "first-cluster-fault"
        root (str "tmp/armed-test/" name)
        armer-step agent/armer-step
        injected? (atom false)]
    (test-support/delete-recursively! root)
    (test-support/populate-published-root! root)
    (with-redefs
      [agent/armer-step
       (fn
         ([]
          (armer-step))
         ([args]
          (armer-step args))
         ([state transition]
          (if (and (= ::flow/resume transition)
                   (compare-and-set! injected? false true))
            (throw
             (ex-info "first cluster proc fault"
                      {}))
            (armer-step state transition)))
         ([state input message]
          (armer-step state input message)))
       ai/complete
       (fn [_projection _request]
         {:seon.ai/text "(seon.run/complete \"fault observed\")"})]
      (let [instance (boot/start! {:seon.boot/cluster-name name
                                      :seon.boot/root root})]
        (try
          (let [connection (:seon.boot/cluster-connection instance)
                fact
                (await-fact
                 connection
                 (fn [database]
                   (first
                    (filter
                     #(= "first cluster proc fault" (:seon.error/message %))
                     (errors database)))))]
            (is (true? @injected?)
                "the fault was injected at the first resume transition")
            (is (= "first cluster proc fault" (:seon.error/message fact)))
            (is (= :seon.agent/armer (:seon.error/proc fact)))
            (is (= (:seon.db.process/id
                    (:seon.turn.loop/cluster instance))
                   (:seon.error/process fact))
                "the first fault is durable with cluster provenance"))
          (finally
            ;; Flow reports a failed transition and retains the proc's
            ;; pre-transition status (`:paused`). Restore the lifecycle that
            ;; this test deliberately perturbed so cluster teardown can send
            ;; its armer-quiescence request before stopping the graph.
            (flow/resume (:seon.flow/graph instance))
            (boot/stop! instance)))))))
