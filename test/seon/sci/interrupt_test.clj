(ns seon.sci.interrupt-test
  (:require [clojure.test :refer [deftest is testing]]
            [seon.sci.interrupt :as interrupt]))

(deftest time-limit-is-checked-at-every-entry
  (let [{interrupt-fn :interrupt-fn
         stop! ::interrupt/stop!
         record ::interrupt/record}
        (interrupt/start {::interrupt/time-limit-ms 10})]
    (try
      (Thread/sleep 25)
      (let [thrown (try
                     (interrupt-fn)
                     nil
                     (catch Throwable throwable throwable))]
        (is (interrupt/interrupted? thrown))
        (is (= :time (:seon.eval/outcome (record :error))))
        (is (= 1 (:seon.eval/fn-entries (record :error)))))
      (finally
        (stop!)))))

(deftest allocated-bytes-is-only-a-diagnostic
  (testing "the operation returns a measurement or the documented sentinel"
    (is (int? (interrupt/allocated-bytes)))))
