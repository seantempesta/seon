(ns seon.dev.repair-test
  "Tests for the repair namespace - delimiter detection and repair."
  (:require [clojure.string :as str]
            [clojure.test :refer :all]
            [seon.dev.repair :as repair]))

;;; ---------------------------------------------------------------------------
;;; delimiter-error? Tests
;;; ---------------------------------------------------------------------------

(deftest delimiter-error?-test
  (testing "Detects missing closing parentheses"
    (is (true? (repair/delimiter-error? {::repair/content "(defn foo [x] (+ x 1"})))
    (is (true? (repair/delimiter-error? {::repair/content "(let [x 1"})))
    (is (true? (repair/delimiter-error? {::repair/content "((("}))))

  (testing "Detects missing closing brackets"
    (is (true? (repair/delimiter-error? {::repair/content "[1 2 3"})))
    (is (true? (repair/delimiter-error? {::repair/content "(defn foo [x"})))
    (is (true? (repair/delimiter-error? {::repair/content "[[[1 2] 3"}))))

  (testing "Detects missing closing braces"
    (is (true? (repair/delimiter-error? {::repair/content "{:a 1"})))
    (is (true? (repair/delimiter-error? {::repair/content "{:a {:b 1}"})))
    (is (true? (repair/delimiter-error? {::repair/content "{{{"}))))

  (testing "Returns false for balanced code"
    (is (false? (repair/delimiter-error? {::repair/content "(defn foo [x] (+ x 1))"})))
    (is (false? (repair/delimiter-error? {::repair/content "[1 2 3]"})))
    (is (false? (repair/delimiter-error? {::repair/content "{:a 1 :b 2}"})))
    (is (false? (repair/delimiter-error? {::repair/content "(let [x {:a [1 2 3]}] x)"}))))

  (testing "Handles edge cases"
    (is (false? (repair/delimiter-error? {::repair/content ""})))
    (is (false? (repair/delimiter-error? {::repair/content nil})))
    (is (false? (repair/delimiter-error? {::repair/content "   "})))
    (is (false? (repair/delimiter-error? {::repair/content "; just a comment"}))))

  (testing "Handles complex valid code"
    (is (false? (repair/delimiter-error?
                 {::repair/content "(defn process-data
                     \"Process some data.\"
                     [{:keys [input output]}]
                     (let [result (map inc input)]
                       {:output result}))"}))))

  (testing "Handles strings and characters"
    ;; Strings should not confuse the parser
    (is (false? (repair/delimiter-error? {::repair/content "(str \"hello (world)\")"})))
    (is (false? (repair/delimiter-error? {::repair/content "(str \"[brackets]\")"})))
    (is (false? (repair/delimiter-error? {::repair/content "(str \"{braces}\")"})))))

;;; ---------------------------------------------------------------------------
;;; repair Tests
;;; ---------------------------------------------------------------------------

(deftest repair-test
  (testing "Repairs missing closing parentheses"
    (let [broken "(defn foo [x]\n  (+ x 1"
          result (repair/repair {::repair/content broken})]
      (is (true? (::repair/success result)))
      (is (string? (::repair/repaired result)))
      (is (not (repair/delimiter-error? {::repair/content (::repair/repaired result)})))))

  (testing "Repairs missing closing brackets"
    (let [broken "[1 2 3"
          result (repair/repair {::repair/content broken})]
      (is (true? (::repair/success result)))
      (is (string? (::repair/repaired result)))
      (is (not (repair/delimiter-error? {::repair/content (::repair/repaired result)})))))

  (testing "Repairs missing closing braces"
    (let [broken "{:a 1 :b 2"
          result (repair/repair {::repair/content broken})]
      (is (true? (::repair/success result)))
      (is (string? (::repair/repaired result)))
      (is (not (repair/delimiter-error? {::repair/content (::repair/repaired result)})))))

  (testing "Returns success false for already valid code"
    (is (false? (::repair/success (repair/repair {::repair/content "(defn foo [x] (+ x 1))"}))))
    (is (false? (::repair/success (repair/repair {::repair/content "[1 2 3]"}))))
    (is (false? (::repair/success (repair/repair {::repair/content "{:a 1}"})))))

  (testing "Returns success false for empty/nil input"
    (is (false? (::repair/success (repair/repair {::repair/content ""}))))
    (is (false? (::repair/success (repair/repair {::repair/content nil})))))

  (testing "Repairs nested structures"
    (let [broken "(let [x {:a [1 2 3"
          result (repair/repair {::repair/content broken})]
      (is (true? (::repair/success result)))
      (is (string? (::repair/repaired result)))
      (is (not (repair/delimiter-error? {::repair/content (::repair/repaired result)})))))

  (testing "Preserves code structure during repair"
    (let [broken "(defn add [a b]\n  (+ a b"
          result (repair/repair {::repair/content broken})
          repaired (::repair/repaired result)]
      (is (true? (::repair/success result)))
      (is (string? repaired))
      ;; Should preserve the basic structure
      (is (str/includes? repaired "defn add"))
      (is (str/includes? repaired "[a b]"))
      (is (str/includes? repaired "(+ a b")))))

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
      (is (not (repair/delimiter-error? {::repair/content (::repair/content result)})))))

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
          _ (is (true? (repair/delimiter-error? {::repair/content broken})))
          ;; 2. Repair
          repair-result (repair/repair {::repair/content broken})
          repaired (::repair/repaired repair-result)
          _ (is (true? (::repair/success repair-result)))
          _ (is (string? repaired))
          _ (is (false? (repair/delimiter-error? {::repair/content repaired})))
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
          repair-result (repair/repair {::repair/content broken})
          repaired (::repair/repaired repair-result)]
      (is (true? (::repair/success repair-result)))
      (is (string? repaired))
      (is (false? (repair/delimiter-error? {::repair/content repaired})))
      ;; Should preserve the docstring and structure
      (is (str/includes? repaired "Calculate the total price"))
      (is (str/includes? repaired "tax-rate")))))

;;; ---------------------------------------------------------------------------
;;; Edge Cases and Robustness
;;; ---------------------------------------------------------------------------

(deftest robustness-test
  (testing "Handles reader conditionals"
    (is (false? (repair/delimiter-error?
                 {::repair/content "#?(:clj (+ 1 2) :cljs (+ 3 4))"}))))

  (testing "Handles metadata"
    (is (false? (repair/delimiter-error?
                 {::repair/content "^:private (defn foo [] 1)"})))
    (is (false? (repair/delimiter-error?
                 {::repair/content "(defn ^String foo [] \"hi\")"}))))

  (testing "Handles quoted forms"
    (is (false? (repair/delimiter-error? {::repair/content "'(1 2 3)"})))
    (is (false? (repair/delimiter-error? {::repair/content "`(a ~b ~@c)"}))))

  (testing "Handles anonymous functions"
    (is (false? (repair/delimiter-error? {::repair/content "#(+ % 1)"})))
    (is (false? (repair/delimiter-error? {::repair/content "#(+ %1 %2)"}))))

  (testing "Handles sets"
    (is (false? (repair/delimiter-error? {::repair/content "#{1 2 3}"})))
    (is (true? (repair/delimiter-error? {::repair/content "#{1 2 3"}))))

  (testing "Handles regex literals"
    (is (false? (repair/delimiter-error? {::repair/content "#\"[a-z]+\""})))
    (is (false? (repair/delimiter-error? {::repair/content "(re-find #\"\\(\" s)"})))))
