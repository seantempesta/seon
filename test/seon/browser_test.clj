(ns seon.browser-test
  "Unit tests for seon.browser retry, backoff, and load-detection helpers."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.browser]))

(deftest retry-helpers-test
  (testing "next-retry-delay grows then caps at 2000"
    (is (= [250 500 1000 2000 2000 2000] (mapv seon.browser/next-retry-delay [0 1 2 3 4 10]))))
  (testing "loaded? detects blank vs rendered document"
    (is (seon.browser/loaded? {:app true}))
    (is (seon.browser/loaded? {:children 3}))
    (is (not (seon.browser/loaded? {:children 0 :readyState "complete"})))
    (is (not (seon.browser/loaded? {:error "boom"})))
    (is (not (seon.browser/loaded? nil))))
  (testing "retry-exhausted? bounds by attempts and deadline"
    (is (not (seon.browser/retry-exhausted? 3 12 1000 30000)))
    (is (seon.browser/retry-exhausted? 12 12 1000 30000))
    (is (seon.browser/retry-exhausted? 3 12 30000 30000))))
