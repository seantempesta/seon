; Evaluate each form separately through MCP jvm on default. No source reload
; or fixture acquisition outside seon.test/run. The admission and identity forms are reads;
; the test form runs and records the existing canonical regression.
(require '[seon.operator] '[seon.db] '[seon.schema]
         '[seon.test] '[seon.test.runner])

(let [connection (seon.operator/connection "default")
      database (seon.db/db connection)
      projection (seon.schema/projection-from-database database)
      row {:seon.schedule/id "root/maintenance/footprint-schedule"
           :seon.schedule/expression "7 4 * * *"}]
  {:f2/map-refusal (#'seon.db/write-error database projection [row])
   :f2/datom-refusal
   (#'seon.db/write-error database projection
    [[:db/add -1 :seon.schedule/id "f2-incomplete"]])
   :f2/call-refusal
   (#'seon.db/write-error database projection
    [[:db.fn/call (fn [_] [{:seon.schedule/id "f2-incomplete"}])]])
   :f2/existing
   (seon.db/pull database '[*]
                 [:seon.schedule/id "root/maintenance/footprint-schedule"])})

(let [database (seon.db/db (seon.operator/connection "default"))]
  (seon.db/q '[:find ?sym :where [?e :seon.fn/sym ?sym]
              (not [?e :seon.fn/ns _])]
             database))

(do
  (#'seon.test/with-test-loader (fn [] (require 'seon.db-test :reload)))
  (def f2-baseline-run
    (future
      (let [connection (seon.operator/connection "default")
            database (seon.db/db connection)]
        (seon.test/run
         (#'seon.test/resolve-test
          'seon.db-test/transaction-wrappers-cannot-hide-a-classified-refusal)
         connection
         {:seon.test/remaining-ms 180000
          :seon.test.run/provenance (seon.test.runner/provenance database)}))))
  :f2/started)

(if (realized? f2-baseline-run) @f2-baseline-run :f2/running)

; Run only after recording the completed result.
(when (realized? f2-baseline-run) (ns-unmap 'user 'f2-baseline-run))
