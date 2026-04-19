(ns seon.db.validation-test
  "Tests for Malli value validation gate in db/transact!.

   Verifies that transact! validates entity map values against their
   registered Malli schemas before data reaches Datalevin."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [seon.db :as db]
            [seon.db.datalevin.conn :as conn]
            [seon.schema :as schema]
            [seon.test-utils :as tu]))

;;; ---------------------------------------------------------------------------
;;; Test Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::id
                  [:int {:db/unique :db.unique/identity
                         :description "Test entity identity"}])

(schema/register! ::name
                  [:string {:min 1 :max 200
                            :description "Test entity name"}])

(schema/register! ::status
                  [:keyword {:description "Test entity status keyword"}])

(schema/register! ::count
                  [:int {:min 0
                         :description "Test entity count"}])

(schema/register! ::active?
                  [:boolean {:description "Whether entity is active"}])

(schema/register! ::score
                  [:double {:min 0.0 :max 1.0
                            :description "Test score"}])

(schema/register! ::uuid-field
                  [:uuid {:description "Test UUID field"}])

(schema/register! ::tags
                  [:vector :keyword])

(schema/register! ::kind
                  [:enum :alpha :beta :gamma])

;;; ---------------------------------------------------------------------------
;;; Fixtures
;;; ---------------------------------------------------------------------------

(def ^:private test-datalevin-schema
  {::id {:db/valueType :db.type/long :db/unique :db.unique/identity}
   ::name {:db/valueType :db.type/string}
   ::status {:db/valueType :db.type/keyword}
   ::count {:db/valueType :db.type/long}
   ::active? {:db/valueType :db.type/boolean}
   ::score {:db/valueType :db.type/double}
   ::uuid-field {:db/valueType :db.type/uuid}
   ::tags {:db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}
   ::kind {:db/valueType :db.type/keyword}})

(defn- with-validation-conn
  "Create a temp conn with test schemas and bind db/*direct-mode* + *conn-manager*."
  [f]
  (tu/with-temp-conn test-datalevin-schema
    (fn [conn]
      (let [fake-mgr {::conn/port 0
                      ::conn/connections (atom {:test {::conn/connection conn}})}]
        (binding [db/*direct-mode* true
                  db/*conn-manager* fake-mgr]
          (f conn))))))

;;; ---------------------------------------------------------------------------
;;; Valid Entity Maps Pass Through
;;; ---------------------------------------------------------------------------

(deftest valid-entity-passes-test
  (testing "valid entity map with correct types transacts successfully"
    (with-validation-conn
      (fn [conn]
        (db/transact! :test [{::id 1
                              ::name "test-entity"
                              ::status :active
                              ::active? true}])
        (let [result (d/pull @conn '[*] [::id 1])]
          (is (= "test-entity" (::name result)))
          (is (= :active (::status result)))
          (is (true? (::active? result))))))))

(deftest valid-entity-with-all-types-test
  (testing "entity with string, int, keyword, boolean, double, uuid all pass"
    (with-validation-conn
      (fn [conn]
        (let [test-uuid (java.util.UUID/randomUUID)]
          (db/transact! :test [{::id 2
                                ::name "full-entity"
                                ::status :ready
                                ::count 42
                                ::active? false
                                ::score 0.75
                                ::uuid-field test-uuid}])
          (let [result (d/pull @conn '[*] [::id 2])]
            (is (= "full-entity" (::name result)))
            (is (= 42 (::count result)))
            (is (= 0.75 (::score result)))
            (is (= test-uuid (::uuid-field result)))))))))

(deftest valid-enum-passes-test
  (testing "valid enum value passes validation"
    (with-validation-conn
      (fn [conn]
        (db/transact! :test [{::id 3 ::kind :alpha}])
        (let [result (d/pull @conn '[*] [::id 3])]
          (is (= :alpha (::kind result))))))))

;;; ---------------------------------------------------------------------------
;;; Wrong-Type Values Produce Clear Errors
;;; ---------------------------------------------------------------------------

(deftest string-where-keyword-expected-test
  (testing "string where keyword expected throws with clear error"
    (with-validation-conn
      (fn [_conn]
        (let [ex (try
                   (db/transact! :test [{::id 10 ::status "active"}])
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex) "should throw an exception")
          (is (re-find #"Malli validation failed" (ex-message ex)))
          (is (= ::status (:attr (ex-data ex))))
          (is (= "active" (:actual-value (ex-data ex))))
          (is (some? (:malli-explanation (ex-data ex)))))))))

(deftest int-where-string-expected-test
  (testing "int where string expected throws with clear error"
    (with-validation-conn
      (fn [_conn]
        (let [ex (try
                   (db/transact! :test [{::id 11 ::name 42}])
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex) "should throw an exception")
          (is (re-find #"Malli validation failed" (ex-message ex)))
          (is (= ::name (:attr (ex-data ex))))
          (is (= 42 (:actual-value (ex-data ex)))))))))

(deftest keyword-where-int-expected-test
  (testing "keyword where int expected throws"
    (with-validation-conn
      (fn [_conn]
        (let [ex (try
                   (db/transact! :test [{::id 12 ::count :many}])
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex) "should throw an exception")
          (is (= ::count (:attr (ex-data ex))))
          (is (= :many (:actual-value (ex-data ex)))))))))

(deftest string-where-boolean-expected-test
  (testing "string where boolean expected throws"
    (with-validation-conn
      (fn [_conn]
        (let [ex (try
                   (db/transact! :test [{::id 13 ::active? "yes"}])
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex) "should throw an exception")
          (is (= ::active? (:attr (ex-data ex))))
          (is (= "yes" (:actual-value (ex-data ex)))))))))

(deftest invalid-enum-value-test
  (testing "invalid enum value throws"
    (with-validation-conn
      (fn [_conn]
        (let [ex (try
                   (db/transact! :test [{::id 14 ::kind :delta}])
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex) "should throw an exception")
          (is (= ::kind (:attr (ex-data ex))))
          (is (= :delta (:actual-value (ex-data ex)))))))))

(deftest double-out-of-range-test
  (testing "double outside schema range throws"
    (with-validation-conn
      (fn [_conn]
        (let [ex (try
                   (db/transact! :test [{::id 15 ::score 1.5}])
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex) "should throw an exception")
          (is (= ::score (:attr (ex-data ex))))
          (is (= 1.5 (:actual-value (ex-data ex)))))))))

;;; ---------------------------------------------------------------------------
;;; Nil Values Produce Clear Errors
;;; ---------------------------------------------------------------------------

(deftest nil-value-for-string-test
  (testing "nil where string expected throws validation error"
    (with-validation-conn
      (fn [_conn]
        (let [ex (try
                   (db/transact! :test [{::id 20 ::name nil}])
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex) "should throw for nil value")
          (is (re-find #"Malli validation failed" (ex-message ex)))
          (is (= ::name (:attr (ex-data ex))))
          (is (nil? (:actual-value (ex-data ex)))))))))

(deftest nil-value-for-keyword-test
  (testing "nil where keyword expected throws validation error"
    (with-validation-conn
      (fn [_conn]
        (let [ex (try
                   (db/transact! :test [{::id 21 ::status nil}])
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex) "should throw for nil value")
          (is (= ::status (:attr (ex-data ex))))
          (is (nil? (:actual-value (ex-data ex)))))))))

(deftest nil-value-for-int-test
  (testing "nil where int expected throws validation error"
    (with-validation-conn
      (fn [_conn]
        (let [ex (try
                   (db/transact! :test [{::id 22 ::count nil}])
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex) "should throw for nil value")
          (is (= ::count (:attr (ex-data ex)))))))))

;;; ---------------------------------------------------------------------------
;;; System Attributes Skipped
;;; ---------------------------------------------------------------------------

(deftest db-system-attrs-skipped-test
  (testing ":db/id and other :db/* attrs are not validated against Malli"
    (with-validation-conn
      (fn [conn]
        ;; :db/id is a system attr; transact! should not try to validate it
        (db/transact! :test [{:db/id -1
                              ::id 30
                              ::name "with-db-id"}])
        (let [result (d/pull @conn '[*] [::id 30])]
          (is (= "with-db-id" (::name result))))))))

;;; ---------------------------------------------------------------------------
;;; Vector Tuples Skipped
;;; ---------------------------------------------------------------------------

(deftest vector-add-tuple-skipped-test
  (testing "[:db/add eid attr val] tuples are not validated as entity maps"
    (with-validation-conn
      (fn [conn]
        ;; First create the entity
        (db/transact! :test [{::id 40 ::name "original"}])
        ;; Then use a vector tuple to update (these bypass entity-map validation)
        (let [eid (:db/id (d/pull @conn '[:db/id] [::id 40]))]
          (db/transact! :test [[:db/add eid ::name "updated"]])
          (let [result (d/pull @conn '[*] [::id 40])]
            (is (= "updated" (::name result)))))))))

(deftest vector-retract-tuple-skipped-test
  (testing "[:db/retract eid attr] tuples are not validated as entity maps"
    (with-validation-conn
      (fn [conn]
        (db/transact! :test [{::id 41 ::name "to-retract" ::status :active}])
        (let [eid (:db/id (d/pull @conn '[:db/id] [::id 41]))]
          ;; Retract should not be validated
          (db/transact! :test [[:db/retract eid ::status :active]])
          (let [result (d/pull @conn '[*] [::id 41])]
            (is (nil? (::status result)))))))))

;;; ---------------------------------------------------------------------------
;;; Unregistered Attrs Still Caught
;;; ---------------------------------------------------------------------------

(deftest unregistered-attr-still-caught-test
  (testing "unregistered attrs throw before value validation"
    (with-validation-conn
      (fn [_conn]
        (let [ex (try
                   (db/transact! :test [{::id 50
                                         :completely.fake/attr "value"}])
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex) "should throw for unregistered attr")
          (is (re-find #"Unregistered attributes" (ex-message ex))))))))

;;; ---------------------------------------------------------------------------
;;; Multiple Entities in One Transaction
;;; ---------------------------------------------------------------------------

(deftest multiple-entities-validation-test
  (testing "validation catches error in second entity of a batch"
    (with-validation-conn
      (fn [_conn]
        (let [ex (try
                   (db/transact! :test [{::id 60 ::name "valid-one"}
                                        {::id 61 ::name 999}])
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex) "should throw for invalid entity in batch")
          (is (= ::name (:attr (ex-data ex))))
          (is (= 999 (:actual-value (ex-data ex)))))))))

;;; ---------------------------------------------------------------------------
;;; Error Message Quality
;;; ---------------------------------------------------------------------------

(deftest error-message-contains-attr-and-schema-test
  (testing "error message includes attribute name and expected schema"
    (with-validation-conn
      (fn [_conn]
        (let [ex (try
                   (db/transact! :test [{::id 70 ::name 42}])
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex))
          (let [msg (ex-message ex)]
            (is (re-find #":seon\.db\.validation-test/name" msg)
                "error message should contain the attribute name")
            (is (re-find #":string" msg)
                "error message should mention the expected type")))))))

(deftest error-data-contains-malli-explanation-test
  (testing "ex-data contains :malli-explanation with :errors"
    (with-validation-conn
      (fn [_conn]
        (let [ex (try
                   (db/transact! :test [{::id 71 ::status "not-a-keyword"}])
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex))
          (let [explanation (:malli-explanation (ex-data ex))]
            (is (map? explanation))
            (is (seq (:errors explanation)))))))))

(deftest large-value-truncated-in-message-test
  (testing "large values are truncated in error message but full in ex-data"
    (with-validation-conn
      (fn [_conn]
        (let [large-val (apply str (repeat 200 "x"))
              ex (try
                   (db/transact! :test [{::id 72 ::count large-val}])
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex))
          ;; Message should be truncated (not contain the full 200-char string)
          (is (<= (count (ex-message ex)) 300)
              "error message should be reasonably short")
          ;; But ex-data has the full value
          (is (= large-val (:actual-value (ex-data ex)))))))))

;;; ---------------------------------------------------------------------------
;;; Mixed Entity Maps and Vector Tuples
;;; ---------------------------------------------------------------------------

(deftest mixed-maps-and-vectors-test
  (testing "tx-data with both entity maps and vector tuples validates only maps"
    (with-validation-conn
      (fn [conn]
        ;; First create an entity to get its eid
        (db/transact! :test [{::id 80 ::name "first"}])
        (let [eid (:db/id (d/pull @conn '[:db/id] [::id 80]))]
          ;; Mix entity map (valid) with vector tuple
          (db/transact! :test [{::id 81 ::name "second"}
                               [:db/add eid ::name "updated-first"]])
          (let [r1 (d/pull @conn '[*] [::id 80])
                r2 (d/pull @conn '[*] [::id 81])]
            (is (= "updated-first" (::name r1)))
            (is (= "second" (::name r2)))))))))
