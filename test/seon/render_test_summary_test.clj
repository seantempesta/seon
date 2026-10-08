(ns seon.render-test-summary-test
  "A finished test run renders as a short data summary."
  (:require [clojure.test :refer [deftest is]]
            [seon.render.test :as render.test]
            [seon.test-runner-failure-fixture]
            [seon.test.runner :as runner]))

(defn- fixture-run
  "The failure fixture's members run by the one capture owner, shaped as a run result."
  {:malli/schema [:=> [:cat] :map]}
  []
  (let [writer (java.io.StringWriter.)
        vars (->> (ns-interns 'seon.test-runner-failure-fixture)
                  vals (filter (comp :test meta)) (sort-by symbol) vec)]
    {:seon.test.run/id "fixture"
     :seon.test/results (binding [clojure.test/*test-out* writer] (runner/run-vars! vars {}))}))

(deftest a-failing-test-renders-its-summary-as-data
  (let [view (render.test/render-run-result-ai {:seon.render/value (fixture-run)})
        failure (->> (:seon.render.test/failures view)
                     (filter (comp #{'seon.test-runner-failure-fixture/failing-example} :seon.test/sym)) first)]
    (is (pos? (:seon.render.test/fail view)))
    (is (pos? (:seon.render.test/pass view)))
    (is (some? failure))
    (is (string? (:seon.test.failure/expected failure)))))

(deftest a-passing-run-renders-counts-only
  (let [run (update (fixture-run) :seon.test/results
                    #(filterv (comp #{'seon.test-runner-failure-fixture/passing-example} :seon.test/sym) %))
        view (render.test/render-run-result-ai {:seon.render/value run})]
    (is (= 0 (:seon.render.test/fail view)))
    (is (not (contains? view :seon.render.test/failures)))))
