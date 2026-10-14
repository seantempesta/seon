(ns scratch.wbfe2e-test
  (:require [clojure.test :refer [deftest is]]))

(defn plus-two "Add two." {:malli/schema [:=> [:cat :int] :int]} [x] (+ x 2))

(deftest plus-two-adds-two (is (= 3 (plus-two 1))))
