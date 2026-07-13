(ns seon.server.protocol-extensions-test
  "Integration tests for the retained remote read operations:
     - schema (read schema map)
     - q / pull with optional :basis-t for snapshot reads

   Each test uses an isolated in-process memory writer."
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
  ;; Include a component-ref attribute so schema reads cover reference metadata.
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

;; ---------- schema ----------

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
