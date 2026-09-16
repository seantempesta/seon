;; Load this file through default's JVM REPL after verifying adoption.
;; Poll reach-closure-regression-done in later short MCP calls.
;; Canonical tests run serially on a daemon; no test JVM is launched.
(require 'seon.config 'seon.db 'seon.instrument 'seon.operator
         'seon.schema 'seon.test 'seon.test.runner)

(def reach-closure-regression-results (atom []))
(def reach-closure-regression-done (promise))
(doto
  (Thread.
    (bound-fn []
      (try
        (#'seon.test/with-test-loader
         #(doseq [namespace-name '[seon.test-support seon.program-test
                                  seon.test-failure-facts-test
                                  seon.test-support-test seon.cluster.turn-test]]
            (require namespace-name :reload)))
        ;; First construction is outside both the test loader and any test bound.
        (let [database (seon.db/db (seon.operator/connection "default"))
              base (seon.schema/call-with-projection
                    (seon.schema/projection-from-database database)
                    #(deref (var-get (find-var 'seon.test-support/database-base))))]
          (when (:seon.error/kind base)
            (throw (ex-info "Canonical base construction refused." base)))
          (let [projection (seon.schema/projection-from-database database)]
            (seon.schema/call-with-projection
             projection
             #(seon.instrument/apply!
               {:seon.schema/projection projection
                :seon.config/on-core-error
                (:seon.config/on-core-error
                 (seon.config/effective database "default"))}))))
        (doseq [test-symbol
                '[seon.program-test/runtime-deletion-preserves-identity-through-tuple-retractions
                  seon.test-failure-facts-test/failure-readers-use-the-structured-claims
                  seon.test-support-test/failed-base-construction-retries-without-caller-interruption
                  seon.cluster.turn-test/ns-unmap-retracts-the-owned-function-after-the-terminal-commit
                  seon.cluster.turn-test/qualified-dynamic-ns-unmap-is-durable-in-a-fresh-context]]
          (let [connection (seon.operator/connection "default")
                database (seon.db/db connection)
                started (System/nanoTime)
                result (seon.test/run
                        (#'seon.test/resolve-test test-symbol) connection
                        {:seon.db/db database
                         :seon.test.run/provenance (seon.test.runner/provenance database)
                         :seon.test/remaining-ms 100000})]
            (swap! reach-closure-regression-results conj
                   {:seon.test/sym (str test-symbol)
                    :seon.test/elapsed-ms (quot (- (System/nanoTime) started) 1000000)
                    :seon.test/result result})
            (when-not (and (pos? (get result :seon.test/pass-count 0))
                           (zero? (get result :seon.test/fail-count 1))
                           (zero? (get result :seon.test/error-count 1)))
              (throw (ex-info "Canonical regression did not pass." result)))))
        (deliver reach-closure-regression-done :complete)
        (catch Throwable failure
          (deliver reach-closure-regression-done
                   {:seon.error/message (ex-message failure)
                    :seon.error/data (ex-data failure)}))))
    "reach-closure-regressions")
  (.setDaemon true)
  (.start))
