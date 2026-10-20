(ns r5.judge-test
  "R5: root's judge as an observer, and a merge train's combined run with its bisection, each
  step-fn called as a plain function on data (no graph)."
  (:require [clojure.test :refer [deftest is testing]] [seon.reconcile :as r]))

(defn- writes [outs] (mapcat #(get-in % [:req :req :tx-data]) outs))

(deftest the-judge-records-the-end-the-agent-wrote
  (let [st (r/judge-proc {})
        end {:outcome :green :turns 1 :model-calls 1 :tokens 9 :ms 1.0 :basis-t 7 :results {'app.proofs/a-test :pass}
             :uncontracted #{} :unmerged [] :changed #{"[:var app.m/f]"}}
        [st o] (r/judge-proc st :ended {:attempt "t/a#1" :agent :w1 :branch :b :task "t/a" :end {:end end :conflicts []}})
        tx (writes (:to-api o))]
    (is (= 1 (:judged st)))
    (is (= [:db/cas [:seon.attempt/id "t/a#1"] :seon.attempt/status :running :ended] (first tx)))
    (is (= #{"[:var app.m/f]"} (set (:seon.attempt/changed (second tx)))))
    (is (= 7 (:seon.attempt/basis-t (second tx)))))
  (testing "an agent that sent no end died"
    (let [[_ o] (r/judge-proc (r/judge-proc {}) :ended {:attempt "t/a#1" :agent :w1 :branch :b :task "t/a"})]
      (is (= :dead (nth (first (writes (:to-api o))) 4))))))

(defn- train-state
  "A reconciler state holding one combined run over the members named."
  [run members]
  (let [ms (mapv (fn [i] {:attempt (str "t/m" i "-merge#1") :task (str "t/m" i "-merge")}) members)
        all (mapv (fn [i] {:attempt (str "t/m" i "-merge#1") :task (str "t/m" i "-merge")}) (range 8))]
    (assoc (r/reconciler-proc {:boot "abcdefgh-boot" :provider {:kind :scripted :script 'seon.fixtures/reply}})
           :idx {:attempts (into {} (for [{:keys [attempt]} all] [attempt {:seon.attempt/env {:branch (keyword attempt)}}]))}
           :runs {run {:act {:op :merge-train :attempt (:attempt (first all)) :members all} :members ms}})))

(deftest a-red-combined-run-bisects-and-a-green-one-lands
  (testing "green: the members run land in one merge train, the rest are behind"
    (let [[st o] (r/reconciler-proc (train-state "ci/x" (range 4)) :ci {:ci "ci/x" :end {:green true :results {}}})
          req (:req (first (:to-api o)))]
      (is (= :merge-train (get-in req [:req :op])))
      (is (= 4 (count (get-in req [:req :branches]))))
      (is (= 4 (count (nth (val (first (:pending st))) 3))) "the four left out are behind once it lands")))
  (testing "red: the first half runs next (O(log n) runs)"
    (let [[st o] (r/reconciler-proc (train-state "ci/x" (range 8)) :ci {:ci "ci/x" :end {:green false :results {'app.proofs/b-test :fail}}})
          req (:req (first (:to-api o)))]
      (is (= :spawn (:op req)))
      (is (= 4 (count (get-in req [:opts :work :ci :branches]))))
      (is (= 1 (count (:runs st))))))
  (testing "red with two: the first member alone lands (its own run was main plus its change)"
    (let [[_ o] (r/reconciler-proc (train-state "ci/x" (range 2)) :ci {:ci "ci/x" :end {:green false}})
          req (:req (first (:to-api o)))]
      (is (= [:t/m0-merge#1] (get-in req [:req :branches])))))
  (testing "an agent that sent no end is red"
    (let [[_ o] (r/reconciler-proc (train-state "ci/x" (range 8)) :ci {:ci "ci/x"})]
      (is (= :spawn (get-in (first (:to-api o)) [:req :op]))))))
