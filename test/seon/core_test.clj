(ns seon.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [seon.core :as core])
  (:import [clojure.lang ExceptionInfo]))

(deftest check-port-free-test
  (testing "port that is not in use does not throw"
    ;; Port 19999 is extremely unlikely to be in use
    (is (nil? (#'core/check-port-free! 19999 "Test"))))

  (testing "phase-1-keys is a vector of qualified keywords"
    (let [keys @#'core/phase-1-keys]
      (is (vector? keys))
      (is (every? qualified-keyword? keys))
      (is (pos? (count keys))))))
