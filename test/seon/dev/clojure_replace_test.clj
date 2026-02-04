(ns seon.dev.clojure-replace-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [seon.dev.clojure-replace :as repl]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures
;;; ---------------------------------------------------------------------------

(def ^:dynamic *test-file* nil)

(defn with-temp-file
  "Fixture that creates a temp file for each test."
  [f]
  (let [temp (java.io.File/createTempFile "clojure-replace-test" ".clj")]
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
;;; 1. Basic Code Replacement (existing behavior)
;;; ---------------------------------------------------------------------------

(deftest basic-code-replacement-test
  (testing "Simple s-expression replacement without comments"
    (write-test-file! "(defn foo [] (+ 1 2))")
    (let [result (repl/replace-sexp!
                  {::repl/file-path *test-file*
                   ::repl/match "(+ 1 2)"
                   ::repl/replace "(+ 1 3)"
                   ::repl/lint? false})]
      (is (::repl/success result))
      (is (= 1 (::repl/replacements result)))
      (is (str/includes? (read-test-file) "(+ 1 3)")))))

;;; ---------------------------------------------------------------------------
;;; 2. Code Replacement Preserves Unmatched Comments
;;; ---------------------------------------------------------------------------

(deftest code-replacement-preserves-comments-test
  (testing "When match pattern has NO comment, existing comments are preserved"
    (write-test-file! ";; important comment\n(+ x 10)")
    (let [result (repl/replace-sexp!
                  {::repl/file-path *test-file*
                   ::repl/match "(+ x 10)"
                   ::repl/replace "(* x 2)"
                   ::repl/lint? false})]
      (is (::repl/success result))
      (is (str/includes? (read-test-file) ";; important comment"))
      (is (str/includes? (read-test-file) "(* x 2)")))))

;;; ---------------------------------------------------------------------------
;;; 3. Comment + Code Replacement
;;; ---------------------------------------------------------------------------

(deftest comment-and-code-replacement-test
  (testing "Both comment AND code are replaced when both are in pattern"
    (write-test-file! ";; old comment\n(+ x 10)")
    (let [result (repl/replace-sexp!
                  {::repl/file-path *test-file*
                   ::repl/match ";; old comment\n(+ x 10)"
                   ::repl/replace ";; new comment\n(* x 2)"
                   ::repl/lint? false})]
      (is (::repl/success result))
      (is (not (str/includes? (read-test-file) ";; old comment")))
      (is (str/includes? (read-test-file) ";; new comment"))
      (is (str/includes? (read-test-file) "(* x 2)")))))

;;; ---------------------------------------------------------------------------
;;; 4. Comment Removal
;;; ---------------------------------------------------------------------------

(deftest comment-removal-test
  (testing "Comment is removed when match has comment but replace doesn't"
    (write-test-file! ";; old comment\n(+ x 10)")
    (let [result (repl/replace-sexp!
                  {::repl/file-path *test-file*
                   ::repl/match ";; old comment\n(+ x 10)"
                   ::repl/replace "(* x 2)"
                   ::repl/lint? false})]
      (is (::repl/success result))
      (is (not (str/includes? (read-test-file) ";; old comment")))
      (is (str/includes? (read-test-file) "(* x 2)")))))

;;; ---------------------------------------------------------------------------
;;; 5. Comment Addition
;;; ---------------------------------------------------------------------------

(deftest comment-addition-test
  (testing "Comment is added when replace has comment but match doesn't"
    (write-test-file! "(+ x 10)")
    (let [result (repl/replace-sexp!
                  {::repl/file-path *test-file*
                   ::repl/match "(+ x 10)"
                   ::repl/replace ";; new comment\n(+ x 10)"
                   ::repl/lint? false})]
      (is (::repl/success result))
      (is (str/includes? (read-test-file) ";; new comment"))
      (is (str/includes? (read-test-file) "(+ x 10)")))))

;;; ---------------------------------------------------------------------------
;;; 6. Comment Mismatch Fails
;;; ---------------------------------------------------------------------------

(deftest comment-mismatch-fails-test
  (testing "Fails when comment in pattern doesn't match source"
    (write-test-file! ";; actual comment\n(+ x 10)")
    (let [result (repl/replace-sexp!
                  {::repl/file-path *test-file*
                   ::repl/match ";; wrong comment\n(+ x 10)"
                   ::repl/replace "(* x 2)"
                   ::repl/lint? false})]
      (is (not (::repl/success result)))
      (is (str/includes? (::repl/error result) "comments don't match"))
      (is (str/includes? (read-test-file) ";; actual comment"))
      (is (str/includes? (read-test-file) "(+ x 10)")))))

;;; ---------------------------------------------------------------------------
;;; 7. Whitespace-Insensitive Matching
;;; ---------------------------------------------------------------------------

(deftest whitespace-insensitive-match-test
  (testing "Matches expression with different whitespace"
    (write-test-file! "(defn foo []\n  (+  1\n      2))")
    (let [result (repl/replace-sexp!
                  {::repl/file-path *test-file*
                   ::repl/match "(+ 1 2)"
                   ::repl/replace "(+ 1 3)"
                   ::repl/lint? false})]
      (is (::repl/success result))
      (is (str/includes? (read-test-file) "(+ 1 3)")))))

;;; ---------------------------------------------------------------------------
;;; 8. Inline Comment (After Code)
;;; ---------------------------------------------------------------------------

(deftest inline-comment-test
  (testing "Handles inline comment after code"
    (write-test-file! "(+ x 10) ; inline comment")
    (let [result (repl/replace-sexp!
                  {::repl/file-path *test-file*
                   ::repl/match "(+ x 10) ; inline comment"
                   ::repl/replace "(* x 2) ; updated inline"
                   ::repl/lint? false})]
      (is (::repl/success result))
      (is (not (str/includes? (read-test-file) "; inline comment")))
      (is (str/includes? (read-test-file) "; updated inline"))
      (is (str/includes? (read-test-file) "(* x 2)")))))

;;; ---------------------------------------------------------------------------
;;; 9. Both Before and Inline Comments
;;; ---------------------------------------------------------------------------

(deftest both-comments-test
  (testing "Handles both before and inline comments"
    (write-test-file! ";; before\n(+ x 10) ; inline")
    (let [result (repl/replace-sexp!
                  {::repl/file-path *test-file*
                   ::repl/match ";; before\n(+ x 10) ; inline"
                   ::repl/replace ";; new before\n(* x 2) ; new inline"
                   ::repl/lint? false})]
      (is (::repl/success result))
      (is (str/includes? (read-test-file) ";; new before"))
      (is (str/includes? (read-test-file) "; new inline"))
      (is (str/includes? (read-test-file) "(* x 2)")))))

;;; ---------------------------------------------------------------------------
;;; 10. cljfmt Fixes Indentation
;;; ---------------------------------------------------------------------------

(deftest cljfmt-formatting-test
  (testing "Output is formatted by cljfmt"
    (write-test-file! "(defn foo []\n(+ 1 2))")
    (let [result (repl/replace-sexp!
                  {::repl/file-path *test-file*
                   ::repl/match "(+ 1 2)"
                   ::repl/replace "(+ 1 3)"
                   ::repl/lint? false})]
      (is (::repl/success result))
      (let [content (read-test-file)]
        (is (str/includes? content "(defn foo []\n  (+ 1 3)"))))))

;;; ---------------------------------------------------------------------------
;;; 11. Multiple Matches Without replace_all Fails
;;; ---------------------------------------------------------------------------

(deftest multiple-matches-rejected-test
  (testing "Multiple matches without replace_all is rejected"
    (write-test-file! "(defn foo [] (+ 1 2) (+ 1 2))")
    (let [result (repl/replace-sexp!
                  {::repl/file-path *test-file*
                   ::repl/match "(+ 1 2)"
                   ::repl/replace "(+ 1 3)"
                   ::repl/lint? false})]
      (is (not (::repl/success result)))
      (is (str/includes? (::repl/error result) "Found 2 matches"))
      (is (= 2 (count (::repl/matches result))))
      (is (= "(defn foo [] (+ 1 2) (+ 1 2))" (read-test-file))))))

;;; ---------------------------------------------------------------------------
;;; 12. Multiple Matches With replace_all Succeeds
;;; ---------------------------------------------------------------------------

(deftest replace-all-test
  (testing "Replace all occurrences with replace_all flag"
    (write-test-file! "(defn foo [] (+ 1 2) (+ 1 2) (+ 1 2))")
    (let [result (repl/replace-sexp!
                  {::repl/file-path *test-file*
                   ::repl/match "(+ 1 2)"
                   ::repl/replace "(+ 1 3)"
                   ::repl/replace-all? true
                   ::repl/lint? false})]
      (is (::repl/success result))
      (is (= 3 (::repl/replacements result)))
      (is (not (str/includes? (read-test-file) "(+ 1 2)")))
      (is (= 3 (count (re-seq #"\(\+ 1 3\)" (read-test-file))))))))

;;; ---------------------------------------------------------------------------
;;; 13. Line Range Filtering
;;; ---------------------------------------------------------------------------

(deftest line-range-disambiguation-test
  (testing "Line range narrows match scope"
    (write-test-file! "(defn foo [] (+ 1 2))\n(defn bar [] (+ 1 2))\n(defn baz [] (+ 1 2))")
    (let [result (repl/replace-sexp!
                  {::repl/file-path *test-file*
                   ::repl/match "(+ 1 2)"
                   ::repl/replace "(+ 1 3)"
                   ::repl/line-start 2
                   ::repl/line-end 2
                   ::repl/lint? false})]
      (is (::repl/success result))
      (is (= 1 (::repl/replacements result)))
      (let [lines (str/split-lines (read-test-file))]
        (is (str/includes? (first lines) "(+ 1 2)"))
        (is (str/includes? (second lines) "(+ 1 3)"))
        (is (str/includes? (nth lines 2) "(+ 1 2)"))))))

;;; ---------------------------------------------------------------------------
;;; 14. Dry Run Mode
;;; ---------------------------------------------------------------------------

(deftest dry-run-test
  (testing "Dry run shows diff but doesn't modify file"
    (write-test-file! "(defn foo [] (+ 1 2))")
    (let [original (read-test-file)
          result (repl/replace-sexp!
                  {::repl/file-path *test-file*
                   ::repl/match "(+ 1 2)"
                   ::repl/replace "(+ 1 3)"
                   ::repl/dry-run? true
                   ::repl/lint? false})]
      (is (::repl/success result))
      (is (str/includes? (::repl/message result) "DRY RUN"))
      (is (= 1 (::repl/replacements result)))
      (is (some? (::repl/diff result)))
      (is (= original (read-test-file))))))

;;; ---------------------------------------------------------------------------
;;; 15. Similar Forms Hint When No Match
;;; ---------------------------------------------------------------------------

(deftest similar-forms-hint-test
  (testing "Shows similar forms when match not found"
    (write-test-file! "(defn foo [] (+ 1 2))")
    (let [result (repl/replace-sexp!
                  {::repl/file-path *test-file*
                   ::repl/match "(+ 3 4)"
                   ::repl/replace "(+ 5 6)"
                   ::repl/lint? false})]
      (is (not (::repl/success result)))
      (is (str/includes? (::repl/error result) "Match not found"))
      (is (str/includes? (::repl/error result) "Similar forms"))
      (is (str/includes? (::repl/error result) "(+ 1 2)"))))

  (testing "No similar forms shown when head symbol doesn't match"
    (write-test-file! "(defn foo [] (+ 1 2))")
    (let [result (repl/replace-sexp!
                  {::repl/file-path *test-file*
                   ::repl/match "(bar x y)"
                   ::repl/replace "(baz x y)"
                   ::repl/lint? false})]
      (is (not (::repl/success result)))
      (is (str/includes? (::repl/error result) "Match not found"))
      (is (not (str/includes? (::repl/error result) "Similar forms"))))))

;;; ---------------------------------------------------------------------------
;;; Additional Edge Cases
;;; ---------------------------------------------------------------------------

(deftest nested-expression-test
  (testing "Replaces deeply nested expression"
    (write-test-file! "(defn foo [x]\n  (let [y 10]\n    (if (nil? x)\n      :default\n      x)))")
    (let [result (repl/replace-sexp!
                  {::repl/file-path *test-file*
                   ::repl/match "(if (nil? x) :default x)"
                   ::repl/replace "(or x :default)"
                   ::repl/lint? false})]
      (is (::repl/success result))
      (is (str/includes? (read-test-file) "(or x :default)")))))

(deftest map-replacement-test
  (testing "Replaces map expressions"
    (write-test-file! "(def config {:a 1 :b 2})")
    (let [result (repl/replace-sexp!
                  {::repl/file-path *test-file*
                   ::repl/match "{:a 1 :b 2}"
                   ::repl/replace "{:a 10 :b 20 :c 30}"
                   ::repl/lint? false})]
      (is (::repl/success result))
      (is (str/includes? (read-test-file) "{:a 10 :b 20 :c 30}")))))

(deftest vector-replacement-test
  (testing "Replaces vector expressions"
    (write-test-file! "(def items [1 2 3])")
    (let [result (repl/replace-sexp!
                  {::repl/file-path *test-file*
                   ::repl/match "[1 2 3]"
                   ::repl/replace "[4 5 6 7]"
                   ::repl/lint? false})]
      (is (::repl/success result))
      (is (str/includes? (read-test-file) "[4 5 6 7]")))))

(deftest symbol-replacement-test
  (testing "Replaces symbols"
    (write-test-file! "(defn foo [x] (old-fn x))")
    (let [result (repl/replace-sexp!
                  {::repl/file-path *test-file*
                   ::repl/match "old-fn"
                   ::repl/replace "new-fn"
                   ::repl/lint? false})]
      (is (::repl/success result))
      (is (str/includes? (read-test-file) "(new-fn x)")))))

(deftest invalid-match-expression-test
  (testing "Invalid match expression returns error"
    (write-test-file! "(defn foo [] (+ 1 2))")
    (let [result (repl/replace-sexp!
                  {::repl/file-path *test-file*
                   ::repl/match "(+ 1 2"
                   ::repl/replace "(+ 1 3)"
                   ::repl/lint? false})]
      (is (not (::repl/success result)))
      (is (str/includes? (::repl/error result) "Invalid match pattern")))))

(deftest invalid-replace-expression-test
  (testing "Invalid replace expression returns error"
    (write-test-file! "(defn foo [] (+ 1 2))")
    (let [result (repl/replace-sexp!
                  {::repl/file-path *test-file*
                   ::repl/match "(+ 1 2)"
                   ::repl/replace "(+ 1 3"
                   ::repl/lint? false})]
      (is (not (::repl/success result)))
      (is (str/includes? (::repl/error result) "Invalid replace pattern")))))

(deftest file-not-found-test
  (testing "Non-existent file returns error"
    (let [result (repl/replace-sexp!
                  {::repl/file-path "/nonexistent/path/file.clj"
                   ::repl/match "(+ 1 2)"
                   ::repl/replace "(+ 1 3)"
                   ::repl/lint? false})]
      (is (not (::repl/success result)))
      (is (str/includes? (::repl/error result) "File not found")))))

(deftest diff-output-test
  (testing "Returns unified diff"
    (write-test-file! "(defn foo [] (+ 1 2))")
    (let [result (repl/replace-sexp!
                  {::repl/file-path *test-file*
                   ::repl/match "(+ 1 2)"
                   ::repl/replace "(+ 1 3)"
                   ::repl/lint? false})]
      (is (::repl/success result))
      (is (string? (::repl/diff result)))
      (is (str/includes? (::repl/diff result) "---"))
      (is (str/includes? (::repl/diff result) "+++"))))

  (testing "Diff truncation for many-line changes"
    (let [big-form (str "(defn big-fn []\n"
                        (str/join "\n" (repeat 60 "  (println \"line\")"))
                        ")")
          replacement (str "(defn big-fn []\n"
                           (str/join "\n" (repeat 60 "  (println \"changed\")"))
                           ")")]
      (write-test-file! big-form)
      (let [result (repl/replace-sexp!
                    {::repl/file-path *test-file*
                     ::repl/match big-form
                     ::repl/replace replacement
                     ::repl/lint? false})]
        (is (::repl/success result))
        (is (str/includes? (::repl/diff result) "truncated"))))))

;;; ---------------------------------------------------------------------------
;;; 16. Static Analysis (clj-kondo integration)
;;; ---------------------------------------------------------------------------

(deftest lint-blocks-undefined-symbols-test
  (testing "Lint blocks edit that introduces undefined symbols"
    (write-test-file! "(defn foo [x] (+ x 1))")
    (let [result (repl/replace-sexp!
                  {::repl/file-path *test-file*
                   ::repl/match "(+ x 1)"
                   ::repl/replace "(undefined-fn x)"
                   ::repl/lint? true})]
      (is (not (::repl/success result)))
      ;; Uses unified error format with LINT ERRORS header
      (is (str/includes? (::repl/error result) "LINT ERRORS"))
      (is (some? (::repl/lint-findings result)))
      (is (str/includes? (read-test-file) "(+ x 1)")))))

(deftest lint-passes-valid-code-test
  (testing "Lint passes valid code changes"
    (write-test-file! "(defn foo [x] (+ x 1))")
    (let [result (repl/replace-sexp!
                  {::repl/file-path *test-file*
                   ::repl/match "(+ x 1)"
                   ::repl/replace "(inc x)"
                   ::repl/lint? true})]
      (is (::repl/success result))
      (is (str/includes? (read-test-file) "(inc x)")))))

(deftest lint-disabled-allows-invalid-code-test
  (testing "Disabling lint allows invalid code"
    (write-test-file! "(defn foo [x] (+ x 1))")
    (let [result (repl/replace-sexp!
                  {::repl/file-path *test-file*
                   ::repl/match "(+ x 1)"
                   ::repl/replace "(undefined-fn x)"
                   ::repl/lint? false})]
      (is (::repl/success result))
      (is (str/includes? (read-test-file) "(undefined-fn x)")))))
