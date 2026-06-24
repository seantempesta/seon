(ns seon.server.protocol-integration-test
  "Integration tests for the sidecar wire protocol. Each test spawns its own
   JVM writer subprocess against an in-memory store, drives it via the
   client API in `seon.server.client`, and asserts on response + pub event
   shapes.

   Borrowed-and-adapted from upstream datahike:
     - test-listen-pub-fires-on-transact            ~ datahike.test.listen-test/test-listen!
     - test-tx-data-shape-on-pub                    ~ datahike.test.listen-test (datom shape assertion)
     - test-pub-does-not-fire-on-read-ops           (new — protocol invariant)
     - test-transact-returns-updated-basis-t        ~ datahike.test.transact (basis-t monotonicity)
     - test-request-id-round-trips                  (new — gap #2 prep)
     - test-schema-altering-tx-shape                ~ datahike.test.transact (schema attrs)

   Each test is independent; the writer subprocess is started in
   `(use-fixtures :each ...)` and killed in teardown."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [seon.server.test-util :as tu :refer [*ctx*]]
            [seon.server.client :as client])
  (:import [java.util UUID]))

(set! *warn-on-reflection* true)

;; ---------- Fixture (shared in-process writer, see seon.server.test-util) ----------

(use-fixtures :each tu/with-fresh-writer)

;; ---------- Helpers ----------

(defn- req! [op extra]
  (with-open [ch (client/connect (:req-sock *ctx*))]
    (client/call! ch (merge {:seon.store.wire/op op} extra))))

(defn- attrs-of [tx-data]
  ;; Under the uniform Transit frame, each datom is [e a v t op] with a as a
  ;; native keyword attr and v native — no decode.
  (set (map #(nth % 1) tx-data)))

(defn- install-person-schema! []
  (req! "transact"
        {:seon.store.wire/tx-data
         [{:db/ident :person/name :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
          {:db/ident :person/age  :db/valueType :db.type/long   :db/cardinality :db.cardinality/one}]}))

(defn- await-pub-events [n timeout-ms]
  (let [events (atom [])
        deadline (+ (System/currentTimeMillis) timeout-ms)
        ^java.nio.channels.SocketChannel pub-ch
        (client/start-pub-collector! (:pub-sock *ctx*) events)]
    (try
      (loop []
        (cond
          (>= (count @events) n) @events
          (> (System/currentTimeMillis) deadline) @events
          :else (do (Thread/sleep 25) (recur))))
      (finally (try (.close pub-ch) (catch Throwable _))))))

;; ---------- Tests ----------

(deftest test-ping-roundtrip
  (testing "ping is the cheapest req/resp shape — sanity-check the codec"
    (let [r (req! "ping" {})]
      (is (= true (:seon.store.wire/ok r)))
      (is (= true (:seon.store.wire/pong r))))))

(deftest test-transact-returns-updated-basis-t
  (testing "every transact bumps basis-t (single-writer monotonicity)"
    (let [r1 (install-person-schema!)
          r2 (req! "transact" {:seon.store.wire/tx-data [{:person/name "alice" :person/age 33}]})
          r3 (req! "transact" {:seon.store.wire/tx-data [{:person/name "bob"   :person/age 41}]})]
      (is (= true (:seon.store.wire/ok r1)))
      (is (= true (:seon.store.wire/ok r2)))
      (is (= true (:seon.store.wire/ok r3)))
      (is (< (:seon.store.wire/basis-t r1) (:seon.store.wire/basis-t r2) (:seon.store.wire/basis-t r3))
          "basis-t is strictly monotonic across commits")
      (is (= (:seon.store.wire/basis-t r2) (:seon.store.wire/basis-t-before r3))
          "basis-t-before of tx N+1 equals basis-t of tx N"))))

(deftest test-pub-fires-on-transact
  (testing "borrowed from datahike.test.listen-test/test-listen!: every
            committed transact produces exactly one pub event with the
            corresponding basis-t"
    (let [events (atom [])
          ^java.nio.channels.SocketChannel pub-ch
          (client/start-pub-collector! (:pub-sock *ctx*) events)]
      (try
        (let [r1 (install-person-schema!)
              r2 (req! "transact" {:seon.store.wire/tx-data [{:person/name "alex"}]})
              r3 (req! "transact" {:seon.store.wire/tx-data [{:person/name "boris"}]})]
          ;; Wait briefly for the pub fanout
          (Thread/sleep 250)
          (is (= 3 (count @events))
              "exactly one pub event per transact")
          (is (= (mapv :seon.store.wire/basis-t @events)
                 [(:seon.store.wire/basis-t r1) (:seon.store.wire/basis-t r2) (:seon.store.wire/basis-t r3)])
              "pub events arrive in commit order and carry the right basis-t")
          (is (every? #(= "tx" (:seon.store.wire/event %)) @events)
              "all pub events are tx events"))
        (finally (.close pub-ch))))))

(deftest test-tx-data-shape-on-pub
  (testing "gap #1 — pub event ships full datoms (5-vectors), not just counts.
            Adapted from datahike.test.listen-test/test-listen!:
            each datom is [e a v t op] with a as a native keyword and
            op as boolean."
    (install-person-schema!)
    (let [events (atom [])
          ^java.nio.channels.SocketChannel pub-ch
          (client/start-pub-collector! (:pub-sock *ctx*) events)]
      (try
        (let [r (req! "transact" {:seon.store.wire/tx-data [{:person/name "dima" :person/age 19}]})
              _ (Thread/sleep 250)
              ev (first @events)
              tx-data (:seon.store.wire/tx-data ev)]
          (is (= 1 (count @events)) "one event for one tx")
          (is (vector? tx-data) "tx-data is a vector")
          (is (>= (count tx-data) 3) "at least txInstant + name + age datoms")
          (is (every? #(= 5 (count %)) tx-data)
              "datom on the wire is [e a v t op]")
          ;; Uniform Transit: `a` is a native keyword, `v` is the native value.
          (is (every? #(keyword? (nth % 1)) tx-data)
              "attr is a native keyword")
          (is (every? #(boolean? (nth % 4)) tx-data)
              "op is a boolean")
          (let [attrs (attrs-of tx-data)]
            (is (contains? attrs :person/name) "name datom present")
            (is (contains? attrs :person/age) "age datom present")
            (is (contains? attrs :db/txInstant) "tx datom present"))
          (is (= tx-data (:seon.store.wire/tx-data r))
              "response tx-data matches pub event tx-data"))
        (finally (.close pub-ch))))))

(deftest test-pub-does-not-fire-on-read-ops
  (testing "protocol invariant: q and pull are pure reads, no pub event"
    (install-person-schema!)
    (req! "transact" {:seon.store.wire/tx-data [{:person/name "alice" :person/age 33}]})
    ;; Drain the events from the writes above, then watch for new ones during reads.
    (Thread/sleep 200)
    (let [events (atom [])
          ^java.nio.channels.SocketChannel pub-ch
          (client/start-pub-collector! (:pub-sock *ctx*) events)]
      (try
        (let [q-resp    (req! "q"
                              {:seon.store.wire/query '[:find ?n :where [?e :person/name ?n]]
                               :seon.store.wire/args  []})
              pull-resp (req! "pull"
                              {:seon.store.wire/selector [:db/id :person/name]
                               :seon.store.wire/eid      [:person/name "alice"]})]
          (is (= true (:seon.store.wire/ok q-resp)))
          (is (= true (:seon.store.wire/ok pull-resp)))
          ;; Generous: 250ms is well over the <1µs commit→sub latency we
          ;; measured; if a read fired a pub event we'd see it.
          (Thread/sleep 250)
          (is (= 0 (count @events))
              "reads must NOT produce pub events"))
        (finally (.close pub-ch))))))

(deftest test-tx-meta-shape
  (testing "datahike emits db/txInstant + db/commitId on every commit; gap
            #1 carries them on the pub event AND the transact response"
    (install-person-schema!)
    (let [events (atom [])
          ^java.nio.channels.SocketChannel pub-ch
          (client/start-pub-collector! (:pub-sock *ctx*) events)]
      (try
        (let [r (req! "transact" {:seon.store.wire/tx-data [{:person/name "alex"}]})
              _ (Thread/sleep 250)
              ev (first @events)
              r-meta  (:seon.store.wire/tx-meta r)
              ev-meta (:seon.store.wire/tx-meta ev)]
          (is (contains? r-meta :db/txInstant)
              "response tx-meta has :db/txInstant")
          (is (contains? r-meta :db/commitId)
              "response tx-meta has :db/commitId")
          (is (contains? ev-meta :db/txInstant)
              "pub event tx-meta has :db/txInstant")
          (is (contains? ev-meta :db/commitId)
              "pub event tx-meta has :db/commitId")
          (is (= r-meta ev-meta)
              "response and pub event carry identical tx-meta"))
        (finally (.close pub-ch))))))

(deftest test-write-id-round-trips
  (testing "a caller-supplied write-id is echoed on the response AND on the
            pub event. This is the primitive own-tx dedup is built on."
    (install-person-schema!)
    (let [events (atom [])
          ^java.nio.channels.SocketChannel pub-ch
          (client/start-pub-collector! (:pub-sock *ctx*) events)
          rid    (str (UUID/randomUUID))]
      (try
        (let [r (req! "transact" {:seon.store.wire/tx-data [{:person/name "alex"}]
                                  :seon.store.wire/write-id rid})
              _ (Thread/sleep 250)
              ev (first @events)]
          (is (= rid (:seon.store.wire/write-id r))
              "response carries the same write-id")
          (is (= rid (:seon.store.wire/write-id ev))
              "pub event carries the same write-id"))
        (finally (.close pub-ch))))))

(deftest test-write-id-absent-when-not-supplied
  (testing "write-id is optional — when absent on the request, neither
            response nor pub event includes it"
    (install-person-schema!)
    (let [events (atom [])
          ^java.nio.channels.SocketChannel pub-ch
          (client/start-pub-collector! (:pub-sock *ctx*) events)]
      (try
        (let [r (req! "transact" {:seon.store.wire/tx-data [{:person/name "alex"}]})
              _ (Thread/sleep 250)
              ev (first @events)]
          (is (nil? (:seon.store.wire/write-id r))
              "no write-id on response")
          (is (nil? (:seon.store.wire/write-id ev))
              "no write-id on pub event"))
        (finally (.close pub-ch))))))

(deftest test-schema-altering-tx
  (testing "schema-installing transact ships datoms for :db/ident etc.
            — same shape as data-installing transact, just different attrs.
            This is the per-tx invariant that lets a future schema-altering
            flag (gap #7) be a derived property of tx-data rather than a
            separate field."
    (let [events (atom [])
          ^java.nio.channels.SocketChannel pub-ch
          (client/start-pub-collector! (:pub-sock *ctx*) events)]
      (try
        (install-person-schema!)
        (Thread/sleep 250)
        (let [ev (first @events)
              tx-data (:seon.store.wire/tx-data ev)
              attrs (attrs-of tx-data)]
          (is (contains? attrs :db/ident) "schema tx includes :db/ident datoms")
          (is (contains? attrs :db/valueType) "schema tx includes :db/valueType")
          (is (contains? attrs :db/cardinality) "schema tx includes :db/cardinality"))
        (finally (.close pub-ch))))))

(deftest test-tempids-round-trip
  (testing "tempids in tx-data resolve to entity ids returned in the response"
    (install-person-schema!)
    (let [r (req! "transact"
                  {:seon.store.wire/tx-data [{:db/id -1 :person/name "alice" :person/age 33}]})
          tempids (:seon.store.wire/tempids r)]
      (is (= true (:seon.store.wire/ok r)))
      (is (map? tempids) "tempids is a map")
      ;; With Transit, the tempid key stays the integer -1.
      (let [eid (or (get tempids -1) (get tempids "-1"))]
        (is (some? eid) (str "tempid -1 was assigned; tempids=" (pr-str tempids)))
        (is (integer? eid))
        (is (pos? eid))))))
