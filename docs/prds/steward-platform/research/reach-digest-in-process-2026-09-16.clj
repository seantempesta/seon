; Dated REPL evidence. Evaluate individual comment forms through MCP jvm mode.
; These reproduce the measured captures; loading this file runs no tests.
; The filename is the assigned dated research convention, not a classpath name.
#_{:clj-kondo/ignore [:namespace-name-mismatch]}
(ns reach-digest-in-process-2026-09-16
  (:require [seon.config] [seon.db] [seon.instrument] [seon.operator]
            [seon.schema] [seon.test] [seon.test.runner]
            [seon.test-reaching-test] [seon.test-support]))

(comment
  ; The canonical fixture's retained delay had failed in default. This
  ; acquisition uses its existing owner and is closed explicitly below.
  (def reach-fresh-base
    (seon.schema/call-with-projection
      (seon.db/carried-projection (seon.db/db (seon.operator/connection "default")))
      #(#'seon.test-support/create-base nil)))

  ; Earlier candidate-contract probes used this exact scoped arming setup.
  ; Substitute only the named regression Var; the landing note records each run.
  (do
 (def reach-last-result
  (seon.test-support/preserving-instrumentation-state
   (fn []
    (let [d (seon.db/db (:seon.test-support/connection reach-fresh-base))
          p (get-in reach-fresh-base [:seon.sci.eval/ctx :seon.schema/projection])
          effective (seon.config/effective (seon.db/db (seon.operator/connection "default")) "default")]
     (doseq [v [#'seon.error/prepare #'seon.test/run #'seon.test/reach-digest #'seon.test/stale
                        #'seon.test/verified? #'seon.test/check #'seon.test/feedback
                        #'seon.test.runner/reach-digests #'seon.test.runner/run-var!
                        #'seon.test.runner/record-tx #'seon.test.runner/commit-results!]]
       (#'seon.instrument/arm-var! v (:malli/schema (meta v)) p
                                   (seon.config/result-caps effective)))
     (with-redefs-fn {#'seon.test-support/database-base (delay reach-fresh-base)}
       #(seon.test/run #'seon.test-reaching-test/unchanged-closures-reuse-green-results
                       (seon.operator/connection "default")
                       {:seon.db/db (seon.db/db (seon.operator/connection "default"))
                        :seon.test.run/provenance (seon.test.runner/provenance (seon.db/db (seon.operator/connection "default")))
                        :seon.test/remaining-ms 120000}))))))
 (pr-str (dissoc reach-last-result :seon.test/failure-message :seon.error/data)))

  ; Final post-edit invocation: only owned functions are armed here.
  (do
 (let [p (get-in reach-fresh-base [:seon.sci.eval/ctx :seon.schema/projection])
       connection (seon.operator/connection "default")
       database (seon.db/db connection)
       effective (seon.config/effective database "default")]
   (doseq [v [#'seon.test/run #'seon.test/reach-digest #'seon.test/stale
              #'seon.test/verified? #'seon.test/check #'seon.test/feedback
              #'seon.test.runner/reach-digests #'seon.test.runner/run-var!
              #'seon.test.runner/record-tx #'seon.test.runner/commit-results!]]
     (#'seon.instrument/arm-var! v (:malli/schema (meta v)) p
                                 (seon.config/result-caps effective)))
   (def reach-last-result
     (with-redefs-fn {#'seon.test-support/database-base (delay reach-fresh-base)}
       #(seon.test/run #'seon.test-reaching-test/fixture-state-observation-is-total
                       connection
                       {:seon.db/db database
                        :seon.test.run/provenance (seon.test.runner/provenance database)
                        :seon.test/remaining-ms 120000}))))
 (pr-str (dissoc reach-last-result :seon.test/failure-message :seon.error/data)))

  ; Cold and same-value measurements, taken consecutively.
  (do
  (def reach-measured-db (seon.db/db (seon.operator/connection "default")))
  (def reach-measured-symbols
    (vec (sort (seon.db/q '[:find [?s ...]
                            :where [?t :seon.test/sym ?s] [?t :seon.test/source]]
                          reach-measured-db))))
  (alter-meta! (:seon.sci.eval/projection-state (meta reach-measured-db))
               dissoc :seon.test.runner/reach-index)
  (let [started (System/nanoTime)
        digests (seon.test.runner/reach-digests reach-measured-db reach-measured-symbols)]
    (pr-str {:tests (count reach-measured-symbols)
             :digests (count digests)
             :elapsed-ms (/ (- (System/nanoTime) started) 1e6)})))
  (let [started (System/nanoTime)
      digests (seon.test.runner/reach-digests reach-measured-db reach-measured-symbols)
      index (:seon.test.runner/reach-index
              (meta (:seon.sci.eval/projection-state (meta reach-measured-db))))]
  (pr-str {:elapsed-ms (/ (- (System/nanoTime) started) 1e6)
           :digests (count digests) :computed (:seon.test.runner/reach-computed index)}))

  ; One changed function source, all digests compared on a canonical branch.
  (do
 (def reach-incremental-measurement
  (with-redefs-fn {#'seon.test-support/database-base (delay reach-fresh-base)}
   (fn [] (seon.test-support/with-database
     (fn [connection]
       (let [database (seon.db/db connection)
             symbols (vec (sort (seon.db/q '[:find [?s ...] :where [?t :seon.test/sym ?s] [?t :seon.test/source]] database)))
             before (seon.test.runner/reach-digests database symbols)
             function-row (seon.db/pull database [:db/id :seon.fn/source] [:seon.fn/sym "seon.id/valid?"])
             index (:seon.test.runner/reach-index (meta (:seon.sci.eval/projection-state (meta database))))
             expected (into #{} (keep (fn [[s entry]] (when (contains? (:seon.test.runner/reach-dependencies entry) (:db/id function-row)) s))) (:seon.test.runner/reach-digests index))
             tx (seon.db/transact! connection [[:db/add [:seon.fn/sym "seon.id/valid?"] :seon.fn/source (str (:seon.fn/source function-row) "\n")]])
             changed (seon.db/db connection)
             started (System/nanoTime)
             after (seon.test.runner/reach-digests changed symbols)
             elapsed (/ (- (System/nanoTime) started) 1e6)
             actual (into #{} (filter #(not= (get before %) (get after %))) symbols)
             index (:seon.test.runner/reach-index (meta (:seon.sci.eval/projection-state (meta changed))))]
         {:tests (count symbols) :expected (vec (sort expected)) :changed (vec (sort actual))
          :elapsed-ms elapsed :updated (:seon.test.runner/reach-updated index)
          :computed (:seon.test.runner/reach-computed index)
          :invalidated (:seon.test.runner/reach-invalidated index)
          :transaction-committed (boolean (:db-after tx))}))))))
 (pr-str reach-incremental-measurement))

  ; Live unchanged checks after the explicit seon.id-test run recorded green.
  (with-redefs-fn {#'seon.test-support/database-base (delay reach-fresh-base)}
    #(seon.test/check {:seon.db/connection (seon.operator/connection "default")
                       :seon.test/namespaces ['seon.id-test]}))

  ; Close only the base this probe acquired, never the shared fixture delay.
  (#'seon.test-support/close-base! reach-fresh-base))
