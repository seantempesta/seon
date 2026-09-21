(ns seon.id-test
  (:require [clojure.test :refer [deftest is]]
            [seon.id :as id]))

(deftest data-shape-and-explicit-length-determine-identity
  (is (= "ba7816bf8f01" (id/id 'abc)))
  (is (= "ba7816bf" (id/id 'abc 8)))
  (is (not= (id/id 'abc) (id/id "abc")))
  (is (= (id/id ["turn" 2]) (id/evaluation "turn" 2)))
  (is (= (id/id ["turn" 2] 8) (id/digest 8 ["turn" 2])))
  (let [a (id/id) b (id/id)]
    (is (id/valid? id/default-length a))
    (is (id/valid? id/default-length b))
    (is (not= a b))))

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
