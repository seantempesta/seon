(ns demo.math-test (:require [clojure.test :refer [deftest is]] [my.test :as t] [datahike.api :as d]))

(deftest half-halves-a-number
  (is (= 3 (demo.math/half 6)))
  (is (= 1/2 (demo.math/half 1)))
  (is (= 5/2 (demo.math/half 5)))
  (is (= -5/2 (demo.math/half -5)))
  (is (thrown? Exception (demo.math/half 1.5))))

(deftest half-is-recorded-with-its-contract
  (let [db (t/with (t/world)
                   (t/code 'demo.math "(ns demo.math)\n(defn half {:malli/schema [:=> [:cat :int] :int]} [n] (/ n 2))\n"))]
    (is (= 4 ((requiring-resolve 'demo.math/half) 8)))
    (is (string? (:var/contract (d/entity db [:var/name 'demo.math/half]))))))
