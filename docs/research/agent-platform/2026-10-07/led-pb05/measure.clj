;; Copy this file to test/seon/pb05_measure_test.clj.
;; Parent: bin/seon check test/seon/pb05_measure_test.clj --test seon.pb05-measure-test
;; Change: add src/seon/schema.clj to the same command's paths.
;; The intentional assertion failure returns measured DATA through the runner's
;; value printer. It is an evidence probe, not a correctness regression.
(ns seon.pb05-measure-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.db]
            [seon.pass-probe :as probe]
            [seon.schema :as schema]
            [seon.test-support :as support]))

(defn- allocation
  "Median wall ms and allocated MB of five calls of `f` on this thread."
  [f]
  (let [mx (java.lang.management.ManagementFactory/getThreadMXBean)
        id (.getId (Thread/currentThread))
        one (fn [] (let [b0 (.getThreadAllocatedBytes mx id) t0 (System/nanoTime)]
                     (f)
                     [(/ (- (System/nanoTime) t0) 1e6) (/ (- (.getThreadAllocatedBytes mx id) b0) 1e6)]))
        samples (sort (repeatedly 5 one))]
    {:ms (first (nth samples 2)) :mb (second (nth samples 2))}))

(deftest measure
  (support/with-running-agent
   (fn [running _ connection]
     (let [records (probe/run-mcp running "sci" 3 #{1 2 3} true)
           db0 (d/db connection)
           base (seon.db/carried-projection db0)
           db1 (:db-after (d/with db0 [{:seon.fn/sym 'pb05.probe/f :seon.fn/spec "[:=> [:cat :int] :int]"}]))]
       (is (= :measurement
              {:passes (mapv #(select-keys % [::probe/kind ::probe/wall-ms ::probe/allocated-bytes
                                             ::probe/checked-calls ::probe/own-commits ::probe/complete? ::probe/error?])
                             records)
               :load-projection-one-contract (allocation #(schema/load-projection db1 base))
               :declarations [(count (:seon.schema.projection/forms base))
                              (count (:seon.schema.projection/function-contracts base))]}))))))
