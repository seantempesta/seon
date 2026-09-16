(require 'seon.test-support 'seon.cluster.turn-test 'seon.operator
         'seon.db 'seon.schema 'seon.fn 'seon.test 'seon.test.runner
         'seon.config)

(defn turn-reds-run
  "Run named tests serially on fresh canonical branches and SCI contexts."
  [label test-names]
  (let [connection (seon.operator/connection "turn-test-reds")
        database (seon.db/db connection)]
    (seon.schema/call-with-projection
     (seon.db/carried-projection database)
     (fn []
       (let [manifest (seon.fn/build-manifest
                       {:seon.fn/roots ["/Users/sean/src/seon/tmp/turn-test-reds-wt/src" "/Users/sean/src/seon/tmp/turn-test-reds-wt/test"]})
             base (with-redefs-fn
                    {#'seon.test-support/source-manifest (delay manifest)}
                    #(#'seon.test-support/create-base nil))]
         (try
           (with-redefs-fn
             {(ns-resolve 'seon.test-support 'database-base) (delay base)}
             (fn []
               (mapv
                (fn [test-name]
                  (let [result
                        (seon.test/run
                         (ns-resolve 'seon.cluster.turn-test (symbol test-name))
                         connection
                         {:seon.test.run/provenance
                          (seon.test.runner/provenance (seon.db/db connection))
                          :seon.test/remaining-ms
                          (:seon.test/check-time-limit-ms
                           (seon.config/effective (seon.db/db connection) "turn-test-reds"))})]
                    (spit (str "/Users/sean/src/seon/tmp/turn-test-reds/" label "-" test-name ".edn")
                          (pr-str result))
                    (select-keys result [:seon.test/sym :seon.test/pass-count
                                         :seon.test/fail-count :seon.test/error-count
                                         :seon.test/run])))
                test-names)))
           (finally (#'seon.test-support/close-base! base))))))))

(comment
  ;; Default's read-only after-edit seam probe (returned true/true in 4 ms).
  (let [database (seon.db/db (seon.operator/connection "default"))
        identity [:seon.test/sym "my.agents.turn-reds/never-declared"]]
    {:turn-test-reds/absent? (nil? (seon.db/pull database '[*] identity))
     :turn-test-reds/deleted?
     (seon.sci.eval/committed-row?
      database
      {:seon.program/delete-identities [identity]
       :seon.program/source "(ns-unmap 'my.agents.turn-reds 'never-declared)"})})

  ;; Scope this observer only around the isolated snapshot's serial test run.
  ;; The real declaration-diff function runs; no result is substituted.
  (let [observations (atom [])
        original @#'seon.turn/schema-attribute-change-tx]
    (try
      (with-redefs-fn
        {#'seon.turn/schema-attribute-change-tx
         (fn [database current candidate]
           (let [tx (original database current candidate)
                 k :shared.runtime/unregister-me]
             (swap! observations conj
                    {:turn-test-reds/current-form
                     (get (:seon.schema.projection/forms current) k)
                     :turn-test-reds/candidate-form
                     (get (:seon.schema.projection/forms candidate) k)
                     :turn-test-reds/stored-form
                     (seon.db/pull database [:seon.schema/form] [:seon.schema/key k])
                     :turn-test-reds/installed (get (:schema database) k)
                     :turn-test-reds/tx-data tx})
             tx))}
        #(turn-reds-run
          "unregister-trace"
          ["runtime-schema-unregister-removes-one-unused-global-schema"]))
      (finally
        (spit "tmp/turn-test-reds/unregister-trace.edn"
              (pr-str @observations))))))
