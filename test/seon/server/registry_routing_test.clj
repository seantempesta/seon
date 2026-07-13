(ns seon.server.registry-routing-test
  "Connection routing + on-ensure-db extension-point tests for
   `seon.server.registry`.

   Covers the multi-cluster writer seam:
   - `resolve-conn` routes a request to the right conn by db-name across MANY
     clusters in one registry; unknown → typed not-found.
   - `register-on-ensure-db-hook!` fires once per newly-opened conn, with a
     real datahike conn + db-name, and a `d/listen!` registered inside the hook
     receives the full TxReport on commit.
   - `ensure-db!` idempotency (no re-open, no re-fire of hooks).

   All `:memory` backend. Each test snapshots/restores the registry + the
   on-ensure-db hook vector so tests are isolated and don't leak conns."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [seon.server.registry :as reg]))

;;; --- Fixture ---------------------------------------------------------------

(defn ^:private isolate [t]
  ;; Snapshot BEFORE resetting hooks — restore-registry! puts the live
  ;; hooks (::raw-broadcast, ::embed, ...) back from the snapshot, so
  ;; running this suite in a live JVM no longer strands an empty hook
  ;; vector (one of the 2026-06-10 hook-loss vectors).
  (let [{::reg/keys [snapshot]} (reg/snapshot-registry {})]
    (reg/reset-on-ensure-db-hooks!)
    (try
      (t)
      (finally
        (reg/restore-registry! {::reg/snapshot snapshot})))))

(use-fixtures :each isolate)

(defn- mem [n]
  (reg/ensure-db! {::reg/db-name n ::reg/backend :memory}))

;;; --- resolve-conn across many clusters -------------------------------------

(deftest resolve-conn-by-db-name-across-many-clusters
  (testing "20 clusters in one registry; resolve-conn by db-name hits exactly
            the conn that ensure-db! returned for that name"
    (let [names   (mapv #(keyword "cluster" (str "c" %)) (range 20))
          name->c (into {} (map (fn [n] [n (::reg/conn (mem n))])) names)]
      (doseq [n names]
        (let [res (reg/resolve-conn {::reg/db-name n})]
          (is (identical? (name->c n) (::reg/conn res))
              (str "db-name " n " resolves to its own conn"))
          (is (= n (::reg/db-name res)))))
      ;; every conn is distinct (no cross-wiring)
      (is (= 20 (count (into #{} (map second) name->c)))
          "20 clusters => 20 distinct conns")
      (doseq [n names] (reg/remove-db! {::reg/db-name n})))))

(deftest resolve-conn-unknown-db-is-typed-not-found
  (testing "an unknown db-name yields a typed not-found value with no conn"
    (mem :cluster/present)
    (let [d (reg/resolve-conn {::reg/db-name :cluster/absent})]
      (is (= "not-found" (::reg/error-kind d)))
      (is (nil? (::reg/conn d)))
      (is (string? (::reg/error d))))
    (reg/remove-db! {::reg/db-name :cluster/present})))

(deftest resolve-conn-absent-keys-is-unresolved
  (testing "no db-name → ::unresolved? so the caller can use its ambient conn"
    (let [res (reg/resolve-conn {})]
      (is (true? (::reg/unresolved? res)))
      (is (nil? (::reg/conn res)))
      (is (nil? (::reg/error-kind res))))))

;;; --- on-ensure-db extension point ------------------------------------------

(deftest on-ensure-db-hook-fires-once-with-conn-and-name
  (testing "a registered hook runs on ensure-db with the real conn + db-name,
            exactly once (idempotent re-ensure does NOT re-fire)"
    (let [calls (atom [])]
      (reg/register-on-ensure-db-hook!
       {::reg/hook-key ::fires-once
        ::reg/hook-fn (fn [conn db-name]
                        (swap! calls conj [db-name (some? conn) (some? @conn)]))})
      (let [c1 (::reg/conn (mem :cluster/hook))
            c2 (::reg/conn (mem :cluster/hook))]   ; idempotent re-ensure
        (is (identical? c1 c2) "re-ensure returns same conn")
        (is (= 1 (count @calls)) "hook fired exactly once across two ensures")
        (is (= [:cluster/hook true true] (first @calls))
            "hook saw the db-name and a live, deref-able conn"))
      (reg/remove-db! {::reg/db-name :cluster/hook}))))

(deftest on-ensure-db-multiple-hooks-all-fire-in-order
  (testing "multiple registered hooks all run, in first-registration order"
    (let [order (atom [])]
      (reg/register-on-ensure-db-hook!
       {::reg/hook-key ::first
        ::reg/hook-fn (fn [_ _] (swap! order conj :first))})
      (reg/register-on-ensure-db-hook!
       {::reg/hook-key ::second
        ::reg/hook-fn (fn [_ _] (swap! order conj :second))})
      (mem :cluster/multi)
      (is (= [:first :second] @order))
      (reg/remove-db! {::reg/db-name :cluster/multi}))))

(deftest on-ensure-db-hook-reregistration-replaces-by-key
  (testing "re-registering the same ::hook-key REPLACES the fn in place —
            no duplicate fire, original position kept. This is the reload
            self-heal contract: wire/boot re-register at every ns load with
            no defonce guard, and the hook set never accumulates copies."
    (let [order (atom [])]
      (reg/register-on-ensure-db-hook!
       {::reg/hook-key ::replaced
        ::reg/hook-fn (fn [_ _] (swap! order conj :stale))})
      (reg/register-on-ensure-db-hook!
       {::reg/hook-key ::tail
        ::reg/hook-fn (fn [_ _] (swap! order conj :tail))})
      ;; simulate the ns reload re-running its registration form
      (let [{::reg/keys [hook-count]}
            (reg/register-on-ensure-db-hook!
             {::reg/hook-key ::replaced
              ::reg/hook-fn (fn [_ _] (swap! order conj :fresh))})]
        (is (= 2 hook-count) "re-registration did not grow the hook set"))
      (mem :cluster/rereg)
      (is (= [:fresh :tail] @order)
          "replaced fn fired once, in its original position; stale fn never fired")
      (reg/remove-db! {::reg/db-name :cluster/rereg}))))

(deftest on-ensure-db-hook-can-install-a-listener-that-gets-the-tx-report
  (testing "the canonical use: a hook registers a d/listen! that receives the
            full synchronous TxReport on every commit (the ::raw-broadcast
            seam)"
    (let [reports (atom [])]
      (reg/register-on-ensure-db-hook!
       {::reg/hook-key ::test-listener
        ::reg/hook-fn (fn [conn _db-name]
                        (d/listen conn ::test-listener
                                  (fn [report] (swap! reports conj report))))})
      (let [conn (::reg/conn (mem :cluster/listen))]
        ;; real multi-clause schema + data
        (d/transact conn [{:db/ident :person/name
                           :db/valueType :db.type/string
                           :db/cardinality :db.cardinality/one}
                          {:db/ident :person/age
                           :db/valueType :db.type/long
                           :db/cardinality :db.cardinality/one}])
        (d/transact conn [{:person/name "alice" :person/age 33}
                          {:person/name "bob"   :person/age 41}])
        ;; the listener fires synchronously inside d/transact (verified in REPL)
        (is (= 2 (count @reports)) "listener got both commits")
        (let [last-report (last @reports)]
          ;; 4 person attr datoms + 1 :db/txInstant tx datom (keep-history?).
          (is (= 4 (count (filter (fn [d] (#{:person/name :person/age} (:a d)))
                                  (:tx-data last-report))))
              "2 entities x (name+age) person datoms in the second commit")
          (is (pos-int? (:max-tx (:db-after last-report)))
              "report carries the post-commit db with a basis-t")
          (is (= #{"alice" "bob"}
                 (set (d/q '[:find [?n ...] :where [?e :person/name ?n]]
                           (:db-after last-report))))
              "report's db-after answers a real multi-clause query")))
      (reg/remove-db! {::reg/db-name :cluster/listen}))))

;;; --- idempotency -----------------------------------------------------------

(deftest ensure-db-idempotent-no-reseed
  (testing "second ensure-db! returns the identical conn and does not reset
            engine-visible state"
    (let [c1 (::reg/conn (mem :cluster/idem))]
      (d/transact c1 [{:db/ident :k/v :db/valueType :db.type/string
                       :db/cardinality :db.cardinality/one}])
      (d/transact c1 [{:k/v "kept"}])
      (let [c2 (::reg/conn (mem :cluster/idem))]
        (is (identical? c1 c2))
        (is (= "kept" (d/q '[:find ?v . :where [_ :k/v ?v]] @c2))
            "idempotent re-ensure preserves committed data")))
    (reg/remove-db! {::reg/db-name :cluster/idem})))
