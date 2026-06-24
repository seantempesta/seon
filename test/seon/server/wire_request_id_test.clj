(ns seon.server.wire-request-id-test
  "R1 (platform / wire-side): the single `transact` op must stamp the request's
   write-id into the commit's tx-meta as `:seon.store.wire/write-id`, so the
   per-conn `::reactive` listener (`reactive/on-tx!`) can read it off the
   TxReport and carry it on the `changed-summaries` event for own-tx dedup
   (review issue 1 — load-bearing).

   The reactive-SIDE of this (on-tx! surfaces tx-meta's write-id on the event)
   is covered by `reactive-test/request-id-rides-the-event`. This pins the
   WIRE-SIDE: that `handle-op \"transact\"` actually puts it in tx-meta, mirroring
   the `transact-batch` path.

   It also pins the production seal: cluster conns are `:schema-flexibility :write`
   (`store.clj`), and `seed-base-schema!` installs `:seon.store.wire/write-id` so
   such a commit is accepted. We drive `wire/handle-op` directly (in-memory
   conn), the same harness style as `wire_props_test`."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.server.wire :as wire]))

(defn- mem-conn
  "Fresh :memory conn. `flex` is :read or :write."
  [flex]
  (let [cfg {:store {:backend :memory :id (java.util.UUID/randomUUID)}
             :schema-flexibility flex :keep-history? true}]
    (d/create-database cfg)
    (d/connect cfg)))

(defn- capture-last-report
  "Attach a listener that records every TxReport, return the atom it writes to."
  [conn]
  (let [reports (atom [])]
    (d/listen conn ::capture (fn [r] (swap! reports conj r)))
    reports))

(defn- transact-op
  "Call wire's `handle-op` \"transact\" directly. tx-data is native data,
   write-id a control field."
  [conn tx-data write-id]
  (wire/handle-op conn (cond-> {:seon.store.wire/op "transact"
                                :seon.store.wire/tx-data tx-data}
                         write-id (assoc :seon.store.wire/write-id write-id))))

(deftest transact-stamps-write-id-into-tx-meta
  (testing ":read conn — write-id present → commit tx-meta carries it"
    (let [conn    (mem-conn :read)
          reports (capture-last-report conn)
          resp    (transact-op conn [{:db/id -1 :unit/name "A"}] "rid-1")]
      (is (true? (:seon.store.wire/ok resp)) "the transact succeeded")
      (is (= "rid-1" (:seon.store.wire/write-id resp))
          "the response echoes the write-id")
      (is (= "rid-1" (:seon.store.wire/write-id (:tx-meta (last @reports))))
          "the COMMIT's tx-meta carries :seon.store.wire/write-id — what on-tx! reads")))

  (testing ":read conn — no write-id → tx-meta has no :seon.store.wire/write-id"
    (let [conn    (mem-conn :read)
          reports (capture-last-report conn)
          resp    (transact-op conn [{:db/id -1 :unit/name "B"}] nil)]
      (is (true? (:seon.store.wire/ok resp)))
      (is (nil? (:seon.store.wire/write-id resp)) "no write-id echoed")
      (is (nil? (:seon.store.wire/write-id (:tx-meta (last @reports))))
          "absent write-id is not stamped (no empty-string leakage either)"))))

(deftest write-flexibility-accepts-write-id-after-seed
  ;; The production path: cluster conns are :schema-flexibility :write, which
  ;; rejects un-installed attrs. seed-base-schema! installs
  ;; :seon.store.wire/write-id so a write-id-bearing commit is accepted. Without
  ;; it, this transact would throw — this pins the seal that makes R1 work on a
  ;; real cluster conn.
  (let [conn (mem-conn :write)]
    (#'wire/seed-base-schema! conn)
    ;; install the domain attr we transact, so :write doesn't reject *it*
    (d/transact conn [{:db/ident :unit/name
                       :db/valueType :db.type/string
                       :db/cardinality :db.cardinality/one}])
    (let [reports (capture-last-report conn)
          resp    (transact-op conn [{:unit/name "A"}] "rid-w")]
      (is (true? (:seon.store.wire/ok resp))
          "a :write conn accepts the write-id-bearing transact (seed-base-schema! sealed it)")
      (is (= "rid-w" (:seon.store.wire/write-id (:tx-meta (last @reports))))
          "and the commit's tx-meta carries it on the :write path too"))))
