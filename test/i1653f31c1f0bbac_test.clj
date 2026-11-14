(ns i1653f31c1f0bbac-test (:require [clojure.test :refer [deftest is]]))

(deftest ^{:seon.test/estimate {:ticks 100000}} t (is (= 2 (inc 1))))
