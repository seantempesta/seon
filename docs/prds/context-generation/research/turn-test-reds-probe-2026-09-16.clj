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
