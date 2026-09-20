(ns seon.test.fixture-timing-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.cluster.export :as export]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.sci.eval :as sci.eval]
            [seon.test-support :as support]))

(deftest published-fixture-acquisition-is-bounded
  (let [phases (atom {})
        observations [#'schema/projection-from-database #'sci.eval/cluster-ctx
                      #'support/clone-directory! #'export/reidentify!
                      #'d/connect #'d/branch! #'d/release #'d/delete-branch!
                      #'db/carry-connection-projection-state!
                      #'sci.eval/projection-state]
        wrappers (into {}
                       (map (fn [v]
                              (let [original @v]
                                [v (fn [& args]
                                     (let [started (System/nanoTime)]
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
                      (is (some? (db/carried-projection (db/db connection))))))
                  (/ (- (System/nanoTime) started) 1e6)))
              (range 11))))
        subsequent (sort (subvec samples 1))
        p50 (nth subsequent 4)]
    (println "FIXTURE ACQUISITION" {:first-ms (first samples)
                                     :subsequent-p50-ms p50
                                     :phase-ms @phases
                                     :samples-ms samples})
    (is (<= (first samples) 2000.0))
    (is (<= p50 100.0))))
