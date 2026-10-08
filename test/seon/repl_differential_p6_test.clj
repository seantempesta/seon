(ns seon.repl-differential-p6-test
  "P6 rows: the forgiving scan repairs an unbalanced evaluation and passes
  valid Clojure through unchanged, so each row answers its stated value."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.repl-differential-test :as differential]
            [seon.test-support :as support]))

(def rows
  "`[label code expected]`: the value of `code`'s last form through the MCP entrance."
  [["a-stray-closer-is-dropped" "(+ 1 2))" 3]
   ["an-unclosed-defn-closes-before-the-next-form" "(defn p6f [x]\n  (+ x 1)\n(p6f 1)" 2]
   ["a-mismatched-closer-closes-what-it-matches" "(count [1 2 (inc 2)))" 3]
   ["delimiters-in-strings-and-characters-are-not-repaired" "(str \"(]\" \\( \\])" "(](]"]
   ["fence-lines-around-code-are-skipped" "```clojure\n(+ 1 2)\n```" 3]
   ["a-discarded-form-reads-nothing" "#_ (/ 1 0) (+ 2 2)" 4]])

(deftest unbalanced-code-is-repaired-and-valid-code-unchanged
  (support/with-running-agent
   (fn [running agent-id _]
     (doseq [[label code expected] rows]
       (testing label
         (is (= expected (#'differential/answered code agent-id running))))))))
