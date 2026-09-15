(ns my.examples-fixture
  "One statically indexed test for the agent API's executable examples."
  (:require [clojure.test :refer [deftest is]]))

(deftest ^{:seon.test/usage true} arithmetic
  (is (= 2 (+ 1 1))))
