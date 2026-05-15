(ns seon.ns.lifecycle-test
  "Tests for seon.ns.lifecycle. Ported in M-2b from the legacy datalevin
   shape to the canonical datahike `:memory` fixture.

   Two pre-port tests are dropped pending M-4 — they exercise
   `seon.ctx/persist!` which currently calls into `seon.db/resolve-conn`
   (a deprecation shim that throws). M-4 redesigns `*ctx*` with
   atom-semantics + auto-persist + warn-on-unserializable; restore
   instance-resume-round-trip and backup-all-instances then."
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

(deftest initial-value-calls-namespace-fn-test
  (testing "calls initial-state fn when present"
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
    ;; M-2b: omit ::lifecycle/db-name to skip the `inject-vars!` *conn*
    ;; injection that calls into the now-deprecated `seon.db/resolve-conn`
    ;; shim. The in-memory ctx creation + validation path is what this
    ;; test exercises; persistence wiring is M-3/M-4 scope.
    (let [result (lifecycle/ensure-instance! {::lifecycle/ns-sym 'seon.health.workout})]
      (is (string? (::lifecycle/instance-id result)))
      (is (some? (::lifecycle/ctx-atom result)))
      (let [ctx-val @(::lifecycle/ctx-atom result)]
        (is (vector? (:seon.health.workout/workouts ctx-val)))))))

(deftest ensure-instance-validates-state-test
  (testing "ctx-schema rejects invalid swap! (wrong type for required key)"
    (let [result (lifecycle/ensure-instance! {::lifecycle/ns-sym 'seon.health.workout})
          ctx-atom (::lifecycle/ctx-atom result)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (swap! ctx-atom assoc :seon.health.workout/workouts "not-a-vector"))))))

(deftest ensure-instance-with-explicit-id-test
  (testing "uses provided instance-id when no existing instance"
    (let [result (lifecycle/ensure-instance! {::lifecycle/ns-sym 'seon.health.workout
                                              ::lifecycle/instance-id "test42"})]
      (is (= "test42" (::lifecycle/instance-id result))))))

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
    (let [test-atom (atom {:test true})
          result (lifecycle/inject-vars! {::lifecycle/ns-sym 'seon.health.workout
                                          ::lifecycle/ctx-atom test-atom})]
      (is (true? result))
      (let [v (ns-resolve 'seon.health.workout '*ctx*)]
        (is (some? v) "*ctx* var should exist")
        (is (.isDynamic v) "*ctx* should be dynamic")
        (is (= test-atom @v) "*ctx* should hold the atom")))))
