(ns seon.dev.heap-options-test
  (:require [clojure.test :refer [deftest is]])
  (:import [com.sun.management HotSpotDiagnosticMXBean]
           [java.lang.management ManagementFactory]))

(deftest periodic-collection-bounds-unused-heap
  (let [options (ManagementFactory/getPlatformMXBean HotSpotDiagnosticMXBean)
        option #(Long/parseLong (.getValue (.getVMOption options %)))
        minimum (option "MinHeapFreeRatio")
        maximum (option "MaxHeapFreeRatio")]
    (is (pos? (option "G1PeriodicGCInterval")))
    (is (<= minimum 20))
    (is (<= minimum maximum 40))))
