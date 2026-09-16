(require 'clojure.string 'seon.test-support 'seon.cluster.turn-test 'seon.operator
         'seon.db 'seon.schema 'seon.fn 'seon.test 'seon.test.runner
         'seon.config)

(defn reds19-run
  "Run named tests serially on fresh canonical branches and SCI contexts."
  [label test-names]
  (let [connection (seon.operator/connection "turn-test-reds19")
        database (seon.db/db connection)]
    (seon.schema/call-with-projection
     (seon.db/carried-projection database)
     (fn []
       (let [manifest (seon.fn/build-manifest
                       {:seon.fn/roots ["/Users/sean/src/seon/tmp/turn-test-reds19-wt/src" "/Users/sean/src/seon/tmp/turn-test-reds19-wt/test"]})
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
                         (requiring-resolve (symbol test-name))
                         connection
                         {:seon.test.run/provenance
                          (seon.test.runner/provenance (seon.db/db connection))
                          :seon.test/remaining-ms
                          (:seon.test/check-time-limit-ms
                           (seon.config/effective (seon.db/db connection) "turn-test-reds19"))})]
                    (spit (str "/Users/sean/src/seon/tmp/turn-test-reds19/" label "-" (clojure.string/replace test-name "/" "--") ".edn")
                          (pr-str result))
                    (select-keys result [:seon.test/sym :seon.test/pass-count
                                         :seon.test/fail-count :seon.test/error-count
                                         :seon.test/run])))
                test-names)))
           (finally (#'seon.test-support/close-base! base))))))))


; Baseline test identities from the batch-19 report.
(def batch19-tests
 ["seon.cluster.turn-test/refused-terminal-program-transactions-settle-and-do-not-refire" "seon.cluster.turn-test/delimiter-repair-is-span-local-and-precedes-intent" "seon.cluster.turn-test/a-run-prompts-from-its-opening-database-value" "seon.cluster.turn-test/runtime-schema-key-changes-pass-the-one-usage-guarded-decision" "seon.cluster.turn-test/turn-intent-is-the-complete-crash-falsifier" "seon.cluster.turn-test/a-prompt-refusal-is-a-recorded-error-value-never-a-throw" "seon.cluster.turn-test/generated-fixed-point-closes-the-run" "seon.cluster.turn-test/generated-membership-failure-never-advances-the-run-to-call" "seon.cluster.turn-test/generated-phase-failures-converge-through-one-terminal-exit" "seon.cluster.turn-test/streaming-writes-zero-datoms-test" "seon.cluster.turn-test/a-lost-model-call-leaves-a-durable-readable-reason" "seon.cluster.turn-test/a-partial-stream-truncation-is-a-durable-nonfailure-attempt-fact" "seon.cluster.turn-test/a-refused-definition-stays-in-its-agents-defs" "seon.cluster.turn-test/an-unpaid-failure-with-a-backup-makes-exactly-two-calls" "seon.cluster.turn-test/concurrent-streams-share-one-conn-test" "seon.cluster.turn-test/generated-model-attempt-traces-preserve-presence-and-episode-laws" "seon.cluster.turn-test/reasoning-only-time-limit-persists-its-flat-diagnostic" "seon.cluster.turn-test/runtime-schema-unregister-removes-one-unused-global-schema" "seon.sci.eval-test/one-unloadable-row-cannot-prevent-cold-acquisition"])

; Read-only default program-row probe, 2026-09-16 03:43 UTC.
(comment
  (let [connection (seon.operator/connection "default")
        database (seon.db/db connection)
        source (:seon.fn/source
                (seon.db/pull database [:seon.fn/source]
                              [:seon.fn/sym "seon.turn/record-attempt!"]))]
    {:cluster (seon.db/pull database [:seon.source/commit-id]
                            [:seon.cluster/name "default"])
     :recording-call? (clojure.string/includes? source "error/recording")
     :prepared-fact-key? (clojure.string/includes? source ":seon.error/fact")}))
