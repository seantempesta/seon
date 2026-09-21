(ns seon.sci.lazy-acquisition-test
  (:require [clojure.test :refer [deftest is]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.sci.eval :as evaluation]
            [seon.sci.kernel :as kernel]
            [seon.test-support :as support]))

(deftest ^{:seon.test/long "Two complete SCI acquisitions from the canonical published program verify first use and database replacement."
           :seon.test/long-ms 30000}
  cluster-program-is-acquired-on-first-use-and-reused-for-that-database
  (support/with-database
   (fn [connection]
     (let [database (db/db connection)
           ctx (evaluation/cluster-ctx database connection)
           fork (:seon.sci.eval/ctx
                 (evaluation/fork-for-turn {:seon.sci.eval/ctx ctx
                                            :seon.db/db database
                                            :seon.agent/id "lazy-acquisition"}))
           request {:seon.sci.eval/ctx fork
                    :seon.sci.admit/caps (config/result-caps config/defaults)
                    :seon.sci.eval/time-limit-ms 10000
                    :seon.config/on-core-error :panic
                    :seon.cluster.eval/source "(+ 1 2)"}]
       (is (empty? @(::kernel/installed-functions ctx)))
       (is (empty? @(::kernel/installed-functions fork)))
       (is (nil? (:seon.db/db (evaluation/acquired-program ctx))))
       (is (= 3 (:seon.sci.admit/value (evaluation/evaluate request))))
       (is (seq @(::kernel/installed-functions ctx)))
       (let [acquired @(::kernel/program-snapshot ctx)
             environment @(:env ctx)]
         (is (= (db/committed-value-identity database)
                (db/committed-value-identity (:seon.db/db acquired))))
         (evaluation/acquire! {:seon.sci.eval/ctx ctx :seon.db/db (db/db connection)})
         (is (identical? acquired @(::kernel/program-snapshot ctx)))
         (is (identical? environment @(:env ctx))))
       (support/seed-cluster! connection "lazy-acquisition")
       (let [changed (db/db connection)
             previous @(::kernel/program-snapshot ctx)]
         (is (= 3 (:seon.sci.admit/value (evaluation/evaluate request))))
         (is (not (identical? previous @(::kernel/program-snapshot ctx))))
         (is (= (db/committed-value-identity changed)
                (db/committed-value-identity
                 (:seon.db/db (evaluation/acquired-program ctx))))))))))
