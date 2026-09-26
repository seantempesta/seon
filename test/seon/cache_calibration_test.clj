(ns seon.cache-calibration-test
  "Each process-cache kind's charge stays within a stated ratio of the retention
  measured offline (bench/seon/measure_cache_kinds.clj, one kind per fresh JVM;
  never measured on default). A changed weigh factor, or a re-measurement the
  factor no longer fits, fails here: the byte dial means bytes."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [seon.cache]
            [seon.config]
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
    :seon.schema/declaration-predicates (* schema/declaration-predicates-bytes-per-entry n)
    :seon.program/authored-shapes (* program/shape-bytes-per-family n)))

(deftest every-kind-is-charged-what-it-was-measured-to-retain
  (let [kinds (:seon.measure/kinds (measurement))]
    (is (= #{:seon.schema.edn/packaged-population :seon.schema/declaration-projection
             :seon.schema/projection :seon.schema/projection-with-contracts
             :seon.schema/declaration-predicates :seon.program/authored-shapes}
           (set (keys kinds)))
        "every weighed schema-family kind has a measurement")
    (doseq [[kind row] kinds]
      (testing kind
        (let [ratio (/ (double (charge kind row)) (:retained-bytes row))]
          (is (<= (/ 1 tolerance) ratio tolerance)
              (str kind " charges " (charge kind row) " B for " (:retained-bytes row)
                   " B measured (ratio " ratio ")")))))))

(deftest the-packaged-declaration-projection-is-one-entry
  ;; Config read the packaged declaration projection through a kind of its own,
  ;; so one object was held, and charged, twice (~9 MB). It reads the
  ;; declaration-projection kind; only the cheap predicates alias sits beside it.
  (let [forms (schema.edn/packaged-forms)
        projection (schema/declaration-projection forms)]
    (is (identical? projection (#'seon.config/declared-projection)))
    (is (identical? projection (get (seon.cache/retained :seon.schema/declaration-projection)
                                    [forms (get (seon.cache/retained :seon.schema/declaration-predicates) forms)])))
    (is (empty? (seon.cache/retained :seon.config/declared-projection)))))
