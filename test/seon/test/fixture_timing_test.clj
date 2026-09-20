(ns seon.test.fixture-timing-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.cluster.export :as export]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as support]))

(deftest ^{:seon.test/fixture-observation
           "Measures first fixture acquisition in a fresh armed JVM, before any other database fixture. Cold workers already acquire their base before readiness. Run: bin/test-fast seon.test.fixture-timing-test"}
  published-fixture-acquisition-is-bounded
  (let [phases (atom {})
        calls (atom {})
        projections (atom [])
        absent-before-acquisition (atom [])
        population (atom {})
        observations [#'schema/projection-from-database #'sci.eval/cluster-ctx
                      #'schema/build-projection #'schema/projection-from-rows
                      #'schema/projection-registry #'d/q
                      #'schema/projection-rows #'schema/projection-admissions
                      #'schema/validate-contracts!
                      #'schema/assert-render-contracts! #'schema/shape-row-in
                      #'schema/canonical-reference-graph
                      #'schema/assert-acyclic-references!
                      #'schema/projection-fingerprint #'schema/bound-forms
                      #'schema/assert-config-display!
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
                                         (swap! population merge
                                                 {:schemas (count (first args))
                                                  :contracts (count (second args))}))
                                       (when (= v #'schema/projection-registry)
                                         (swap! population assoc :retained-schemas
                                                (count (nth args 3 {}))))
                                       (try (apply original args)
                                            (finally
                                              (swap! phases update (symbol v)
                                                     (fnil + 0.0)
                                                     (/ (- (System/nanoTime) started) 1e6))))))])))
                       observations)
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
        p50 (nth subsequent 4)]
    (println "FIXTURE ACQUISITION" {:first-ms (first samples)
                                   :subsequent-p50-ms p50
                                   :phase-ms @phases
                                   :samples-ms samples})
    (println "PROJECTION ACQUISITION"
             {:calls @calls :population @population
              :absent-before-acquisition @absent-before-acquisition
              :read-rows-ms (+ (get @phases 'seon.schema/projection-rows 0.0)
                              (get @phases 'seon.schema/projection-admissions 0.0))
              :projection-ms (get @phases 'seon.schema/projection-from-database)})
    (is (= 1 (get @calls 'seon.schema/projection-from-database)))
    (is (= [true] @absent-before-acquisition))
    (is (every? #(identical? (first @projections) %) @projections))
    (is (<= (get @phases 'seon.schema/projection-from-database) 1000.0))
    (is (<= (first samples) 2000.0))
    (is (<= p50 100.0))))
