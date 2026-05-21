(ns seon.ns.lifecycle-test
  "Tests for seon.ns.lifecycle. Uses the canonical datahike `:memory`
   fixture.

   Two tests are pending restoration — they exercise `seon.ctx/persist!`
   which currently calls into `seon.db/resolve-conn` (a deprecation shim
   that throws). Restore instance-resume-round-trip and
   backup-all-instances once `*ctx*` is redesigned with atom-semantics
   + auto-persist + warn-on-unserializable."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [seon.ctx :as ctx]
            [seon.db :as db]
            [seon.ns.lifecycle :as lifecycle]
            [seon.test-utils :as tu]))

;;; ---------------------------------------------------------------------------
;;; Test Fixture
;;; ---------------------------------------------------------------------------

(def ^:private lifecycle-malli-schema
  "Merged schema covering the graph entity (`:seon.ns/*`) + ctx entity
   (`:seon.ctx/*`) attrs the lifecycle tests touch."
  [:map
   [:seon.ns/name :seon.ns/name]
   [:seon.ns/doc {:optional true} :string]
   [:seon.ns/file {:optional true} :string]
   [:seon.ns/target {:optional true} :keyword]
   [:seon.ns/dynamic? {:optional true} :boolean]
   [:seon.ctx/instance-id :seon.ctx/instance-id]
   [:seon.ctx/namespace {:optional true} :symbol]
   [:seon.ctx/data {:optional true} :string]
   [:seon.ctx/updated-at {:optional true} :inst]])

(defn- cleanup-ctx-registry! []
  (doseq [{::ctx/keys [instance-id]} (ctx/list-instances {})]
    (try (ctx/destroy! {::ctx/instance-id instance-id})
         (catch Throwable _))))

(use-fixtures :each
  (fn [f]
    ((tu/with-test-db-fixture
       {::tu/namespaces [:seon.runtime]
        ::tu/schemas    {:seon.runtime lifecycle-malli-schema}})
     (fn []
       (cleanup-ctx-registry!)
       (try
         (f)
         (finally
           (cleanup-ctx-registry!)))))))

;;; ---------------------------------------------------------------------------
;;; ctx-spec-key tests (pure)
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
  (testing "returns false when no data in datahike"
    (is (false? (::lifecycle/dynamic?
                 (lifecycle/dynamic-namespace? {::lifecycle/db-name :seon.runtime
                                                ::lifecycle/ns-sym 'seon.fake.ns})))))

  (testing "returns true when scanner has marked namespace as dynamic"
    (db/transact! :seon.runtime [{:seon.ns/name "seon.health.workout"
                                  :seon.ns/dynamic? true}])
    (is (true? (::lifecycle/dynamic?
                (lifecycle/dynamic-namespace? {::lifecycle/db-name :seon.runtime
                                               ::lifecycle/ns-sym 'seon.health.workout})))))

  (testing "returns false for non-dynamic namespace"
    (db/transact! :seon.runtime [{:seon.ns/name "seon.schema"
                                  :seon.ns/dynamic? false}])
    (is (false? (::lifecycle/dynamic?
                 (lifecycle/dynamic-namespace? {::lifecycle/db-name :seon.runtime
                                                ::lifecycle/ns-sym 'seon.schema}))))))

;;; ---------------------------------------------------------------------------
;;; initial-value tests
;;; ---------------------------------------------------------------------------

;; M-2c: initial-value-calls-namespace-fn-test removed when the
;; seon.health.workout demo namespace was deleted. Restore once a
;; substrate-internal fixture namespace exposes an `initial-state` fn
;; and a registered `*ctx*` schema for testing.

(deftest initial-value-returns-nil-for-unknown-ns-test
  (testing "returns nil data for namespace without initial-state or spec"
    (let [result (lifecycle/initial-value {::lifecycle/ns-sym 'seon.nonexistent.ns})]
      (is (nil? (::lifecycle/data result))))))

;;; ---------------------------------------------------------------------------
;;; ensure-instance! tests
;;; ---------------------------------------------------------------------------

;; M-2b: the three ensure-instance-* tests redef `inject-vars!` because
;; the prod fn calls into `seon.db/resolve-conn` (deprecation shim that
;; throws after M-2) and instrumentation rejects passing ::db-name nil
;; to bypass that path. The in-memory ctx creation + Malli validation
;; is what these tests target; *conn* injection is M-3/M-4 scope.

(defn- stub-inject-vars!
  "Test stub for `lifecycle/inject-vars!`. Injects only `*ctx*` (skips the
   `*conn*` injection that hits the deprecation shim)."
  [{::lifecycle/keys [ns-sym ctx-atom]}]
  (let [ns-obj (or (find-ns ns-sym) (create-ns ns-sym))]
    (let [v (intern ns-obj '*ctx* ctx-atom)]
      (.setDynamic v true))
    true))

;; M-2c: ensure-instance-creates-fresh-test and ensure-instance-validates-state-test
;; removed when the seon.health.workout demo namespace was deleted. Both depend on
;; a real loadable namespace with an `initial-state` fn and a registered ctx schema
;; whose required keys are typed (so a wrong-type swap! throws). Restore once a
;; substrate-internal fixture namespace fills that role.

(deftest ensure-instance-with-explicit-id-test
  (testing "uses provided instance-id when no existing instance"
    (with-redefs [lifecycle/inject-vars! stub-inject-vars!]
      (let [result (lifecycle/ensure-instance! {::lifecycle/ns-sym 'seon.health.workout
                                                ::lifecycle/instance-id "test42"})]
        (is (= "test42" (::lifecycle/instance-id result)))))))

;;; ---------------------------------------------------------------------------
;;; resolve-instance tests
;;; ---------------------------------------------------------------------------

(deftest resolve-instance-finds-persisted-test
  (testing "resolves instance persisted to datahike"
    (let [data {:seon.health.workout/workouts []}
          data-str (pr-str data)]
      (db/transact! :seon.runtime [{:seon.ctx/instance-id "inst01"
                                    :seon.ctx/namespace 'seon.health.workout
                                    :seon.ctx/data data-str
                                    :seon.ctx/updated-at (java.util.Date.)}])
      (let [result (lifecycle/resolve-instance {::lifecycle/db-name :seon.runtime
                                                ::lifecycle/ns-sym 'seon.health.workout
                                                ::lifecycle/instance-id "inst01"})]
        (is (= "inst01" (::lifecycle/instance-id result)))
        (is (= data (::lifecycle/data result)))))))

(deftest resolve-instance-returns-nil-for-missing-test
  (testing "returns nil when instance not found"
    (is (nil? (lifecycle/resolve-instance {::lifecycle/db-name :seon.runtime
                                           ::lifecycle/ns-sym 'seon.health.workout
                                           ::lifecycle/instance-id "nonexistent"})))))

(deftest resolve-instance-finds-most-recent-test
  (testing "finds most recent instance when no id given"
    (let [old-data {:seon.health.workout/workouts []}
          new-data {:seon.health.workout/workouts [{:seon.health.workout/exercise "Squat"
                                                    :seon.health.workout/sets 5
                                                    :seon.health.workout/reps 5
                                                    :seon.health.workout/weight 100}]}]
      (db/transact! :seon.runtime [{:seon.ctx/instance-id "old01"
                                    :seon.ctx/namespace 'seon.health.workout
                                    :seon.ctx/data (pr-str old-data)
                                    :seon.ctx/updated-at #inst "2024-01-01T00:00:00Z"}])
      (db/transact! :seon.runtime [{:seon.ctx/instance-id "new01"
                                    :seon.ctx/namespace 'seon.health.workout
                                    :seon.ctx/data (pr-str new-data)
                                    :seon.ctx/updated-at #inst "2025-01-01T00:00:00Z"}])
      (let [result (lifecycle/resolve-instance {::lifecycle/db-name :seon.runtime
                                                ::lifecycle/ns-sym 'seon.health.workout})]
        (is (= "new01" (::lifecycle/instance-id result)))
        (is (= new-data (::lifecycle/data result)))))))

;;; ---------------------------------------------------------------------------
;;; instance-resume + backup-all-instances dropped pending M-4
;;; ---------------------------------------------------------------------------
;;;
;;; `instance-resume-round-trip-test` and `backup-all-instances-test`
;;; both exercise `seon.ctx/persist!`, which currently calls into
;;; `seon.db/resolve-conn` (the deprecation shim that throws). M-4
;;; redesigns `*ctx*` with atom semantics + auto-persist + warn-on-
;;; unserializable per `remaining.md` Forward decisions. Restore both
;;; tests then.

;;; ---------------------------------------------------------------------------
;;; inject-vars! tests
;;; ---------------------------------------------------------------------------

(deftest inject-vars-test
  (testing "injects *ctx* dynamic var into namespace"
    (let [test-ns-sym 'seon.test.placeholder.inject-vars
          _ (create-ns test-ns-sym)
          test-atom (atom {:test true})
          result (lifecycle/inject-vars! {::lifecycle/ns-sym test-ns-sym
                                          ::lifecycle/ctx-atom test-atom})]
      (try
        (is (true? result))
        (let [v (ns-resolve test-ns-sym '*ctx*)]
          (is (some? v) "*ctx* var should exist")
          (is (.isDynamic v) "*ctx* should be dynamic")
          (is (= test-atom @v) "*ctx* should hold the atom"))
        (finally
          (remove-ns test-ns-sym))))))
