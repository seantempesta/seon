(ns seon.ns.lifecycle-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [seon.ctx :as ctx]
            [seon.db :as db]
            [seon.graph.ingest :as ingest]
            [seon.ns.lifecycle :as lifecycle]
            [seon.runtime :as runtime]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures
;;; ---------------------------------------------------------------------------

(def ^:private test-dir (atom nil))
(def ^:private test-conn (atom nil))

(defn- temp-dir []
  (str "tmp/test-lifecycle-" (System/currentTimeMillis) "-" (rand-int 10000)))

(defn- setup-datalevin! []
  (let [dir (temp-dir)
        ;; Merge graph schema + ctx schema + runtime schema for full coverage
        merged-schema (merge ingest/datalevin-schema
                             ctx/datalevin-schema
                             runtime/runtime-schema)
        conn (d/create-conn dir merged-schema)]
    (reset! test-dir dir)
    (reset! test-conn conn)
    conn))

(defn- teardown-datalevin! []
  (when-let [conn @test-conn]
    (try (d/close conn) (catch Exception _)))
  (when-let [dir @test-dir]
    (try
      (let [f (java.io.File. dir)]
        (doseq [child (reverse (file-seq f))]
          (.delete child)))
      (catch Exception _))))

(defn- cleanup-ctx-registry! []
  (doseq [{::ctx/keys [instance-id]} (ctx/list-instances {})]
    (ctx/destroy! {::ctx/instance-id instance-id})))

(use-fixtures :each
  (fn [f]
    (setup-datalevin!)
    (try
      (binding [db/*direct-write* true]
        (f))
      (finally
        (cleanup-ctx-registry!)
        (teardown-datalevin!)))))

;;; ---------------------------------------------------------------------------
;;; ctx-spec-key tests
;;; ---------------------------------------------------------------------------

(deftest ctx-spec-key-test
  (testing "produces correct keyword from namespace symbol"
    (is (= :seon.health.workout/*ctx*
           (::lifecycle/ctx-spec-key
            (lifecycle/ctx-spec-key {::lifecycle/ns-sym 'seon.health.workout}))))
    (is (= :seon.trading.signals/*ctx*
           (::lifecycle/ctx-spec-key
            (lifecycle/ctx-spec-key {::lifecycle/ns-sym 'seon.trading.signals}))))))

;;; ---------------------------------------------------------------------------
;;; dynamic-namespace? tests
;;; ---------------------------------------------------------------------------

(deftest dynamic-namespace-test
  (let [conn @test-conn]
    (testing "returns false when no data in Datalevin"
      (is (false? (::lifecycle/dynamic?
                   (lifecycle/dynamic-namespace? {::lifecycle/conn conn
                                                  ::lifecycle/ns-sym 'seon.fake.ns})))))

    (testing "returns true when scanner has marked namespace as dynamic"
      (d/transact! conn [{:seon.ns/name "seon.health.workout"
                          :seon.ns/dynamic? true}])
      (is (true? (::lifecycle/dynamic?
                  (lifecycle/dynamic-namespace? {::lifecycle/conn conn
                                                 ::lifecycle/ns-sym 'seon.health.workout})))))

    (testing "returns false for non-dynamic namespace"
      (d/transact! conn [{:seon.ns/name "seon.schema"
                          :seon.ns/dynamic? false}])
      (is (false? (::lifecycle/dynamic?
                   (lifecycle/dynamic-namespace? {::lifecycle/conn conn
                                                  ::lifecycle/ns-sym 'seon.schema})))))))

;;; ---------------------------------------------------------------------------
;;; initial-value tests
;;; ---------------------------------------------------------------------------

(deftest initial-value-calls-namespace-fn-test
  (testing "calls initial-state fn when present"
    ;; seon.health.workout has initial-state defined
    (let [result (lifecycle/initial-value {::lifecycle/ns-sym 'seon.health.workout})]
      (is (map? (::lifecycle/data result)))
      (is (vector? (:seon.health.workout/workouts (::lifecycle/data result)))))))

(deftest initial-value-returns-nil-for-unknown-ns-test
  (testing "returns nil data for namespace without initial-state or spec"
    (let [result (lifecycle/initial-value {::lifecycle/ns-sym 'seon.nonexistent.ns})]
      (is (nil? (::lifecycle/data result))))))

;;; ---------------------------------------------------------------------------
;;; ensure-instance! tests
;;; ---------------------------------------------------------------------------

(deftest ensure-instance-creates-fresh-test
  (testing "creates fresh instance with schema validation"
    (let [conn @test-conn
          result (lifecycle/ensure-instance! {::lifecycle/conn conn
                                              ::lifecycle/ns-sym 'seon.health.workout})]
      (is (string? (::lifecycle/instance-id result)))
      (is (some? (::lifecycle/ctx-atom result)))
      (let [ctx-val @(::lifecycle/ctx-atom result)]
        (is (vector? (:seon.health.workout/workouts ctx-val)))))))

(deftest ensure-instance-validates-state-test
  (testing "ctx-schema rejects invalid swap! (wrong type for required key)"
    (let [conn @test-conn
          result (lifecycle/ensure-instance! {::lifecycle/conn conn
                                              ::lifecycle/ns-sym 'seon.health.workout})
          ctx-atom (::lifecycle/ctx-atom result)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (swap! ctx-atom assoc :seon.health.workout/workouts "not-a-vector"))))))

(deftest ensure-instance-with-explicit-id-test
  (testing "uses provided instance-id when no existing instance"
    (let [conn @test-conn
          result (lifecycle/ensure-instance! {::lifecycle/conn conn
                                              ::lifecycle/ns-sym 'seon.health.workout
                                              ::lifecycle/instance-id "test42"})]
      (is (= "test42" (::lifecycle/instance-id result))))))

;;; ---------------------------------------------------------------------------
;;; resolve-instance tests
;;; ---------------------------------------------------------------------------

(deftest resolve-instance-finds-persisted-test
  (testing "resolves instance persisted to Datalevin"
    (let [conn @test-conn
          data {:seon.health.workout/workouts []}
          data-str (pr-str data)]
      ;; Persist directly to Datalevin
      (d/transact! conn [{:seon.ctx/instance-id "inst01"
                          :seon.ctx/namespace "seon.health.workout"
                          :seon.ctx/data data-str
                          :seon.ctx/updated-at (java.util.Date.)}])
      (let [result (lifecycle/resolve-instance {::lifecycle/conn conn
                                                ::lifecycle/ns-sym 'seon.health.workout
                                                ::lifecycle/instance-id "inst01"})]
        (is (= "inst01" (::lifecycle/instance-id result)))
        (is (= data (::lifecycle/data result)))))))

(deftest resolve-instance-returns-nil-for-missing-test
  (testing "returns nil when instance not found"
    (let [conn @test-conn]
      (is (nil? (lifecycle/resolve-instance {::lifecycle/conn conn
                                             ::lifecycle/ns-sym 'seon.health.workout
                                             ::lifecycle/instance-id "nonexistent"}))))))

(deftest resolve-instance-finds-most-recent-test
  (testing "finds most recent instance when no id given"
    (let [conn @test-conn
          old-data {:seon.health.workout/workouts []}
          new-data {:seon.health.workout/workouts [{:seon.health.workout/exercise "Squat"
                                                    :seon.health.workout/sets 5
                                                    :seon.health.workout/reps 5
                                                    :seon.health.workout/weight 100}]}]
      (d/transact! conn [{:seon.ctx/instance-id "old01"
                          :seon.ctx/namespace "seon.health.workout"
                          :seon.ctx/data (pr-str old-data)
                          :seon.ctx/updated-at #inst "2024-01-01T00:00:00Z"}])
      (d/transact! conn [{:seon.ctx/instance-id "new01"
                          :seon.ctx/namespace "seon.health.workout"
                          :seon.ctx/data (pr-str new-data)
                          :seon.ctx/updated-at #inst "2025-01-01T00:00:00Z"}])
      (let [result (lifecycle/resolve-instance {::lifecycle/conn conn
                                                ::lifecycle/ns-sym 'seon.health.workout})]
        (is (= "new01" (::lifecycle/instance-id result)))
        (is (= new-data (::lifecycle/data result)))))))

;;; ---------------------------------------------------------------------------
;;; Instance resume (create -> persist -> resolve -> matches)
;;; ---------------------------------------------------------------------------

(deftest instance-resume-round-trip-test
  (testing "create instance, persist, resolve, state matches"
    (let [conn @test-conn
          ;; Create instance
          result (lifecycle/ensure-instance! {::lifecycle/conn conn
                                              ::lifecycle/ns-sym 'seon.health.workout
                                              ::lifecycle/instance-id "resume01"})
          iid (::lifecycle/instance-id result)
          original-state @(::lifecycle/ctx-atom result)]
      ;; Force persist
      (ctx/persist! {::ctx/conn conn ::ctx/instance-id iid})
      ;; Destroy the in-memory instance
      (ctx/destroy! {::ctx/instance-id iid})
      ;; Resolve from Datalevin
      (let [resolved (lifecycle/resolve-instance {::lifecycle/conn conn
                                                   ::lifecycle/ns-sym 'seon.health.workout
                                                   ::lifecycle/instance-id "resume01"})]
        (is (some? resolved) "Should find persisted instance")
        (is (= "resume01" (::lifecycle/instance-id resolved)))
        (is (= original-state (::lifecycle/data resolved))
            "Resolved state should match original")))))

;;; ---------------------------------------------------------------------------
;;; backup-all-instances! tests
;;; ---------------------------------------------------------------------------

(deftest backup-all-instances-test
  (testing "backs up all active ctx instances"
    (let [conn @test-conn]
      ;; Create a couple of instances
      (lifecycle/ensure-instance! {::lifecycle/conn conn
                                   ::lifecycle/ns-sym 'seon.health.workout
                                   ::lifecycle/instance-id "bk01"})
      (let [result (lifecycle/backup-all-instances! {::lifecycle/conn conn})]
        (is (pos? (::lifecycle/backed-up result)))
        (is (= (::lifecycle/backed-up result) (::lifecycle/total result)))))))

;;; ---------------------------------------------------------------------------
;;; inject-vars! tests
;;; ---------------------------------------------------------------------------

(deftest inject-vars-test
  (testing "injects *ctx* dynamic var into namespace"
    (let [test-atom (atom {:test true})
          result (lifecycle/inject-vars! {::lifecycle/ns-sym 'seon.health.workout
                                          ::lifecycle/ctx-atom test-atom})]
      (is (true? result))
      (let [v (ns-resolve 'seon.health.workout '*ctx*)]
        (is (some? v) "*ctx* var should exist")
        (is (.isDynamic v) "*ctx* should be dynamic")
        (is (= test-atom @v) "*ctx* should hold the atom")))))
