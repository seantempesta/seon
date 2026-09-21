(ns seon.test.duration-test
  (:require [clojure.edn :as edn]
            [clojure.test :as test :refer [deftest is]]
            [seon.id :as id]
            [seon.test.runner :as runner]))

(deftest duration-bound-requires-both-the-allowance-and-its-reason
  (let [options (#'runner/report-options)
        ordinary (:seon.test/time-limit-ms options)
        namespace-name (symbol (str "duration.probe." (id/id)))
        namespace-object (create-ns namespace-name)
        probe (intern namespace-object 'probe (fn []))
        elapsed (double (inc ordinary))
        long-limit (* 2 ordinary)]
    (try
      (doseq [declaration [{} {:seon.test/long-ms long-limit}
                           {:seon.test/long "Explained work without an allowance"}
                           {:seon.test/long " " :seon.test/long-ms long-limit}]]
        (alter-meta! probe #(merge (dissoc % :seon.test/long :seon.test/long-ms) declaration))
        (let [[failure] (#'runner/duration-failures options probe elapsed ordinary)]
          (is (= :fail (:type failure)))
          (is (= {:seon.test/time-limit-ms ordinary} (:expected failure)))
          (is (= {:seon.test/elapsed-ms elapsed} (:actual failure)))))
      (alter-meta! probe assoc :seon.test/long "Two bounded database operations"
                   :seon.test/long-ms long-limit)
      (is (empty? (#'runner/duration-failures options probe (double long-limit) ordinary)))
      (is (= long-limit (get-in (first (#'runner/duration-failures options probe (double (inc long-limit)) ordinary))
                               [:expected :seon.test/time-limit-ms])))
      (alter-meta! probe dissoc :seon.test/long :seon.test/long-ms)
      (alter-meta! namespace-object assoc :seon.test/long "Namespace-scoped bounded work"
                   :seon.test/long-ms long-limit)
      (is (empty? (#'runner/duration-failures options probe elapsed ordinary)))
      (finally (remove-ns namespace-name)))))

(deftest overrun-is-captured-and-reported-as-an-assertion-failure
  (let [options (#'runner/report-options)
        limit (:seon.test/time-limit-ms options)
        v #'overrun-is-captured-and-reported-as-an-assertion-failure
        sym (symbol v)
        capture (atom {::runner/order [] ::runner/results {}})
        counters (ref test/*initial-report-counters*)
        report (fn [event]
                 (#'runner/capture-and-report-event!
                  options capture #{'seon.test.duration-test}
                  (fn [_]) (atom #{}) event))
        output
        (with-out-str
          (binding [test/*test-out* *out* test/*report-counters* counters
                    test/*testing-vars* [v]]
            (report {:type :begin-test-var :var v})
            (report {:type :pass :var v})
            ;; Supply an already elapsed interval at the reporter boundary;
            ;; the regression never sleeps to consume the bound it verifies.
            (swap! capture assoc-in [::runner/results sym ::runner/started-nanos]
                   (- (System/nanoTime) (* (inc limit) 1000000)))
            (report {:type :end-test-var :var v})))
        result (first (#'runner/captured-results @capture))
        failure (first (:seon.test.failure/reports result))]
    (is (= 1 (:fail @counters)))
    (is (= 1 (:seon.test/fail-count result)))
    (is (zero? (:seon.test/error-count result)))
    (is (= :fail (:seon.test.failure/type failure)))
    (is (= {:seon.test/time-limit-ms limit}
           (edn/read-string (:seon.test.failure/expected failure))))
    (is (> (:seon.test/elapsed-ms (edn/read-string (:seon.test.failure/actual failure))) limit))
    (is (.contains output "exceeded its declared duration"))
    (is (not (contains? result ::runner/started-nanos)))))
