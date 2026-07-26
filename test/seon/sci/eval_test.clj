(ns seon.sci.eval-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [sci.core :as sci]
            [seon.sci.eval :as eval]
            [seon.sci.interrupt :as interrupt]))

(deftest sci-reader-refuses-read-eval-before-host-code-runs
  (let [path (str (System/getProperty "user.dir")
                  "/tmp/seon-sci-read-eval-"
                  (random-uuid)
                  ".txt")
        file (io/file path)
        source
        (format
         "#=(clojure.core/spit %s \"read-eval escaped SCI\")"
         (pr-str path))]
    (.delete file)
    (eval/open! {::eval/concurrency 1})
    (let [result (eval/evaluate
                  {::eval/source source
                   ::interrupt/time-limit-ms 1000})]
      (is (eval/error? (::eval/value result)))
      (is (= :error
             (get-in result [::eval/record :seon.eval/outcome])))
      (is (false? (.exists file))))))

(deftest time-limit-trips-below-the-allocation-sample-cadence
  (eval/open! {::eval/concurrency 1})
  (let [result
        (eval/evaluate
         {::eval/source
          "(let [b (loop [v 2N i 0] (if (< i 18) (recur (* v v) (inc i)) v))] (mod (apply * (repeat 300 b)) 7))"
          ::interrupt/time-limit-ms 10})
        record (::eval/record result)]
    (testing "the next function-body entrance observes the expired time flag"
      (is (eval/error? (::eval/value result)))
      (is (= :time (:seon.eval/outcome record)))
      (is (< (:seon.eval/fn-entries record) 1024)))))

(deftest agent-code-cannot-catch-the-interrupt-marker
  (eval/open! {::eval/concurrency 1})
  (let [result
        (eval/evaluate
         {::eval/source
          "(try (loop [] (recur)) (catch Throwable _ :swallowed))"
          ::interrupt/time-limit-ms 10})]
    (is (eval/error? (::eval/value result)))
    (is (= :time
           (get-in result [::eval/record :seon.eval/outcome])))))

(deftest ordinary-source-produces-a-value-and-diagnostics
  (eval/open! {::eval/concurrency 1})
  (let [result
        (eval/evaluate
         {::eval/source "(reduce + (range 10))"
          ::interrupt/time-limit-ms 1000})]
    (is (= 45 (::eval/value result)))
    (is (= :ok (get-in result [::eval/record :seon.eval/outcome])))
    (is (pos? (get-in result [::eval/record :seon.eval/fn-entries])))))

(deftest blocked-host-call-consumes-only-its-own-capacity
  (let [release (promise)
        base-ctx
        (sci/init
         {:namespaces
          {'user {'block (fn [] @release)}}})]
    (eval/open! {::eval/concurrency 2})
    (let [blocked
          (eval/evaluate
           {::eval/source "(user/block)"
            ::eval/base-ctx base-ctx
            ::interrupt/time-limit-ms 10})]
      (is (= :time
             (get-in blocked [::eval/record :seon.eval/outcome])))
      (is (= 1 (eval/available))
          "the still-running platform task keeps exactly one permit")
      (is (= 3
             (::eval/value
              (eval/evaluate
               {::eval/source "(+ 1 2)"
                ::eval/base-ctx base-ctx
                ::interrupt/time-limit-ms 1000})))
          "unrelated capacity remains usable")
      (deliver release true)
      (loop [attempt 0]
        (when (and (< attempt 10000)
                   (not= 2 (eval/available)))
          (Thread/onSpinWait)
          (recur (inc attempt))))
      (is (= 2 (eval/available))
          "the permit returns only when the blocked call really exits"))))
