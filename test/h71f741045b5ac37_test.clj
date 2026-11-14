(ns h71f741045b5ac37-test (:require [clojure.test :refer [deftest is]]))

(deftest ^{:seon.test/estimate {:ticks 100000}} t (is (= 2 (inc 1))))
