(ns seon.cost-test
  "Cost proofs (idea 16): the judge's laws on stated measurements, and seon.cost/proof end
  to end on real functions, red when the body does more than the work needs."
  (:refer-clojure :exclude [defn defn-])
  (:require [clojure.test :refer [deftest is testing]] [seon.contract :refer [defn defn-]]
            [seon.cost :as cost] [seon.profile :as profile]))

;; ---- the judge ------------------------------------------------------------------------

(def ^:private linear
  {:of 'seon.cost-test/one-pass :input 'seon.cost-test/ints :n "integers" :work "one pass" :growth :linear
   :per-n {:ns 100.0 :bytes 50.0 :calls 1.0} :fixed {:calls 1.0} :sizes [100 1000 10000]})

(defn- rows
  "Rows of estimate e at its sizes, measured as (m n) per dimension."
  {:malli/schema [:=> [:cat :seon.cost/estimate :seon.host/fn] [:vector :seon.cost/row]]}
  [e m] (vec (for [n (:sizes e)] {:n n :measured (m n) :expected (cost/expected e cost/units n)})))

(deftest work-is-the-growth-at-n
  (is (= [1.0 1000.0 1000000.0] (mapv #(cost/work % 1000) [:constant :linear :quadratic])))
  (is (< 9.96 (cost/work :log 1000) 9.97))
  (is (< 9965.0 (cost/work :n-log-n 1000) 9966.0)))

(deftest exponent-is-the-log-log-slope
  (is (< 0.99 (cost/exponent [[10 30] [100 300] [1000 3000]]) 1.01))
  (is (< 1.99 (cost/exponent [[10 1] [100 100] [1000 10000]]) 2.01))
  (is (= 0.0 (cost/exponent [[10 5]])) "one point has no slope")
  (is (< 0.99 (cost/exponent [[10 0] [100 100] [1000 1000]]) 1.01) "a zero measure is left out"))

(deftest expected-is-fixed-plus-per-unit-times-work
  (is (= {:ns 100000.0 :bytes 50000.0 :calls 1001.0} (cost/expected linear cost/units 1000)))
  (testing ":ops are priced by the unit table; :per-n wins over them"
    (let [u {:a/op {:ns 10.0 :bytes 4.0 :what "a"}}
          e (assoc linear :ops {:a/op 2.0} :per-n {:calls 1.0})]
      (is (= {:ns 20.0 :bytes 8.0 :calls 1.0} (cost/per-unit e u)))
      (is (= {:ns 200.0 :bytes 80.0 :calls 11.0} (cost/expected e u 10))))))

(deftest within-the-estimate-is-green
  (let [v (cost/judge linear (rows linear (fn [n] {:ns (* 60.0 n) :bytes (* 50.0 n) :calls (+ 1.0 n)})))]
    (is (:ok? v))
    (is (= [] (:over v) (:steeper v) (:under v) (:unmeasured v)))))

(deftest a-size-over-its-slack-is-red
  (let [v (cost/judge linear (rows linear (fn [n] {:ns (* 60.0 n) :bytes (* 50.0 n) :calls (+ 2.0 n)})))]
    (is (not (:ok? v)))
    (is (= [:calls] (distinct (map :dim (:over v)))) "calls are exact: one more than stated is over")
    (is (empty? (:steeper v))))
  (testing "wall time has 3x slack"
    (is (:ok? (cost/judge linear (rows linear (fn [n] {:ns (* 290.0 n) :bytes (* 50.0 n) :calls (+ 1.0 n)})))))
    (is (not (:ok? (cost/judge linear (rows linear (fn [n] {:ns (* 310.0 n) :bytes (* 50.0 n) :calls (+ 1.0 n)}))))))))

(deftest a-second-pass-is-steeper-even-inside-the-slack
  ;; quadratic bytes that stay under the linear estimate at every size: only the growth sees them
  (let [e (assoc linear :per-n {:bytes 100.0} :fixed {} :sizes [10 100 1000])
        v (cost/judge e (rows e (fn [n] {:bytes (* 0.09 n n)})))]
    (is (empty? (:over v)))
    (is (= [:bytes] (map :dim (:steeper v))))
    (is (< 1.9 (:exponent (first (:steeper v))) 2.1))
    (is (not (:ok? v)))))

(deftest n-log-n-under-a-linear-estimate-is-not-steeper
  (let [e (assoc linear :per-n {:ns 1000.0} :fixed {})]
    (is (empty? (:steeper (cost/judge e (rows e (fn [n] {:ns (cost/work :n-log-n n)}))))))))

(deftest a-loose-estimate-is-a-finding-not-red
  (let [v (cost/judge linear (rows linear (fn [n] {:ns (* 10.0 n) :bytes (* 50.0 n) :calls (+ 1.0 n)})))]
    (is (:ok? v))
    (is (= [{:dim :ns :n 10000 :ratio 0.1}] (mapv #(update % :ratio (fn [r] (/ (Math/round (* r 100)) 100.0))) (:under v))))))

(deftest a-stated-dimension-never-measured-is-named
  (let [v (cost/judge linear (rows linear (fn [n] {:ns (* 60.0 n) :bytes (* 50.0 n)})))]
    (is (= [:calls] (:unmeasured v)))))

;; ---- proofs end to end ----------------------------------------------------------------

(defn- seen
  "One contracted call per element: the unit the call counts below see."
  {:malli/schema [:=> [:cat :map :int] :map]}
  [m x] (assoc m x true))

(defn ints
  "The input of size n: n distinct integers."
  {:malli/schema [:=> [:cat pos-int?] [:tuple [:vector :int]]]}
  [n] [(vec (range n))])

(defn one-pass
  "The distinct integers of xs as a set of keys: one pass, one call of seen each."
  {:malli/schema [:=> [:cat [:vector :int]] :map]}
  [xs] (reduce seen {} xs))

(defn rescanning
  "The same answer, but each element first scans the elements before it: a pass per element."
  {:malli/schema [:=> [:cat [:vector :int]] :map]}
  [xs] (reduce (fn [m i] (if (some #(= % (nth xs i)) (subvec xs 0 i)) m (seen m (nth xs i)))) {} (range (count xs))))

(def ^:private estimate
  {:of 'seon.cost-test/one-pass :input 'seon.cost-test/ints :n "integers in the input"
   :work "one pass: per integer one seen call (one persistent assoc)" :growth :linear
   :ops {:seq/reduce 1.0 :fn/armed-call 1.0 :map/assoc 1.0} :per-n {:calls 1.0} :fixed {:calls 1.0}
   :sizes [100 1000 10000]})

(deftest a-one-pass-function-meets-its-estimate
  (let [{:keys [rows verdict]} (cost/run estimate)]
    (is (= [100 1000 10000] (mapv :n rows)))
    (when seon.contract/on?
      (is (= [101.0 1001.0 10001.0] (mapv #(get-in % [:measured :calls]) rows)) "calls are exact"))
    (is (:ok? verdict) (cost/explain estimate rows verdict))))

(deftest a-scan-inside-the-loop-is-red-and-says-why
  (let [e (assoc estimate :of 'seon.cost-test/rescanning)
        {:keys [rows verdict]} (cost/run e)
        text (cost/explain e rows verdict)]
    (is (not (:ok? verdict)))
    (is (< (count rows) 3) "it stops at the first size over its estimate")
    (is (re-find #"OVER: ns" text))
    (is (re-find #"one pass" text) "the explanation restates the work the data needs")))

(deftest the-unit-table-is-current
  ;; every row of the table measured again at 10k units: within 3x of its ns, its bytes within
  ;; 1.25x (allocation is counted); a drifted row means the estimates priced by it are stale
  (doseq [[k {:keys [ns bytes]}] cost/units
          :let [m (cost/measure-unit k 10000)]]
    (is (<= (/ ns 3.0) (:ns m) (* ns 3.0)) (str k " ns: table " ns ", measured " (:ns m)))
    (is (<= (- (/ bytes 1.25) 1.0) (:bytes m) (+ (* bytes 1.25) 1.0)) (str k " bytes: table " bytes ", measured " (:bytes m)))))
