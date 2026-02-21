(ns seon.runtime-test
  "Tests for the unified runtime registry.

   Tests ID generation, instance registration, and crash detection."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [datalevin.core :as d]
            [seon.graph.ingest :as ingest]
            [seon.runtime :as runtime]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures
;;; ---------------------------------------------------------------------------

(def ^:private test-dir (atom nil))
(def ^:private test-conn (atom nil))

(defn- temp-dir []
  (str "tmp/test-runtime-" (System/currentTimeMillis) "-" (rand-int 10000)))

(defn- setup-datalevin! []
  (let [dir (temp-dir)
        ;; Merge graph schema with runtime schema (like production)
        merged-schema (merge ingest/datalevin-schema runtime/runtime-schema)
        conn (d/get-conn dir merged-schema)]
    (reset! test-dir dir)
    (reset! test-conn conn)
    ;; Initialize runtime with test connection
    (runtime/init! {::runtime/conn conn})
    conn))

(defn- teardown-datalevin! []
  ;; Reset registry first
  (runtime/reset-registry! {})
  ;; Close connection
  (when-let [conn @test-conn]
    (try (d/close conn) (catch Exception _)))
  ;; Clean up temp dir
  (when-let [dir @test-dir]
    (try
      (let [f (java.io.File. dir)]
        (doseq [child (reverse (file-seq f))]
          (.delete child)))
      (catch Exception _))))

(use-fixtures :each
  (fn [f]
    (setup-datalevin!)
    (try (f) (finally (teardown-datalevin!)))))

;;; ---------------------------------------------------------------------------
;;; ID Generation Tests
;;; ---------------------------------------------------------------------------

(deftest generate-id-test
  (testing "generate-id produces 6-char hex strings"
    (let [{::runtime/keys [id]} (runtime/generate-id {})]
      (is (string? id))
      (is (= 6 (count id)))
      (is (re-matches #"[a-f0-9]{6}" id))))

  (testing "generate-id produces unique values with no collisions"
    (let [ids (set (map ::runtime/id (repeatedly 100 #(runtime/generate-id {}))))]
      (is (= 100 (count ids)) "all 100 IDs should be unique (collision checked)"))))

;;; ---------------------------------------------------------------------------
;;; Registration Tests
;;; ---------------------------------------------------------------------------

(deftest register-basic-test
  (testing "register! creates an instance in memory"
    (let [result (runtime/register! {::runtime/namespace "test.ns"
                                     ::runtime/status :running
                                     ::runtime/location :in-process})]
      (is (= "test.ns" (::runtime/namespace result)))
      (is (= :running (::runtime/status result)))
      (is (= :in-process (::runtime/location result)))
      (is (inst? (::runtime/started-at result)))))

  (testing "registered instance is queryable"
    (runtime/register! {::runtime/namespace "test.query"
                        ::runtime/status :running
                        ::runtime/location :in-process})
    (let [instance (runtime/instance {::runtime/namespace "test.query"})]
      (is (some? instance))
      (is (= "test.query" (::runtime/namespace instance)))
      (is (= :running (::runtime/status instance))))))

(deftest register-with-optional-fields-test
  (testing "register! with session-id and nrepl-port"
    (let [result (runtime/register! {::runtime/namespace "test.external"
                                     ::runtime/status :running
                                     ::runtime/location :external
                                     ::runtime/session-id "abc123"
                                     ::runtime/nrepl-port 7901})]
      (is (= "abc123" (::runtime/session-id result)))
      (is (= 7901 (::runtime/nrepl-port result)))))

  (testing "register! with component-key"
    (let [result (runtime/register! {::runtime/namespace "test.component"
                                     ::runtime/status :running
                                     ::runtime/location :in-process
                                     ::runtime/component-key :seon/test-component})]
      (is (= :seon/test-component (::runtime/component-key result))))))

(deftest register-upsert-test
  (testing "register! upserts existing instance"
    ;; Register first time
    (runtime/register! {::runtime/namespace "test.upsert"
                        ::runtime/status :running
                        ::runtime/location :in-process})

    ;; Register again with different status
    (runtime/register! {::runtime/namespace "test.upsert"
                        ::runtime/status :paused
                        ::runtime/location :in-process})

    ;; Should have updated status
    (let [instance (runtime/instance {::runtime/namespace "test.upsert"})]
      (is (= :paused (::runtime/status instance))))))

;;; ---------------------------------------------------------------------------
;;; Unregister Tests
;;; ---------------------------------------------------------------------------

(deftest unregister-test
  (testing "unregister! sets status to stopped"
    (runtime/register! {::runtime/namespace "test.unregister"
                        ::runtime/status :running
                        ::runtime/location :in-process})

    (let [result (runtime/unregister! {::runtime/namespace "test.unregister"})]
      (is (= :stopped (::runtime/status result)))
      (is (inst? (::runtime/stopped-at result)))))

  (testing "unregister! returns nil for non-existent instance"
    (let [result (runtime/unregister! {::runtime/namespace "nonexistent"})]
      (is (nil? result)))))

;;; ---------------------------------------------------------------------------
;;; Query Tests
;;; ---------------------------------------------------------------------------

(deftest instance-test
  (testing "instance returns nil for non-existent namespace"
    (is (nil? (runtime/instance {::runtime/namespace "does.not.exist"}))))

  (testing "instance returns registered instance"
    (runtime/register! {::runtime/namespace "test.instance"
                        ::runtime/status :running
                        ::runtime/location :in-process})
    (let [instance (runtime/instance {::runtime/namespace "test.instance"})]
      (is (some? instance))
      (is (= "test.instance" (::runtime/namespace instance))))))

(deftest instances-test
  (testing "instances returns empty vector when no registrations"
    (is (= [] (runtime/instances {}))))

  (testing "instances returns all registered instances"
    (runtime/register! {::runtime/namespace "test.list.1"
                        ::runtime/status :running
                        ::runtime/location :in-process})
    (runtime/register! {::runtime/namespace "test.list.2"
                        ::runtime/status :running
                        ::runtime/location :external})

    (let [all (runtime/instances {})]
      (is (= 2 (count all)))
      (is (some #(= "test.list.1" (::runtime/namespace %)) all))
      (is (some #(= "test.list.2" (::runtime/namespace %)) all)))))

;;; ---------------------------------------------------------------------------
;;; Crash Detection Tests
;;; ---------------------------------------------------------------------------

(deftest mark-crashed-test
  (testing "mark-crashed! marks running instances in Datalevin as crashed"
    ;; Register an instance (persists to Datalevin)
    (runtime/register! {::runtime/namespace "test.crash"
                        ::runtime/status :running
                        ::runtime/location :in-process})

    ;; Reset the in-memory registry (simulates restart)
    (runtime/reset-registry! {})

    ;; Now mark crashed
    (let [{::runtime/keys [crashed-count]} (runtime/mark-crashed! {})]
      (is (= 1 crashed-count)))

    ;; Verify in Datalevin
    (let [results (d/q '[:find ?status
                         :in $ ?ns
                         :where
                         [?e :seon.runtime/namespace ?ns]
                         [?e :seon.runtime/status ?status]]
                       @@test-conn "test.crash")]
      (is (= #{[:crashed]} results))))

  (testing "mark-crashed! returns 0 when no running instances"
    (runtime/reset-registry! {})
    (let [{::runtime/keys [crashed-count]} (runtime/mark-crashed! {})]
      (is (= 0 crashed-count)))))

;;; ---------------------------------------------------------------------------
;;; Persistence Tests
;;; ---------------------------------------------------------------------------

(deftest persistence-test
  (testing "registered instance is persisted to Datalevin"
    (runtime/register! {::runtime/namespace "test.persist"
                        ::runtime/status :running
                        ::runtime/location :in-process
                        ::runtime/component-key :seon/test})

    ;; Query Datalevin directly
    (let [results (d/q '[:find ?ns ?status ?location ?key
                         :in $ ?ns
                         :where
                         [?e :seon.runtime/namespace ?ns]
                         [?e :seon.runtime/status ?status]
                         [?e :seon.runtime/location ?location]
                         [?e :seon.runtime/component-key ?key]]
                       @@test-conn "test.persist")]
      (is (= 1 (count results)))
      (is (= ["test.persist" :running :in-process :seon/test] (first results)))))

  (testing "unregister updates status in Datalevin"
    (runtime/register! {::runtime/namespace "test.persist.unreg"
                        ::runtime/status :running
                        ::runtime/location :in-process})
    (runtime/unregister! {::runtime/namespace "test.persist.unreg"})

    (let [results (d/q '[:find ?status
                         :in $ ?ns
                         :where
                         [?e :seon.runtime/namespace ?ns]
                         [?e :seon.runtime/status ?status]]
                       @@test-conn "test.persist.unreg")]
      (is (= #{[:stopped]} results)))))

;;; ---------------------------------------------------------------------------
;;; Reset Tests
;;; ---------------------------------------------------------------------------

(deftest reset-registry-test
  (testing "reset-registry! clears in-memory state"
    (runtime/register! {::runtime/namespace "test.reset"
                        ::runtime/status :running
                        ::runtime/location :in-process})

    (is (= 1 (count (runtime/instances {}))))

    (runtime/reset-registry! {})

    (is (= 0 (count (runtime/instances {}))))))

(comment
  (require '[kaocha.repl :as k])
  (k/run 'seon.runtime-test)
  nil)
