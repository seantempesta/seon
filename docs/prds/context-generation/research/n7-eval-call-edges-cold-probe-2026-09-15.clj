;; MCP JVM mode on default; no test JVM. Prepare a detached worktree at
;; commit 171c0c193 under tmp/n7-cold-wt and link its reference-code directory.
;; Create tmp/n7-cold for output. Load this file through the MCP tool, then
;; inspect (deref n7-cold-task 1000 :pending) until completion within the
;; declared preparation/test bounds. Each seon.test/run records in default.
(require 'seon.operator 'seon.db 'seon.schema 'seon.fn 'seon.config
         'seon.test 'seon.test.runner 'seon.test-support
         'seon.cluster.turn-test 'seon.fn-test)

(def n7-cold-task
  (future
    (let [conn (seon.operator/connection "default")
          projection (seon.db/carried-projection (seon.db/db conn))]
      (seon.schema/call-with-projection
       projection
       (fn []
         (let [manifest (seon.fn/build-manifest
                         {:seon.fn/roots ["tmp/n7-cold-wt/src" "tmp/n7-cold-wt/test"]})
               base (with-redefs-fn {#'seon.test-support/source-manifest (delay manifest)}
                      #(#'seon.test-support/create-base nil))]
           (try
             (with-redefs-fn
               {(ns-resolve 'seon.test-support 'database-base) (delay base)}
               (fn []
                 (mapv
                  (fn [v]
                    (let [r (seon.test/run
                             v conn
                             {:seon.test.run/provenance
                              (seon.test.runner/provenance (seon.db/db conn))
                              :seon.test/remaining-ms
                              (:seon.test/check-time-limit-ms
                               (seon.config/effective (seon.db/db conn) "default"))})]
                      (spit (str "tmp/n7-cold/final-" (:name (meta v)) ".edn") (pr-str r))
                      (select-keys r [:seon.test/sym :seon.test/pass-count :seon.test/fail-count :seon.test/error-count :seon.test/run])))
                  [#'seon.cluster.turn-test/a-completing-disposition-closes-in-the-terminal-transaction
                   #'seon.cluster.turn-test/a-waiting-disposition-frees-the-agent-and-keeps-its-note
                   #'seon.cluster.turn-test/a-batched-turn-commits-only-queryable-definition-facts
                   #'seon.fn-test/agent-source-reaches-the-evaluator-through-one-visible-path])))
             (finally (#'seon.test-support/close-base! base)))))))))
