(ns seon.dev.clojure-edit-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [seon.dev.clojure-edit :as edit]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures
;;; ---------------------------------------------------------------------------

(def ^:dynamic *test-file* nil)

(defn with-temp-file
  "Fixture that creates a temp file for each test."
  [f]
  (let [temp (java.io.File/createTempFile "clojure-edit-test" ".clj")]
    (try
      (binding [*test-file* (.getAbsolutePath temp)]
        (f))
      (finally
        (.delete temp)))))

(use-fixtures :each with-temp-file)

(defn write-test-file!
  "Write content to the test file."
  [content]
  (spit *test-file* content))

(defn read-test-file
  "Read the test file content."
  []
  (slurp *test-file*))

;;; ---------------------------------------------------------------------------
;;; Basic Replacement Tests
;;; ---------------------------------------------------------------------------

(deftest simple-replacement-test
  (testing "Simple s-expression replacement"
    (write-test-file! "(defn foo [] (+ 1 2))")
    (let [result (edit/edit-sexp!
                  {::edit/file-path *test-file*
                   ::edit/match "(+ 1 2)"
                   ::edit/replace "(+ 1 3)"})]
      (is (::edit/success result))
      (is (= 1 (::edit/replacements result)))
      (is (str/includes? (read-test-file) "(+ 1 3)")))))

(deftest whitespace-insensitive-match-test
  (testing "Matches expression with different whitespace"
    (write-test-file! "(defn foo []\n  (+  1\n      2))")
    (let [result (edit/edit-sexp!
                  {::edit/file-path *test-file*
                   ::edit/match "(+ 1 2)"
                   ::edit/replace "(+ 1 3)"})]
      (is (::edit/success result))
      (is (str/includes? (read-test-file) "(+ 1 3)")))))

(deftest nested-expression-test
  (testing "Replaces deeply nested expression"
    (write-test-file! "(defn foo [x]
  (let [y 10]
    (if (nil? x)
      :default
      x)))")
    (let [result (edit/edit-sexp!
                  {::edit/file-path *test-file*
                   ::edit/match "(if (nil? x) :default x)"
                   ::edit/replace "(or x :default)"})]
      (is (::edit/success result))
      (is (str/includes? (read-test-file) "(or x :default)")))))

;;; ---------------------------------------------------------------------------
;;; Multiple Match Tests
;;; ---------------------------------------------------------------------------

(deftest multiple-matches-rejected-test
  (testing "Multiple matches without replace_all is rejected"
    (write-test-file! "(defn foo [] (+ 1 2) (+ 1 2))")
    (let [result (edit/edit-sexp!
                  {::edit/file-path *test-file*
                   ::edit/match "(+ 1 2)"
                   ::edit/replace "(+ 1 3)"})]
      (is (not (::edit/success result)))
      (is (str/includes? (::edit/error result) "Found 2 matches"))
      (is (= 2 (count (::edit/matches result))))
      ;; File should be unchanged
      (is (= "(defn foo [] (+ 1 2) (+ 1 2))" (read-test-file))))))

(deftest replace-all-test
  (testing "Replace all occurrences with replace_all flag"
    (write-test-file! "(defn foo [] (+ 1 2) (+ 1 2) (+ 1 2))")
    (let [result (edit/edit-sexp!
                  {::edit/file-path *test-file*
                   ::edit/match "(+ 1 2)"
                   ::edit/replace "(+ 1 3)"
                   ::edit/replace-all? true})]
      (is (::edit/success result))
      (is (= 3 (::edit/replacements result)))
      (is (not (str/includes? (read-test-file) "(+ 1 2)")))
      (is (= 3 (count (re-seq #"\(\+ 1 3\)" (read-test-file))))))))

(deftest line-range-disambiguation-test
  (testing "Line range narrows match scope"
    (write-test-file! "(defn foo [] (+ 1 2))
(defn bar [] (+ 1 2))
(defn baz [] (+ 1 2))")
    (let [result (edit/edit-sexp!
                  {::edit/file-path *test-file*
                   ::edit/match "(+ 1 2)"
                   ::edit/replace "(+ 1 3)"
                   ::edit/line-start 2
                   ::edit/line-end 2})]
      (is (::edit/success result))
      (is (= 1 (::edit/replacements result)))
      ;; Only the second line should be changed
      (let [lines (str/split-lines (read-test-file))]
        (is (str/includes? (first lines) "(+ 1 2)"))
        (is (str/includes? (second lines) "(+ 1 3)"))
        (is (str/includes? (nth lines 2) "(+ 1 2)"))))))

;;; ---------------------------------------------------------------------------
;;; Error Handling Tests
;;; ---------------------------------------------------------------------------

(deftest invalid-match-expression-test
  (testing "Invalid match expression returns error"
    (write-test-file! "(defn foo [] (+ 1 2))")
    (let [result (edit/edit-sexp!
                  {::edit/file-path *test-file*
                   ::edit/match "(+ 1 2"  ; Missing closing paren
                   ::edit/replace "(+ 1 3)"})]
      (is (not (::edit/success result)))
      (is (str/includes? (::edit/error result) "Invalid match expression")))))

(deftest invalid-replace-expression-test
  (testing "Invalid replace expression returns error"
    (write-test-file! "(defn foo [] (+ 1 2))")
    (let [result (edit/edit-sexp!
                  {::edit/file-path *test-file*
                   ::edit/match "(+ 1 2)"
                   ::edit/replace "(+ 1 3"})]  ; Missing closing paren
      (is (not (::edit/success result)))
      (is (str/includes? (::edit/error result) "Invalid replace expression")))))

(deftest match-not-found-test
  (testing "No match returns error"
    (write-test-file! "(defn foo [] (+ 1 2))")
    (let [result (edit/edit-sexp!
                  {::edit/file-path *test-file*
                   ::edit/match "(+ 3 4)"
                   ::edit/replace "(+ 5 6)"})]
      (is (not (::edit/success result)))
      (is (str/includes? (::edit/error result) "Match not found")))))

(deftest file-not-found-test
  (testing "Non-existent file returns error"
    (let [result (edit/edit-sexp!
                  {::edit/file-path "/nonexistent/path/file.clj"
                   ::edit/match "(+ 1 2)"
                   ::edit/replace "(+ 1 3)"})]
      (is (not (::edit/success result)))
      (is (str/includes? (::edit/error result) "File not found")))))

;;; ---------------------------------------------------------------------------
;;; Complex Expression Tests
;;; ---------------------------------------------------------------------------

(deftest map-replacement-test
  (testing "Replaces map expressions"
    (write-test-file! "(def config {:a 1 :b 2})")
    (let [result (edit/edit-sexp!
                  {::edit/file-path *test-file*
                   ::edit/match "{:a 1 :b 2}"
                   ::edit/replace "{:a 10 :b 20 :c 30}"})]
      (is (::edit/success result))
      (is (str/includes? (read-test-file) "{:a 10 :b 20 :c 30}")))))

(deftest vector-replacement-test
  (testing "Replaces vector expressions"
    (write-test-file! "(def items [1 2 3])")
    (let [result (edit/edit-sexp!
                  {::edit/file-path *test-file*
                   ::edit/match "[1 2 3]"
                   ::edit/replace "[4 5 6 7]"})]
      (is (::edit/success result))
      (is (str/includes? (read-test-file) "[4 5 6 7]")))))

(deftest symbol-replacement-test
  (testing "Replaces symbols"
    (write-test-file! "(defn foo [x] (old-fn x))")
    (let [result (edit/edit-sexp!
                  {::edit/file-path *test-file*
                   ::edit/match "old-fn"
                   ::edit/replace "new-fn"})]
      (is (::edit/success result))
      (is (str/includes? (read-test-file) "(new-fn x)")))))

(deftest keyword-replacement-test
  (testing "Replaces keywords"
    (write-test-file! "(def status :pending)")
    (let [result (edit/edit-sexp!
                  {::edit/file-path *test-file*
                   ::edit/match ":pending"
                   ::edit/replace ":completed"})]
      (is (::edit/success result))
      (is (str/includes? (read-test-file) ":completed")))))

;;; ---------------------------------------------------------------------------
;;; Diff Output Tests
;;; ---------------------------------------------------------------------------

(deftest diff-output-test
  (testing "Returns unified diff"
    (write-test-file! "(defn foo [] (+ 1 2))")
    (let [result (edit/edit-sexp!
                  {::edit/file-path *test-file*
                   ::edit/match "(+ 1 2)"
                   ::edit/replace "(+ 1 3)"})]
      (is (::edit/success result))
      (is (string? (::edit/diff result)))
      (is (str/includes? (::edit/diff result) "---"))
      (is (str/includes? (::edit/diff result) "+++")))))
