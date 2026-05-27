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
            [seon.server.client :as client]
            [seon.server.transit :as transit])
  (:import [java.io File]))

(set! *warn-on-reflection* true)

;; ---------- Fixture (copy of the pattern in the sibling test ns) ----------

(def ^:dynamic *ctx* nil)

(defn- unique-sock [prefix]
  (str "/tmp/seon-poc-test-" prefix "-" (System/nanoTime) ".sock"))

(defn- writer-ready? [path]
  (try (with-open [ch (client/connect path)] (.isConnected ch))
       (catch Throwable _ false)))

(defn- wait-for-socket! [path timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (writer-ready? path) :ok
        (> (System/currentTimeMillis) deadline)
        (throw (ex-info "writer never came up" {:path path}))
        :else (do (Thread/sleep 200) (recur))))))

(defn- spawn-writer! []
  (let [req-sock (unique-sock "req")
        pub-sock (unique-sock "pub")
        cmd ["clojure" "-M:writer"
             "--backend" "memory"
             "--req-sock" req-sock
             "--pub-sock" pub-sock]
        pb (doto (ProcessBuilder. ^java.util.List cmd)
             (.redirectErrorStream true)
             (.redirectOutput (java.lang.ProcessBuilder$Redirect/to
                                (File. (str "logs/writer-test-" (System/nanoTime) ".log")))))
        _ (.mkdirs (File. "logs"))
        proc (.start pb)]
    (wait-for-socket! req-sock 60000)
    (wait-for-socket! pub-sock 60000)
    {:req-sock req-sock :pub-sock pub-sock :process proc}))

(defn- teardown-writer! [{:keys [^Process process req-sock pub-sock]}]
  (try (.destroy process) (catch Throwable _))
  (try (.waitFor process) (catch Throwable _))
  (try (.delete (File. ^String req-sock)) (catch Throwable _))
  (try (.delete (File. ^String pub-sock)) (catch Throwable _)))

(defn- with-fresh-writer [tfn]
  (let [ctx (spawn-writer!)]
    (try (binding [*ctx* ctx] (tfn))
         (finally (teardown-writer! ctx)))))

(use-fixtures :each with-fresh-writer)

;; ---------- Helpers ----------

(defn- req! [op extra]
  (with-open [ch (client/connect (:req-sock *ctx*))]
    (client/call! ch (merge {"op" op} extra))))

(defn- result-of [resp]
  (transit/read-str (get resp "result")))

(defn- install-team-schema! []
  ;; Schema with one component-ref attr to exercise the entity-pull recursion.
  (req! "transact"
        {"tx-data"
         "[{:db/ident :team/name
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
            :db/cardinality :db.cardinality/one}]"}))

(defn- seed! []
  (install-team-schema!)
  (req! "transact"
        {"tx-data"
         "[{:team/name \"alpha\"
            :team/members [{:person/name \"alice\" :person/age 33}
                           {:person/name \"bob\"   :person/age 41}]}]"}))

;; ---------- entity-pull ----------

(deftest test-entity-pull-by-eid
  (testing "entity-pull with a numeric eid returns the realized entity map"
    (seed!)
    (let [;; look up alice's eid via q (find .)
          q-resp (req! "q" {"query" "[:find ?e . :where [?e :person/name \"alice\"]]"
                            "args"  []})
          alice-eid (result-of q-resp)
          r (req! "entity-pull" {"ref" (str alice-eid)})]
      (is (= true (get r "ok")))
      (let [m (result-of r)]
        (is (map? m))
        (is (= "alice" (get m :person/name)))
        (is (= 33 (get m :person/age)))))))

(deftest test-entity-pull-by-lookup-ref
  (testing "entity-pull accepts an EDN lookup-ref string for `ref`"
    (seed!)
    (let [r (req! "entity-pull" {"ref" "[:person/name \"bob\"]"})]
      (is (= true (get r "ok")))
      (let [m (result-of r)]
        (is (= "bob" (get m :person/name)))
        (is (= 41 (get m :person/age)))))))

(deftest test-entity-pull-expands-component-refs
  (testing "entity-pull eagerly realizes component refs to depth 1.
            Matches the audit's V0 usage at agent.cljs:493 — `(:seon.agent/sessions a)`
            traversal works because the overlay receives a vector of realized maps."
    (seed!)
    (let [r (req! "entity-pull" {"ref" "[:team/name \"alpha\"]"})]
      (is (= true (get r "ok")))
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
    (let [r (req! "entity-pull" {"ref" "[:person/name \"ghost\"]"})]
      ;; Datahike returns nil for a missing entity pull; ok=true, result=nil.
      (is (= true (get r "ok")))
      (is (nil? (result-of r))))))

;; ---------- pull-many ----------

(deftest test-pull-many-by-lookup-refs
  (testing "pull-many returns a vector of pulled entities preserving input order"
    (seed!)
    (let [r (req! "pull-many"
                  {"selector" "[:person/name :person/age]"
                   "eids"     ["[:person/name \"alice\"]"
                               "[:person/name \"bob\"]"]})]
      (is (= true (get r "ok")))
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
      (is (= true (get r "ok")))
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
      (is (= true (get r "ok")))
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
                   {"pred-query" "[:find ?e :where [?e :person/age ?a] [(>= ?a 40)]]"
                    "args"       []})]
      (is (= true (get r1 "ok")))
      (let [handle (get r1 "handle")]
        (is (integer? handle))
        (is (= 1 (get r1 "kept")) "exactly one person matches the predicate")
        (let [r2 (req! "q-filtered"
                       {"handle" handle
                        "query"  "[:find ?n :where [?e :person/name ?n]]"
                        "args"   []})]
          (is (= true (get r2 "ok")))
          (let [names (set (map first (result-of r2)))]
            (is (= #{"bob"} names) "filtered db only exposes bob")))
        ;; Release is idempotent
        (let [r3 (req! "filter-release" {"handle" handle})]
          (is (= true (get r3 "ok")))
          (is (= true (get r3 "released"))))
        ;; After release, the handle is gone
        (let [r4 (req! "q-filtered"
                       {"handle" handle
                        "query"  "[:find ?n :where [?e :person/name ?n]]"
                        "args"   []})]
          (is (= false (get r4 "ok")))
          (is (= "not-found" (get r4 "error-kind"))))))))

;; ---------- q / pull with :basis-t ----------

(deftest test-q-with-basis-t-as-of-snapshot
  (testing "q with explicit :basis-t reads against an older snapshot.
            The audit's warnings composer (agent.cljs:1029) needs this for
            consistent multi-query reads against one tx event."
    (install-team-schema!)
    (let [r1 (req! "transact" {"tx-data" "[{:person/name \"alice\" :person/age 33}]"})
          bt1 (get r1 "basis-t")
          _   (req! "transact" {"tx-data" "[{:person/name \"bob\"   :person/age 41}]"})
          ;; Query at the post-alice basis: only alice should be visible.
          r-old (req! "q" {"query" "[:find ?n :where [?e :person/name ?n]]"
                           "args"  []
                           "basis-t" bt1})
          r-now (req! "q" {"query" "[:find ?n :where [?e :person/name ?n]]"
                           "args"  []})]
      (is (= true (get r-old "ok")))
      (is (= true (get r-now "ok")))
      (let [old-names (set (map first (result-of r-old)))
            now-names (set (map first (result-of r-now)))]
        (is (= #{"alice"} old-names) "snapshot at bt1 only sees alice")
        (is (= #{"alice" "bob"} now-names) "current snapshot sees both")))))

(deftest test-pull-with-basis-t
  (testing "pull with explicit :basis-t pulls from the snapshot"
    (install-team-schema!)
    (let [r1 (req! "transact" {"tx-data" "[{:person/name \"alice\" :person/age 33}]"})
          bt1 (get r1 "basis-t")
          _   (req! "transact" {"tx-data" "[[:db/add [:person/name \"alice\"] :person/age 99]]"})
          r-old (req! "pull" {"selector" "[:person/name :person/age]"
                              "eid"      "[:person/name \"alice\"]"
                              "basis-t"  bt1})
          r-now (req! "pull" {"selector" "[:person/name :person/age]"
                              "eid"      "[:person/name \"alice\"]"})]
      (is (= 33 (get (result-of r-old) :person/age)) "old snapshot age")
      (is (= 99 (get (result-of r-now) :person/age)) "new age"))))
