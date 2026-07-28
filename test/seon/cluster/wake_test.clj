(ns seon.cluster.wake-test
  "Sealed acceptance draft for the wake (N3, C1-C3).

  DRAFT FOR ORCHESTRATOR SEAL (drafted 2026-07-27). The implementation
  lane makes these green by implementing `seon.cluster.wake` ONLY.

  The two hazards are LIVE here, not described: a handler that throws
  must not hang the committing caller (probe A killed a JVM proving it
  does), and a saturated channel must drop rather than park. Both are
  asserted against a real in-memory connection with a real `transact`
  on the test's own thread — a mock listener would prove nothing about
  the one thing that matters, which is what datahike does INSIDE the
  transaction's go block before it delivers."
  (:require [clojure.core.async :as async]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [datahike.api :as d]
            [seon.cluster.loop :as cluster.loop]
            [seon.cluster.wake :as wake]
            [seon.schema]
            [seon.test-support :as test-support])
  (:import [java.util Date]))

(defn- with-connection [body]
  (test-support/with-database
    (fn [connection]
      (d/transact connection [{:seon.cluster.agent/id "agent-a"}])
      (body connection))))

(defn- message-tx [id]
  [{:seon.cluster.message/id id
    :seon.cluster.message/to [:seon.cluster.agent/id "agent-a"]
    :seon.cluster.message/content "hello"
    :seon.cluster.message/at (Date.)}])

;;; ---------------------------------------------------------------------------
;;; C1 — the predicate and its three traps
;;; ---------------------------------------------------------------------------

(deftest a-wake-attribute-wakes-and-nothing-else-does
  (let [check
        (tc/quick-check
         200
         (prop/for-all [attributes (gen/set gen/keyword {:min-elements 1
                                                         :max-elements 4})
                        present (gen/vector gen/keyword 0 5)
                        include? gen/boolean]
           (let [wake-attribute (first (sort attributes))
                 datoms (cond-> (mapv (fn [a] [1 a "v" 536870913 true]) present)
                          include? (conj [1 wake-attribute "v" 536870913 true]))
                 report {:tx-data datoms}
                 expected (boolean (some (comp attributes second) datoms))]
             (= expected (wake/wake? attributes report))))
         :seed 20260727)]
    (is (true? (:result check)) (str "wake? failed: " (pr-str check))))
  (testing "a transaction instant is in EVERY report and must never wake"
    (is (false? (wake/wake? (wake/wake-attributes)
                            {:tx-data [[1 :db/txInstant (Date.) 536870913 true]]}))))
  (testing "an empty report never wakes"
    (is (false? (wake/wake? (wake/wake-attributes) {:tx-data []})))))

;;; ---------------------------------------------------------------------------
;;; C2 — disjointness, computed on both sides
;;; ---------------------------------------------------------------------------

(deftest the-loop-never-wakes-itself
  (let [wakes (wake/wake-attributes)
        commits (cluster.loop/committed-attributes)]
    (is (seq wakes) "the wake set is not empty")
    (is (seq commits) "and neither is the committed set")
    (is (empty? (set/intersection wakes commits))
        "a loop that wakes on its own commits spins forever — and both
         sides of this are computed, never a reviewed list")))

;;; ---------------------------------------------------------------------------
;;; C3 — the handler's two absolute prohibitions, live
;;; ---------------------------------------------------------------------------

(deftest a-committed-wake-attribute-delivers-one-wake
  (with-connection
    (fn [connection]
      (let [channel (async/chan (async/sliding-buffer 1))
            faults (async/chan (async/sliding-buffer 1))
            key (wake/listen! {:seon.cluster.wake/connection connection
                               :seon.cluster.wake/attributes
                               (wake/wake-attributes)
                               :seon.cluster.wake/channel channel
                               :seon.cluster.wake/fault-channel faults
                               :seon.cluster.wake/key ::probe})]
        (try
          (d/transact connection (message-tx "m-1"))
          (is (some? (test-support/await-event! channel "wake delivery"))
              "the wake arrived")
          (testing "and a commit of a non-wake attribute delivers nothing"
            (d/transact connection [{:seon.cluster.run/id "run-1"
                                     :seon.cluster.run/agent
                                     [:seon.cluster.agent/id "agent-a"]
                                     :seon.cluster.run/opened-at (Date.)
                                     :seon.cluster.run/plan-digest
                                     (apply str (repeat 64 "a"))}])
            (is (nil? (async/poll! channel))))
          (finally
            (wake/unlisten! {:seon.cluster.wake/connection connection
                             :seon.cluster.wake/key key})))))))

(deftest a-saturated-channel-drops-and-never-parks
  ;; the handler runs on the committing caller's critical path: probe B
  ;; measured an 800 ms listener adding 804 ms to `transact`
  (with-connection
    (fn [connection]
      (let [channel (async/chan (async/sliding-buffer 1))
            faults (async/chan (async/sliding-buffer 1))
            key (wake/listen! {:seon.cluster.wake/connection connection
                               :seon.cluster.wake/attributes
                               (wake/wake-attributes)
                               :seon.cluster.wake/channel channel
                               :seon.cluster.wake/fault-channel faults
                               :seon.cluster.wake/key ::probe})]
        (try
          ;; Nobody is reading while the transactions run. Completion
          ;; of the returned Future is the proof that the listener did
          ;; not park the writer; no elapsed-time threshold is involved.
          (let [committed
                (future
                  (mapv (fn [n]
                          (d/transact connection
                                      (message-tx (str "m-" n))))
                        (range 5)))]
            (is (= 5
                   (count
                    (test-support/await-event!
                     committed "saturated-listener transactions")))))
          (is (some? (async/poll! channel))
              "and the newest wake is still there — sliding, not blocking")
          (finally
            (wake/unlisten! {:seon.cluster.wake/connection connection
                             :seon.cluster.wake/key key})))))))

(deftest a-throwing-handler-delivers-a-fault-and-never-hangs-the-writer
  ;; THE hazard: datahike fires listeners inside the transaction's go
  ;; block BEFORE (deliver p tx-report) (writer.cljc:384-386). An
  ;; escaping exception means the deliver never happens and the
  ;; committing caller waits forever. The handler must therefore catch
  ;; everything — including a wake channel that has been closed under it.
  (with-connection
    (fn [connection]
      (let [channel (async/chan (async/sliding-buffer 1))
            faults (async/chan (async/sliding-buffer 1))
            key (wake/listen! {:seon.cluster.wake/connection connection
                               :seon.cluster.wake/attributes
                               (wake/wake-attributes)
                               :seon.cluster.wake/channel channel
                               :seon.cluster.wake/fault-channel faults
                               :seon.cluster.wake/key ::probe})]
        (try
          (async/close! channel)
          (let [committed (future (d/transact connection (message-tx "m-9")))]
            (is (map? (test-support/await-event!
                       committed "throwing-listener transaction"))
                "the committing caller returned — the handler swallowed
                 its own failure instead of hanging the writer forever")
            ;; surviving is only half of it: a swallowed failure that
            ;; reports nothing is an invisible fault, so the payload
            ;; must ARRIVE
            (let [fault (test-support/await-event! faults
                                                   "wake listener fault")]
              (is (some? fault) "the fault reached the fault channel")
              (is (instance? Throwable fault)
                  "and it is the throwable itself, not a summary of it")))
          (finally
            (wake/unlisten! {:seon.cluster.wake/connection connection
                             :seon.cluster.wake/key key})))))))

(deftest unlisten-is-idempotent-and-stops-delivery
  (with-connection
    (fn [connection]
      (let [channel (async/chan (async/sliding-buffer 1))
            faults (async/chan (async/sliding-buffer 1))
            key (wake/listen! {:seon.cluster.wake/connection connection
                               :seon.cluster.wake/attributes
                               (wake/wake-attributes)
                               :seon.cluster.wake/channel channel
                               :seon.cluster.wake/fault-channel faults
                               :seon.cluster.wake/key ::probe})
            request {:seon.cluster.wake/connection connection
                     :seon.cluster.wake/key key}]
        (is (nil? (wake/unlisten! request)))
        (is (nil? (wake/unlisten! request)) "removing an absent listener
                                             is a no-op, because stop may
                                             arrive after a release")
        (d/transact connection (message-tx "m-after"))
        (is (nil? (async/poll! channel))
            "nothing is delivered after unlisten")))))
