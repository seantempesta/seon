(ns seon.server.boot-test
  "Tests for the boot-layer wire ops: the query subscriptions
   (register-subscription / unregister-subscription — the reactive engine).
   Driven in-process through the public `wire/handle-op` multimethod the boot
   ns extends; changed-summaries delivery is observed on the in-process
   broadcast fanout (`bcast/subscribe!`) — the same per-DB routing the pub
   socket rides. (The tx-feed `replay-tx` op is covered by
   seon.server.tx-feed-replay-test.)

   Wire shape: the uniform Transit frame (`seon.server.codec`) — every request
   extra AND every response field is a `:seon.store.wire/*` NAMESPACED-KEYWORD
   key with NATIVE Clojure values (tx-data is a native vector, `payload` is a
   native map — no inner Transit strings). Same convention as the correctly-
   written `wire_request_id_test`.

   Loading `seon.server.boot` registers the op defmethods + the `::reactive`
   on-ensure-db hook. The fixture runs the registry hooks on the fresh conn
   (mirroring `wire/-main`) so the conn gets the reactive listener + the
   subscription schema."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [seon.server.boot]                 ; registers ops + hook (side-effecting)
            [seon.server.broadcast :as bcast]
            [seon.server.wire :as wire]
            [seon.server.registry :as registry]
            [seon.server.test-util :as tu :refer [*ctx*]]))

(set! *warn-on-reflection* true)

;; One ambient db-name PER FIXTURE INVOCATION so the conn's ::raw-broadcast,
;; the ::reactive listener, and the ops' db-name resolution all agree.
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

(defn- await-event
  "Block (bounded) until an event named `event-name` shows up in `!evs`
   (an atom of event-name strings) or ~3s elapse. Returns the vector of
   event names seen. The in-process datahike writer commits on an async
   thread, so delivery is legitimately delayed."
  [!evs event-name]
  (let [deadline (+ (System/currentTimeMillis) 3000)]
    (loop []
      (if (or (some #{event-name} @!evs)
              (>= (System/currentTimeMillis) deadline))
        @!evs
        (do (Thread/sleep 20) (recur))))))

(deftest register-subscription-returns-initial-rows-and-fires-changed
  (testing "register-subscription persists + seeds rows; a matching commit emits changed-summaries"
    (op! "transact" {:seon.store.wire/tx-data [{:db/ident :unit/name :db/valueType :db.type/string
                                                :db/cardinality :db.cardinality/one}]})
    (op! "transact" {:seon.store.wire/tx-data [{:unit/name "A"}]})
    (let [reg (op! "register-subscription"
                   {:seon.store.wire/sub-id "s1"
                    :seon.store.wire/query  (pr-str '[:find ?n :where [?e :unit/name ?n]])})
          payload (:seon.store.wire/payload reg)]
      (is (true? (:seon.store.wire/ok reg)))
      (is (= "s1" (:seon.store.wire/sub-id reg)))
      (is (= #{["A"]} (set (:seon.server.reactive/rows payload)))
          "initial rows seeded from the current db")
      (testing "a matching commit emits a changed-summaries event on the fanout"
        (let [db-name (wire/ambient-db-name)
              !evs    (atom [])
              sub     (bcast/subscribe!
                       db-name
                       (fn [ev] (swap! !evs conj (:seon.store.wire/event ev))))]
          (try
            (op! "transact" {:seon.store.wire/tx-data [{:unit/name "B"}]})
            ;; the commit surfaces as a raw "tx" AND a reactive "changed-summaries"
            (let [evs (await-event !evs "changed-summaries")]
              (is (some #{"changed-summaries"} evs)
                  (str "expected a changed-summaries event; saw " (pr-str evs))))
            (finally (bcast/unsubscribe! db-name sub))))))))

(deftest unregister-subscription-stops-changed-events
  (op! "transact" {:seon.store.wire/tx-data [{:db/ident :unit2/name :db/valueType :db.type/string
                                              :db/cardinality :db.cardinality/one}]})
  (op! "register-subscription" {:seon.store.wire/sub-id "s2"
                                :seon.store.wire/query  (pr-str '[:find ?n :where [?e :unit2/name ?n]])})
  (let [un (op! "unregister-subscription" {:seon.store.wire/sub-id "s2"})
        payload (:seon.store.wire/payload un)]
    (is (true? (:seon.store.wire/ok un)))
    (is (false? (:seon.subscription/active? payload)))))
