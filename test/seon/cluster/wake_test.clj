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
            [clojure.core.async.impl.protocols :as async.protocols]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [seon.db :as db]
            [seon.cluster.loop :as cluster.loop]
            [seon.cluster.wake :as wake]
            [seon.schema]
            [seon.test-support :as test-support])
  (:import [java.util Date]))

(defn- with-connection [body]
  (test-support/with-database
    (fn [connection]
      (db/transact! connection [{:seon.cluster.agent/id "agent-a"}])
      (body connection))))

(defn- agent-eid
  "The recipient's ENTITY ID — what a `:seon.cluster.message/to` datom
  carries as its value, and therefore the key `route!` looks up."
  [connection]
  (db/q '[:find ?e . :where [?e :seon.cluster.agent/id "agent-a"]]
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
  ([connection mailbox]
   (route-probe! connection mailbox (fn [_ _] false)))
  ([connection mailbox fenced?]
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
                         :seon.cluster.wake/fenced? fenced?
                         :seon.cluster.wake/armer-channel armer
                         :seon.cluster.wake/render-channel render
                         :seon.cluster.wake/fault-channel faults
                         :seon.cluster.wake/key ::probe})})))

;;; ---------------------------------------------------------------------------
;;; C1 — the routed set and its three traps
;;; ---------------------------------------------------------------------------

(deftest a-routed-attribute-wakes-its-agent-and-nothing-else-does
  (with-connection
    (fn [connection]
      (let [mailbox (async/chan (async/sliding-buffer 1))
            {:keys [render key]} (route-probe! connection mailbox)]
        (try
          (db/transact! connection (message-tx "m-1"))
          (is (some? (test-support/await-event! mailbox "mailbox wake"))
              "a message to this agent reaches ITS mailbox")
          (testing "a commit of attributes only a TURN writes wakes no
                    mailbox — the trap that would spin an idle cluster"
            (db/transact! connection (run-tx "run-1"))
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
          (db/transact! connection [{:seon.cluster.agent/id "agent-b"}])
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
                          (db/transact! connection
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

(deftest delivery-requires-both-a-closed-offer-and-the-derived-fence
  ;; `offer!` establishes transport state; the route owner establishes
  ;; lifecycle meaning. Closedness alone cannot turn a broken render
  ;; route into an intentional agent fence.
  (let [closed (async/chan 1)
        full (async/chan 1)
        open (async/chan 1)
        sliding (async/chan (async/sliding-buffer 1))]
    (async/close! closed)
    (async/offer! full :fill)
    (async/offer! sliding :fill)
    (is (= :seon.cluster.wake/fenced
           (wake/delivery (async/offer! closed :wake) true))
        "a closed, still-routed agent mailbox is the fence")
    (is (= :seon.cluster.wake/refused
           (wake/delivery false false))
        "the same closed transport without a derived fence stays loud")
    (is (= :seon.cluster.wake/refused
           (wake/delivery (async/offer! full :wake) false))
        "a LIVE route that cannot take it is the loud case")
    (is (= :seon.cluster.wake/delivered
           (wake/delivery (async/offer! open :wake) false)))
    (is (= :seon.cluster.wake/delivered
           (wake/delivery (async/offer! sliding :wake) false))
        "a sliding buffer is never full, so `refused` cannot arise on a
         mailbox and the classification is total there")))

(deftest a-fenced-mailbox-is-recognized-and-never-hangs-the-writer
  ;; THE hazard is unchanged: datahike fires listeners inside the
  ;; transaction's go block BEFORE (deliver p tx-report)
  ;; (writer.cljc:384-386), so an escaping exception means the
  ;; committing caller waits forever. What CHANGED is the verdict on a
  ;; closed mailbox. `seon.cluster.loop`'s terminal settlement fence
  ;; closes an agent's mailbox in place so it takes no further pass over
  ;; a still-running receipt; committing that very fault also commits
  ;; its explanation message, so routing used to turn the quarantine
  ;; into a fresh core fault about itself. It is a lifecycle state, and
  ;; the router now says so.
  (with-connection
    (fn [connection]
      (let [mailbox (async/chan (async/sliding-buffer 1))
            {:keys [faults key]}
            (route-probe!
             connection mailbox
             (fn [recipient channel]
               (and (= recipient (agent-eid connection))
                    (identical? channel mailbox)
                    (async.protocols/closed? channel))))]
        (try
          (async/close! mailbox)
          (let [committed (future (db/transact! connection (message-tx "m-9")))]
            (is (map? (test-support/await-event!
                       committed "fenced-mailbox transaction"))
                "the committing caller returned — the handler swallowed
                 its own failure instead of hanging the writer forever")
            ;; The listener runs to completion INSIDE the transaction
            ;; before the caller is delivered, so once the future has a
            ;; report the handler has already decided. `poll!` is an
            ;; ordering fact here, not a race with a sleep in it.
            (is (nil? (async/poll! faults))
                "and the fence produced NO core fault about itself"))
          (is (= ["m-9"]
                 (db/q '[:find [?id ...]
                        :where [_ :seon.cluster.message/id ?id]]
                      @connection))
              "the message stays a durable fact — the fresh mailbox
               `arm!` builds after recovery derives it from facts")
          (finally
            (wake/unlisten! {:seon.cluster.wake/connection connection
                             :seon.cluster.wake/key key})))))))

(deftest a-live-but-non-accepting-route-still-faults-loudly
  ;; The half that must NOT go quiet. A route that is open and cannot
  ;; take the wake is losing a wake nobody will re-derive, which is the
  ;; invisible-failure class the fault fact exists for.
  (with-connection
    (fn [connection]
      (let [mailbox (async/chan 1)
            {:keys [faults key]} (route-probe! connection mailbox)]
        (try
          ;; fixed-1, saturated and unread: the next offer! must park,
          ;; so it returns nil rather than false
          (async/offer! mailbox ::filler)
          (let [committed (future (db/transact! connection (message-tx "m-f")))]
            (is (map? (test-support/await-event!
                       committed "saturated-route transaction")))
            (let [fault (test-support/await-event! faults "route fault")]
              (is (= :seon.cluster.wake/undeliverable-wake
                     (:seon.error/kind (ex-data fault)))
                  "one classifier, one kind — no second fault path")
              (is (= :seon.cluster.wake/mailbox
                     (:seon.cluster.wake/route (ex-data fault)))
                  "and it names WHICH route, so the fault is actionable")))
          (finally
            (wake/unlisten! {:seon.cluster.wake/connection connection
                             :seon.cluster.wake/key key})))))))

(deftest a-closed-render-channel-delivers-a-fault
  ;; Production shutdown unlistens before closing render. A closed
  ;; render route while this listener is live is therefore a broken
  ;; delivery, not an agent quarantine.
  (with-connection
    (fn [connection]
      (let [mailbox (async/chan (async/sliding-buffer 1))
            {:keys [render faults key]} (route-probe! connection mailbox)]
        (try
          (async/close! render)
          (let [committed (future (db/transact! connection (message-tx "m-r")))]
            (is (map? (test-support/await-event!
                       committed "closed-render transaction"))
                "the writer still returned")
            (let [fault (test-support/await-event! faults "render fault")]
              (is (= :seon.cluster.wake/undeliverable-wake
                     (:seon.error/kind (ex-data fault))))
              (is (= :seon.cluster.wake/render
                     (:seon.cluster.wake/route (ex-data fault))))))
          (finally
            (wake/unlisten! {:seon.cluster.wake/connection connection
                             :seon.cluster.wake/key key})))))))

;;; ---------------------------------------------------------------------------
;;; The F2 sealed suite — route-render-wake-and-disjointness-property
;;; seed 2026072826
;;; ---------------------------------------------------------------------------

(deftest route-render-wake-and-disjointness-property
  ;; ORACLE: over generated commit batches — the render channel receives
  ;; a wake for EVERY report, unconditionally; mailbox routing is
  ;; unchanged by the added delivery; and the C2 property holds over the
  ;; re-grounded COMPUTED sets, with the message/to delivery asserted
  ;; from the other direction (an attribute that routes to a mailbox is
  ;; one no turn commits).
  (let [check
        (tc/quick-check
         50
         (prop/for-all
          [commits (gen/vector (gen/elements [:message :agent :run]) 1 8)]
          (test-support/with-database
            (fn [connection]
              (db/transact! connection [{:seon.cluster.agent/id "agent-a"}])
              (let [mailbox (async/chan 64)
                    armer (async/chan 64)
                    ;; a COUNTING render channel: production slides,
                    ;; because a wake says only "look" and coalescing is
                    ;; free — here every delivery is kept so the
                    ;; per-report claim can be counted at all
                    render (async/chan 256)
                    faults (async/chan (async/sliding-buffer 1))
                    key (wake/route!
                         {:seon.cluster.wake/connection connection
                          :seon.cluster.wake/channels
                          (fn [] {(agent-eid connection) mailbox})
                          :seon.cluster.wake/fenced? (fn [_ _] false)
                          :seon.cluster.wake/armer-channel armer
                          :seon.cluster.wake/render-channel render
                          :seon.cluster.wake/fault-channel faults
                          :seon.cluster.wake/key ::property})]
                (try
                  (doseq [[commit index] (map vector commits (range))]
                    (case commit
                      :message (db/transact! connection (message-tx
                                                       (str "pm-" index)))
                      :agent (db/transact! connection
                                         [{:seon.cluster.agent/id
                                           (str "pa-" index)}])
                      :run (db/transact! connection (run-tx
                                                   (str "pr-" index)))))
                  (let [drain (fn [channel]
                                (loop [n 0]
                                  (if (async/poll! channel)
                                    (recur (inc n))
                                    n)))
                        rendered (drain render)
                        mailed (drain mailbox)
                        armed (drain armer)]
                    (and
                     ;; one render wake per REPORT, every report
                     (= (count commits) rendered)
                     ;; routing is unchanged by the added delivery
                     (= (count (filter #{:message} commits)) mailed)
                     (= (count (filter #{:agent} commits)) armed)
                     ;; and no fault was raised on any healthy path
                     (nil? (async/poll! faults))))
                  (finally
                    (wake/unlisten!
                     {:seon.cluster.wake/connection connection
                      :seon.cluster.wake/key key})))))))
         :seed 2026072826)]
    (is (true? (:result check))
        (str "routing/render-wake property failed: " (pr-str check))))

  (testing "C2 from the other direction: every attribute that routes to
            a mailbox is one no turn commits"
    (let [wakes (wake/wake-attributes)
          commits (cluster.loop/committed-attributes)]
      (is (every? (fn [attribute] (not (contains? commits attribute)))
                  wakes))
      (is (every? (fn [attribute] (not (contains? wakes attribute)))
                  commits)))))

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
        (db/transact! connection (message-tx "m-after"))
        (is (nil? (async/poll! mailbox))
            "nothing is delivered after unlisten")))))
