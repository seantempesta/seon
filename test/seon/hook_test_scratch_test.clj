(ns seon.hook-test-scratch-test
  (:require [clojure.test :refer [deftest is testing]]
            [seon.hook-test-scratch :as sut]))

(deftest add-numbers-test
  (testing "adds two positive numbers"
    (is (= 5 (sut/add-numbers 2 3))))
  (testing "adds negative numbers"
    (is (= -1 (sut/add-numbers 2 -3))))
  (testing "adds zero"
    (is (= 7 (sut/add-numbers 7 0)))))
