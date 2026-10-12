(ns seon.sentinel-test
  (:require [clojure.test :refer [deftest is]]
            [seon.sentinel :as sentinel]))

(defn- probe
  "A probe reply with request cost `ms` and settle stage `settle`."
  {:malli/schema [:=> [:cat number? number?] :map]}
  [ms settle]
  {:agent "fixture" :failed? false :window-ms ms :window-alloc-mb 1.5
   :request {:seon.cost/ms ms :seon.cost/alloc-mb 0.5 :seon.cost/calls 10 :seon.cost/reads 2
             :seon.cost/transactions 1 :seon.cost/stages {:settle settle :evaluate 1.0}}})

(deftest sentinel-summary-reduces-probes-to-baseline-figures
  (let [warm (mapv #(probe % (* 0.5 %)) [40.0 38.0 44.0 39.0 41.0])
        result (sentinel/summary {:warm warm
                                  :fresh [(probe 210.0 60.0)]
                                  :definition (probe 380.0 130.0)})]
    (is (= 40.0 (get-in result [:warm :median-ms])))
    (is (= [40.0 38.0 44.0 39.0 41.0] (get-in result [:warm :samples-ms])))
    (is (= [20.0 19.0 22.0 19.5 20.5] (get-in result [:warm :settle-ms])))
    (is (= 1 (get-in result [:warm :transactions])))
    (is (= 1 (count (:fresh result))))
    (is (= 210.0 (get-in result [:fresh 0 :ms])))
    (is (= 130.0 (get-in result [:defn :stages :settle])))))
