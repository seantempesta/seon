(ns seon.server.tx-feed-replay-test
  "DE-2 lossless wake: paginated `replay-tx` recovers every committed tx after
   a subscriber watermark without an unbounded reply or a truncated old range.

   Two layers:
   - pure bounded pages over an isolated in-memory Datahike connection;
   - boot's `replay-tx` op exposes the same continuation facts and routing."
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
;; Layer 1 — wire/replay-tx-page (the pure, per-subscriber replay source)
;; ---------------------------------------------------------------------------

(defn- replay-pages
  [conn db-name since-t page-size]
  (loop [cursor since-t through-t nil pages [] remaining 100]
    (when-not (pos? remaining)
      (throw (ex-info "replay test exceeded its page bound" {})))
    (let [page (#'wire/replay-tx-page* conn db-name cursor through-t page-size)
          pages (conj pages page)]
      (if (:seon.store.wire/done? page)
        pages
        (recur (:seon.store.wire/continuation-t page)
               (:seon.store.wire/through-t page)
               pages
               (dec remaining))))))

(defn- replay-events
  [conn db-name since-t page-size]
  (into [] (mapcat :seon.store.wire/events)
        (replay-pages conn db-name since-t page-size)))

(deftest replay-pages-return-the-complete-gap-in-order
  (let [conn (mem-conn)
        t0   (commit! conn [{:db/ident :note/text :db/valueType :db.type/string
                             :db/cardinality :db.cardinality/one}
                            {:db/ident :note/id :db/valueType :db.type/string
                             :db/unique :db.unique/identity
                             :db/cardinality :db.cardinality/one}])
        t1   (commit! conn [{:note/id "a" :note/text "one"}])
        t2   (commit! conn [{:note/id "b" :note/text "two"}])
        t3   (commit! conn [{:note/id "c" :note/text "three"}])]
    (testing "a gap larger than one page is complete and ascending"
      (let [pages (replay-pages conn "db" t1 1)
            evs   (into [] (mapcat :seon.store.wire/events) pages)]
        (is (= 2 (count pages)) "the one-event page bound was honored")
        (is (= [t2 t3] (mapv :seon.store.wire/basis-t evs))
            "exactly the txs strictly after since-t, in commit order")
        (is (= [false true] (mapv :seon.store.wire/done? pages)))
        (is (= [t2 t3] (mapv :seon.store.wire/continuation-t pages)))
        (is (= [t3 t3] (mapv :seon.store.wire/through-t pages))
            "every page retains the first page's upper watermark")
        (is (every? #(= "tx" (:seon.store.wire/event %)) evs)
            "each replayed event is shaped like a live tx event")
        (is (= ["db" "db"] (mapv :seon.store.wire/db-name evs))
            "tagged with the subscriber's db-name")))
    (testing "a cursor at the upper watermark is an explicit empty final page"
      (let [page (#'wire/replay-tx-page* conn "db" t3 nil 1)]
        (is (true? (:seon.store.wire/done? page)))
        (is (= t3 (:seon.store.wire/through-t page)))
        (is (= t3 (:seon.store.wire/continuation-t page)))
        (is (empty? (:seon.store.wire/events page)))))
    (testing "since-t before the first commit replays the whole log"
      (is (= [t0 t1 t2 t3]
             (mapv :seon.store.wire/basis-t
                   (replay-events conn "db" 0 2)))))
    (testing "replayed tx-data carries the asserted datoms (native 5-vectors)"
      (let [ev (first (replay-events conn "db" t1 1))
            vs (set (map (fn [[_ a v _ op]] [a v op]) (:seon.store.wire/tx-data ev)))]
        (is (contains? vs [:note/id "b" true]))
        (is (contains? vs [:note/text "two" true]))))))

(deftest replay-captures-one-upper-watermark-across-concurrent-commits
  (let [conn (mem-conn)
        t0   (commit! conn [{:db/ident :note/value :db/valueType :db.type/long
                             :db/cardinality :db.cardinality/one}])
        t1   (commit! conn [{:note/value 1}])
        t2   (commit! conn [{:note/value 2}])
        p1   (#'wire/replay-tx-page* conn "db" t0 nil 1)
        t3   (commit! conn [{:note/value 3}])
        p1-again (#'wire/replay-tx-page* conn "db" t0
                                         (:seon.store.wire/through-t p1) 1)
        p2   (#'wire/replay-tx-page* conn "db"
                                     (:seon.store.wire/continuation-t p1)
                                     (:seon.store.wire/through-t p1) 1)
        next-replay (#'wire/replay-tx-page* conn "db" t2 nil 1)]
    (is (= t2 (:seon.store.wire/through-t p1))
        "the first page snapshots the writer before the racing commit")
    (is (= p1 p1-again) "repeating a page request is deterministic")
    (is (= [t1] (mapv :seon.store.wire/basis-t
                      (:seon.store.wire/events p1))))
    (is (= [t2] (mapv :seon.store.wire/basis-t
                      (:seon.store.wire/events p2))))
    (is (true? (:seon.store.wire/done? p2)))
    (is (= [t3] (mapv :seon.store.wire/basis-t
                      (:seon.store.wire/events next-replay)))
        "the commit beyond the fixed upper remains for the next replay")))

(deftest replay-recovers-tx-meta-and-wire-id
  (let [conn (mem-conn)
        _    (commit! conn [{:db/ident :note/id :db/valueType :db.type/string
                             :db/unique :db.unique/identity
                             :db/cardinality :db.cardinality/one}])
        tk   (bt conn)
        _    (commit-meta! conn [{:note/id "x"}]
                           {:seon.store.wire/id "wid-7"
                            :seon.audit/cause :seon.cause/test})
        evs  (replay-events conn "db" tk 1)]
    (is (= 1 (count evs)))
    (let [ev (first evs)]
      (is (= "wid-7" (:seon.store.wire/id ev))
          "wire-id recovered from the tx entity for echo-suppression")
      (is (= "wid-7" (get-in ev [:seon.store.wire/tx-meta :seon.store.wire/id])))
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
        ev    (first (replay-events conn "db" tk 1))
        datoms (:seon.store.wire/tx-data ev)
        retraction (first (filter (fn [[_ a v _ op]] (and (= a :note/text) (= v "old") (false? op)))
                                  datoms))]
    (is (= 1 (:seon.store.wire/datoms-retracted ev)) "one retraction counted")
    (is (some? retraction) "the explicit retraction is replayed")
    (is (neg? (long (nth retraction 3)))
        "the retraction datom keeps its NEGATIVE tx — byte-identical to a live datom->wire")))

(deftest replay-refuses-to-advance-past-an-unreconstructable-transaction
  (let [conn    (mem-conn)
        t0      (commit! conn [{:db/ident :note/value :db/valueType :db.type/long
                                :db/cardinality :db.cardinality/one}])
        t1      (commit! conn [{:note/value 1}])
        instant (:db/txInstant (d/entity (d/db conn) t1))
        _       (commit! conn [[:db/retract t1 :db/txInstant instant]])
        error   (try
                  (replay-events conn "db" t0 1)
                  nil
                  (catch clojure.lang.ExceptionInfo caught caught))]
    (is (some? error))
    (is (= t1 (:seon.store.wire/basis-t (ex-data error)))
        "mutable tx metadata cannot silently remove a basis-t from replay")
    (is (= "protocol" (:seon.store.wire/error-kind (ex-data error))))))

;; ---------------------------------------------------------------------------
;; Layer 2 — boot wiring: the `replay-tx` handle-op returns the gap directly
;; in the reply. Driven through wire/handle-op directly (the in-process
;; harness style of wire_request_id_test).
;; ---------------------------------------------------------------------------

(def ^:dynamic *conn* nil)
(def ^:dynamic *ambient* nil)

(use-fixtures :each
  (fn [tfn]
    (let [conn    (mem-conn)
          ambient (str "replay-wire-" (System/nanoTime))]
      ;; Pin wire's ambient-db-name + install ::raw-broadcast under it, so a
      ;; replay-tx with no agent-id/db-name resolves to the same db-name the
      ;; commit broadcasts under (mirrors a cold wire-server boot).
      (reset! @#'wire/state {:conn conn :ambient-db-name ambient})
      (d/listen conn :seon.server.wire/raw-broadcast
                (#'wire/raw-broadcast-listener-fn ambient))
      (registry/run-on-ensure-db-hooks! conn ambient)
      (binding [*conn* conn *ambient* ambient] (tfn)))))

(deftest replay-tx-op-returns-gap-directly
  ;; The pod's pub-socket adapter calls "replay-tx" on every (re)connect and
  ;; applies the reply's events ahead of buffered live frames.
  (let [t0   (commit! *conn* [{:db/ident :u3/n :db/valueType :db.type/string
                               :db/cardinality :db.cardinality/one}])
        t1   (commit! *conn* [{:u3/n "a"}])
        t2   (commit! *conn* [{:u3/n "b"}])
        resp (wire/handle-op *conn* {:seon.store.wire/op "replay-tx"
                                     :seon.store.wire/since-t t0})]
    (is (true? (:seon.store.wire/ok resp)))
    (is (= *ambient* (:seon.store.wire/db-name resp))
        "carries the resolved db-name — the client-side pub demux key")
    (is (= 2 (:seon.store.wire/replayed resp)))
    (is (true? (:seon.store.wire/done? resp)))
    (is (= t2 (:seon.store.wire/through-t resp)))
    (is (= t2 (:seon.store.wire/continuation-t resp)))
    (is (= [t1 t2] (mapv :seon.store.wire/basis-t (:seon.store.wire/events resp)))
        "the gap comes back DIRECTLY in the reply, ascending commit order")
    (is (every? #(= "tx" (:seon.store.wire/event %)) (:seon.store.wire/events resp))
        "each replayed event is shaped like a live tx event")))

(deftest replay-tx-op-requires-since-t
  (let [resp (wire/handle-op *conn* {:seon.store.wire/op "replay-tx"})]
    (is (false? (:seon.store.wire/ok resp)))
    (is (= "protocol" (:seon.store.wire/error-kind resp)))))

(deftest replay-tx-op-rejects-an-impossible-watermark
  (let [current (bt *conn*)
        resp    (wire/handle-op *conn* {:seon.store.wire/op "replay-tx"
                                        :seon.store.wire/since-t (inc current)})]
    (is (false? (:seon.store.wire/ok resp)))
    (is (= "protocol" (:seon.store.wire/error-kind resp)))))
