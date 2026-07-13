(ns seon.server.registry-routing-test
  "Connection routing and explicit initialization tests for the writer registry.

   Covers the multi-cluster writer seam:
   - `resolve-conn` routes a request to the right conn by db-name across MANY
     clusters in one registry; unknown → typed not-found.
   - the passed initializer runs once per newly-opened connection and may
     install a Datahike listener;
   - initialization failure leaves no published entry; and
   - `ensure-db!` idempotency does not reopen or reinitialize.

   All `:memory` backend. Each test snapshots/restores opaque connection
   entries so tests are isolated and don't leak conns."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [seon.server.registry :as reg]))

;;; --- Fixture ---------------------------------------------------------------

(defn ^:private isolate [t]
  (let [{::reg/keys [snapshot]} (reg/snapshot-registry {})]
    (try
      (t)
      (finally
        (reg/restore-registry! {::reg/snapshot snapshot})))))

(use-fixtures :each isolate)

(defn- mem [n]
  (reg/ensure-db! {::reg/db-name n
                   ::reg/backend :memory
                   ::reg/initialize-connection! (fn [_conn _db-name] nil)}))

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

;;; --- explicit connection initialization -----------------------------------

(deftest initializer-runs-once-with-the-live-connection
  (let [calls (atom [])
        initialize! (fn [conn db-name]
                      (swap! calls conj [db-name (some? @conn)]))
        request {::reg/db-name :cluster/initialized
                 ::reg/backend :memory
                 ::reg/initialize-connection! initialize!}
        first-entry (reg/ensure-db! request)
        second-entry (reg/ensure-db! request)]
    (is (identical? (::reg/conn first-entry) (::reg/conn second-entry)))
    (is (= [[:cluster/initialized true]] @calls)
        "an idempotent ensure does not repeat initialization")
    (reg/remove-db! {::reg/db-name :cluster/initialized})))

(deftest initializer-can-install-a-datahike-listener
  (let [reports (atom [])
        initialize! (fn [conn _db-name]
                      (d/listen conn ::test-listener
                                (fn [report] (swap! reports conj report))))
        conn (::reg/conn
              (reg/ensure-db!
               {::reg/db-name :cluster/listen
                ::reg/backend :memory
                ::reg/initialize-connection! initialize!}))]
    (d/transact conn [{:db/ident :person/name
                       :db/valueType :db.type/string
                       :db/cardinality :db.cardinality/one}])
    (d/transact conn [{:person/name "alice"} {:person/name "bob"}])
    (is (= 2 (count @reports)))
    (is (= #{"alice" "bob"}
           (set (d/q '[:find [?name ...] :where [?e :person/name ?name]]
                     (:db-after (last @reports))))))
    (reg/remove-db! {::reg/db-name :cluster/listen})))

(deftest failed-initialization-never-publishes-a-broken-entry
  (let [db-name :cluster/init-failure
        error (try
                (reg/ensure-db!
                 {::reg/db-name db-name
                  ::reg/backend :memory
                  ::reg/initialize-connection!
                  (fn [_conn _db-name] (throw (ex-info "boom" {})))})
                nil
                (catch clojure.lang.ExceptionInfo caught caught))]
    (is (= "boom" (.getMessage error)))
    (is (nil? (::reg/conn (reg/get-conn {::reg/db-name db-name}))))
    (let [entry (mem db-name)]
      (is (some? (::reg/conn entry))
          "a later healthy initializer can retry the open"))
    (reg/remove-db! {::reg/db-name db-name})))

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
