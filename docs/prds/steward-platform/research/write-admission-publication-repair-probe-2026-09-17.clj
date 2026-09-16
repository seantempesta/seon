; Evaluate these forms separately through MCP JVM on default. No publication,
; connection mutation, fixture-base access, or dependency reload occurs here.
(require '[datahike.api] '[seon.db] '[seon.fn] '[seon.operator]
         '[seon.schema] '[seon.schema.edn] '[seon.schema.datahike]
         '[seon.error.refusal] '[seon.program])

(def f2-publication-replay
  (future
    (let [database (datahike.api/branch-as-db
                    (seon.operator/connection "default") :current-src)
          projection (seon.schema/build-projection (seon.schema.edn/packaged-forms))
          request {:seon.fn/roots seon.fn/source-roots
                   :seon.schema.projection/forms
                   (:seon.schema.projection/forms projection)}]
      (seon.schema/call-with-projection
       projection
       (fn []
         (let [manifest (seon.fn/build-manifest request)
               rows (#'seon.fn/desired-rows
                     (assoc request :seon.fn/manifest manifest) (constantly nil))
               transaction (seon.fn/reconcile-tx database rows [])
               report (datahike.api/with
                       database
                       {:tx-data (seon.schema.datahike/encode-transaction-in
                                  projection transaction)
                        :tx-meta {:datahike/validate-report
                                  (#'seon.db/write-report-validator projection)}})]
           {:seon.probe/rows (count rows)
            :seon.probe/operations (count transaction)
            :seon.probe/datoms (count (:tx-data report))
            :seon.probe/before (:max-tx database)
            :seon.probe/after (:max-tx (:db-after report))
            :seon.probe/accepted true}))))))

(if (realized? f2-publication-replay) @f2-publication-replay :seon.probe/running)

; Remove only after recording the complete result.
(when (realized? f2-publication-replay) (ns-unmap 'user 'f2-publication-replay))

; Replay adoption including removed identities, still without writing either branch.
(def f2-adoption-replay
  (future
    (let [connection (seon.operator/connection "default")
          database (seon.db/db connection)
          source (datahike.api/branch-as-db connection :current-src)
          projection (seon.schema/projection-from-database source)]
      (seon.schema/call-with-projection
       projection
       (fn []
         (let [rows (#'seon.fn/published-index-rows source)
               previous (vec (for [attribute seon.program/identity-attributes
                                   value (seon.db/q
                                          '[:find [?v ...] :in $ ?a
                                            :where [?e ?a ?v]
                                            [?e :seon.schema.admission/source :core]]
                                          database attribute)]
                               [attribute value]))
               transaction (seon.fn/reconcile-tx database rows previous)
               report (datahike.api/with
                       database
                       {:tx-data (seon.schema.datahike/encode-transaction-in
                                  projection transaction)
                        :tx-meta {:datahike/validate-report
                                  (#'seon.db/write-report-validator projection)}})]
           {:seon.probe/rows (count rows)
            :seon.probe/operations (count transaction)
            :seon.probe/datoms (count (:tx-data report))
            :seon.probe/accepted true}))))))

(if (realized? f2-adoption-replay) @f2-adoption-replay :seon.probe/running)
(when (realized? f2-adoption-replay) (ns-unmap 'user 'f2-adoption-replay))
