(ns seon.server.protocol-extensions-test
  "Integration tests for Phase B.1 protocol extensions:
     - entity-pull (eager `d/entity` replacement)
     - pull-many   (batched pull)
     - schema      (read schema map)
     - reverse-schema (read rschema)
     - db-filter + q-filtered + filter-release (Datalog-predicate filtered db)
     - q / pull with optional :basis-t for snapshot reads

   Each test spawns its own JVM writer subprocess (in-memory backend) and
   tears it down. Shape mirrors `protocol_integration_test.clj`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [seon.server.test-util :as tu]))

(set! *warn-on-reflection* true)

;; ---------- Fixture (shared in-process writer, see seon.server.test-util) ----------

(use-fixtures :each tu/with-fresh-writer)

;; ---------- Helpers ----------

(defn- req! [op extra] (tu/req! op extra))

(defn- result-of [resp]
  (:seon.store.wire/result resp))

(defn- install-team-schema! []
  ;; Schema with one component-ref attr to exercise the entity-pull recursion.
  (req! "transact"
        {:seon.store.wire/tx-data
         [{:db/ident :team/name
           :db/valueType :db.type/string
           :db/cardinality :db.cardinality/one
           :db/unique :db.unique/identity}
          {:db/ident :team/members
           :db/valueType :db.type/ref
           :db/cardinality :db.cardinality/many
           :db/isComponent true}
          {:db/ident :person/name
           :db/valueType :db.type/string
           :db/cardinality :db.cardinality/one
           :db/unique :db.unique/identity}
          {:db/ident :person/age
           :db/valueType :db.type/long
           :db/cardinality :db.cardinality/one}]}))

(defn- seed! []
  (install-team-schema!)
  (req! "transact"
        {:seon.store.wire/tx-data
         [{:team/name "alpha"
           :team/members [{:person/name "alice" :person/age 33}
                          {:person/name "bob"   :person/age 41}]}]}))

;; ---------- entity-pull ----------

(deftest test-entity-pull-by-eid
  (testing "entity-pull with a numeric eid returns the realized entity map"
    (seed!)
    (let [;; look up alice's eid via q (find .)
          q-resp (req! "q" {:seon.store.wire/query '[:find ?e . :where [?e :person/name "alice"]]
                            :seon.store.wire/args  []})
          alice-eid (result-of q-resp)
          r (req! "entity-pull" {:seon.store.wire/ref alice-eid})]
      (is (= true (:seon.store.wire/ok r)))
      (let [m (result-of r)]
        (is (map? m))
        (is (= "alice" (get m :person/name)))
        (is (= 33 (get m :person/age)))))))

(deftest test-entity-pull-by-lookup-ref
  (testing "entity-pull accepts a native lookup-ref for `ref`"
    (seed!)
    (let [r (req! "entity-pull" {:seon.store.wire/ref [:person/name "bob"]})]
      (is (= true (:seon.store.wire/ok r)))
      (let [m (result-of r)]
        (is (= "bob" (get m :person/name)))
        (is (= 41 (get m :person/age)))))))

(deftest test-entity-pull-expands-component-refs
  (testing "entity-pull eagerly realizes component refs to depth 1.
            Matches the audit's V0 usage at agent.cljs:493 — `(:seon.agent/sessions a)`
            traversal works because the overlay receives a vector of realized maps."
    (seed!)
    (let [r (req! "entity-pull" {:seon.store.wire/ref [:team/name "alpha"]})]
      (is (= true (:seon.store.wire/ok r)))
      (let [m (result-of r)
            members (get m :team/members)]
        (is (= "alpha" (get m :team/name)))
        (is (vector? members) "members is a vector of pulled component maps")
        (is (= 2 (count members)))
        (is (every? map? members) "each member is a realized map, not an eid")
        (let [names (set (map #(get % :person/name) members))]
          (is (= #{"alice" "bob"} names)))))))

(deftest test-entity-pull-not-found
  (testing "entity-pull on a missing lookup-ref returns nil result without erroring"
    (install-team-schema!)
    (let [r (req! "entity-pull" {:seon.store.wire/ref [:person/name "ghost"]})]
      ;; Datahike returns nil for a missing entity pull; ok=true, result=nil.
      (is (= true (:seon.store.wire/ok r)))
      (is (nil? (result-of r))))))

;; ---------- pull-many ----------

(deftest test-pull-many-by-lookup-refs
  (testing "pull-many returns a vector of pulled entities preserving input order"
    (seed!)
    (let [r (req! "pull-many"
                  {:seon.store.wire/selector [:person/name :person/age]
                   :seon.store.wire/eids     [[:person/name "alice"]
                                              [:person/name "bob"]]})]
      (is (= true (:seon.store.wire/ok r)))
      (let [xs (result-of r)]
        (is (vector? xs))
        (is (= 2 (count xs)))
        (is (= "alice" (get (first xs) :person/name)))
        (is (= "bob"   (get (second xs) :person/name)))))))

;; ---------- schema / reverse-schema ----------

(deftest test-schema-read
  (testing "schema op returns the attr -> attr-schema map. Caller-visible attrs include the ones we installed."
    (install-team-schema!)
    (let [r (req! "schema" {})]
      (is (= true (:seon.store.wire/ok r)))
      (let [s (result-of r)]
        (is (map? s))
        ;; Keys are now native keywords (Transit preserves the type).
        (let [idents (set (keys s))]
          (is (contains? idents :person/name))
          (is (contains? idents :person/age))
          (is (contains? idents :team/name))
          (is (contains? idents :team/members)))))))

(deftest test-reverse-schema-read
  (testing "reverse-schema op returns the rschema indexed by property"
    (install-team-schema!)
    (let [r (req! "reverse-schema" {})]
      (is (= true (:seon.store.wire/ok r)))
      (let [rs (result-of r)]
        (is (map? rs))
        ;; rschema keys are native property keywords (Transit-preserved).
        (let [props (set (keys rs))]
          (is (some #(and (keyword? %) (re-find #"unique" (str %))) props)
              (str "expected a unique-* keyword key, got " (pr-str props))))))))

;; ---------- db-filter / q-filtered / filter-release ----------

(deftest test-db-filter-then-query
  (testing "db-filter accepts a predicate query returning eids; q-filtered
            against the resulting handle only sees the kept entities."
    (seed!)
    ;; Filter: keep only people whose age >= 40 (just bob).
    (let [r1 (req! "db-filter"
                   {:seon.store.wire/pred-query '[:find ?e :where [?e :person/age ?a] [(>= ?a 40)]]
                    :seon.store.wire/args       []})]
      (is (= true (:seon.store.wire/ok r1)))
      (let [handle (:seon.store.wire/handle r1)]
        (is (integer? handle))
        (is (= 1 (:seon.store.wire/kept r1)) "exactly one person matches the predicate")
        (let [r2 (req! "q-filtered"
                       {:seon.store.wire/handle handle
                        :seon.store.wire/query  '[:find ?n :where [?e :person/name ?n]]
                        :seon.store.wire/args   []})]
          (is (= true (:seon.store.wire/ok r2)))
          (let [names (set (map first (result-of r2)))]
            (is (= #{"bob"} names) "filtered db only exposes bob")))
        ;; Release is idempotent
        (let [r3 (req! "filter-release" {:seon.store.wire/handle handle})]
          (is (= true (:seon.store.wire/ok r3)))
          (is (= true (:seon.store.wire/released r3))))
        ;; After release, the handle is gone
        (let [r4 (req! "q-filtered"
                       {:seon.store.wire/handle handle
                        :seon.store.wire/query  '[:find ?n :where [?e :person/name ?n]]
                        :seon.store.wire/args   []})]
          (is (= false (:seon.store.wire/ok r4)))
          (is (= "not-found" (:seon.store.wire/error-kind r4))))))))

;; ---------- q / pull with :basis-t ----------

(deftest test-q-with-basis-t-as-of-snapshot
  (testing "q with explicit :basis-t reads against an older snapshot.
            The audit's warnings composer (agent.cljs:1029) needs this for
            consistent multi-query reads against one tx event."
    (install-team-schema!)
    (let [r1 (req! "transact" {:seon.store.wire/tx-data [{:person/name "alice" :person/age 33}]})
          bt1 (:seon.store.wire/basis-t r1)
          _   (req! "transact" {:seon.store.wire/tx-data [{:person/name "bob"   :person/age 41}]})
          ;; Query at the post-alice basis: only alice should be visible.
          r-old (req! "q" {:seon.store.wire/query '[:find ?n :where [?e :person/name ?n]]
                           :seon.store.wire/args  []
                           :seon.store.wire/basis-t bt1})
          r-now (req! "q" {:seon.store.wire/query '[:find ?n :where [?e :person/name ?n]]
                           :seon.store.wire/args  []})]
      (is (= true (:seon.store.wire/ok r-old)))
      (is (= true (:seon.store.wire/ok r-now)))
      (let [old-names (set (map first (result-of r-old)))
            now-names (set (map first (result-of r-now)))]
        (is (= #{"alice"} old-names) "snapshot at bt1 only sees alice")
        (is (= #{"alice" "bob"} now-names) "current snapshot sees both")))))

(deftest test-pull-with-basis-t
  (testing "pull with explicit :basis-t pulls from the snapshot"
    (install-team-schema!)
    (let [r1 (req! "transact" {:seon.store.wire/tx-data [{:person/name "alice" :person/age 33}]})
          bt1 (:seon.store.wire/basis-t r1)
          _   (req! "transact" {:seon.store.wire/tx-data [[:db/add [:person/name "alice"] :person/age 99]]})
          r-old (req! "pull" {:seon.store.wire/selector [:person/name :person/age]
                              :seon.store.wire/eid      [:person/name "alice"]
                              :seon.store.wire/basis-t  bt1})
          r-now (req! "pull" {:seon.store.wire/selector [:person/name :person/age]
                              :seon.store.wire/eid      [:person/name "alice"]})]
      (is (= 33 (get (result-of r-old) :person/age)) "old snapshot age")
      (is (= 99 (get (result-of r-now) :person/age)) "new age"))))
