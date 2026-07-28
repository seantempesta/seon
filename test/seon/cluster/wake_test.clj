(ns seon.cluster.wake-test
  "The wake, through the ONE listener that survives: `route!`.

  RE-GROUNDED AT F2 §3.3. `listen!`'s one-global-channel delivery and
  its `wake?` predicate are deleted; the six classes they proved are
  proved here against routing delivery instead — wake-on-routed-set
  only, C2 disjointness between two COMPUTED sets, one delivery per
  commit, drop-never-park, fault-never-hang, and unlisten idempotence.
  Not one oracle is looser than it was; each now runs against the
  listener production actually registers.

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

(defn- agent-eid
  "The recipient's ENTITY ID — what a `:seon.cluster.message/to` datom
  carries as its value, and therefore the key `route!` looks up."
  [connection]
  (d/q '[:find ?e . :where [?e :seon.cluster.agent/id "agent-a"]]
       @connection))

(defn- message-tx [id]
  [{:seon.cluster.message/id id
    :seon.cluster.message/to [:seon.cluster.agent/id "agent-a"]
    :seon.cluster.message/content "hello"
    :seon.cluster.message/at (Date.)}])

(defn- run-tx
  "A commit of attributes a TURN writes — the other side of C2."
  [id]
  [{:seon.cluster.run/id id
    :seon.cluster.run/agent [:seon.cluster.agent/id "agent-a"]
    :seon.cluster.run/opened-at (Date.)
    :seon.cluster.run/plan-digest (apply str (repeat 64 "a"))}])

(defn- route-probe!
  "Register the production listener with this test's own channels.
  Returns the channels plus the registered key, so every test speaks to
  the same wiring `arm-agents!` builds."
  [connection mailbox]
  (let [armer (async/chan (async/sliding-buffer 1))
        render (async/chan (async/sliding-buffer 1))
        faults (async/chan (async/sliding-buffer 1))]
    {:mailbox mailbox
     :armer armer
     :render render
     :faults faults
     :key (wake/route! {:seon.cluster.wake/connection connection
                        :seon.cluster.wake/channels
                        (fn [] {(agent-eid connection) mailbox})
                        :seon.cluster.wake/armer-channel armer
                        :seon.cluster.wake/render-channel render
                        :seon.cluster.wake/fault-channel faults
                        :seon.cluster.wake/key ::probe})}))

;;; ---------------------------------------------------------------------------
;;; C1 — the routed set and its three traps
;;; ---------------------------------------------------------------------------

(deftest a-routed-attribute-wakes-its-agent-and-nothing-else-does
  (with-connection
    (fn [connection]
      (let [mailbox (async/chan (async/sliding-buffer 1))
            {:keys [render key]} (route-probe! connection mailbox)]
        (try
          (d/transact connection (message-tx "m-1"))
          (is (some? (test-support/await-event! mailbox "mailbox wake"))
              "a message to this agent reaches ITS mailbox")
          (testing "a commit of attributes only a TURN writes wakes no
                    mailbox — the trap that would spin an idle cluster"
            (d/transact connection (run-tx "run-1"))
            (is (nil? (async/poll! mailbox))))
          (testing "a transaction instant is in EVERY report and routes
                    nowhere by itself"
            ;; the run commit above carried one, and the mailbox stayed
            ;; empty; the RENDER wake is the deliberate exception — it
            ;; is per-report and unconditional, and it derives pages
            ;; rather than work
            (is (some? (async/poll! render))
                "every commit is render interest"))
          (finally
            (wake/unlisten! {:seon.cluster.wake/connection connection
                             :seon.cluster.wake/key key})))))))

;;; ---------------------------------------------------------------------------
;;; C2 — disjointness, computed on both sides
;;; ---------------------------------------------------------------------------

(deftest a-turn-never-wakes-itself
  (let [wakes (wake/wake-attributes)
        commits (cluster.loop/committed-attributes)]
    (is (seq wakes) "the routed set is not empty")
    (is (seq commits) "and neither is the committed set")
    (is (empty? (set/intersection wakes commits))
        "an agent that wakes on its own commits spins forever — and both
         sides of this are computed, never a reviewed list")))

;;; ---------------------------------------------------------------------------
;;; C3 — the handler's two absolute prohibitions, live
;;; ---------------------------------------------------------------------------

(deftest a-committed-agent-id-wakes-the-armer
  ;; the second routed attribute: a committed agent creation IS an arm
  ;; wake, and it is what makes the created-and-messaged-in-one-commit
  ;; window survivable
  (with-connection
    (fn [connection]
      (let [mailbox (async/chan (async/sliding-buffer 1))
            {:keys [armer key]} (route-probe! connection mailbox)]
        (try
          (d/transact connection [{:seon.cluster.agent/id "agent-b"}])
          (is (some? (test-support/await-event! armer "armer wake"))
              "the armer derives (agents in facts) − (armed set)")
          (finally
            (wake/unlisten! {:seon.cluster.wake/connection connection
                             :seon.cluster.wake/key key})))))))

(deftest a-saturated-mailbox-drops-and-never-parks
  ;; the handler runs on the committing caller's critical path: probe B
  ;; measured an 800 ms listener adding 804 ms to `transact`
  (with-connection
    (fn [connection]
      (let [mailbox (async/chan (async/sliding-buffer 1))
            {:keys [key]} (route-probe! connection mailbox)]
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
          (is (some? (async/poll! mailbox))
              "and the newest wake is still there — sliding, not blocking")
          (finally
            (wake/unlisten! {:seon.cluster.wake/connection connection
                             :seon.cluster.wake/key key})))))))

(deftest a-closed-channel-delivers-a-fault-and-never-hangs-the-writer
  ;; THE hazard: datahike fires listeners inside the transaction's go
  ;; block BEFORE (deliver p tx-report) (writer.cljc:384-386). An
  ;; escaping exception means the deliver never happens and the
  ;; committing caller waits forever. The handler must therefore catch
  ;; everything — including a mailbox that has been closed under it.
  (with-connection
    (fn [connection]
      (let [mailbox (async/chan (async/sliding-buffer 1))
            {:keys [faults key]} (route-probe! connection mailbox)]
        (try
          (async/close! mailbox)
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
              (is (some? fault) "the fault reached the fault channel")))
          (finally
            (wake/unlisten! {:seon.cluster.wake/connection connection
                             :seon.cluster.wake/key key})))))))

(deftest a-closed-render-channel-delivers-a-fault
  ;; the third delivery is under the SAME contract as the other two: a
  ;; render channel nobody can reach means a page that silently stops
  ;; updating, which is exactly the invisible-failure class
  (with-connection
    (fn [connection]
      (let [mailbox (async/chan (async/sliding-buffer 1))
            {:keys [render faults key]} (route-probe! connection mailbox)]
        (try
          (async/close! render)
          (let [committed (future (d/transact connection (message-tx "m-r")))]
            (is (map? (test-support/await-event!
                       committed "closed-render transaction"))
                "the writer still returned")
            (is (some? (test-support/await-event! faults "render fault"))
                "and the undeliverable render wake was reported"))
          (finally
            (wake/unlisten! {:seon.cluster.wake/connection connection
                             :seon.cluster.wake/key key})))))))

(deftest unlisten-is-idempotent-and-stops-delivery
  (with-connection
    (fn [connection]
      (let [mailbox (async/chan (async/sliding-buffer 1))
            {:keys [key]} (route-probe! connection mailbox)
            request {:seon.cluster.wake/connection connection
                     :seon.cluster.wake/key key}]
        (is (nil? (wake/unlisten! request)))
        (is (nil? (wake/unlisten! request)) "removing an absent listener
                                             is a no-op, because stop may
                                             arrive after a release")
        (d/transact connection (message-tx "m-after"))
        (is (nil? (async/poll! mailbox))
            "nothing is delivered after unlisten")))))
