(ns seon.server.registry-routing-test
  "P1 conn-resolution + on-ensure-db extension-point tests for
   `seon.server.registry`.

   Covers the multi-cluster seam the reactive engine plugs into:
   - `resolve-conn` routes a request to the right conn by agent-id or db-name
     across MANY clusters in one registry; unknown → typed not-found; the
     `register-agent!` agent→cluster binding.
   - `register-on-ensure-db-hook!` fires once per newly-opened conn, with a
     real datahike conn + db-name, and a `d/listen!` registered inside the hook
     receives the full TxReport on commit.
   - `ensure-db!` idempotency (no re-open, no re-fire of hooks).

   All `:memory` backend. Each test snapshots/restores the registry + agents +
   the on-ensure-db hook vector so tests are isolated and don't leak conns."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [seon.server.registry :as reg]))

;;; --- Fixture ---------------------------------------------------------------

(defn ^:private isolate [t]
  ;; Snapshot BEFORE resetting hooks — restore-registry! puts the live
  ;; hooks (::raw-broadcast, ::reactive, ...) back from the snapshot, so
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

(deftest resolve-conn-by-agent-id
  (testing "agent-id → db-name → conn; many agents fanned across many clusters"
    (let [names    (mapv #(keyword "cluster" (str "ac" %)) (range 12))
          name->c  (into {} (map (fn [n] [n (::reg/conn (mem n))])) names)
          ;; 3 agents per cluster
          agents   (for [n names i (range 3)]
                     [(str "agent-" (name n) "-" i) n])]
      (doseq [[aid n] agents]
        (reg/register-agent! {:seon.agent/id aid ::reg/db-name n}))
      (testing "each agent resolves to its cluster's conn"
        (doseq [[aid n] agents]
          (let [res (reg/resolve-conn {:seon.agent/id aid})]
            (is (identical? (name->c n) (::reg/conn res)))
            (is (= n (::reg/db-name res))))))
      (testing "list-agents reflects every binding"
        (is (= (count agents)
               (count (::reg/agents (reg/list-agents {}))))))
      (doseq [n names] (reg/remove-db! {::reg/db-name n})))))

(deftest resolve-conn-unknown-is-typed-not-found
  (testing "unknown agent-id and unknown db-name both yield not-found, no conn"
    (mem :cluster/present)
    (let [a (reg/resolve-conn {:seon.agent/id "nope-not-registered"})
          d (reg/resolve-conn {::reg/db-name :cluster/absent})]
      (is (= "not-found" (::reg/error-kind a)))
      (is (nil? (::reg/conn a)))
      (is (string? (::reg/error a)))
      (is (= "not-found" (::reg/error-kind d)))
      (is (nil? (::reg/conn d))))
    (reg/remove-db! {::reg/db-name :cluster/present})))

(deftest resolve-conn-absent-keys-is-unresolved
  (testing "neither agent-id nor db-name → ::unresolved? (caller falls back to
            its ambient single-DB conn — back-compat)"
    (let [res (reg/resolve-conn {})]
      (is (true? (::reg/unresolved? res)))
      (is (nil? (::reg/conn res)))
      (is (nil? (::reg/error-kind res))))))

(deftest agent-id-precedence-over-db-name
  (testing "when both present, agent-id wins the resolution"
    (mem :cluster/x)
    (mem :cluster/y)
    (reg/register-agent! {:seon.agent/id "ag-x" ::reg/db-name :cluster/x})
    (let [res (reg/resolve-conn {:seon.agent/id "ag-x" ::reg/db-name :cluster/y})]
      (is (= :cluster/x (::reg/db-name res))
          "agent-id resolution takes precedence over the explicit db-name"))
    (reg/remove-db! {::reg/db-name :cluster/x})
    (reg/remove-db! {::reg/db-name :cluster/y})))

(deftest register-agent-requires-registered-db
  (testing "binding an agent to an unregistered cluster throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (reg/register-agent! {:seon.agent/id "orphan"
                                       ::reg/db-name :cluster/never})))))

(deftest remove-db-drops-its-agent-bindings
  (testing "removing a cluster removes the agents that pointed at it"
    (mem :cluster/z)
    (reg/register-agent! {:seon.agent/id "z1" ::reg/db-name :cluster/z})
    (reg/register-agent! {:seon.agent/id "z2" ::reg/db-name :cluster/z})
    (is (= 2 (count (::reg/agents (reg/list-agents {})))))
    (reg/remove-db! {::reg/db-name :cluster/z})
    (is (empty? (::reg/agents (reg/list-agents {}))))
    (is (= "not-found" (::reg/error-kind (reg/resolve-conn {:seon.agent/id "z1"}))))))

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
            full synchronous TxReport on every commit (the ::raw-broadcast /
            ::reactive seam)"
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
