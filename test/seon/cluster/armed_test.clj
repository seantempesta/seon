(ns seon.cluster.armed-test
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
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.ai :as ai]
            [seon.cluster :as cluster]
            [seon.cluster.agent]
            [seon.cluster.work :as work]
            [seon.schema]
            [seon.test-support :as test-support])
  (:import [java.util.concurrent CountDownLatch]))

(set! *warn-on-reflection* true)

(defn- with-cluster
  "Boot one real cluster into its own root, and always stop it."
  [name body]
  (let [root (str "tmp/armed-test/" name)]
    (doseq [file (reverse (file-seq (io/file root)))]
      (.delete ^java.io.File file))
    (let [instance (cluster/start! {:seon.boot/cluster-name name
                                    :seon.boot/root root})]
      (try
        (body instance)
        (finally
          (cluster/stop! instance))))))

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

(defn- errors
  [db]
  (d/q '[:find [(pull ?error [*]) ...]
         :where [?error :seon.error/id _]]
       db))

(defn- messages-to
  [db agent-id]
  (d/q '[:find [(pull ?message [*]) ...]
         :in $ ?agent-id
         :where
         [?agent :seon.cluster.agent/id ?agent-id]
         [?message :seon.cluster.message/to ?agent]]
       db agent-id))

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
                 (d/q '[:find ?id . :in $ ?id
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
        (testing "the handle was derived from facts, not assembled by hand
        in a script: the provider comes from the dials"
          (let [handle (:seon.cluster.loop/cluster instance)]
            (is (seon.schema/valid-candidate-value? :seon.cluster.loop/cluster
                                                    handle))
            (is (str/starts-with?
                 (:seon.ai/endpoint (:seon.ai/primary handle))
                 "https://"))
            (is (= "root" (:seon.config.error/escalate-to handle)))
            (testing "and the shipped document configures NO backup, so
            the handle simply has no backup key — absence, never nil"
              (is (not (contains? handle :seon.ai/backup))))
            (testing "while the backoff strategy IS derived from the
            dials, because the no-backup path is the only resilience a
            default cluster has"
              (is (seon.schema/valid-candidate-value?
                   :seon.ai.retry/strategy
                   (:seon.ai.retry/strategy handle))))))))))

(deftest booting-spends-nothing
  ;; owner-explicit: an armed loop must not cost tokens. It is armed and
  ;; IDLE — the wake channel is primed, and a wake only says "look".
  (let [calls (atom 0)]
    (with-redefs [ai/complete (fn [_]
                                (swap! calls inc)
                                (throw (ex-info "boot made a model call" {})))]
      (with-cluster
        "idle"
        (fn [instance]
          (let [routing (:seon.cluster.agent/routing instance)
                entry (seon.cluster.agent/armed routing "root")
                graph (:seon.flow/graph entry)
                derived (CountDownLatch. 1)
                next-agent-work work/next-agent-work]
            ;; root's graph has looked at least once (the arm primes
            ;; its mailbox) and found nothing: turns only advance when
            ;; work was done
            (is (zero? (:seon.cluster.agent/turns
                        (:clojure.core.async.flow/state
                         (flow/ping-proc graph :seon.cluster.agent/turn)))))
            (with-redefs [work/next-agent-work
                          (fn [& arguments]
                            (let [result (apply next-agent-work arguments)]
                              (.countDown derived)
                              result))]
              (async/offer! (:seon.cluster.wake/channel entry) ::probe)
              (test-support/await-event! derived
                                         "empty-wake work derivation")
              ;; `ping-proc` is processed by the proc after its active
              ;; transform returns, so the state is terminal here.
              (is (zero? (:seon.cluster.agent/turns
                          (:clojure.core.async.flow/state
                           (flow/ping-proc
                            graph :seon.cluster.agent/turn))))
                  "a wake with no facts to act on is not work"))
            (is (zero? @calls)
                "and nothing anywhere called the model")))))))

;;; ---------------------------------------------------------------------------
;;; THE VISIBILITY PROPERTY — an escaped Throwable becomes facts
;;; ---------------------------------------------------------------------------

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
              (is (= "clojure.lang.ExceptionInfo" (:seon.error/class fact)))
              (is (= :seon.cluster.agent/turn (:seon.error/proc fact)))
              (is (= "root"
                     (d/q '[:find ?agent-id .
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
              (is (map? (clojure.edn/read-string (:seon.error/data-edn fact)))))
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
