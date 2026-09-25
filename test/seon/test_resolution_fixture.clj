(ns seon.test-resolution-fixture
  "A host test namespace that `seon.test-resolution-test` removes from the JVM
  while it stays registered as loaded; nothing else requires it."
  (:require [clojure.test :refer [deftest is]]))

(deftest resolves-after-removal
  (is (= 2 (inc 1))))
