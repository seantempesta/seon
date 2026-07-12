(ns seon.server.wire-request-id-test
  "R1 (platform / wire-side): the single `transact` op must stamp the request's
   wire id into the commit's tx-meta as `:seon.store.wire/id`, so the
   per-conn `::reactive` listener (`reactive/on-tx!`) can read it off the
   TxReport and carry it on the `changed-summaries` event for own-tx dedup
   (review issue 1 — load-bearing).

   The reactive-SIDE of this (on-tx! surfaces tx-meta's wire id on the event)
   is covered by `reactive-test/request-id-rides-the-event`. This pins the
   WIRE-SIDE: that `handle-op \"transact\"` actually puts it in tx-meta, mirroring
   the `transact-batch` path.

   It also pins the production seal: cluster conns are `:schema-flexibility :write`
   (`store.clj`), and `seed-base-schema!` installs `:seon.store.wire/id` so
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
    (let [conn (d/connect cfg)]
      (#'wire/seed-base-schema! conn)
      conn)))

(defn- capture-last-report
  "Attach a listener that records every TxReport, return the atom it writes to."
  [conn]
  (let [reports (atom [])]
    (d/listen conn ::capture (fn [r] (swap! reports conj r)))
    reports))

(defn- transact-op
  "Call wire's `handle-op` \"transact\" directly. tx-data is native data,
   wire-id is the durable idempotency identity."
  [conn tx-data wire-id]
  (wire/handle-op conn (cond-> {:seon.store.wire/op "transact"
                                :seon.store.wire/tx-data tx-data}
                         wire-id (assoc :seon.store.wire/id wire-id))))

(deftest transact-stamps-wire-id-into-tx-meta
  (testing ":read conn — wire id present → commit tx-meta carries it"
    (let [conn    (mem-conn :read)
          reports (capture-last-report conn)
          resp    (transact-op conn [{:db/id -1 :unit/name "A"}] "rid-1")]
      (is (true? (:seon.store.wire/ok resp)) "the transact succeeded")
      (is (= "rid-1" (:seon.store.wire/id resp))
          "the response echoes the wire id")
      (is (= "rid-1" (:seon.store.wire/id (:tx-meta (last @reports))))
          "the COMMIT's tx-meta carries :seon.store.wire/id — what on-tx! reads")))

  (testing ":read conn — no wire id is a protocol rejection"
    (let [conn    (mem-conn :read)
          reports (capture-last-report conn)
          resp    (transact-op conn [{:db/id -1 :unit/name "B"}] nil)]
      (is (false? (:seon.store.wire/ok resp)))
      (is (= "protocol" (:seon.store.wire/error-kind resp)))
      (is (empty? @reports)
          "a write without the idempotency identity reaches no transaction"))))

(deftest write-flexibility-accepts-wire-id-after-seed
  ;; The production path: cluster conns are :schema-flexibility :write, which
  ;; rejects un-installed attrs. seed-base-schema! installs
  ;; :seon.store.wire/id so a wire-id-bearing commit is accepted. Without
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
          "a :write conn accepts the wire-id-bearing transact (seed-base-schema! sealed it)")
      (is (= "rid-w" (:seon.store.wire/id (:tx-meta (last @reports))))
          "and the commit's tx-meta carries it on the :write path too"))))

(deftest repeated-wire-id-recovers-the-one-commit
  (let [conn     (mem-conn :read)
        reports  (capture-last-report conn)
        request  [{:db/id -1 :unit/name "only-once"}]
        first-r  (transact-op conn request "rid-recover")
        second-r (transact-op conn
                              [(array-map :unit/name "only-once" :db/id -1)]
                              "rid-recover")]
    (is (true? (:seon.store.wire/ok first-r)))
    (is (true? (:seon.store.wire/ok second-r)))
    (is (true? (:seon.store.wire/recovered? second-r))
        "the second delivery is a historical response, not another commit")
    (is (= (:seon.store.wire/basis-t first-r)
           (:seon.store.wire/basis-t second-r))
        "both replies identify the same committed transaction")
    (is (= {-1 (get (:seon.store.wire/tempids first-r) -1)}
           (:seon.store.wire/tempids second-r))
        "the durable receipt reconstructs the caller's exact tempid key and eid")
    (is (= 1 (count @reports))
        "recovery does not fire Datahike listeners a second time")
    (is (= 1
           (d/q '[:find (count ?entity) .
                  :where [?entity :unit/name "only-once"]]
                (d/db conn)))
        "the domain assertion committed exactly once")))

(deftest concurrent-repeat-serializes-to-one-commit
  (let [conn    (mem-conn :read)
        reports (capture-last-report conn)
        start   (promise)
        invoke  (fn []
                  @start
                  (transact-op conn [{:db/id "domain" :unit/name "parallel"}]
                               "rid-parallel"))
        a       (future (invoke))
        b       (future (invoke))]
    (deliver start true)
    (let [responses [@a @b]]
      (is (every? :seon.store.wire/ok responses))
      (is (= 1 (count (filter :seon.store.wire/recovered? responses)))
          "one request commits and the other recovers")
      (is (= 1 (count @reports))
          "only the committing request reaches listeners")
      (is (= 1
             (d/q '[:find (count ?entity) .
                    :where [?entity :unit/name "parallel"]]
                  (d/db conn)))))))

(deftest wire-id-reuse-with-different-data-is-rejected
  (let [conn   (mem-conn :read)
        first-r (transact-op conn [{:unit/name "first"}] "rid-reused")
        reused  (transact-op conn [{:unit/name "different"}] "rid-reused")]
    (is (true? (:seon.store.wire/ok first-r)))
    (is (false? (:seon.store.wire/ok reused)))
    (is (= "wire-id-conflict" (:seon.store.wire/error-kind reused)))
    (is (nil? (d/q '[:find ?entity .
                     :where [?entity :unit/name "different"]]
                   (d/db conn)))
        "the mismatched retry writes no domain datoms")))

(deftest base-schema-seed-does-not-transact-when-converged
  (let [conn   (mem-conn :write)
        before (:max-tx (d/db conn))]
    (#'wire/seed-base-schema! conn)
    (is (= before (:max-tx (d/db conn)))
        "re-seeding an exact wire schema is a structural no-op")))
