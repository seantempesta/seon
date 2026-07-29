(ns seon.test-runner-failure-fixture
  "Selected explicitly by runner tests; not discovered by the full gate."
  (:require [clojure.test :refer [deftest is]]))

(deftest passing-example
  (is (= 4 (+ 2 2))))

(deftest failing-example
  (is (= 5 (+ 2 2)) "the deliberate broken-test evidence"))
