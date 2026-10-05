(ns my.examples-fixture
  "The examples agent's own namespace: its one test is what the agent's
  `(my.test/run)` example runs, as any agent runs its own namespace's tests."
  (:require [clojure.test :refer [deftest is]]))

(deftest ^{:seon.test/usage true} arithmetic
  (is (= 2 (+ 1 1))))
