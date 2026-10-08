;; Copy this file to test/seon/pass_waste_measure_test.clj.
;; Parent: bin/seon check test/seon/pass_waste_measure_test.clj --test seon.pass-waste-measure-test
;; Change: add src/seon/schema/datahike.clj to the same command's paths.
;; The intentional assertion failure returns measured DATA through the runner's
;; value printer. It is an evidence probe, not a correctness regression.
(ns seon.pass-waste-measure-test
  (:require [clojure.test :refer [deftest is]]
            [seon.pass-probe :as probe]
            [seon.test-support :as support]))
(deftest measure
  (support/with-running-agent
   (fn [running _ _]
     (let [records (probe/run-mcp running "sci" 2 #{1 2} true)]
       (is (= :measurement
              (mapv #(select-keys % [::probe/kind ::probe/wall-ms ::probe/allocated-bytes
                                    ::probe/checked-calls ::probe/own-commits ::probe/store-bytes
                                    ::probe/complete? ::probe/error?]) records)))))))
