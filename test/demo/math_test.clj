(ns demo.math-test (:require [clojure.test :refer [deftest is]] [my.test :as t] [datahike.api :as d]))

(deftest double-is-recorded-and-doubles
  (let [db (t/with (t/world)
                   (t/code 'demo.math "(ns demo.math)\n(defn double {:malli/schema [:=> [:cat :int] :int]} [n] (* 2 n))\n"))]
    (is (= "[:=> [:cat :int] :int]" (:var/contract (d/entity db [:var/name 'demo.math/double]))))
    (is (= 8 ((requiring-resolve 'demo.math/double) 4)))))
