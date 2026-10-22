(ns seon.profile-test
  "seon.profile: every contracted call counted exactly, one in 64 timed, read per epoch."
  (:refer-clojure :exclude [defn defn-])
  (:require [clojure.test :refer [deftest is testing]] [seon.contract :as contract :refer [defn defn-]]
            [seon.profile :as profile] [seon.repl :as repl] [seon.agent :as agent]))

(defn- leaf {:malli/schema [:=> [:cat :int] :int]} [x] (* x x))

(defn branch {:malli/schema [:=> [:cat :int] :int]} [n] (reduce + (map leaf (range n))))

(defn- cost-of
  "Each touched function's calls, by symbol, after running thunk in a fresh epoch."
  [thunk] (profile/begin!) (thunk) (into {} (map (juxt :var :calls)) (profile/touched)))

(deftest every-call-of-a-contracted-function-is-counted
  (when contract/on?
    (is (= {'seon.profile-test/branch 1 'seon.profile-test/leaf 1000} (cost-of #(branch 1000))))
    (testing "a new epoch starts from zero"
      (is (= {'seon.profile-test/leaf 3} (cost-of #(dotimes [i 3] (leaf i))))))
    (testing "an epoch that calls nothing touched nothing"
      (is (= {} (cost-of (fn [] nil)))))))

(deftest calls-is-the-epochs-total
  (when contract/on?
    (profile/begin!)
    (branch 200)
    (is (= 201 (profile/calls)))))

(deftest every-touched-function-has-an-estimated-time
  (when contract/on?
    (testing "the first call of an epoch is always timed, so a function called once has a time"
      (profile/begin!)
      (branch 50)
      (let [by (into {} (map (juxt :var :ms)) (profile/touched))]
        (is (pos? (by 'seon.profile-test/branch)))
        (is (every? #(>= % 0.0) (vals by)) "a call shorter than a clock read reads as 0, never negative")))
    (testing "the estimate (one call in 64 timed) is within 2x of the exact time in a loop (probe: 12-30% high)"
      (profile/begin!)
      (let [t0 (profile/now-ns) _ (dotimes [i 64000] (leaf i)) exact (/ (- (profile/now-ns) t0) 1e6)
            est (:ms (first (profile/touched)))]
        (is (<= (/ exact 2) est (* exact 2)) (str "exact " exact " ms, estimated " est " ms"))))))

(deftest top-orders-by-time-then-calls
  (is (= '[c a b] (mapv :var (profile/top [{:var 'a :calls 9 :ms 1.0} {:var 'b :calls 1 :ms 1.0} {:var 'c :calls 1 :ms 2.0}] 3))))
  (is (= 1 (count (profile/top [{:var 'a :calls 9 :ms 1.0} {:var 'b :calls 1 :ms 1.0}] 1)))))

(deftest an-evals-record-carries-its-costliest-functions
  (when contract/on?
    ;; the REPL's own handler in this process, as a scratch session (not agent mode: nothing is transacted)
    (let [answers (volatile! [])
          old @agent/state]
      (reset! agent/state {:n 0 :ns (the-ns 'user)})
      (try ((repl/handler) {"op" "eval" "code" "(seon.profile-test/branch 300)" :reply #(vswap! answers conj %)})
           (finally (reset! agent/state old)))
      (let [items (repl/fold @answers)
            fns (:fns (first items))]
        (is (= "8955050" (:printed (first items))) (pr-str items))
        (is (= {'seon.profile-test/branch 1 'seon.profile-test/leaf 300}
               (into {} (map (juxt :var :calls)) fns)))
        (is (= fns (:fns (:seon.cluster.eval/record (first (repl/record-tx :a "r" items nil))))))))))
