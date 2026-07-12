(ns seon.server.registry-test
  "Tests for seon.server.registry — Path B registry.

   All tests use the `:memory` backend exclusively. `:file`/`:sqlite`
   exercise the same code paths via store/config-for; the registry's
   invariants are backend-agnostic.

   Isolation: each test runs under a fixture that snapshots the
   registry, runs the test, then restores. Conns opened during a
   test get released by `restore-registry!`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datahike.api :as d]
            [seon.server.registry :as ss])
  (:import [java.io File]))

;;; --- Fixture ---------------------------------------------------------------

(defn ^:private isolate-registry [t]
  (let [{::ss/keys [snapshot]} (ss/snapshot-registry {})]
    (try
      (t)
      (finally
        (ss/restore-registry! {::ss/snapshot snapshot})))))

(use-fixtures :each isolate-registry)

(defn- session-count
  "Current registry size. Tests assert DELTAS against a baseline captured at
   test start — the live JVM's registry may legitimately hold ambient entries
   (and the dev hook's generative checks have been observed to leak entries),
   so absolute counts are not stable."
  []
  (count (::ss/sessions (ss/list-sessions {}))))

(defn- delete-tree!
  "Remove a disposable file-store directory after an isolated test."
  [path]
  (let [root (File. ^String path)]
    (when (.exists root)
      (run! (fn [^File f] (.delete f)) (reverse (file-seq root))))))

;;; --- Tests -----------------------------------------------------------------

(deftest happy-path-create-get-transact-query-remove
  (testing "full lifecycle: ensure, get-conn, transact, query, remove"
    (let [db-name :test/happy
          entry   (ss/ensure-db! {::ss/db-name db-name ::ss/backend :memory})
          conn    (::ss/conn (ss/get-conn {::ss/db-name db-name}))]
      (is (some? entry))
      (is (= :memory (::ss/backend entry)))
      (is (identical? (::ss/conn entry) conn)
          "get-conn returns the same conn instance as ensure-db!")
      (d/transact conn [{:db/ident :test/k
                         :db/valueType :db.type/string
                         :db/cardinality :db.cardinality/one}])
      (d/transact conn [{:test/k "hello"}])
      (is (= "hello"
             (d/q '[:find ?v . :where [_ :test/k ?v]] @conn))
          "transacted datom is queryable")
      (let [{::ss/keys [removed?]} (ss/remove-db! {::ss/db-name db-name})]
        (is (true? removed?)))
      (is (nil? (::ss/conn (ss/get-conn {::ss/db-name db-name})))
          "after remove, get-conn returns no conn"))))

(deftest ensure-db-is-idempotent
  (testing "calling ensure-db! twice with same db-name returns same conn"
    (let [baseline (session-count)
          name     :test/idem
          e1       (ss/ensure-db! {::ss/db-name name ::ss/backend :memory})
          e2       (ss/ensure-db! {::ss/db-name name ::ss/backend :memory})]
      (is (identical? (::ss/conn e1) (::ss/conn e2))
          "second ensure must return the same identical conn")
      (is (= (inc baseline) (session-count))
          "two ensures added exactly one registry entry")
      (ss/remove-db! {::ss/db-name name}))))

(deftest concurrent-ensure-converges
  (testing "N threads racing on same db-name → all get the same conn"
    (let [baseline  (session-count)
          name      :test/race
          n         8
          latch     (promise)
          fs        (vec
                     (for [_ (range n)]
                       (future
                         @latch
                         (ss/ensure-db! {::ss/db-name name
                                         ::ss/backend :memory}))))
          _         (deliver latch :go)
          entries   (mapv deref fs)
          conns     (map ::ss/conn entries)
          first-c   (first conns)]
      (is (every? #(identical? first-c %) conns)
          "all racing threads must observe the same conn")
      (is (= (inc baseline) (session-count))
          "registry gained exactly one entry for the raced name")
      (ss/remove-db! {::ss/db-name name}))))

(deftest list-sessions-reflects-state
  (testing "list-sessions returns all registered names; remove drops"
    (ss/ensure-db! {::ss/db-name :test/a ::ss/backend :memory})
    (ss/ensure-db! {::ss/db-name :test/b ::ss/backend :memory})
    (let [names (->> (::ss/sessions (ss/list-sessions {}))
                     (map ::ss/db-name)
                     set)]
      (is (contains? names :test/a))
      (is (contains? names :test/b)))
    (ss/remove-db! {::ss/db-name :test/a})
    (let [names (->> (::ss/sessions (ss/list-sessions {}))
                     (map ::ss/db-name)
                     set)]
      (is (not (contains? names :test/a)))
      (is (contains? names :test/b)))
    (ss/remove-db! {::ss/db-name :test/b})))

(deftest remove-absent-is-noop
  (testing "remove-db! on unregistered name returns {::removed? false}"
    (let [baseline (session-count)
          {::ss/keys [removed?]}
          (ss/remove-db! {::ss/db-name :never/registered})]
      (is (false? removed?))
      (is (= baseline (session-count))
          "no-op remove leaves the registry size unchanged"))))

(deftest get-conn-absent-returns-no-conn
  (testing "get-conn on unregistered name returns a response without ::conn"
    (let [resp (ss/get-conn {::ss/db-name :never/registered})]
      (is (not (contains? resp ::ss/conn))
          "absent get-conn must not contain a ::conn key")
      (is (nil? (::ss/conn resp))))))

(deftest sessions-are-independent
  (testing "two registered DBs are isolated — datom in A invisible in B"
    (let [ca (::ss/conn
              (ss/ensure-db! {::ss/db-name :test/iso-a ::ss/backend :memory}))
          cb (::ss/conn
              (ss/ensure-db! {::ss/db-name :test/iso-b ::ss/backend :memory}))]
      (d/transact ca [{:db/ident :test/k
                       :db/valueType :db.type/string
                       :db/cardinality :db.cardinality/one}])
      (d/transact cb [{:db/ident :test/k
                       :db/valueType :db.type/string
                       :db/cardinality :db.cardinality/one}])
      (d/transact ca [{:test/k "from-a"}])
      (is (= "from-a" (d/q '[:find ?v . :where [_ :test/k ?v]] @ca)))
      (is (nil? (d/q '[:find ?v . :where [_ :test/k ?v]] @cb))
          "DB B should not see DB A's datom")
      (ss/remove-db! {::ss/db-name :test/iso-a})
      (ss/remove-db! {::ss/db-name :test/iso-b}))))

(deftest fork-is-registrable-under-its-own-store-identity
  (testing "a file fork opens as its own cluster and preserves the fork point"
    (let [root        (str (System/getProperty "java.io.tmpdir") "/seon-registry-fork-"
                           (random-uuid))
          source-name :test/fork-source
          fork-name   :test/fork-target
          source-path (str root "/source")
          fork-path   (str root "/fork")]
      (try
        (let [source-entry (ss/ensure-db! {::ss/db-name source-name
                                           ::ss/backend :file
                                           ::ss/path source-path})
              source-conn  (::ss/conn source-entry)
              _            (d/transact source-conn
                                        [{:db/ident       :test/fork-value
                                          :db/valueType   :db.type/string
                                          :db/cardinality :db.cardinality/one}
                                         {:test/fork-value "at-fork"}])
              basis-t      (:max-tx @source-conn)
              forked       (ss/fork-db! {::ss/db-name source-name
                                          ::ss/fork-name fork-name
                                          ::ss/at basis-t
                                          ::ss/path fork-path})
              fork-entry   (ss/ensure-db! {::ss/db-name fork-name
                                           ::ss/backend :file
                                           ::ss/path fork-path})
              fork-conn    (::ss/conn fork-entry)]
          (is (true? (::ss/forked? forked)))
          (is (= basis-t (::ss/basis-t forked)))
          (is (not= (get-in @source-conn [:config :store :id])
                    (get-in @fork-conn [:config :store :id]))
              "the fork must never reuse the source store identity")
          (is (= "at-fork"
                 (d/q '[:find ?value . :where [_ :test/fork-value ?value]]
                      @fork-conn))
              "the fork is queryable at the captured basis"))
        (finally
          (ss/delete-db! {::ss/db-name fork-name})
          (ss/delete-db! {::ss/db-name source-name})
          (delete-tree! root))))))
