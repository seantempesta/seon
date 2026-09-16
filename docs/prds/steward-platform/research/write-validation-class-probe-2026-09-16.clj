; Dated replay through MCP jvm mode on default; no test JVM or process operation.
; Evaluate each form separately. The tests run serially, under the real runner.
(require 'seon.db 'seon.operator 'seon.test 'seon.test-support
         'seon.transact-feedback-test 'seon.test-support-test
         'seon.render.transcript-test 'seon.turn-loop-test)

(let [database (seon.db/db (seon.operator/connection "default"))
      projection (#'seon.db/carried-projection database)]
  {:seon.write-probe/refusals
   (mapv (fn [row]
           (select-keys (#'seon.db/write-error database projection [row])
                        [:seon.error/kind :seon.error/message :seon.db/path]))
         [{:seon.problems/id "x"}
          {:seon.turn/_attempts 1}
          {:seon.cluster.eval/id "incomplete"}
          {:seon.turn/id "gauge-run"}
          {:seon.error/id (apply str (repeat 64 "a"))
           :seon.error/signature (apply str (repeat 64 "a"))
           :seon.error/kind :seon.error/unclassified}])})

(seon.test-support/with-database
 (fn [connection]
   (select-keys (#'seon.render.transcript-test/seed-populated-history! connection)
                [:seon.error/kind :seon.error/message :seon.db/path])))

; Dated subjects from this assignment, not a maintained production roster.
(doseq [test-symbol '[seon.transact-feedback-test/raw-write-maps-select-only-their-asserted-required-identity
                     seon.test-support-test/fixture-setup-refusals-stop-before-the-body
                     seon.render.transcript-test/about-identity-resolution-pulls-one-deterministic-ordered-id-vector
                     seon.render.transcript-test/malformed-receipt-bytes-and-any-unique-about-stay-replayable
                     seon.render.transcript-test/the-history-query-bounds-what-the-transcript-pulls
                     seon.render.transcript-test/historical-shown-text-keeps-its-original-elision
                     seon.render.transcript-test/supersession-chains-vanish-from-the-history
                     seon.render.transcript-test/durable-history-entries-never-invent-executions
                     seon.render.transcript-test/same-instant-bootstrap-prefix-and-newest-tail-preserve-plan-order
                     seon.render.transcript-test/selected-evaluations-project-only-their-stored-source-and-result
                     seon.render.transcript-test/the-transcript-is-whole-and-the-ai-boundary-elides-it
                     seon.render.transcript-test/error-receipt-without-triage-has-an-execution-error-face
                     seon.render.transcript-test/selected-run-keeps-status-outside-agent-visible-text
                     seon.render.transcript-test/receipt-content-enters-the-shared-capped-floor
                     seon.render.transcript-test/populated-history-restores-the-repl-fidelity-checklist
                     seon.render.transcript-test/stored-evaluations-are-terminal-transcript-values
                     seon.render.transcript-test/history-unit-derives-both-projections-from-one-bounded-derivation
                     seon.render.transcript-test/admitted-top-level-string-is-terminal-text
                     seon.render.transcript-test/one-reply-reads-identically-on-the-page-in-history-and-in-the-prompt
                     seon.render.transcript-test/reasoning-is-html-only-and-inline-blob-history-has-one-disclosure
                     seon.turn-loop-test/attempt-settlement-updates-the-registered-model-gauges
                     seon.turn-loop-test/a-refused-terminal-commit-still-closes-the-run
                     seon.turn-loop-test/kill-positions-per-agent-test]]
  (let [result (seon.test/run (resolve test-symbol)
                              (seon.operator/connection "default"))]
    (prn (select-keys result [:seon.test/sym :seon.test/pass-count
                             :seon.test/fail-count :seon.test/error-count
                             :seon.test/run :seon.test/run-basis-t]))))
