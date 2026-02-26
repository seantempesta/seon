(ns seon.ctx.history-test
  (:require [clojure.test :refer [deftest is testing]]
            [seon.ctx.history :as history]))

(deftest map-diff-test
  (testing "identical maps produce empty delta"
    (let [d (history/map-diff {::history/before {:a 1 :b 2}
                               ::history/after {:a 1 :b 2}})]
      (is (= {} d))
      (is (history/empty-delta? {::history/delta d}))))

  (testing "added key"
    (let [d (history/map-diff {::history/before {:a 1}
                               ::history/after {:a 1 :b 2}})]
      (is (= {::history/added {:b 2}} d))))

  (testing "removed key"
    (let [d (history/map-diff {::history/before {:a 1 :b 2}
                               ::history/after {:a 1}})]
      (is (= {::history/retracted {:b 2}} d))))

  (testing "changed value"
    (let [d (history/map-diff {::history/before {:a 1 :b 2}
                               ::history/after {:a 1 :b 3}})]
      (is (= {::history/added {:b 3}
              ::history/retracted {:b 2}} d))))

  (testing "multiple changes"
    (let [d (history/map-diff {::history/before {:a 1 :b 2 :c 3}
                               ::history/after {:a 10 :c 3 :d 4}})]
      (is (= {::history/added {:a 10 :d 4}
              ::history/retracted {:a 1 :b 2}} d))))

  (testing "empty to populated"
    (let [d (history/map-diff {::history/before {}
                               ::history/after {:a 1 :b 2}})]
      (is (= {::history/added {:a 1 :b 2}} d))))

  (testing "populated to empty"
    (let [d (history/map-diff {::history/before {:a 1 :b 2}
                               ::history/after {}})]
      (is (= {::history/retracted {:a 1 :b 2}} d)))))

(deftest apply-delta-test
  (testing "apply added keys"
    (is (= {:a 1 :b 2 :c 3}
           (history/apply-delta {::history/state {:a 1 :b 2}
                                 ::history/delta {::history/added {:c 3}}}))))

  (testing "apply changed value"
    (is (= {:a 1 :b 3}
           (history/apply-delta {::history/state {:a 1 :b 2}
                                 ::history/delta {::history/added {:b 3}
                                                  ::history/retracted {:b 2}}}))))

  (testing "apply removal"
    (is (= {:a 1}
           (history/apply-delta {::history/state {:a 1 :b 2}
                                 ::history/delta {::history/retracted {:b 2}}}))))

  (testing "empty delta is identity"
    (is (= {:a 1 :b 2}
           (history/apply-delta {::history/state {:a 1 :b 2}
                                 ::history/delta {}})))))

(deftest reverse-delta-test
  (testing "reversing swaps added and retracted"
    (is (= {::history/added {:b 2}
            ::history/retracted {:b 3}}
           (history/reverse-delta {::history/delta {::history/added {:b 3}
                                                    ::history/retracted {:b 2}}}))))

  (testing "reversing addition becomes removal"
    (is (= {::history/retracted {:c 4}}
           (history/reverse-delta {::history/delta {::history/added {:c 4}}}))))

  (testing "reversing empty delta"
    (is (= {}
           (history/reverse-delta {::history/delta {}})))))

(deftest round-trip-test
  (testing "diff then apply reproduces the target state"
    (let [before {:a 1 :b 2 :c 3}
          after {:a 10 :c 3 :d 4}
          delta (history/map-diff {::history/before before
                                   ::history/after after})
          result (history/apply-delta {::history/state before
                                       ::history/delta delta})]
      (is (= after result))))

  (testing "diff then reverse-apply reproduces the original state"
    (let [before {:a 1 :b 2 :c 3}
          after {:a 10 :c 3 :d 4}
          delta (history/map-diff {::history/before before
                                   ::history/after after})
          reversed (history/reverse-delta {::history/delta delta})
          result (history/apply-delta {::history/state after
                                       ::history/delta reversed})]
      (is (= before result))))

  (testing "chain of deltas"
    (let [s0 {:x 0}
          s1 {:x 1 :y 10}
          s2 {:x 1 :y 20 :z 30}
          d01 (history/map-diff {::history/before s0 ::history/after s1})
          d12 (history/map-diff {::history/before s1 ::history/after s2})
          ;; Forward
          r1 (history/apply-delta {::history/state s0 ::history/delta d01})
          r2 (history/apply-delta {::history/state r1 ::history/delta d12})]
      (is (= s1 r1))
      (is (= s2 r2))
      ;; Backward
      (let [b1 (history/apply-delta {::history/state r2
                                     ::history/delta (history/reverse-delta {::history/delta d12})})
            b0 (history/apply-delta {::history/state b1
                                     ::history/delta (history/reverse-delta {::history/delta d01})})]
        (is (= s1 b1))
        (is (= s0 b0))))))
