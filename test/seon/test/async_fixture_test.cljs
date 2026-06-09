(ns seon.test.async-fixture-test
  "Verifier probe 2+3: does run-vars await async :once :before and :each :after fixture bodies?"
  (:require [cljs.test :as t :refer [deftest is async]]
            [seon.test.runner :as r]
            [seon.test.async-fixture-probes :as probes]))

(deftest async-fixture-before-and-after-are-awaited
  (async done
    (reset! probes/lifecycle [])
    (reset! probes/armed? true)
    (-> (r/run! {:seon.test.runner/ns 'seon.test.async-fixture-probes})
        (.then
          (fn [result]
            (let [seq @probes/lifecycle
                  summary (:seon.test.runner/summary result)]
              (is (= 0 (:error summary))
                  (str "no errors; summary=" (pr-str summary)))
              (is (= 0 (:fail summary))
                  (str "no fails — async :once :before and :each :after must be awaited; lifecycle="
                       (pr-str seq)))
              ;; Exact lifecycle: once-before-async, then for each probe:
              ;; each-before, body, each-after-async, then once-after
              (is (= [:once-before-async-resolved
                      :each-before :probe-a-body :each-after-async-resolved
                      :each-before :probe-b-body :each-after-async-resolved
                      :once-after]
                     seq)
                  (str "exact lifecycle order wrong; got=" (pr-str seq))))
            (reset! probes/armed? false)
            (done)))
        (.catch (fn [e]
                  (reset! probes/armed? false)
                  (is false (str "threw — " e))
                  (done))))))
