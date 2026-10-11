(ns seon.interpreted-census-test
  (:require [clojure.test :refer [deftest is]]
            [seon.interpreted-census :as census]))

(deftest ^{:seon/tasks #{'seon.interpreted-census-test/a-refused-census-batch-is-recorded-as-refused}}
  a-refused-census-batch-is-recorded-as-refused
  (let [lines (census/batch-lines {'a/t :red 'a/u :green} ['a/t 'a/u]
                                  {:refusal "refused" :results [] :unrun []})]
    (is (= [{:seon.test/sym 'a/t :class :red :refusal "refused"}
            {:seon.test/sym 'a/u :class :green :refusal "refused"}]
           lines))))

(deftest an-answered-census-batch-records-each-member
  (is (= [{:seon.test/sym 'a/t :class :red :seon.test/pass-count 1}]
         (census/batch-lines {'a/t :red} ['a/t]
                             {:results [{:seon.test/sym 'a/t :seon.test/pass-count 1}]}))))
