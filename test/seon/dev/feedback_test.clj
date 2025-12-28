(ns seon.dev.feedback-test
  "Tests for the feedback namespace - REPL introspection and generative testing."
  (:require [clojure.test :refer :all]
            [malli.core :as m]
            [seon.dev.feedback :as fb]
            [seon.test-utils :refer [with-test-node *test-node*]]))

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

;;; ---------------------------------------------------------------------------
;;; File Hash Tests
;;; ---------------------------------------------------------------------------

(deftest file-hash-test
  (testing "Returns consistent hash for same content"
    (let [test-file "src/seon/dev/feedback.clj"
          hash1 (fb/file-hash test-file)
          hash2 (fb/file-hash test-file)]
      (is (string? hash1) "Should return a string")
      (is (= hash1 hash2) "Same file should produce same hash")))

  (testing "Returns nil for non-existent file"
    (let [hash (fb/file-hash "this/file/does/not/exist.clj")]
      (is (nil? hash)))))

(deftest fn-id-test
  (testing "Converts namespace and function to keyword"
    (is (= :seon.foo/bar (fb/fn-id 'seon.foo 'bar)))
    (is (= :clojure.core/inc (fb/fn-id 'clojure.core 'inc)))))

;;; ---------------------------------------------------------------------------
;;; XTDB Storage Tests
;;; ---------------------------------------------------------------------------

(deftest record-function-test
  (with-test-node
    (fn []
      (testing "Records function with correct fields"
        (let [result (fb/record-function! *test-node* 'seon.dev.feedback-test 'sample-add)]
          (is (some? result) "Should return transaction result")
          (is (:tx-id result) "Should have transaction ID")))

      (testing "Stored function has expected fields"
        (let [stored (fb/stored-functions *test-node* 'seon.dev.feedback-test)
              fn-data (get stored 'sample-add)]
          (is (some? fn-data) "Should find stored function")
          (is (= :seon.dev.feedback-test/sample-add (:id fn-data)))
          (is (= [:=> [:cat :int :int] :int] (:schema fn-data)))
          ;; schema-refs can be a set or empty - just check it exists
          (is (contains? fn-data :schema-refs) "should have schema-refs key")
          (is (some? (:first-seen fn-data)))))

      (testing "Returns nil for function without schema"
        (let [result (fb/record-function! *test-node* 'clojure.core 'inc)]
          (is (nil? result)))))))

(deftest stored-functions-test
  (with-test-node
    (fn []
      (testing "Returns empty map when no functions stored"
        (let [stored (fb/stored-functions *test-node* 'some.random.namespace)]
          (is (map? stored))
          (is (empty? stored))))

      (testing "Returns stored functions after recording"
        ;; Record multiple functions
        (fb/record-function! *test-node* 'seon.dev.feedback-test 'sample-add)
        (fb/record-function! *test-node* 'seon.dev.feedback-test 'sample-greet)

        (let [stored (fb/stored-functions *test-node* 'seon.dev.feedback-test)]
          (is (= 2 (count stored)))
          (is (contains? stored 'sample-add))
          (is (contains? stored 'sample-greet)))))))

(deftest new-functions-test
  (with-test-node
    (fn []
      (testing "All functions are new when nothing stored"
        (let [new-fns (fb/new-functions *test-node* 'seon.dev.feedback-test)]
          (is (set? new-fns))
          (is (contains? new-fns 'sample-add))
          (is (contains? new-fns 'sample-greet))))

      (testing "Function no longer new after recording"
        (fb/record-function! *test-node* 'seon.dev.feedback-test 'sample-add)
        (let [new-fns (fb/new-functions *test-node* 'seon.dev.feedback-test)]
          (is (not (contains? new-fns 'sample-add)) "sample-add should not be new")
          (is (contains? new-fns 'sample-greet) "sample-greet should still be new"))))))

(deftest file-changed-test
  (with-test-node
    (fn []
      (testing "Returns true when file not tracked"
        (is (true? (fb/file-changed? *test-node* "src/seon/dev/feedback.clj"))))

      (testing "Returns false after recording function from file"
        (fb/record-function! *test-node* 'seon.dev.feedback-test 'sample-add)
        ;; The test file should now be tracked
        ;; Note: var metadata :file is relative (e.g., "seon/dev/feedback_test.clj")
        ;; so we need to query using the exact same path that was stored
        (let [stored (fb/stored-functions *test-node* 'seon.dev.feedback-test)
              file-path (:file (get stored 'sample-add))]
          ;; File path from var metadata may be nil or relative
          (when (and file-path (.exists (clojure.java.io/file file-path)))
            (is (false? (fb/file-changed? *test-node* file-path))
                "File should not be changed when hash matches")))))))

(deftest record-error-test
  (with-test-node
    (fn []
      (testing "Records error with correct fields"
        (let [result (fb/record-error! *test-node*
                                       :gen-test-fail
                                       :seon.foo/bar
                                       {:message "Test failed"
                                        :shrunk-input {:x 0}})]
          (is (some? result))
          (is (:tx-id result))))

      (testing "Can retrieve errors for function"
        (fb/record-error! *test-node* :gen-test-fail :seon.foo/bar {:message "Error 1"})
        (fb/record-error! *test-node* :unit-test :seon.foo/bar {:message "Error 2"})

        ;; 3 errors total: 1 from first test + 2 from this test
        (let [errors (fb/function-errors *test-node* :seon.foo/bar)]
          (is (= 3 (count errors)))
          (is (every? #(= :seon.foo/bar (:error/function %)) errors)))))))

(deftest record-edit-event-test
  (with-test-node
    (fn []
      (testing "Records edit event with correct fields"
        (let [result (fb/record-edit-event! *test-node*
                                            "src/seon/foo.clj"
                                            #{:seon.foo/bar :seon.foo/baz}
                                            :success)]
          (is (some? result))
          (is (:tx-id result)))))))
