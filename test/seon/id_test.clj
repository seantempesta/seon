(ns seon.id-test
  (:require [clojure.test :refer [deftest is]]
            [seon.id :as id]))

(deftest an-evaluation-id-is-stable-short-and-a-symbol
  ;; THE CLASS: every stable identifier comes from the one derivation.
  (let [a (id/evaluation "cluster-default" "turn-1" 0)
        b (id/evaluation "cluster-default" "turn-1" 0)
        c (id/evaluation "cluster-default" "turn-1" 1)
        d (id/evaluation "cluster-other" "turn-1" 0)]
    (is (= a b) "same parts, same id, every time")
    (is (distinct? a c d) "ordinal and branch each change the id")
    (is (id/valid? 12 a) "twelve lowercase hex characters")
    (is (= (symbol "result" (str "e" a)) (id/symbol-in "result" \e a))
        "the handle leads with a letter")
    (is (= (id/symbol-in "result" \e a)
           (read-string (str (id/symbol-in "result" \e a))))
        "the handle survives the reader")))
