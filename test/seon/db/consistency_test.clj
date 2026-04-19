(ns seon.db.consistency-test
  "Tests for Phase 5: startup schema consistency check.
   Validates that `validate-persisted-schema` catches banned types
   and passes for all real production entity schemas."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.db.schema :as dbs]))

;;; ---------------------------------------------------------------------------
;;; validate-persisted-schema — individual schema validation
;;; ---------------------------------------------------------------------------

(deftest catches-any-violation-test
  (testing ":any is rejected"
    (let [violations (dbs/validate-persisted-schema "test"
                       [:map [:foo/x :any]])]
      (is (= 1 (count violations)))
      (is (= :any (:violation (first violations))))
      (is (= :foo/x (:attr (first violations)))))))

(deftest catches-some-violation-test
  (testing ":some is rejected"
    (let [violations (dbs/validate-persisted-schema "test"
                       [:map [:foo/x :some]])]
      (is (= 1 (count violations)))
      (is (= :some (:violation (first violations)))))))

(deftest catches-nil-violation-test
  (testing ":nil is rejected"
    (let [violations (dbs/validate-persisted-schema "test"
                       [:map [:foo/x :nil]])]
      (is (= 1 (count violations)))
      (is (= :nil (:violation (first violations)))))))

(deftest catches-maybe-violation-test
  (testing "[:maybe X] is rejected in persisted schema"
    (let [violations (dbs/validate-persisted-schema "test"
                       [:map [:foo/x [:maybe :string]]])]
      (is (= 1 (count violations)))
      (is (= :maybe (:violation (first violations)))))))

(deftest catches-mixed-enum-violation-test
  (testing "mixed-type enum is rejected"
    (let [violations (dbs/validate-persisted-schema "test"
                       [:map [:foo/x [:enum :a "b"]]])]
      (is (= 1 (count violations)))
      (is (= :mixed-enum (:violation (first violations))))))

  (testing "three-type enum is rejected"
    (let [violations (dbs/validate-persisted-schema "test"
                       [:map [:foo/x [:enum :a "b" 1]]])]
      (is (= 1 (count violations)))
      (is (= :mixed-enum (:violation (first violations)))))))

(deftest catches-nested-violations-test
  (testing ":any inside nested :map is caught"
    (let [violations (dbs/validate-persisted-schema "test"
                       [:map [:foo/child [:map [:bar/x :any]]]])]
      (is (= 1 (count violations)))
      (is (= :bar/x (:attr (first violations))))
      (is (= :any (:violation (first violations))))))

  (testing "[:maybe X] inside nested :map is caught"
    (let [violations (dbs/validate-persisted-schema "test"
                       [:map [:foo/child [:map [:bar/x [:maybe :int]]]]])]
      (is (= 1 (count violations)))
      (is (= :bar/x (:attr (first violations))))
      (is (= :maybe (:violation (first violations)))))))

(deftest catches-collection-violations-test
  (testing ":any inside [:vector ...] is caught"
    (let [violations (dbs/validate-persisted-schema "test"
                       [:map [:foo/tags [:vector :any]]])]
      (is (= 1 (count violations)))
      (is (= :any (:violation (first violations))))))

  (testing ":any inside [:set ...] is caught"
    (let [violations (dbs/validate-persisted-schema "test"
                       [:map [:foo/tags [:set :any]]])]
      (is (= 1 (count violations)))
      (is (= :any (:violation (first violations)))))))

(deftest multiple-violations-test
  (testing "multiple violations in one schema are all reported"
    (let [violations (dbs/validate-persisted-schema "test"
                       [:map
                        [:foo/a :any]
                        [:foo/b [:maybe :string]]
                        [:foo/c [:enum :x "y"]]
                        [:foo/d :string]])]
      (is (= 3 (count violations)))
      (is (= #{:any :maybe :mixed-enum}
             (set (map :violation violations)))))))

(deftest schema-name-propagated-test
  (testing "schema-name appears in all violation maps"
    (let [violations (dbs/validate-persisted-schema "my-module"
                       [:map [:foo/x :any] [:foo/y :some]])]
      (is (every? #(= "my-module" (:schema-name %)) violations)))))

;;; ---------------------------------------------------------------------------
;;; Valid schemas pass cleanly
;;; ---------------------------------------------------------------------------

(deftest valid-schemas-pass-test
  (testing "basic types pass"
    (is (empty? (dbs/validate-persisted-schema "test"
                  [:map
                   [:foo/s :string]
                   [:foo/i :int]
                   [:foo/d :double]
                   [:foo/b :boolean]
                   [:foo/k :keyword]
                   [:foo/sym :symbol]
                   [:foo/u :uuid]
                   [:foo/t :inst]]))))

  (testing "optional fields pass"
    (is (empty? (dbs/validate-persisted-schema "test"
                  [:map
                   [:foo/id :uuid]
                   [:foo/name {:optional true} :string]]))))

  (testing "homogeneous enums pass"
    (is (empty? (dbs/validate-persisted-schema "test"
                  [:map
                   [:foo/status [:enum :a :b :c]]
                   [:foo/role [:enum "admin" "user"]]]))))

  (testing "collections pass"
    (is (empty? (dbs/validate-persisted-schema "test"
                  [:map
                   [:foo/tags [:vector :keyword]]
                   [:foo/ids [:set :uuid]]]))))

  (testing "nested maps pass"
    (is (empty? (dbs/validate-persisted-schema "test"
                  [:map
                   [:foo/child [:map [:bar/name :string]]]]))))

  (testing ":seon.db/ref passes"
    (is (empty? (dbs/validate-persisted-schema "test"
                  [:map
                   [:foo/ref :seon.db/ref]]))))

  (testing "predicate types pass"
    (is (empty? (dbs/validate-persisted-schema "test"
                  [:map [:foo/at inst?]])))))

;;; ---------------------------------------------------------------------------
;;; Production schemas: all 15 registered schemas pass
;;; ---------------------------------------------------------------------------

(deftest all-production-schemas-pass-test
  (testing "every registered persisted schema passes validation"
    (let [schemas (dbs/persisted-schemas)]
      (is (pos? (count schemas)) "should have registered schemas")
      (doseq [[schema-name schema] schemas]
        (testing (str "schema: " schema-name)
          (is (empty? (dbs/validate-persisted-schema schema-name schema))))))))

;;; ---------------------------------------------------------------------------
;;; validate-persisted-schemas! — bulk validation
;;; ---------------------------------------------------------------------------

(deftest validate-persisted-schemas-passes-test
  (testing "validate-persisted-schemas! succeeds with current schemas"
    (let [result (dbs/validate-persisted-schemas!)]
      (is (true? (:valid? result)))
      (is (empty? (:violations result)))
      (is (pos? (:schema-count result))))))

;;; ---------------------------------------------------------------------------
;;; register-entity-schema! — registration
;;; ---------------------------------------------------------------------------

(deftest register-entity-schema-test
  (testing "registration is idempotent"
    (dbs/register-entity-schema! "test.idempotent"
      [:map [:test.idempotent/x :string]])
    (dbs/register-entity-schema! "test.idempotent"
      [:map [:test.idempotent/x :string]])
    (is (= 1 (count (filter #(= "test.idempotent" (key %))
                             (dbs/persisted-schemas))))))

  (testing "overwrite updates the schema"
    (dbs/register-entity-schema! "test.overwrite"
      [:map [:test.overwrite/x :string]])
    (dbs/register-entity-schema! "test.overwrite"
      [:map [:test.overwrite/x :int]])
    (let [schema (get (dbs/persisted-schemas) "test.overwrite")]
      (is (= [:map [:test.overwrite/x :int]] schema)))))
