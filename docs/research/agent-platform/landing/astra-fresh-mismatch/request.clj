(let [handle (:seon.turn.loop/cluster (get @seon.operator.runtime/running-instances "default"))
      observation (volatile! nil)
      result (seon.test/run
               {:seon.test/execution handle
                :seon.test/recording-connection (:seon.db/connection handle)
                :seon.test/policy :named :seon.test/namespaces #{'seon.error.refusal-test}
                :seon.db/writer
                (fn [connection tx]
                  (let [report (seon.db/transact! connection tx)]
                    (vreset! observation
                      {:report (if (:db-after report) :admitted report)
                       :basis (get-in (nth (first tx) 2) [:seon.test.run/provenance :seon.test.run/basis-t])
                       :now (seon.db/basis-t (seon.db/db connection))
                       :changes (seon.db/q '[:find ?e ?a :where [?e ?a]]
                                  (seon.db/since (seon.db/history (seon.db/db connection))
                                    (get-in (nth (first tx) 2) [:seon.test.run/provenance :seon.test.run/basis-t])))})
                    {:seon.error/at (java.util.Date.) :seon.error/layer :seon.test/admission
                     :seon.error/operation 'seon.test/admit-run
                     :seon.test/admission-refusal :probe/stop
                     :seon.error/message "Probe stops before all test bodies."}))})]
  {:observation @observation :result result})
