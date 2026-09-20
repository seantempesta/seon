(ns seon.test.fixture-timing-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [malli.core :as m]
            [malli.registry :as mr]
            [seon.cluster.export :as export]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as support]))

(deftest ^{:seon.test/long "Measures the first worker projection acquisition plus ten branch acquisitions; observed 6.874 s with phase observations on 2026-09-23. The first-use target remains an independent assertion."
           :seon.test/long-ms 10000}
  published-fixture-acquisition-is-bounded
  (let [phases (atom {})
        calls (atom {})
        projections (atom [])
        registry-depth (atom 0)
        compile-depth (atom 0)
        compile-ms (atom 0.0)
        seals (atom [])
        tables (atom [])
        registry-results (atom [])
        absent-before-acquisition (atom [])
        population (atom {})
        observations [#'schema/projection-from-database #'sci.eval/cluster-ctx
                      #'schema/build-projection #'schema/projection-from-rows
                      #'schema/projection-registry #'d/q
                      #'support/clone-directory! #'export/reidentify!
                      #'d/connect #'d/branch! #'d/release #'d/delete-branch!
                      #'db/carry-connection-projection-state!
                      #'sci.eval/projection-state]
        wrappers (into {}
                       (map (fn [v]
                              (let [original @v]
                                [v (fn [& args]
                                     (let [started (System/nanoTime)]
                                       (swap! calls update (symbol v) (fnil inc 0))
                                       (when (= v #'schema/projection-from-database)
                                         (swap! absent-before-acquisition conj
                                                (nil? (db/carried-projection (first args)))))
                                       (when (= v #'schema/build-projection)
                                         (reset! population
                                                 {:schemas (count (first args))
                                                  :contracts (count (second args))}))
                                       (when (= v #'schema/projection-registry)
                                         (swap! registry-depth inc))
                                       (try (let [result (apply original args)]
                                              (when (= v #'schema/projection-registry)
                                                (swap! registry-results conj result))
                                              result)
                                            (finally
                                              (when (= v #'schema/projection-registry)
                                                (swap! registry-depth dec))
                                              (swap! phases update (symbol v)
                                                     (fnil + 0.0)
                                                     (/ (- (System/nanoTime) started) 1e6))))))])))
                       observations)
        wrappers
        (merge wrappers
               (into {}
                     (map (fn [v]
                            (let [original @v]
                              [v (fn [& args]
                                   (if (pos? @registry-depth)
                                     (let [outer? (= 1 (swap! compile-depth inc))
                                           started (System/nanoTime)]
                                       (try (apply original args)
                                            (finally
                                              (swap! compile-depth dec)
                                              (when outer?
                                                (swap! compile-ms +
                                                       (/ (- (System/nanoTime) started) 1e6))))))
                                     (apply original args)))])))
                     [#'m/schema #'m/function-schema])
               {#'mr/schemas
                (let [original mr/schemas]
                  (fn [registry]
                    (let [started (System/nanoTime)
                          result (original registry)]
                      (when (pos? @registry-depth)
                        (swap! tables conj [result (/ (- (System/nanoTime) started) 1e6)]))
                      result)))
                #'mr/fast-registry
                (let [original mr/fast-registry]
                  (fn [table]
                    (let [started (System/nanoTime)
                          result (original table)]
                      (when (pos? @registry-depth)
                        (swap! seals conj [result table (/ (- (System/nanoTime) started) 1e6)]))
                      result)))})
        samples
        (with-redefs-fn wrappers
          (fn []
            (mapv (fn [_]
                    (let [started (System/nanoTime)]
                      (support/with-database
                        (fn [connection]
                          (let [projection (db/carried-projection (db/db connection))]
                            (swap! projections conj projection)
                            (is (some? projection)))))
                      (/ (- (System/nanoTime) started) 1e6)))
                  (range 11))))
        subsequent (sort (subvec samples 1))
        p50 (nth subsequent 4)
        seal-ms
        (reduce + 0.0
                (for [[result table elapsed] @seals
                      :when (some #(identical? result %) @registry-results)]
                  (+ elapsed (reduce + 0.0 (for [[value elapsed] @tables
                                                :when (identical? value table)] elapsed)))))]
    (println "FIXTURE ACQUISITION" {:first-ms (first samples)
                                   :subsequent-p50-ms p50
                                   :phase-ms @phases
                                   :samples-ms samples})
    (println "PROJECTION ACQUISITION"
             {:calls @calls :population @population
              :absent-before-acquisition @absent-before-acquisition
              :read-rows-ms (get @phases 'datahike.api/q 0.0)
              :compile-ms @compile-ms :seal-ms seal-ms
              :other-projection-work-ms
              (- (get @phases 'seon.schema/projection-from-database 0.0)
                 (get @phases 'datahike.api/q 0.0) @compile-ms seal-ms)})
    (is (= 1 (get @calls 'seon.schema/projection-from-database)))
    (is (= [true] @absent-before-acquisition))
    (is (every? #(identical? (first @projections) %) @projections))
    (is (<= (first samples) 2000.0))
    (is (<= p50 100.0))))
