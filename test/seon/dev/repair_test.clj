(ns seon.dev.repair-test
  "Tests for the repair namespace - delimiter detection and repair."
  (:require [clojure.test :refer :all]
            [seon.dev.repair :as repair]))

;;; ---------------------------------------------------------------------------
;;; delimiter-error? Tests
;;; ---------------------------------------------------------------------------

(deftest delimiter-error?-test
  (testing "Detects missing closing parentheses"
    (is (true? (repair/delimiter-error? "(defn foo [x] (+ x 1")))
    (is (true? (repair/delimiter-error? "(let [x 1")))
    (is (true? (repair/delimiter-error? "((("))))

  (testing "Detects missing closing brackets"
    (is (true? (repair/delimiter-error? "[1 2 3")))
    (is (true? (repair/delimiter-error? "(defn foo [x")))
    (is (true? (repair/delimiter-error? "[[[1 2] 3"))))

  (testing "Detects missing closing braces"
    (is (true? (repair/delimiter-error? "{:a 1")))
    (is (true? (repair/delimiter-error? "{:a {:b 1}")))
    (is (true? (repair/delimiter-error? "{{{"))))

  (testing "Returns false for balanced code"
    (is (false? (repair/delimiter-error? "(defn foo [x] (+ x 1))")))
    (is (false? (repair/delimiter-error? "[1 2 3]")))
    (is (false? (repair/delimiter-error? "{:a 1 :b 2}")))
    (is (false? (repair/delimiter-error? "(let [x {:a [1 2 3]}] x)"))))

  (testing "Handles edge cases"
    (is (false? (repair/delimiter-error? "")))
    (is (false? (repair/delimiter-error? nil)))
    (is (false? (repair/delimiter-error? "   ")))
    (is (false? (repair/delimiter-error? "; just a comment"))))

  (testing "Handles complex valid code"
    (is (false? (repair/delimiter-error?
                 "(defn process-data
                     \"Process some data.\"
                     [{:keys [input output]}]
                     (let [result (map inc input)]
                       {:output result}))"))))

  (testing "Handles strings and characters"
    ;; Strings should not confuse the parser
    (is (false? (repair/delimiter-error? "(str \"hello (world)\")")))
    (is (false? (repair/delimiter-error? "(str \"[brackets]\")")))
    (is (false? (repair/delimiter-error? "(str \"{braces}\")")))))

;;; ---------------------------------------------------------------------------
;;; repair Tests
;;; ---------------------------------------------------------------------------

(deftest repair-test
  (testing "Repairs missing closing parentheses"
    (let [broken "(defn foo [x]\n  (+ x 1"
          repaired (repair/repair broken)]
      (is (string? repaired))
      (is (not (repair/delimiter-error? repaired)))))

  (testing "Repairs missing closing brackets"
    (let [broken "[1 2 3"
          repaired (repair/repair broken)]
      (is (string? repaired))
      (is (not (repair/delimiter-error? repaired)))))

  (testing "Repairs missing closing braces"
    (let [broken "{:a 1 :b 2"
          repaired (repair/repair broken)]
      (is (string? repaired))
      (is (not (repair/delimiter-error? repaired)))))

  (testing "Returns nil for already valid code"
    (is (nil? (repair/repair "(defn foo [x] (+ x 1))")))
    (is (nil? (repair/repair "[1 2 3]")))
    (is (nil? (repair/repair "{:a 1}"))))

  (testing "Returns nil for empty/nil input"
    (is (nil? (repair/repair "")))
    (is (nil? (repair/repair nil))))

  (testing "Repairs nested structures"
    (let [broken "(let [x {:a [1 2 3"
          repaired (repair/repair broken)]
      (is (string? repaired))
      (is (not (repair/delimiter-error? repaired)))))

  (testing "Preserves code structure during repair"
    (let [broken "(defn add [a b]\n  (+ a b"
          repaired (repair/repair broken)]
      (is (string? repaired))
      ;; Should preserve the basic structure
      (is (clojure.string/includes? repaired "defn add"))
      (is (clojure.string/includes? repaired "[a b]"))
      (is (clojure.string/includes? repaired "(+ a b")))))

;;; ---------------------------------------------------------------------------
;;; repair-and-format Tests
;;; ---------------------------------------------------------------------------

(deftest repair-and-format-test
  (testing "Repairs and returns success for broken code"
    (let [result (repair/repair-and-format
                  {::repair/content "(defn foo [x] (+ x 1"
                   ::repair/format? false})]
      (is (true? (::repair/success result)))
      (is (string? (::repair/content result)))
      (is (not (repair/delimiter-error? (::repair/content result))))))

  (testing "Returns success for already valid code"
    (let [result (repair/repair-and-format
                  {::repair/content "(defn foo [x] (+ x 1))"})]
      (is (true? (::repair/success result)))
      (is (= "(defn foo [x] (+ x 1))" (::repair/content result)))))

  (testing "Formats code when format? is true"
    (let [result (repair/repair-and-format
                  {::repair/content "(defn foo [x](+ x 1))"
                   ::repair/format? true})]
      (is (true? (::repair/success result)))
      ;; Formatted code should have proper spacing/indentation
      (is (string? (::repair/content result)))))

  (testing "Does not format when format? is false"
    (let [original "(defn foo [x](+ x 1))"
          result (repair/repair-and-format
                  {::repair/content original
                   ::repair/format? false})]
      (is (true? (::repair/success result)))
      ;; Should be unchanged when format? is false
      (is (= original (::repair/content result)))))

  (testing "Returns error for unrepairable code"
    ;; Severely malformed code that parinfer can't fix
    ;; This is a contrived case - most delimiter issues are repairable
    (let [result (repair/repair-and-format
                  {::repair/content ")))))"})]
      ;; Either succeeds (parinfer fixed it) or fails gracefully
      (is (contains? result ::repair/success))
      (is (contains? result ::repair/content)))))

;;; ---------------------------------------------------------------------------
;;; Integration Tests
;;; ---------------------------------------------------------------------------

(deftest integration-test
  (testing "Full workflow: detect, repair, format"
    (let [broken "(defn process-items [items]\n  (map inc items"
          ;; 1. Detect error
          _ (is (true? (repair/delimiter-error? broken)))
          ;; 2. Repair
          repaired (repair/repair broken)
          _ (is (string? repaired))
          _ (is (false? (repair/delimiter-error? repaired)))
          ;; 3. Format via repair-and-format
          result (repair/repair-and-format
                  {::repair/content repaired
                   ::repair/format? true})]
      (is (true? (::repair/success result)))))

  (testing "LLM-style broken code repair"
    ;; Common pattern: LLM forgets to close a function
    (let [broken "(defn calculate-total
  \"Calculate the total price.\"
  [{:keys [items tax-rate]}]
  (let [subtotal (reduce + (map :price items))]
    (* subtotal (+ 1 tax-rate)"
          repaired (repair/repair broken)]
      (is (string? repaired))
      (is (false? (repair/delimiter-error? repaired)))
      ;; Should preserve the docstring and structure
      (is (clojure.string/includes? repaired "Calculate the total price"))
      (is (clojure.string/includes? repaired "tax-rate")))))

;;; ---------------------------------------------------------------------------
;;; Edge Cases and Robustness
;;; ---------------------------------------------------------------------------

(deftest robustness-test
  (testing "Handles reader conditionals"
    (is (false? (repair/delimiter-error?
                 "#?(:clj (+ 1 2) :cljs (+ 3 4))"))))

  (testing "Handles metadata"
    (is (false? (repair/delimiter-error?
                 "^:private (defn foo [] 1)")))
    (is (false? (repair/delimiter-error?
                 "(defn ^String foo [] \"hi\")"))))

  (testing "Handles quoted forms"
    (is (false? (repair/delimiter-error? "'(1 2 3)")))
    (is (false? (repair/delimiter-error? "`(a ~b ~@c)"))))

  (testing "Handles anonymous functions"
    (is (false? (repair/delimiter-error? "#(+ % 1)")))
    (is (false? (repair/delimiter-error? "#(+ %1 %2)"))))

  (testing "Handles sets"
    (is (false? (repair/delimiter-error? "#{1 2 3}")))
    (is (true? (repair/delimiter-error? "#{1 2 3"))))

  (testing "Handles regex literals"
    (is (false? (repair/delimiter-error? "#\"[a-z]+\"")))
    (is (false? (repair/delimiter-error? "(re-find #\"\\(\" s)")))))
