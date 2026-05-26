(ns seon.sidecar.protocol-integration-test
  "Integration tests for the sidecar wire protocol. Each test spawns its own
   JVM writer subprocess against an in-memory store, drives it via the
   client API in `seon.sidecar.client`, and asserts on response + pub event
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
            [clojure.string :as str]
            [seon.sidecar.client :as client]
            [seon.sidecar.transit :as transit])
  (:import [java.io File]
           [java.util UUID]))

(set! *warn-on-reflection* true)

;; ---------- Fixture: spawn a fresh writer per test ----------

(def ^:dynamic *ctx* nil)

(defn- unique-sock [prefix]
  (str "/tmp/seon-poc-test-" prefix "-" (System/nanoTime) ".sock"))

(defn- writer-ready?
  "Poll: is there a process accepting connections on this UDS?"
  [path]
  (try
    (with-open [ch (client/connect path)]
      (.isConnected ch))
    (catch Throwable _ false)))

(defn- wait-for-socket! [path timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (writer-ready? path) :ok
        (> (System/currentTimeMillis) deadline)
        (throw (ex-info "writer never came up" {:path path}))
        :else (do (Thread/sleep 200) (recur))))))

(defn- spawn-writer!
  "Spawn `clojure -M:writer` in a child process pointed at unique sockets.
   Returns a map `{:req-sock, :pub-sock, :process}`."
  []
  (let [req-sock (unique-sock "req")
        pub-sock (unique-sock "pub")
        ;; In-memory backend — tests don't need persistence
        cmd ["clojure" "-M:writer"
             "--backend" "memory"
             "--req-sock" req-sock
             "--pub-sock" pub-sock]
        pb (doto (ProcessBuilder. ^java.util.List cmd)
             ;; Inherit so we see errors during dev; redirect to file would be
             ;; cleaner long-term.
             (.redirectErrorStream true)
             (.redirectOutput (java.lang.ProcessBuilder$Redirect/to
                               (File. (str "logs/writer-test-" (System/nanoTime) ".log")))))
        ;; Run from this project root (test runner is invoked here too)
        _ (.mkdirs (File. "logs"))
        proc (.start pb)]
    (wait-for-socket! req-sock 60000)
    (wait-for-socket! pub-sock 60000)
    {:req-sock req-sock
     :pub-sock pub-sock
     :process proc}))

(defn- teardown-writer! [{:keys [^Process process req-sock pub-sock]}]
  (try (.destroy process) (catch Throwable _))
  (try (.waitFor process) (catch Throwable _))
  (try (.delete (File. ^String req-sock)) (catch Throwable _))
  (try (.delete (File. ^String pub-sock)) (catch Throwable _)))

(defn- with-fresh-writer [tfn]
  (let [ctx (spawn-writer!)]
    (try
      (binding [*ctx* ctx] (tfn))
      (finally (teardown-writer! ctx)))))

(use-fixtures :each with-fresh-writer)

;; ---------- Helpers ----------

(defn- req! [op extra]
  (with-open [ch (client/connect (:req-sock *ctx*))]
    (client/call! ch (merge {"op" op} extra))))

(defn- T [v] (transit/write-str v))
(defn- decode-payload
  "Decode the Transit `payload` field if present."
  [resp]
  (transit/read-str (get resp "payload")))

(defn- decode-tx-data
  "Decode the `a` and `v` Transit strings of every datom in a tx-data
   wire array, returning [e a v t op] with native Clojure values."
  [tx-data]
  (mapv (fn [[e a v t op]]
          [e (transit/read-str a) (transit/read-str v) t op])
        tx-data))

(defn- attrs-of [tx-data]
  (set (map #(nth % 1) (decode-tx-data tx-data))))

(defn- decode-tx-meta [resp-or-ev]
  (transit/read-str (get resp-or-ev "tx-meta")))

(defn- decode-tempids [resp]
  (transit/read-str (get resp "tempids")))

(defn- install-person-schema! []
  (req! "transact"
        {"tx-data"
         "[{:db/ident :person/name :db/valueType :db.type/string :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
            {:db/ident :person/age  :db/valueType :db.type/long   :db/cardinality :db.cardinality/one}]"}))

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
      (is (= true (get r "ok")))
      (is (= true (get r "pong"))))))

(deftest test-transact-returns-updated-basis-t
  (testing "every transact bumps basis-t (single-writer monotonicity)"
    (let [r1 (install-person-schema!)
          r2 (req! "transact" {"tx-data" "[{:person/name \"alice\" :person/age 33}]"})
          r3 (req! "transact" {"tx-data" "[{:person/name \"bob\"   :person/age 41}]"})]
      (is (= true (get r1 "ok")))
      (is (= true (get r2 "ok")))
      (is (= true (get r3 "ok")))
      (is (< (get r1 "basis-t") (get r2 "basis-t") (get r3 "basis-t"))
          "basis-t is strictly monotonic across commits")
      (is (= (get r2 "basis-t") (get r3 "basis-t-before"))
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
              r2 (req! "transact" {"tx-data" "[{:person/name \"alex\"}]"})
              r3 (req! "transact" {"tx-data" "[{:person/name \"boris\"}]"})]
          ;; Wait briefly for the pub fanout
          (Thread/sleep 250)
          (is (= 3 (count @events))
              "exactly one pub event per transact")
          (is (= (mapv #(get % "basis-t") @events)
                 [(get r1 "basis-t") (get r2 "basis-t") (get r3 "basis-t")])
              "pub events arrive in commit order and carry the right basis-t")
          (is (every? #(= "tx" (get % "event")) @events)
              "all pub events are tx events"))
        (finally (.close pub-ch))))))

(deftest test-tx-data-shape-on-pub
  (testing "gap #1 — pub event ships full datoms (5-vectors), not just counts.
            Adapted from datahike.test.listen-test/test-listen!:
            each datom is [e a v t op] with a as namespace/name string and
            op as boolean."
    (install-person-schema!)
    (let [events (atom [])
          ^java.nio.channels.SocketChannel pub-ch
          (client/start-pub-collector! (:pub-sock *ctx*) events)]
      (try
        (let [r (req! "transact" {"tx-data" "[{:person/name \"dima\" :person/age 19}]"})
              _ (Thread/sleep 250)
              ev (first @events)
              tx-data (get ev "tx-data")]
          (is (= 1 (count @events)) "one event for one tx")
          (is (vector? tx-data) "tx-data is a vector")
          (is (>= (count tx-data) 3) "at least txInstant + name + age datoms")
          (is (every? #(= 5 (count %)) tx-data)
              "datom on the wire is [e a v t op]")
          ;; `a` is a Transit-JSON string of a keyword; `v` is Transit-JSON
          ;; of any value. Both decode via seon.sidecar.transit/read-str.
          (is (every? #(string? (nth % 1)) tx-data)
              "attr is a (transit-encoded) string")
          (is (every? #(string? (nth % 2)) tx-data)
              "value is a (transit-encoded) string")
          (is (every? #(boolean? (nth % 4)) tx-data)
              "op is a boolean")
          (let [attrs (attrs-of tx-data)]
            (is (contains? attrs :person/name) "name datom present")
            (is (contains? attrs :person/age) "age datom present")
            (is (contains? attrs :db/txInstant) "tx datom present"))
          (is (= tx-data (get r "tx-data"))
              "response tx-data matches pub event tx-data"))
        (finally (.close pub-ch))))))

(deftest test-pub-does-not-fire-on-read-ops
  (testing "protocol invariant: q and pull are pure reads, no pub event"
    (install-person-schema!)
    (req! "transact" {"tx-data" "[{:person/name \"alice\" :person/age 33}]"})
    ;; Drain the events from the writes above, then watch for new ones during reads.
    (Thread/sleep 200)
    (let [events (atom [])
          ^java.nio.channels.SocketChannel pub-ch
          (client/start-pub-collector! (:pub-sock *ctx*) events)]
      (try
        (let [q-resp    (req! "q"
                              {"query" "[:find ?n :where [?e :person/name ?n]]"
                               "args"  []})
              pull-resp (req! "pull"
                              {"selector" "[:db/id :person/name]"
                               "eid"      "[:person/name \"alice\"]"})]
          (is (= true (get q-resp "ok")))
          (is (= true (get pull-resp "ok")))
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
        (let [r (req! "transact" {"tx-data" "[{:person/name \"alex\"}]"})
              _ (Thread/sleep 250)
              ev (first @events)
              r-meta  (decode-tx-meta r)
              ev-meta (decode-tx-meta ev)]
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

(deftest test-request-id-round-trips
  (testing "gap #2 prep: a caller-supplied request-id is echoed on the
            response AND on the pub event. Once gap #2 lands, this is the
            primitive own-tx dedup is built on."
    (install-person-schema!)
    (let [events (atom [])
          ^java.nio.channels.SocketChannel pub-ch
          (client/start-pub-collector! (:pub-sock *ctx*) events)
          rid    (str (UUID/randomUUID))]
      (try
        (let [r (req! "transact" {"tx-data" "[{:person/name \"alex\"}]"
                                  "request-id" rid})
              _ (Thread/sleep 250)
              ev (first @events)]
          (is (= rid (get r "request-id"))
              "response carries the same request-id")
          (is (= rid (get ev "request-id"))
              "pub event carries the same request-id"))
        (finally (.close pub-ch))))))

(deftest test-request-id-absent-when-not-supplied
  (testing "request-id is optional — when absent on the request, neither
            response nor pub event includes it"
    (install-person-schema!)
    (let [events (atom [])
          ^java.nio.channels.SocketChannel pub-ch
          (client/start-pub-collector! (:pub-sock *ctx*) events)]
      (try
        (let [r (req! "transact" {"tx-data" "[{:person/name \"alex\"}]"})
              _ (Thread/sleep 250)
              ev (first @events)]
          (is (nil? (get r "request-id"))
              "no request-id on response")
          (is (nil? (get ev "request-id"))
              "no request-id on pub event"))
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
              tx-data (get ev "tx-data")
              attrs (attrs-of tx-data)]
          (is (contains? attrs :db/ident) "schema tx includes :db/ident datoms")
          (is (contains? attrs :db/valueType) "schema tx includes :db/valueType")
          (is (contains? attrs :db/cardinality) "schema tx includes :db/cardinality"))
        (finally (.close pub-ch))))))

(deftest test-tempids-round-trip
  (testing "tempids in tx-data resolve to entity ids returned in the response"
    (install-person-schema!)
    (let [r (req! "transact"
                  {"tx-data" "[{:db/id -1 :person/name \"alice\" :person/age 33}]"})
          tempids (decode-tempids r)]
      (is (= true (get r "ok")))
      (is (map? tempids) "tempids is a map")
      ;; With Transit, the tempid key stays the integer -1 (was stringified
      ;; under the old CBOR walker).
      (let [eid (or (get tempids -1) (get tempids "-1"))]
        (is (some? eid) (str "tempid -1 was assigned; tempids=" (pr-str tempids)))
        (is (integer? eid))
        (is (pos? eid))))))
