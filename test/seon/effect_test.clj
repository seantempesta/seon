(ns seon.effect-test
  (:require [clojure.core.async :as async]
            [clojure.test :refer [deftest is testing]]
            [datahike.core :as datahike]
            [sci.core :as sci]
            [seon.cluster.run :as run]
            [seon.config :as config]
            [seon.db :as db]
            [seon.effect :as effect]
            [seon.flow :as flow]
            [seon.sci.eval :as sci.eval]
            [seon.sci.kernel :as kernel]
            [seon.test-support :as test-support])
  (:import [java.util Date]
           [java.util.concurrent CountDownLatch]
           [java.util.concurrent.atomic AtomicBoolean]))

(def ^:private test-environment
  ;; The subset environment (store layer only) every crossing this
  ;; namespace constructs names; boot's own constructor, fewer layers.
  (delay (test-support/environment "seon.effect-test")))


(def ^:private handler-calls (atom []))

(defn- test-handler
  {:malli/schema [:=> [:cat
                       [:map [:seon.effect-test/value :int]]
                       :seon.config/effective]
                  :map]}
  [request effective]
  (swap! handler-calls conj request)
  {:seon.effect-test/value (:seon.effect-test/value request)
   :seon.effect-test/cluster (:seon.config/cluster effective)
   :seon.effect-test/virtual-thread? (.isVirtual (Thread/currentThread))})

(defn capability-owner
  {:malli/schema [:=> [:cat [:map [:seon.effect-test/value :int]]]
                  [:or :map :seon.error/value]]}
  [request]
  request)

;;; ---------------------------------------------------------------------------
;;; The arm at the door — a handler that really enters interpreted code
;;; ---------------------------------------------------------------------------

;;; A capability handler is host code, so sci's `interrupt!` can only reach it
;;; where it ENTERS interpreted code. That is exactly the observable this
;;; regression needs: entrance counting and interruption both prove the arm
;;; arrived, and neither can be faked by the test.

(defonce ^:private probe-ctx (delay (sci.eval/build-base-ctx)))

(def ^:private bounded-loop
  '(fn [] (loop [i 0] (if (< i 20000) (recur (inc i)) i))))

(def ^:private unbounded-loop
  '(fn [] (loop [i 0] (recur (inc i)))))

(def ^:private handler-gate (atom nil))

(defn- arm-probe-handler
  {:malli/schema [:=> [:cat
                       [:map [:seon.effect-test/iterations :int]]
                       :seon.config/effective]
                  :map]}
  [request _effective]
  (let [gated? (:seon.effect-test/gated? request)
        gate @handler-gate
        _ (when (and gated? gate) (deref gate 10000 :seon.effect-test/timeout))
        form (if (neg? (:seon.effect-test/iterations request))
               unbounded-loop
               bounded-loop)]
    {:seon.effect-test/armed? (some? (kernel/current-arm))
     ;; Which arm, not merely whether one: a detached submission runs under
     ;; a FRESH arm whose deadline is its own, so the remaining milliseconds
     ;; here separate "bounded by my own limit" from "bounded by the turn's".
     :seon.effect-test/deadline-remaining-ms (kernel/deadline-remaining-ms)
     ;; The connection every capability handler needs. Background handlers
     ;; got nil here until the far side rebuilt its context from data, and
     ;; every `my.shell/run` submitted in the background failed on it.
     :seon.effect-test/connection?
     (some? (:seon.db/connection effect/*request-context*))
     :seon.effect-test/outcome
     (try
       (sci/eval-form @probe-ctx (list form))
       :seon.effect-test/completed
       (catch Throwable failure
         (if (kernel/interrupted? failure)
           :seon.effect-test/interrupted
           :seon.effect-test/failed)))}))

(defn arm-probe-owner
  {:malli/schema [:=> [:cat [:map [:seon.effect-test/iterations :int]]]
                  [:or :map :seon.error/value]]}
  [request]
  request)

(defn- install-arm-probe!
  [connection]
  (let [handler-meta (meta #'arm-probe-handler)]
    (db/transact!
     connection
     [{:seon.schema/key :seon.effect-test/arm-probe-request
       :seon.schema/form
       (pr-str [:map
                [:seon.effect-test/iterations :int]
                [:seon.effect-test/gated? {:optional true} :boolean]])}
      {:seon.fn/sym "seon.effect-test/arm-probe-owner"
       :seon.fn/spec
       (pr-str [:=> [:cat :seon.effect-test/arm-probe-request]
                [:or :map :seon.error/value]])
       :seon.fn/workload :io
       :seon.effect/capability
       (symbol (str (ns-name (:ns handler-meta)))
               (str (:name handler-meta)))}])))

(defn- install-capability!
  [connection]
  (let [handler-meta (meta #'test-handler)]
    (db/transact!
     connection
     [{:seon.schema/key :seon.effect-test/request
       :seon.schema/form
       (pr-str [:map [:seon.effect-test/value :int]])}
      {:seon.fn/sym "seon.effect-test/capability-owner"
       :seon.fn/spec
       (pr-str [:=> [:cat :seon.effect-test/request]
                [:or :map :seon.error/value]])
       :seon.fn/workload :io
       :seon.effect/capability
       (symbol (str (ns-name (:ns handler-meta)))
               (str (:name handler-meta)))}])))

(defn- cluster-config
  "A COMPLETE effective-config row, because a partial one is not readable.

  `config/effective` answers with its missing-facts error the moment one
  required dial is absent, so a test that needs a real dial value — here the
  background time limit — transacts the shipped defaults with that one dial
  overridden rather than a two-key stub."
  [background-limit-ms]
  (assoc (config/defaults)
         :seon.config/cluster "default"
         :seon.config.effect.background/time-limit-ms background-limit-ms))

(defn- request-context
  ([connection]
   (request-context connection nil))
  ([connection launcher]
   {:seon.env/environment @test-environment
    :seon.db/connection connection
    :seon.cluster.agent/id "effect-agent"
    :seon.cluster.run/id "effect-run"
    :seon.cluster.run.form/ordinal 3
    :seon.boot/cluster-name "default"
    :seon.flow/work-launcher launcher
    :seon.sci.admit/caps (config/result-caps (config/defaults))
    :seon.config/on-core-error :record
    :seon.effect/counter (atom -1)}))

(deftest background-settlement-carries-its-connection-across-a-thread-hop
  (test-support/with-database
    (fn [connection]
      (db/transact!
       connection
       [(cluster-config 60000)
        {:seon.cluster.agent/id "effect-agent"}
        {:seon.cluster.run/id "effect-run"
         :seon.cluster.run/agent
         [:seon.cluster.agent/id "effect-agent"]}])
      (install-capability! connection)
      (let [settled (CountDownLatch. 1)
            observation (atom nil)
            launcher ::fresh-thread-launcher
            effect-id (pr-str ["effect-run" 3 0])
            result-ref [:seon.effect/id effect-id]]
        (with-redefs
          [flow/submit!
           (fn [_ submission]
             (.start
              (Thread.
               ^Runnable
               (fn []
                 (try
                   (let [before-work
                         [effect/*request-context* db/*conn*]
                         terminal
                         (try
                           {::flow/value
                            ((::flow/work-fn submission) {})}
                           (catch Throwable throwable
                             {::flow/throwable throwable}))
                         before-settlement
                         [effect/*request-context* db/*conn*]]
                     ((::flow/complete! submission) terminal)
                     (reset! observation
                             {:before-work before-work
                              :before-settlement before-settlement}))
                   (catch Throwable throwable
                     (reset! observation {:failure throwable}))
                   (finally
                     (.countDown settled))))))
             true)]
          (is (= result-ref
                 (binding [effect/*request-context*
                           (request-context connection launcher)]
                   (effect/request!
                    #'capability-owner
                    {:seon.effect-test/value 7}
                    {:seon.effect/background? true})))))
        (test-support/await-event! settled ::background-effect-settled)
        (is (nil? (:failure @observation)) (pr-str @observation))
        (is (= [[nil nil] [nil nil]]
               ((juxt :before-work :before-settlement) @observation))
            "the fresh worker has no effect or database binding frame")
        (let [receipt
              (db/pull @connection
                       '[* {:seon.effect/to
                            [:seon.cluster.agent/id]}]
                       [:seon.effect/id effect-id])
              result (read-string (:seon.effect/result-edn receipt))]
          (is (= 7 (:seon.effect-test/value result)))
          (is (= "effect-agent"
                 (get-in receipt
                         [:seon.effect/to :seon.cluster.agent/id])))
          (is (nil? (:seon.effect/notify receipt)))
          (is (int? (:seon.effect/duration-ms receipt)))
          (is (not (neg? (:seon.effect/duration-ms receipt)))))))))

(deftest the-door-runs-its-handler-under-the-requesting-evaluations-arm
  ;; THE CLASS: work that crosses a thread escapes the ONE limit. The
  ;; guarded door is a crossing like any other — it hands the handler to the
  ;; process root's `:io` executor — and until the arm travelled with the
  ;; request, every fs/shell/web/llm/db handler ran on a thread with no arm
  ;; at all: entrances attributed to nothing and `interrupt!` unable to reach
  ;; it. Both halves are asserted, and neither can pass vacuously: the
  ;; entrance count comes from sci's own `:interrupt-fn` and the second
  ;; handler's loop is genuinely unbounded, so only the carried deadline can
  ;; end it.
  (test-support/with-database
    (fn [connection]
      (db/transact! connection [(cluster-config 600000)
                                {:seon.cluster.run/id "effect-run"}])
      (install-arm-probe! connection)
      (let [ctx @probe-ctx]
        (testing "entrances made inside the handler reach the requester's arm"
          (let [arm (kernel/arm ctx 60000)
                result
                (binding [effect/*request-context* (request-context connection)]
                  (effect/request! #'arm-probe-owner
                                   {:seon.effect-test/iterations 20000}))
                record ((:seon.sci.kernel/record arm) :ok)]
            ((:seon.sci.kernel/stop! arm))
            (is (true? (:seon.effect-test/armed? result))
                "the handler thread served the requesting evaluation's arm")
            (is (= :seon.effect-test/completed
                   (:seon.effect-test/outcome result)))
            (is (<= 20000 (:seon.eval/fn-entries record))
                (str "20k interpreted entrances inside a capability handler "
                     "must reach the requesting arm; a 0 here is the lying "
                     "diagnostic this regression exists to kill"))))

        (testing "a handler that outruns the limit is interrupted by it"
          (let [context (request-context connection)
                started (System/nanoTime)
                arm (kernel/arm ctx 300)
                result
                (binding [effect/*request-context*
                          (assoc context :seon.effect/counter (atom 0))]
                  (effect/request! #'arm-probe-owner
                                   {:seon.effect-test/iterations -1}))
                elapsed-ms (quot (- (System/nanoTime) started) 1000000)]
            ((:seon.sci.kernel/stop! arm))
            (is (= :seon.effect-test/interrupted
                   (:seon.effect-test/outcome result))
                (str "an unbounded handler must end by SCI's own interrupt; "
                     "got " (pr-str result)))
            (is (< elapsed-ms 5000)
                (str "the handler must end at its ~300ms limit; elapsed "
                     elapsed-ms "ms"))))))))

(deftest background-work-outlives-the-deadline-of-the-turn-that-started-it
  ;; THE CLASS: a semantic inversion, not a crash. `my.background` exists so
  ;; work can finish AFTER the run that started it, so a background
  ;; submission must not inherit the submitting turn's deadline latch. It is
  ;; asserted the only honest way: the turn's limit has demonstrably fired
  ;; (its own `reached` latch is closed) BEFORE the handler enters
  ;; interpreted code, so an inherited arm would cut it at the first
  ;; entrance and the receipt would settle as a handler failure.
  (test-support/with-database
    (fn [connection]
      (db/transact!
       connection
       [(cluster-config 60000)
        {:seon.cluster.agent/id "effect-agent"}
        {:seon.cluster.run/id "effect-run"
         :seon.cluster.run/agent [:seon.cluster.agent/id "effect-agent"]}])
      (install-arm-probe! connection)
      (let [events (async/chan 4)
            listener-key (random-uuid)
            _ (datahike/listen! connection listener-key #(async/put! events %))
            launcher
            (flow/start-work-launcher!
             {:seon.env/environment @test-environment
              ::flow/configuration
              {:seon.config.flow.compute/queue-depth 1
               :seon.config.flow.compute/concurrency 1
               :seon.config.flow.io/queue-depth 1
               :seon.config.flow.io/concurrency 1}})
            effect-id (pr-str ["effect-run" 3 0])
            gate (promise)
            arm (kernel/arm @probe-ctx 150)
            submitting-arm (kernel/current-arm)]
        (reset! handler-gate gate)
        (try
          (is (= [:seon.effect/id effect-id]
                 (binding [effect/*request-context*
                           (request-context connection launcher)]
                   (effect/request!
                    #'arm-probe-owner
                    {:seon.effect-test/iterations 20000
                     :seon.effect-test/gated? true}
                    {:seon.effect/background? true}))))
          ((:seon.sci.kernel/stop! arm))
          ;; Wait on the OBSERVABLE, not on a sleep: the turn's deadline task
          ;; closing its own latch is the event this regression depends on.
          (test-support/await-event!
           (future
             (while (not (.get ^AtomicBoolean
                               (:seon.sci.kernel/reached submitting-arm)))
               (Thread/sleep 5))
             ::deadline-reached)
           ::submitting-turn-deadline-reached)
          (deliver gate ::released)
          (test-support/await-event!
           events
           ::background-effect-settled
           #(:seon.effect/result-edn
             (db/pull (:db-after %) [:seon.effect/result-edn]
                      [:seon.effect/id effect-id])))
          (let [result-edn
                (:seon.effect/result-edn
                 (db/pull @connection [:seon.effect/result-edn]
                          [:seon.effect/id effect-id]))
                settled (read-string result-edn)]
            (is (= :seon.effect-test/completed
                   (:seon.effect-test/outcome settled))
                (str "background work must survive the deadline of the turn "
                     "that submitted it; got " result-edn))
            (is (true? (:seon.effect-test/armed? settled))
                (str "and it must run under an arm of its own — unarmed was "
                     "the interim state before the config dial was ruled"))
            (is (< 30000 (:seon.effect-test/deadline-remaining-ms settled))
                (str "that arm's deadline must be the detached limit (60 s "
                     "here), not the submitting turn's 150 ms, which had "
                     "demonstrably already latched"))
            (is (true? (:seon.effect-test/connection? settled))
                (str "a background handler must still receive its cluster's "
                     "connection: every my.shell/run submitted in the "
                     "background failed on a nil one")))
          (finally
            (reset! handler-gate nil)
            (deliver gate ::released)
            (datahike/unlisten! connection listener-key)
            (async/close! events)
            (flow/stop-work-launcher! launcher)))))))

(defn- settled-background-value
  "Submit ONE detached arm-probe request and return the value it settled with.

  `config-limit-ms` is the cluster's declared background bound; `execution`
  is whatever the submitting form named on top of `:seon.effect/background?`;
  `before-release` runs after the submission and before a gated handler is
  let into interpreted code, which is where a scenario waits on the deadline
  it wants to have passed."
  [config-limit-ms submit! before-release]
  (let [captured (promise)]
    (test-support/with-database
      (fn [connection]
        (db/transact!
         connection
         [(cluster-config config-limit-ms)
          {:seon.cluster.agent/id "effect-agent"}
          {:seon.cluster.run/id "effect-run"
           :seon.cluster.run/agent [:seon.cluster.agent/id "effect-agent"]}])
        (install-arm-probe! connection)
        (let [events (async/chan 4)
              listener-key (random-uuid)
              _ (datahike/listen! connection listener-key
                                  #(async/put! events %))
              launcher
              (flow/start-work-launcher!
               {:seon.env/environment @test-environment
                ::flow/configuration
                {:seon.config.flow.compute/queue-depth 1
                 :seon.config.flow.compute/concurrency 1
                 :seon.config.flow.io/queue-depth 1
                 :seon.config.flow.io/concurrency 1}})
              effect-id (pr-str ["effect-run" 3 0])
              gate (promise)]
          (reset! handler-gate gate)
          (try
            (submit! (request-context connection launcher))
            (before-release)
            (deliver gate ::released)
            (test-support/await-event!
             events
             ::background-effect-settled
             #(:seon.effect/result-edn
               (db/pull (:db-after %) [:seon.effect/result-edn]
                        [:seon.effect/id effect-id])))
            (deliver captured
                     (read-string
                      (:seon.effect/result-edn
                       (db/pull @connection [:seon.effect/result-edn]
                                [:seon.effect/id effect-id]))))
            (finally
              (reset! handler-gate nil)
              (deliver gate ::released)
              (datahike/unlisten! connection listener-key)
              (async/close! events)
              (flow/stop-work-launcher! launcher))))))
    @captured))

(defn- await-deadline!
  "Block until a fresh `limit-ms` deadline has demonstrably latched.

  A scenario that needs \"the limit that would have applied has passed\"
  waits on a real arm's own latch rather than sleeping a guessed interval."
  [limit-ms]
  (let [armed (kernel/detached-arm limit-ms)]
    (test-support/await-event!
     (future
       (while (not (.get ^AtomicBoolean (:seon.sci.kernel/reached armed)))
         (Thread/sleep 5))
       ::deadline-reached)
     ::detached-deadline-reached)
    (kernel/release-arm! armed)))

(deftest detached-work-is-bounded-by-its-own-limit-config-then-the-form
  ;; THE CLASS: work with no bound at all. Background work correctly refuses
  ;; the submitting turn's deadline, and the interim state after that repair
  ;; was an UNARMED submission — bounded by nothing but whatever the
  ;; capability happened to bound itself with. The owner's ruling
  ;; (2026-08-08 night) makes unbounded unrepresentable: a config fact is the
  ;; default and the submitting form's explicit limit wins in either
  ;; direction. All three arms of that rule are asserted here, and none can
  ;; pass vacuously — the interrupted cases run a genuinely unbounded
  ;; interpreted loop that only a real deadline can end, and the looser case
  ;; enters interpreted code only AFTER the config limit has demonstrably
  ;; latched, so a config win would cut it at its first entrance.
  (testing "with no explicit limit, the config fact bounds the work"
    (let [started (System/nanoTime)
          settled (settled-background-value
                   300
                   (fn [context]
                     (binding [effect/*request-context* context]
                       (effect/request!
                        #'arm-probe-owner
                        {:seon.effect-test/iterations -1
                         :seon.effect-test/gated? true}
                        {:seon.effect/background? true})))
                   (fn []))
          elapsed-ms (quot (- (System/nanoTime) started) 1000000)]
      (is (= :seon.effect-test/interrupted
             (:seon.effect-test/outcome settled))
          (str "an unbounded detached handler must be cut by the cluster's "
               "background limit; got " (pr-str settled)))
      (is (< elapsed-ms 15000)
          (str "and cut at ~300ms, not left running; elapsed " elapsed-ms))))

  (testing "an explicit TIGHTER limit wins over a generous config fact"
    (let [started (System/nanoTime)
          settled (settled-background-value
                   60000
                   (fn [context]
                     (binding [effect/*request-context* context]
                       (effect/request!
                        #'arm-probe-owner
                        {:seon.effect-test/iterations -1
                         :seon.effect-test/gated? true}
                        {:seon.effect/background? true
                         :seon.effect/time-limit-ms 300})))
                   (fn []))
          elapsed-ms (quot (- (System/nanoTime) started) 1000000)]
      (is (= :seon.effect-test/interrupted
             (:seon.effect-test/outcome settled)))
      (is (< elapsed-ms 15000)
          (str "the form's 300ms must govern, not the cluster's 60s; "
               "elapsed " elapsed-ms))))

  (testing "an explicit LOOSER limit wins over a strict config fact"
    (let [settled (settled-background-value
                   200
                   (fn [context]
                     (binding [effect/*request-context* context]
                       (effect/request!
                        #'arm-probe-owner
                        {:seon.effect-test/iterations 20000
                         :seon.effect-test/gated? true}
                        {:seon.effect/background? true
                         :seon.effect/time-limit-ms 60000})))
                   #(await-deadline! 200))]
      (is (= :seon.effect-test/completed
             (:seon.effect-test/outcome settled))
          (str "the form's 60s must govern after the cluster's 200ms would "
               "have latched; got " (pr-str settled)))
      (is (< 30000 (:seon.effect-test/deadline-remaining-ms settled))
          "and the arm the work ran under must carry that 60s deadline")))

  (testing "a cluster with no background limit refuses the submission"
    (test-support/with-database
      (fn [connection]
        (db/transact! connection [{:seon.config/cluster "default"}
                                  {:seon.cluster.agent/id "effect-agent"}
                                  {:seon.cluster.run/id "effect-run"}])
        (install-arm-probe! connection)
        (let [result
              (binding [effect/*request-context*
                        (request-context connection)]
                (effect/request! #'arm-probe-owner
                                 {:seon.effect-test/iterations 1}
                                 {:seon.effect/background? true}))]
          (is (= :seon.effect/missing-background-time-limit
                 (:seon.error/kind result))
              (str "unbounded detached work must be refused loudly; got "
                   (pr-str result)))
          (is (nil? (db/pull @connection [:seon.effect/id]
                             [:seon.effect/id (pr-str ["effect-run" 3 0])]))
              "and refused before any receipt is opened")))))

  (testing "a nonsense explicit limit is refused, never treated as absent"
    (test-support/with-database
      (fn [connection]
        (db/transact! connection [(cluster-config 60000)
                                  {:seon.cluster.agent/id "effect-agent"}
                                  {:seon.cluster.run/id "effect-run"}])
        (install-arm-probe! connection)
        (let [result
              (binding [effect/*request-context*
                        (request-context connection)]
                (effect/request! #'arm-probe-owner
                                 {:seon.effect-test/iterations 1}
                                 {:seon.effect/background? true
                                  :seon.effect/time-limit-ms 0}))]
          (is (= :seon.effect/invalid-time-limit
                 (:seon.error/kind result))))))))

(deftest capability-reachability-is-a-database-query
  (test-support/with-database
    (fn [connection]
      (install-capability! connection)
      (db/transact!
       connection
       [{:seon.fn/sym "seon.effect-test/pure-caller"
         :seon.fn/calls
         [[:seon.fn/sym "seon.effect-test/capability-owner"]]}])
      (let [database @connection]
        (is (= #{"seon.effect-test/capability-owner"}
               (effect/capabilities database
                                    'seon.effect-test/capability-owner)))
        (is (= #{"seon.effect-test/capability-owner"}
               (effect/capabilities database
                                    'seon.effect-test/pure-caller)))
        (is (= #{}
               (effect/capabilities database
                                    'seon.effect-test/test-handler)))))))

(deftest request-commits-before-io-dispatch-and-settles-once
  (test-support/with-database
    (fn [connection]
      (reset! handler-calls [])
      (db/transact! connection [(cluster-config 600000)
                                {:seon.cluster.run/id "effect-run"}])
      (install-capability! connection)
      (let [first-result
            (binding [effect/*request-context* (request-context connection)]
              (effect/request! #'capability-owner
                               {:seon.effect-test/value 7}))
            receipt
            (db/pull @connection '[* {:seon.effect/owner [:seon.fn/sym]}]
                     [:seon.effect/id (pr-str ["effect-run" 3 0])])]
        (testing "the handler ran on the shared io executor with effective facts"
          (is (= 7 (:seon.effect-test/value first-result)))
          (is (true? (:seon.effect-test/virtual-thread? first-result)))
          (is (= [{:seon.effect-test/value 7}] @handler-calls)))
        (testing "one open-before-dispatch receipt settled with bounded data"
          (is (= "seon.effect-test/capability-owner"
                 (get-in receipt [:seon.effect/owner :seon.fn/sym])))
          (is (= 0 (:seon.effect/ordinal receipt)))
          (is (= 3 (:seon.effect/form-ordinal receipt)))
          (is (string? (:seon.effect/request-edn receipt)))
          (is (string? (:seon.effect/result-edn receipt)))
          (is (inst? (:seon.effect/opened-at receipt)))
          (is (inst? (:seon.effect/settled-at receipt))))
        (testing "the same identity refuses redispatch"
          (let [second-result
                (binding [effect/*request-context* (request-context connection)]
                  (effect/request! #'capability-owner
                                   {:seon.effect-test/value 7}))]
            (is (= :seon.effect/already-recorded
                   (:seon.error/kind second-result)))
            (is (= 1 (count @handler-calls)))))))))

(deftest invalid-requests-never-open-a-receipt
  (test-support/with-database
    (fn [connection]
      (install-capability! connection)
      (let [result
            (binding [effect/*request-context* (request-context connection)]
              (effect/request! #'capability-owner
                               {:seon.effect-test/value "wrong"}))]
        (is (= :seon.effect/invalid-request (:seon.error/kind result)))
        (is (nil? (db/pull @connection [:seon.effect/id]
                           [:seon.effect/id
                            (pr-str ["effect-run" 3 0])])))))))

(deftest interrupted-handlers-mark-the-open-receipt-without-a-result
  (test-support/with-database
    (fn [connection]
      (db/transact! connection [(cluster-config 600000)
                                {:seon.cluster.run/id "effect-run"}])
      (install-capability! connection)
      (with-redefs-fn
        {(ns-resolve 'seon.effect 'dispatch)
         (fn [_handler _owner-sym _effect-id _request _effective]
           (throw (InterruptedException. "test interruption")))}
        (fn []
          (let [result
                (binding [effect/*request-context* (request-context connection)]
                  (effect/request! #'capability-owner
                                   {:seon.effect-test/value 7}))
                receipt
                (db/pull @connection '[*]
                         [:seon.effect/id
                          (pr-str ["effect-run" 3 0])])]
            (is (= :seon.effect/interrupted (:seon.error/kind result)))
            (is (inst? (:seon.effect/interrupted-at receipt)))
            (is (nil? (:seon.effect/result-edn receipt)))))))))

(deftest guarded-sci-evaluation-supplies-the-effect-identity-context
  (test-support/with-database
    (fn [connection]
      (db/transact! connection [(cluster-config 600000)
                                {:seon.cluster.run/id "effect-run"}])
      (install-capability! connection)
      (let [ctx (test-support/fork-cluster-ctx connection)
            effective (config/defaults)
            evaluation
            (sci.eval/evaluate
             {:seon.cluster.run.form/source
              (str "(seon.effect/request! "
                   "#'seon.effect-test/capability-owner "
                   "{:seon.effect-test/value 9})")
              :seon.cluster.run.form/ns [:seon.ns/name 'user]
              :seon.sci.admit/caps (config/result-caps effective)
              :seon.sci.eval/time-limit-ms
              (:seon.config.eval/time-limit-ms effective)
              :seon.config/on-core-error :record
              :seon.sci.eval/ctx ctx
              :seon.cluster.agent/id "root"
              :seon.cluster.run/id "effect-run"
              :seon.cluster.run.form/ordinal 3
              :seon.boot/cluster-name "default"})
            receipt
            (db/pull @connection '[*]
                     [:seon.effect/id (pr-str ["effect-run" 3 0])])]
        (is (= 9 (get-in evaluation
                         [:seon.sci.admit/value
                          :seon.effect-test/value])))
        (is (= 0 (:seon.effect/ordinal receipt)))
        (is (inst? (:seon.effect/settled-at receipt)))))))

(deftest recovery-marks-open-receipts-interrupted-without-refiring
  (test-support/with-database
    (fn [connection]
      (let [opened-at (Date. 1699999999000)
            now (Date. 1700000000000)]
        (db/transact! connection [{:seon.cluster.agent/id "effect-agent"}])
        (db/transact!
         connection
         (run/open-tx
          {::run/id "effect-run"
           ::run/agent [:seon.cluster.agent/id "effect-agent"]
           ::run/opened-at opened-at}))
        (db/transact!
         connection
         (run/claim-tx
          {::run/id "effect-run"
           ::run/process "dead-process"
           ::run/live-processes #{"dead-process"}
           ::run/now opened-at}))
        (install-capability! connection)
        (db/transact!
         connection
         [{:seon.effect/id (pr-str ["effect-run" 3 0])
           :seon.effect/run [:seon.cluster.run/id "effect-run"]
           :seon.effect/owner [:seon.fn/sym "seon.effect-test/capability-owner"]
           :seon.effect/form-ordinal 3
           :seon.effect/ordinal 0
           :seon.effect/request-edn "{}"
           :seon.effect/opened-at now}])
        (db/transact!
         connection
         (run/recover-tx
          {::run/id "effect-run"
           ::run/live-processes #{"live-process"}
           ::run/now now}))
        (let [receipt (db/pull @connection '[*]
                               [:seon.effect/id
                                (pr-str ["effect-run" 3 0])])]
          (is (= now (:seon.effect/interrupted-at receipt)))
          (is (nil? (:seon.effect/result-edn receipt)))
          (is (some? (::run/closed-at
                      (db/pull @connection '[*]
                               [:seon.cluster.run/id "effect-run"])))))))))
