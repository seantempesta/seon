(ns seon.stale-caller-probe-test
  "Copy to test/seon/stale_caller_probe_test.clj and run
  `bin/seon check [src/seon/sci/eval.clj] test/seon/stale_caller_probe_test.clj --test seon.stale-caller-probe-test --include-long`:
  sixteen measured MCP SCI passes (ordinary, defn, the pass after it), shown as the failing assertion's actual value."
  (:require [clojure.test :refer [deftest is]]
            [seon.pass-probe :as probe]
            [seon.test-support :as support]))

(deftest ^{:seon.test/long "measurement" :seon.test/long-ms 120000} measure
  (support/with-running-agent
   (fn [running _ _]
     (let [records (probe/run-mcp running "sci" 8 #{2 4 6 8} true)]
       (is (= :show (mapv #(select-keys % [::probe/kind ::probe/wall-ms ::probe/allocated-bytes
                                            ::probe/checked-calls ::probe/attempts ::probe/error?])
                           records)))))))
