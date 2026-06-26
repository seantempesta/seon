(ns seon.server.tx-feed-replay-test
  "DE-2 lossless wake: `subscribe-tx` with a per-subscriber `:seon.store.wire/since-t`
   replays every committed tx after that basis-t, shaped EXACTLY like a live
   `tx` event, in commit order, ahead of live events — so a reconnecting feed
   subscriber recovers a gap instead of dropping a wake.

   Two layers:
   - the pure replay (`wire/replay-tx-events`) over an isolated in-memory conn;
   - the boot wiring (`subscribe-tx` → `next-tx-event`) drains replay first,
     then live, exactly once."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [seon.server.boot]                 ; registers the raw tx-feed ops
            [seon.server.wire :as wire]
            [seon.server.registry :as registry]))

(set! *warn-on-reflection* true)

(defn- mem-conn []
  (let [cfg {:store {:backend :memory :id (java.util.UUID/randomUUID)}
             :schema-flexibility :read :keep-history? true}]
    (d/create-database cfg)
    (d/connect cfg)))

(defn- bt [conn] (:max-tx (d/db conn)))

(defn- commit! [conn tx-data]
  (:max-tx (:db-after (d/transact conn tx-data))))

(defn- commit-meta! [conn tx-data tx-meta]
  (:max-tx (:db-after (d/transact conn {:tx-data tx-data :tx-meta tx-meta}))))

;; ---------------------------------------------------------------------------
;; Layer 1 — wire/replay-tx-events (the pure, per-subscriber replay source)
;; ---------------------------------------------------------------------------

(deftest replay-returns-gap-in-order-exactly-once
  (let [conn (mem-conn)
        t0   (commit! conn [{:db/ident :note/text :db/valueType :db.type/string
                             :db/cardinality :db.cardinality/one}
                            {:db/ident :note/id :db/valueType :db.type/string
                             :db/unique :db.unique/identity
                             :db/cardinality :db.cardinality/one}])
        t1   (commit! conn [{:note/id "a" :note/text "one"}])
        t2   (commit! conn [{:note/id "b" :note/text "two"}])
        t3   (commit! conn [{:note/id "c" :note/text "three"}])]
    (testing "since-t in the middle replays only the later txs, ascending"
      (let [evs (wire/replay-tx-events conn "db" t1)]
        (is (= [t2 t3] (mapv :seon.store.wire/basis-t evs))
            "exactly the txs strictly after since-t, in commit order")
        (is (every? #(= "tx" (:seon.store.wire/event %)) evs)
            "each replayed event is shaped like a live tx event")
        (is (= ["db" "db"] (mapv :seon.store.wire/db-name evs))
            "tagged with the subscriber's db-name")))
    (testing "since-t at current basis-t replays nothing (strictly-greater)"
      (is (= [] (wire/replay-tx-events conn "db" t3))))
    (testing "since-t before the first commit replays the whole log"
      (is (= [t0 t1 t2 t3]
             (mapv :seon.store.wire/basis-t (wire/replay-tx-events conn "db" 0)))))
    (testing "replayed tx-data carries the asserted datoms (native 5-vectors)"
      (let [ev (first (wire/replay-tx-events conn "db" t1))   ; the t2 tx
            vs (set (map (fn [[_ a v _ op]] [a v op]) (:seon.store.wire/tx-data ev)))]
        (is (contains? vs [:note/id "b" true]))
        (is (contains? vs [:note/text "two" true]))))))

(deftest replay-recovers-tx-meta-and-write-id
  (let [conn (mem-conn)
        _    (commit! conn [{:db/ident :note/id :db/valueType :db.type/string
                             :db/unique :db.unique/identity
                             :db/cardinality :db.cardinality/one}])
        tk   (bt conn)
        _    (commit-meta! conn [{:note/id "x"}]
                           {:seon.store.wire/write-id "wid-7"
                            :seon.audit/cause :seon.cause/test})
        evs  (wire/replay-tx-events conn "db" tk)]
    (is (= 1 (count evs)))
    (let [ev (first evs)]
      (is (= "wid-7" (:seon.store.wire/write-id ev))
          "write-id recovered from the tx entity for echo-suppression")
      (is (= "wid-7" (get-in ev [:seon.store.wire/tx-meta :seon.store.wire/write-id])))
      (is (= :seon.cause/test (get-in ev [:seon.store.wire/tx-meta :seon.audit/cause]))
          "arbitrary seon tx-meta provenance survives the replay"))))

(deftest replay-preserves-retraction-shape
  (let [conn  (mem-conn)
        _     (commit! conn [{:db/ident :note/id :db/valueType :db.type/string
                              :db/unique :db.unique/identity
                              :db/cardinality :db.cardinality/one}
                             {:db/ident :note/text :db/valueType :db.type/string
                              :db/cardinality :db.cardinality/one}])
        _     (commit! conn [{:note/id "a" :note/text "old"}])
        tk    (bt conn)
        eid   (d/q '[:find ?e . :where [?e :note/id "a"]] (d/db conn))
        _     (commit! conn [[:db/retract eid :note/text "old"]
                             [:db/add eid :note/text "new"]])
        ev    (first (wire/replay-tx-events conn "db" tk))
        datoms (:seon.store.wire/tx-data ev)
        retraction (first (filter (fn [[_ a v _ op]] (and (= a :note/text) (= v "old") (false? op)))
                                  datoms))]
    (is (= 1 (:seon.store.wire/datoms-retracted ev)) "one retraction counted")
    (is (some? retraction) "the explicit retraction is replayed")
    (is (neg? (long (nth retraction 3)))
        "the retraction datom keeps its NEGATIVE tx — byte-identical to a live datom->wire")))

;; ---------------------------------------------------------------------------
;; Layer 2 — boot wiring: subscribe-tx (since-t) → next-tx-event drains replay
;; before live, exactly once. Driven through wire/handle-op directly (the
;; in-process harness style of wire_request_id_test).
;; ---------------------------------------------------------------------------

(def ^:dynamic *conn* nil)
(def ^:dynamic *ambient* nil)

(use-fixtures :each
  (fn [tfn]
    (let [conn    (mem-conn)
          ambient (str "replay-wire-" (System/nanoTime))]
      ;; Pin wire's ambient-db-name + install ::raw-broadcast under it, so a
      ;; subscribe-tx with no agent-id/db-name resolves to the same db-name the
      ;; commit broadcasts under (mirrors a cold wire-server boot).
      (reset! @#'wire/state {:conn conn :ambient-db-name ambient})
      (d/listen conn :seon.server.wire/raw-broadcast
                (#'wire/raw-broadcast-listener-fn ambient))
      (registry/run-on-ensure-db-hooks! conn ambient)
      (binding [*conn* conn *ambient* ambient] (tfn)))))

(defn- subscribe! [extra]
  (wire/handle-op *conn* (merge {:seon.store.wire/op "subscribe-tx"} extra)))

(defn- next-ev! [handle]
  (wire/handle-op *conn* {:seon.store.wire/op "next-tx-event"
                          :seon.store.wire/handle handle}))

(defn- drain
  "Collect up to `n` real events for `handle`, tolerating the async
   broadcast latency on live events (bounded retries)."
  [handle n]
  (loop [acc [] tries 0]
    (if (or (>= (count acc) n) (>= tries 80))
      acc
      (let [ev (next-ev! handle)]
        (if (:seon.store.wire/ok ev)
          (recur (conj acc ev) (inc tries))
          (do (Thread/sleep 5) (recur acc (inc tries))))))))

(deftest subscribe-with-since-t-replays-gap-then-live-once
  (let [t0 (commit! *conn* [{:db/ident :u/n :db/valueType :db.type/string
                             :db/cardinality :db.cardinality/one}])
        t1 (commit! *conn* [{:u/n "a"}])
        t2 (commit! *conn* [{:u/n "b"}])
        sub (subscribe! {:seon.store.wire/since-t t0})
        h   (:seon.store.wire/handle sub)]
    (is (true? (:seon.store.wire/ok sub)))
    (is (= 2 (:seon.store.wire/replayed sub)) "the two missed txs are queued for replay")
    ;; a live tx AFTER subscribe — its basis-t must arrive after the replay, once.
    (let [t3   (commit! *conn* [{:u/n "c"}])
          bts  (mapv :seon.store.wire/basis-t (drain h 3))]
      (is (= [t1 t2 t3] bts)
          "replay (t1,t2) drains before live (t3), each basis-t exactly once, in order"))))

(deftest subscribe-without-since-t-replays-nothing
  (let [_  (commit! *conn* [{:db/ident :u2/n :db/valueType :db.type/string
                             :db/cardinality :db.cardinality/one}])
        _  (commit! *conn* [{:u2/n "old-1"}])
        _  (commit! *conn* [{:u2/n "old-2"}])
        sub (subscribe! {})
        h   (:seon.store.wire/handle sub)]
    (is (true? (:seon.store.wire/ok sub)))
    (is (nil? (:seon.store.wire/replayed sub)) "no since-t → no replay key")
    (let [t-live (commit! *conn* [{:u2/n "live"}])
          bts    (mapv :seon.store.wire/basis-t (drain h 1))]
      (is (= [t-live] bts) "only the post-subscribe live tx is delivered — no backlog"))))
