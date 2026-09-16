(ns config-apply-cost-probe-2026-09-16
  (:require [seon.config]
            [seon.db]
            [seon.operator]
            [seon.reconcile]
            [seon.schema]))

;; Run with load-file in default's JVM. This reads one immutable database;
;; neither alternative transacts or changes a Var. It compares the old
;; projection-rebuilding plan with the carried-projection plan on exact inputs.
(let [database (seon.db/db (seon.operator/connection "default"))
      projection (seon.db/carried-projection database)
      compiled (seon.config/compile-manifest
                {:seon.config/manifest
                 (seon.config/effective database "default")})
      desired (into [(:seon.config/desired-row compiled)]
                    (:seon.config/initialization compiled))
      forms (:seon.schema.projection/forms projection)
      identities (into #{}
                       (keep #(#'seon.config/row-identity forms %))
                       desired)
      request {:seon.reconcile/desired desired
               :seon.reconcile/process seon.config/managing-process-identity
               :seon.reconcile/adopt-identities identities}
      measure (fn [operation]
                (let [started (System/nanoTime)
                      operations (operation)]
                  {:seon.probe/ms (/ (- (System/nanoTime) started) 1e6)
                   :seon.probe/operations operations}))
      rebuilt (mapv
               (fn [_]
                 (measure
                  #(let [rebuilt (seon.schema/projection-from-database database)]
                     (seon.schema/call-with-projection
                      rebuilt
                      (fn []
                        (#'seon.reconcile/plan-transaction-data
                         (:seon.schema.projection/forms rebuilt)
                         database request))))))
               (range 3))
      carried (mapv (fn [_] (measure #(seon.reconcile/plan database request)))
                    (range 3))
      plans (map :seon.probe/operations (into rebuilt carried))]
  {:seon.probe/basis-t (:max-tx database)
   :seon.probe/equal-plans? (apply = plans)
   :seon.probe/rebuilt-ms (mapv :seon.probe/ms rebuilt)
   :seon.probe/carried-ms (mapv :seon.probe/ms carried)
   :seon.probe/operation-counts (mapv count plans)})
