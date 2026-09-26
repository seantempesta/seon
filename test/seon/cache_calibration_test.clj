(ns seon.cache-calibration-test
  "Each process-cache kind's charge stays within a stated ratio of the retention
  measured offline (bench/seon/measure_cache_kinds.clj, one kind per fresh JVM;
  never measured on default). A changed weigh factor, or a re-measurement the
  factor no longer fits, fails here: the byte dial means bytes."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [seon.fs :as fs]
            [seon.program :as program]
            [seon.schema :as schema]
            [seon.schema.edn :as schema.edn]))

(def ^:private tolerance
  "The charge may differ from the measured retention by at most 25% either way:
  two runs of one kind differed by 2.3% (the file's :repeats)."
  1.25)

(defn- measurement []
  (edn/read-string (slurp (io/file (fs/source-directory) "bench/seon/cache_kind_measurement.edn"))))

(defn- charge
  "What the kind's weigher charges for the measured counts."
  [kind {n :count contracts :contracts}]
  (case kind
    :seon.schema.edn/packaged-population (* schema.edn/population-bytes-per-form n)
    :seon.schema/declaration-projection (* schema/declaration-projection-bytes-per-form n)
    :seon.schema/projection (* schema/projection-bytes-per-form n)
    :seon.schema/projection-with-contracts
    (+ (* schema/projection-bytes-per-form (- n contracts))
       (* schema/projection-bytes-per-contract contracts))
    :seon.program/authored-shapes (* program/shape-bytes-per-family n)))

(deftest every-kind-is-charged-what-it-was-measured-to-retain
  (let [kinds (:seon.measure/kinds (measurement))]
    (is (= #{:seon.schema.edn/packaged-population :seon.schema/declaration-projection
             :seon.schema/projection :seon.schema/projection-with-contracts
             :seon.program/authored-shapes}
           (set (keys kinds)))
        "every weighed schema-family kind has a measurement")
    (doseq [[kind row] kinds]
      (testing kind
        (let [ratio (/ (double (charge kind row)) (:retained-bytes row))]
          (is (<= (/ 1 tolerance) ratio tolerance)
              (str kind " charges " (charge kind row) " B for " (:retained-bytes row)
                   " B measured (ratio " ratio ")")))))))
