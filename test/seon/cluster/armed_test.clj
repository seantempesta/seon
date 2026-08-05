(ns ^{:seon.test/long "Every test boots a real armed cluster to cover fixture-vs-live wiring."}
  seon.cluster.armed-test
  "The armed layers of a booted cluster: root, the loop, the fault path.

  These are the falsifiers for the wiring whose ABSENCE was the highest
  -value defect in the error grounding (D4): `start!` built the tower and
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
            [seon.cluster.agent :as agent]
            [seon.cluster.run :as run]
            [seon.cluster.work :as work]
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
    (cluster/refresh-source! root)
    (let [instance (cluster/start! {:seon.boot/cluster-name name
                                    :seon.boot/root root})]
      (try
        (await-fact
         (:seon.boot/cluster-connection instance)
         (fn [db]
           (db/q '[:find ?closed-at .
                  :in $ ?run-id
                  :where
                  [?run :seon.cluster.run/id ?run-id]
                  [?run :seon.cluster.run/closed-at ?closed-at]]
                db (bootstrap/run-id "root"))))
        (body instance)
        (finally
          (cluster/stop! instance))))))

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
         [?agent :seon.cluster.agent/id ?agent-id]
         [?message :seon.cluster.message/to ?agent]]
       db agent-id))

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

;;; ---------------------------------------------------------------------------
;;; What boot leaves standing
;;; ---------------------------------------------------------------------------

(deftest boot-seeds-the-root-agent-and-arms-the-loop
  (with-cluster
    "armed"
    (fn [instance]
      (let [connection (:seon.boot/cluster-connection instance)]
        (testing "the root agent exists, so the escalation dial names
        something real rather than something hoped for"
          (is (= "root"
                 (db/q '[:find ?id . :in $ ?id
                        :where [?agent :seon.cluster.agent/id ?id]]
                      @connection "root"))))
        (testing "the ARMER proc is running on the cluster's own graph"
          (is (= :running
                 (:clojure.core.async.flow/status
                  (flow/ping-proc (:seon.flow/graph instance)
                                  :seon.cluster.agent/armer)))))
        (testing "and the root agent has its OWN armed graph (F1): the
        armer's boot prime derived the agent set from facts and armed
        one graph per agent, mailbox and turn procs both running"
          (let [routing (:seon.cluster.agent/routing instance)
                entry (seon.cluster.agent/armed routing "root")]
            (is (some? entry))
            (is (= :running
                   (:clojure.core.async.flow/status
                    (flow/ping-proc (:seon.flow/graph entry)
                                    :seon.cluster.agent/mailbox))))
            (is (= :running
                   (:clojure.core.async.flow/status
                    (flow/ping-proc (:seon.flow/graph entry)
                                    :seon.cluster.agent/turn))))))
        (testing "and the fault path exists — this is D4"
          (is (some? (:seon.flow/error-fanout instance)))
          (is (some? (:seon.flow/fault-channel
                      (:seon.flow/error-fanout instance))))
          (is (zero? @(:seon.error/drops instance))))
        (testing "the handle retains identity while AI settings stay live"
          (let [handle (:seon.cluster.loop/cluster instance)]
            (is (seon.schema/valid-candidate-value? :seon.cluster.loop/cluster
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
                  targets (ai/targets settings)]
              (is (str/starts-with?
                   (:seon.ai/endpoint (:seon.ai/primary targets))
                   "https://"))
              (is (not (contains? targets :seon.ai/backup)))
              (is (seon.schema/valid-candidate-value?
                   :seon.ai.retry/strategy
                   (ai/retry-strategy settings))))))))))

(deftest two-clusters-in-one-jvm-own-distinct-live-program-contexts
  (let [root "tmp/armed-test/live-program-boundary"]
    (test-support/delete-recursively! root)
    (cluster/refresh-source! root)
    (let [left (cluster/start! {:seon.boot/cluster-name "live-left"
                                :seon.boot/root root})
          right (cluster/start! {:seon.boot/cluster-name "live-right"
                                 :seon.boot/root root})]
      (try
        (doseq [instance [left right]]
          (await-fact
           (:seon.boot/cluster-connection instance)
           (fn [db]
             (db/q '[:find ?closed-at .
                    :in $ ?run-id
                    :where
                    [?run :seon.cluster.run/id ?run-id]
                    [?run :seon.cluster.run/closed-at ?closed-at]]
                  db (bootstrap/run-id "root")))))
        (let [left-handle (:seon.cluster.loop/cluster left)
              right-handle (:seon.cluster.loop/cluster right)
              left-ctx (:seon.sci.eval/ctx left-handle)
              right-ctx (:seon.sci.eval/ctx right-handle)
              request
              (fn [handle agent-id source]
                (sci.eval/evaluate
                 {:seon.cluster.run.form/source source
                  :seon.sci.admit/caps (:seon.sci.admit/caps handle)
                  :seon.sci.eval/time-limit-ms
                  (:seon.config.eval/time-limit-ms handle)
                  :seon.config/on-core-error
                  (:seon.config/on-core-error handle)
                  :seon.sci.eval/ctx (:seon.sci.eval/ctx handle)
                  :seon.cluster.agent/id agent-id}))
              definition
              (request left-handle "agent-a"
                       (str
                        "(defn ^{:malli/schema [:=> [:cat :int] :int]} "
                        "shared-live [x] (inc x))"))
              shared-call
              (request left-handle "agent-b"
                       "(my.agents.agent-a/shared-live 41)")
              isolated-call
              (request right-handle "agent-b"
                       "(my.agents.agent-a/shared-live 41)")]
          (is (not (identical? (:env left-ctx) (:env right-ctx)))
              "cluster construction allocates distinct SCI env atoms")
          (is (nil? (:seon.cluster.eval/error definition)))
          (is (= 42 (:seon.sci.admit/value shared-call))
              "another agent in the left cluster sees the live definition")
          (is (= :seon.sci.eval/evaluation-failed
                 (:seon.error/kind
                  (:seon.sci.admit/value isolated-call)))
              "the right cluster cannot see the left cluster's definition"))
        (finally
          (cluster/stop! right)
          (cluster/stop! left))))))

(deftest booting-spends-no-model-call
  ;; The system-authored bootstrap plan evaluates locally; boot must not
  ;; spend a provider call. After that one run, an empty wake stays free.
  (let [calls (atom 0)]
    (with-redefs [ai/complete (fn [_]
                                (swap! calls inc)
                                (throw (ex-info "boot made a model call" {})))]
      (with-cluster
        "idle"
        (fn [instance]
          (let [routing (:seon.cluster.agent/routing instance)
                entry (seon.cluster.agent/armed routing "root")
                derived (CountDownLatch. 1)
                next-agent-work work/next-agent-work
                connection (:seon.boot/cluster-connection instance)
                run-count (fn []
                            (db/q '[:find (count ?run) .
                                   :in $ ?agent-id
                                   :where
                                   [?agent :seon.cluster.agent/id ?agent-id]
                                   [?run :seon.cluster.run/agent ?agent]]
                                 @connection "root"))]
            (is (= 1 (run-count))
                "the one local bootstrap plan is the only durable run")
            (with-redefs [work/next-agent-work
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

(deftest a-message-committed-during-boot-arming-is-conserved
  (let [name "arming-window"
        root (str "tmp/armed-test/" name)
        primed (CountDownLatch. 1)
        called (CountDownLatch. 1)
        next-agent-work work/next-agent-work
        arm! agent/arm!]
    (test-support/delete-recursively! root)
    (cluster/refresh-source! root)
    (with-redefs
      [work/next-agent-work
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
           (db/transact!
            (:seon.db/connection
             (:seon.cluster.loop/cluster request))
            [{:seon.cluster.message/id "boot-window-message"
              :seon.cluster.message/to [:seon.cluster.agent/id "root"]
              :seon.cluster.message/content "answer during boot"
              :seon.cluster.message/at (Date.)}])
           entry))
       ai/complete
       (fn [_request]
         (.countDown called)
         {:seon.ai/text "(my.run/complete \"answered\")"})]
      (let [instance (cluster/start! {:seon.boot/cluster-name name
                                      :seon.boot/root root})]
        (try
          (test-support/await-event! called "boot-window model call")
          (is (= "boot-window-message"
                 (db/q '[:find ?message-id .
                        :where
                        [?run :seon.cluster.run/trigger ?message]
                        [?message :seon.cluster.message/id ?message-id]]
                      @(:seon.boot/cluster-connection instance)))
              "the committed message opened a run without a later wake")
          (finally
            (cluster/stop! instance)))))))

;;; ---------------------------------------------------------------------------
;;; THE VISIBILITY PROPERTY — an escaped Throwable becomes facts
;;; ---------------------------------------------------------------------------

(deftest refused-refusal-settlement-faults-and-recovers-on-reboot
  (let [name "terminal-refusal-fault"
        root (str "tmp/armed-test/" name)
        run-id (atom nil)]
    (test-support/delete-recursively! root)
    (cluster/refresh-source! root)
    (let [instance (cluster/start! {:seon.boot/cluster-name name
                                    :seon.boot/root root})]
      (try
        (let [connection (:seon.boot/cluster-connection instance)
              routing (:seon.cluster.agent/routing instance)
              entry (agent/armed routing "root")
              transact! db/transact!]
          (with-redefs
            [ai/complete
             (fn [_]
               {:seon.ai/text "(identity 1)"})
             sci.eval/evaluate
             (fn [_]
               {:seon.sci.admit/value 1
                :seon.cluster.eval/result-edn "1"})
             db/transact!
             (fn [target transaction]
               (cond
                 (transition-transaction?
                  transaction #'run/receipt-settle-call)
                 {:seon.error/kind :seon.cluster.run/refused
                  :seon.error/message "injected original terminal refusal"}

                 (transition-transaction?
                  transaction #'run/receipt-refusal-call)
                 {:seon.error/kind :seon.db/rejected
                  :seon.error/message "injected refusal settlement rejection"
                  :seon.error/data {:error :transact/schema}}

                 :else
                 (transact! target transaction)))]
            (db/transact!
             connection
             [{:seon.cluster.message/id "terminal-refusal-trigger"
               :seon.cluster.message/to [:seon.cluster.agent/id "root"]
               :seon.cluster.message/content "exercise the terminal seam"
               :seon.cluster.message/at (Date.)}])
            ;; The database fact is downstream of the agent graph's own
            ;; error channel, the shared fault channel, and the R41
            ;; committer. No test code touches those channels.
            (let [fact
                  (first
                   (await-fact
                    connection
                    (fn [db]
                      (seq
                       (filter
                        #(= :seon.cluster.loop/terminal-refusal-settlement-refused
                            (:seon.error/kind %))
                        (errors db))))))
                  rid
                  (db/q '[:find ?run-id .
                         :in $ ?error-id
                         :where
                         [?error :seon.error/id ?error-id]
                         [?error :seon.error/run ?run]
                         [?run :seon.cluster.run/id ?run-id]]
                       @connection (:seon.error/id fact))]
              (reset! run-id rid)
              (is (some? fact) "the refused settlement became a fault fact")
              (is (= "clojure.lang.ExceptionInfo"
                     (:seon.error/throwable-class fact)))
              (is (= :seon.cluster.agent/turn (:seon.error/proc fact))
                  "it traversed the agent graph's error channel")
              (is (pos?
                   (:seon.flow/panicked
                    (:clojure.core.async.flow/state
                     (flow/ping-proc
                      (:seon.flow/fault-graph
                       (:seon.flow/error-fanout instance))
                      :seon.flow/fault-committer))))
                  "the shipped :panic dial took R41's loud path")
              (let [receipt
                    (db/q '[:find (pull ?receipt [*]) .
                           :in $ ?run-id
                           :where
                           [?run :seon.cluster.run/id ?run-id]
                           [?receipt :seon.cluster.eval/run ?run]]
                         @connection rid)
                    run
                    (db/pull @connection '[*]
                            [:seon.cluster.run/id rid])]
                (is (false? (run/terminal? receipt))
                    "the function never reported a settlement that did not land")
                (is (nil? (:seon.cluster.run/closed-at run)))
                (is (some? (:seon.cluster.run/process run))
                    "the held state is left for boot recovery"))
              (testing "the fence is a RECOGNIZED state, not a second
                        failure: the agent stays armed and routed with
                        its mailbox closed in place, and the durable
                        explanation message the same commit produced
                        reaches that closed route without becoming a
                        fresh core fault about the quarantine"
                (is (true? (agent/fenced? routing "root")))
                (is (some? (agent/armed routing "root"))
                    "still armed — the entry is what makes the closed
                     channel mean the fence rather than a teardown")
                (is (empty?
                     (filter #(= :seon.cluster.wake/undeliverable-wake
                                 (:seon.error/kind %))
                             (errors @connection)))
                    "no secondary undeliverable-wake faults")
                (is (seq (messages-to @connection "root"))
                    "and the notice is a durable fact for the fresh
                     mailbox to derive after reboot")))))
        (finally
          (cluster/stop! instance))))

    (with-redefs
      [ai/complete (fn [_] {:seon.ai/text "(identity 1)"})
       ;; `start!` twice in one test JVM has the same real
       ;; (pid,start-instant). A reboot has a new process identity; make
       ;; that fact explicit so the production recovery transition sees
       ;; the prior holder as dead.
       cluster/process-identity
       (fn [_] "terminal-refusal-recovery-process")
       sci.eval/evaluate
       (fn [_]
         {:seon.sci.admit/value 1
          :seon.cluster.eval/result-edn "1"})]
      (let [restarted (cluster/start! {:seon.boot/cluster-name name
                                       :seon.boot/root root})]
        (try
          (let [connection (:seon.boot/cluster-connection restarted)
                recovered
                (await-fact
                 connection
                 (fn [db]
                   (let [receipt
                         (db/q '[:find (pull ?receipt [*]) .
                                :in $ ?run-id
                                :where
                                [?run :seon.cluster.run/id ?run-id]
                                [?receipt :seon.cluster.eval/run ?run]]
                              db @run-id)
                         run (db/pull db '[*]
                                     [:seon.cluster.run/id @run-id])]
                     (when (:seon.cluster.eval/interrupted-at receipt)
                       {:receipt receipt :run run}))))]
            (is (some? recovered)
                "reboot marked the dangling receipt interrupted")
            (is (some? (:seon.cluster.run/closed-at
                        (:run recovered)))
                "recovery ends the interrupted run without inventing success")
            (is (pos? (:seon.boot/recovered-runs restarted)))
            (is (zero? @(:seon.error/drops restarted))))
          (finally
            (cluster/stop! restarted)))))))

(deftest an-escaped-throwable-becomes-a-fact-and-a-message
  (with-cluster
    "faulting"
    (fn [instance]
      (let [connection (:seon.boot/cluster-connection instance)
            handle (:seon.cluster.loop/cluster instance)
            routing (:seon.cluster.agent/routing instance)
            entry (seon.cluster.agent/armed routing "root")
            graph (:seon.flow/graph entry)]
        ;; INJECTED AT THE REAL SEAM: the turn proc's transform calls
        ;; `next-agent-work`, so a throw there is a throw inside a
        ;; running flow proc — the exact path §1.2's report shapes come
        ;; from. Nothing here touches the error channel by hand.
        (with-redefs [work/next-agent-work
                      (fn [& _]
                        (throw (ex-info "injected core fault"
                                        {:seon.error/kind ::injected})))]
          (async/offer! (:seon.cluster.wake/channel entry) ::fault)
          (let [fact (first (await-fact connection (comp seq errors)))]
            (testing "exactly one error fact, carrying what happened"
              (is (some? fact))
              (is (= ::injected (:seon.error/kind fact)))
              (is (= "clojure.lang.ExceptionInfo"
                     (:seon.error/throwable-class fact)))
              (is (= :seon.cluster.agent/turn (:seon.error/proc fact)))
              (is (= "root"
                     (db/q '[:find ?agent-id .
                            :in $ ?error-id
                            :where
                            [?error :seon.error/id ?error-id]
                            [?error :seon.error/agent ?agent]
                            [?agent :seon.cluster.agent/id ?agent-id]]
                          @connection (:seon.error/id fact)))
                  "the fault arrived TAGGED with its agent — structural
                   provenance from the error-channel join, never a
                   global attribution query")
              (is (= (:seon.cluster.run/process handle)
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
                                    (:seon.cluster.message/content candidate)
                                    (:seon.error/id fact)))
                                 (messages-to db "root")))))]
                (is (some? message) "the message names the evidence")
                (is (some? (:seon.cluster.message/about message))
                    "and points at the fact — the absence of `about` on an
                     ordinary user message is what makes the storm fence
                     computable without a flag")))))
        (testing "THE STORM IS BOUNDED, and this is the falsifier for the
        cycle the live probe found: an explanation message is a commit,
        a commit wakes the loop through :seon.cluster.message/to, and a
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
                  (flow/ping-proc graph :seon.cluster.agent/turn
                                  :timeout-ms 5000))))
          (is (zero? @(:seon.error/drops instance))
              "and nothing was dropped on the way"))))))
