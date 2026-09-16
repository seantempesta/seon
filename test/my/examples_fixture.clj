(ns ^{:seon.test/fixture
      "Executable-example material: this namespace's one test exists so the
       usage-example machinery has a statically indexed row to assert over,
       not to gate anything."}
  my.examples-fixture
  "One statically indexed test for the agent API's executable examples."
  (:require [clojure.test :refer [deftest is]]))

(deftest ^{:seon.test/usage true} arithmetic
  (is (= 2 (+ 1 1))))
