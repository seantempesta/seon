(ns next.smoke-test
  "The scaffold's one deftest: konserve's fasl codec (our fork's jolt/main) round-trips a value."
  (:require [clojure.test :refer [deftest is]] [konserve.jolt.fasl :as fasl]))

(deftest fasl-round-trip
  (let [v {:a 1 :b [1 2.5 "three" :four 'five] :c #{nil true}}]
    (is (= v (fasl/decode (fasl/encode v))))))
