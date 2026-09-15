; Evaluate through MCP JVM after loading seon.test-reaching-test.
; This creates and closes a fresh private canonical base and SCI context,
; on a plain virtual thread without inheriting the operator REPL bindings.
(require '[clojure.test]
         '[seon.db]
         '[seon.schema]
         '[seon.sci.eval]
         '[seon.test]
         '[seon.test.arm]
         '[seon.test.runner]
         '[seon.test-reaching-test]
         '[seon.test-support])

(let [projection (#'seon.test.arm/packaged-test-projection "nested-probe")
      task
      (java.util.concurrent.FutureTask.
       ^java.util.concurrent.Callable
       (fn []
         (seon.schema/call-with-projection
          projection
          (fn []
            (let [base (#'seon.test-support/create-base
                        "target/test-published-bases/368dc5a6094e5093f70f2183b471a5418dab5b37aa422ebf9adf102682f075da/base")
                  connection (:seon.test-support/connection base)
                  state (seon.sci.eval/projection-state
                         @connection (:seon.schema/projection (:seon.sci.eval/ctx base)))]
              (try
                (#'seon.test-support/run-database-body
                 connection state []
                 (fn [connection]
                   (let [counters (ref clojure.test/*initial-report-counters*)
                         output (java.io.StringWriter.)
                         results
                         (binding [clojure.test/*report-counters* counters
                                   clojure.test/*test-out* output
                                   clojure.test/report (constantly nil)]
                           (mapv
                            (fn [[body ms]]
                              (#'seon.test-reaching-test/with-test
                               connection body
                               (fn [_ v]
                                 (seon.test/run
                                  v connection
                                  {:seon.test.run/provenance
                                   (seon.test.runner/provenance (seon.db/db connection))
                                   :seon.test/remaining-ms ms}))))
                            [['(clojure.test/is false "expected red reaching probe") 2000]
                             ['(.await (java.util.concurrent.CountDownLatch. 1)) 50]]))]
                     {:outer-counters @counters :inner-results results :output (str output)})))
                (finally (#'seon.test-support/close-base! base))))))))]
  (.start (Thread/ofVirtual) ^Runnable task)
  (seon.test-support/await-event! task :test-runner-waste/nested-probe))
