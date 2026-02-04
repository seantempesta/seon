(ns seon.dev.suggestions-test
  "Tests for the symbol suggestions module."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.dev.suggestions :as suggestions]))

(deftest levenshtein-distance-test
  (testing "Basic edit distance calculations"
    (is (= 0 (#'suggestions/levenshtein-distance "foo" "foo"))
        "Same strings have distance 0")
    (is (= 1 (#'suggestions/levenshtein-distance "foo" "fop"))
        "One substitution")
    (is (= 1 (#'suggestions/levenshtein-distance "foo" "fooo"))
        "One insertion")
    (is (= 1 (#'suggestions/levenshtein-distance "foo" "fo"))
        "One deletion")
    (is (= 3 (#'suggestions/levenshtein-distance "kitten" "sitting"))
        "Classic example"))

  (testing "Edge cases"
    (is (= 3 (#'suggestions/levenshtein-distance "" "abc"))
        "Empty to non-empty")
    (is (= 3 (#'suggestions/levenshtein-distance "abc" ""))
        "Non-empty to empty")
    (is (= 0 (#'suggestions/levenshtein-distance "" ""))
        "Both empty")))

(deftest suggest-symbol-test
  (testing "Finds close matches (single-char substitution)"
    (let [result (suggestions/suggest-symbol
                  {::suggestions/target "mop"
                   ::suggestions/candidates ["map" "mapv" "mapcat" "filter"]})]
      (is (= "map" (::suggestions/suggestion result)))
      (is (= 1 (::suggestions/distance result)))))

  (testing "Finds matches with transposition typos (longer strings)"
    (let [result (suggestions/suggest-symbol
                  {::suggestions/target "undefiend"
                   ::suggestions/candidates ["undefined" "define" "defn"]})]
      (is (= "undefined" (::suggestions/suggestion result)))
      (is (= 2 (::suggestions/distance result)))))

  (testing "Returns nil for no close match"
    (let [result (suggestions/suggest-symbol
                  {::suggestions/target "xyz"
                   ::suggestions/candidates ["abc" "def" "ghi"]})]
      (is (nil? (::suggestions/suggestion result)))
      (is (nil? (::suggestions/distance result)))))

  (testing "Handles empty inputs"
    (is (nil? (::suggestions/suggestion
               (suggestions/suggest-symbol
                {::suggestions/target ""
                 ::suggestions/candidates ["foo" "bar"]}))))
    (is (nil? (::suggestions/suggestion
               (suggestions/suggest-symbol
                {::suggestions/target "foo"
                 ::suggestions/candidates []})))))

  (testing "Respects max-distance override"
    (let [result (suggestions/suggest-symbol
                  {::suggestions/target "mpa"
                   ::suggestions/candidates ["map"]
                   ::suggestions/max-distance 0})]
      (is (nil? (::suggestions/suggestion result))
          "Distance 1 exceeds max-distance 0"))))

(deftest enrich-findings-test
  (testing "Enriches unresolved-symbol findings"
    (let [findings [{:type :unresolved-symbol
                     :message "Unresolved symbol: mop"
                     :row 1 :col 5 :level :error}]
          result (suggestions/enrich-findings
                  {::suggestions/findings findings
                   ::suggestions/available-symbols ["map" "mapv" "filter"]})]
      (is (= "map" (::suggestions/suggestion (first (::suggestions/findings result)))))
      (is (= 1 (::suggestions/distance (first (::suggestions/findings result)))))))

  (testing "Enriches unresolved-var findings"
    (let [findings [{:type :unresolved-var
                     :message "Unresolved var: ns/reduc"
                     :row 1 :col 5 :level :error}]
          result (suggestions/enrich-findings
                  {::suggestions/findings findings
                   ::suggestions/available-symbols ["reduce" "reducer" "reductions"]})]
      (is (= "reduce" (::suggestions/suggestion (first (::suggestions/findings result)))))))

  (testing "Leaves other findings unchanged"
    (let [findings [{:type :invalid-arity
                     :message "Wrong arity"
                     :row 1 :col 5 :level :error}]
          result (suggestions/enrich-findings
                  {::suggestions/findings findings})]
      (is (nil? (::suggestions/suggestion (first (::suggestions/findings result)))))))

  (testing "Uses default symbols when none provided"
    (let [findings [{:type :unresolved-symbol
                     :message "Unresolved symbol: reducee"
                     :row 1 :col 5 :level :error}]
          result (suggestions/enrich-findings
                  {::suggestions/findings findings})]
      (is (= "reduce" (::suggestions/suggestion (first (::suggestions/findings result))))
          "Should find 'reduce' in default Clojure core symbols"))))
