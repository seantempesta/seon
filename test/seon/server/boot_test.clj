(ns seon.server.boot-test
  "Tests for the boot-layer wire ops: the raw tx-feed (subscribe-tx /
   next-tx-event / unsubscribe-tx — the guest `listen!` model) and the query
   subscriptions (register-subscription / unregister-subscription — the reactive
   engine). Driven in-process through the public `wire/handle-op` multimethod
   the boot ns extends.

   Loading `seon.server.boot` registers the op defmethods + the `::reactive`
   on-ensure-db hook. The fixture runs the registry hooks on the fresh conn
   (mirroring `wire/-main`) so the conn gets the reactive listener + the
   subscription schema."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [seon.server.boot]                 ; registers ops + hook (side-effecting)
            [seon.server.wire :as wire]
            [seon.server.registry :as registry]
            [seon.server.transit :as transit]
            [seon.server.test-util :as tu :refer [*ctx*]]))

(set! *warn-on-reflection* true)

;; One ambient db-name PER FIXTURE INVOCATION so the conn's ::raw-broadcast,
;; the ::reactive listener, and subscribe-tx's db-name resolution all agree.
;; In production wire/-main sets wire's state :ambient-db-name to the conn's
;; cfg :name and runs the hooks under it; the in-process fixture mirrors that.
;; The name must be UNIQUE per test: boot's `!engines` defonce caches engine
;; state by db-name for the JVM's lifetime (one-engine-per-conn — sound for a
;; wire-server boot, which is a fresh JVM). Reusing a fixed name across runs
;; handed test 2+ a stale engine bound to a dead conn (with prior sub-ids
;; cached) — first-run-green / rerun-red (audit 2026-06-10).

(use-fixtures :each
  (fn [tfn]
    (let [ambient (str "boot-test-ambient-" (System/nanoTime))
          ctx     (tu/spawn-writer!)
          conn    (:conn ctx)]
      ;; Pin wire's ambient-db-name to `ambient`, re-install ::raw-broadcast
      ;; under that name (replaces the spawn-writer! one, keyed by ::raw-broadcast),
      ;; and run the registry on-ensure-db hooks (installs ::reactive + seeds
      ;; subscription schema) under the SAME name. Now all three agree, exactly
      ;; as a cold wire-server boot wires them.
      (reset! @#'wire/state {:conn conn :ambient-db-name ambient})
      (d/listen conn :seon.server.wire/raw-broadcast
                (#'wire/raw-broadcast-listener-fn ambient))
      (registry/run-on-ensure-db-hooks! conn ambient)
      ;; The in-process datahike writer commits asynchronously; the hook's
      ;; seed-subscription-schema! transact may not have landed yet. Block
      ;; until the subscription attr is installed so register-subscription
      ;; (which transacts a :seon.subscription/id datom) doesn't race the seed.
      (let [deadline (+ (System/currentTimeMillis) 5000)]
        (loop []
          (when (and (not (contains? (:schema (d/db conn)) :seon.subscription/id))
                     (< (System/currentTimeMillis) deadline))
            (Thread/sleep 20) (recur))))
      (try (binding [*ctx* ctx] (tfn))
           (finally (tu/teardown-writer! ctx))))))

(defn- op! [op extra] (tu/req! op extra))
(defn- T [v] (transit/write-str v))
(defn- rT [s] (transit/read-str s))

(defn- drain-events
  "Poll next-tx-event for `handle` until `n` real events arrive or `tries`
   polls elapse. The in-process datahike writer commits on an async thread, so
   the first poll(s) may legitimately return no-event — exactly the bounded
   retry the guest's listen! loop performs. Returns the vector of event names."
  ([handle n] (drain-events handle n 20))
  ([handle n tries]
   (loop [acc [] t 0]
     (if (or (>= (count acc) n) (>= t tries))
       acc
       (let [ev (op! "next-tx-event" {"handle" handle})]
         (if (get ev "ok")
           (recur (conj acc (get ev "event")) (inc t))
           (recur acc (inc t))))))))

(deftest raw-tx-feed-delivers-commit-events
  (testing "subscribe-tx → commit → next-tx-event delivers the raw tx event"
    (let [sub  (op! "subscribe-tx" {})
          h    (get sub "handle")]
      (is (true? (get sub "ok")))
      (is (integer? h))
      ;; commit a tx; the ambient conn's ::raw-broadcast feeds the sub's queue.
      (op! "transact" {"tx-data" (T [{:db/ident :ft/v :db/valueType :db.type/string
                                      :db/cardinality :db.cardinality/one}])})
      (op! "transact" {"tx-data" (T [{:ft/v "hello"}])})
      ;; drain with bounded retries (async writer → broadcast latency). Both
      ;; the schema tx and the data tx surface as "tx" events.
      (let [evs (drain-events h 1)]
        (is (seq evs) (str "expected at least one tx event; saw " (pr-str evs)))
        (is (every? #(= "tx" %) evs))))))

(deftest next-tx-event-times-out-cleanly-when-empty
  (testing "no-event is a typed not-found, not an exception (guest swallows it)"
    (let [sub (op! "subscribe-tx" {})
          h   (get sub "handle")
          ev  (op! "next-tx-event" {"handle" h})]
      (is (false? (get ev "ok")))
      (is (= "no-event" (get ev "error")))
      (is (= "not-found" (get ev "error-kind"))))))

(deftest unknown-handle-is-typed-error
  (let [ev (op! "next-tx-event" {"handle" 999999})]
    (is (false? (get ev "ok")))
    (is (= "not-found" (get ev "error-kind")))))

(deftest unsubscribe-stops-the-feed
  (let [sub (op! "subscribe-tx" {})
        h   (get sub "handle")]
    (op! "unsubscribe-tx" {"handle" h})
    (op! "transact" {"tx-data" (T [{:db/ident :ft2/v :db/valueType :db.type/string
                                    :db/cardinality :db.cardinality/one}])})
    (op! "transact" {"tx-data" (T [{:ft2/v "x"}])})
    ;; after unsubscribe the handle is gone → unknown-handle error, not events
    (let [ev (op! "next-tx-event" {"handle" h})]
      (is (false? (get ev "ok")))
      (is (= "not-found" (get ev "error-kind"))))))

(deftest register-subscription-returns-initial-rows-and-fires-changed
  (testing "register-subscription persists + seeds rows; a matching commit emits changed-summaries"
    (op! "transact" {"tx-data" (T [{:db/ident :unit/name :db/valueType :db.type/string
                                    :db/cardinality :db.cardinality/one}])})
    (op! "transact" {"tx-data" (T [{:unit/name "A"}])})
    (let [reg (op! "register-subscription"
                   {"sub-id" "s1"
                    "query"  (pr-str '[:find ?n :where [?e :unit/name ?n]])})
          payload (rT (get reg "payload"))]
      (is (true? (get reg "ok")))
      (is (= "s1" (get reg "sub-id")))
      (is (= #{["A"]} (set (:seon.server.reactive/rows payload)))
          "initial rows seeded from the current db")
      (testing "a matching commit emits a changed-summaries event on the feed"
        (let [sub (op! "subscribe-tx" {})
              h   (get sub "handle")]
          (op! "transact" {"tx-data" (T [{:unit/name "B"}])})
          ;; expect a raw "tx" AND a reactive "changed-summaries" for the commit
          (let [evs (drain-events h 2)]
            (is (some #{"changed-summaries"} evs)
                (str "expected a changed-summaries event; saw " (pr-str evs)))))))))

(deftest unregister-subscription-stops-changed-events
  (op! "transact" {"tx-data" (T [{:db/ident :unit2/name :db/valueType :db.type/string
                                  :db/cardinality :db.cardinality/one}])})
  (op! "register-subscription" {"sub-id" "s2"
                                "query"  (pr-str '[:find ?n :where [?e :unit2/name ?n]])})
  (let [un (op! "unregister-subscription" {"sub-id" "s2"})
        payload (rT (get un "payload"))]
    (is (true? (get un "ok")))
    (is (false? (:seon.subscription/active? payload)))))
