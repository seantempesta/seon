(ns seon.dev.feedback-test
  "Tests for the feedback namespace - REPL introspection and generative testing."
  (:require [clojure.test :refer :all]
            [malli.core :as m]
            [seon.dev.feedback :as fb]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures - Sample Functions with Schemas
;;; ---------------------------------------------------------------------------

;; Define a test namespace with schema-annotated functions
;; We'll use this namespace's own functions for testing

(defn sample-add
  "A simple function for testing."
  [x y]
  (+ x y))

(m/=> sample-add [:=> [:cat :int :int] :int])

(defn sample-greet
  "A function that takes a string."
  [name]
  (str "Hello, " name "!"))

(m/=> sample-greet [:=> [:cat :string] :string])

;;; ---------------------------------------------------------------------------
;;; namespace-schemas Tests
;;; ---------------------------------------------------------------------------

(deftest namespace-schemas-test
  (testing "Returns schemas for namespace with registered functions"
    (let [schemas (fb/namespace-schemas 'seon.dev.feedback-test)]
      (is (map? schemas)
          "Should return a map")
      (is (contains? schemas 'sample-add)
          "Should contain sample-add")
      (is (contains? schemas 'sample-greet)
          "Should contain sample-greet")))

  (testing "Returns nil for namespace with no schemas"
    (let [schemas (fb/namespace-schemas 'clojure.core)]
      ;; clojure.core doesn't have Malli schemas registered
      (is (nil? schemas)
          "Should return nil for namespace without Malli schemas")))

  (testing "Returns nil for non-existent namespace"
    (let [schemas (fb/namespace-schemas 'this.namespace.does.not.exist)]
      (is (nil? schemas)))))

;;; ---------------------------------------------------------------------------
;;; extract-schema-refs Tests
;;; ---------------------------------------------------------------------------

(deftest extract-schema-refs-test
  (testing "Extracts namespaced keywords from function schema"
    (let [schema [:=> [:cat :user/id :order/cart] :order/result]
          refs (fb/extract-schema-refs schema)]
      (is (set? refs))
      (is (= #{:user/id :order/cart :order/result} refs))))

  (testing "Returns empty set for schema with only built-in types"
    (let [schema [:=> [:cat :int :string] :boolean]
          refs (fb/extract-schema-refs schema)]
      (is (set? refs))
      (is (empty? refs)
          "Built-in types should not be counted as refs")))

  (testing "Handles map schemas"
    (let [schema [:map [:id :uuid] [:name :string]]
          refs (fb/extract-schema-refs schema)]
      (is (empty? refs)
          "Standard map with built-in types has no refs")))

  (testing "Handles nested schemas with refs"
    (let [schema [:map
                  [:user :user/profile]
                  [:orders [:vector :order/summary]]]
          refs (fb/extract-schema-refs schema)]
      (is (= #{:user/profile :order/summary} refs))))

  (testing "Handles vector schemas"
    (let [schema [:vector :item/product]
          refs (fb/extract-schema-refs schema)]
      (is (= #{:item/product} refs))))

  (testing "Handles deeply nested schemas"
    (let [schema [:=> [:cat [:map [:id :user/id]]]
                  [:map [:result :api/response]]]
          refs (fb/extract-schema-refs schema)]
      (is (= #{:user/id :api/response} refs))))

  (testing "Returns empty set for invalid schema input"
    (let [refs (fb/extract-schema-refs "not a schema")]
      (is (set? refs))
      (is (empty? refs)))))

;;; ---------------------------------------------------------------------------
;;; check-function Tests
;;; ---------------------------------------------------------------------------

(deftest check-function-test
  (testing "Returns nil for passing function"
    (let [result (fb/check-function 'seon.dev.feedback-test 'sample-add {:num-tests 5})]
      (is (nil? result)
          "Should return nil when all tests pass")))

  (testing "Returns nil for another passing function"
    (let [result (fb/check-function 'seon.dev.feedback-test 'sample-greet {:num-tests 5})]
      (is (nil? result)
          "sample-greet should pass generative tests")))

  (testing "Returns nil for function without schema"
    (let [result (fb/check-function 'clojure.core 'inc {:num-tests 5})]
      (is (nil? result)
          "Function without schema should return nil")))

  (testing "Returns nil for non-existent function"
    (let [result (fb/check-function 'seon.dev.feedback-test 'does-not-exist {:num-tests 5})]
      (is (nil? result)))))

;;; ---------------------------------------------------------------------------
;;; check-namespace Tests
;;; ---------------------------------------------------------------------------

(deftest check-namespace-test
  (testing "Returns empty vector when all tests pass"
    (let [results (fb/check-namespace 'seon.dev.feedback-test {:num-tests 5})]
      (is (vector? results))
      (is (empty? results)
          "All sample functions should pass")))

  (testing "Returns empty vector for namespace without schemas"
    (let [results (fb/check-namespace 'clojure.core {:num-tests 5})]
      (is (vector? results))
      (is (empty? results))))

  (testing "Returns empty vector for non-existent namespace"
    (let [results (fb/check-namespace 'this.does.not.exist {:num-tests 5})]
      (is (vector? results))
      (is (empty? results)))))

;;; ---------------------------------------------------------------------------
;;; Utility Function Tests
;;; ---------------------------------------------------------------------------

(deftest schema-fns-test
  (testing "Returns set of function symbols with schemas"
    (let [fns (fb/schema-fns 'seon.dev.feedback-test)]
      (is (set? fns))
      (is (contains? fns 'sample-add))
      (is (contains? fns 'sample-greet))))

  (testing "Returns empty set for namespace without schemas"
    (let [fns (fb/schema-fns 'clojure.core)]
      (is (set? fns))
      (is (empty? fns)))))

(deftest function-schema-test
  (testing "Returns schema for registered function"
    (let [schema (fb/function-schema 'seon.dev.feedback-test 'sample-add)]
      (is (= [:=> [:cat :int :int] :int] schema))))

  (testing "Returns nil for function without schema"
    (let [schema (fb/function-schema 'clojure.core 'inc)]
      (is (nil? schema)))))

(deftest function-info-test
  (testing "Returns comprehensive info for schema function"
    (let [info (fb/function-info 'seon.dev.feedback-test 'sample-add)]
      (is (map? info))
      (is (= 'sample-add (:fn info)))
      (is (= 'seon.dev.feedback-test (:ns info)))
      (is (= [:=> [:cat :int :int] :int] (:schema info)))
      (is (set? (:schema-refs info)))
      (is (map? (:var-meta info)))))

  (testing "Returns nil for function without schema"
    (let [info (fb/function-info 'clojure.core 'inc)]
      (is (nil? info)))))
